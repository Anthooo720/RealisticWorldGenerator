package fr.antho.realisticworld.vegetation;

import fr.antho.realisticworld.climate.ClimateEngine;
import fr.antho.realisticworld.config.WorldGenConfig;
import fr.antho.realisticworld.hydrology.WaterColumnEngine;
import fr.antho.realisticworld.landscape.LandscapeRegionSystem;
import fr.antho.realisticworld.noise.SimplexNoise;
import fr.antho.realisticworld.terrain.TerrainEngine;
import fr.antho.realisticworld.util.MathUtil;

/**
 * Âge et structure des peuplements. Les forêts ne sont plus uniformes : patches jeunes,
 * matures, anciennes, clairières de perturbation et limite des arbres climato-altitudinale.
 */
public final class ForestSuccessionSystem {
    private final WorldGenConfig config;
    private final TerrainEngine terrain;
    private final ClimateEngine climate;
    private final WaterColumnEngine waterColumns;
    private final LandscapeRegionSystem landscape;
    private final SimplexNoise ageNoise;
    private final SimplexNoise disturbanceNoise;
    private final SimplexNoise densityNoise;

    public ForestSuccessionSystem(long seed, WorldGenConfig config, TerrainEngine terrain,
                                  ClimateEngine climate, WaterColumnEngine waterColumns,
                                  LandscapeRegionSystem landscape) {
        this.config = config;
        this.terrain = terrain;
        this.climate = climate;
        this.waterColumns = waterColumns;
        this.landscape = landscape;
        this.ageNoise = new SimplexNoise(seed ^ 0x464F524553544147L);
        this.disturbanceNoise = new SimplexNoise(seed ^ 0x444953545552424CL);
        this.densityNoise = new SimplexNoise(seed ^ 0x43414E4F505901L);
    }

    public ForestSample sample(double x, double z, double elevation) {
        ClimateEngine.ClimateSample c = climate.sample(x, z, elevation);
        double altitude = elevation - terrain.seaLevel();
        double scale = Math.max(0.00015, config.vegetation().successionScale());
        double ageRaw = ageNoise.sample(x * scale, z * scale) * 0.5 + 0.5;
        double disturbanceLine = Math.abs(disturbanceNoise.sample(x * scale * 1.8 + 61, z * scale * 1.8 - 29));
        double disturbance = 1.0 - MathUtil.smootherstep(0.035, 0.16, disturbanceLine);
        double age = MathUtil.clamp(ageRaw * (1.0 - disturbance * 0.70), 0, 1);

        double thermal = MathUtil.smoothstep(0.10, 0.30, c.temperature())
                * (1.0 - MathUtil.smoothstep(0.86, 0.98, c.temperature()) * 0.45);
        double moisture = MathUtil.smoothstep(0.20, 0.68, c.humidity());
        double treeLine = 1.0 - MathUtil.smootherstep(78.0, 124.0, altitude);
        treeLine *= MathUtil.smoothstep(0.10, 0.25, c.temperature());

        LandscapeRegionSystem.LandscapeType type = landscape.classify(x, z, elevation, terrain.seaLevel(),
                c.continentalness(), terrain.mountainInfluence(x, z), terrain.valleyInfluence(x, z));
        double regionFactor = switch (type) {
            case WETLAND_BASIN -> 0.78;
            case ALPINE_MOUNTAINS, GLACIAL_VALLEY -> 0.72;
            case CANYONLANDS -> 0.44;
            case GREAT_PLAINS -> 0.62;
            default -> 1.0;
        };

        double patch = densityNoise.sample(x * config.vegetation().groveScale(), z * config.vegetation().groveScale()) * 0.5 + 0.5;
        int ix=(int)Math.floor(x), iz=(int)Math.floor(z);
        WaterColumnEngine.ColumnSample column=waterColumns.sample(ix,iz);
        double waterBoost = MathUtil.clamp(column.river().strength() * 0.12 + column.lake().strength() * 0.10, 0, 0.16);
        double density = MathUtil.clamp(thermal * moisture * treeLine * regionFactor
                * (0.42 + patch * 0.78) + waterBoost - disturbance * 0.32, 0, 1);
        double oldGrowth = MathUtil.smootherstep(0.68, 0.93, age) * density;
        double young = (1.0 - MathUtil.smootherstep(0.36, 0.68, age)) * density;
        return new ForestSample(density, age, oldGrowth, young, disturbance, treeLine);
    }

    public record ForestSample(double density, double maturity, double oldGrowth,
                               double youngGrowth, double disturbance, double treeLine) {}
}
