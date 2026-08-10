package fr.antho.realisticworld.gen;

import fr.antho.realisticworld.biome.RealisticBiomeProvider;
import fr.antho.realisticworld.climate.ClimateEngine;
import fr.antho.realisticworld.hydrology.LakeEngine;
import fr.antho.realisticworld.hydrology.RiverEngine;
import fr.antho.realisticworld.hydrology.WaterColumnEngine;
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
                WaterColumnEngine.ColumnSample column=ctx.waterColumns.sample(wx,wz);
                double natural=column.naturalHeight();
                RiverEngine.RiverSample river=column.river();
                LakeEngine.LakeSample lake=column.lake();
                int surface=MathUtil.clamp(column.groundY(),minY+1,maxY-2);
                var geo=ctx.geology.sample(wx,wz);

                for(int y=minY;y<=surface;y++) {
                    Material material=y<=minY+1?Material.BEDROCK:ctx.geology.rockAt(geo,wx,y,wz);
                    data.setBlock(x,y,z,material);
                }

                if(column.hasWater()) {
                    int waterTop=Math.min(maxY-1,column.waterTop());
                    for(int y=surface+1;y<=waterTop;y++) data.setBlock(x,y,z,Material.WATER);
                }
            }
        }
    }

    @Override public void generateSurface(WorldInfo worldInfo, Random random, int chunkX, int chunkZ, ChunkData data) {
        GenerationContext ctx=contexts.forWorld(worldInfo);
        int minY=data.getMinHeight(),maxY=data.getMaxHeight();
        for(int z=0;z<16;z++) { int wz=chunkZ*16+z;
            for(int x=0;x<16;x++) { int wx=chunkX*16+x;
                WaterColumnEngine.ColumnSample column=ctx.waterColumns.sample(wx,wz);
                double natural=column.naturalHeight();
                RiverEngine.RiverSample river=column.river();
                LakeEngine.LakeSample lake=column.lake();
                int surface=MathUtil.clamp(column.groundY(),minY+1,maxY-2);
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
                double surface=ctx.waterColumns.sample(wx,wz).groundHeight();
                var geo=ctx.geology.sample(wx,wz);
                int top=Math.min(maxY,(int)Math.floor(surface)-ctx.config.caves().surfaceBuffer());
                for(int y=minY;y<=top;y++) {
                    Material current=data.getType(x,y,z);
                    if(current==Material.AIR||current==Material.WATER||current==Material.BEDROCK) continue;
                    if(ctx.caves.shouldCarve(geo,wx,y,wz,surface)) {
                        data.setBlock(x,y,z,ctx.caves.isDeepAquifer(wx,y,wz,surface) ? Material.WATER : Material.AIR);
                    }
                }
            }
        }
    }

    /**
     * Heightmap de référence utilisée par le pipeline vanilla (structures, locate, etc.).
     * Elle doit impérativement suivre le relief réellement produit par generateNoise().
     *
     * L'ancienne implémentation utilisait baseHeightRaw(), donc AVANT l'érosion. Une
     * structure pouvait être calculée plusieurs blocs au-dessus ou au-dessous du sol final.
     * WaterColumnEngine réutilise exactement la même colonne que generateNoise() : hauteur
     * érodée, incision locale, niveau marin et eau continentale. Son cache par colonne évite
     * de recalculer l'hydrologie lors des nombreux appels de worldgen et de /locate.
     */
    @Override public int getBaseHeight(WorldInfo worldInfo, Random random, int x, int z, HeightMap heightMap) {
        GenerationContext ctx=contexts.forWorld(worldInfo);
        WaterColumnEngine.ColumnSample column=ctx.waterColumns.sample(x,z);
        int groundTop=MathUtil.clamp(column.groundY()+1,worldInfo.getMinHeight(),worldInfo.getMaxHeight()-1);
        int worldTop=MathUtil.clamp(column.worldSurfaceY()+1,worldInfo.getMinHeight(),worldInfo.getMaxHeight()-1);
        return switch(heightMap) {
            case OCEAN_FLOOR, OCEAN_FLOOR_WG -> groundTop;
            case WORLD_SURFACE, WORLD_SURFACE_WG, MOTION_BLOCKING, MOTION_BLOCKING_NO_LEAVES -> worldTop;
        };
    }

    @Override public BiomeProvider getDefaultBiomeProvider(WorldInfo worldInfo){ return biomeProvider; }
    @Override public boolean shouldGenerateNoise(WorldInfo worldInfo,Random random,int chunkX,int chunkZ){ return false; }
    @Override public boolean shouldGenerateSurface(WorldInfo worldInfo,Random random,int chunkX,int chunkZ){ return false; }
    @Override public boolean shouldGenerateCaves(WorldInfo worldInfo,Random random,int chunkX,int chunkZ){
        GenerationContext ctx=contexts.forWorld(worldInfo);
        if(!ctx.config.compatibility().vanillaCaves()) return false;
        // Les aquifères/carvers vanilla sont calibrés sur le noise generator vanilla. Sous un
        // océan entièrement custom ils peuvent ouvrir de gigantesques poches d'air juste sous
        // la mer, laissant des plafonds/dalles d'eau suspendus. On conserve le vanilla sur terre
        // et on le coupe uniquement dans les chunks très majoritairement océaniques.
        if(ctx.config.caves().protectOceanCarvers()) {
            double landRatio=ctx.waterColumns.landRatioForCaves(chunkX,chunkZ);
            if(landRatio<=ctx.config.caves().oceanCarverMaxLandRatio()) return false;
        }
        return true;
    }
    @Override public boolean shouldGenerateDecorations(WorldInfo worldInfo,Random random,int chunkX,int chunkZ){
        return contexts.forWorld(worldInfo).config.compatibility().vanillaDecorations();
    }
    @Override public boolean shouldGenerateMobs(WorldInfo worldInfo,Random random,int chunkX,int chunkZ){ return true; }
    /** Structures : toujours vanilla dans le plugin principal. */
    @Override public boolean shouldGenerateStructures(WorldInfo worldInfo,Random random,int chunkX,int chunkZ){ return true; }
}
