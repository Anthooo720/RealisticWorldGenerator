package fr.antho.realisticworld.config;

import org.bukkit.configuration.file.FileConfiguration;

/** Snapshot immutable du config.yml : aucun accès YAML pendant la génération parallèle. */
public record WorldGenConfig(
        long configuredSeed,
        Terrain terrain,
        Geology geology,
        Landscape landscape,
        Ocean ocean,
        Erosion erosion,
        Rivers rivers,
        Lakes lakes,
        Climate climate,
        Biomes biomes,
        Caves caves,
        Vegetation vegetation,
        NaturalFeatures naturalFeatures,
        Compatibility compatibility,
        Performance performance
) {
    public static WorldGenConfig load(FileConfiguration c) {
        return new WorldGenConfig(
                c.getLong("generation.seed", 0L),
                new Terrain(
                        c.getInt("terrain.sea-level", 63),
                        c.getDouble("terrain.base-scale", 0.00118),
                        c.getDouble("terrain.continent-scale", 0.00032),
                        c.getDouble("terrain.warp-scale", 0.00042),
                        c.getDouble("terrain.warp-strength", 150.0),
                        c.getDouble("terrain.hill-height", 28.0),
                        c.getDouble("terrain.foothill-height", 34.0),
                        c.getDouble("terrain.mountain-height", 112.0),
                        c.getDouble("terrain.mountain-chain-width", 0.31),
                        c.getDouble("terrain.alpine-ruggedness", 0.88),
                        c.getDouble("terrain.valley-depth", 18.0),
                        c.getInt("terrain.octaves", 5),
                        c.getInt("terrain.tectonic-cell-size", 5600),
                        c.getDouble("terrain.tectonic-boundary-width", 1450.0),
                        c.getDouble("terrain.glacial-valley-strength", 0.78),
                        c.getDouble("terrain.micro-relief", 4.2)
                ),
                new Geology(
                        c.getBoolean("geology.enabled", true),
                        c.getInt("geology.province-size", 5200),
                        c.getDouble("geology.contact-blend", 0.18),
                        c.getDouble("geology.strata-scale", 0.010),
                        c.getDouble("geology.strata-thickness", 7.0),
                        c.getDouble("geology.dip-strength", 0.34)
                ),
                new Landscape(
                        c.getBoolean("landscape.enabled", true),
                        c.getDouble("landscape.region-scale", 0.00020),
                        c.getDouble("landscape.cliff-strength", 0.72),
                        c.getDouble("landscape.canyon-strength", 0.62),
                        c.getDouble("landscape.wetland-strength", 0.55),
                        c.getDouble("landscape.plateau-strength", 0.68),
                        c.getDouble("landscape.landmark-strength", 0.74),
                        c.getDouble("landscape.regional-contrast", 1.18)
                ),
                new Ocean(
                        c.getDouble("ocean.shelf-width", 0.24),
                        c.getDouble("ocean.shelf-depth", 18.0),
                        c.getDouble("ocean.abyss-depth", 58.0),
                        c.getDouble("ocean.coast-ruggedness", 0.24),
                        c.getDouble("ocean.island-frequency", 0.16),
                        c.getDouble("ocean.estuary-strength", 0.42)
                ),
                new Erosion(
                        c.getBoolean("erosion.enabled", true),
                        c.getInt("erosion.tile-size", 320),
                        c.getInt("erosion.sample-spacing", 10),
                        c.getInt("erosion.margin-samples", 9),
                        c.getInt("erosion.hydraulic-iterations", 28),
                        c.getInt("erosion.thermal-iterations", 4),
                        c.getDouble("erosion.rainfall", 0.40),
                        c.getDouble("erosion.evaporation", 0.15),
                        c.getDouble("erosion.capacity", 1.58),
                        c.getDouble("erosion.erosion-rate", 0.23),
                        c.getDouble("erosion.deposition-rate", 0.14),
                        c.getDouble("erosion.talus", 2.50),
                        c.getDouble("erosion.intensity", 0.94),
                        c.getDouble("erosion.velocity-inertia", 0.44)
                ),
                new Rivers(
                        c.getBoolean("rivers.enabled", true),
                        c.getInt("rivers.tile-size", 640),
                        c.getInt("rivers.sample-spacing", 6),
                        c.getInt("rivers.margin-samples", 22),
                        c.getDouble("rivers.accumulation-threshold", 46.0),
                        c.getDouble("rivers.max-carve-depth", 5.2),
                        c.getDouble("rivers.max-width", 17.0),
                        c.getDouble("rivers.min-width", 1.7),
                        c.getDouble("rivers.bank-buffer", 1.45),
                        c.getDouble("rivers.max-water-depth", 2.6),
                        c.getDouble("rivers.max-wet-grade", 0.17),
                        c.getDouble("rivers.waterfall-grade", 0.11),
                        c.getInt("rivers.terrace-run", 14),
                        c.getDouble("rivers.meander-scale", 0.0027),
                        c.getDouble("rivers.meander-strength", 1.02),
                        c.getDouble("rivers.floodplain-width", 11.0),
                        c.getDouble("rivers.profile-exponent", 1.55),
                        c.getDouble("rivers.bank-slope-width", 7.0),
                        c.getDouble("rivers.bank-max-cut", 2.0),
                        c.getDouble("rivers.floodplain-max-cut", 1.4),
                        c.getDouble("rivers.edge-roughness", 0.40),
                        c.getDouble("rivers.secondary-channel-frequency", 0.07),
                        c.getDouble("rivers.coastal-merge-height", 14.0),
                        c.getDouble("rivers.coastal-water-gradient", 0.34),
                        c.getDouble("rivers.channel-bed-flatness", 0.38),
                        c.getDouble("rivers.bank-height-jitter", 0.26),
                        c.getDouble("rivers.thalweg-offset", 0.14),
                        c.getDouble("rivers.bank-transition-power", 1.0)
                ),
                new Lakes(
                        c.getBoolean("lakes.enabled", true),
                        c.getInt("lakes.cell-size", 1800),
                        c.getDouble("lakes.frequency", 0.20),
                        c.getDouble("lakes.min-radius", 26.0),
                        c.getDouble("lakes.max-radius", 105.0),
                        c.getDouble("lakes.max-depth", 9.0),
                        c.getDouble("lakes.min-rim-height", 4.5),
                        c.getDouble("lakes.coast-guard-height", 12.0),
                        c.getInt("lakes.rim-samples", 28),
                        c.getDouble("lakes.river-connect-distance", 20.0),
                        c.getDouble("lakes.shore-blend-width", 6.0),
                        c.getDouble("lakes.bed-roughness", 0.42)
                ),
                new Climate(
                        c.getDouble("climate.latitude-period", 24000.0),
                        c.getDouble("climate.temperature-scale", 0.00062),
                        c.getDouble("climate.humidity-scale", 0.00072),
                        c.getDouble("climate.altitude-lapse-rate", 0.0030),
                        c.getDouble("climate.ocean-moisture", 0.20),
                        c.getDouble("climate.aspect-strength", 0.12),
                        c.getDouble("climate.orographic-strength", 0.22)
                ),
                new Biomes(
                        c.getDouble("biomes.open-flat-max-slope", 0.095),
                        c.getDouble("biomes.open-flat-min-openness", 0.50),
                        c.getDouble("biomes.temperate-forest-humidity", 0.70),
                        c.getDouble("biomes.dark-forest-humidity", 0.86),
                        c.getDouble("biomes.open-region-bias", 0.12),
                        c.getInt("biomes.transition-radius", 14),
                        c.getDouble("biomes.transition-patch-scale", 0.045),
                        c.getDouble("biomes.rare-biome-frequency", 0.18)
                ),
                new Caves(
                        c.getBoolean("caves.enabled", true),
                        c.getDouble("caves.large-scale", 0.018),
                        c.getDouble("caves.detail-scale", 0.047),
                        c.getDouble("caves.threshold", 0.72),
                        c.getInt("caves.min-y", -52),
                        c.getInt("caves.max-y", 112),
                        c.getInt("caves.surface-buffer", 12),
                        c.getInt("caves.ocean-buffer", 18),
                        c.getDouble("caves.tunnel-scale", 0.024),
                        c.getDouble("caves.tunnel-radius", 0.094),
                        c.getInt("caves.chamber-spacing", 152),
                        c.getDouble("caves.chamber-frequency", 0.068),
                        c.getDouble("caves.max-chamber-radius", 10.0),
                        c.getDouble("caves.overlay-strength", 0.76),
                        c.getDouble("caves.vertical-link-frequency", 0.038),
                        c.getDouble("caves.aquifer-frequency", 0.045),
                        c.getInt("caves.aquifer-max-y", -18),
                        c.getBoolean("caves.protect-ocean-carvers", true),
                        c.getDouble("caves.ocean-carver-max-land-ratio", 0.34),
                        c.getDouble("caves.surface-detail-strength", 0.34),
                        c.getDouble("caves.decoration-frequency", 0.018),
                        c.getDouble("caves.step-frequency", 0.009)
                ),
                new Vegetation(
                        c.getBoolean("vegetation.enabled", true),
                        c.getDouble("vegetation.tree-density", 0.074),
                        c.getDouble("vegetation.shrub-density", 0.32),
                        c.getDouble("vegetation.ground-cover-density", 0.62),
                        c.getDouble("vegetation.boulder-density", 0.018),
                        c.getDouble("vegetation.grove-scale", 0.00120),
                        c.getDouble("vegetation.succession-scale", 0.00048),
                        c.getDouble("vegetation.deadwood-density", 0.042),
                        c.getDouble("vegetation.parametric-variation", 0.92),
                        c.getDouble("vegetation.open-ground-cover-density", 0.78),
                        c.getDouble("vegetation.open-shrub-density", 0.11),
                        c.getDouble("vegetation.azalea-frequency", 0.006),
                        c.getDouble("vegetation.custom-flora-bias", 0.82)
                ),
                new NaturalFeatures(
                        c.getBoolean("natural-features.enabled", true),
                        c.getDouble("natural-features.talus-density", 0.18),
                        c.getDouble("natural-features.outcrop-density", 0.14),
                        c.getDouble("natural-features.fallen-log-density", 0.034),
                        c.getDouble("natural-features.stump-density", 0.020),
                        c.getDouble("natural-features.bank-gravel-density", 0.24)
                ),
                new Compatibility(
                        c.getBoolean("compatibility.vanilla-caves", true),
                        c.getBoolean("compatibility.vanilla-decorations", true)
                ),
                new Performance(
                        c.getInt("performance.erosion-cache-tiles", 96),
                        c.getInt("performance.watershed-cache-tiles", 64),
                        c.getInt("performance.river-cache-tiles", 48),
                        c.getInt("performance.column-cache-chunks", 224)
                )
        );
    }

    public long effectiveSeed(long worldSeed) { return configuredSeed == 0L ? worldSeed : configuredSeed; }

    public record Terrain(int seaLevel,double baseScale,double continentScale,double warpScale,
                          double warpStrength,double hillHeight,double foothillHeight,
                          double mountainHeight,double mountainChainWidth,double alpineRuggedness,
                          double valleyDepth,int octaves,int tectonicCellSize,
                          double tectonicBoundaryWidth,double glacialValleyStrength,
                          double microRelief) {}
    public record Geology(boolean enabled,int provinceSize,double contactBlend,
                          double strataScale,double strataThickness,double dipStrength) {}
    public record Landscape(boolean enabled,double regionScale,double cliffStrength,
                            double canyonStrength,double wetlandStrength,
                            double plateauStrength,double landmarkStrength,
                            double regionalContrast) {}
    public record Ocean(double shelfWidth,double shelfDepth,double abyssDepth,
                        double coastRuggedness,double islandFrequency,double estuaryStrength) {}
    public record Erosion(boolean enabled,int tileSize,int sampleSpacing,int marginSamples,
                          int hydraulicIterations,int thermalIterations,double rainfall,
                          double evaporation,double capacity,double erosionRate,
                          double depositionRate,double talus,double intensity,
                          double velocityInertia) {}
    public record Rivers(boolean enabled,int tileSize,int sampleSpacing,int marginSamples,
                         double accumulationThreshold,double maxCarveDepth,double maxWidth,
                         double minWidth,double bankBuffer,double maxWaterDepth,
                         double maxWetGrade,double waterfallGrade,int terraceRun,
                         double meanderScale,double meanderStrength,double floodplainWidth,
                         double profileExponent,double bankSlopeWidth,double bankMaxCut,
                         double floodplainMaxCut,double edgeRoughness,
                         double secondaryChannelFrequency,double coastalMergeHeight,
                         double coastalWaterGradient,double channelBedFlatness,
                         double bankHeightJitter,double thalwegOffset,
                         double bankTransitionPower) {}
    public record Lakes(boolean enabled,int cellSize,double frequency,double minRadius,
                        double maxRadius,double maxDepth,double minRimHeight,
                        double coastGuardHeight,int rimSamples,double riverConnectDistance,
                        double shoreBlendWidth,double bedRoughness) {}
    public record Climate(double latitudePeriod,double temperatureScale,double humidityScale,
                          double altitudeLapseRate,double oceanMoisture,
                          double aspectStrength,double orographicStrength) {}
    public record Biomes(double openFlatMaxSlope,double openFlatMinOpenness,
                         double temperateForestHumidity,double darkForestHumidity,
                         double openRegionBias,int transitionRadius,
                         double transitionPatchScale,double rareBiomeFrequency) {}
    public record Caves(boolean enabled,double largeScale,double detailScale,double threshold,
                        int minY,int maxY,int surfaceBuffer,int oceanBuffer,
                        double tunnelScale,double tunnelRadius,int chamberSpacing,
                        double chamberFrequency,double maxChamberRadius,
                        double overlayStrength,double verticalLinkFrequency,
                        double aquiferFrequency,int aquiferMaxY,
                        boolean protectOceanCarvers,double oceanCarverMaxLandRatio,
                        double surfaceDetailStrength,double decorationFrequency,
                        double stepFrequency) {}
    public record Vegetation(boolean enabled,double treeDensity,double shrubDensity,
                             double groundCoverDensity,double boulderDensity,double groveScale,
                             double successionScale,double deadwoodDensity,
                             double parametricVariation,double openGroundCoverDensity,
                             double openShrubDensity,double azaleaFrequency,
                             double customFloraBias) {}
    public record NaturalFeatures(boolean enabled,double talusDensity,double outcropDensity,
                                  double fallenLogDensity,double stumpDensity,
                                  double bankGravelDensity) {}
    public record Compatibility(boolean vanillaCaves,boolean vanillaDecorations) {}
    public record Performance(int erosionCacheTiles,int watershedCacheTiles,int riverCacheTiles,
                              int columnCacheChunks) {}
}
