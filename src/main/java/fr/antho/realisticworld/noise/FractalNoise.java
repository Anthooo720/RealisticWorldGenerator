package fr.antho.realisticworld.noise;

public final class FractalNoise {
    private final SimplexNoise[] octaves;

    public FractalNoise(long seed, int octaveCount) {
        int count = Math.max(1, octaveCount);
        this.octaves = new SimplexNoise[count];
        for (int i = 0; i < count; i++) {
            octaves[i] = new SimplexNoise(seed + i * 0x9E3779B97F4A7C15L);
        }
    }

    public double fbm(double x, double z, double lacunarity, double gain) {
        double sum = 0.0;
        double amp = 1.0;
        double freq = 1.0;
        double norm = 0.0;
        for (SimplexNoise octave : octaves) {
            sum += octave.sample(x * freq, z * freq) * amp;
            norm += amp;
            freq *= lacunarity;
            amp *= gain;
        }
        return norm == 0 ? 0 : sum / norm;
    }

    public double ridged(double x, double z, double lacunarity, double gain) {
        double sum = 0.0;
        double amp = 1.0;
        double freq = 1.0;
        double norm = 0.0;
        for (SimplexNoise octave : octaves) {
            double n = 1.0 - Math.abs(octave.sample(x * freq, z * freq));
            n *= n;
            sum += n * amp;
            norm += amp;
            freq *= lacunarity;
            amp *= gain;
        }
        return norm == 0 ? 0 : sum / norm;
    }
}
