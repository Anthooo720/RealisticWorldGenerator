package fr.antho.realisticworld.hydrology;

import fr.antho.realisticworld.config.WorldGenConfig;
import fr.antho.realisticworld.noise.SimplexNoise;
import fr.antho.realisticworld.terrain.TerrainEngine;
import fr.antho.realisticworld.util.MathUtil;

/**
 * Coupe de rivière continue construite autour du réseau D8 lissé du WatershedEngine.
 * Le watershed possède la cote hydraulique ; ce moteur ne la re-plafonne jamais bloc par
 * bloc. Il transforme seulement débit/distance en largeur, lit en U, thalweg et berges.
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

        WatershedEngine.HydroSample h=watersheds.sample(x,z);
        if(h.channelAccumulation()<=0 || !Double.isFinite(h.channelWaterSurface())) return RiverSample.NONE;

        double natural=terrain.heightWithoutRivers(x,z);
        if(natural<=terrain.seaLevel()-1) return RiverSample.NONE;

        double q=Math.max(1,h.channelAccumulation());
        double normalized=normalizeDischarge(q);
        double grade=MathUtil.clamp(h.channelGrade(),0,1.5);
        double calm=1.0-MathUtil.smoothstep(0.035,0.18,grade);

        // Largeur pilotée par le débit, avec une variation lente pour éviter les rubans constants.
        double width=cfg.minWidth()+(cfg.maxWidth()-cfg.minWidth())*Math.pow(normalized,1.02);
        double widthVar=widthNoise.sample(x*0.00105,z*0.00105)*0.5+0.5;
        width*=0.84+widthVar*0.32;
        width*=0.66+calm*0.34;
        double coastProximity=1.0-MathUtil.smoothstep(2.0,Math.max(8.0,cfg.coastalMergeHeight()+8.0),natural-terrain.seaLevel());
        if(normalized>0.30 && grade<0.080) width*=1.0+coastProximity*ocean.estuaryStrength()*0.78;
        width=MathUtil.clamp(width,cfg.minWidth(),cfg.maxWidth()*(1.0+ocean.estuaryStrength()*0.24));

        double signed=Double.isFinite(h.channelSignedDistance())?h.channelSignedDistance():h.channelDistance();
        if(!Double.isFinite(signed)) return RiverSample.NONE;

        double half=Math.max(0.80,width*0.5);
        double edge=(edgeNoise.sample(x*0.0035,z*0.0035)*0.76
                +edgeNoise.sample(x*0.0085-19,z*0.0085+37)*0.24)
                *cfg.edgeRoughness()*(0.24+half*0.09);
        double mainDistance=Math.max(0.0,Math.abs(signed)-edge);

        // Bras secondaire seulement sur les grands cours calmes.
        double braidField=braidNoise.sample(x*0.00082+71,z*0.00082-41)*0.5+0.5;
        double braidActivation=MathUtil.smootherstep(1.0-cfg.secondaryChannelFrequency(),0.98,braidField)
                *MathUtil.smootherstep(0.64,0.90,normalized)*MathUtil.smootherstep(0.45,0.92,calm);
        double sideSign=braidNoise.sample(x*0.0017-17,z*0.0017+83)>=0?1.0:-1.0;
        double sideOffset=sideSign*(half*1.55+1.8+normalized*2.4);
        double sideDistance=Math.abs(signed-sideOffset);
        double sideHalf=Math.max(0.62,half*(0.25+normalized*0.14));

        double effectiveDistance=mainDistance;
        boolean sideActive=braidActivation>0.10 && sideDistance<mainDistance;
        if(braidActivation>0.10) effectiveDistance=Math.min(effectiveDistance,sideDistance);

        double wetHalf=Math.max(0.74,half*(0.84+normalized*0.10));
        double activeWetHalf=sideActive?sideHalf:wetHalf;
        double activeDistance=sideActive?sideDistance:mainDistance;
        double bankWidth=Math.max(2.2,cfg.bankSlopeWidth()*(0.92+normalized*0.48));
        double bankHalf=wetHalf+bankWidth;
        double floodHalf=bankHalf+cfg.floodplainWidth()*(0.34+normalized*1.12)*calm;
        if(effectiveDistance>floodHalf+2.0) return RiverSample.NONE;

        // Bord humide étalé sur presque un bloc afin d'éviter un seuil sec/eau brutal.
        double wet=1.0-MathUtil.smootherstep(Math.max(0.30,wetHalf*0.80),wetHalf+0.28,mainDistance);
        if(braidActivation>0.10) {
            double sideWet=(1.0-MathUtil.smootherstep(sideHalf*0.72,sideHalf+0.24,sideDistance))*braidActivation*0.82;
            wet=Math.max(wet,sideWet);
        }
        wet=MathUtil.clamp(wet,0,1);

        double bank=1.0-MathUtil.smootherstep(wetHalf,bankHalf,effectiveDistance);
        bank=MathUtil.clamp(bank,0,1)*(1.0-wet);
        double floodplain=1.0-MathUtil.smootherstep(bankHalf,floodHalf,effectiveDistance);
        floodplain=MathUtil.clamp(floodplain,0,1)*(1.0-wet)*(1.0-bank);

        // La cote vient exclusivement du watershed. Aucun floor/plafond côtier transversal ici.
        double waterSurface=h.channelWaterSurface();
        if(!Double.isFinite(waterSurface)) return RiverSample.NONE;

        // Vérification de confinement sans déplacer l'eau : si les berges réelles sont trop
        // basses localement, on assèche progressivement la colonne au lieu de créer une fuite.
        double dirLen=Math.max(1.0e-6,Math.hypot(h.channelDirX(),h.channelDirZ()));
        double nx=-h.channelDirZ()/dirLen, nz=h.channelDirX()/dirLen;
        double outerProbe=Math.max(3.0,bankHalf+1.5);
        double bankA=terrain.heightWithoutRivers(x+nx*outerProbe,z+nz*outerProbe);
        double bankB=terrain.heightWithoutRivers(x-nx*outerProbe,z-nz*outerProbe);
        double containmentCeiling=Math.min(bankA,bankB)-cfg.bankBuffer()*0.35;
        if(waterSurface>containmentCeiling) {
            double contain=MathUtil.clamp(1.0-(waterSurface-containmentCeiling)/1.8,0,1);
            wet*=contain;
        }

        // Les fortes pentes deviennent torrents/ravins : pas une source d'eau à chaque marche.
        if(grade>cfg.waterfallGrade()) {
            double torrent=1.0-MathUtil.smootherstep(cfg.waterfallGrade(),Math.max(cfg.waterfallGrade()+0.01,cfg.maxWetGrade()),grade);
            wet*=0.18+0.82*torrent;
        }
        if(grade>cfg.maxWetGrade()) wet*=0.10;

        // Profondeur cohérente avec le débit. Le thalweg est légèrement décentré et varie
        // lentement, donnant une sensation de courant sans transformer le lit en zigzag.
        double waterDepth=0.90+cfg.maxWaterDepth()*(0.12+normalized*0.46)*(0.72+calm*0.28);
        waterDepth=MathUtil.clamp(waterDepth,0.82,cfg.maxWaterDepth());
        if(grade>cfg.waterfallGrade()) waterDepth=Math.min(waterDepth,1.24);

        double lateral=sideActive?signed-sideOffset:signed;
        double thalwegShift=bankNoise.sample(x*0.0022+47,z*0.0022-31)
                *MathUtil.clamp(cfg.thalwegOffset(),0.0,0.36)*activeWetHalf;
        double channelU=MathUtil.clamp(Math.abs(lateral-thalwegShift)/Math.max(0.1,activeWetHalf),0,1);
        double flat=MathUtil.clamp(cfg.channelBedFlatness(),0.10,0.82);
        double shoulderU=MathUtil.clamp((channelU-flat)/Math.max(0.08,1.0-flat),0,1);
        double shoulder=Math.pow(MathUtil.smootherstep(0.0,1.0,shoulderU),Math.max(0.70,cfg.profileExponent()));
        double localDepth=0.42+(waterDepth-0.42)*(1.0-shoulder*0.82);
        double bedTexture=edgeNoise.sample(x*0.014+23,z*0.014-11)*0.13*(0.35+normalized*0.65);
        double targetBed=waterSurface-localDepth+bedTexture;
        double channelCarve=MathUtil.clamp(Math.max(0,natural-targetBed),0,cfg.maxCarveDepth());

        // Berge continue : au bord de l'eau elle vise ~0.4 bloc au-dessus de la nappe,
        // puis rejoint progressivement LE terrain naturel de la colonne à l'extérieur.
        double bankU=MathUtil.clamp((effectiveDistance-activeWetHalf)/Math.max(0.5,bankWidth),0,1);
        double transition=Math.pow(MathUtil.smootherstep(0.0,1.0,bankU),
                MathUtil.clamp(cfg.bankTransitionPower(),0.65,2.4));
        double bankAsym=bankNoise.sample(x*0.0030,z*0.0030)*cfg.bankHeightJitter();
        double edgeBank=waterSurface+0.38+bankAsym*0.22;
        double desiredBank=MathUtil.lerp(edgeBank,natural,transition);
        double bankCut=Math.min(cfg.bankMaxCut(),Math.max(0,natural-desiredBank));
        bankCut*=MathUtil.smootherstep(0.0,0.92,bank);

        // Plaine alluviale très légère, réservée aux cours matures et calmes.
        double floodU=MathUtil.clamp((effectiveDistance-bankHalf)/Math.max(0.5,floodHalf-bankHalf),0,1);
        double floodShape=(1.0-MathUtil.smootherstep(0.0,1.0,floodU))*calm*MathUtil.smoothstep(0.24,0.70,normalized);
        double floodVariation=floodNoise.sample(x*0.00155,z*0.00155)*0.58;
        double desiredFlood=waterSurface+1.35+normalized*1.10+coastProximity*0.28+floodVariation;
        double floodCut=Math.min(cfg.floodplainMaxCut(),Math.max(0,natural-desiredFlood))*floodShape;

        // Assemblage sans seuil discret entre lit/berge/plaine.
        double channelWeight=MathUtil.smootherstep(0.04,0.36,wet);
        double carve=Math.max(channelCarve*channelWeight,Math.max(bankCut,floodCut));

        double bedAfter=natural-carve;
        if(wet>0.14 && bedAfter>waterSurface-0.10) wet=0;

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
