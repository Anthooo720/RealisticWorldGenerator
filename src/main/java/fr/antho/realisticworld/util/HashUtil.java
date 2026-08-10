package fr.antho.realisticworld.util;

public final class HashUtil {
    private HashUtil() {}

    public static long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }

    public static long hash(long seed, long x, long z, long salt) {
        long h = seed ^ salt;
        h = mix64(h ^ (x * 0x9E3779B97F4A7C15L));
        h = mix64(h ^ (z * 0xC2B2AE3D27D4EB4FL));
        return h;
    }

    public static double unitDouble(long h) {
        return ((h >>> 11) * 0x1.0p-53);
    }

    public static double signedDouble(long h) {
        return unitDouble(h) * 2.0 - 1.0;
    }

    public static int range(long h, int minInclusive, int maxExclusive) {
        int span = Math.max(1, maxExclusive - minInclusive);
        return minInclusive + (int) Math.floorMod(h, span);
    }
}
