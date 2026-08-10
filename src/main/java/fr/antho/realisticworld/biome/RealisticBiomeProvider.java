package fr.antho.realisticworld.biome;

import fr.antho.realisticworld.gen.ContextRegistry;
import org.bukkit.block.Biome;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.WorldInfo;

import java.util.List;

/** Provider léger : toute la classification partagée vit dans BiomeEngine. */
public final class RealisticBiomeProvider extends BiomeProvider {
    private static final List<Biome> USED = List.of(
            Biome.PLAINS, Biome.FOREST, Biome.BIRCH_FOREST, Biome.DARK_FOREST,
            Biome.TAIGA, Biome.SNOWY_TAIGA, Biome.SNOWY_PLAINS, Biome.SNOWY_SLOPES,
            Biome.FROZEN_PEAKS, Biome.JAGGED_PEAKS, Biome.STONY_PEAKS, Biome.MEADOW,
            Biome.CHERRY_GROVE,
            Biome.DESERT, Biome.SAVANNA, Biome.JUNGLE, Biome.SPARSE_JUNGLE,
            Biome.SWAMP, Biome.BADLANDS, Biome.WOODED_BADLANDS,
            Biome.BEACH, Biome.SNOWY_BEACH, Biome.STONY_SHORE,
            Biome.RIVER, Biome.FROZEN_RIVER,
            Biome.OCEAN, Biome.COLD_OCEAN, Biome.FROZEN_OCEAN, Biome.LUKEWARM_OCEAN, Biome.WARM_OCEAN,
            Biome.DEEP_OCEAN, Biome.DEEP_COLD_OCEAN, Biome.DEEP_FROZEN_OCEAN, Biome.DEEP_LUKEWARM_OCEAN
    );

    private final ContextRegistry contexts;
    public RealisticBiomeProvider(ContextRegistry contexts) { this.contexts = contexts; }

    @Override
    public Biome getBiome(WorldInfo worldInfo, int x, int y, int z) {
        return contexts.forWorld(worldInfo).biomes.getBiome(x, z);
    }

    @Override public List<Biome> getBiomes(WorldInfo worldInfo) { return USED; }
}
