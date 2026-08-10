package fr.antho.realisticworld.vegetation;

import fr.antho.realisticworld.biome.BiomeEngine;
import fr.antho.realisticworld.climate.ClimateEngine;
import fr.antho.realisticworld.config.WorldGenConfig;
import fr.antho.realisticworld.hydrology.LakeEngine;
import fr.antho.realisticworld.hydrology.RiverEngine;
import fr.antho.realisticworld.hydrology.WaterColumnEngine;
import fr.antho.realisticworld.noise.SimplexNoise;
import fr.antho.realisticworld.terrain.TerrainEngine;
import fr.antho.realisticworld.util.HashUtil;
import fr.antho.realisticworld.util.MathUtil;
import fr.antho.realisticworld.vegetation.community.VegetationCommunitySystem;
import fr.antho.realisticworld.vegetation.community.VegetationCommunitySystem.CommunitySample;
import org.bukkit.Material;
import org.bukkit.block.Biome;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.generator.ChunkGenerator.ChunkData;

/**
 * Flore procédurale RWG. Les grands arbres, arbustes et tapis végétaux sont choisis en
 * priorité par le climat et la communauté écologique ; les azalées sont un accent rare,
 * jamais un arbuste humide par défaut.
 */
public final class VegetationGenerator {
    private final long seed;
    private final WorldGenConfig config;
    private final TerrainEngine terrain;
    private final ClimateEngine climate;
    private final WaterColumnEngine waterColumns;
    private final ForestSuccessionSystem succession;
    private final BiomeEngine biomes;
    private final SimplexNoise groveNoise;
    private final SimplexNoise clearingNoise;
    private final SimplexNoise meadowNoise;

    public VegetationGenerator(long seed, WorldGenConfig config, TerrainEngine terrain,
                               ClimateEngine climate, RiverEngine ignoredRivers, LakeEngine ignoredLakes,
                               WaterColumnEngine waterColumns, ForestSuccessionSystem succession,
                               BiomeEngine biomes) {
        this.seed=seed;
        this.config=config;
        this.terrain=terrain;
        this.climate=climate;
        this.waterColumns=waterColumns;
        this.succession=succession;
        this.biomes=biomes;
        this.groveNoise=new SimplexNoise(seed ^ 0x51A7B03E11L);
        this.clearingNoise=new SimplexNoise(seed ^ 0x2277CC91A4L);
        this.meadowNoise=new SimplexNoise(seed ^ 0x73D1A90B5EL);
    }

    public void render(ChunkData data,int chunkX,int chunkZ) {
        if(!config.vegetation().enabled()) return;
        decorateGround(data,chunkX,chunkZ);

        int x0=chunkX*16,z0=chunkZ*16;
        int margin=10;
        for(int wz=z0-margin;wz<=z0+15+margin;wz++) for(int wx=x0-margin;wx<=x0+15+margin;wx++) {
            if(!isTreeCandidate(wx,wz)) continue;
            SurfaceSample s=sampleSurface(wx,wz);
            if(!s.plantable()||s.slope()>0.44) continue;
            Biome biome=biomes.getBiome(wx,wz);
            if(biomes.isVillageOpenBiome(biome)) continue;

            ForestSuccessionSystem.ForestSample fs=succession.sample(wx,wz,s.y());
            double grove=groveFactor(wx,wz,s.climate().humidity());
            double mountain=terrain.mountainInfluence(wx,wz);
            double altitude=s.y()-terrain.seaLevel();
            CommunitySample community=VegetationCommunitySystem.classify(
                    s.climate().temperature(),s.climate().humidity(),altitude,s.slope(),mountain,
                    s.river().distanceToChannel(),fs.density(),fs.oldGrowth());

            double customBias=MathUtil.clamp(config.vegetation().customFloraBias(),0.25,1.35);
            double acceptance=config.vegetation().treeDensity()*8.0*fs.density()
                    *(0.34+grove*0.66)*(0.78+fs.maturity()*0.30)
                    *community.treeMultiplier()*customBias;
            acceptance=MathUtil.clamp(acceptance,0,0.95);
            long h=HashUtil.hash(seed,wx,wz,0x7EE5A11L);
            if(HashUtil.unitDouble(HashUtil.mix64(h^0xA19L))>=acceptance) continue;
            renderTree(data,chunkX,chunkZ,wx,wz,s.y()+1,s,fs,community,h,biome,mountain,altitude);
        }
    }

