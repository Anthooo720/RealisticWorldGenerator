package fr.antho.realisticworld.hydrology;

import fr.antho.realisticworld.config.WorldGenConfig;
import fr.antho.realisticworld.terrain.TerrainEngine;
import fr.antho.realisticworld.util.BoundedCache;
import fr.antho.realisticworld.util.MathUtil;

/**
 * Arbitre unique de la colonne terrain/eau.
 *
 * <p>Avant la v1.8, generateNoise(), generateSurface(), getBaseHeight() et les caves
 * recalculaient chacun natural/river/lake et combinaient les niveaux d'eau de façon
 * légèrement différente. Une rivière côtière ou un lac proche de la mer pouvait donc
 * fournir un waterSurface supérieur au niveau marin tandis que le terrain voisin était
 * déjà océanique. Ici une colonne possède une seule vérité : sol final + niveau d'eau.
 * Le résultat est caché par colonne afin de ne pas multiplier les samples hydrologiques lors des appels dispersés de worldgen.</p>
 */
public final class WaterColumnEngine {
    private final TerrainEngine terrain;
    private final RiverEngine rivers;
    private final LakeEngine lakes;
    private final WorldGenConfig.Rivers riverCfg;
    private final BoundedCache<Long, ColumnSample> cache;

    public WaterColumnEngine(TerrainEngine terrain, RiverEngine rivers, LakeEngine lakes,
                             WorldGenConfig.Rivers riverCfg, int cacheChunks) {
        this.terrain = terrain;
        this.rivers = rivers;
        this.lakes = lakes;
        this.riverCfg = riverCfg;
        // Cache par colonne plutôt que tuile pré-calculée : /locate et getBaseHeight
        // interrogent souvent un seul point dans beaucoup de chunks éloignés. Préparer 256
        // colonnes à chaque miss rendait ce chemin inutilement coûteux.
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

            // Les systèmes restent séparés mais une colonne ne doit jamais additionner deux
            // excavations fortes à une confluence lac/rivière. On garde l'intervention qui
            // domine localement ; une berge de lac (carve négatif = léger remblai) reste valide.
            double riverCarve = river.carveDepth();
            double lakeCarve = lake.carveDepth();
            double carve;
            if (river.strength() > 0.04 && lake.strength() > 0.04) {
                carve = Math.abs(riverCarve) >= Math.abs(lakeCarve) ? riverCarve : lakeCarve;
            } else {
                carve = riverCarve + lakeCarve;
            }
            double ground = natural - carve;

            int sea = terrain.seaLevel();
            boolean oceanColumn = natural < sea;
            double riverWater = river.isRiver() ? river.waterSurface() : Double.NEGATIVE_INFINITY;
            double lakeWater = lake.isLake() ? lake.waterSurface() : Double.NEGATIVE_INFINITY;

            // Estuaire : un cours d'eau qui atteint la bande littorale converge vers le niveau
            // marin bien AVANT la dernière colonne. Cela évite les terrasses d'eau suspendues
            // de 3-8 blocs au-dessus de l'océan observées en v1.7.
            if (Double.isFinite(riverWater)) {
                double coastalCeiling = coastalWaterCeiling(natural);
                if (river.estuary() || natural <= sea + riverCfg.coastalMergeHeight()) {
                    riverWater = Math.min(riverWater, coastalCeiling);
                }
                if (oceanColumn) riverWater = sea;
                riverWater = Math.min(riverWater, Math.floor(natural - 0.35));
            }

            // Un lac côtier qui recoupe une colonne océanique n'a pas le droit d'imposer son
            // niveau local à la mer. LakeEngine possède aussi son propre garde de bassin.
            if (Double.isFinite(lakeWater) && (oceanColumn || natural <= sea + 1.0)) {
                lakeWater = Double.NEGATIVE_INFINITY;
            }

            double water = Double.NEGATIVE_INFINITY;
            if (oceanColumn) water = sea;
            if (Double.isFinite(riverWater)) water = Math.max(water, riverWater);
            if (Double.isFinite(lakeWater)) water = Math.max(water, lakeWater);

            // Garde final : hors océan, aucune nappe ne peut flotter au-dessus du terrain
            // naturel qui la contient. Le lit peut être plus bas grâce au carve, pas l'eau.
            if (!oceanColumn && Double.isFinite(water)) {
                water = Math.min(water, Math.floor(natural - 0.35));
            }

            int groundY = (int) Math.floor(ground);
            int waterTop = Double.isFinite(water) ? (int) Math.floor(water + 1.0e-6) : Integer.MIN_VALUE;
            if (waterTop <= groundY) waterTop = Integer.MIN_VALUE;

            return new ColumnSample(natural, ground, groundY, waterTop, oceanColumn, river, lake);
    }

    private double coastalWaterCeiling(double natural) {
        int sea = terrain.seaLevel();
        double above = Math.max(0.0, natural - (sea + 1.5));
        double rise = above * MathUtil.clamp(riverCfg.coastalWaterGradient(), 0.05, 0.80);
        return sea + Math.min(rise, Math.max(1.0, riverCfg.coastalMergeHeight() * 0.42));
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
