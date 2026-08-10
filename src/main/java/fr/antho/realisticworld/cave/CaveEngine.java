package fr.antho.realisticworld.cave;

import fr.antho.realisticworld.config.WorldGenConfig;
import fr.antho.realisticworld.geology.GeologyMap;
import fr.antho.realisticworld.noise.ValueNoise3D;
import fr.antho.realisticworld.terrain.TerrainEngine;
import fr.antho.realisticworld.util.HashUtil;
import fr.antho.realisticworld.util.MathUtil;

/**
 * Surcouche "vanilla+". En v1.6 les carvers vanilla restent la base du réseau de grottes.
 * Ce moteur ne cherche plus à remplacer Minecraft : il ajoute seulement quelques petits
 * connecteurs et de rares salles moyennes dans les zones profondes, afin d'améliorer la
 * continuité sans recréer les immenses cavernes des anciennes versions.
 */
public final class CaveEngine {
    private final long seed;
    private final WorldGenConfig.Caves cfg;
    private final TerrainEngine terrain;
    private final GeologyMap geology;
    private final ValueNoise3D tubeA;
    private final ValueNoise3D tubeB;
    private final ValueNoise3D tubeC;
    private final ValueNoise3D tubeD;
    private final ValueNoise3D regionMask;
    private final ValueNoise3D roughness;

    public CaveEngine(long seed, WorldGenConfig.Caves cfg, TerrainEngine terrain, GeologyMap geology) {
        this.seed=seed; this.cfg=cfg; this.terrain=terrain; this.geology=geology;
        this.tubeA=new ValueNoise3D(seed ^ 0x1600A11L);
        this.tubeB=new ValueNoise3D(seed ^ 0x1600B22L);
        this.tubeC=new ValueNoise3D(seed ^ 0x1600C33L);
        this.tubeD=new ValueNoise3D(seed ^ 0x1600D44L);
        this.regionMask=new ValueNoise3D(seed ^ 0x1600E55L);
        this.roughness=new ValueNoise3D(seed ^ 0x1600F66L);
    }

    public boolean enabled(){ return cfg.enabled(); }

    public boolean shouldCarve(int x,int y,int z,double surface) {
        return shouldCarve(geology.sample(x,z),x,y,z,surface);
    }

    public boolean shouldCarve(GeologyMap.GeologySample geo,int x,int y,int z,double surface) {
        if(!cfg.enabled() || y<cfg.minY() || y>cfg.maxY()) return false;
        int sea=terrain.seaLevel();
        int buffer=surface<=sea+4?cfg.oceanBuffer():cfg.surfaceBuffer();
        if(y>surface-buffer) return false;
        if(surface<=sea+3 && y>sea-cfg.oceanBuffer()) return false;

        // Seulement certaines régions reçoivent les améliorations : la fréquence générale
        // reste donc celle des grottes vanilla.
        double mask=regionMask.fbm(x*0.0042,y*0.0028,z*0.0042,2,2.0,0.52);
        if(mask<0.28) return false;

        double depth=MathUtil.clamp((surface-y)/105.0,0,1);
        double geoRadius=switch(geo.type()) {
            case LIMESTONE -> 1.10;
            case VOLCANIC -> 1.04;
            default -> 1.0;
        };

        double scale=Math.max(0.006,cfg.tunnelScale()*1.08);
        double a=tubeA.fbm(x*scale,y*scale*0.58,z*scale,2,2.0,0.50);
        double b=tubeB.fbm(x*scale*0.93+31,y*scale*0.55-17,z*scale*1.05+13,2,2.0,0.50);
        double c=tubeC.fbm(x*scale*0.72-43,y*scale*0.50+11,z*scale*0.78+27,2,2.0,0.50);
        double d=tubeD.fbm(x*scale*0.76+19,y*scale*0.48-29,z*scale*0.70-37,2,2.0,0.50);

        double baseRadius=Math.max(0.018,cfg.tunnelRadius()*0.47)*geoRadius*(0.92+depth*0.10);
        boolean connector=Math.hypot(a,b)<baseRadius;
        boolean branch=Math.hypot(c,d)<baseRadius*0.63 && mask>0.52;
        boolean chamber=isInsideRareRoom(x,y,z,depth,geoRadius) && (Math.hypot(a,b)<baseRadius*2.8 || Math.hypot(c,d)<baseRadius*2.2);
        return connector || branch || chamber;
    }

    private boolean isInsideRareRoom(int x,int y,int z,double depth,double geoRadius) {
        int cellXZ=Math.max(150,cfg.chamberSpacing()+56);
        int cellY=Math.max(56,cellXZ/2);
        int cx=Math.floorDiv(x,cellXZ), cy=Math.floorDiv(y,cellY), cz=Math.floorDiv(z,cellXZ);
        double chance=MathUtil.clamp(cfg.chamberFrequency()*0.32,0.015,0.075);
        for(int dz=-1;dz<=1;dz++) for(int dy=-1;dy<=1;dy++) for(int dx=-1;dx<=1;dx++) {
            int gx=cx+dx,gy=cy+dy,gz=cz+dz;
            long h=hash3(gx,gy,gz,0x1600CA7EL);
            if(HashUtil.unitDouble(h)>=chance) continue;
            double px=(gx+0.18+HashUtil.unitDouble(HashUtil.mix64(h^0x11L))*0.64)*cellXZ;
            double py=(gy+0.20+HashUtil.unitDouble(HashUtil.mix64(h^0x22L))*0.60)*cellY;
            double pz=(gz+0.18+HashUtil.unitDouble(HashUtil.mix64(h^0x33L))*0.64)*cellXZ;
            double maxR=MathUtil.clamp(cfg.maxChamberRadius(),5.0,10.0);
            double radius=(4.0+HashUtil.unitDouble(HashUtil.mix64(h^0x44L))*(maxR-4.0))*geoRadius;
            radius*=0.94+depth*0.08;
            double nx=(x-px)/radius, ny=(y-py)/(radius*0.62), nz=(z-pz)/radius;
            double wobble=roughness.sample(x*0.060,y*0.052,z*0.060)*0.08;
            if(nx*nx+ny*ny+nz*nz<0.72+wobble) return true;
        }
        return false;
    }

    private long hash3(long x,long y,long z,long salt) {
        long h=HashUtil.mix64(seed^salt^(x*0x9E3779B97F4A7C15L));
        h=HashUtil.mix64(h^(y*0xC2B2AE3D27D4EB4FL));
        return HashUtil.mix64(h^(z*0x165667B19E3779F9L));
    }

    /** Les aquifères sont laissés au moteur vanilla dans le mode par défaut v1.6. */
    public boolean isDeepAquifer(int x,int y,int z,double surface) { return false; }
}
