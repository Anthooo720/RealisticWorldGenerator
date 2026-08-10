package fr.antho.realisticworld.features;

import fr.antho.realisticworld.biome.BiomeEngine;
import fr.antho.realisticworld.climate.ClimateEngine;
import fr.antho.realisticworld.config.WorldGenConfig;
import fr.antho.realisticworld.geology.GeologyMap;
import fr.antho.realisticworld.hydrology.LakeEngine;
import fr.antho.realisticworld.hydrology.RiverEngine;
import fr.antho.realisticworld.hydrology.WaterColumnEngine;
import fr.antho.realisticworld.terrain.TerrainEngine;
import fr.antho.realisticworld.util.HashUtil;
import fr.antho.realisticworld.util.MathUtil;
import fr.antho.realisticworld.vegetation.ForestSuccessionSystem;
import org.bukkit.Material;
import org.bukkit.generator.ChunkGenerator.ChunkData;

/** Éboulis, affleurements, troncs couchés et souches : détails naturels indépendants des arbres vivants. */
public final class NaturalFeatureGenerator {
    private final long seed;
    private final WorldGenConfig config;
    private final TerrainEngine terrain;
    private final GeologyMap geology;
    private final ClimateEngine climate;
    private final WaterColumnEngine waterColumns;
    private final ForestSuccessionSystem forest;
    private final BiomeEngine biomes;

    public NaturalFeatureGenerator(long seed,WorldGenConfig config,TerrainEngine terrain,
                                   GeologyMap geology,ClimateEngine climate,RiverEngine ignoredRivers,
                                   LakeEngine ignoredLakes,WaterColumnEngine waterColumns,
                                   ForestSuccessionSystem forest,BiomeEngine biomes) {
        this.seed=seed;
        this.config=config;
        this.terrain=terrain;
        this.geology=geology;
        this.climate=climate;
        this.waterColumns=waterColumns;
        this.forest=forest;
        this.biomes=biomes;
    }

    public void render(ChunkData data,int chunkX,int chunkZ) {
        if(!config.naturalFeatures().enabled()) return;
        int x0=chunkX*16,z0=chunkZ*16;
        for(int z=0;z<16;z++) for(int x=0;x<16;x++) {
            int wx=x0+x,wz=z0+z;
            WaterColumnEngine.ColumnSample column=waterColumns.sample(wx,wz);
            double elevation=column.naturalHeight();
            if(biomes.isVillageOpenBiome(biomes.getBiome(wx,wz))) continue;
            RiverEngine.RiverSample river=column.river();
            LakeEngine.LakeSample lake=column.lake();
            int y=column.groundY();
            if(y<=terrain.seaLevel()+2||lake.isLake()) continue;
            double slope=waterColumns.slope(wx,wz);
            long h=HashUtil.hash(seed,wx,wz,0x4E41545552414CL);
            GeologyMap.GeologySample geo=geology.sample(wx,wz);

            if(river.isBank()||river.isFloodplain()) {
                renderBankDetail(data,chunkX,chunkZ,wx,wz,y,river,climate.sample(wx,wz,elevation),h);
                continue;
            }
            if(river.isRiver()||river.strength()>0.30) continue;

            double talusChance=config.naturalFeatures().talusDensity()
                    *MathUtil.smootherstep(0.34,0.82,slope)*(0.5+geo.cliffFactor()*0.5);
            if(HashUtil.unitDouble(HashUtil.mix64(h^0x11L))<talusChance*0.08) {
                Material rock=geo.surfaceRock();
                setIfAir(data,x,y+1,z,HashUtil.unitDouble(h)<0.42?Material.GRAVEL:rock);
                continue;
            }

            double outcropChance=config.naturalFeatures().outcropDensity()
                    *MathUtil.smoothstep(0.20,0.62,slope)*0.055;
            if(HashUtil.unitDouble(HashUtil.mix64(h^0x22L))<outcropChance) {
                renderOutcrop(data,chunkX,chunkZ,wx,wz,y+1,geo.surfaceRock(),h);
                continue;
            }

            ForestSuccessionSystem.ForestSample fs=forest.sample(wx,wz,elevation);
            if(fs.density()>0.35&&slope<0.42) {
                double deadwood=config.vegetation().deadwoodDensity();
                double fallen=(config.naturalFeatures().fallenLogDensity()+deadwood*0.55)
                        *(0.35+fs.oldGrowth()*1.65+fs.disturbance()*0.60);
                ClimateEngine.ClimateSample localClimate=climate.sample(wx,wz,elevation);
                Material deadLog=localClimate.temperature()<0.40?Material.SPRUCE_LOG:Material.OAK_LOG;
                if(HashUtil.unitDouble(HashUtil.mix64(h^0x33L))<fallen*0.12) {
                    renderFallenLog(data,chunkX,chunkZ,wx,wz,y+1,deadLog,h);
                } else if(HashUtil.unitDouble(HashUtil.mix64(h^0x3AL))
                        <deadwood*(0.20+fs.oldGrowth()+fs.disturbance()*0.85)*0.055) {
                    renderSnag(data,chunkX,chunkZ,wx,wz,y+1,deadLog,h);
                } else if(HashUtil.unitDouble(HashUtil.mix64(h^0x44L))
                        <config.naturalFeatures().stumpDensity()*(0.25+fs.oldGrowth())*0.10) {
                    setIfAirWorld(data,chunkX,chunkZ,wx,y+1,wz,deadLog);
                }
            }
        }
    }

