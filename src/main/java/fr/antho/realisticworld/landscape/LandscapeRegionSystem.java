package fr.antho.realisticworld.landscape;

import fr.antho.realisticworld.config.WorldGenConfig;
import fr.antho.realisticworld.noise.FractalNoise;
import fr.antho.realisticworld.noise.SimplexNoise;
import fr.antho.realisticworld.util.MathUtil;

/** Grandes régions paysagères de plusieurs kilomètres qui modulent relief et végétation. */
public final class LandscapeRegionSystem {
    private final WorldGenConfig.Landscape cfg;
    private final FractalNoise region;
    private final SimplexNoise wetness;
    private final SimplexNoise canyon;
    private final SimplexNoise escarpment;

    public LandscapeRegionSystem(long seed, WorldGenConfig.Landscape cfg) {
        this.cfg = cfg;
        this.region = new FractalNoise(seed ^ 0x4C414E4453434150L, 3);
        this.wetness = new SimplexNoise(seed ^ 0x5745544C414E44L);
        this.canyon = new SimplexNoise(seed ^ 0x43414E594F4E53L);
        this.escarpment = new SimplexNoise(seed ^ 0x434C4946465301L);
    }

    public RegionFactors factors(double x, double z) {
        if (!cfg.enabled()) return RegionFactors.NEUTRAL;
        double s = Math.max(0.00005, cfg.regionScale());
        double r = region.fbm(x * s, z * s, 2.0, 0.52);
        double w = wetness.sample(x * s * 1.15 + 31, z * s * 1.15 - 17) * 0.5 + 0.5;
        double c = canyon.sample(x * s * 0.88 - 77, z * s * 0.88 + 44) * 0.5 + 0.5;
        double e = Math.abs(escarpment.sample(x * s * 1.55, z * s * 1.55));
        double cliff = (1.0 - MathUtil.smootherstep(0.025, 0.17, e)) * cfg.cliffStrength();
        double rugged = MathUtil.smootherstep(-0.18, 0.68, r);
        double rolling = 1.0 - Math.abs(r) * 0.60;
        double wetland = MathUtil.smootherstep(0.62, 0.90, w) * (1.0 - rugged * 0.55) * cfg.wetlandStrength();
        double canyonMask = MathUtil.smootherstep(0.68, 0.91, c) * (0.40 + rugged * 0.60) * cfg.canyonStrength();
        return new RegionFactors(MathUtil.clamp(rugged,0,1), MathUtil.clamp(rolling,0,1),
                MathUtil.clamp(wetland,0,1), MathUtil.clamp(canyonMask,0,1), MathUtil.clamp(cliff,0,1));
    }

    public LandscapeType classify(double x, double z, double elevation, int seaLevel,
                                  double continentalness, double mountain, double valley) {
        RegionFactors f = factors(x, z);
        if (elevation < seaLevel + 8 && f.wetland > 0.34) return LandscapeType.WETLAND_BASIN;
        if (continentalness < -0.12 && f.cliff > 0.35) return LandscapeType.COASTAL_CLIFFS;
        if (f.canyon > 0.35 && elevation > seaLevel + 20) return LandscapeType.CANYONLANDS;
        if (mountain > 0.62 && valley > 0.45) return LandscapeType.GLACIAL_VALLEY;
        if (mountain > 0.50) return LandscapeType.ALPINE_MOUNTAINS;
        if (f.ruggedness > 0.68) return LandscapeType.HIGHLANDS;
        if (f.rolling > 0.78) return LandscapeType.ROLLING_HILLS;
        if (f.wetland > 0.25) return LandscapeType.WETLAND_BASIN;
        if (f.ruggedness < 0.34) return LandscapeType.GREAT_PLAINS;
        return LandscapeType.TEMPERATE_LOWLAND;
    }

    public enum LandscapeType {
        ALPINE_MOUNTAINS, GLACIAL_VALLEY, HIGHLANDS, ROLLING_HILLS,
        TEMPERATE_LOWLAND, WETLAND_BASIN, GREAT_PLAINS, CANYONLANDS, COASTAL_CLIFFS
    }

    public record RegionFactors(double ruggedness, double rolling, double wetland,
                                double canyon, double cliff) {
        public static final RegionFactors NEUTRAL = new RegionFactors(0.45,0.55,0,0,0);
    }
}
