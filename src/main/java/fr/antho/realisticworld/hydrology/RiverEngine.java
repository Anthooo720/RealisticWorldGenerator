package fr.antho.realisticworld.hydrology;

import fr.antho.realisticworld.config.WorldGenConfig;
import fr.antho.realisticworld.noise.SimplexNoise;
import fr.antho.realisticworld.terrain.TerrainEngine;
import fr.antho.realisticworld.util.MathUtil;

/**
 * Hydrologie v4 : le réseau D8 fournit uniquement la topologie. Le tracé visible est
 * déformé par un champ de coordonnées continu afin de casser les diagonales/segments
 * droits, puis reçoit un profil transversal asymétrique, des plages discontinues et,
 * sur les grands cours calmes, un bras secondaire occasionnel.
 */
public final class RiverEngine {
    private final TerrainEngine terrain;
    private final WatershedEngine watersheds;
    private final WorldGenConfig.Rivers cfg;
    private final WorldGenConfig.Ocean ocean;
    private final SimplexNoise warpXNoise;
    private final SimplexNoise warpZNoise;
    private final SimplexNoise widthNoise;
    private final SimplexNoise edgeNoise;
    private final SimplexNoise braidNoise;
    private final SimplexNoise bankNoise;

    public RiverEngine(long seed, TerrainEngine terrain, WatershedEngine watersheds, WorldGenConfig.Rivers cfg,
                       WorldGenConfig.Ocean ocean, int ignoredCacheTiles) {
        this.terrain=terrain; this.watersheds=watersheds; this.cfg=cfg; this.ocean=ocean;
        this.warpXNoise=new SimplexNoise(seed ^ 0x5249563457415258L);
        this.warpZNoise=new SimplexNoise(seed ^ 0x524956345741525AL);
        this.widthNoise=new SimplexNoise(seed ^ 0x5249563457494454L);
        this.edgeNoise=new SimplexNoise(seed ^ 0x5249563445444745L);
        this.braidNoise=new SimplexNoise(seed ^ 0x5249563442524149L);
        this.bankNoise=new SimplexNoise(seed ^ 0x5249563442414E4BL);
    }

