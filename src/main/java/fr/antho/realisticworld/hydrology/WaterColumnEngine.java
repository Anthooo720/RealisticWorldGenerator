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
 * expose exactement la même colonne à noise/surface/caves/getBaseHeight/API.</p>
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

        double carve = switch (owner) {
            case OCEAN -> river.isRiver() ? river.carveDepth() : 0.0;
            case LAKE -> lake.carveDepth();
            case RIVER -> river.carveDepth();
            case NONE -> dryCarve(river, lake);
        };
        double ground = natural - carve;

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

        // Garde géométrique uniquement : la cote n'est jamais déplacée par l'arbitre.
        if (waterTop <= groundY) {
            waterTop = Integer.MIN_VALUE;
            water = Double.NEGATIVE_INFINITY;
        }

        return new ColumnSample(natural, ground, groundY, water, waterTop, oceanColumn, river, lake);
    }

    private static double dryCarve(RiverEngine.RiverSample river, LakeEngine.LakeSample lake) {
        double riverCarve=river.carveDepth();
        double lakeCarve=lake.carveDepth();
        if(river.strength()>0.02 && lake.strength()>0.02) {
            // Deux transitions sèches ne s'additionnent jamais : on garde la modification
            // dominante pour éviter une encoche à la rencontre rive de lac/berge de rivière.
            return Math.abs(riverCarve)>=Math.abs(lakeCarve)?riverCarve:lakeCarve;
        }
        return riverCarve+lakeCarve;
    }

    private static ColumnOwner resolveOwner(boolean oceanColumn, RiverEngine.RiverSample river,
                                             LakeEngine.LakeSample lake) {
        if (oceanColumn) return ColumnOwner.OCEAN;
        boolean lakeWater = lake.isLake() && Double.isFinite(lake.waterSurface());
        boolean riverWater = river.isRiver() && Double.isFinite(river.waterSurface());
        if (lakeWater) return ColumnOwner.LAKE;
        if (riverWater) return ColumnOwner.RIVER;
        return ColumnOwner.NONE;
    }

    private enum ColumnOwner { NONE, OCEAN, RIVER, LAKE }

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

    public record ColumnSample(double naturalHeight, double groundHeight, int groundY,
                               double waterSurface, int waterTop, boolean oceanColumn,
                               RiverEngine.RiverSample river, LakeEngine.LakeSample lake) {
        public boolean hasWater() { return waterTop != Integer.MIN_VALUE; }
        public int worldSurfaceY() { return hasWater() ? Math.max(groundY, waterTop) : groundY; }
    }
}