    private void decorateGround(ChunkData data,int chunkX,int chunkZ) {
        int x0=chunkX*16,z0=chunkZ*16;
        for(int z=0;z<16;z++) for(int x=0;x<16;x++) {
            int wx=x0+x,wz=z0+z;
            SurfaceSample s=sampleSurface(wx,wz);
            if(!s.plantable()||s.slope()>0.52) continue;
            int y=s.y()+1;
            if(y<=data.getMinHeight()||y>=data.getMaxHeight()) continue;
            Material ground=data.getType(x,s.y(),z);
            if(!plantableGround(ground)) continue;

            ForestSuccessionSystem.ForestSample fs=succession.sample(wx,wz,s.y());
            double grove=groveFactor(wx,wz,s.climate().humidity());
            double altitude=s.y()-terrain.seaLevel();
            double mountain=terrain.mountainInfluence(wx,wz);
            CommunitySample community=VegetationCommunitySystem.classify(
                    s.climate().temperature(),s.climate().humidity(),altitude,s.slope(),mountain,
                    s.river().distanceToChannel(),fs.density(),fs.oldGrowth());
            long h=HashUtil.hash(seed,wx,wz,0x4819A33EL);
            Biome biome=biomes.getBiome(wx,wz);
            double customBias=MathUtil.clamp(config.vegetation().customFloraBias(),0.25,1.35);

            // Les biomes de village gardent seulement un tapis léger afin de ne jamais gêner
            // les fondations/chemins vanilla placés ensuite.
            if(biomes.isVillageOpenBiome(biome)) {
                double chance=config.vegetation().openGroundCoverDensity()*0.72*customBias;
                if(HashUtil.unitDouble(HashUtil.mix64(h^0x401L))<chance) {
                    Material plant=selectGroundPlant(s.climate(),wx,wz,h);
                    if(plant!=null) setIfAirWorld(data,chunkX,chunkZ,wx,y,wz,plant);
                }
                returnOrContinueNoop();
                continue;
            }

            if(fs.density()>0.56&&grove>0.55
                    &&HashUtil.unitDouble(HashUtil.mix64(h^0x181L))<0.050*customBias) {
                setIfAirWorld(data,chunkX,chunkZ,wx,y,wz,Material.LEAF_LITTER);
                continue;
            }

            double shrubChance=config.vegetation().shrubDensity()*(0.08+fs.density()*0.28)
                    *(0.60+grove*0.40)*community.shrubMultiplier()*customBias;
            if(HashUtil.unitDouble(HashUtil.mix64(h^0x303L))<shrubChance) {
                renderShrub(data,chunkX,chunkZ,wx,wz,y,s.climate(),h);
                continue;
            }

            double boulderChance=config.vegetation().boulderDensity()*(0.04+s.slope()*0.18);
            if(HashUtil.unitDouble(HashUtil.mix64(h^0x202L))<boulderChance) {
                renderBoulder(data,chunkX,chunkZ,wx,wz,y,s.climate(),h);
                continue;
            }

            double cover=config.vegetation().groundCoverDensity()
                    *groundCoverFactor(s.climate().temperature(),s.climate().humidity(),wx,wz)
                    *community.groundMultiplier()*customBias;
            if(HashUtil.unitDouble(HashUtil.mix64(h^0x404L))<cover) {
                Material plant=selectGroundPlant(s.climate(),wx,wz,h);
                if(plant!=null) setIfAirWorld(data,chunkX,chunkZ,wx,y,wz,plant);
            }
        }
    }

    /** No-op explicite pour conserver un flux lisible dans la branche de village. */
    private static void returnOrContinueNoop() {}

    private SurfaceSample sampleSurface(int wx,int wz) {
        WaterColumnEngine.ColumnSample column=waterColumns.sample(wx,wz);
        int y=column.groundY();
        ClimateEngine.ClimateSample c=climate.sample(wx,wz,column.naturalHeight());
        double slope=terrain.slope(wx,wz);
        boolean plantable=y>terrain.seaLevel()+2&&c.temperature()>0.09&&!column.hasWater()
                &&!column.river().isRiver()&&!column.lake().isLake();
        return new SurfaceSample(y,slope,c,column.river(),column.lake(),plantable);
    }