    public RiverSample sample(double x,double z) {
        if(!cfg.enabled()) return RiverSample.NONE;

        WatershedEngine.HydroSample h0=watersheds.sample(x,z);
        if(h0.channelAccumulation()<=0 || !Double.isFinite(h0.channelWaterSurface())) return RiverSample.NONE;

        double q0=Math.max(1,h0.channelAccumulation());
        double n0=normalizeDischarge(q0);
        double g0=MathUtil.clamp(h0.channelGrade(),0,1.5);
        double calm0=1.0-MathUtil.smoothstep(0.045,0.18,g0);

        // Déformation de coordonnées, et non simple décalage du centre : les anciens
        // segments D8 droits deviennent réellement courbes dans l'espace du monde.
        double warpScale=Math.max(0.00025,cfg.meanderScale()*0.62);
        double warpAmp=(1.4 + 10.5*Math.pow(n0,1.18))*cfg.meanderStrength()*(0.34+0.66*calm0);
        warpAmp=MathUtil.clamp(warpAmp,0.8,12.5);
        double wx=x + (warpXNoise.sample(x*warpScale,z*warpScale)*0.78
                + warpXNoise.sample(x*warpScale*2.35+31,z*warpScale*2.35-17)*0.22)*warpAmp;
        double wz=z + (warpZNoise.sample(x*warpScale-47,z*warpScale+23)*0.78
                + warpZNoise.sample(x*warpScale*2.20+11,z*warpScale*2.20+53)*0.22)*warpAmp;

        WatershedEngine.HydroSample h=watersheds.sample(wx,wz);
        if(h.channelAccumulation()<=0 || !Double.isFinite(h.channelWaterSurface())) return RiverSample.NONE;

        double natural=terrain.heightWithoutRivers(x,z);
        if(natural<=terrain.seaLevel()-1) return RiverSample.NONE;

        double q=Math.max(1,h.channelAccumulation());
        double normalized=normalizeDischarge(q);
        double grade=MathUtil.clamp(h.channelGrade(),0,1.5);
        double calm=1.0-MathUtil.smoothstep(0.045,0.18,grade);

        // Taille variable selon le débit. Les grands cours peuvent devenir nettement plus
        // larges en plaine, mais restent resserrés dans les vallées encaissées.
        double width=cfg.minWidth()+(cfg.maxWidth()-cfg.minWidth())*Math.pow(normalized,1.12);
        width*=0.88 + (widthNoise.sample(x*0.0017,z*0.0017)*0.5+0.5)*0.22;
        width*=0.62 + calm*0.38;
        double coastProximity=1.0-MathUtil.smoothstep(2.0,18.0,natural-terrain.seaLevel());
        if(normalized>0.42 && grade<0.055) width*=1.0+coastProximity*ocean.estuaryStrength()*0.62;
        width=MathUtil.clamp(width,cfg.minWidth(),cfg.maxWidth()*(1.0+ocean.estuaryStrength()*0.22));

        double signed=Double.isFinite(h.channelSignedDistance())?h.channelSignedDistance():h.channelDistance();
        if(!Double.isFinite(signed)) return RiverSample.NONE;

        double half=Math.max(0.70,width*0.5);
        // Bord irrégulier à deux échelles : casse l'effet "ruban découpé au compas" sans
        // modifier l'axe hydrologique.
        double edge=(edgeNoise.sample(x*0.0105,z*0.0105)*0.72
                + edgeNoise.sample(x*0.026-19,z*0.026+37)*0.28)*(0.26+half*0.16);
        double mainDistance=Math.max(0.0,Math.abs(signed)-edge);

        // Bras secondaire très occasionnel sur les grands cours calmes. Il reste connecté
        // au même corridor alluvial et n'existe jamais sur les petits torrents.
        double braidField=braidNoise.sample(x*0.00105+71,z*0.00105-41);
        double braidActivation=MathUtil.smootherstep(0.42,0.72,braidField)
                * MathUtil.smootherstep(0.58,0.86,normalized) * calm;
        double sideSign=braidNoise.sample(x*0.0022-17,z*0.0022+83)>=0?1.0:-1.0;
        double sideOffset=sideSign*(half*1.45+1.8+normalized*2.0);
        double sideDistance=Math.abs(signed-sideOffset);
        double sideHalf=Math.max(0.55,half*(0.30+normalized*0.16));

        double effectiveDistance=mainDistance;
        if(braidActivation>0.08) effectiveDistance=Math.min(effectiveDistance,sideDistance);

        double bankAsym=bankNoise.sample(x*0.0064,z*0.0064)*0.36;
        double wetHalf=Math.max(0.62,half*(0.70+normalized*0.16));
        double bankHalf=half+cfg.bankBuffer()*(0.78+Math.abs(bankAsym)*0.55)+0.20;
        double floodHalf=bankHalf+cfg.floodplainWidth()*(0.45+normalized*1.05)*calm;
        if(effectiveDistance>floodHalf+1.5) return RiverSample.NONE;

        double wet=1.0-MathUtil.smootherstep(Math.max(0.20,wetHalf*0.72),wetHalf+0.16,mainDistance);
        if(braidActivation>0.08) {
            double sideWet=(1.0-MathUtil.smootherstep(sideHalf*0.64,sideHalf+0.14,sideDistance))*braidActivation*0.88;
            wet=Math.max(wet,sideWet);
        }
        wet=MathUtil.clamp(wet,0,1);
        double bank=1.0-MathUtil.smootherstep(wetHalf+0.02,bankHalf,effectiveDistance);
        bank=MathUtil.clamp(bank,0,1)*(1.0-wet);
        double floodplain=1.0-MathUtil.smootherstep(bankHalf,floodHalf,effectiveDistance);
        floodplain=MathUtil.clamp(floodplain,0,1)*(1.0-wet)*(1.0-bank);

        // Niveau d'eau borné par les berges réelles, pas par le terrain interpolé de la
        // cellule hydrologique. C'est le garde-fou principal contre les nappes qui fuient.
        double nx=-h.channelDirZ(), nz=h.channelDirX();
        double probe=Math.max(2.4,bankHalf+0.9);
        double nearProbe=Math.max(1.15,wetHalf+0.38);
        double bankA=terrain.heightWithoutRivers(x+nx*probe,z+nz*probe);
        double bankB=terrain.heightWithoutRivers(x-nx*probe,z-nz*probe);
        double nearA=terrain.heightWithoutRivers(x+nx*nearProbe,z+nz*nearProbe);
        double nearB=terrain.heightWithoutRivers(x-nx*nearProbe,z-nz*nearProbe);
        double bankCap=Math.floor(Math.min(Math.min(bankA,bankB),Math.min(nearA,nearB))-0.45);
        // Garde-fou voxel : une source ne doit pas surplomber un bloc sec immédiatement
        // adjacent. Ce test cardinal complète les sondes perpendiculaires au chenal et
        // élimine les fuites provoquées par un virage serré ou un méandre déformé.
        double immediateCap=Math.floor(Math.min(
                Math.min(terrain.heightWithoutRivers(x+1,z),terrain.heightWithoutRivers(x-1,z)),
                Math.min(terrain.heightWithoutRivers(x,z+1),terrain.heightWithoutRivers(x,z-1))
        )-0.30);
        bankCap=Math.min(bankCap,immediateCap);

        double waterSurface=Math.floor(h.channelWaterSurface()+1.0e-6);
        if(natural<=terrain.seaLevel()+4.0) waterSurface=terrain.seaLevel();
        waterSurface=Math.min(waterSurface,bankCap);
        waterSurface=Math.min(waterSurface,Math.floor(natural-0.78));
        if(!Double.isFinite(waterSurface)) return RiverSample.NONE;

        // Les fortes pentes restent mouillées mais beaucoup plus étroites : on obtient une
        // rivière encaissée / un torrent, pas une large nappe ni un ravin artificiellement sec.
        if(grade>cfg.maxWetGrade()) wet*=MathUtil.clamp(1.0-(grade-cfg.maxWetGrade())*2.6,0.28,0.82);

        double waterDepth=0.95+cfg.maxWaterDepth()*(0.14+normalized*0.52)*(0.60+calm*0.40);
        waterDepth=MathUtil.clamp(waterDepth,0.90,cfg.maxWaterDepth()+0.20);
        if(grade>cfg.waterfallGrade()) waterDepth=Math.min(waterDepth,1.20);
        double targetBed=waterSurface-waterDepth;
        double centerCarve=Math.min(cfg.maxCarveDepth(),Math.max(0,natural-targetBed));

        // Profil encaissé sur fortes pentes, large et plus doux en plaine.
        double exponent=grade>cfg.waterfallGrade()?1.72:(0.72+normalized*0.16);
        double profile=Math.pow(MathUtil.clamp(1.0-effectiveDistance/(bankHalf+0.18),0,1),exponent);
        double carve=centerCarve*profile;
        // La berge sèche n'est jamais abaissée : les matériaux de plage sont une passe de
        // surface, pas une excavation. Cela garantit le confinement des sources d'eau.
        if(bank>0) carve=0.0;
        if(floodplain>0) carve=0.0;
        if(wet<=0.14 && bank<=0.0) carve=0.0;

        double bedAfter=natural-carve;
        if(bedAfter>waterSurface-0.18) wet=0.0;
        if(wet<=0.14) carve=0.0;

        boolean estuary=natural<=terrain.seaLevel()+8 && normalized>0.38 && grade<0.075;
        return new RiverSample(
                Math.max(wet,Math.max(bank*0.66,floodplain*0.20)),wet,bank,floodplain,carve,waterSurface,width,q,
                h.basinId(),effectiveDistance,grade,estuary,normalized
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
