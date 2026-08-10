package fr.antho.realisticworld.gen;

import fr.antho.realisticworld.cave.CaveEngine;
import fr.antho.realisticworld.biome.BiomeEngine;
import fr.antho.realisticworld.climate.ClimateEngine;
import fr.antho.realisticworld.coast.CoastEngine;
import fr.antho.realisticworld.config.WorldGenConfig;
import fr.antho.realisticworld.features.NaturalFeatureGenerator;
import fr.antho.realisticworld.geology.GeologyMap;
import fr.antho.realisticworld.hydrology.LakeEngine;
import fr.antho.realisticworld.hydrology.RiverEngine;
import fr.antho.realisticworld.hydrology.WatershedEngine;
import fr.antho.realisticworld.hydrology.WaterColumnEngine;
import fr.antho.realisticworld.landscape.LandscapeRegionSystem;
import fr.antho.realisticworld.soil.SoilEngine;
import fr.antho.realisticworld.terrain.ErosionEngine;
import fr.antho.realisticworld.terrain.MountainGraph;
import fr.antho.realisticworld.terrain.TerrainEngine;
import fr.antho.realisticworld.terrain.ValleyGraph;
import fr.antho.realisticworld.vegetation.ForestSuccessionSystem;
import fr.antho.realisticworld.vegetation.VegetationGenerator;

/** Tous les sous-systèmes naturels, immuables et déterministes pour une seed de monde. */
public final class GenerationContext {
    public final long seed;
    public final WorldGenConfig config;
    public final CoastEngine coast;
    public final GeologyMap geology;
    public final LandscapeRegionSystem landscape;
    public final MountainGraph mountainGraph;
    public final ValleyGraph valleyGraph;
    public final TerrainEngine terrain;
    public final ErosionEngine erosion;
    public final WatershedEngine watersheds;
    public final RiverEngine rivers;
    public final LakeEngine lakes;
    public final WaterColumnEngine waterColumns;
    public final ClimateEngine climate;
    public final SoilEngine soils;
    public final CaveEngine caves;
    public final ForestSuccessionSystem forestSuccession;
    public final BiomeEngine biomes;
    public final VegetationGenerator vegetation;
    public final NaturalFeatureGenerator naturalFeatures;

    public GenerationContext(long seed, WorldGenConfig config) {
        this.seed=seed; this.config=config;
        this.coast=new CoastEngine(seed,config.ocean());
        this.geology=new GeologyMap(seed,config.geology());
        this.landscape=new LandscapeRegionSystem(seed,config.landscape());
        this.mountainGraph=new MountainGraph(seed,config.terrain());
        this.valleyGraph=new ValleyGraph(seed,config.terrain());
        this.terrain=new TerrainEngine(seed,config.terrain(),mountainGraph,valleyGraph,geology,landscape,coast);
        this.erosion=new ErosionEngine(seed,terrain,geology,config.erosion(),config.performance().erosionCacheTiles());
        this.terrain.attachErosion(erosion);
        this.watersheds=new WatershedEngine(seed,terrain,config.rivers(),config.performance().watershedCacheTiles());
        this.rivers=new RiverEngine(seed,terrain,watersheds,config.rivers(),config.ocean(),config.performance().riverCacheTiles());
        this.lakes=new LakeEngine(seed,terrain,watersheds,config.lakes());
        this.waterColumns=new WaterColumnEngine(terrain,rivers,lakes,config.rivers(),config.performance().columnCacheChunks());
        this.climate=new ClimateEngine(seed,config.climate(),terrain,rivers,lakes);
        this.soils=new SoilEngine(terrain,geology,landscape);
        this.caves=new CaveEngine(seed,config.caves(),terrain,geology);
        this.forestSuccession=new ForestSuccessionSystem(seed,config,terrain,climate,rivers,lakes,landscape);
        this.biomes=new BiomeEngine(this);
        this.vegetation=new VegetationGenerator(seed,config,terrain,climate,rivers,lakes,waterColumns,forestSuccession,biomes);
        this.naturalFeatures=new NaturalFeatureGenerator(seed,config,terrain,geology,climate,rivers,lakes,waterColumns,forestSuccession,biomes);
    }
}
