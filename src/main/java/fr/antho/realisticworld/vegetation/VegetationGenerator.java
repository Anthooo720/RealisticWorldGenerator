package fr.antho.realisticworld.vegetation;

import fr.antho.realisticworld.climate.ClimateEngine;
import fr.antho.realisticworld.biome.BiomeEngine;
import fr.antho.realisticworld.config.WorldGenConfig;
import fr.antho.realisticworld.hydrology.RiverEngine;
import fr.antho.realisticworld.hydrology.LakeEngine;
import fr.antho.realisticworld.noise.SimplexNoise;
import fr.antho.realisticworld.terrain.TerrainEngine;
import fr.antho.realisticworld.vegetation.community.VegetationCommunitySystem;
import fr.antho.realisticworld.vegetation.community.VegetationCommunitySystem.CommunitySample;
import fr.antho.realisticworld.util.HashUtil;
import fr.antho.realisticworld.util.MathUtil;
import org.bukkit.Material;
import org.bukkit.block.Biome;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.generator.ChunkGenerator.ChunkData;

/**
 * Décoration végétale procédurale déterministe. Les arbres ne sont pas des features
 * vanilla : leurs troncs, branches et couronnes sont dessinés bloc par bloc afin de
 * produire plusieurs silhouettes et de vrais bosquets climatiques.
 */
public final class VegetationGenerator {
    private final long seed;
    private final WorldGenConfig config;
    private final TerrainEngine terrain;
    private final ClimateEngine climate;
    private final RiverEngine rivers;
    private final LakeEngine lakes;
    private final ForestSuccessionSystem succession;
    private final BiomeEngine biomes;
    private final SimplexNoise groveNoise;
    private final SimplexNoise clearingNoise;
    private final SimplexNoise meadowNoise;

    public VegetationGenerator(long seed, WorldGenConfig config, TerrainEngine terrain,
                               ClimateEngine climate, RiverEngine rivers, LakeEngine lakes,
                               ForestSuccessionSystem succession, BiomeEngine biomes) {
        this.seed = seed;
        this.config = config;
        this.terrain = terrain;
        this.climate = climate;
        this.rivers = rivers;
        this.lakes = lakes;
        this.succession = succession;
        this.biomes = biomes;
        this.groveNoise = new SimplexNoise(seed ^ 0x51A7B03E11L);
        this.clearingNoise = new SimplexNoise(seed ^ 0x2277CC91A4L);
        this.meadowNoise = new SimplexNoise(seed ^ 0x73D1A90B5EL);
    }

    public void render(ChunkData data, int chunkX, int chunkZ) {
        if (!config.vegetation().enabled()) return;

        // Le tapis végétal ne dépasse pas le chunk : il peut donc être traité d'abord.
        decorateGround(data, chunkX, chunkZ);

        // Les arbres ont une cime pouvant dépasser de 5 à 6 blocs dans le chunk voisin.
        int x0 = chunkX * 16, z0 = chunkZ * 16;
        int margin = 10;
        for (int wz = z0 - margin; wz <= z0 + 15 + margin; wz++) {
            for (int wx = x0 - margin; wx <= x0 + 15 + margin; wx++) {
                if (!isTreeCandidate(wx, wz)) continue;

                SurfaceSample s = sampleSurface(wx, wz);
                if (!s.plantable || s.slope > 0.43 || s.river.isRiver()) continue;

                Biome biome = biomes.getBiome(wx, wz);
                // Les biomes capables d'accueillir un village vanilla sont volontairement
                // des zones ouvertes/plates. Aucun arbre custom n'y est posé avant la passe
                // vanilla des structures, ce qui évite chemins/toits générés sur nos cimes.
                if (biomes.isVillageOpenBiome(biome)) continue;

                ForestSuccessionSystem.ForestSample fs = succession.sample(wx, wz, s.y);
                double forest = fs.density();
                double grove = groveFactor(wx, wz, s.climate.humidity());
                double mountain = terrain.mountainInfluence(wx, wz);
                double altitude = s.y - terrain.seaLevel();

                // Densité désormais pilotée par succession forestière, climat, perturbations,
                // limite des arbres et patches de peuplement plutôt que par humidité seule.
                double foothillBonus = 1.0 + MathUtil.smootherstep(0.16, 0.64, mountain)
                        * MathUtil.smoothstep(0.34, 0.72, s.climate.humidity()) * 0.22;
                double ageStructure = 0.78 + fs.maturity() * 0.30;
                CommunitySample community = VegetationCommunitySystem.classify(
                        s.climate.temperature(), s.climate.humidity(), altitude, s.slope, mountain,
                        s.river.distanceToChannel(), forest, fs.oldGrowth());
                double acceptance = MathUtil.clamp(
                        config.vegetation().treeDensity() * 8.2 * forest * (0.35 + grove * 0.65)
                                * foothillBonus * ageStructure * community.treeMultiplier(),
                        0.0, 0.96);
                long h = HashUtil.hash(seed, wx, wz, 0x7EE5A11L);
                if (HashUtil.unitDouble(HashUtil.mix64(h ^ 0xA19L)) >= acceptance) continue;

                renderTree(data, chunkX, chunkZ, wx, wz, s.y + 1, s.climate, h, grove, mountain, altitude, fs, s.slope, s.river.distanceToChannel(), biome);
            }
        }
    }

