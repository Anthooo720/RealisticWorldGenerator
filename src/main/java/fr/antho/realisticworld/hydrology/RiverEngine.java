package fr.antho.realisticworld.hydrology;

import fr.antho.realisticworld.config.WorldGenConfig;
import fr.antho.realisticworld.noise.SimplexNoise;
import fr.antho.realisticworld.terrain.TerrainEngine;
import fr.antho.realisticworld.util.MathUtil;

/**
 * Hydrologie v5 : le D8 fournit la topologie et le débit, mais le rendu du chenal est
 * construit comme une vraie coupe de rivière. Les méandres déplacent l'axe dans sa
 * normale (pas un warp XY arbitraire), le lit est en U, les berges sont raccordées au
 * terrain par une pente progressive et les grands cours possèdent une plaine alluviale
 * très légèrement nivelée. Le résultat évite l'effet "tranchée creusée" des v1.5/v1.6.
 */
public final class RiverEngine {
    private final TerrainEngine terrain;
    private final WatershedEngine watersheds;
    private final WorldGenConfig.Rivers cfg;
    private final WorldGenConfig.Ocean ocean;
    private final SimplexNoise widthNoise;
    private final SimplexNoise edgeNoise;
    private final SimplexNoise braidNoise;
    private final SimplexNoise bankNoise;
    private final SimplexNoise floodNoise;

    public RiverEngine(long seed, TerrainEngine terrain, WatershedEngine watersheds, WorldGenConfig.Rivers cfg,
                       WorldGenConfig.Ocean ocean, int ignoredCacheTiles) {
        this.terrain=terrain; this.watersheds=watersheds; this.cfg=cfg; this.ocean=ocean;
        this.widthNoise=new SimplexNoise(seed ^ 0x5249563557494454L);
        this.edgeNoise=new SimplexNoise(seed ^ 0x5249563545444745L);
        this.braidNoise=new SimplexNoise(seed ^ 0x5249563542524149L);
        this.bankNoise=new SimplexNoise(seed ^ 0x5249563542414E4BL);
        this.floodNoise=new SimplexNoise(seed ^ 0x52495635464C4F4FL);
    }