    private void renderShrub(ChunkData data,int cx,int cz,int x,int z,int y,
                             ClimateEngine.ClimateSample c,long h) {
        double shape=HashUtil.unitDouble(HashUtil.mix64(h^0x51A3L));
        double azaleaChance=MathUtil.clamp(config.vegetation().azaleaFrequency(),0.0,0.05);
        double azaleaRoll=HashUtil.unitDouble(HashUtil.mix64(h^0xA2A0L));

        // Azalée : uniquement en climat vraiment humide et à la fréquence explicite de config.
        if(c.humidity()>0.72&&c.temperature()>0.30&&c.temperature()<0.72&&azaleaRoll<azaleaChance) {
            renderRareAzalea(data,cx,cz,x,z,y,h);
            return;
        }

        if(c.temperature()>0.67&&c.humidity()<0.40) {
            if(shape<0.40) renderDryScrub(data,cx,cz,x,z,y,h);
            else setIfAirWorld(data,cx,cz,x,y,z,Material.DEAD_BUSH);
            return;
        }
        if(c.temperature()<0.40&&c.humidity()>0.45) {
            if(shape<0.30) renderBerryThicket(data,cx,cz,x,z,y,h);
            else if(shape<0.56) renderDwarfConifer(data,cx,cz,x,z,y,h);
            else renderLowShrub(data,cx,cz,x,z,y,Material.SPRUCE_LOG,Material.SPRUCE_LEAVES,h);
            return;
        }
        if(c.humidity()>0.76) {
            if(shape<0.18) setIfAirWorld(data,cx,cz,x,y,z,Material.FIREFLY_BUSH);
            else if(shape<0.52) renderSpreadingShrub(data,cx,cz,x,z,y,Material.OAK_LEAVES,h);
            else if(shape<0.78) renderLowShrub(data,cx,cz,x,z,y,Material.BIRCH_LOG,Material.BIRCH_LEAVES,h);
            else renderBerryThicket(data,cx,cz,x,z,y,h);
            return;
        }
        if(shape<0.34) setIfAirWorld(data,cx,cz,x,y,z,Material.BUSH);
        else if(shape<0.64) renderLowShrub(data,cx,cz,x,z,y,Material.BIRCH_LOG,Material.BIRCH_LEAVES,h);
        else renderLowShrub(data,cx,cz,x,z,y,Material.OAK_LOG,Material.OAK_LEAVES,h);
    }

    private void renderRareAzalea(ChunkData data,int cx,int cz,int x,int z,int y,long h) {
        // 1 à 3 plants maximum, contre jusqu'à 9 auparavant.
        setIfAirWorld(data,cx,cz,x,y,z,
                HashUtil.unitDouble(h)<0.28?Material.FLOWERING_AZALEA:Material.AZALEA);
        for(int i=0;i<2;i++) {
            long q=HashUtil.mix64(h^(0xA2A1L+i));
            if(HashUtil.unitDouble(q)>0.38) continue;
            int dx=(q&1L)==0?1:-1;
            int dz=(q&2L)==0?0:((q&4L)==0?1:-1);
            setIfAirWorld(data,cx,cz,x+dx,y,z+dz,Material.AZALEA);
        }
    }

    private Material selectGroundPlant(ClimateEngine.ClimateSample c,int x,int z,long h) {
        double t=c.temperature(),m=c.humidity();
        double r=HashUtil.unitDouble(HashUtil.mix64(h^0xB10F1L));
        if(t>0.70&&m<0.34) return r<0.55?Material.DEAD_BUSH:null;
        if(t<0.34&&m>0.48) return r<0.58?Material.FERN:Material.SHORT_GRASS;
        if(m>0.76) {
            if(r<0.09) return Material.FIREFLY_BUSH;
            if(r<0.34) return Material.FERN;
            if(r<0.44) return Material.BLUE_ORCHID;
            return Material.SHORT_GRASS;
        }
        double patch=meadowNoise.sample(x*0.0068+41,z*0.0068-19);
        if(patch>0.34) {
            if(r<0.13) return Material.WILDFLOWERS;
            if(r<0.21) return Material.POPPY;
            if(r<0.28) return Material.CORNFLOWER;
            if(r<0.35) return Material.OXEYE_DAISY;
            if(r<0.40) return Material.DANDELION;
        }
        return Material.SHORT_GRASS;
    }

