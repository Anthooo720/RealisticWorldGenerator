package fr.antho.realisticworld.cave;

import fr.antho.realisticworld.config.WorldGenConfig;
import fr.antho.realisticworld.geology.GeologyMap;
import fr.antho.realisticworld.noise.ValueNoise3D;
import fr.antho.realisticworld.terrain.TerrainEngine;
import fr.antho.realisticworld.util.HashUtil;
import fr.antho.realisticworld.util.MathUtil;

/**
 * Surcouche vanilla+ v4. Les carvers Minecraft restent responsables de la fréquence et
 * des formes principales. RWG ajoute des connecteurs courbes, de rares cheminées, quelques
 * salles moyennes et des poches d'eau profondes très occasionnelles. L'objectif est de
 * rendre l'exploration plus cohérente sans remplacer le caractère vanilla des grottes.
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
    private final ValueNoise3D warpX;
    private final ValueNoise3D warpY;
    private final ValueNoise3D warpZ;
    private final ValueNoise3D verticalMask;
    private final ValueNoise3D aquiferMask;

    public CaveEngine(long seed, WorldGenConfig.Caves cfg, TerrainEngine terrain, GeologyMap geology) {
        this.seed=seed; this.cfg=cfg; this.terrain=terrain; this.geology=geology;
        this.tubeA=new ValueNoise3D(seed ^ 0x1700A11L);
        this.tubeB=new ValueNoise3D(seed ^ 0x1700B22L);
        this.tubeC=new ValueNoise3D(seed ^ 0x1700C33L);
        this.tubeD=new ValueNoise3D(seed ^ 0x1700D44L);
        this.regionMask=new ValueNoise3D(seed ^ 0x1700E55L);
        this.roughness=new ValueNoise3D(seed ^ 0x1700F66L);
        this.warpX=new ValueNoise3D(seed ^ 0x1700111L);
        this.warpY=new ValueNoise3D(seed ^ 0x1700222L);
        this.warpZ=new ValueNoise3D(seed ^ 0x1700333L);
        this.verticalMask=new ValueNoise3D(seed ^ 0x1700444L);
        this.aquiferMask=new ValueNoise3D(seed ^ 0x1700555L);
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

        double mask=regionMask.fbm(x*0.0036,y*0.0023,z*0.0036,2,2.0,0.52);
        if(mask<0.14) return false;

        double depth=MathUtil.clamp((surface-y)/110.0,0,1);
        double geoRadius=switch(geo.type()) {
            case LIMESTONE -> 1.12;
            case VOLCANIC -> 1.05;
            default -> 1.0;
        };

        // Warp lent : les connecteurs changent progressivement de direction au lieu de
        // ressembler à deux isosurfaces de bruit qui se croisent à angle constant.
        double wScale=0.0052;
        double warpAmount=7.0+cfg.overlayStrength()*7.0;
        double sx=x+warpX.fbm(x*wScale,y*wScale*0.62,z*wScale,2,2.0,0.50)*warpAmount;
        double sy=y+warpY.fbm(x*wScale*0.86,y*wScale*0.74,z*wScale*0.86,2,2.0,0.50)*warpAmount*0.48;
        double sz=z+warpZ.fbm(x*wScale,y*wScale*0.62,z*wScale,2,2.0,0.50)*warpAmount;

        double scale=Math.max(0.006,cfg.tunnelScale());
        double a=tubeA.fbm(sx*scale,sy*scale*0.58,sz*scale,2,2.0,0.50);
        double b=tubeB.fbm(sx*scale*0.94+31,sy*scale*0.55-17,sz*scale*1.04+13,2,2.0,0.50);
        double c=tubeC.fbm(sx*scale*0.76-43,sy*scale*0.50+11,sz*scale*0.82+27,2,2.0,0.50);
        double d=tubeD.fbm(sx*scale*0.80+19,sy*scale*0.48-29,sz*scale*0.74-37,2,2.0,0.50);

        double baseRadius=Math.max(0.024,cfg.tunnelRadius()*1.02)*geoRadius
                *(0.92+depth*0.12)*(0.78+MathUtil.clamp(cfg.overlayStrength(),0.35,1.40)*0.34);
        boolean connector=Math.hypot(a,b)<baseRadius;
        boolean branch=Math.hypot(c,d)<baseRadius*0.62 && mask>0.50;

        // Connexion verticale rare : petit puits/cheminée, pas un gouffre géant.
        double vertical=verticalMask.fbm(x*0.012,y*0.0028,z*0.012,2,2.0,0.50)*0.5+0.5;
        boolean chimney=vertical>1.0-MathUtil.clamp(cfg.verticalLinkFrequency(),0.0,0.18)
                && Math.hypot(c,d)<baseRadius*0.78 && y<surface-buffer-12;

        boolean chamber=isInsideRareRoom(x,y,z,depth,geoRadius)
                && (Math.hypot(a,b)<baseRadius*2.9 || Math.hypot(c,d)<baseRadius*2.3);
        return connector || branch || chimney || chamber;
    }

    private boolean isInsideRareRoom(int x,int y,int z,double depth,double geoRadius) {
        int cellXZ=Math.max(138,cfg.chamberSpacing());
        int cellY=Math.max(52,cellXZ/2);
        int cx=Math.floorDiv(x,cellXZ), cy=Math.floorDiv(y,cellY), cz=Math.floorDiv(z,cellXZ);
        double chance=MathUtil.clamp(cfg.chamberFrequency(),0.015,0.12);
        for(int dz=-1;dz<=1;dz++) for(int dy=-1;dy<=1;dy++) for(int dx=-1;dx<=1;dx++) {
            int gx=cx+dx,gy=cy+dy,gz=cz+dz;
            long h=hash3(gx,gy,gz,0x1700CA7EL);
            if(HashUtil.unitDouble(h)>=chance) continue;
            double px=(gx+0.18+HashUtil.unitDouble(HashUtil.mix64(h^0x11L))*0.64)*cellXZ;
            double py=(gy+0.20+HashUtil.unitDouble(HashUtil.mix64(h^0x22L))*0.60)*cellY;
            double pz=(gz+0.18+HashUtil.unitDouble(HashUtil.mix64(h^0x33L))*0.64)*cellXZ;
            double maxR=MathUtil.clamp(cfg.maxChamberRadius(),5.0,11.0);
            double radius=(4.2+HashUtil.unitDouble(HashUtil.mix64(h^0x44L))*(maxR-4.2))*geoRadius;
            radius*=0.94+depth*0.10;
            double nx=(x-px)/radius, ny=(y-py)/(radius*0.60), nz=(z-pz)/radius;
            double wobble=roughness.sample(x*0.055,y*0.050,z*0.055)*0.10;
            if(nx*nx+ny*ny+nz*nz<0.74+wobble) return true;
        }
        return false;
    }

    private long hash3(long x,long y,long z,long salt) {
        long h=HashUtil.mix64(seed^salt^(x*0x9E3779B97F4A7C15L));
        h=HashUtil.mix64(h^(y*0xC2B2AE3D27D4EB4FL));
        return HashUtil.mix64(h^(z*0x165667B19E3779F9L));
    }

    /**
     * Petits aquifères RWG uniquement dans la partie profonde des cavités ajoutées.
     * Le niveau varie très lentement par région afin de former une surface d'eau cohérente.
     */
    public boolean isDeepAquifer(int x,int y,int z,double surface) {
        if(cfg.aquiferFrequency()<=0 || y>cfg.aquiferMaxY() || surface-y<34) return false;
        double region=aquiferMask.fbm(x*0.0024,0,z*0.0024,2,2.0,0.52)*0.5+0.5;
        if(region<1.0-MathUtil.clamp(cfg.aquiferFrequency(),0.0,0.30)) return false;
        double level=cfg.aquiferMaxY()-5.0+aquiferMask.sample(x*0.0009+71,0,z*0.0009-39)*4.0;
        return y<=level;
    }
}
