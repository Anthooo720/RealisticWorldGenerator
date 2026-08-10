package fr.antho.realisticworld.biome;

import fr.antho.realisticworld.gen.ContextRegistry;
import org.bukkit.block.Biome;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.WorldInfo;

import java.util.List;

/** Provider 3D partagé avec BiomeEngine, liste alignée sur les biomes vanilla Paper 26.2. */
public final class RealisticBiomeProvider extends BiomeProvider {
    /**
     * Tous les biomes vanilla exposés par Paper 26.2 (CUSTOM exclu car déprécié).
     * Les biomes Nether/End restent naturellement dimensionnels et ne sont pas injectés
     * dans l'Overworld RWG ; ils figurent ici pour que le provider ne maintienne plus une
     * liste partielle qui devient obsolète à chaque version.
     */
    private static final List<Biome> SUPPORTED = List.of(
            Biome.BADLANDS, Biome.BAMBOO_JUNGLE, Biome.BASALT_DELTAS, Biome.BEACH,
            Biome.BIRCH_FOREST, Biome.CHERRY_GROVE, Biome.COLD_OCEAN, Biome.CRIMSON_FOREST,
            Biome.DARK_FOREST, Biome.DEEP_COLD_OCEAN, Biome.DEEP_DARK, Biome.DEEP_FROZEN_OCEAN,
            Biome.DEEP_LUKEWARM_OCEAN, Biome.DEEP_OCEAN, Biome.DESERT, Biome.DRIPSTONE_CAVES,
            Biome.END_BARRENS, Biome.END_HIGHLANDS, Biome.END_MIDLANDS, Biome.ERODED_BADLANDS,
            Biome.FLOWER_FOREST, Biome.FOREST, Biome.FROZEN_OCEAN, Biome.FROZEN_PEAKS,
            Biome.FROZEN_RIVER, Biome.GROVE, Biome.ICE_SPIKES, Biome.JAGGED_PEAKS,
            Biome.JUNGLE, Biome.LUKEWARM_OCEAN, Biome.LUSH_CAVES, Biome.MANGROVE_SWAMP,
            Biome.MEADOW, Biome.MUSHROOM_FIELDS, Biome.NETHER_WASTES, Biome.OCEAN,
            Biome.OLD_GROWTH_BIRCH_FOREST, Biome.OLD_GROWTH_PINE_TAIGA,
            Biome.OLD_GROWTH_SPRUCE_TAIGA, Biome.PALE_GARDEN, Biome.PLAINS, Biome.RIVER,
            Biome.SAVANNA, Biome.SAVANNA_PLATEAU, Biome.SMALL_END_ISLANDS, Biome.SNOWY_BEACH,
            Biome.SNOWY_PLAINS, Biome.SNOWY_SLOPES, Biome.SNOWY_TAIGA, Biome.SOUL_SAND_VALLEY,
            Biome.SPARSE_JUNGLE, Biome.STONY_PEAKS, Biome.STONY_SHORE, Biome.SULFUR_CAVES,
            Biome.SUNFLOWER_PLAINS, Biome.SWAMP, Biome.TAIGA, Biome.THE_END, Biome.THE_VOID,
            Biome.WARM_OCEAN, Biome.WARPED_FOREST, Biome.WINDSWEPT_FOREST,
            Biome.WINDSWEPT_GRAVELLY_HILLS, Biome.WINDSWEPT_HILLS, Biome.WINDSWEPT_SAVANNA,
            Biome.WOODED_BADLANDS
    );

    private final ContextRegistry contexts;
    public RealisticBiomeProvider(ContextRegistry contexts) { this.contexts = contexts; }

    @Override
    public Biome getBiome(WorldInfo worldInfo, int x, int y, int z) {
        return contexts.forWorld(worldInfo).biomes.getBiome(x,y,z);
    }

    @Override public List<Biome> getBiomes(WorldInfo worldInfo) { return SUPPORTED; }
}