    public RiverSample sample(double x,double z) {
        if(!cfg.enabled()) return RiverSample.NONE;

        // WatershedEngine fournit déjà un axe D8 lissé/déplacé en continu. RiverEngine ne
        // re-warp plus les coordonnées : il se concentre sur la coupe, les berges et la largeur.
        WatershedEngine.HydroSample h=watersheds.sample(x,z);
        if(h.channelAccumulation()<=0 || !Double.isFinite(h.channelWaterSurface())) return RiverSample.NONE;

        double natural=terrain.heightWithoutRivers(x,z);
        if(natural<=terrain.seaLevel()-1) return RiverSample.NONE;

        double q=Math.max(1,h.channelAccumulation());
        double normalized=normalizeDischarge(q);
        double grade=MathUtil.clamp(h.channelGrade(),0,1.5);
        double calm=1.0-MathUtil.smoothstep(0.040,0.18,grade);

        double width=cfg.minWidth()+(cfg.maxWidth()-cfg.minWidth())*Math.pow(normalized,1.06);
        double widthVar=widthNoise.sample(x*0.00115,z*0.00115)*0.5+0.5;
        width*=0.82+widthVar*0.34;
        width*=0.60+calm*0.40;
        double coastProximity=1.0-MathUtil.smoothstep(2.0,Math.max(8.0,cfg.coastalMergeHeight()+8.0),natural-terrain.seaLevel());
        if(normalized>0.32 && grade<0.075) width*=1.0+coastProximity*ocean.estuaryStrength()*0.78;
        width=MathUtil.clamp(width,cfg.minWidth(),cfg.maxWidth()*(1.0+ocean.estuaryStrength()*0.24));

        double signed=Double.isFinite(h.channelSignedDistance())?h.channelSignedDistance():h.channelDistance();
        if(!Double.isFinite(signed)) return RiverSample.NONE;

        double half=Math.max(0.72,width*0.5);
        // Irrégularité à plus grande longueur d'onde que la v1.6 : les berges ondulent,
        // elles ne deviennent pas un zigzag bloc par bloc.
        double edge=(edgeNoise.sample(x*0.0042,z*0.0042)*0.76
                +edgeNoise.sample(x*0.0105-19,z*0.0105+37)*0.24)
                * cfg.edgeRoughness()*(0.32+half*0.12);
        double mainDistance=Math.max(0.0,Math.abs(signed)-edge);

        // Bras secondaire : réservé aux cours réellement matures, larges et presque plats.
        double braidField=braidNoise.sample(x*0.00082+71,z*0.00082-41)*0.5+0.5;
        double braidActivation=MathUtil.smootherstep(1.0-cfg.secondaryChannelFrequency(),0.98,braidField)
                *MathUtil.smootherstep(0.64,0.90,normalized)*MathUtil.smootherstep(0.45,0.92,calm);
        double sideSign=braidNoise.sample(x*0.0017-17,z*0.0017+83)>=0?1.0:-1.0;
        double sideOffset=sideSign*(half*1.55+1.8+normalized*2.4);
        double sideDistance=Math.abs(signed-sideOffset);
        double sideHalf=Math.max(0.58,half*(0.25+normalized*0.14));

        double effectiveDistance=mainDistance;
        boolean sideActive=braidActivation>0.10 && sideDistance<mainDistance;
        if(braidActivation>0.10) effectiveDistance=Math.min(effectiveDistance,sideDistance);

        double wetHalf=Math.max(0.68,half*(0.82+normalized*0.10));
        double activeWetHalf=sideActive?sideHalf:wetHalf;
        double activeChannelDistance=sideActive?sideDistance:mainDistance;
        double bankWidth=Math.max(1.2,cfg.bankSlopeWidth()*(0.74+normalized*0.42));
        double bankHalf=wetHalf+bankWidth;
        double floodHalf=bankHalf+cfg.floodplainWidth()*(0.38+normalized*1.10)*calm;
        if(effectiveDistance>floodHalf+2.0) return RiverSample.NONE;

        double wet=1.0-MathUtil.smootherstep(Math.max(0.24,wetHalf*0.78),wetHalf+0.12,mainDistance);
        if(braidActivation>0.10) {
            double sideWet=(1.0-MathUtil.smootherstep(sideHalf*0.72,sideHalf+0.12,sideDistance))*braidActivation*0.82;
            wet=Math.max(wet,sideWet);
        }
        wet=MathUtil.clamp(wet,0,1);
        double bank=1.0-MathUtil.smootherstep(wetHalf,bankHalf,effectiveDistance);
        bank=MathUtil.clamp(bank,0,1)*(1.0-wet);
        double floodplain=1.0-MathUtil.smootherstep(bankHalf,floodHalf,effectiveDistance);
        floodplain=MathUtil.clamp(floodplain,0,1)*(1.0-wet)*(1.0-bank);

        double dirLen=Math.max(1.0e-6,Math.hypot(h.channelDirX(),h.channelDirZ()));
        double nx=-h.channelDirZ()/dirLen, nz=h.channelDirX()/dirLen;
        double outerProbe=Math.max(2.8,bankHalf+1.35);
        double bankA=terrain.heightWithoutRivers(x+nx*outerProbe,z+nz*outerProbe);
        double bankB=terrain.heightWithoutRivers(x-nx*outerProbe,z-nz*outerProbe);
        double farA=terrain.heightWithoutRivers(x+nx*(outerProbe+1.8),z+nz*(outerProbe+1.8));
        double farB=terrain.heightWithoutRivers(x-nx*(outerProbe+1.8),z-nz*(outerProbe+1.8));
        double bankCap=Math.floor(Math.min(Math.min(bankA,bankB),Math.min(farA,farB))-0.22);

        double waterSurface=Math.floor(h.channelWaterSurface()+1.0e-6);
        // Fusion littorale progressive : une rivière ne conserve jamais un palier haut juste
        // avant l'océan. Le plafond remonte lentement avec le terrain au lieu d'un seuil sec.
        double coastAbove=Math.max(0.0,natural-(terrain.seaLevel()+1.5));
        double coastalCeiling=terrain.seaLevel()+Math.min(
                coastAbove*MathUtil.clamp(cfg.coastalWaterGradient(),0.05,0.80),
                Math.max(1.0,cfg.coastalMergeHeight()*0.42));
        if(natural<=terrain.seaLevel()+cfg.coastalMergeHeight() || coastProximity>0.08)
            waterSurface=Math.min(waterSurface,Math.floor(coastalCeiling+1.0e-6));
        waterSurface=Math.min(waterSurface,bankCap);
        waterSurface=Math.min(waterSurface,Math.floor(natural-0.55));
        if(!Double.isFinite(waterSurface)) return RiverSample.NONE;

        if(grade>cfg.maxWetGrade()) {
            double torrentFactor=MathUtil.clamp(1.0-(grade-cfg.maxWetGrade())*2.2,0.34,0.84);
            wet*=torrentFactor;
        }

        // Lit en U avec fond réellement large. Dans les versions précédentes le profil restait
        // mathématiquement pointu : en voxel il devenait une tranchée. Ici la partie centrale
        // garde presque toute sa profondeur, puis remonte sur une longue épaule submergée.
        double waterDepth=0.82+cfg.maxWaterDepth()*(0.10+normalized*0.42)*(0.68+calm*0.32);
        waterDepth=MathUtil.clamp(waterDepth,0.78,cfg.maxWaterDepth());
        if(grade>cfg.waterfallGrade()) waterDepth=Math.min(waterDepth,1.18);
        double channelU=MathUtil.clamp(activeChannelDistance/Math.max(0.1,activeWetHalf),0,1);
        double flat=MathUtil.clamp(cfg.channelBedFlatness(),0.10,0.82);
        double shoulderU=MathUtil.clamp((channelU-flat)/Math.max(0.08,1.0-flat),0,1);
        double shoulder=1.0-Math.pow(MathUtil.smootherstep(0.0,1.0,shoulderU),Math.max(0.65,cfg.profileExponent()));
        double depthFraction=0.18+shoulder*0.82;
        double bedTexture=edgeNoise.sample(x*0.017+23,z*0.017-11)*0.16*(0.35+normalized*0.65);
        double targetBed=waterSurface-Math.max(0.48,waterDepth*depthFraction)+bedTexture;
        double channelCarve=MathUtil.clamp(Math.max(0,natural-targetBed),0,cfg.maxCarveDepth());

        // Raccord de berge : abaisse seulement le terrain trop haut vers une rampe qui part
        // environ un demi-bloc au-dessus de l'eau et rejoint naturellement le terrain sec.
        double bankU=MathUtil.clamp((effectiveDistance-activeWetHalf)/Math.max(0.5,bankWidth),0,1);
        double bankShape=1.0-MathUtil.smootherstep(0.0,1.0,bankU);
        double bankAsym=bankNoise.sample(x*0.0032,z*0.0032)*cfg.bankHeightJitter();
        double bankRise=Math.pow(bankU,1.32)*(1.65+normalized*0.95+coastProximity*0.55);
        double desiredBank=waterSurface+0.38+bankRise+bankAsym*(0.25+bankShape*0.75);
        double bankCut=Math.min(cfg.bankMaxCut(),Math.max(0,natural-desiredBank))*Math.pow(bankShape,0.82);
        // Un bloc de berge sec doit rester au moins au niveau de la source d'eau voisine.
        bankCut=Math.min(bankCut,Math.max(0,natural-(waterSurface+0.30)));

        // Plaine alluviale : nivellement très léger et uniquement pour les grands cours
        // calmes. On ne crée jamais une dalle plate ; un bruit lent conserve les levées,
        // anciens bras et micro-dépressions.
        double floodU=MathUtil.clamp((effectiveDistance-bankHalf)/Math.max(0.5,floodHalf-bankHalf),0,1);
        double floodShape=(1.0-MathUtil.smootherstep(0.0,1.0,floodU))*calm*MathUtil.smoothstep(0.24,0.70,normalized);
        double floodVariation=floodNoise.sample(x*0.00165,z*0.00165)*0.62;
        double desiredFlood=waterSurface+1.20+normalized*1.18+coastProximity*0.35+floodVariation;
        double floodCut=Math.min(cfg.floodplainMaxCut(),Math.max(0,natural-desiredFlood))*floodShape;

        double carve;
        if(wet>0.14) carve=channelCarve;
        else if(bank>0.02) carve=bankCut;
        else if(floodplain>0.02) carve=floodCut;
        else carve=0;

        double bedAfter=natural-carve;
        if(wet>0.14 && bedAfter>waterSurface-0.12) {
            wet=0;
            carve=Math.max(bankCut,floodCut);
        }

        boolean estuary=natural<=terrain.seaLevel()+cfg.coastalMergeHeight() && normalized>0.28 && grade<0.085;
        return new RiverSample(
                Math.max(wet,Math.max(bank*0.64,floodplain*0.20)),wet,bank,floodplain,carve,waterSurface,
                wetHalf*2.0,q,h.basinId(),effectiveDistance,grade,estuary,normalized
        );
    }

    private double normalizeDischarge(double q) {
        return MathUtil.clamp(Math.log1p(q)/Math.log1p(Math.max(8,cfg.accumulationThreshold()*26)),0,1);
    }

    public record RiverSample(double strength,double wetness,double bankStrength,double floodplainStrength,
                              double carveDepth,double waterSurface,double approximateWidth,double discharge,
                              long basinId,double distanceToChannel,double grade,boolean estuary,double maturity) {
        public static final RiverSample NONE=new RiverSample(0,0,0,0,0,Double.NEGATIVE_INFINITY,0,0,0,Double.POSITIVE_INFINITY,0,false,0);
        public boolean isRiver(){ return wetness>0.14; }
        public boolean isBank(){ return bankStrength>0.05&&!isRiver(); }
        public boolean isFloodplain(){ return floodplainStrength>0.08&&!isRiver()&&!isBank(); }
    }
}