    private void renderTree(ChunkData data,int cx,int cz,int x,int z,int y,SurfaceSample s,
                            ForestSuccessionSystem.ForestSample fs,CommunitySample community,long h,
                            Biome biome,double mountain,double altitude) {
        double t=s.climate().temperature(),m=s.climate().humidity();
        double v=HashUtil.unitDouble(HashUtil.mix64(h^0x111L));
        boolean nearWater=s.river().distanceToChannel()<7.0;

        if(biome==Biome.CHERRY_GROVE) {
            renderDeciduous(data,cx,cz,x,z,y,Material.CHERRY_LOG,Material.CHERRY_LEAVES,h,6,10,true);
            return;
        }
        if(nearWater&&altitude<32&&m>0.62&&t>0.58&&v<0.24) {
            renderDeciduous(data,cx,cz,x,z,y,Material.MANGROVE_LOG,Material.MANGROVE_LEAVES,h,5,9,true);
            return;
        }
        if(community.type()==VegetationCommunitySystem.Community.BOREAL_FOREST
                ||community.type()==VegetationCommunitySystem.Community.SUBALPINE_CONIFER
                ||t<0.34||mountain>0.42) {
            Material log=v<0.72?Material.SPRUCE_LOG:Material.OAK_LOG;
            Material leaves=v<0.72?Material.SPRUCE_LEAVES:Material.OAK_LEAVES;
            if(log==Material.SPRUCE_LOG) renderConifer(data,cx,cz,x,z,y,log,leaves,h,7,15);
            else renderDeciduous(data,cx,cz,x,z,y,log,leaves,h,6,10,false);
            return;
        }
        if(t>0.72&&m>0.68) {
            renderDeciduous(data,cx,cz,x,z,y,Material.JUNGLE_LOG,Material.JUNGLE_LEAVES,h,8,15,true);
            return;
        }
        if(t>0.66&&m<0.48) {
            renderAcacia(data,cx,cz,x,z,y,h);
            return;
        }

        if(v<0.28) renderDeciduous(data,cx,cz,x,z,y,Material.BIRCH_LOG,Material.BIRCH_LEAVES,h,7,12,false);
        else if(v<0.72) renderDeciduous(data,cx,cz,x,z,y,Material.OAK_LOG,Material.OAK_LEAVES,h,6,12,true);
        else renderConifer(data,cx,cz,x,z,y,Material.SPRUCE_LOG,Material.SPRUCE_LEAVES,h,8,14);
    }

    private void renderDeciduous(ChunkData data,int cx,int cz,int x,int z,int y,
                                 Material log,Material leaves,long h,int minH,int maxH,boolean broad) {
        int height=HashUtil.range(HashUtil.mix64(h^0x7101L),minH,maxH+1);
        int bendX=0,bendZ=0;
        for(int i=0;i<height;i++) {
            if(i>height/2&&i%3==0) {
                long q=HashUtil.mix64(h^i^0x7102L);
                if(HashUtil.unitDouble(q)<0.32) {
                    bendX+=HashUtil.range(q,-1,2);
                    bendZ+=HashUtil.range(HashUtil.mix64(q),-1,2);
                    bendX=MathUtil.clamp(bendX,-2,2); bendZ=MathUtil.clamp(bendZ,-2,2);
                }
            }
            setIfAirWorld(data,cx,cz,x+bendX,y+i,z+bendZ,log);
        }
        int topY=y+height;
        int radius=broad?3:2;
        for(int dy=-2;dy<=2;dy++) {
            int r=Math.max(1,radius-Math.max(0,Math.abs(dy)-1));
            leafDisk(data,cx,cz,x+bendX,topY+dy,z+bendZ,r,leaves,h^dy^0x7103L);
        }
        if(broad) {
            // Deux lobes de couronne donnent une silhouette moins sphérique.
            int sx=(h&1L)==0?2:-2,sz=(h&2L)==0?1:-1;
            leafDisk(data,cx,cz,x+bendX+sx,topY-1,z+bendZ+sz,2,leaves,h^0x7104L);
        }
    }

