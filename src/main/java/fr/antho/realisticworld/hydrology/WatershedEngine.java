package fr.antho.realisticworld.hydrology;

import fr.antho.realisticworld.config.WorldGenConfig;
import fr.antho.realisticworld.terrain.TerrainEngine;
import fr.antho.realisticworld.util.BoundedCache;
import fr.antho.realisticworld.util.HashUtil;
import fr.antho.realisticworld.util.MathUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Bassins versants : Priority-Flood, drainage D8, accumulation puis extraction de
 * segments de chenal continus. La distance et le niveau d'eau sont calculés par
 * projection sur ces segments, et non plus par interpolation de cellules bleues.
 *
 * Cette représentation est essentielle dans Minecraft : une source d'eau placée
 * au-dessus d'une berge se met immédiatement à couler hors du lit. Chaque nœud de
 * chenal reçoit donc aussi un niveau d'eau "safe" borné par les deux berges.
 */
public final class WatershedEngine {
    private final long seed;
    private final TerrainEngine terrain;
    private final WorldGenConfig.Rivers cfg;
    private final BoundedCache<TileKey, Tile> cache;

    public WatershedEngine(long seed, TerrainEngine terrain, WorldGenConfig.Rivers cfg, int cacheTiles) {
        this.seed = seed;
        this.terrain = terrain;
        this.cfg = cfg;
        this.cache = new BoundedCache<>(Math.max(8, cacheTiles));
    }

    public HydroSample sample(double x, double z) {
        int ts = Math.max(256, cfg.tileSize());
        int tx = Math.floorDiv((int) Math.floor(x), ts);
        int tz = Math.floorDiv((int) Math.floor(z), ts);
        return cache.computeIfAbsent(new TileKey(tx, tz), this::buildTile).sample(x, z);
    }

