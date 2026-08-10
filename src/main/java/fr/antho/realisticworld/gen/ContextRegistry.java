package fr.antho.realisticworld.gen;

import fr.antho.realisticworld.config.WorldGenConfig;
import org.bukkit.generator.WorldInfo;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class ContextRegistry {
    private final WorldGenConfig config;
    private final ConcurrentMap<Long, GenerationContext> contexts = new ConcurrentHashMap<>();

    public ContextRegistry(WorldGenConfig config) {
        this.config = config;
    }

    public GenerationContext forWorld(WorldInfo worldInfo) {
        long seed = config.effectiveSeed(worldInfo.getSeed());
        return contexts.computeIfAbsent(seed, s -> new GenerationContext(s, config));
    }
}
