package fr.antho.realisticworld.hydrology;

import fr.antho.realisticworld.config.WorldGenConfig;
import fr.antho.realisticworld.terrain.TerrainEngine;
import fr.antho.realisticworld.util.BoundedCache;

/**
 * Arbitre unique de la colonne terrain/eau.
 *
 * <p>Les moteurs hydrologiques calculent leur géométrie et leur cote d'eau en amont.
 * WaterColumnEngine ne recalcule pas ces cotes à partir de la hauteur naturelle locale :
 * il choisit le corps d'eau propriétaire de la colonne, lui associe son propre lit et
 * expose exactement la même colonne à noise/surface/caves/getBaseHeight.</p>
 */
public final class WaterColumnEngine {
    private final TerrainEngine terrain;
    private final RiverEngine rivers;
    private final LakeEngine lakes;
    private final BoundedCache<Long, ColumnSample> cache;

    public WaterColumnEngine(TerrainEngine terrain, RiverEngine rivers, LakeEngine lakes,
                             WorldGenConfig.Rivers ignoredRiverCfg, int cacheChunks) {
        this.terrain = terrain;
        this.rivers = rivers;
        this.lakes = lakes;
        // Cache par colonne plutôt que tuile pré-calculée : /locate et getBaseHeight
        // interrogent souvent un seul point dans beaucoup de chunks éloignés. Préparer 256
        // colonnes à chaque miss rendrait ce chemin inutilement coûteux.
        this.cache = new BoundedCache<>(Math.max(4096, cacheChunks * 256));
    }

    public ColumnSample sample(int x, int z) {
        long key = (((long) x) << 32) ^ (z & 0xffffffffL);
        return cache.computeIfAbsent(key, ignored -> buildColumn(x, z));
    }

    private ColumnSample buildColumn(int wx, int wz) {
        double natural = terrain.heightWithoutRivers(wx, wz);
        RiverEngine.RiverSample river = rivers.sample(wx, wz);
        LakeEngine.LakeSample lake = lakes.sample(wx, wz);

        int sea = terrain.seaLevel();
        boolean oceanColumn = natural < sea;
        ColumnOwner owner = resolveOwner(oceanColumn, river, lake);

        // Le propriétaire de l'eau possède aussi le lit correspondant. À une confluence,
        // on ne peut donc plus prendre le carve rivière puis le niveau du lac (ou l'inverse).
        // Hors eau, les faibles corrections sèches de berge/rive restent additives et lisses.
        double carve = switch (owner) {
            case OCEAN -> river.isRiver() ? river.carveDepth() : 0.0;
            case LAKE -> lake.carveDepth();
            case RIVER -> river.carveDepth();
            case NONE -> river.carveDepth() + lake.carveDepth();
        };
        double ground = natural - carve;

        // Aucun plafond côtier ni clamp par natural n'est appliqué ici. RiverEngine/LakeEngine
        // sont responsables de leur cote hydraulique ; la modifier bloc par bloc recréerait
        // précisément les anneaux de lac et les marches transversales observés en v1.8.
        double water = switch (owner) {
            case OCEAN -> sea;
            case LAKE -> lake.waterSurface();
            case RIVER -> river.waterSurface();
            case NONE -> Double.NEGATIVE_INFINITY;
        };

        int groundY = (int) Math.floor(ground);
        int waterTop = Double.isFinite(water)
                ? (int) Math.floor(water + 1.0e-6)
                : Integer.MIN_VALUE;

        // Garde géométrique uniquement : on ne déplace jamais la surface hydraulique.
        // Si le moteur propriétaire n'a pas réellement creusé sous sa cote d'eau, la
        // colonne reste sèche plutôt que de créer une nappe flottante.
        if (waterTop <= groundY) waterTop = Integer.MIN_VALUE;

        return new ColumnSample(natural, ground, groundY, waterTop, oceanColumn, river, lake);
    }

    private static ColumnOwner resolveOwner(boolean oceanColumn, RiverEngine.RiverSample river,
                                             LakeEngine.LakeSample lake) {
        if (oceanColumn) return ColumnOwner.OCEAN;

        boolean lakeWater = lake.isLake() && Double.isFinite(lake.waterSurface());
        boolean riverWater = river.isRiver() && Double.isFinite(river.waterSurface());

        // Dans l'emprise réelle d'un lac, la cote unique du bassin domine. Une rivière qui
        // traverse/rejoint ce bassin ne peut pas imposer localement une autre nappe.
        if (lakeWater) return ColumnOwner.LAKE;
        if (riverWater) return ColumnOwner.RIVER;
        return ColumnOwner.NONE;
    }

    private enum ColumnOwner {
        NONE,
        OCEAN,
        RIVER,
        LAKE
    }

    /**
     * Ratio de points terrestres sur une grille 3x3 du chunk. Utilisé avant les carvers
     * vanilla : on n'a pas besoin de construire le watershed juste pour protéger un océan.
     */
    public double landRatioForCaves(int chunkX, int chunkZ) {
        int sea = terrain.seaLevel();
        int x0 = chunkX * 16, z0 = chunkZ * 16;
        int land = 0, total = 0;
        int[] o = {2, 8, 13};
        for (int dz : o) for (int dx : o) {
            total++;
            double h = terrain.heightWithoutRivers(x0 + dx, z0 + dz);
            if (h > sea + 2.0) land++;
        }
        return land / (double) total;
    }

    public record ColumnSample(double naturalHeight, double groundHeight, int groundY, int waterTop,
                               boolean oceanColumn, RiverEngine.RiverSample river,
                               LakeEngine.LakeSample lake) {
        public boolean hasWater() { return waterTop != Integer.MIN_VALUE; }
        public int worldSurfaceY() { return hasWater() ? Math.max(groundY, waterTop) : groundY; }
    }
}