    private Tile buildTile(TileKey key) {
        int spacing = Math.max(4, cfg.sampleSpacing());
        int core = Math.max(24, cfg.tileSize() / spacing);
        int margin = Math.max(10, cfg.marginSamples());
        int n = core + margin * 2 + 1;
        int ox = key.x * cfg.tileSize() - margin * spacing;
        int oz = key.z * cfg.tileSize() - margin * spacing;
        int size = n * n;

        double[] h = new double[size];
        double[] filled = new double[size];
        double[] flow = new double[size];
        int[] downstream = new int[size];
        int[] root = new int[size];
        long[] basin = new long[size];
        Arrays.fill(downstream, -1);
        Arrays.fill(root, -1);
        Integer[] order = new Integer[size];

        for (int z = 0; z < n; z++) for (int x = 0; x < n; x++) {
            int i = z * n + x;
            double elevation = terrain.heightWithoutRivers(ox + x * spacing, oz + z * spacing);
            h[i] = filled[i] = elevation;
            flow[i] = 0.82 + MathUtil.clamp((elevation - terrain.seaLevel()) / 180.0, 0, 1) * 0.34;
            order[i] = i;
        }

        int[] dx = {-1,0,1,-1,1,-1,0,1};
        int[] dz = {-1,-1,-1,0,0,1,1,1};
        boolean[] visited = new boolean[size];
        PriorityQueue<FloodNode> flood = new PriorityQueue<>(Comparator.comparingDouble(FloodNode::height));
        for (int x = 0; x < n; x++) {
            seedBoundary(x, 0, n, filled, visited, flood);
            seedBoundary(x, n - 1, n, filled, visited, flood);
        }
        for (int z = 1; z < n - 1; z++) {
            seedBoundary(0, z, n, filled, visited, flood);
            seedBoundary(n - 1, z, n, filled, visited, flood);
        }

        final double epsilon = 0.002;
        while (!flood.isEmpty()) {
            FloodNode node = flood.poll();
            int i = node.index;
            int x = i % n, z = i / n;
            for (int k = 0; k < 8; k++) {
                int nx = x + dx[k], nz = z + dz[k];
                if (nx < 0 || nx >= n || nz < 0 || nz >= n) continue;
                int ni = nz * n + nx;
                if (visited[ni]) continue;
                visited[ni] = true;
                filled[ni] = Math.max(h[ni], node.height + epsilon);
                flood.add(new FloodNode(ni, filled[ni]));
            }
        }

        // D8 sur la surface remplie : garantit un chemin d'écoulement sans boucle.
        for (int z = 1; z < n - 1; z++) for (int x = 1; x < n - 1; x++) {
            int i = z * n + x;
            double bestFilled = filled[i];
            double bestOriginal = Double.POSITIVE_INFINITY;
            int best = -1;
            for (int k = 0; k < 8; k++) {
                int ni = (z + dz[k]) * n + (x + dx[k]);
                double f = filled[ni];
                if (f < bestFilled - 1.0e-8 || (Math.abs(f - bestFilled) < 1.0e-8 && h[ni] < bestOriginal)) {
                    bestFilled = f;
                    bestOriginal = h[ni];
                    best = ni;
                }
            }
            downstream[i] = best;
        }

        Arrays.sort(order, (a, b) -> Double.compare(filled[b], filled[a]));
        for (int i : order) {
            int d = downstream[i];
            if (d >= 0) flow[d] += flow[i];
        }

        for (int i = 0; i < size; i++) {
            int r = findRoot(i, downstream, root);
            int rx = r % n, rz = r / n;
            basin[i] = HashUtil.hash(seed, ox + rx * spacing, oz + rz * spacing, 0x424153494E4944L);
        }

        double threshold = Math.max(4.0, cfg.accumulationThreshold());
        boolean[] center = new boolean[size];
        double[] safeWater = new double[size];
        Arrays.fill(safeWater, Double.POSITIVE_INFINITY);

        // Un chenal est une ligne de drainage. On retire les dépressions profondes : elles
        // relèvent du système de lac et ne doivent jamais devenir une nappe de rivière.
        for (int z = 1; z < n - 1; z++) for (int x = 1; x < n - 1; x++) {
            int i = z * n + x;
            if (h[i] <= terrain.seaLevel() - 1 || flow[i] < threshold) continue;
            if (filled[i] - h[i] > 1.60) continue;
            int d = downstream[i];
            if (d < 0) continue;
            center[i] = true;

            double x1 = ox + x * spacing;
            double z1 = oz + z * spacing;
            double x2 = ox + (d % n) * spacing;
            double z2 = oz + (d / n) * spacing;
            double sx = x2 - x1, sz = z2 - z1;
            double len = Math.max(1.0e-6, Math.hypot(sx, sz));
            double nx = -sz / len, nz = sx / len;
            double width = widthFor(flow[i]);
            double probe = Math.max(2.25, width * 0.5 + 2.1);

            double bankA = terrain.heightWithoutRivers(x1 + nx * probe, z1 + nz * probe);
            double bankB = terrain.heightWithoutRivers(x1 - nx * probe, z1 - nz * probe);
            double localBank = Math.min(bankA, bankB);
            double level = Math.min(h[i], localBank) - cfg.bankBuffer();

            // A l'embouchure, la rivière rejoint le niveau marin au lieu de former une
            // cascade suspendue au-dessus de la plage.
            if (h[i] <= terrain.seaLevel() + 5.5) level = terrain.seaLevel();
            safeWater[i] = Math.floor(level + 1.0e-6);
        }

        // Rend le profil longitudinal monotone : en allant vers l'aval le niveau ne peut
        // jamais remonter. Les chutes restent possibles, mais aucune "bosse d'eau".
        for (int i : order) {
            if (!center[i] || !Double.isFinite(safeWater[i])) continue;
            int d = downstream[i];
            if (d < 0) continue;
            if (center[d] && Double.isFinite(safeWater[d])) {
                safeWater[d] = Math.min(safeWater[d], safeWater[i]);
            }
        }

        List<Segment> segments = new ArrayList<>();
        int[] segmentFromNode = new int[size];
        Arrays.fill(segmentFromNode, -1);
        for (int i = 0; i < size; i++) {
            if (!center[i] || !Double.isFinite(safeWater[i])) continue;
            int d = downstream[i];
            if (d < 0) continue;

            double x1 = ox + (i % n) * spacing;
            double z1 = oz + (i / n) * spacing;
            double x2 = ox + (d % n) * spacing;
            double z2 = oz + (d / n) * spacing;
            double w1 = safeWater[i];
            double w2;
            if (center[d] && Double.isFinite(safeWater[d])) {
                w2 = Math.min(w1, safeWater[d]);
            } else if (h[d] <= terrain.seaLevel() + 3.0) {
                w2 = Math.min(w1, terrain.seaLevel());
            } else {
                // Segment terminal en bord de tuile : reste sous le terrain local.
                w2 = Math.min(w1, h[d] - cfg.bankBuffer());
            }
            Segment s = new Segment(x1, z1, x2, z2, h[i], h[d], flow[i], flow[d], w1, w2);
            segmentFromNode[i] = segments.size();
            segments.add(s);
        }

        // Chaque nœud de la grille mémorise le segment continu le plus proche. Lors du
        // sample final on reprojette les vraies coordonnées du bloc sur ce segment.
        int[] nearestSegment = new int[size];
        Arrays.fill(nearestSegment, -1);
        double[] nearestDistance = new double[size];
        Arrays.fill(nearestDistance, Double.POSITIVE_INFINITY);
        int search = Math.max(4, (int) Math.ceil(cfg.maxWidth() / spacing) + 4);

        for (int z = 0; z < n; z++) for (int x = 0; x < n; x++) {
            int i = z * n + x;
            double px = ox + x * spacing, pz = oz + z * spacing;
            int z0 = Math.max(0, z - search), z1 = Math.min(n - 1, z + search);
            int x0 = Math.max(0, x - search), x1 = Math.min(n - 1, x + search);
            for (int nz = z0; nz <= z1; nz++) for (int nx = x0; nx <= x1; nx++) {
                int node = nz * n + nx;
                int sid = segmentFromNode[node];
                if (sid < 0) continue;
                Segment seg = segments.get(sid);
                double dist = seg.distance(px, pz);
                if (dist < nearestDistance[i]) {
                    nearestDistance[i] = dist;
                    nearestSegment[i] = sid;
                }
            }
        }

        return new Tile(ox, oz, spacing, n, h, filled, flow, basin,
                nearestSegment, segments.toArray(Segment[]::new));
    }