    private void decorateGround(ChunkData data, int chunkX, int chunkZ) {
        int x0 = chunkX * 16, z0 = chunkZ * 16;
        for (int z = 0; z < 16; z++) {
            int wz = z0 + z;
            for (int x = 0; x < 16; x++) {
                int wx = x0 + x;
                SurfaceSample s = sampleSurface(wx, wz);
                if (!s.plantable || s.slope > 0.50 || s.river.isRiver()) continue;

                int y = s.y + 1;
                if (y <= data.getMinHeight() || y >= data.getMaxHeight()) continue;
                Material ground = data.getType(x, s.y, z);
                if (ground != Material.GRASS_BLOCK && ground != Material.DIRT && ground != Material.COARSE_DIRT) continue;

                double grove = groveFactor(wx, wz, s.climate.humidity());
                ForestSuccessionSystem.ForestSample groundFs = succession.sample(wx, wz, s.y);
                double forest = groundFs.density();
                CommunitySample community = VegetationCommunitySystem.classify(
                        s.climate.temperature(), s.climate.humidity(), s.y - terrain.seaLevel(), s.slope,
                        terrain.mountainInfluence(wx, wz), s.river.distanceToChannel(), forest, groundFs.oldGrowth());
                long h = HashUtil.hash(seed, wx, wz, 0x4819A33EL);
                Biome biome = biomes.getBiome(wx, wz);

                // Dans les biomes ouverts compatibles villages : uniquement herbes/fleurs.
                // Les blocs volumineux (rochers, fourrés, troncs) restent hors de ces zones
                // pour laisser le pipeline vanilla poser proprement villages et chemins.
                if (biomes.isVillageOpenBiome(biome)) {
                    double sparseCover = config.vegetation().groundCoverDensity() * 0.42
                            * groundCoverFactor(s.climate.temperature(), s.climate.humidity(), wx, wz);
                    if (HashUtil.unitDouble(HashUtil.mix64(h ^ 0x405L)) < sparseCover) {
                        Material plant = selectGroundPlant(s.climate, wx, wz, h);
                        if (plant != null) setIfAirWorld(data, chunkX, chunkZ, wx, y, wz, plant);
                    }
                    continue;
                }

                // Sous-bois : podzol/mousse/coarse dirt par petites taches, pas en tapis uniforme.
                double floorPatch = HashUtil.unitDouble(HashUtil.mix64(h ^ 0x101L));
                if (forest > 0.58 && grove > 0.60 && floorPatch < 0.055) {
                    Material floor = s.climate.humidity() > 0.70 ? Material.MOSS_BLOCK
                            : s.climate.temperature() < 0.38 ? Material.PODZOL : Material.COARSE_DIRT;
                    data.setBlock(x, s.y, z, floor);
                }

                // Litière forestière récente : donne un sous-bois moins vide et casse les
                // grandes surfaces d'herbe uniforme sous les peuplements denses.
                if (forest > 0.52 && grove > 0.54
                        && HashUtil.unitDouble(HashUtil.mix64(h ^ 0x181L)) < 0.060) {
                    setIfAirWorld(data, chunkX, chunkZ, wx, y, wz, Material.LEAF_LITTER);
                    continue;
                }

                // Rochers isolés, plus probables dans les collines et climats frais.
                double rockChance = config.vegetation().boulderDensity()
                        * (0.035 + MathUtil.clamp(s.slope * 1.8, 0.0, 0.13));
                if (HashUtil.unitDouble(HashUtil.mix64(h ^ 0x202L)) < rockChance) {
                    renderBoulder(data, chunkX, chunkZ, wx, wz, y, s.climate, h);
                    continue;
                }

                // Arbustes et buissons plus fréquents aux lisières et près des zones humides.
                double shrubChance = config.vegetation().shrubDensity()
                        * (0.10 + forest * 0.30)
                        * (0.62 + 0.38 * grove) * community.shrubMultiplier();
                if (HashUtil.unitDouble(HashUtil.mix64(h ^ 0x303L)) < shrubChance) {
                    renderShrub(data, chunkX, chunkZ, wx, wz, y, s.climate, h);
                    continue;
                }

                double coverChance = config.vegetation().groundCoverDensity()
                        * groundCoverFactor(s.climate.temperature(), s.climate.humidity(), wx, wz) * community.groundMultiplier();
                if (HashUtil.unitDouble(HashUtil.mix64(h ^ 0x404L)) < coverChance) {
                    Material plant = selectGroundPlant(s.climate, wx, wz, h);
                    if (plant != null) setIfAirWorld(data, chunkX, chunkZ, wx, y, wz, plant);
                }
            }
        }
    }

    private SurfaceSample sampleSurface(int wx, int wz) {
        double eroded = terrain.heightWithoutRivers(wx, wz);
        RiverEngine.RiverSample river = rivers.sample(wx, wz);
        LakeEngine.LakeSample lake = lakes.sample(wx, wz);
        int y = (int) Math.floor(eroded - river.carveDepth() - lake.carveDepth());
        ClimateEngine.ClimateSample c = climate.sample(wx, wz, eroded);
        double slope = terrain.slope(wx, wz);
        boolean plantable = y > terrain.seaLevel() + 2 && c.temperature() > 0.09 && !lake.isLake() && !river.isRiver();
        return new SurfaceSample(y, slope, c, river, lake, plantable);
    }

    /** Un candidat maximum par cellule 5x5 : espacement irrégulier mais maîtrisé. */
    private boolean isTreeCandidate(int wx, int wz) {
        int cell = 5;
        int cx = Math.floorDiv(wx, cell);
        int cz = Math.floorDiv(wz, cell);
        long h = HashUtil.hash(seed, cx, cz, 0x3610F4A7L);
        int ox = HashUtil.range(h, 0, cell);
        int oz = HashUtil.range(HashUtil.mix64(h ^ 0x991L), 0, cell);
        return wx == cx * cell + ox && wz == cz * cell + oz;
    }

    private double groveFactor(int x, int z, double humidity) {
        double scale = Math.max(0.0002, config.vegetation().groveScale());
        double g = groveNoise.sample(x * scale, z * scale);
        double clear = clearingNoise.sample(x * scale * 0.58, z * scale * 0.58);
        double clusters = MathUtil.smootherstep(-0.58, 0.30, g + (humidity - 0.5) * 0.40);
        // Clairières plus franches et plus larges : on veut de vrais contrastes de densité,
        // pas une forêt uniformément remplie à l'échelle de plusieurs kilomètres.
        double clearings = MathUtil.smootherstep(0.20, 0.62, clear);
        double secondary = clearingNoise.sample(x * scale * 1.35 + 83, z * scale * 1.35 - 47) * 0.5 + 0.5;
        double gap = MathUtil.smootherstep(0.74, 0.92, secondary);
        return MathUtil.clamp(0.12 + clusters * 1.02 - clearings * 0.82 - gap * 0.38, 0.025, 1.0);
    }

    private double forestFactor(double t, double h) {
        if (t < 0.10 || h < 0.22) return 0.04;
        double moisture = MathUtil.smoothstep(0.22, 0.70, h);
        double thermal = 1.0 - MathUtil.clamp(Math.abs(t - 0.50) / 0.58, 0, 1) * 0.38;
        if (t > 0.76 && h < 0.52) thermal *= 0.55;
        return MathUtil.clamp(moisture * thermal, 0, 1);
    }

    private double groundCoverFactor(double t, double h, int x, int z) {
        if (t < 0.13) return 0.04;
        if (h < 0.18) return 0.06;
        double meadow = meadowNoise.sample(x * 0.0032, z * 0.0032);
        double moisture = MathUtil.smoothstep(0.18, 0.62, h);
        return MathUtil.clamp(0.20 + moisture * 0.62 + meadow * 0.18, 0.08, 0.95);
    }

    private Material selectGroundPlant(ClimateEngine.ClimateSample c, int x, int z, long h) {
        double t = c.temperature(), m = c.humidity();
        double r = HashUtil.unitDouble(HashUtil.mix64(h ^ 0xB10F1L));

        if (t > 0.70 && m < 0.34) return r < 0.55 ? Material.DEAD_BUSH : null;
        if (t < 0.34 && m > 0.48) return r < 0.58 ? Material.FERN : Material.SHORT_GRASS;
        if (m > 0.76) {
            if (r < 0.12) return Material.FIREFLY_BUSH;
            if (r < 0.36) return Material.FERN;
            if (r < 0.46) return Material.BLUE_ORCHID;
            return Material.SHORT_GRASS;
        }

        double flowerPatch = meadowNoise.sample(x * 0.0068 + 41.0, z * 0.0068 - 19.0);
        if (flowerPatch > 0.34) {
            if (r < 0.12) return Material.WILDFLOWERS;
            if (r < 0.20) return Material.POPPY;
            if (r < 0.27) return Material.CORNFLOWER;
            if (r < 0.33) return Material.OXEYE_DAISY;
            if (r < 0.37) return Material.DANDELION;
        }
        return Material.SHORT_GRASS;
    }

