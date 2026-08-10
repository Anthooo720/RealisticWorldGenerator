package fr.antho.realisticworld.terrain;

import fr.antho.realisticworld.config.WorldGenConfig;
import fr.antho.realisticworld.geology.GeologyMap;
import fr.antho.realisticworld.noise.SimplexNoise;
import fr.antho.realisticworld.util.BoundedCache;
import fr.antho.realisticworld.util.MathUtil;

import java.util.Arrays;

/**
 * Érosion hydraulique + thermique par tuiles. La simulation suit eau, sédiments et
 * inertie de l'écoulement ; la géologie module l'érodabilité et le talus naturel.
 */
public final class ErosionEngine {
    private final TerrainEngine terrain;
    private final GeologyMap geology;
    private final WorldGenConfig.Erosion cfg;
    private final BoundedCache<TileKey, Tile> cache;
    private final SimplexNoise rainfallNoise;

    public ErosionEngine(long seed, TerrainEngine terrain, GeologyMap geology,
                         WorldGenConfig.Erosion cfg, int cacheTiles) {
        this.terrain = terrain;
        this.geology = geology;
        this.cfg = cfg;
        this.cache = new BoundedCache<>(Math.max(8, cacheTiles));
        this.rainfallNoise = new SimplexNoise(seed ^ 0x45524F53494F4E4CL);
    }

    public double height(double x, double z) {
        if (!cfg.enabled()) return terrain.baseHeightRaw(x, z);
        int ts = Math.max(128, cfg.tileSize());
        int tx = Math.floorDiv((int) Math.floor(x), ts);
        int tz = Math.floorDiv((int) Math.floor(z), ts);
        Tile t = cache.computeIfAbsent(new TileKey(tx, tz), this::buildTile);
        return t.sample(x, z);
    }

