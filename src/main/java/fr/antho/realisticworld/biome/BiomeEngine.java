package fr.antho.realisticworld.biome;

import fr.antho.realisticworld.climate.ClimateEngine;
import fr.antho.realisticworld.gen.GenerationContext;
import fr.antho.realisticworld.landscape.LandscapeRegionSystem;
import fr.antho.realisticworld.noise.SimplexNoise;
import fr.antho.realisticworld.util.MathUtil;
import org.bukkit.block.Biome;

/**
 * Classification macro des biomes utilisée à la fois par le BiomeProvider et la végétation.
 *
 * Deux règles de gameplay sont volontairement intégrées ici :
 *  - les biomes pouvant accueillir un village vanilla sont réservés aux terrains macro
 *    suffisamment ouverts et plats ;
 *  - CHERRY_GROVE est une région rare et explicite. Aucun cerisier RWG n'est autorisé
 *    en dehors de ce biome.
 */
public final class BiomeEngine {
    private final GenerationContext ctx;
    private final SimplexNoise opennessNoise;
    private final SimplexNoise cherryNoise;
    private final SimplexNoise woodlandNoise;

    public BiomeEngine(GenerationContext ctx) {
        this.ctx = ctx;
        long seed = ctx.seed;
        this.opennessNoise = new SimplexNoise(seed ^ 0x42494F4D454F504EL);
        this.cherryNoise = new SimplexNoise(seed ^ 0x4348455252594752L);
        this.woodlandNoise = new SimplexNoise(seed ^ 0x574F4F444C414E44L);
    }

    public Biome getBiome(int x, int z) {
        double elevation = ctx.terrain.baseHeightRaw(x, z);
        ClimateEngine.ClimateSample c = ctx.climate.sampleFast(x, z, elevation);
        int sea = ctx.terrain.seaLevel();

        if (elevation < sea - 3) {
            boolean deep = elevation < sea - 24;
            double t = c.temperature();
            if (t < 0.18) return deep ? Biome.DEEP_FROZEN_OCEAN : Biome.FROZEN_OCEAN;
            if (t < 0.34) return deep ? Biome.DEEP_COLD_OCEAN : Biome.COLD_OCEAN;
            if (t > 0.82 && !deep) return Biome.WARM_OCEAN;
            if (t > 0.62) return deep ? Biome.DEEP_LUKEWARM_OCEAN : Biome.LUKEWARM_OCEAN;
            return deep ? Biome.DEEP_OCEAN : Biome.OCEAN;
        }

        double altitude = elevation - sea;
        double mountain = ctx.terrain.mountainInfluence(x, z);
        double valley = ctx.terrain.valleyInfluence(x, z);

        // Approximation légère des grands couloirs fluviaux pour garder /locate biome rapide.
        if (altitude > 2 && altitude < 42 && valley > 0.84 && mountain < 0.56) {
            return c.temperature() < 0.24 ? Biome.FROZEN_RIVER : Biome.RIVER;
        }

        if (elevation <= sea + 3) {
            if (mountain > 0.62) return Biome.STONY_SHORE;
            return c.temperature() < 0.26 ? Biome.SNOWY_BEACH : Biome.BEACH;
        }

        LandscapeRegionSystem.LandscapeType region = ctx.landscape.classify(x, z, elevation, sea,
                c.continentalness(), mountain, valley);

        if (altitude > 105) {
            if (c.temperature() < 0.25) return Biome.FROZEN_PEAKS;
            if (mountain > 0.72) return Biome.JAGGED_PEAKS;
            return Biome.STONY_PEAKS;
        }
        if (altitude > 62) {
            if (c.temperature() < 0.30) return Biome.SNOWY_SLOPES;
            if (c.humidity() > 0.44 && mountain < 0.72) return Biome.MEADOW;
            return Biome.STONY_PEAKS;
        }

        double slope = macroSlope(x, z, elevation);
        double openness = openness(x, z);
        var biomeCfg=ctx.config.biomes();
        double openScore=openness+biomeCfg.openRegionBias()*(1.0-MathUtil.clamp(mountain*2.2,0,1));
        boolean openFlat = slope < biomeCfg.openFlatMaxSlope() && mountain < 0.27
                && openScore > biomeCfg.openFlatMinOpenness();

        // Cherry grove : rare, tempéré, humide, en relief doux. Ce test est la seule
        // porte d'entrée vers CHERRY_GROVE ; la végétation réutilise exactement ce biome.
        double cherry = cherryNoise.sample(x * 0.00058 + 17.0, z * 0.00058 - 43.0) * 0.5 + 0.5;
        if (altitude > 24 && altitude < 76 && slope < 0.24 && mountain > 0.08 && mountain < 0.55
                && c.temperature() > 0.40 && c.temperature() < 0.64
                && c.humidity() > 0.54 && c.humidity() < 0.86 && cherry > 0.79) {
            return Biome.CHERRY_GROVE;
        }

        if (region == LandscapeRegionSystem.LandscapeType.WETLAND_BASIN && c.humidity() > 0.62)
            return Biome.SWAMP;
        if (region == LandscapeRegionSystem.LandscapeType.CANYONLANDS && c.humidity() < 0.40)
            return c.temperature() > 0.62 ? Biome.BADLANDS : Biome.WOODED_BADLANDS;

        if (altitude > 26 && mountain > 0.18) {
            if (c.temperature() < 0.58 && c.humidity() > 0.34) return Biome.TAIGA;
            if (c.temperature() < 0.70 && c.humidity() > 0.56) return Biome.FOREST;
            if (valley > 0.42 && c.humidity() > 0.44) return Biome.MEADOW;
        }

        return whittaker(c.temperature(), c.humidity(), openFlat, slope, openScore, x, z);
    }