    private void renderShrub(ChunkData data, int chunkX, int chunkZ, int x, int z, int y,
                             ClimateEngine.ClimateSample c, long h) {
        double r = HashUtil.unitDouble(HashUtil.mix64(h ^ 0x51A2L));
        double shape = HashUtil.unitDouble(HashUtil.mix64(h ^ 0x51A3L));

        if (c.temperature() > 0.66 && c.humidity() < 0.38) {
            if (shape < 0.34) renderDryScrub(data, chunkX, chunkZ, x, z, y, h);
            else setIfAirWorld(data, chunkX, chunkZ, x, y, z, Material.DEAD_BUSH);
            return;
        }
        if (c.temperature() < 0.40 && c.humidity() > 0.46) {
            if (shape < 0.28) renderBerryThicket(data, chunkX, chunkZ, x, z, y, h);
            else if (shape < 0.50) renderDwarfConifer(data, chunkX, chunkZ, x, z, y, h);
            else if (shape < 0.76) renderLowShrub(data, chunkX, chunkZ, x, z, y, Material.SPRUCE_LOG, Material.SPRUCE_LEAVES, h);
            else setIfAirWorld(data, chunkX, chunkZ, x, y, z, Material.FERN);
            return;
        }
        if (c.humidity() > 0.76) {
            if (r < 0.14) { setIfAirWorld(data, chunkX, chunkZ, x, y, z, Material.FIREFLY_BUSH); return; }
            if (shape < 0.28) { renderAzaleaThicket(data, chunkX, chunkZ, x, z, y, h); return; }
            if (shape < 0.52) { renderSpreadingShrub(data, chunkX, chunkZ, x, z, y, Material.OAK_LEAVES, h); return; }
            if (shape < 0.78) { renderLowShrub(data, chunkX, chunkZ, x, z, y, Material.OAK_LOG, Material.OAK_LEAVES, h); return; }
        }
        if (c.humidity() > 0.60 && shape < 0.44) {
            renderAzaleaThicket(data, chunkX, chunkZ, x, z, y, h);
            return;
        }
        if (shape < 0.40) {
            setIfAirWorld(data, chunkX, chunkZ, x, y, z, Material.BUSH);
        } else if (shape < 0.72) {
            renderLowShrub(data, chunkX, chunkZ, x, z, y, Material.BIRCH_LOG, Material.BIRCH_LEAVES, h);
        } else {
            renderLowShrub(data, chunkX, chunkZ, x, z, y, Material.OAK_LOG, Material.OAK_LEAVES, h);
        }
    }

    private void renderSpreadingShrub(ChunkData data, int cx, int cz, int x, int z, int y, Material leaves, long h) {
        int reach=HashUtil.range(HashUtil.mix64(h ^ 0x5A11L),1,3);
        setIfAirWorld(data,cx,cz,x,y,z,Material.BUSH);
        for(int dz=-reach;dz<=reach;dz++) for(int dx=-reach;dx<=reach;dx++) {
            double d=Math.hypot(dx,dz);
            if(d>reach+0.2) continue;
            long q=HashUtil.hash(seed,x+dx,z+dz,h^0x5A12L);
            if(HashUtil.unitDouble(q)<0.52) setIfAirWorld(data,cx,cz,x+dx,y+(d<0.8?1:0),z+dz,leaves);
        }
    }

    private void renderDwarfConifer(ChunkData data, int cx, int cz, int x, int z, int y, long h) {
        int height=HashUtil.range(HashUtil.mix64(h ^ 0x5B11L),2,5);
        for(int i=0;i<height;i++) setIfAirWorld(data,cx,cz,x,y+i,z,Material.SPRUCE_LOG);
        for(int yy=0;yy<height+1;yy++) {
            int r=yy<2?2:1;
            leafDisk(data,cx,cz,x,y+yy,z,r,Material.SPRUCE_LEAVES,h^yy^0x5B12L);
        }
    }