    private Tile buildTile(TileKey key) {
        int spacing = Math.max(4, cfg.sampleSpacing());
        int core = Math.max(16, cfg.tileSize() / spacing);
        int margin = Math.max(4, cfg.marginSamples());
        int n = core + margin * 2 + 1;
        int originX = key.x * cfg.tileSize() - margin * spacing;
        int originZ = key.z * cfg.tileSize() - margin * spacing;

        int size=n*n;
        double[] h = new double[size];
        double[] original = new double[size];
        double[] water = new double[size];
        double[] sediment = new double[size];
        double[] vx = new double[size];
        double[] vz = new double[size];
        double[] resistance = new double[size];
        for (int z = 0; z < n; z++) {
            for (int x = 0; x < n; x++) {
                int i = z * n + x;
                int wx = originX + x * spacing;
                int wz = originZ + z * spacing;
                h[i] = original[i] = terrain.baseHeightRaw(wx, wz);
                resistance[i] = geology.sample(wx, wz).erosionResistance();
            }
        }

        int[] dx = {-1,0,1,-1,1,-1,0,1};
        int[] dz = {-1,-1,-1,0,0,1,1,1};
        double inertia = MathUtil.clamp(cfg.velocityInertia(), 0.0, 0.92);

        // Buffers réutilisés entre itérations : l'ancienne version allouait quatre grands
        // tableaux à chaque tour hydraulique, créant beaucoup de pression GC à chaque miss
        // de tuile sans apporter la moindre différence visuelle.
        double[] nextWater = new double[size];
        double[] nextSed = new double[size];
        double[] nextVx = new double[size];
        double[] nextVz = new double[size];

        for (int iter = 0; iter < Math.max(0, cfg.hydraulicIterations()); iter++) {
            Arrays.fill(nextWater,0.0);
            Arrays.fill(nextSed,0.0);
            Arrays.fill(nextVx,0.0);
            Arrays.fill(nextVz,0.0);

            for (int z = 1; z < n - 1; z++) {
                for (int x = 1; x < n - 1; x++) {
                    int idx = z * n + x;
                    int wx = originX + x * spacing;
                    int wz = originZ + z * spacing;
                    double rainPatch = rainfallNoise.sample(wx * 0.00055, wz * 0.00055) * 0.5 + 0.5;
                    water[idx] += cfg.rainfall() * (0.72 + rainPatch * 0.56);

                    double surface = h[idx] + water[idx] * 0.18;
                    int best = -1;
                    double bestScore = 0.0;
                    double bestDrop = 0.0;
                    int bestDx = 0, bestDz = 0;
                    for (int k = 0; k < 8; k++) {
                        int ni = (z + dz[k]) * n + (x + dx[k]);
                        double distance = (dx[k] != 0 && dz[k] != 0) ? 1.41421356237 : 1.0;
                        double drop = surface - (h[ni] + water[ni] * 0.18);
                        if (drop <= 0) continue;
                        double dirX = dx[k] / distance, dirZ = dz[k] / distance;
                        double alignment = Math.max(0.0, vx[idx] * dirX + vz[idx] * dirZ);
                        double score = drop / distance * (1.0 + alignment * inertia * 0.55);
                        if (score > bestScore) {
                            bestScore = score;
                            bestDrop = drop / distance;
                            best = ni;
                            bestDx = dx[k]; bestDz = dz[k];
                        }
                    }

                    if (best < 0) {
                        nextWater[idx] += water[idx] * (1.0 - cfg.evaporation());
                        nextSed[idx] += sediment[idx];
                        continue;
                    }

                    double flowFraction = MathUtil.clamp(0.46 + bestDrop * 0.055, 0.46, 0.82);
                    double moved = water[idx] * flowFraction;
                    double retained = water[idx] - moved;
                    nextWater[idx] += retained * (1.0 - cfg.evaporation());

                    double distance = (bestDx != 0 && bestDz != 0) ? 1.41421356237 : 1.0;
                    double dirX = bestDx / distance, dirZ = bestDz / distance;
                    double outVx = vx[idx] * inertia + dirX * (1.0 - inertia);
                    double outVz = vz[idx] * inertia + dirZ * (1.0 - inertia);
                    double speed = Math.hypot(outVx, outVz) * (0.60 + Math.sqrt(Math.max(0, bestDrop)) * 0.42);
                    double slope = bestDrop / Math.max(1.0, spacing);
                    double hardness = resistance[idx];
                    double capacity = Math.max(0.005,
                            moved * Math.sqrt(Math.max(0.0001, slope)) * speed * cfg.capacity() * (1.22 - hardness * 0.46));

                    double carried = sediment[idx] * flowFraction;
                    double localSed = sediment[idx] - carried;
                    nextSed[idx] += localSed;
                    if (carried < capacity) {
                        double erodibility = 0.36 + (1.0 - hardness) * 0.92;
                        double erode = Math.min((capacity - carried) * cfg.erosionRate() * erodibility, 0.62);
                        h[idx] -= erode;
                        carried += erode;
                    } else {
                        double deposit = Math.min((carried - capacity) * cfg.depositionRate(), 0.52);
                        h[idx] += deposit;
                        carried -= deposit;
                    }

                    nextWater[best] += moved * (1.0 - cfg.evaporation());
                    nextSed[best] += carried;
                    nextVx[best] += outVx * moved;
                    nextVz[best] += outVz * moved;
                }
            }

            // Échange des buffers au lieu de recopier quatre tableaux complets.
            double[] swap=water; water=nextWater; nextWater=swap;
            swap=sediment; sediment=nextSed; nextSed=swap;
            swap=vx; vx=nextVx; nextVx=swap;
            swap=vz; vz=nextVz; nextVz=swap;

            for (int i = 0; i < size; i++) {
                if (water[i] > 1.0e-8) {
                    vx[i] /= water[i];
                    vz[i] /= water[i];
                    double len = Math.hypot(vx[i], vz[i]);
                    if (len > 1.0) { vx[i] /= len; vz[i] /= len; }
                } else { vx[i] = 0; vz[i] = 0; }
            }
        }

        // Érosion thermique : un seul buffer delta réutilisé entre itérations.
        double[] delta = new double[size];
        for (int iter = 0; iter < Math.max(0, cfg.thermalIterations()); iter++) {
            Arrays.fill(delta,0.0);
            for (int z = 1; z < n - 1; z++) {
                for (int x = 1; x < n - 1; x++) {
                    int idx = z * n + x;
                    double localTalus = cfg.talus() * (0.72 + resistance[idx] * 0.62);
                    for (int k = 0; k < 8; k++) {
                        int ni = (z + dz[k]) * n + (x + dx[k]);
                        double diff = h[idx] - h[ni];
                        if (diff > localTalus) {
                            double move = (diff - localTalus) * (0.026 + (1.0 - resistance[idx]) * 0.035);
                            delta[idx] -= move;
                            delta[ni] += move;
                        }
                    }
                }
            }
            for (int i = 0; i < size; i++) h[i] += delta[i];
        }

        double intensity = MathUtil.clamp(cfg.intensity(), 0.0, 1.5);
        for (int i = 0; i < size; i++) h[i] = original[i] + (h[i] - original[i]) * intensity;
        return new Tile(originX, originZ, spacing, n, h);
    }

    private record TileKey(int x, int z) {}

    private record Tile(int originX, int originZ, int spacing, int n, double[] heights) {
        double sample(double x, double z) {
            double gx = (x - originX) / spacing;
            double gz = (z - originZ) / spacing;
            int ix = MathUtil.clamp((int) Math.floor(gx), 0, n - 2);
            int iz = MathUtil.clamp((int) Math.floor(gz), 0, n - 2);
            double tx = MathUtil.clamp(gx - ix, 0, 1);
            double tz = MathUtil.clamp(gz - iz, 0, 1);
            int a = iz * n + ix, b = a + 1, c = (iz + 1) * n + ix, d = c + 1;
            return MathUtil.bilerp(heights[a], heights[b], heights[c], heights[d], tx, tz);
        }
    }
}
