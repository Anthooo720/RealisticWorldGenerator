package fr.antho.realisticworld.util;

public final class MathUtil {
    private MathUtil() {}

    public static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    public static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    public static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    public static double smoothstep(double edge0, double edge1, double x) {
        if (edge0 == edge1) return x < edge0 ? 0.0 : 1.0;
        double t = clamp((x - edge0) / (edge1 - edge0), 0.0, 1.0);
        return t * t * (3.0 - 2.0 * t);
    }

    public static double smootherstep(double edge0, double edge1, double x) {
        double t = clamp((x - edge0) / (edge1 - edge0), 0.0, 1.0);
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    public static double fract(double x) {
        return x - Math.floor(x);
    }

    public static double bilerp(double a, double b, double c, double d, double tx, double tz) {
        return lerp(lerp(a, b, tx), lerp(c, d, tx), tz);
    }
}