    private void renderLowShrub(ChunkData data, int cx, int cz, int x, int z, int y,
                                Material log, Material leaves, long h) {
        setIfAirWorld(data, cx, cz, x, y, z, log);
        int height = HashUtil.unitDouble(HashUtil.mix64(h ^ 0x612L)) < 0.32 ? 2 : 1;
        if (height == 2) setIfAirWorld(data, cx, cz, x, y + 1, z, log);
        int crownY = y + height;
        for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
            if (Math.abs(dx) + Math.abs(dz) == 2 && HashUtil.unitDouble(HashUtil.hash(seed, x + dx, z + dz, h)) > 0.48) continue;
            setIfAirWorld(data, cx, cz, x + dx, crownY, z + dz, leaves);
        }
        if (height == 2) setIfAirWorld(data, cx, cz, x, crownY + 1, z, leaves);
    }

    private void renderBerryThicket(ChunkData data, int cx, int cz, int x, int z, int y, long h) {
        for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
            if (HashUtil.unitDouble(HashUtil.hash(seed, x + dx, z + dz, h ^ 0xBEEFL)) < 0.48)
                setIfAirWorld(data, cx, cz, x + dx, y, z + dz, Material.SWEET_BERRY_BUSH);
        }
        if (HashUtil.unitDouble(h) < 0.42) setIfAirWorld(data, cx, cz, x, y, z, Material.BUSH);
    }

    private void renderAzaleaThicket(ChunkData data, int cx, int cz, int x, int z, int y, long h) {
        for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
            double rr = HashUtil.unitDouble(HashUtil.hash(seed, x + dx, z + dz, h ^ 0xA2A1L));
            if (rr < 0.32) setIfAirWorld(data, cx, cz, x + dx, y, z + dz, Material.FLOWERING_AZALEA);
            else if (rr < 0.68) setIfAirWorld(data, cx, cz, x + dx, y, z + dz, Material.AZALEA);
        }
    }

    private void renderDryScrub(ChunkData data, int cx, int cz, int x, int z, int y, long h) {
        setIfAirWorld(data, cx, cz, x, y, z, Material.DEAD_BUSH);
        for (int i = 0; i < 3; i++) {
            long q = HashUtil.mix64(h ^ (0xD00L + i));
            int dx = HashUtil.range(q, -2, 3), dz = HashUtil.range(HashUtil.mix64(q), -2, 3);
            if (Math.abs(dx) + Math.abs(dz) > 3) continue;
            setIfAirWorld(data, cx, cz, x + dx, y, z + dz, HashUtil.unitDouble(q) < 0.35 ? Material.BUSH : Material.DEAD_BUSH);
        }
    }

    private void renderBoulder(ChunkData data, int chunkX, int chunkZ, int x, int z, int y,
                               ClimateEngine.ClimateSample c, long h) {
        Material main = c.humidity() > 0.60 ? Material.MOSSY_COBBLESTONE : Material.ANDESITE;
        int radius = HashUtil.unitDouble(HashUtil.mix64(h ^ 0x771L)) < 0.18 ? 2 : 1;
        for (int dy = 0; dy <= radius; dy++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    double shape = dx * dx + dz * dz + dy * dy * 1.45;
                    double jitter = HashUtil.unitDouble(HashUtil.hash(seed, x + dx, z + dz, h ^ dy));
                    if (shape > radius * radius + 0.55 + jitter) continue;
                    setIfAirWorld(data, chunkX, chunkZ, x + dx, y + dy, z + dz,
                            jitter < 0.18 ? Material.COBBLESTONE : main);
                }
            }
        }
    }

    private void renderTree(ChunkData data, int chunkX, int chunkZ, int x, int z, int y,
                            ClimateEngine.ClimateSample c, long h, double grove,
                            double mountain, double altitude, ForestSuccessionSystem.ForestSample fs,
                            double slope, double waterDistance, Biome biome) {
        double t = c.temperature(), m = c.humidity();
        double variant = HashUtil.unitDouble(HashUtil.mix64(h ^ 0x111L));
        boolean nearWater = waterDistance < 7.0;
        CommunitySample community = VegetationCommunitySystem.classify(t, m, altitude, slope, mountain,
                waterDistance, fs.density(), fs.oldGrowth());

        // Règle stricte demandée : aucun cerisier RWG hors CHERRY_GROVE. Dans ce biome,
        // les cerisiers dominent mais sont mélangés à quelques feuillus vanilla+.
        if (biome == Biome.CHERRY_GROVE) {
            if (variant < 0.72) renderCherry(data, chunkX, chunkZ, x, z, y, h);
            else if (variant < 0.86) renderTallBirch(data, chunkX, chunkZ, x, z, y, h);
            else renderBroadOak(data, chunkX, chunkZ, x, z, y, h);
            return;
        }

        // Une partie des arbres utilise un modèle paramétrique : hauteur, courbure du tronc,
        // nombre de branches et couronne changent par individu sans perdre l'identité de l'espèce.
        double parametric = config.vegetation().parametricVariation();
        double pr = HashUtil.unitDouble(HashUtil.mix64(h ^ 0x514AAAL));
        if (pr < parametric * 0.34 && community.type() != VegetationCommunitySystem.Community.ALPINE_MEADOW) {
            if (community.type() == VegetationCommunitySystem.Community.BOREAL_FOREST
                    || community.type() == VegetationCommunitySystem.Community.SUBALPINE_CONIFER) {
                renderParametricConifer(data, chunkX, chunkZ, x, z, y, h, fs.oldGrowth());
                return;
            }
            if (community.type() == VegetationCommunitySystem.Community.TEMPERATE_MIXED
                    || community.type() == VegetationCommunitySystem.Community.OLD_GROWTH_FOREST
                    || community.type() == VegetationCommunitySystem.Community.MEADOW_EDGE) {
                renderParametricDeciduous(data, chunkX, chunkZ, x, z, y, h, fs.oldGrowth());
                return;
            }
        }

        // Ripisylve : silhouettes propres aux berges, sans planter le tronc dans le chenal.
        if (nearWater && altitude < 30 && m > 0.54) {
            if (t > 0.66 && m > 0.72 && variant < 0.28) { renderMangrove(data, chunkX, chunkZ, x, z, y, h); return; }
            if (t > 0.30 && t < 0.68 && variant < 0.28) { renderWillow(data, chunkX, chunkZ, x, z, y, h); return; }
            if (t > 0.24 && t < 0.62 && variant < 0.46) { renderAlder(data, chunkX, chunkZ, x, z, y, h); return; }
            if (t > 0.26 && t < 0.64 && variant < 0.66) { renderRiverBirch(data, chunkX, chunkZ, x, z, y, h); return; }
        }

        // Ceinture montagnarde : anciens géants, pins battus par le vent, sapins et pins élancés.
        if (mountain > 0.20 && altitude > 26 && altitude < 104 && t < 0.66 && m > 0.32) {
            if (fs.oldGrowth() > 0.62 && variant < 0.18) { renderGiantSpruce(data, chunkX, chunkZ, x, z, y, h); return; }
            if (fs.oldGrowth() > 0.48 && variant < 0.30) { renderCedar(data, chunkX, chunkZ, x, z, y, h); return; }
            if (slope > 0.24 && variant < 0.42) { renderWindsweptPine(data, chunkX, chunkZ, x, z, y, h); return; }
            double coniferChance = MathUtil.clamp(0.52 + mountain * 0.28 + altitude / 250.0, 0.52, 0.90);
            if (variant < coniferChance) {
                if (HashUtil.unitDouble(HashUtil.mix64(h ^ 0xA71L)) < 0.58) renderPine(data, chunkX, chunkZ, x, z, y, h);
                else renderFir(data, chunkX, chunkZ, x, z, y, h);
                return;
            }
        }

        if (t < 0.34) {
            if (fs.oldGrowth() > 0.70 && variant < 0.24) renderGiantSpruce(data, chunkX, chunkZ, x, z, y, h);
            else if (variant < 0.52) renderPine(data, chunkX, chunkZ, x, z, y, h);
            else if (variant < 0.80) renderFir(data, chunkX, chunkZ, x, z, y, h);
            else renderAspen(data, chunkX, chunkZ, x, z, y, h);
            return;
        }
        if (t > 0.70 && m < 0.50) {
            if (variant < 0.34 && m > 0.27) renderUmbrellaPine(data, chunkX, chunkZ, x, z, y, h);
            else renderAcacia(data, chunkX, chunkZ, x, z, y, h);
            return;
        }
        if (t > 0.67 && m > 0.66) {
            if (variant < 0.24 + fs.oldGrowth() * 0.20) renderKapok(data, chunkX, chunkZ, x, z, y, h);
            else renderJungleTree(data, chunkX, chunkZ, x, z, y, h);
            return;
        }
        if (t > 0.30 && t < 0.52 && m > 0.66 && grove > 0.62 && variant < 0.13) { renderPaleOak(data, chunkX, chunkZ, x, z, y, h); return; }
        if (m > 0.66 && variant < 0.12 + fs.oldGrowth() * 0.34) { renderAncientOak(data, chunkX, chunkZ, x, z, y, h); return; }

        double form = HashUtil.unitDouble(HashUtil.mix64(h ^ 0x11F0L));
        if (form < 0.12) renderAspen(data, chunkX, chunkZ, x, z, y, h);
        else if (form < 0.24) renderTallBirch(data, chunkX, chunkZ, x, z, y, h);
        else if (form < 0.35) renderBirchCluster(data, chunkX, chunkZ, x, z, y, h);
        else if (form < 0.49) renderBeech(data, chunkX, chunkZ, x, z, y, h);
        else if (form < 0.63) renderColumnarOak(data, chunkX, chunkZ, x, z, y, h);
        else if (form < 0.78) renderCrookedOak(data, chunkX, chunkZ, x, z, y, h);
        else renderBroadOak(data, chunkX, chunkZ, x, z, y, h);
    }

    private void renderParametricDeciduous(ChunkData data, int cx, int cz, int x, int z, int y, long h, double oldGrowth) {
        boolean birch = HashUtil.unitDouble(HashUtil.mix64(h ^ 0xD311L)) < 0.28;
        Material log = birch ? Material.BIRCH_LOG : (oldGrowth > 0.68 ? Material.DARK_OAK_LOG : Material.OAK_LOG);
        Material leaves = birch ? Material.BIRCH_LEAVES : (oldGrowth > 0.68 ? Material.DARK_OAK_LEAVES : Material.OAK_LEAVES);
        int height = HashUtil.range(HashUtil.mix64(h ^ 0xD312L), birch ? 7 : 6, birch ? 13 : (oldGrowth > 0.7 ? 14 : 11));
        int bendX = HashUtil.range(HashUtil.mix64(h ^ 0xD313L), -1, 2);
        int bendZ = HashUtil.range(HashUtil.mix64(h ^ 0xD314L), -1, 2);
        int tx=x, tz=z;
        for(int i=0;i<height;i++) {
            if(i>height/2 && i%3==0) { tx += Integer.signum(bendX); tz += Integer.signum(bendZ); }
            setWorld(data,cx,cz,tx,y+i,tz,log);
        }
        int branches = HashUtil.range(HashUtil.mix64(h ^ 0xD315L), birch?2:3, oldGrowth>0.65?7:6);
        for(int b=0;b<branches;b++) {
            long bh=HashUtil.mix64(h ^ (0xD320L+b*17L));
            int dx=HashUtil.range(bh,-1,2), dz=HashUtil.range(HashUtil.mix64(bh),-1,2);
            if(dx==0&&dz==0) dx=1;
            int by=y+height-2-HashUtil.range(HashUtil.mix64(bh^9),0,4);
            int len=HashUtil.range(HashUtil.mix64(bh^19),1,oldGrowth>0.6?4:3);
            for(int i=1;i<=len;i++) setWorld(data,cx,cz,tx+dx*i,by+i/2,tz+dz*i,log);
            leafBlob(data,cx,cz,tx+dx*len,by+1+len/2,tz+dz*len,birch?2:2+(oldGrowth>0.7?1:0),leaves,bh);
        }
        leafBlob(data,cx,cz,tx,y+height,tz,birch?2:3,leaves,h^0xD399L);
    }

    private void renderParametricConifer(ChunkData data, int cx, int cz, int x, int z, int y, long h, double oldGrowth) {
        int height=HashUtil.range(HashUtil.mix64(h ^ 0xC011L), 10, oldGrowth>0.65?23:18);
        int leanX=HashUtil.range(HashUtil.mix64(h ^ 0xC012L),-1,2), leanZ=HashUtil.range(HashUtil.mix64(h ^ 0xC013L),-1,2);
        int tx=x,tz=z;
        for(int i=0;i<height;i++) {
            if(i>height*0.62 && i%4==0) { tx += Integer.signum(leanX); tz += Integer.signum(leanZ); }
            setWorld(data,cx,cz,tx,y+i,tz,Material.SPRUCE_LOG);
        }
        int crown=HashUtil.range(HashUtil.mix64(h ^ 0xC014L),6,Math.min(13,height-2));
        int base=y+height-crown;
        for(int yy=base;yy<=y+height+1;yy++) {
            int d=y+height+1-yy;
            int radius=MathUtil.clamp(1+d/3,1,oldGrowth>0.65?4:3);
            if(((yy-base)&1)==1 && radius>1) radius--;
            leafDisk(data,cx,cz,tx,yy,tz,radius,Material.SPRUCE_LEAVES,h^yy);
        }
    }

    private void renderBeech(ChunkData data, int cx, int cz, int x, int z, int y, long h) {
        int height=HashUtil.range(HashUtil.mix64(h^0xBEE501L),9,15);
        int tx=x,tz=z;
        for(int i=0;i<height;i++) {
            if(i>height*0.70 && i%4==0) { tx += HashUtil.signedDouble(HashUtil.mix64(h^i))>0.55?1:0; }
            setWorld(data,cx,cz,tx,y+i,tz,Material.DARK_OAK_LOG);
        }
        int top=y+height-1;
        leafBlob(data,cx,cz,tx,top,z,3,Material.OAK_LEAVES,h^0xBEE510L);
        leafBlob(data,cx,cz,tx+2,top-2,z+1,2,Material.OAK_LEAVES,h^0xBEE511L);
        leafBlob(data,cx,cz,tx-2,top-2,z-1,2,Material.OAK_LEAVES,h^0xBEE512L);
    }

    private void renderBirchCluster(ChunkData data,int cx,int cz,int x,int z,int y,long h) {
        int stems=HashUtil.range(HashUtil.mix64(h^0xB1C001L),2,4);
        for(int s=0;s<stems;s++) {
            long q=HashUtil.mix64(h+s*0x51L);
            int ox=s==0?0:HashUtil.range(q,-1,2), oz=s==0?0:HashUtil.range(HashUtil.mix64(q),-1,2);
            int height=HashUtil.range(HashUtil.mix64(q^0x22L),8,14);
            for(int i=0;i<height;i++) setWorld(data,cx,cz,x+ox,y+i,z+oz,Material.BIRCH_LOG);
            leafBlob(data,cx,cz,x+ox,y+height,z+oz,2,Material.BIRCH_LEAVES,q);
        }
    }

    private void renderRiverBirch(ChunkData data,int cx,int cz,int x,int z,int y,long h) {
        int stems=HashUtil.range(HashUtil.mix64(h^0x71B1A1L),2,5);
        for(int s=0;s<stems;s++) {
            long q=HashUtil.mix64(h+s*131L);
            int dx=HashUtil.range(q,-1,2), dz=HashUtil.range(HashUtil.mix64(q),-1,2);
            if(s==0){dx=0;dz=0;}
            int height=HashUtil.range(HashUtil.mix64(q^0x31L),6,11);
            for(int i=0;i<height;i++) {
                int lean=i>height/2?Integer.signum(dx):0;
                setWorld(data,cx,cz,x+dx+lean,y+i,z+dz,Material.BIRCH_LOG);
            }
            leafBlob(data,cx,cz,x+dx+Integer.signum(dx),y+height,z+dz,2,Material.BIRCH_LEAVES,q);
        }
    }

    private void renderCedar(ChunkData data,int cx,int cz,int x,int z,int y,long h) {
        int height=HashUtil.range(HashUtil.mix64(h^0xCEDA21L),13,22);
        for(int i=0;i<height;i++) setWorld(data,cx,cz,x,y+i,z,Material.SPRUCE_LOG);
        int top=y+height-1;
        for(int layer=0;layer<5;layer++) {
            int yy=top-2-layer*3;
            int r=Math.max(1,4-layer/2);
            leafDisk(data,cx,cz,x,yy,z,r,Material.SPRUCE_LEAVES,h^(layer*0x99L));
            if(layer<3) leafDisk(data,cx,cz,x,yy+1,z,Math.max(1,r-1),Material.SPRUCE_LEAVES,h^(layer*0xA9L));
        }
        leafBlob(data,cx,cz,x,top,z,1,Material.SPRUCE_LEAVES,h^0xCEDA99L);
    }

    private void renderUmbrellaPine(ChunkData data,int cx,int cz,int x,int z,int y,long h) {
        int height=HashUtil.range(HashUtil.mix64(h^0xA8B311L),8,14);
        for(int i=0;i<height;i++) setWorld(data,cx,cz,x,y+i,z,Material.SPRUCE_LOG);
        int top=y+height-1;
        int[][] dirs={{1,0},{-1,0},{0,1},{0,-1},{1,1},{-1,-1}};
        for(int i=0;i<dirs.length;i++) {
            long q=HashUtil.mix64(h+i*37L); int len=HashUtil.range(q,2,5);
            for(int n=1;n<=len;n++) setWorld(data,cx,cz,x+dirs[i][0]*n,top-2+n/2,z+dirs[i][1]*n,Material.SPRUCE_LOG);
            leafBlob(data,cx,cz,x+dirs[i][0]*len,top,z+dirs[i][1]*len,2,Material.SPRUCE_LEAVES,q);
        }
        leafDisk(data,cx,cz,x,top+1,z,3,Material.SPRUCE_LEAVES,h^0xA8B399L);
    }

    private void renderCrookedOak(ChunkData data,int cx,int cz,int x,int z,int y,long h) {
        int height=HashUtil.range(HashUtil.mix64(h^0xC70001L),6,11);
        int dirX=HashUtil.range(HashUtil.mix64(h^0xC70002L),-1,2);
        int dirZ=HashUtil.range(HashUtil.mix64(h^0xC70003L),-1,2);
        if(dirX==0&&dirZ==0) dirX=1;
        int tx=x,tz=z;
        for(int i=0;i<height;i++) {
            if(i>2 && i%3==0){tx+=dirX;tz+=dirZ;}
            setWorld(data,cx,cz,tx,y+i,tz,Material.OAK_LOG);
        }
        int top=y+height-1;
        leafBlob(data,cx,cz,tx,top+1,tz,3,Material.OAK_LEAVES,h);
        leafBlob(data,cx,cz,tx-dirZ*2,top-1,tz+dirX*2,2,Material.OAK_LEAVES,h^0xC70011L);
        leafBlob(data,cx,cz,tx+dirZ*2,top-2,tz-dirX*2,2,Material.OAK_LEAVES,h^0xC70012L);
    }

    private void renderBroadOak(ChunkData data, int cx, int cz, int x, int z, int y, long h) {
        int height = HashUtil.range(HashUtil.mix64(h ^ 0x201L), 5, 8);
        for (int i = 0; i < height; i++) setWorld(data, cx, cz, x, y + i, z, Material.OAK_LOG);
        int top = y + height - 1;

        // Branches irrégulières en bois (écorce sur toutes les faces), puis plusieurs lobes de feuilles.
        int[][] dirs = {{1,0},{-1,0},{0,1},{0,-1}};
        for (int i = 0; i < dirs.length; i++) {
            long bh = HashUtil.mix64(h ^ (0x310L + i));
            if (HashUtil.unitDouble(bh) > 0.58) continue;
            int len = HashUtil.range(bh, 1, 3);
            int by = top - 1 + (i & 1);
            for (int n = 1; n <= len; n++) {
                setWorld(data, cx, cz, x + dirs[i][0] * n, by + (n / 2), z + dirs[i][1] * n, Material.OAK_WOOD);
            }
            leafBlob(data, cx, cz, x + dirs[i][0] * len, by + 1, z + dirs[i][1] * len, 2, Material.OAK_LEAVES, bh);
        }
        leafBlob(data, cx, cz, x, top + 1, z, 3, Material.OAK_LEAVES, h ^ 0x99L);
    }

    private void renderAncientOak(ChunkData data, int cx, int cz, int x, int z, int y, long h) {
        int height = HashUtil.range(HashUtil.mix64(h ^ 0x221L), 7, 11);
        for (int i = 0; i < height; i++) {
            setWorld(data, cx, cz, x, y + i, z, Material.DARK_OAK_LOG);
            setWorld(data, cx, cz, x + 1, y + i, z, Material.DARK_OAK_LOG);
            if (i < height - 2) setWorld(data, cx, cz, x, y + i, z + 1, Material.DARK_OAK_LOG);
            if (i < height - 3) setWorld(data, cx, cz, x + 1, y + i, z + 1, Material.DARK_OAK_LOG);
        }
        int top = y + height - 1;
        leafBlob(data, cx, cz, x, top, z, 3, Material.DARK_OAK_LEAVES, h);
        leafBlob(data, cx, cz, x + 2, top - 1, z + 1, 3, Material.DARK_OAK_LEAVES, h ^ 0x12L);
        leafBlob(data, cx, cz, x - 2, top - 1, z, 2, Material.DARK_OAK_LEAVES, h ^ 0x13L);
        leafBlob(data, cx, cz, x + 1, top + 2, z, 2, Material.DARK_OAK_LEAVES, h ^ 0x14L);
    }

    private void renderTallBirch(ChunkData data, int cx, int cz, int x, int z, int y, long h) {
        int height = HashUtil.range(HashUtil.mix64(h ^ 0x231L), 7, 11);
        for (int i = 0; i < height; i++) setWorld(data, cx, cz, x, y + i, z, Material.BIRCH_LOG);
        int top = y + height - 1;
        for (int dy = -2; dy <= 2; dy++) {
            int r = dy == 2 ? 1 : (dy < 0 ? 2 : 2);
            leafDisk(data, cx, cz, x, top + dy, z, r, Material.BIRCH_LEAVES, h ^ dy);
        }
    }

    private void renderPine(ChunkData data, int cx, int cz, int x, int z, int y, long h) {
        int height = HashUtil.range(HashUtil.mix64(h ^ 0x241L), 13, 21);
        for (int i = 0; i < height; i++) setWorld(data, cx, cz, x, y + i, z, Material.SPRUCE_LOG);
        int top = y + height - 1;
        int start = top - Math.max(6, height - 4);
        for (int yy = start; yy <= top + 1; yy++) {
            int dist = top + 1 - yy;
            int radius = dist <= 1 ? 1 : Math.min(3, 1 + dist / 3);
            if (((yy - start) & 1) == 1 && radius > 1) radius--;
            leafDisk(data, cx, cz, x, yy, z, radius, Material.SPRUCE_LEAVES, h ^ yy);
        }
    }

    private void renderFir(ChunkData data, int cx, int cz, int x, int z, int y, long h) {
        int height = HashUtil.range(HashUtil.mix64(h ^ 0x251L), 10, 17);
        for (int i = 0; i < height; i++) setWorld(data, cx, cz, x, y + i, z, Material.SPRUCE_LOG);
        int top = y + height - 1;
        for (int dy = -6; dy <= 1; dy++) {
            int radius = dy < -3 ? 3 : dy < 0 ? 2 : 1;
            if ((dy & 1) == 0 && radius == 3) radius = 2;
            leafDisk(data, cx, cz, x, top + dy, z, radius, Material.SPRUCE_LEAVES, h ^ (dy * 31L));
        }
    }

    private void renderAcacia(ChunkData data, int cx, int cz, int x, int z, int y, long h) {
        int height = HashUtil.range(HashUtil.mix64(h ^ 0x261L), 5, 8);
        int dx = HashUtil.unitDouble(h) < 0.5 ? 1 : -1;
        int dz = HashUtil.unitDouble(HashUtil.mix64(h)) < 0.5 ? 1 : -1;
        for (int i = 0; i < height; i++) {
            int bend = i > height / 2 ? (i - height / 2) / 2 : 0;
            setWorld(data, cx, cz, x + dx * bend, y + i, z + dz * bend, Material.ACACIA_WOOD);
        }
        int bx = x + dx * Math.max(1, (height - height / 2) / 2);
        int bz = z + dz * Math.max(1, (height - height / 2) / 2);
        int top = y + height - 1;
        leafDisk(data, cx, cz, bx, top, bz, 3, Material.ACACIA_LEAVES, h);
        leafDisk(data, cx, cz, bx, top + 1, bz, 2, Material.ACACIA_LEAVES, h ^ 0x17L);

        // Une seconde branche rend la silhouette en parasol moins répétitive.
        if (HashUtil.unitDouble(HashUtil.mix64(h ^ 0x33L)) < 0.68) {
            int sx = -dz, sz = dx;
            for (int n = 1; n <= 2; n++) setWorld(data, cx, cz, bx + sx * n, top - 1 + n / 2, bz + sz * n, Material.ACACIA_WOOD);
            leafDisk(data, cx, cz, bx + sx * 2, top + 1, bz + sz * 2, 2, Material.ACACIA_LEAVES, h ^ 0x34L);
        }
    }

    private void renderJungleTree(ChunkData data, int cx, int cz, int x, int z, int y, long h) {
        int height = HashUtil.range(HashUtil.mix64(h ^ 0x271L), 10, 16);
        for (int i = 0; i < height; i++) setWorld(data, cx, cz, x, y + i, z, Material.JUNGLE_LOG);
        int top = y + height - 1;
        leafBlob(data, cx, cz, x, top, z, 3, Material.JUNGLE_LEAVES, h);
        leafBlob(data, cx, cz, x + 2, top - 2, z - 1, 2, Material.JUNGLE_LEAVES, h ^ 0x61L);
        leafBlob(data, cx, cz, x - 2, top - 3, z + 1, 2, Material.JUNGLE_LEAVES, h ^ 0x62L);
    }

    private void renderPaleOak(ChunkData data, int cx, int cz, int x, int z, int y, long h) {
        int height = HashUtil.range(HashUtil.mix64(h ^ 0x2761L), 7, 11);
        for (int i = 0; i < height; i++) setWorld(data, cx, cz, x, y + i, z, Material.PALE_OAK_LOG);
        int top = y + height - 1;
        leafBlob(data, cx, cz, x, top + 1, z, 3, Material.PALE_OAK_LEAVES, h ^ 0x81L);
        leafBlob(data, cx, cz, x + 2, top - 1, z, 2, Material.PALE_OAK_LEAVES, h ^ 0x82L);
        leafBlob(data, cx, cz, x - 2, top - 1, z + 1, 2, Material.PALE_OAK_LEAVES, h ^ 0x83L);
    }

    private void renderCherry(ChunkData data, int cx, int cz, int x, int z, int y, long h) {
        int height = HashUtil.range(HashUtil.mix64(h ^ 0x281L), 5, 8);
        for (int i = 0; i < height; i++) setWorld(data, cx, cz, x, y + i, z, Material.CHERRY_LOG);
        int top = y + height - 1;
        leafBlob(data, cx, cz, x, top + 1, z, 2, Material.CHERRY_LEAVES, h);
        leafBlob(data, cx, cz, x + 2, top, z, 2, Material.CHERRY_LEAVES, h ^ 0x71L);
        leafBlob(data, cx, cz, x - 2, top, z + 1, 2, Material.CHERRY_LEAVES, h ^ 0x72L);
    }

    private void renderAspen(ChunkData data, int cx, int cz, int x, int z, int y, long h) {
        int height = HashUtil.range(HashUtil.mix64(h ^ 0xA501L), 10, 16);
        for (int i = 0; i < height; i++) setWorld(data, cx, cz, x, y + i, z, Material.BIRCH_LOG);
        int top = y + height - 1;
        for (int dy = -6; dy <= 1; dy++) {
            int r = dy < -3 ? 1 : 2;
            if ((dy & 1) == 0 && r == 2) r = 1;
            leafDisk(data, cx, cz, x, top + dy, z, r, Material.BIRCH_LEAVES, h ^ (dy * 71L));
        }
    }

    private void renderColumnarOak(ChunkData data, int cx, int cz, int x, int z, int y, long h) {
        int height = HashUtil.range(HashUtil.mix64(h ^ 0xC011L), 9, 15);
        for (int i = 0; i < height; i++) setWorld(data, cx, cz, x, y + i, z, Material.OAK_LOG);
        int top = y + height - 1;
        leafBlob(data, cx, cz, x, top, z, 2, Material.OAK_LEAVES, h);
        for (int i = 0; i < 3; i++) {
            long q = HashUtil.mix64(h ^ (0xC120L + i));
            int dx = (i == 0 ? 1 : i == 1 ? -1 : 0);
            int dz = i == 2 ? ((q & 1L) == 0 ? 1 : -1) : 0;
            int by = top - 2 - i;
            for (int n = 1; n <= 2; n++) setWorld(data, cx, cz, x + dx*n, by + n/2, z + dz*n, Material.OAK_WOOD);
            leafBlob(data, cx, cz, x + dx*2, by + 1, z + dz*2, 2, Material.OAK_LEAVES, q);
        }
    }

    private void renderGiantSpruce(ChunkData data, int cx, int cz, int x, int z, int y, long h) {
        int height = HashUtil.range(HashUtil.mix64(h ^ 0x651A7L), 20, 30);
        for (int i = 0; i < height; i++) {
            setWorld(data, cx, cz, x, y + i, z, Material.SPRUCE_LOG);
            if (i < 7) {
                setWorld(data, cx, cz, x + 1, y + i, z, Material.SPRUCE_LOG);
                setWorld(data, cx, cz, x, y + i, z + 1, Material.SPRUCE_LOG);
            }
        }
        int top = y + height - 1;
        for (int dy = -13; dy <= 1; dy++) {
            int dist = top + 1 - (top + dy);
            int radius = dist > 9 ? 4 : dist > 5 ? 3 : dist > 2 ? 2 : 1;
            if (((dy + 13) & 1) == 1 && radius > 1) radius--;
            leafDisk(data, cx, cz, x, top + dy, z, radius, Material.SPRUCE_LEAVES, h ^ (dy * 97L));
        }
        for (int[] d : new int[][]{{1,0},{-1,0},{0,1},{0,-1}})
            for (int n=1;n<=3;n++) setWorld(data,cx,cz,x+d[0]*n,y,z+d[1]*n,Material.SPRUCE_LOG);
    }

    private void renderWindsweptPine(ChunkData data, int cx, int cz, int x, int z, int y, long h) {
        int height = HashUtil.range(HashUtil.mix64(h ^ 0x71D5L), 9, 16);
        int dx = (h & 1L) == 0 ? 1 : -1;
        int dz = (h & 2L) == 0 ? 0 : ((h & 4L) == 0 ? 1 : -1);
        if (dz != 0) dx = 0;
        int bx=x,bz=z;
        for (int i=0;i<height;i++) {
            if (i > height/2 && (i-height/2)%3==0) { bx += dx; bz += dz; }
            setWorld(data,cx,cz,bx,y+i,bz,Material.SPRUCE_LOG);
        }
        int top=y+height-1;
        leafBlob(data,cx,cz,bx+dx,top,bz+dz,2,Material.SPRUCE_LEAVES,h);
        leafBlob(data,cx,cz,bx+dx*2,top-2,bz+dz*2,2,Material.SPRUCE_LEAVES,h^0x715L);
        leafDisk(data,cx,cz,bx,top-4,bz,2,Material.SPRUCE_LEAVES,h^0x716L);
    }

    private void renderWillow(ChunkData data, int cx, int cz, int x, int z, int y, long h) {
        int height=HashUtil.range(HashUtil.mix64(h^0x8110L),6,10);
        for(int i=0;i<height;i++) setWorld(data,cx,cz,x,y+i,z,Material.OAK_LOG);
        int top=y+height-1;
        int[][] dirs={{1,0},{-1,0},{0,1},{0,-1}};
        for(int i=0;i<dirs.length;i++){
            long q=HashUtil.mix64(h^(0x8120L+i)); int len=HashUtil.range(q,2,5);
            for(int n=1;n<=len;n++) setWorld(data,cx,cz,x+dirs[i][0]*n,top-1+n/3,z+dirs[i][1]*n,Material.OAK_WOOD);
            int ex=x+dirs[i][0]*len, ez=z+dirs[i][1]*len, ey=top+len/3;
            leafBlob(data,cx,cz,ex,ey,ez,2,Material.OAK_LEAVES,q);
            for(int drop=1;drop<=3;drop++) {
                setIfAirWorld(data,cx,cz,ex,ey-drop,ez,Material.OAK_LEAVES);
                if(drop<3) setIfAirWorld(data,cx,cz,ex+dirs[i][1],ey-drop,ez+dirs[i][0],Material.OAK_LEAVES);
            }
        }
        leafBlob(data,cx,cz,x,top+1,z,2,Material.OAK_LEAVES,h^0x8199L);
    }

    private void renderAlder(ChunkData data, int cx, int cz, int x, int z, int y, long h) {
        int stems=HashUtil.range(HashUtil.mix64(h^0xA1D3L),2,4);
        for(int s=0;s<stems;s++){
            long q=HashUtil.mix64(h+s*31L); int ox=s==0?0:HashUtil.range(q,-1,2), oz=s==0?0:HashUtil.range(HashUtil.mix64(q),-1,2);
            int height=HashUtil.range(q,6,11);
            for(int i=0;i<height;i++) setWorld(data,cx,cz,x+ox,y+i,z+oz,Material.OAK_LOG);
            leafBlob(data,cx,cz,x+ox,y+height,z+oz,2,Material.OAK_LEAVES,q);
        }
    }

    private void renderKapok(ChunkData data, int cx, int cz, int x, int z, int y, long h) {
        int height=HashUtil.range(HashUtil.mix64(h^0xCA90L),16,25);
        for(int i=0;i<height;i++) setWorld(data,cx,cz,x,y+i,z,Material.JUNGLE_LOG);
        for(int[] d:new int[][]{{1,0},{-1,0},{0,1},{0,-1}})
            for(int n=1;n<=3;n++) setWorld(data,cx,cz,x+d[0]*n,y+(n==3?0:1),z+d[1]*n,Material.JUNGLE_WOOD);
        int top=y+height-1;
        int[][] dirs={{1,0},{-1,0},{0,1},{0,-1}};
        for(int i=0;i<4;i++){
            int len=HashUtil.range(HashUtil.mix64(h+i*101L),3,6);
            for(int n=1;n<=len;n++) setWorld(data,cx,cz,x+dirs[i][0]*n,top-2+n/3,z+dirs[i][1]*n,Material.JUNGLE_WOOD);
            leafBlob(data,cx,cz,x+dirs[i][0]*len,top,z+dirs[i][1]*len,3,Material.JUNGLE_LEAVES,h^i);
        }
        leafBlob(data,cx,cz,x,top+2,z,3,Material.JUNGLE_LEAVES,h^0xCAB0L);
    }

    private void renderMangrove(ChunkData data, int cx, int cz, int x, int z, int y, long h) {
        int height=HashUtil.range(HashUtil.mix64(h^0x6A6EL),6,11);
        for(int[] d:new int[][]{{1,0},{-1,0},{0,1},{0,-1}}){
            setIfAirWorld(data,cx,cz,x+d[0],y,z+d[1],Material.MANGROVE_ROOTS);
            if(HashUtil.unitDouble(HashUtil.hash(seed,x+d[0],z+d[1],h))<0.55)
                setIfAirWorld(data,cx,cz,x+d[0]*2,y,z+d[1]*2,Material.MANGROVE_ROOTS);
        }
        for(int i=0;i<height;i++) setWorld(data,cx,cz,x,y+i,z,Material.MANGROVE_LOG);
        int top=y+height-1;
        leafBlob(data,cx,cz,x,top+1,z,3,Material.MANGROVE_LEAVES,h);
        leafBlob(data,cx,cz,x+2,top-1,z,2,Material.MANGROVE_LEAVES,h^0x6A1L);
        leafBlob(data,cx,cz,x-2,top-1,z+1,2,Material.MANGROVE_LEAVES,h^0x6A2L);
    }

    private void leafDisk(ChunkData data, int cx, int cz, int x, int y, int z, int radius,
                          Material leaves, long salt) {
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                double d = dx * dx + dz * dz;
                if (d > radius * radius + 0.35) continue;
                if (d > (radius - 0.4) * (radius - 0.4)
                        && HashUtil.unitDouble(HashUtil.hash(seed, x + dx, z + dz, salt ^ y)) > 0.72) continue;
                setIfAirWorld(data, cx, cz, x + dx, y, z + dz, leaves);
            }
        }
    }

    private void leafBlob(ChunkData data, int cx, int cz, int x, int y, int z, int radius,
                          Material leaves, long salt) {
        for (int dy = -radius; dy <= radius; dy++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    double d = dx * dx + dz * dz + dy * dy * 1.18;
                    if (d > radius * radius + 0.60) continue;
                    if (d > (radius - 0.35) * (radius - 0.35)
                            && HashUtil.unitDouble(HashUtil.hash(seed, x + dx + dy * 17L, z + dz, salt)) > 0.70) continue;
                    setIfAirWorld(data, cx, cz, x + dx, y + dy, z + dz, leaves);
                }
            }
        }
    }

    private static void setIfAirWorld(ChunkData data, int chunkX, int chunkZ,
                                      int wx, int y, int wz, Material material) {
        int lx = wx - chunkX * 16, lz = wz - chunkZ * 16;
        if (lx < 0 || lx >= 16 || lz < 0 || lz >= 16 || y < data.getMinHeight() || y >= data.getMaxHeight()) return;
        if (data.getType(lx, y, lz) == Material.AIR) setGeneratedBlock(data,lx,y,lz,material);
    }

    private static void setWorld(ChunkData data, int chunkX, int chunkZ,
                                 int wx, int y, int wz, Material material) {
        int lx = wx - chunkX * 16, lz = wz - chunkZ * 16;
        if (lx < 0 || lx >= 16 || lz < 0 || lz >= 16 || y < data.getMinHeight() || y >= data.getMaxHeight()) return;
        setGeneratedBlock(data,lx,y,lz,material);
    }

    /**
     * Les feuilles dessinées bloc par bloc ne passent pas par la feature arbre vanilla.
     * Elles doivent donc être explicitement persistantes, sinon le moteur de decay les
     * supprime dès qu'une partie de la couronne est à plus de 7 blocs d'un tronc.
     */
    private static void setGeneratedBlock(ChunkData data,int x,int y,int z,Material material) {
        BlockData blockData=material.createBlockData();
        if(blockData instanceof Leaves leaves) {
            leaves.setPersistent(true);
            data.setBlock(x,y,z,leaves);
        } else {
            data.setBlock(x,y,z,material);
        }
    }

    private record SurfaceSample(int y, double slope, ClimateEngine.ClimateSample climate,
                                 RiverEngine.RiverSample river, LakeEngine.LakeSample lake,
                                 boolean plantable) {}
}
