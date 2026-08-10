package fr.antho.realisticworld.terrain;

import fr.antho.realisticworld.config.WorldGenConfig;
import fr.antho.realisticworld.noise.SimplexNoise;
import fr.antho.realisticworld.util.MathUtil;

/**
 * Réseau de vallées hiérarchique créé avant l'hydrologie. Les lignes principales
 * forment de larges corridors ; les lignes secondaires deviennent tributaires/ravins.
 */
public final class ValleyGraph {
    private final WorldGenConfig.Terrain cfg;
    private final SimplexNoise trunkA, trunkB, tributary, glacier, warpA, warpB;

    public ValleyGraph(long seed, WorldGenConfig.Terrain cfg) {
        this.cfg = cfg;
        this.trunkA = new SimplexNoise(seed ^ 0x56414C4C455941L);
        this.trunkB = new SimplexNoise(seed ^ 0x56414C4C455942L);
        this.tributary = new SimplexNoise(seed ^ 0x54524942555445L);
        this.glacier = new SimplexNoise(seed ^ 0x474C4143494552L);
        this.warpA = new SimplexNoise(seed ^ 0x5741525056414CL);
        this.warpB = new SimplexNoise(seed ^ 0x5741525056414DL);
    }

    public ValleySample sample(double x, double z) {
        double wx = x + warpA.sample(x * 0.00020, z * 0.00020) * 260.0;
        double wz = z + warpB.sample(x * 0.00020, z * 0.00020) * 260.0;

        double a = Math.abs(trunkA.sample(wx * 0.00048, wz * 0.00048));
        double b = Math.abs(trunkB.sample(wx * 0.00036 + 53.0, wz * 0.00036 - 41.0));
        double mainA = 1.0 - MathUtil.smootherstep(0.025, 0.165, a);
        double mainB = 1.0 - MathUtil.smootherstep(0.025, 0.145, b);
        double main = MathUtil.clamp(Math.max(mainA, mainB * 0.88), 0.0, 1.0);

        double t = Math.abs(tributary.sample(wx * 0.00105 + 21.0, wz * 0.00105 - 72.0));
        double secondary = (1.0 - MathUtil.smootherstep(0.020, 0.105, t)) * (0.38 + main * 0.62);
        double glacial = MathUtil.smootherstep(0.16, 0.82,
                glacier.sample(wx * 0.00024, wz * 0.00024) * 0.5 + 0.5) * main;
        return new ValleySample(main, MathUtil.clamp(secondary, 0, 1), MathUtil.clamp(glacial, 0, 1));
    }

    public record ValleySample(double main, double tributary, double glacialPotential) {}
}
