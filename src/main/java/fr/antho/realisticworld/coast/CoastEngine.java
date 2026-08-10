package fr.antho.realisticworld.coast;

import fr.antho.realisticworld.config.WorldGenConfig;
import fr.antho.realisticworld.noise.FractalNoise;
import fr.antho.realisticworld.noise.SimplexNoise;
import fr.antho.realisticworld.util.MathUtil;

/**
 * Formes côtières et bathymétrie. Le moteur distingue plateau continental,
 * talus et bassin abyssal, et ajoute de rares îles sans bruit haute fréquence.
 */
public final class CoastEngine {
    private final WorldGenConfig.Ocean cfg;
    private final FractalNoise bathymetry;
    private final SimplexNoise coastWarp;
    private final SimplexNoise islandNoise;

    public CoastEngine(long seed, WorldGenConfig.Ocean cfg) {
        this.cfg = cfg;
        this.bathymetry = new FractalNoise(seed ^ 0x0CEA04B47A11L, 4);
        this.coastWarp = new SimplexNoise(seed ^ 0xC0457A11L);
        this.islandNoise = new SimplexNoise(seed ^ 0x15A1D5EEDL);
    }

    /** Ajoute des baies/caps à grande échelle et de rares archipels. */
    public double adjustContinentalness(double raw, double x, double z) {
        double coast = coastWarp.sample(x * 0.00019, z * 0.00019) * cfg.coastRuggedness();
        double adjusted = raw + coast * MathUtil.smootherstep(-0.48, 0.26, raw);

        // Îles uniquement dans la marge océanique, jamais au milieu des grands continents.
        double margin = MathUtil.smootherstep(-0.56, -0.19, raw)
                * (1.0 - MathUtil.smootherstep(-0.16, 0.06, raw));
        double island = islandNoise.sample(x * 0.00042, z * 0.00042) * 0.5 + 0.5;
        island = MathUtil.smootherstep(0.78, 0.96, island) * margin * cfg.islandFrequency() * 3.0;
        return MathUtil.clamp(adjusted + island, -1.0, 1.0);
    }

    /** Bathymétrie continue : plage/plateau -> talus -> plaine abyssale. */
    public double oceanFloor(double x, double z, double continentalness, int seaLevel) {
        double nearCoast = MathUtil.smootherstep(-0.42, -0.08, continentalness);
        double shelf = Math.pow(nearCoast, Math.max(0.35, 1.0 - cfg.shelfWidth()));
        double deep = 1.0 - shelf;
        double targetDepth = cfg.shelfDepth() * shelf + cfg.abyssDepth() * deep;

        double broad = bathymetry.fbm(x * 0.00036, z * 0.00036, 2.0, 0.52);
        double detail = bathymetry.fbm(x * 0.00105 + 41.0, z * 0.00105 - 17.0, 2.05, 0.46);
        double roughness = 2.2 + deep * 7.0;
        return seaLevel - targetDepth + broad * roughness + detail * (1.1 + deep * 2.4);
    }

    public double coastalness(double continentalness) {
        return 1.0 - MathUtil.smootherstep(0.0, 0.22, Math.abs(continentalness + 0.08));
    }
}
