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
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;

import java.util.Random;

/** Générateur naturel : relief, géologie 3D, hydrologie, sols, grottes et végétation. */
public final class RealisticChunkGenerator extends ChunkGenerator {
    private static final BlockFace[] HORIZONTAL={BlockFace.NORTH,BlockFace.EAST,BlockFace.SOUTH,BlockFace.WEST};
    private final ContextRegistry contexts;
    private final RealisticBiomeProvider biomeProvider;

    public RealisticChunkGenerator(ContextRegistry contexts) {
        this.contexts=contexts;
        this.biomeProvider=new RealisticBiomeProvider(contexts);
    }

    @Override public void generateNoise(WorldInfo worldInfo,Random random,int chunkX,int chunkZ,ChunkData data) {
        GenerationContext ctx=contexts.forWorld(worldInfo);
        int minY=data.getMinHeight(),maxY=data.getMaxHeight();
        for(int z=0;z<16;z++) {
            int wz=chunkZ*16+z;
            for(int x=0;x<16;x++) {
                int wx=chunkX*16+x;
                WaterColumnEngine.ColumnSample column=ctx.waterColumns.sample(wx,wz);
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

    @Override public void generateSurface(WorldInfo worldInfo,Random random,int chunkX,int chunkZ,ChunkData data) {
        GenerationContext ctx=contexts.forWorld(worldInfo);
        int minY=data.getMinHeight(),maxY=data.getMaxHeight();
        for(int z=0;z<16;z++) {
            int wz=chunkZ*16+z;
            for(int x=0;x<16;x++) {
                int wx=chunkX*16+x;
                WaterColumnEngine.ColumnSample column=ctx.waterColumns.sample(wx,wz);
                double natural=column.naturalHeight();
                RiverEngine.RiverSample river=column.river();
                LakeEngine.LakeSample lake=column.lake();
                int surface=MathUtil.clamp(column.groundY(),minY+1,maxY-2);
                ClimateEngine.ClimateSample climate=ctx.climate.sample(wx,wz,natural);
                double slope=ctx.waterColumns.slope(wx,wz);
                SoilEngine.SoilProfile profile=ctx.soils.sample(wx,wz,natural,slope,climate,river,lake);
                data.setBlock(x,surface,z,profile.top());
                for(int depth=1;depth<=profile.depth()&&surface-depth>minY;depth++) {
                    data.setBlock(x,surface-depth,z,
                            depth>=Math.max(3,profile.depth()-1)?profile.deep():profile.sub());
                }
                if(profile.snowCap()&&surface+1<maxY&&!column.hasWater())
                    data.setBlock(x,surface+1,z,Material.SNOW);
            }
        }
        ctx.vegetation.render(data,chunkX,chunkZ);
        ctx.naturalFeatures.render(data,chunkX,chunkZ);
    }

    /** Carvers vanilla d'abord, puis petite surcouche RWG et détail des surfaces exposées. */
    @Override public void generateCaves(WorldInfo worldInfo,Random random,int chunkX,int chunkZ,ChunkData data) {
        GenerationContext ctx=contexts.forWorld(worldInfo);
        if(!ctx.caves.enabled()) return;
        int minY=Math.max(data.getMinHeight()+3,ctx.config.caves().minY());
        int maxY=Math.min(data.getMaxHeight()-2,ctx.config.caves().maxY());
        for(int z=0;z<16;z++) {
            int wz=chunkZ*16+z;
            for(int x=0;x<16;x++) {
                int wx=chunkX*16+x;
                double surface=ctx.waterColumns.sample(wx,wz).groundHeight();
                var geo=ctx.geology.sample(wx,wz);
                int top=Math.min(maxY,(int)Math.floor(surface)-ctx.config.caves().surfaceBuffer());
                for(int y=minY;y<=top;y++) {
                    Material current=data.getType(x,y,z);
                    if(current.isAir()||current==Material.WATER||current==Material.BEDROCK) continue;
                    if(ctx.caves.shouldCarve(geo,wx,y,wz,surface)) {
                        data.setBlock(x,y,z,ctx.caves.isDeepAquifer(wx,y,wz,surface)?Material.WATER:Material.AIR);
                    }
                }
            }
        }
        detailCaveSurfaces(ctx,data,chunkX,chunkZ,minY,maxY);
    }

    private static void detailCaveSurfaces(GenerationContext ctx,ChunkData data,int chunkX,int chunkZ,
                                           int minY,int maxY) {
        for(int z=0;z<16;z++) {
            int wz=chunkZ*16+z;
            for(int x=0;x<16;x++) {
                int wx=chunkX*16+x;
                double surface=ctx.waterColumns.sample(wx,wz).groundHeight();
                int top=Math.min(maxY,(int)Math.floor(surface)-ctx.config.caves().surfaceBuffer());
                var geo=ctx.geology.sample(wx,wz);
                for(int y=minY+1;y<top;y++) {
                    Material current=data.getType(x,y,z);
                    if(!isCaveRock(current)) continue;
                    boolean airAbove=data.getType(x,y+1,z).isAir();
                    boolean airBelow=data.getType(x,y-1,z).isAir();
                    if(!airAbove&&!airBelow) continue;

                    var detail=ctx.caves.detailSample(wx,y,wz);
                    Material geological=ctx.geology.rockAt(geo,wx,y,wz);
                    if(detail.rockStep()) {
                        BlockData shaped=detail.slab()
                                ? slabData(geological,airAbove)
                                : stairData(geological,airAbove,detail.facingIndex());
                        data.setBlock(x,y,z,shaped);
                        continue;
                    }

                    if(detail.naturalDecoration()&&airAbove&&y+1<top) {
                        Material target=data.getType(x,y+1,z);
                        if(target.isAir()) {
                            Material decoration=switch(geo.type()) {
                                case LIMESTONE -> Material.POINTED_DRIPSTONE;
                                case VOLCANIC -> Material.TUFF;
                                default -> y<0?Material.MOSS_CARPET:Material.GRAVEL;
                            };
                            if(decoration==Material.TUFF||decoration==Material.GRAVEL)
                                data.setBlock(x,y,z,decoration);
                            else data.setBlock(x,y+1,z,decoration);
                        }
                    } else if(detail.naturalDecoration()&&airBelow&&data.getType(x,y-1,z).isAir()) {
                        if(y>0) data.setBlock(x,y-1,z,Material.HANGING_ROOTS);
                    }
                }
            }
        }
    }

    private static boolean isCaveRock(Material material) {
        return material==Material.STONE||material==Material.GRANITE||material==Material.DIORITE
                ||material==Material.ANDESITE||material==Material.TUFF
                ||material==Material.DEEPSLATE||material==Material.CALCITE;
    }

    private static BlockData slabData(Material rock,boolean floor) {
        Material material=switch(rock) {
            case GRANITE -> Material.GRANITE_SLAB;
            case DIORITE -> Material.DIORITE_SLAB;
            case ANDESITE -> Material.ANDESITE_SLAB;
            case TUFF -> Material.TUFF_SLAB;
            case DEEPSLATE -> Material.COBBLED_DEEPSLATE_SLAB;
            default -> Material.STONE_SLAB;
        };
        Slab slab=(Slab)material.createBlockData();
        slab.setType(floor?Slab.Type.BOTTOM:Slab.Type.TOP);
        return slab;
    }

    private static BlockData stairData(Material rock,boolean floor,int facing) {
        Material material=switch(rock) {
            case GRANITE -> Material.GRANITE_STAIRS;
            case DIORITE -> Material.DIORITE_STAIRS;
            case ANDESITE -> Material.ANDESITE_STAIRS;
            case TUFF -> Material.TUFF_STAIRS;
            case DEEPSLATE -> Material.COBBLED_DEEPSLATE_STAIRS;
            default -> Material.STONE_STAIRS;
        };
        Stairs stairs=(Stairs)material.createBlockData();
        stairs.setHalf(floor?Bisected.Half.BOTTOM:Bisected.Half.TOP);
        stairs.setFacing(HORIZONTAL[Math.floorMod(facing,HORIZONTAL.length)]);
        return stairs;
    }

    /**
     * Heightmap exacte de la passe noise. Elle reste identique au relief réellement écrit
     * dans generateNoise : Paper peut donc adapter ses structures vanilla sans divergence
     * entre la hauteur annoncée et le terrain présent.
     */
    @Override public int getBaseHeight(WorldInfo worldInfo,Random random,int x,int z,HeightMap heightMap) {
        GenerationContext ctx=contexts.forWorld(worldInfo);
        WaterColumnEngine.ColumnSample column=ctx.waterColumns.sample(x,z);
        int groundTop=MathUtil.clamp(column.groundY(),worldInfo.getMinHeight(),worldInfo.getMaxHeight()-1);
        int worldTop=MathUtil.clamp(column.worldSurfaceY(),worldInfo.getMinHeight(),worldInfo.getMaxHeight()-1);
        return switch(heightMap) {
            case OCEAN_FLOOR,OCEAN_FLOOR_WG -> groundTop;
            case WORLD_SURFACE,WORLD_SURFACE_WG,MOTION_BLOCKING,MOTION_BLOCKING_NO_LEAVES -> worldTop;
        };
    }

    @Override public BiomeProvider getDefaultBiomeProvider(WorldInfo worldInfo){ return biomeProvider; }
    @Override public boolean shouldGenerateNoise(WorldInfo worldInfo,Random random,int chunkX,int chunkZ){ return false; }
    @Override public boolean shouldGenerateSurface(WorldInfo worldInfo,Random random,int chunkX,int chunkZ){ return false; }

    @Override public boolean shouldGenerateCaves(WorldInfo worldInfo,Random random,int chunkX,int chunkZ) {
        GenerationContext ctx=contexts.forWorld(worldInfo);
        if(!ctx.config.compatibility().vanillaCaves()) return false;
        if(ctx.config.caves().protectOceanCarvers()) {
            double landRatio=ctx.waterColumns.landRatioForCaves(chunkX,chunkZ);
            if(landRatio<=ctx.config.caves().oceanCarverMaxLandRatio()) return false;
        }
        return true;
    }

    @Override public boolean shouldGenerateDecorations(WorldInfo worldInfo,Random random,int chunkX,int chunkZ) {
        return contexts.forWorld(worldInfo).config.compatibility().vanillaDecorations();
    }

    @Override public boolean shouldGenerateMobs(WorldInfo worldInfo,Random random,int chunkX,int chunkZ){ return true; }

    /** Structures : toujours 100% vanilla dans le plugin principal. */
    @Override public boolean shouldGenerateStructures(WorldInfo worldInfo,Random random,int chunkX,int chunkZ){ return true; }
}