    private void renderConifer(ChunkData data,int cx,int cz,int x,int z,int y,
                               Material log,Material leaves,long h,int minH,int maxH) {
        int height=HashUtil.range(HashUtil.mix64(h^0x7201L),minH,maxH+1);
        for(int i=0;i<height;i++) setIfAirWorld(data,cx,cz,x,y+i,z,log);
        int crownStart=Math.max(2,height/3);
        for(int i=crownStart;i<=height;i++) {
            int fromTop=height-i;
            int r=fromTop<2?1:fromTop<5?2:3;
            if(i%2==0||r==1) leafDisk(data,cx,cz,x,y+i,z,r,leaves,h^i^0x7202L);
        }
        setIfAirWorld(data,cx,cz,x,y+height+1,z,leaves);
    }

    private void renderAcacia(ChunkData data,int cx,int cz,int x,int z,int y,long h) {
        int height=HashUtil.range(HashUtil.mix64(h^0x7301L),5,9);
        int dx=(h&1L)==0?1:-1,dz=(h&2L)==0?1:-1;
        int bx=0,bz=0;
        for(int i=0;i<height;i++) {
            if(i>height/2&&i%2==0){bx+=dx;bz+=dz;}
            setIfAirWorld(data,cx,cz,x+bx,y+i,z+bz,Material.ACACIA_LOG);
        }
        leafDisk(data,cx,cz,x+bx,y+height,z+bz,3,Material.ACACIA_LEAVES,h^0x7302L);
        leafDisk(data,cx,cz,x+bx-dx*2,y+height-1,z+bz,2,Material.ACACIA_LEAVES,h^0x7303L);
    }

    private void renderLowShrub(ChunkData data,int cx,int cz,int x,int z,int y,
                                Material log,Material leaves,long h) {
        setIfAirWorld(data,cx,cz,x,y,z,log);
        if(HashUtil.unitDouble(HashUtil.mix64(h^0x612L))<0.30) setIfAirWorld(data,cx,cz,x,y+1,z,log);
        leafDisk(data,cx,cz,x,y+1,z,1,leaves,h^0x613L);
        setIfAirWorld(data,cx,cz,x,y+2,z,leaves);
    }

    private void renderSpreadingShrub(ChunkData data,int cx,int cz,int x,int z,int y,Material leaves,long h) {
        setIfAirWorld(data,cx,cz,x,y,z,Material.BUSH);
        int reach=HashUtil.range(HashUtil.mix64(h^0x5A11L),1,3);
        for(int dz=-reach;dz<=reach;dz++) for(int dx=-reach;dx<=reach;dx++) {
            if(Math.hypot(dx,dz)>reach+0.2) continue;
            long q=HashUtil.hash(seed,x+dx,z+dz,h^0x5A12L);
            if(HashUtil.unitDouble(q)<0.44) setLeafIfAirWorld(data,cx,cz,x+dx,y+(dx==0&&dz==0?1:0),z+dz,leaves);
        }
    }

    private void renderDwarfConifer(ChunkData data,int cx,int cz,int x,int z,int y,long h) {
        int height=HashUtil.range(HashUtil.mix64(h^0x5B11L),2,5);
        for(int i=0;i<height;i++) setIfAirWorld(data,cx,cz,x,y+i,z,Material.SPRUCE_LOG);
        for(int i=0;i<=height;i++) leafDisk(data,cx,cz,x,y+i,z,i<2?2:1,Material.SPRUCE_LEAVES,h^i);
    }

    private void renderBerryThicket(ChunkData data,int cx,int cz,int x,int z,int y,long h) {
        for(int dz=-1;dz<=1;dz++) for(int dx=-1;dx<=1;dx++) {
            if(HashUtil.unitDouble(HashUtil.hash(seed,x+dx,z+dz,h^0xBEEFL))<0.42)
                setIfAirWorld(data,cx,cz,x+dx,y,z+dz,Material.SWEET_BERRY_BUSH);
        }
    }

    private void renderDryScrub(ChunkData data,int cx,int cz,int x,int z,int y,long h) {
        setIfAirWorld(data,cx,cz,x,y,z,Material.DEAD_BUSH);
        for(int i=0;i<2;i++) {
            long q=HashUtil.mix64(h^(0xD00L+i));
            int dx=HashUtil.range(q,-2,3),dz=HashUtil.range(HashUtil.mix64(q),-2,3);
            if(Math.abs(dx)+Math.abs(dz)<=3) setIfAirWorld(data,cx,cz,x+dx,y,z+dz,Material.BUSH);
        }
    }

