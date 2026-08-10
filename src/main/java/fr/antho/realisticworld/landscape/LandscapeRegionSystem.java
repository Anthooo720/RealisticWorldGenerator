package fr.antho.realisticworld.landscape;

import fr.antho.realisticworld.config.WorldGenConfig;
import fr.antho.realisticworld.noise.FractalNoise;
import fr.antho.realisticworld.noise.SimplexNoise;
import fr.antho.realisticworld.util.MathUtil;

/** Grandes régions paysagères de plusieurs kilomètres qui modulent relief et végétation. */
public final class LandscapeRegionSystem {
    private final WorldGenConfig.Landscape cfg;
    private final FractalNoise region;
    private final SimplexNoise regionalBias;
    private final SimplexNoise wetness;
    private final SimplexNoise canyon;
    private final SimplexNoise escarpment;
    private final SimplexNoise plateau;
    private final SimplexNoise landmark;

    public LandscapeRegionSystem(long seed, WorldGenConfig.Landscape cfg) {
        this.cfg = cfg;
        this.region = new FractalNoise(seed ^ 0x4C414E4453434150L, 3);
        this.regionalBias = new SimplexNoise(seed ^ 0x524547494F4E4249L);
        this.wetness = new SimplexNoise(seed ^ 0x5745544C414E44L);
        this.canyon = new SimplexNoise(seed ^ 0x43414E594F4E53L);
        this.escarpment = new SimplexNoise(seed ^ 0x434C4946465301L);
        this.plateau = new SimplexNoise(seed ^ 0x504C415445415531L);
        this.landmark = new SimplexNoise(seed ^ 0x4C414E444D41524BL);
    }

    public RegionFactors factors(double x, double z) {
        if (!cfg.enabled()) return RegionFactors.NEUTRAL;
        double s = Math.max(0.00005, cfg.regionScale());
        double contrast=MathUtil.clamp(cfg.regionalContrast(),0.65,1.85);

        // Deux longueurs d'onde très lentes créent de vraies provinces de relief : une
        // même valeur de bruit local n'aboutit plus partout au même type de paysage.
        double macro=regionalBias.sample(x*s*0.34-211,z*s*0.34+173);
        double r = region.fbm(x * s, z * s, 2.0, 0.52);
        r=MathUtil.clamp(r*contrast+macro*(contrast-0.72)*0.24,-1,1);

        double w = wetness.sample(x * s * 1.15 + 31, z * s * 1.15 - 17) * 0.5 + 0.5;
        double c = canyon.sample(x * s * 0.88 - 77, z * s * 0.88 + 44) * 0.5 + 0.5;
        double e = Math.abs(escarpment.sample(x * s * 1.55, z * s * 1.55));
        double p = plateau.sample(x * s * 0.72 + 113, z * s * 0.72 - 89) * 0.5 + 0.5;
        double l = landmark.sample(x * s * 0.48 - 151, z * s * 0.48 + 67) * 0.5 + 0.5;

        // Le biais macro déplace légèrement les seuils, ce qui produit des régions réellement
        // distinctes plutôt qu'un mélange uniforme des mêmes formes partout dans le monde.
        double rugged = MathUtil.smootherstep(-0.24-macro*0.08,0.62-macro*0.12,r);
        double rollingCore=1.0-Math.abs(r)*0.72;
        double rolling=MathUtil.clamp(rollingCore*(0.88-macro*0.10)+0.08,0,1);
        double cliff = (1.0 - MathUtil.smootherstep(0.022, 0.17, e)) * cfg.cliffStrength()
                *(0.82+rugged*0.34);
        double wetland = MathUtil.smootherstep(0.60+macro*0.035, 0.90, w)
                * (1.0 - rugged * 0.62) * cfg.wetlandStrength();
        double canyonMask = MathUtil.smootherstep(0.66-macro*0.035, 0.91, c)
                * (0.34 + rugged * 0.72) * cfg.canyonStrength();
        double plateauMask = MathUtil.smootherstep(0.70+macro*0.025,0.92,p)
                * (0.48+0.58*(1.0-rugged)) * cfg.plateauStrength();
        double landmarkMask = MathUtil.smootherstep(0.825,0.965,l)
                * cfg.landmarkStrength()*(0.82+Math.abs(macro)*0.28);

        return new RegionFactors(MathUtil.clamp(rugged,0,1), MathUtil.clamp(rolling,0,1),
                MathUtil.clamp(wetland,0,1), MathUtil.clamp(canyonMask,0,1), MathUtil.clamp(cliff,0,1),
                MathUtil.clamp(plateauMask,0,1),MathUtil.clamp(landmarkMask,0,1));
    }

    public LandscapeType classify(double x, double z, double elevation, int seaLevel,
                                  double continentalness, double mountain, double valley) {
        RegionFactors f = factors(x, z);
        if (elevation < seaLevel + 8 && f.wetland > 0.32) return LandscapeType.WETLAND_BASIN;
        if (continentalness < -0.12 && f.cliff > 0.35) return LandscapeType.COASTAL_CLIFFS;
        if (f.canyon > 0.34 && elevation > seaLevel + 20) return LandscapeType.CANYONLANDS;
        if (f.plateau > 0.40 && elevation > seaLevel + 18) return LandscapeType.PLATEAU;
        if (mountain > 0.62 && valley > 0.45) return LandscapeType.GLACIAL_VALLEY;
        if (mountain > 0.50) return LandscapeType.ALPINE_MOUNTAINS;
        if (f.ruggedness > 0.66) return LandscapeType.HIGHLANDS;
        if (f.rolling > 0.77) return LandscapeType.ROLLING_HILLS;
        if (f.wetland > 0.24) return LandscapeType.WETLAND_BASIN;
        if (f.ruggedness < 0.32) return LandscapeType.GREAT_PLAINS;
        return LandscapeType.TEMPERATE_LOWLAND;
    }

    public enum LandscapeType {
        ALPINE_MOUNTAINS, GLACIAL_VALLEY, HIGHLANDS, ROLLING_HILLS, PLATEAU,
        TEMPERATE_LOWLAND, WETLAND_BASIN, GREAT_PLAINS, CANYONLANDS, COASTAL_CLIFFS
    }

    public record RegionFactors(double ruggedness, double rolling, double wetland,
                                double canyon, double cliff, double plateau, double landmark) {
        public static final RegionFactors NEUTRAL = new RegionFactors(0.45,0.55,0,0,0,0,0);
    }
}
