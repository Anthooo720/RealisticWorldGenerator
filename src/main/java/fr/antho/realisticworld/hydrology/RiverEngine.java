package fr.antho.realisticworld.hydrology;

import fr.antho.realisticworld.config.WorldGenConfig;
import fr.antho.realisticworld.noise.SimplexNoise;
import fr.antho.realisticworld.terrain.TerrainEngine;
import fr.antho.realisticworld.util.MathUtil;

/**
 * Coupe fluviale continue autour du réseau D8 lissé du WatershedEngine.
 *
 * <p>Le profil est construit comme une seule fonction latérale : fond en U, rive peu
 * profonde, berge montante puis raccord au terrain naturel. On évite volontairement les
 * assemblages au max() entre plusieurs carves qui produisaient une épaule/palier visible.</p>
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

    public RiverEngine(long seed,TerrainEngine terrain,WatershedEngine watersheds,
                       WorldGenConfig.Rivers cfg,WorldGenConfig.Ocean ocean,int ignoredCacheTiles) {
        this.terrain=terrain;
        this.watersheds=watersheds;
        this.cfg=cfg;
        this.ocean=ocean;
        this.widthNoise=new SimplexNoise(seed ^ 0x5249563557494454L);
        this.edgeNoise=new SimplexNoise(seed ^ 0x5249563545444745L);
        this.braidNoise=new SimplexNoise(seed ^ 0x5249563542524149L);
        this.bankNoise=new SimplexNoise(seed ^ 0x5249563542414E4BL);
        this.floodNoise=new SimplexNoise(seed ^ 0x52495635464C4F4FL);
    }

    public RiverSample sample(double x,double z) {
        if(!cfg.enabled()) return RiverSample.NONE;

        WatershedEngine.HydroSample h=watersheds.sample(x,z);
        if(h.channelAccumulation()<=0||!Double.isFinite(h.channelWaterSurface())) return RiverSample.NONE;

        double natural=terrain.heightWithoutRivers(x,z);
        if(natural<=terrain.seaLevel()-1) return RiverSample.NONE;

        double q=Math.max(1.0,h.channelAccumulation());
        double maturity=normalizeDischarge(q);
        double grade=MathUtil.clamp(h.channelGrade(),0,1.5);
        double calm=1.0-MathUtil.smoothstep(0.030,0.165,grade);
        double width=riverWidth(x,z,natural,maturity,grade,calm);

        double signed=Double.isFinite(h.channelSignedDistance())
                ?h.channelSignedDistance():h.channelDistance();
        if(!Double.isFinite(signed)) return RiverSample.NONE;

        ChannelGeometry geometry=channelGeometry(x,z,signed,width,maturity,calm);
        double distance=geometry.distance();
        double wetHalf=geometry.wetHalf();
        double bankWidth=Math.max(3.2,cfg.bankSlopeWidth()*(1.08+maturity*0.56));
        double bankHalf=wetHalf+bankWidth;
        double floodWidth=cfg.floodplainWidth()*(0.26+maturity*1.18)*calm;
        double floodHalf=bankHalf+floodWidth;
        if(distance>floodHalf+2.5) return RiverSample.NONE;

        double waterSurface=h.channelWaterSurface();
        if(!Double.isFinite(waterSurface)) return RiverSample.NONE;

        double wetGradeFactor=1.0;
        if(grade>cfg.waterfallGrade()) {
            wetGradeFactor=1.0-MathUtil.smootherstep(
                    cfg.waterfallGrade(),Math.max(cfg.waterfallGrade()+0.015,cfg.maxWetGrade()),grade);
            wetGradeFactor=0.12+wetGradeFactor*0.88;
        }
        if(grade>cfg.maxWetGrade()) wetGradeFactor*=0.10;

        // La zone humide déborde légèrement dans la montée de rive. Ainsi, au moment où
        // isRiver() devient faux, le sol de la berge est déjà revenu au niveau de l'eau.
        double wetInner=0.70+maturity*0.55;
        double wetOuter=wetHalf+Math.min(2.2,bankWidth*0.18);
        double wet=1.0-MathUtil.smootherstep(
                Math.max(0.35,wetHalf-wetInner),wetOuter,distance);
        wet*=geometry.wetMultiplier()*wetGradeFactor;
        wet=MathUtil.clamp(wet,0,1);

        double dirLen=Math.max(1.0e-6,Math.hypot(h.channelDirX(),h.channelDirZ()));
        double nx=-h.channelDirZ()/dirLen,nz=h.channelDirX()/dirLen;
        double outerProbe=Math.max(4.0,bankHalf+1.8);
        double bankA=terrain.heightWithoutRivers(x+nx*outerProbe,z+nz*outerProbe);
        double bankB=terrain.heightWithoutRivers(x-nx*outerProbe,z-nz*outerProbe);
        double containmentCeiling=Math.min(bankA,bankB)-cfg.bankBuffer()*0.30;
        if(waterSurface>containmentCeiling) {
            double contain=MathUtil.clamp(1.0-(waterSurface-containmentCeiling)/2.2,0,1);
            wet*=contain;
        }

        double waterDepth=riverDepth(maturity,grade,calm);
        double desired=desiredCrossSectionHeight(
                x,z,natural,waterSurface,distance,wetHalf,bankHalf,floodHalf,waterDepth,maturity,calm);
        double rawCarve=Math.max(0.0,natural-desired);
        if(distance>wetHalf&&distance<=bankHalf) rawCarve=Math.min(rawCarve,cfg.bankMaxCut());
        else if(distance>bankHalf) rawCarve=Math.min(rawCarve,cfg.floodplainMaxCut());
        double carve=MathUtil.clamp(rawCarve,0,cfg.maxCarveDepth());

        double bedAfter=natural-carve;
        if(wet>0.14&&bedAfter>waterSurface-0.08) wet=0;

        double bank=bankStrength(distance,wetHalf,bankHalf,wet);
        double flood=floodplainStrength(distance,bankHalf,floodHalf,wet,bank);
        boolean estuary=natural<=terrain.seaLevel()+cfg.coastalMergeHeight()
                &&maturity>0.28&&grade<0.085;

        return new RiverSample(
                Math.max(wet,Math.max(bank*0.68,flood*0.22)),
                wet,bank,flood,carve,waterSurface,wetHalf*2.0,q,h.basinId(),distance,grade,estuary,maturity
        );
    }

    private double riverWidth(double x,double z,double natural,double maturity,double grade,double calm) {
        double width=cfg.minWidth()+(cfg.maxWidth()-cfg.minWidth())*Math.pow(maturity,1.04);
        double broad=widthNoise.sample(x*0.00082,z*0.00082)*0.5+0.5;
        double detail=widthNoise.sample(x*0.0021+47,z*0.0021-31)*0.5+0.5;
        width*=0.88+broad*0.20+detail*0.06;
        width*=0.72+calm*0.28;

        double coast=1.0-MathUtil.smoothstep(2.0,Math.max(8.0,cfg.coastalMergeHeight()+8.0),
                natural-terrain.seaLevel());
        if(maturity>0.30&&grade<0.080) width*=1.0+coast*ocean.estuaryStrength()*0.74;
        return MathUtil.clamp(width,cfg.minWidth(),cfg.maxWidth()*(1.0+ocean.estuaryStrength()*0.22));
    }

    private ChannelGeometry channelGeometry(double x,double z,double signed,double width,
                                             double maturity,double calm) {
        double half=Math.max(0.90,width*0.5);
        double edge=(edgeNoise.sample(x*0.0026,z*0.0026)*0.82
                +edgeNoise.sample(x*0.0064-19,z*0.0064+37)*0.18)
                *cfg.edgeRoughness()*(0.16+half*0.055);
        double thalwegShift=bankNoise.sample(x*0.0018+47,z*0.0018-31)
                *MathUtil.clamp(cfg.thalwegOffset(),0.0,0.36)*half;
        double shifted=signed-thalwegShift;
        double mainDistance=Math.max(0.0,Math.abs(shifted)-edge);
        double wetHalf=Math.max(0.82,half*(0.88+maturity*0.08));

        double braidField=braidNoise.sample(x*0.00072+71,z*0.00072-41)*0.5+0.5;
        double activation=MathUtil.smootherstep(1.0-cfg.secondaryChannelFrequency(),0.985,braidField)
                *MathUtil.smootherstep(0.72,0.94,maturity)
                *MathUtil.smootherstep(0.58,0.96,calm);
        if(activation<=0.10) return new ChannelGeometry(mainDistance,wetHalf,1.0);

        double sideSign=braidNoise.sample(x*0.0015-17,z*0.0015+83)>=0?1.0:-1.0;
        double sideOffset=sideSign*(half*1.68+2.1+maturity*2.1);
        double sideDistance=Math.abs(shifted-sideOffset);
        double sideHalf=Math.max(0.68,half*(0.24+maturity*0.13));
        if(sideDistance>=mainDistance) return new ChannelGeometry(mainDistance,wetHalf,1.0);
        return new ChannelGeometry(sideDistance,sideHalf,MathUtil.clamp(activation*0.82,0,1));
    }

    private double riverDepth(double maturity,double grade,double calm) {
        double depth=0.92+cfg.maxWaterDepth()*(0.10+maturity*0.50)*(0.78+calm*0.22);
        depth=MathUtil.clamp(depth,0.84,cfg.maxWaterDepth());
        if(grade>cfg.waterfallGrade()) depth=Math.min(depth,1.20);
        return depth;
    }

    private double desiredCrossSectionHeight(double x,double z,double natural,double water,
                                             double distance,double wetHalf,double bankHalf,double floodHalf,
                                             double depth,double maturity,double calm) {
        double edgeDepth=0.30+maturity*0.18;
        if(distance<=wetHalf) {
            double u=MathUtil.clamp(distance/Math.max(0.25,wetHalf),0,1);
            double exponent=MathUtil.clamp(cfg.profileExponent(),1.10,2.60);
            double depthFactor=1.0-Math.pow(MathUtil.smootherstep(0.0,1.0,u),exponent);
            double localDepth=edgeDepth+(depth-edgeDepth)*depthFactor;
            double texture=edgeNoise.sample(x*0.012+23,z*0.012-11)*0.10*(0.25+maturity*0.75);
            return water-localDepth+texture;
        }

        if(distance<=bankHalf) {
            double bankU=MathUtil.clamp((distance-wetHalf)/Math.max(0.5,bankHalf-wetHalf),0,1);
            double rise=MathUtil.smootherstep(0.0,0.22,bankU);
            double blendOut=Math.pow(MathUtil.smootherstep(0.18,1.0,bankU),
                    MathUtil.clamp(cfg.bankTransitionPower(),0.70,1.80));
            double asym=bankNoise.sample(x*0.0025,z*0.0025)*cfg.bankHeightJitter();
            double crest=water+0.58+asym*0.18+maturity*0.20;
            double inner=MathUtil.lerp(water-edgeDepth,crest,rise);
            return MathUtil.lerp(inner,natural,blendOut);
        }

        if(distance<=floodHalf&&floodHalf>bankHalf+0.25) {
            double u=MathUtil.clamp((distance-bankHalf)/(floodHalf-bankHalf),0,1);
            double enter=MathUtil.smootherstep(0.0,0.24,u);
            double exit=1.0-MathUtil.smootherstep(0.58,1.0,u);
            double envelope=enter*exit*calm*MathUtil.smoothstep(0.26,0.72,maturity);
            double variation=floodNoise.sample(x*0.00135,z*0.00135)*0.44;
            double floodTarget=water+1.55+maturity*1.08+variation;
            double cut=Math.min(cfg.floodplainMaxCut(),Math.max(0,natural-floodTarget))*envelope;
            return natural-cut;
        }
        return natural;
    }

    private static double bankStrength(double distance,double wetHalf,double bankHalf,double wet) {
        if(distance<=wetHalf||distance>=bankHalf) return 0;
        double u=(distance-wetHalf)/Math.max(0.5,bankHalf-wetHalf);
        double shape=MathUtil.smootherstep(0.0,0.22,u)*(1.0-MathUtil.smootherstep(0.68,1.0,u));
        return MathUtil.clamp(shape*(1.0-wet),0,1);
    }

    private static double floodplainStrength(double distance,double bankHalf,double floodHalf,
                                             double wet,double bank) {
        if(distance<=bankHalf||distance>=floodHalf||floodHalf<=bankHalf) return 0;
        double u=(distance-bankHalf)/(floodHalf-bankHalf);
        double shape=MathUtil.smootherstep(0.0,0.25,u)*(1.0-MathUtil.smootherstep(0.62,1.0,u));
        return MathUtil.clamp(shape*(1.0-wet)*(1.0-bank),0,1);
    }

    private double normalizeDischarge(double q) {
        return MathUtil.clamp(Math.log1p(q)/Math.log1p(Math.max(8,cfg.accumulationThreshold()*26)),0,1);
    }

    private record ChannelGeometry(double distance,double wetHalf,double wetMultiplier) {}

    public record RiverSample(double strength,double wetness,double bankStrength,double floodplainStrength,
                              double carveDepth,double waterSurface,double approximateWidth,double discharge,
                              long basinId,double distanceToChannel,double grade,boolean estuary,double maturity) {
        public static final RiverSample NONE=new RiverSample(
                0,0,0,0,0,Double.NEGATIVE_INFINITY,0,0,0,Double.POSITIVE_INFINITY,0,false,0);
        public boolean isRiver(){ return wetness>0.14; }
        public boolean isBank(){ return bankStrength>0.05&&!isRiver(); }
        public boolean isFloodplain(){ return floodplainStrength>0.08&&!isRiver()&&!isBank(); }
    }
}