    private void renderBankDetail(ChunkData data,int cx,int cz,int x,int z,int y,
                                  RiverEngine.RiverSample river,ClimateEngine.ClimateSample c,long h) {
        int lx=x-cx*16,lz=z-cz*16;
        if(lx<0||lx>=16||lz<0||lz>=16||y<data.getMinHeight()||y>=data.getMaxHeight()) return;
        double r=HashUtil.unitDouble(HashUtil.mix64(h^0xBAA1L));
        if(river.isBank()) {
            Material top;
            if(c.humidity()>0.78&&r<0.08) top=Material.MUD;
            else if(r<0.20) top=Material.GRAVEL;
            else if(r<0.26) top=Material.CLAY;
            else top=data.getType(lx,y,lz);
            if(top!=Material.AIR&&top!=Material.WATER) data.setBlock(lx,y,lz,top);
            if(HashUtil.unitDouble(HashUtil.mix64(h^0xBAA2L))<0.045)
                setIfAirWorld(data,cx,cz,x,y+1,z,c.humidity()>0.68?Material.MANGROVE_ROOTS:Material.COBBLESTONE);
        } else {
            if(r<0.055&&data.getType(lx,y,lz)==Material.GRASS_BLOCK)
                data.setBlock(lx,y,lz,c.humidity()>0.74?Material.MUD:Material.COARSE_DIRT);
            if(HashUtil.unitDouble(HashUtil.mix64(h^0xBAA3L))<0.018)
                setIfAirWorld(data,cx,cz,x,y+1,z,Material.GRAVEL);
        }
    }

    private void renderOutcrop(ChunkData data,int cx,int cz,int x,int z,int y,Material rock,long h) {
        int r=HashUtil.range(HashUtil.mix64(h),1,3);
        for(int dz=-r;dz<=r;dz++) for(int dx=-r;dx<=r;dx++) for(int dy=0;dy<=r;dy++) {
            double shape=dx*dx+dz*dz+dy*dy*1.55;
            if(shape>r*r+HashUtil.unitDouble(HashUtil.hash(seed,x+dx,z+dz,h^dy))*1.1) continue;
            setIfAirWorld(data,cx,cz,x+dx,y+dy,z+dz,rock);
        }
    }

    private void renderSnag(ChunkData data,int cx,int cz,int x,int z,int y,Material log,long h) {
        int height=HashUtil.range(HashUtil.mix64(h^0x51A6L),3,8);
        for(int i=0;i<height;i++) setIfAirWorld(data,cx,cz,x,y+i,z,log);
        if(height>4) {
            int dx=(h&1L)==0?1:-1;
            int dz=(h&2L)==0?0:1;
            setIfAirWorld(data,cx,cz,x+dx,y+height-2,z+dz,log);
        }
    }

    private void renderFallenLog(ChunkData data,int cx,int cz,int x,int z,int y,Material log,long h) {
        boolean alongX=(h&1L)==0;
        int len=HashUtil.range(HashUtil.mix64(h^0x99L),3,7);
        for(int i=0;i<len;i++) {
            int wx=x+(alongX?i:0),wz=z+(alongX?0:i);
            WaterColumnEngine.ColumnSample local=waterColumns.sample(wx,wz);
            RiverEngine.RiverSample river=local.river();
            LakeEngine.LakeSample lake=local.lake();
            if(river.isRiver()||lake.isLake()||river.strength()>0.12) break;
            int gy=local.groundY()+1;
            setIfAirWorld(data,cx,cz,wx,gy,wz,log);
            if(HashUtil.unitDouble(HashUtil.hash(seed,wx,wz,h))<0.32)
                setIfAirWorld(data,cx,cz,wx,gy+1,wz,Material.MOSS_BLOCK);
        }
    }

    private static void setIfAir(ChunkData data,int x,int y,int z,Material material) {
        if(y<data.getMinHeight()||y>=data.getMaxHeight()) return;
        if(data.getType(x,y,z)==Material.AIR) data.setBlock(x,y,z,material);
    }

    private static void setIfAirWorld(ChunkData data,int chunkX,int chunkZ,int wx,int y,int wz,Material material) {
        int lx=wx-chunkX*16,lz=wz-chunkZ*16;
        if(lx<0||lx>=16||lz<0||lz>=16||y<data.getMinHeight()||y>=data.getMaxHeight()) return;
        if(data.getType(lx,y,lz)==Material.AIR) data.setBlock(lx,y,lz,material);
    }
}
