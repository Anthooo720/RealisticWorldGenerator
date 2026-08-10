package fr.antho.realisticworld.hydrology;

import fr.antho.realisticworld.config.WorldGenConfig;
import fr.antho.realisticworld.noise.SimplexNoise;
import fr.antho.realisticworld.terrain.TerrainEngine;
import fr.antho.realisticworld.util.BoundedCache;
import fr.antho.realisticworld.util.HashUtil;
import fr.antho.realisticworld.util.MathUtil;

/**
 * Lacs-bassins continus et indépendants des frontières de tuiles. Chaque lac possède
 * une cote unique ; le fond est irrégulier et un chenal D8 proche peut devenir son exutoire.
 */
public final class LakeEngine {
    private final long seed;
    private final TerrainEngine terrain;
    private final WatershedEngine watersheds;
    private final WorldGenConfig.Lakes cfg;
    private final SimplexNoise shoreNoise;
    private final SimplexNoise bedNoise;
    private final BoundedCache<CellKey, LakeSite> cache = new BoundedCache<>(256);

    public LakeEngine(long seed, TerrainEngine terrain, WatershedEngine watersheds, WorldGenConfig.Lakes cfg) {
        this.seed=seed; this.terrain=terrain; this.watersheds=watersheds; this.cfg=cfg;
        this.shoreNoise=new SimplexNoise(seed ^ 0x4C414B453234L);
        this.bedNoise=new SimplexNoise(seed ^ 0x4C414B4542454431L);
    }

    public LakeSample sample(double x,double z) {
        if(!cfg.enabled()) return LakeSample.NONE;
        int cell=Math.max(500,cfg.cellSize());
        int cx=Math.floorDiv((int)Math.floor(x),cell), cz=Math.floorDiv((int)Math.floor(z),cell);
        LakeSample best=LakeSample.NONE;
        for(int dz=-1;dz<=1;dz++) for(int dx=-1;dx<=1;dx++) {
            LakeSite site=cache.computeIfAbsent(new CellKey(cx+dx,cz+dz),this::buildSite);
            if(!site.valid) continue;
            LakeSample s=site.sample(x,z,terrain,shoreNoise,bedNoise,cfg);
            if(s.strength()>best.strength()) best=s;
        }
        return best;
    }

    private LakeSite buildSite(CellKey key) {
        int cell=Math.max(500,cfg.cellSize());
        long h=HashUtil.hash(seed,key.x,key.z,0x1A4E5EEDL);
        if(HashUtil.unitDouble(h)>cfg.frequency()) return LakeSite.INVALID;
        double jx=0.18+HashUtil.unitDouble(HashUtil.mix64(h^11))*0.64;
        double jz=0.18+HashUtil.unitDouble(HashUtil.mix64(h^17))*0.64;
        double x=(key.x+jx)*cell, z=(key.z+jz)*cell;
        double center=terrain.heightWithoutRivers(x,z);
        if(center<=terrain.seaLevel()+cfg.coastGuardHeight()) return LakeSite.INVALID;
        double slope=terrain.baseSlope(x,z);
        if(slope>0.24) return LakeSite.INVALID;

        double r0=cfg.minRadius()+(cfg.maxRadius()-cfg.minRadius())*HashUtil.unitDouble(HashUtil.mix64(h^23));
        double rx=r0*(0.72+HashUtil.unitDouble(HashUtil.mix64(h^29))*0.54);
        double rz=r0*(0.72+HashUtil.unitDouble(HashUtil.mix64(h^31))*0.54);
        double angle=HashUtil.unitDouble(HashUtil.mix64(h^37))*Math.PI;

        double rim=Double.POSITIVE_INFINITY;
        double outletWater=Double.POSITIVE_INFINITY;
        int rimSamples=MathUtil.clamp(cfg.rimSamples(),20,64);
        double ca=Math.cos(angle), sa=Math.sin(angle);
        for(int i=0;i<rimSamples;i++) {
            double a=i*Math.PI*2.0/rimSamples;
            double px=Math.cos(a)*rx, pz=Math.sin(a)*rz;
            double wx=x+px*ca-pz*sa, wz=z+px*sa+pz*ca;
            double edgeHeight=terrain.heightWithoutRivers(wx,wz);
            if(edgeHeight<=terrain.seaLevel()+1.5) return LakeSite.INVALID;
            rim=Math.min(rim,edgeHeight);

            WatershedEngine.HydroSample hydro=watersheds.sample(wx,wz);
            if(Double.isFinite(hydro.channelWaterSurface())
                    && hydro.channelDistance()<=Math.max(4.0,cfg.riverConnectDistance())) {
                outletWater=Math.min(outletWater,hydro.channelWaterSurface());
            }
        }
        if(rim<center-cfg.minRimHeight()) return LakeSite.INVALID;

        double water=Math.min(rim-1.15,center-0.45);
        // Si un vrai chenal touche le pourtour, sa cote devient l'exutoire du bassin.
        if(Double.isFinite(outletWater)) water=Math.min(water,outletWater+0.12);
        if(water<=terrain.seaLevel()+cfg.coastGuardHeight()*0.35) return LakeSite.INVALID;

        // Vérification intérieure plus dense : aucune vallée basse ne doit traverser le bassin.
        for(int iz=-3;iz<=3;iz++) for(int ix=-3;ix<=3;ix++) {
            if(ix==0&&iz==0) continue;
            double fx=ix/3.7, fz=iz/3.7;
            if(fx*fx+fz*fz>0.82) continue;
            double px=fx*rx, pz=fz*rz;
            double wx=x+px*ca-pz*sa, wz=z+px*sa+pz*ca;
            if(terrain.heightWithoutRivers(wx,wz)<water-0.30) return LakeSite.INVALID;
        }

        double depth=Math.min(cfg.maxDepth(),3.0+HashUtil.unitDouble(HashUtil.mix64(h^41))*cfg.maxDepth()*0.72);
        return new LakeSite(true,x,z,rx,rz,angle,water,depth,HashUtil.mix64(h));
    }

