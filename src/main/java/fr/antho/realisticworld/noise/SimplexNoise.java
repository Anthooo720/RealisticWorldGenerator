package fr.antho.realisticworld.noise;

import fr.antho.realisticworld.util.HashUtil;

/** Simplex 2D compact et déterministe, sans dépendance externe. */
public final class SimplexNoise {
    private static final double F2 = 0.5 * (Math.sqrt(3.0) - 1.0);
    private static final double G2 = (3.0 - Math.sqrt(3.0)) / 6.0;
    private static final double[][] GRAD = {
            {1,1},{-1,1},{1,-1},{-1,-1}, {1,0},{-1,0},{0,1},{0,-1}
    };

    private final long seed;

    public SimplexNoise(long seed) {
        this.seed = seed;
    }

    public double sample(double xin, double yin) {
        double s = (xin + yin) * F2;
        int i = fastFloor(xin + s);
        int j = fastFloor(yin + s);
        double t = (i + j) * G2;
        double x0 = xin - (i - t);
        double y0 = yin - (j - t);

        int i1, j1;
        if (x0 > y0) { i1 = 1; j1 = 0; }
        else { i1 = 0; j1 = 1; }

        double x1 = x0 - i1 + G2;
        double y1 = y0 - j1 + G2;
        double x2 = x0 - 1.0 + 2.0 * G2;
        double y2 = y0 - 1.0 + 2.0 * G2;

        double n0 = contribution(i, j, x0, y0);
        double n1 = contribution(i + i1, j + j1, x1, y1);
        double n2 = contribution(i + 1, j + 1, x2, y2);
        return 70.0 * (n0 + n1 + n2);
    }

    private double contribution(int i, int j, double x, double y) {
        double t = 0.5 - x*x - y*y;
        if (t < 0) return 0.0;
        long h = HashUtil.hash(seed, i, j, 0x51f15e5dL);
        double[] g = GRAD[(int) (h & 7L)];
        t *= t;
        return t * t * (g[0] * x + g[1] * y);
    }

    private static int fastFloor(double x) {
        int xi = (int) x;
        return x < xi ? xi - 1 : xi;
    }
}