    private double widthFor(double q) {
        double normalized = MathUtil.clamp(
                Math.log1p(Math.max(1.0, q)) / Math.log1p(Math.max(8.0, cfg.accumulationThreshold() * 22.0)), 0, 1);
        double width = cfg.minWidth() + (cfg.maxWidth() - cfg.minWidth()) * Math.pow(normalized, 1.22);
        return MathUtil.clamp(width, cfg.minWidth(), cfg.maxWidth());
    }

    private static int findRoot(int start, int[] downstream, int[] root) {
        if (root[start] >= 0) return root[start];
        int p = start;
        int guard = 0;
        while (downstream[p] >= 0 && downstream[p] != p && root[p] < 0 && guard++ < downstream.length) p = downstream[p];
        int r = root[p] >= 0 ? root[p] : p;
        p = start; guard = 0;
        while (p != r && root[p] < 0 && guard++ < downstream.length) {
            int next = downstream[p];
            root[p] = r;
            if (next < 0 || next == p) break;
            p = next;
        }
        root[start] = r;
        return r;
    }

    private static void seedBoundary(int x, int z, int n, double[] filled, boolean[] visited,
                                     PriorityQueue<FloodNode> flood) {
        int i = z * n + x;
        if (visited[i]) return;
        visited[i] = true;
        flood.add(new FloodNode(i, filled[i]));
    }

    public record HydroSample(double elevation, double filledElevation, double depressionDepth,
                              double accumulation, long basinId, double channelDistance,
                              double channelAccumulation, double channelElevation,
                              double channelPotential, double channelWaterSurface,
                              double channelGrade, double channelDirX, double channelDirZ,
                              double channelSignedDistance) {}

    private record TileKey(int x, int z) {}
    private record FloodNode(int index, double height) {}