    public record LakeSample(double strength,double waterSurface,double depth,double carveDepth,long basinId,double shoreDistance) {
        public static final LakeSample NONE=new LakeSample(0,Double.NEGATIVE_INFINITY,0,0,0,Double.POSITIVE_INFINITY);
        public boolean isLake(){ return strength>0.30 && carveDepth>0.05; }
        public boolean isShore(){ return strength>0.015 && !isLake(); }
    }

    private record CellKey(int x,int z) {}

    private record LakeSite(boolean valid,double x,double z,double rx,double rz,double angle,double water,double depth,long id) {
        static final LakeSite INVALID=new LakeSite(false,0,0,0,0,0,0,0,0);

        LakeSample sample(double wx,double wz,TerrainEngine terrain,SimplexNoise shore,SimplexNoise bed,
                          WorldGenConfig.Lakes cfg) {
            double ca=Math.cos(angle),sa=Math.sin(angle),dx=wx-x,dz=wz-z;
            double lx=(dx*ca+dz*sa)/rx, lz=(-dx*sa+dz*ca)/rz;
            double d=Math.hypot(lx,lz);
            double shoreField=shore.sample(wx*0.014+id*1.0e-9,wz*0.014-id*1.0e-9)*0.070
                    +shore.sample(wx*0.031-53,wz*0.031+29)*0.022;
            double edge=d+shoreField;
            double minRadius=Math.max(8.0,Math.min(rx,rz));
            double shoreNorm=MathUtil.clamp(cfg.shoreBlendWidth()/minRadius,0.025,0.26);
            if(edge>1.0+shoreNorm*1.8) return LakeSample.NONE;

            double wetInner=Math.max(0.58,1.0-shoreNorm*1.35);
            double strength=1.0-MathUtil.smootherstep(wetInner,1.0,edge);
            double elevation=terrain.heightWithoutRivers(wx,wz);
            if(elevation<=terrain.seaLevel()+1.5 && water>terrain.seaLevel()+0.5) return LakeSample.NONE;

            // Rive douce et irrégulière. On n'érige plus un anneau de remblai continu.
            if(strength<=0.30) {
                double shoreU=MathUtil.clamp((1.0+shoreNorm*1.4-edge)/Math.max(0.02,shoreNorm*1.7),0,1);
                double blend=MathUtil.smootherstep(0.0,1.0,shoreU);
                double jitter=shore.sample(wx*0.022+91,wz*0.022-47)*0.32;
                double target=water+0.55+jitter+Math.pow(1.0-blend,1.4)*0.75;
                double carve=0.0;
                if(elevation>target) carve=Math.min(1.8,(elevation-target)*blend);
                else if(elevation<water+0.18) {
                    // Scellement très local des micro-trous de rive, plafonné pour éviter une digue visible.
                    carve=-Math.min(0.45,(water+0.18-elevation)*blend);
                }
                return new LakeSample(Math.max(0.02,strength),water,depth,carve,id,
                        Math.abs(1.0-edge)*minRadius);
            }

            // Fond non radial : grande cuvette + variations lentes + détail faible.
            double bowl=Math.pow(strength,0.76);
            double n1=bed.sample(wx*0.010+17,wz*0.010-31);
            double n2=bed.sample(wx*0.024-71,wz*0.024+43);
            double rough=MathUtil.clamp(cfg.bedRoughness(),0.0,1.2);
            double depthFactor=1.0+(n1*0.22+n2*0.08)*rough;
            double shelf=1.0-MathUtil.smootherstep(0.42,0.92,edge);
            double targetBed=water-0.72-depth*bowl*depthFactor*(0.82+shelf*0.18);
            double carve=Math.max(0,elevation-targetBed)*MathUtil.smootherstep(0.18,0.72,strength);
            carve=Math.min(carve,depth+4.0);
            return new LakeSample(strength,water,depth,carve,id,Math.abs(1.0-edge)*minRadius);
        }
    }
}
