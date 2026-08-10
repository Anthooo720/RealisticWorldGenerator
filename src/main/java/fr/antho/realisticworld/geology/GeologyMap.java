package fr.antho.realisticworld.geology;

import fr.antho.realisticworld.config.WorldGenConfig;
import fr.antho.realisticworld.util.HashUtil;
import fr.antho.realisticworld.util.MathUtil;
import org.bukkit.Material;

/**
 * Provinces géologiques de grande taille. La géologie influe sur la dureté face à
 * l'érosion, la capacité à former des falaises et la palette rocheuse visible.
 */
public final class GeologyMap {
    private final long seed;
    private final WorldGenConfig.Geology cfg;

    public GeologyMap(long seed, WorldGenConfig.Geology cfg) {
        this.seed = seed;
        this.cfg = cfg;
    }

    public GeologySample sample(double x, double z) {
        if (!cfg.enabled()) return sampleForType(RockType.GRANITIC, 1.0);
        int cell = Math.max(1800, cfg.provinceSize());
        int cx = Math.floorDiv((int) Math.floor(x), cell);
        int cz = Math.floorDiv((int) Math.floor(z), cell);

        Site first = null, second = null;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                Site s = site(cx + dx, cz + dz, cell);
                double ddx = x - s.x;
                double ddz = z - s.z;
                double d2 = ddx * ddx + ddz * ddz;
                s = new Site(s.x, s.z, s.type, d2);
                if (first == null || d2 < first.d2) {
                    second = first;
                    first = s;
                } else if (second == null || d2 < second.d2) {
                    second = s;
                }
            }
        }

        if (first == null) return sampleForType(RockType.GRANITIC, 1.0);
        double d1 = Math.sqrt(first.d2);
        double d2 = second == null ? d1 + cell : Math.sqrt(second.d2);
        double boundary = MathUtil.clamp((d2 - d1) / Math.max(1.0, cell * cfg.contactBlend()), 0.0, 1.0);
        GeologySample a = sampleForType(first.type, boundary);
        if (second == null || boundary > 0.94) return a;
        GeologySample b = sampleForType(second.type, 1.0 - boundary);
        double t = MathUtil.smootherstep(0.0, 1.0, boundary);
        return new GeologySample(first.type, a.erosionResistance * t + b.erosionResistance * (1.0 - t),
                a.cliffFactor * t + b.cliffFactor * (1.0 - t),
                a.soilDepth * t + b.soilDepth * (1.0 - t), a.surfaceRock, a.deepRock);
    }

    /**
     * Géologie volumétrique compatible avec les remplacements d'ores vanilla : la roche
     * profonde privilégie STONE/GRANITE/DIORITE/ANDESITE puis DEEPSLATE, avec TUFF/CALCITE
     * en accents géologiques plutôt que de grandes masses de sandstone/terracotta.
     */
    public Material rockAt(double x, int y, double z) {
        return rockAt(sample(x,z), x, y, z);
    }

    public Material rockAt(GeologySample g, double x, int y, double z) {
        double dipX = Math.sin((g.type().ordinal() + 1) * 1.71) * cfg.dipStrength();
        double dipZ = Math.cos((g.type().ordinal() + 1) * 1.37) * cfg.dipStrength();
        double phase = y + x * cfg.strataScale() * dipX * 18.0 + z * cfg.strataScale() * dipZ * 18.0;
        double thickness = Math.max(3.0, cfg.strataThickness());
        int band = Math.floorMod((int) Math.floor(phase / thickness), 8);

        if (y < -28) {
            // Tuff rare dans les provinces volcaniques, sinon deepslate : les minerais
            // deepslate vanilla conservent ainsi leurs cibles naturelles.
            if (g.type()==RockType.VOLCANIC && (band==1 || band==6)) return Material.TUFF;
            return Material.DEEPSLATE;
        }

        return switch (g.type()) {
            case GRANITIC -> switch (band) {
                case 1, 5 -> Material.GRANITE;
                case 3 -> Material.DIORITE;
                case 7 -> Material.ANDESITE;
                default -> Material.STONE;
            };
            case METAMORPHIC -> switch (band) {
                case 0, 4 -> Material.ANDESITE;
                case 2 -> Material.DIORITE;
                case 6 -> Material.GRANITE;
                default -> Material.STONE;
            };
            case LIMESTONE -> switch (band) {
                case 1, 6 -> Material.CALCITE;
                case 3 -> Material.DIORITE;
                default -> Material.STONE;
            };
            case VOLCANIC -> switch (band) {
                case 0, 5 -> Material.TUFF;
                case 2, 7 -> Material.ANDESITE;
                default -> Material.STONE;
            };
            case SEDIMENTARY -> switch (band) {
                case 2 -> Material.DIORITE;
                case 6 -> Material.CALCITE;
                default -> Material.STONE;
            };
            case ARID_SEDIMENTARY -> switch (band) {
                case 1 -> Material.GRANITE;
                case 4 -> Material.DIORITE;
                case 7 -> Material.ANDESITE;
                default -> Material.STONE;
            };
        };
    }

    private Site site(int cx, int cz, int cell) {
        long h = HashUtil.hash(seed, cx, cz, 0x6E01061C4AL);
        double jx = 0.18 + HashUtil.unitDouble(h) * 0.64;
        double jz = 0.18 + HashUtil.unitDouble(HashUtil.mix64(h ^ 0x77L)) * 0.64;
        int typeIndex = HashUtil.range(HashUtil.mix64(h ^ 0x991AL), 0, RockType.values().length);
        return new Site((cx + jx) * cell, (cz + jz) * cell, RockType.values()[typeIndex], 0.0);
    }

    private static GeologySample sampleForType(RockType t, double dominance) {
        return switch (t) {
            case GRANITIC -> new GeologySample(t, 0.90, 0.92, 0.46, Material.STONE, Material.STONE);
            case METAMORPHIC -> new GeologySample(t, 0.83, 0.86, 0.42, Material.ANDESITE, Material.STONE);
            case LIMESTONE -> new GeologySample(t, 0.61, 0.94, 0.58, Material.CALCITE, Material.STONE);
            case VOLCANIC -> new GeologySample(t, 0.78, 0.88, 0.38, Material.TUFF, Material.STONE);
            case SEDIMENTARY -> new GeologySample(t, 0.48, 0.68, 0.72, Material.STONE, Material.STONE);
            case ARID_SEDIMENTARY -> new GeologySample(t, 0.39, 0.74, 0.34, Material.GRANITE, Material.STONE);
        };
    }

    public enum RockType { GRANITIC, METAMORPHIC, LIMESTONE, VOLCANIC, SEDIMENTARY, ARID_SEDIMENTARY }

    public record GeologySample(RockType type, double erosionResistance, double cliffFactor,
                                double soilDepth, Material surfaceRock, Material deepRock) {}

    private record Site(double x, double z, RockType type, double d2) {}
}