    /** Biomes de village vanilla que RWG réserve aux zones ouvertes/plates. */
    public boolean isVillageOpenBiome(Biome biome) {
        return biome == Biome.PLAINS || biome == Biome.SAVANNA || biome == Biome.DESERT
                || biome == Biome.SNOWY_PLAINS || biome == Biome.TAIGA;
    }

    public double openness(int x, int z) {
        double a = opennessNoise.sample(x * 0.00074, z * 0.00074) * 0.5 + 0.5;
        double b = woodlandNoise.sample(x * 0.00155 + 61, z * 0.00155 - 29) * 0.5 + 0.5;
        return MathUtil.clamp(a * 0.72 + (1.0 - b) * 0.28, 0, 1);
    }

    private Biome whittaker(double t, double h, boolean openFlat, double slope, double openness, int x, int z) {
        var cfg=ctx.config.biomes();
        double forestH=MathUtil.clamp(cfg.temperateForestHumidity(),0.48,0.88);
        double darkH=Math.max(forestH+0.08,MathUtil.clamp(cfg.darkForestHumidity(),0.64,0.96));

        if (t < 0.17) {
            if (openFlat && h < 0.68) return Biome.SNOWY_PLAINS;
            return Biome.SNOWY_TAIGA;
        }
        if (t < 0.34) {
            if (openFlat && openness > 0.52 && h < 0.72) return Biome.PLAINS;
            if (h > 0.76) return Biome.SNOWY_TAIGA;
            if (h > 0.52 && openness < 0.62) return Biome.FOREST;
            return openFlat ? Biome.PLAINS : Biome.BIRCH_FOREST;
        }
        if (t < 0.62) {
            if (h > darkH && !openFlat) return Biome.DARK_FOREST;
            if (h > forestH && (!openFlat || openness < 0.48)) return Biome.FOREST;
            if (h > 0.30) {
                if (openFlat || openness > 0.58) return Biome.PLAINS;
                return openness < 0.34 ? Biome.FOREST : Biome.BIRCH_FOREST;
            }
            return slope > 0.18 ? Biome.WOODED_BADLANDS : Biome.PLAINS;
        }
        if (t < 0.82) {
            if (h > 0.86 && !openFlat) return Biome.JUNGLE;
            if (h > 0.70 && openness < 0.56) return Biome.SPARSE_JUNGLE;
            if (h > 0.34) return (openFlat || openness > 0.54) ? Biome.SAVANNA : Biome.SPARSE_JUNGLE;
            if (h < 0.19) return openFlat ? Biome.DESERT : Biome.BADLANDS;
            return openFlat ? Biome.SAVANNA : Biome.BADLANDS;
        }
        if (h < 0.25) return openFlat ? Biome.DESERT : Biome.BADLANDS;
        if (h < 0.56) return (openFlat || openness > 0.55) ? Biome.SAVANNA : Biome.BADLANDS;
        if (h > 0.88 && openness < 0.48) return Biome.JUNGLE;
        return Biome.SPARSE_JUNGLE;
    }

    private double macroSlope(int x, int z, double center) {
        final int d = 12;
        double dx = Math.max(Math.abs(ctx.terrain.baseHeightRaw(x + d, z) - center),
                Math.abs(ctx.terrain.baseHeightRaw(x - d, z) - center)) / d;
        double dz = Math.max(Math.abs(ctx.terrain.baseHeightRaw(x, z + d) - center),
                Math.abs(ctx.terrain.baseHeightRaw(x, z - d) - center)) / d;
        return Math.max(dx, dz);
    }
}
