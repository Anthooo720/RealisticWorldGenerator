package fr.antho.realisticworld.climate;

import fr.antho.realisticworld.config.WorldGenConfig;
import fr.antho.realisticworld.hydrology.LakeEngine;
import fr.antho.realisticworld.hydrology.RiverEngine;
import fr.antho.realisticworld.noise.FractalNoise;
import fr.antho.realisticworld.noise.SimplexNoise;
import fr.antho.realisticworld.terrain.TerrainEngine;
import fr.antho.realisticworld.util.MathUtil;

/** Température/humidité continues avec latitude, altitude, exposition et effet orographique. */
public final class ClimateEngine {
    private final WorldGenConfig.Climate cfg;
    private final TerrainEngine terrain;
    private final RiverEngine rivers;
    private final LakeEngine lakes;
    private final FractalNoise tempNoise;
    private final FractalNoise humidityNoise;
    private final SimplexNoise windNoise;

    public ClimateEngine(long seed, WorldGenConfig.Climate cfg, TerrainEngine terrain,
                         RiverEngine rivers, LakeEngine lakes) {
        this.cfg = cfg;
        this.terrain = terrain;
        this.rivers = rivers;
        this.lakes = lakes;
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
     * Le climat reste cohérent à grande échelle, mais les micro-effets de rivière/exposition
     * sont volontairement ignorés pour qu'une recherche de biome ne puisse pas saturer le serveur.
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

        // Exposition des versants : en hémisphère nord, les pentes regardant vers le sud
        // sont plus chaudes/sèches ; l'effet s'inverse dans l'autre hémisphère.
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

        // Vents dominants variables à très grande échelle + soulèvement orographique.
        double windAngle = (windNoise.sample(x * 0.00012, z * 0.00012) * 0.42 + 0.08) * Math.PI;
        double wx = Math.cos(windAngle), wz = Math.sin(windAngle);
        double upwind = terrain.heightWithoutRivers(x - wx * 192.0, z - wz * 192.0);
        double downwind = terrain.heightWithoutRivers(x + wx * 128.0, z + wz * 128.0);
        double rise = (elevation - upwind) / 120.0;
        double lee = (upwind - downwind) / 160.0;
        h += MathUtil.clamp(rise, -0.18, 0.26) * cfg.orographicStrength();
        h -= Math.max(0.0, lee) * cfg.orographicStrength() * 0.62;

        // Les pentes très exposées au soleil sèchent davantage.
        h -= Math.max(0.0, southFacing) * aspectWeight * cfg.aspectStrength() * 0.42;

        // Couloirs fluviaux et lacs humidifient localement le fond des vallées. Les
        // planificateurs macro peuvent désactiver ce coût pour ne pas construire un bassin
        // versant complet pour chaque cellule de village potentielle.
        if (localWater) {
            RiverEngine.RiverSample river = rivers.sample(x, z);
            LakeEngine.LakeSample lake = lakes.sample(x, z);
            h += MathUtil.clamp(river.strength() * 0.09 + lake.strength() * 0.14, 0, 0.18);
        }

        return new ClimateSample(temp, MathUtil.clamp(h, 0.0, 1.0), continentalness,
                southFacing, MathUtil.clamp(rise, -1, 1));
    }

    public record ClimateSample(double temperature, double humidity, double continentalness,
                                double solarAspect, double windwardness) {}
}
