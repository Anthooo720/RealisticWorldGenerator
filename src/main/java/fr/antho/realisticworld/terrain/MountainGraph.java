package fr.antho.realisticworld.terrain;

import fr.antho.realisticworld.config.WorldGenConfig;
import fr.antho.realisticworld.noise.SimplexNoise;
import fr.antho.realisticworld.util.HashUtil;
import fr.antho.realisticworld.util.MathUtil;

/**
 * Graphe tectonique implicite construit à partir de frontières de plaques Voronoï.
 * Contrairement à un simple bruit ridged, les limites forment de longues chaînes
 * continues avec embranchements aux jonctions de plaques.
 */
public final class MountainGraph {
    private final long seed;
    private final WorldGenConfig.Terrain cfg;
    private final SimplexNoise warpX;
    private final SimplexNoise warpZ;
    private final SimplexNoise provinceNoise;

    public MountainGraph(long seed, WorldGenConfig.Terrain cfg) {
        this.seed = seed;
        this.cfg = cfg;
        this.warpX = new SimplexNoise(seed ^ 0x4D4F554E5441494EL);
        this.warpZ = new SimplexNoise(seed ^ 0x4752415048574152L);
        this.provinceNoise = new SimplexNoise(seed ^ 0x504C4154455301L);
    }

    public MountainSample sample(double x, double z) {
        int cell = Math.max(3200, cfg.tectonicCellSize());
        double warp = cell * 0.12;
        double wx = x + warpX.sample(x / (cell * 1.35), z / (cell * 1.35)) * warp;
        double wz = z + warpZ.sample(x / (cell * 1.35), z / (cell * 1.35)) * warp;

        int cx = Math.floorDiv((int) Math.floor(wx), cell);
        int cz = Math.floorDiv((int) Math.floor(wz), cell);
        PlateSite first = null, second = null, third = null;
        for (int dz = -2; dz <= 2; dz++) {
            for (int dx = -2; dx <= 2; dx++) {
                PlateSite s = site(cx + dx, cz + dz, cell);
                double ddx = wx - s.x;
                double ddz = wz - s.z;
                double d = Math.hypot(ddx, ddz);
                s = s.withDistance(d);
                if (first == null || d < first.distance) {
                    third = second; second = first; first = s;
                } else if (second == null || d < second.distance) {
                    third = second; second = s;
                } else if (third == null || d < third.distance) {
                    third = s;
                }
            }
        }
        if (first == null || second == null) return MountainSample.NONE;

        double boundaryDistance = Math.max(0.0, (second.distance - first.distance) * 0.5);
        double proximity = 1.0 - MathUtil.smootherstep(0.0, Math.max(400.0, cfg.tectonicBoundaryWidth()), boundaryDistance);

        double nx = second.x - first.x;
        double nz = second.z - first.z;
        double len = Math.max(1.0, Math.hypot(nx, nz));
        nx /= len; nz /= len;
        double relativeToward = (first.vx - second.vx) * nx + (first.vz - second.vz) * nz;
        double convergence = MathUtil.smootherstep(-0.45, 1.15, relativeToward);
        double province = provinceNoise.sample(x / 11000.0, z / 11000.0) * 0.5 + 0.5;
        double activity = MathUtil.clamp((first.activity + second.activity) * 0.5, 0.0, 1.0);
        double strength = proximity * (0.18 + 0.82 * convergence) * (0.55 + 0.45 * activity) * (0.68 + province * 0.32);

        double junction = 0.0;
        if (third != null) {
            double d23 = Math.abs(third.distance - second.distance);
            junction = 1.0 - MathUtil.smootherstep(0.0, cfg.tectonicBoundaryWidth() * 0.90, d23);
        }
        double core = Math.pow(MathUtil.clamp(strength, 0.0, 1.0), 1.12);
        return new MountainSample(MathUtil.clamp(strength, 0.0, 1.0), core,
                boundaryDistance, convergence, junction, -nz, nx);
    }

    private PlateSite site(int cx, int cz, int cell) {
        long h = HashUtil.hash(seed, cx, cz, 0x504C415445L);
        double jx = 0.12 + HashUtil.unitDouble(h) * 0.76;
        double jz = 0.12 + HashUtil.unitDouble(HashUtil.mix64(h ^ 0x91L)) * 0.76;
        double angle = HashUtil.unitDouble(HashUtil.mix64(h ^ 0xA51L)) * Math.PI * 2.0;
        double speed = 0.35 + HashUtil.unitDouble(HashUtil.mix64(h ^ 0xBB1L)) * 0.85;
        double activity = 0.28 + HashUtil.unitDouble(HashUtil.mix64(h ^ 0xCC2L)) * 0.72;
        return new PlateSite((cx + jx) * cell, (cz + jz) * cell,
                Math.cos(angle) * speed, Math.sin(angle) * speed, activity, 0.0);
    }

    public record MountainSample(double strength, double core, double boundaryDistance,
                                 double convergence, double junction, double tangentX, double tangentZ) {
        public static final MountainSample NONE = new MountainSample(0,0,Double.POSITIVE_INFINITY,0,0,1,0);
    }

    private record PlateSite(double x, double z, double vx, double vz, double activity, double distance) {
        PlateSite withDistance(double d) { return new PlateSite(x, z, vx, vz, activity, d); }
    }
}
