package fr.antho.realisticworld.climate;

import fr.antho.realisticworld.config.WorldGenConfig;
import fr.antho.realisticworld.hydrology.WaterColumnEngine;
import fr.antho.realisticworld.noise.FractalNoise;
import fr.antho.realisticworld.noise.SimplexNoise;
import fr.antho.realisticworld.terrain.TerrainEngine;
import fr.antho.realisticworld.util.MathUtil;

/** Température/humidité continues avec latitude, altitude, exposition et effet orographique. */
public final class ClimateEngine {
    private final WorldGenConfig.Climate cfg;
    private final TerrainEngine terrain;
    private final WaterColumnEngine waterColumns;
    private final FractalNoise tempNoise;
    private final FractalNoise humidityNoise;
    private final SimplexNoise windNoise;

    public ClimateEngine(long seed, WorldGenConfig.Climate cfg, TerrainEngine terrain,
                         WaterColumnEngine waterColumns) {
        this.cfg = cfg;
        this.terrain = terrain;
        this.waterColumns = waterColumns;
        this.tempNoise = new FractalNoise(seed ^ 0xABCDEF1234L, 4);
        this.humidityNoise = new FractalNoise(seed ^ 0xBADC0FFEE0L, 4);
        this.windNoise = new SimplexNoise(seed ^ 0x57494E444649454CL);
    }

    public ClimateSample sample(double x, double z, double elevation) {
        return sampleInternal(x, z, elevation, true);
    }

    /** Version macro sans requête hydrologique, utilisée par les appels de planification/inspection légers. */
    public ClimateSample sampleMacro(double x, double z, double elevation) {
        return sampleInternal(x, z, elevation, false);
    }

    /**
     * Échantillon macro très léger destiné au BiomeProvider et aux recherches /locate.
     * Aucun bassin versant, aucune érosion en tuile et aucun calcul de pente n'est demandé.
     */
    public ClimateSample sampleFast(double x, double z, double elevation) {
        double period = Math.max(4000.0, cfg.latitudePeriod());
        double latWave = Math.sin(Math.PI * z / period);
        double latitude = Math.abs(latWave);
        double synoptic = tempNoise.fbm(x * cfg.temperatureScale(), z * cfg.temperatureScale(), 2.0, 0.52) * 0.16;
        double baseTemp = 0.92 - latitude * 0.82 + synoptic;
        double altitudePenalty = Math.max(0.0, elevation - terrain.seaLevel()) * cfg.altitudeLapseRate();
        double temp = MathUtil.clamp(baseTemp - altitudePenalty, 0.0, 1.0);

        double h = humidityNoise.fbm(x * cfg.humidityScale(), z * cfg.humidityScale(), 2.05, 0.53) * 0.5 + 0.5;
        double continentalness = terrain.continentalness(x, z);
        double oceanInfluence = 1.0 - MathUtil.smoothstep(-0.35, 0.45, continentalness);
        h += oceanInfluence * cfg.oceanMoisture();
        return new ClimateSample(temp, MathUtil.clamp(h, 0.0, 1.0), continentalness, 0.0, 0.0);
    }

    private ClimateSample sampleInternal(double x, double z, double elevation, boolean localWater) {
        double period = Math.max(4000.0, cfg.latitudePeriod());
        double latWave = Math.sin(Math.PI * z / period);
        double latitude = Math.abs(latWave);
        double hemisphere = latWave >= 0 ? 1.0 : -1.0;

        double synoptic = tempNoise.fbm(x * cfg.temperatureScale(), z * cfg.temperatureScale(), 2.0, 0.52) * 0.16;
        double baseTemp = 0.92 - latitude * 0.82 + synoptic;
        double altitudePenalty = Math.max(0.0, elevation - terrain.seaLevel()) * cfg.altitudeLapseRate();

        TerrainEngine.SlopeVector sv = terrain.slopeVector(x, z);
        double downhillZ = -sv.dz();
        double southFacing = downhillZ * hemisphere;
        double aspectWeight = MathUtil.clamp(sv.grade() / 0.75, 0, 1);
        double aspectTemp = southFacing * aspectWeight * cfg.aspectStrength();
        double temp = MathUtil.clamp(baseTemp - altitudePenalty + aspectTemp, 0.0, 1.0);

        double h = humidityNoise.fbm(x * cfg.humidityScale(), z * cfg.humidityScale(), 2.05, 0.53) * 0.5 + 0.5;
        double continentalness = terrain.continentalness(x, z);
        double oceanInfluence = 1.0 - MathUtil.smoothstep(-0.35, 0.45, continentalness);
        h += oceanInfluence * cfg.oceanMoisture();

        double windAngle = (windNoise.sample(x * 0.00012, z * 0.00012) * 0.42 + 0.08) * Math.PI;
        double wx = Math.cos(windAngle), wz = Math.sin(windAngle);
        double upwind = terrain.heightWithoutRivers(x - wx * 192.0, z - wz * 192.0);
        double downwind = terrain.heightWithoutRivers(x + wx * 128.0, z + wz * 128.0);
        double rise = (elevation - upwind) / 120.0;
        double lee = (upwind - downwind) / 160.0;
        h += MathUtil.clamp(rise, -0.18, 0.26) * cfg.orographicStrength();
        h -= Math.max(0.0, lee) * cfg.orographicStrength() * 0.62;
        h -= Math.max(0.0, southFacing) * aspectWeight * cfg.aspectStrength() * 0.42;

        // Les effets d'eau locaux réutilisent la colonne déjà cachée au lieu de recalculer
        // RiverEngine et LakeEngine indépendamment du générateur principal.
        if (localWater) {
            int ix=(int)Math.floor(x), iz=(int)Math.floor(z);
            WaterColumnEngine.ColumnSample column=waterColumns.sample(ix,iz);
            h += MathUtil.clamp(column.river().strength() * 0.09 + column.lake().strength() * 0.14, 0, 0.18);
        }

        return new ClimateSample(temp, MathUtil.clamp(h, 0.0, 1.0), continentalness,
                southFacing, MathUtil.clamp(rise, -1, 1));
    }

    public record ClimateSample(double temperature, double humidity, double continentalness,
                                double solarAspect, double windwardness) {}
}