    private void renderBoulder(ChunkData data,int cx,int cz,int x,int z,int y,
                               ClimateEngine.ClimateSample c,long h) {
        Material main=c.humidity()>0.60?Material.MOSSY_COBBLESTONE:Material.ANDESITE;
        int radius=HashUtil.unitDouble(HashUtil.mix64(h^0x771L))<0.16?2:1;
        for(int dy=0;dy<=radius;dy++) for(int dz=-radius;dz<=radius;dz++) for(int dx=-radius;dx<=radius;dx++) {
            double shape=dx*dx+dz*dz+dy*dy*1.45;
            double jitter=HashUtil.unitDouble(HashUtil.hash(seed,x+dx,z+dz,h^dy));
            if(shape<=radius*radius+0.45+jitter) setIfAirWorld(data,cx,cz,x+dx,y+dy,z+dz,
                    jitter<0.18?Material.COBBLESTONE:main);
        }
    }

    private void leafDisk(ChunkData data,int cx,int cz,int x,int y,int z,int radius,Material leaves,long salt) {
        for(int dz=-radius;dz<=radius;dz++) for(int dx=-radius;dx<=radius;dx++) {
            double d=Math.hypot(dx,dz);
            if(d>radius+0.20) continue;
            long h=HashUtil.hash(seed,x+dx,z+dz,salt^y);
            if(d>radius-0.15&&HashUtil.unitDouble(h)>0.70) continue;
            setLeafIfAirWorld(data,cx,cz,x+dx,y,z+dz,leaves);
        }
    }

    private double groveFactor(int x,int z,double humidity) {
        double scale=Math.max(0.0002,config.vegetation().groveScale());
        double g=groveNoise.sample(x*scale,z*scale);
        double clear=clearingNoise.sample(x*scale*0.58,z*scale*0.58);
        double clusters=MathUtil.smootherstep(-0.58,0.30,g+(humidity-0.5)*0.40);
        double clearings=MathUtil.smootherstep(0.20,0.62,clear);
        return MathUtil.clamp(0.12+clusters*1.02-clearings*0.82,0.025,1.0);
    }

    private double groundCoverFactor(double t,double h,int x,int z) {
        if(t<0.13) return 0.04;
        if(h<0.18) return 0.06;
        double meadow=meadowNoise.sample(x*0.0032,z*0.0032);
        double moisture=MathUtil.smoothstep(0.18,0.62,h);
        return MathUtil.clamp(0.20+moisture*0.62+meadow*0.18,0.08,0.95);
    }

    private boolean isTreeCandidate(int wx,int wz) {
        int cell=5;
        int cx=Math.floorDiv(wx,cell),cz=Math.floorDiv(wz,cell);
        long h=HashUtil.hash(seed,cx,cz,0x3610F4A7L);
        int ox=HashUtil.range(h,0,cell),oz=HashUtil.range(HashUtil.mix64(h^0x991L),0,cell);
        return wx==cx*cell+ox&&wz==cz*cell+oz;
    }

    private static boolean plantableGround(Material m) {
        return m==Material.GRASS_BLOCK||m==Material.DIRT||m==Material.COARSE_DIRT
                ||m==Material.PODZOL||m==Material.MOSS_BLOCK;
    }

    private static void setIfAirWorld(ChunkData data,int cx,int cz,int wx,int y,int wz,Material m) {
        int x=wx-cx*16,z=wz-cz*16;
        if(x<0||x>=16||z<0||z>=16||y<data.getMinHeight()||y>=data.getMaxHeight()) return;
        if(data.getType(x,y,z).isAir()) data.setBlock(x,y,z,m);
    }

    private static void setLeafIfAirWorld(ChunkData data,int cx,int cz,int wx,int y,int wz,Material material) {
        int x=wx-cx*16,z=wz-cz*16;
        if(x<0||x>=16||z<0||z>=16||y<data.getMinHeight()||y>=data.getMaxHeight()) return;
        if(!data.getType(x,y,z).isAir()) return;
        Leaves leaves=(Leaves)material.createBlockData();
        leaves.setPersistent(true);
        data.setBlock(x,y,z,leaves);
    }

    private record SurfaceSample(int y,double slope,ClimateEngine.ClimateSample climate,
                                 RiverEngine.RiverSample river,LakeEngine.LakeSample lake,
                                 boolean plantable) {}
}
