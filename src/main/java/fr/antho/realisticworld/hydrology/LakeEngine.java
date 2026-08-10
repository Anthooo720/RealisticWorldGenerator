package fr.antho.realisticworld.hydrology;

import fr.antho.realisticworld.config.WorldGenConfig;
import fr.antho.realisticworld.noise.SimplexNoise;
import fr.antho.realisticworld.terrain.TerrainEngine;
import fr.antho.realisticworld.util.BoundedCache;
import fr.antho.realisticworld.util.HashUtil;
import fr.antho.realisticworld.util.MathUtil;

/**
 * Lacs-bassins continus et indépendants des frontières de tuiles hydrologiques.
 * Chaque lac possède une surface d'eau unique, un contour elliptique déformé et un lit sculpté.
 */
public final class LakeEngine {
    private final long seed;
    private final TerrainEngine terrain;
    private final WorldGenConfig.Lakes cfg;
    private final SimplexNoise shoreNoise;
    private final BoundedCache<CellKey, LakeSite> cache = new BoundedCache<>(256);

    public LakeEngine(long seed, TerrainEngine terrain, WatershedEngine ignored, WorldGenConfig.Lakes cfg) {
        this.seed=seed; this.terrain=terrain; this.cfg=cfg;
        this.shoreNoise=new SimplexNoise(seed ^ 0x4C414B453234L);
    }

    public LakeSample sample(double x,double z) {
        if(!cfg.enabled()) return LakeSample.NONE;
        int cell=Math.max(500,cfg.cellSize());
        int cx=Math.floorDiv((int)Math.floor(x),cell), cz=Math.floorDiv((int)Math.floor(z),cell);
        LakeSample best=LakeSample.NONE;
        for(int dz=-1;dz<=1;dz++) for(int dx=-1;dx<=1;dx++) {
            LakeSite site=cache.computeIfAbsent(new CellKey(cx+dx,cz+dz),this::buildSite);
            if(!site.valid) continue;
            LakeSample s=site.sample(x,z,terrain,shoreNoise,cfg);
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
        if(center<=terrain.seaLevel()+7) return LakeSite.INVALID;
        double slope=terrain.baseSlope(x,z);
        if(slope>0.24) return LakeSite.INVALID;

        double r0=cfg.minRadius()+(cfg.maxRadius()-cfg.minRadius())*HashUtil.unitDouble(HashUtil.mix64(h^23));
        double rx=r0*(0.72+HashUtil.unitDouble(HashUtil.mix64(h^29))*0.54);
        double rz=r0*(0.72+HashUtil.unitDouble(HashUtil.mix64(h^31))*0.54);
        double angle=HashUtil.unitDouble(HashUtil.mix64(h^37))*Math.PI;

        // Le bord doit globalement être plus haut que le centre : sinon ce serait une mare suspendue.
        double rim=Double.POSITIVE_INFINITY;
        for(int i=0;i<12;i++) {
            double a=i*Math.PI*2.0/12.0;
            double px=Math.cos(a)*rx, pz=Math.sin(a)*rz;
            double ca=Math.cos(angle), sa=Math.sin(angle);
            double wx=x+px*ca-pz*sa, wz=z+px*sa+pz*ca;
            rim=Math.min(rim,terrain.heightWithoutRivers(wx,wz));
        }
        // On accepte une cuvette douce : le lit sera ensuite réellement sculpté, mais la
        // ligne de rive doit rester au-dessus de la future surface d'eau.
        if(rim<center-cfg.minRimHeight()) return LakeSite.INVALID;
        double water=Math.floor(Math.min(rim-1.15,center-0.45));
        if(water<=terrain.seaLevel()+4) return LakeSite.INVALID;
        double depth=Math.min(cfg.maxDepth(),3.0+HashUtil.unitDouble(HashUtil.mix64(h^41))*cfg.maxDepth()*0.72);
        return new LakeSite(true,x,z,rx,rz,angle,water,depth,HashUtil.mix64(h));
    }

    public record LakeSample(double strength,double waterSurface,double depth,double carveDepth,long basinId,double shoreDistance) {
        public static final LakeSample NONE=new LakeSample(0,Double.NEGATIVE_INFINITY,0,0,0,Double.POSITIVE_INFINITY);
        public boolean isLake(){ return strength>0.32 && carveDepth>0.15; }
        public boolean isShore(){ return strength>0.015 && !isLake(); }
    }
    private record CellKey(int x,int z) {}
    private record LakeSite(boolean valid,double x,double z,double rx,double rz,double angle,double water,double depth,long id) {
        static final LakeSite INVALID=new LakeSite(false,0,0,0,0,0,0,0,0);
        LakeSample sample(double wx,double wz,TerrainEngine terrain,SimplexNoise shore,WorldGenConfig.Lakes cfg) {
            double ca=Math.cos(angle),sa=Math.sin(angle),dx=wx-x,dz=wz-z;
            double lx=(dx*ca+dz*sa)/rx, lz=(-dx*sa+dz*ca)/rz;
            double d=Math.hypot(lx,lz);
            double irregular=shore.sample(wx*0.018+id*1.0e-9,wz*0.018-id*1.0e-9)*0.075;
            double edge=d+irregular;
            if(edge>1.12) return LakeSample.NONE;
            double strength=1.0-MathUtil.smootherstep(0.72,1.02,edge);
            double elevation=terrain.heightWithoutRivers(wx,wz);

            // Anneau de berge sèche. Si le relief naturel est trop bas, on le relève
            // légèrement : aucune source du lac ne peut ainsi s'échapper latéralement.
            if(strength<=0.32) {
                double bank=MathUtil.smootherstep(1.12,0.88,edge);
                double target=water+0.65+bank*0.45;
                double fill=Math.min(0.0,elevation-target)*MathUtil.clamp(bank,0,1);
                return new LakeSample(Math.max(0.02,strength),water,depth,fill,id,Math.abs(1.0-edge)*Math.min(rx,rz));
            }

            double bowl=Math.pow(strength,0.72);
            double targetBed=water-0.9-depth*bowl;
            double carve=Math.max(0,elevation-targetBed)*bowl;
            carve=Math.min(carve,depth+4.0);
            return new LakeSample(strength,water,depth,carve,id,Math.abs(1.0-edge)*Math.min(rx,rz));
        }
    }
}
