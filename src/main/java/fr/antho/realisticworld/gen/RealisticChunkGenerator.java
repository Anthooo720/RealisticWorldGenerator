package fr.antho.realisticworld.gen;

import fr.antho.realisticworld.biome.RealisticBiomeProvider;
import fr.antho.realisticworld.climate.ClimateEngine;
import fr.antho.realisticworld.hydrology.LakeEngine;
import fr.antho.realisticworld.hydrology.RiverEngine;
import fr.antho.realisticworld.soil.SoilEngine;
import fr.antho.realisticworld.util.MathUtil;
import org.bukkit.HeightMap;
import org.bukkit.Material;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;

import java.util.Random;

/** Générateur naturel : relief, géologie 3D, hydrologie, sols, grottes et végétation. */
public final class RealisticChunkGenerator extends ChunkGenerator {
    private final ContextRegistry contexts;
    private final RealisticBiomeProvider biomeProvider;
    public RealisticChunkGenerator(ContextRegistry contexts){ this.contexts=contexts; this.biomeProvider=new RealisticBiomeProvider(contexts); }

    @Override public void generateNoise(WorldInfo worldInfo, Random random, int chunkX, int chunkZ, ChunkData data) {
        GenerationContext ctx=contexts.forWorld(worldInfo);
        int minY=data.getMinHeight(),maxY=data.getMaxHeight(),sea=ctx.terrain.seaLevel();
        for(int z=0;z<16;z++) { int wz=chunkZ*16+z;
            for(int x=0;x<16;x++) { int wx=chunkX*16+x;
                double natural=ctx.terrain.heightWithoutRivers(wx,wz);
                RiverEngine.RiverSample river=ctx.rivers.sample(wx,wz);
                LakeEngine.LakeSample lake=ctx.lakes.sample(wx,wz);
                double carved=natural-river.carveDepth()-lake.carveDepth();
                int surface=MathUtil.clamp((int)Math.floor(carved),minY+1,maxY-2);
                var geo=ctx.geology.sample(wx,wz);

                for(int y=minY;y<=surface;y++) {
                    Material material=y<=minY+1?Material.BEDROCK:ctx.geology.rockAt(geo,wx,y,wz);
                    data.setBlock(x,y,z,material);
                }

                int waterTop=surface;
                if(natural<sea) waterTop=sea;
                if(river.isRiver()) waterTop=Math.max(waterTop,(int)Math.floor(river.waterSurface()));
                if(lake.isLake()) waterTop=Math.max(waterTop,(int)Math.floor(lake.waterSurface()));
                waterTop=Math.min(maxY-1,waterTop);
                for(int y=surface+1;y<=waterTop;y++) data.setBlock(x,y,z,Material.WATER);
            }
        }
    }

    @Override public void generateSurface(WorldInfo worldInfo, Random random, int chunkX, int chunkZ, ChunkData data) {
        GenerationContext ctx=contexts.forWorld(worldInfo);
        int minY=data.getMinHeight(),maxY=data.getMaxHeight();
        for(int z=0;z<16;z++) { int wz=chunkZ*16+z;
            for(int x=0;x<16;x++) { int wx=chunkX*16+x;
                double natural=ctx.terrain.heightWithoutRivers(wx,wz);
                RiverEngine.RiverSample river=ctx.rivers.sample(wx,wz);
                LakeEngine.LakeSample lake=ctx.lakes.sample(wx,wz);
                int surface=MathUtil.clamp((int)Math.floor(natural-river.carveDepth()-lake.carveDepth()),minY+1,maxY-2);
                ClimateEngine.ClimateSample climate=ctx.climate.sample(wx,wz,natural);
                double slope=ctx.terrain.slope(wx,wz);
                SoilEngine.SoilProfile p=ctx.soils.sample(wx,wz,natural,slope,climate,river,lake);
                data.setBlock(x,surface,z,p.top());
                for(int depth=1;depth<=p.depth()&&surface-depth>minY;depth++) {
                    data.setBlock(x,surface-depth,z,depth>=Math.max(3,p.depth()-1)?p.deep():p.sub());
                }
                if(p.snowCap()&&surface+1<maxY&&!river.isRiver()&&!lake.isLake()) data.setBlock(x,surface+1,z,Material.SNOW);
            }
        }
        ctx.vegetation.render(data,chunkX,chunkZ);
        ctx.naturalFeatures.render(data,chunkX,chunkZ);
    }

    /** Surcouche caves vanilla+ : les carvers vanilla passent d'abord, puis RWG ajoute seulement quelques connecteurs/salles rares. */
    @Override public void generateCaves(WorldInfo worldInfo, Random random, int chunkX, int chunkZ, ChunkData data) {
        GenerationContext ctx=contexts.forWorld(worldInfo);
        if(!ctx.caves.enabled()) return;
        int minY=Math.max(data.getMinHeight()+3,ctx.config.caves().minY());
        int maxY=Math.min(data.getMaxHeight()-2,ctx.config.caves().maxY());
        for(int z=0;z<16;z++) { int wz=chunkZ*16+z;
            for(int x=0;x<16;x++) { int wx=chunkX*16+x;
                double surface=ctx.terrain.heightWithoutRivers(wx,wz)-ctx.rivers.sample(wx,wz).carveDepth()-ctx.lakes.sample(wx,wz).carveDepth();
                var geo=ctx.geology.sample(wx,wz);
                int top=Math.min(maxY,(int)Math.floor(surface)-ctx.config.caves().surfaceBuffer());
                for(int y=minY;y<=top;y++) {
                    Material current=data.getType(x,y,z);
                    if(current==Material.AIR||current==Material.WATER||current==Material.BEDROCK) continue;
                    if(ctx.caves.shouldCarve(geo,wx,y,wz,surface)) {
                        data.setBlock(x,y,z,Material.AIR);
                    }
                }
            }
        }
    }

    @Override public int getBaseHeight(WorldInfo worldInfo, Random random, int x, int z, HeightMap heightMap) {
        GenerationContext ctx=contexts.forWorld(worldInfo);
        double top=ctx.terrain.baseHeightRaw(x,z);
        if(top<ctx.terrain.seaLevel()) top=ctx.terrain.seaLevel();
        return MathUtil.clamp((int)Math.floor(top)+1,worldInfo.getMinHeight(),worldInfo.getMaxHeight()-1);
    }

    @Override public BiomeProvider getDefaultBiomeProvider(WorldInfo worldInfo){ return biomeProvider; }
    @Override public boolean shouldGenerateNoise(WorldInfo worldInfo,Random random,int chunkX,int chunkZ){ return false; }
    @Override public boolean shouldGenerateSurface(WorldInfo worldInfo,Random random,int chunkX,int chunkZ){ return false; }
    @Override public boolean shouldGenerateCaves(WorldInfo worldInfo,Random random,int chunkX,int chunkZ){
        // v1.6 : les grottes vanilla sont la base ; CaveEngine n'est qu'une surcouche légère.
        return contexts.forWorld(worldInfo).config.compatibility().vanillaCaves();
    }
    @Override public boolean shouldGenerateDecorations(WorldInfo worldInfo,Random random,int chunkX,int chunkZ){
        return contexts.forWorld(worldInfo).config.compatibility().vanillaDecorations();
    }
    @Override public boolean shouldGenerateMobs(WorldInfo worldInfo,Random random,int chunkX,int chunkZ){ return true; }
    /** Structures : toujours vanilla dans le plugin principal. */
    @Override public boolean shouldGenerateStructures(WorldInfo worldInfo,Random random,int chunkX,int chunkZ){ return true; }
}