    private record Segment(double x1, double z1, double x2, double z2,
                           double e1, double e2, double q1, double q2,
                           double w1, double w2) {
        Projection project(double x, double z) {
            double vx = x2 - x1, vz = z2 - z1;
            double len2 = vx * vx + vz * vz;
            double t = len2 <= 1.0e-12 ? 0.0 : ((x - x1) * vx + (z - z1) * vz) / len2;
            t = MathUtil.clamp(t, 0, 1);
            double px = x1 + vx * t, pz = z1 + vz * t;
            double dx=x-px, dz=z-pz;
            double len=Math.max(1.0e-9,Math.hypot(vx,vz));
            double signed=(vx*dz-vz*dx)/len;
            return new Projection(Math.hypot(dx,dz), t, signed);
        }
        double distance(double x, double z) { return project(x, z).distance; }
        double length() { return Math.max(1.0e-6, Math.hypot(x2 - x1, z2 - z1)); }
    }

    private record Projection(double distance, double t, double signedDistance) {}

    private record Tile(int ox, int oz, int spacing, int n, double[] h, double[] filled,
                        double[] flow, long[] basin, int[] nearestSegment, Segment[] segments) {
        HydroSample sample(double x, double z) {
            double gx = (x - ox) / spacing, gz = (z - oz) / spacing;
            int ix = MathUtil.clamp((int) Math.floor(gx), 0, n - 2);
            int iz = MathUtil.clamp((int) Math.floor(gz), 0, n - 2);
            double tx = MathUtil.clamp(gx - ix, 0, 1), tz = MathUtil.clamp(gz - iz, 0, 1);
            int a = iz*n+ix, b=a+1, c=(iz+1)*n+ix, d=c+1;
            double eh = MathUtil.bilerp(h[a], h[b], h[c], h[d], tx, tz);
            double fh = MathUtil.bilerp(filled[a], filled[b], filled[c], filled[d], tx, tz);
            double fl = MathUtil.bilerp(flow[a], flow[b], flow[c], flow[d], tx, tz);
            int nearestX = tx < 0.5 ? ix : ix + 1;
            int nearestZ = tz < 0.5 ? iz : iz + 1;
            long basinId = basin[nearestZ * n + nearestX];

            // On teste les segments associés aux quatre coins de la cellule et on reprojette
            // le bloc exact dessus. Pas d'interpolation entre deux affluents différents.
            int[] ids = { nearestSegment[a], nearestSegment[b], nearestSegment[c], nearestSegment[d] };
            int bestId = -1;
            Projection best = null;
            for (int k = 0; k < ids.length; k++) {
                int sid = ids[k];
                if (sid < 0) continue;
                boolean duplicate = false;
                for (int j = 0; j < k; j++) if (ids[j] == sid) { duplicate = true; break; }
                if (duplicate) continue;
                Projection p = segments[sid].project(x, z);
                if (best == null || p.distance < best.distance) { best = p; bestId = sid; }
            }

            if (bestId < 0) {
                return new HydroSample(eh, fh, Math.max(0.0, fh - eh), fl, basinId,
                        Double.POSITIVE_INFINITY, 0, 0, 0, Double.NEGATIVE_INFINITY, 0, 0, 0, Double.POSITIVE_INFINITY);
            }

            Segment s = segments[bestId];
            double t = best.t;
            double q = MathUtil.lerp(s.q1, s.q2, t);
            double ce = MathUtil.lerp(s.e1, s.e2, t);
            double cp = Math.pow(MathUtil.smoothstep(1.0, Math.max(8.0, Math.max(s.q1, s.q2)), q), 0.9);
            double water = MathUtil.lerp(s.w1, s.w2, t);
            double grade = Math.abs(s.w2 - s.w1) / s.length();
            double len=s.length();
            double dirX=(s.x2-s.x1)/len, dirZ=(s.z2-s.z1)/len;
            return new HydroSample(eh, fh, Math.max(0.0, fh - eh), fl, basinId,
                    best.distance, q, ce, cp, water, grade, dirX, dirZ, best.signedDistance);
        }
    }
}
