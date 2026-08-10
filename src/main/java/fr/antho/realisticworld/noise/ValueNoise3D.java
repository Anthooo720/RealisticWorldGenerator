package fr.antho.realisticworld.noise;

import fr.antho.realisticworld.util.HashUtil;
import fr.antho.realisticworld.util.MathUtil;

/** Petit bruit de valeur 3D déterministe, sans allocation, destiné aux grottes. */
public final class ValueNoise3D {
    private final long seed;
    public ValueNoise3D(long seed) { this.seed = seed; }

    public double sample(double x, double y, double z) {
        int x0=(int)Math.floor(x), y0=(int)Math.floor(y), z0=(int)Math.floor(z);
        double tx=fade(x-x0), ty=fade(y-y0), tz=fade(z-z0);
        double c000=v(x0,y0,z0), c100=v(x0+1,y0,z0), c010=v(x0,y0+1,z0), c110=v(x0+1,y0+1,z0);
        double c001=v(x0,y0,z0+1), c101=v(x0+1,y0,z0+1), c011=v(x0,y0+1,z0+1), c111=v(x0+1,y0+1,z0+1);
        double x00=MathUtil.lerp(c000,c100,tx), x10=MathUtil.lerp(c010,c110,tx);
        double x01=MathUtil.lerp(c001,c101,tx), x11=MathUtil.lerp(c011,c111,tx);
        return MathUtil.lerp(MathUtil.lerp(x00,x10,ty), MathUtil.lerp(x01,x11,ty), tz);
    }

    public double fbm(double x,double y,double z,int octaves,double lacunarity,double gain) {
        double amp=1, freq=1, sum=0, norm=0;
        for(int i=0;i<octaves;i++) { sum += sample(x*freq,y*freq,z*freq)*amp; norm+=amp; amp*=gain; freq*=lacunarity; }
        return norm==0?0:sum/norm;
    }

    private double v(int x,int y,int z) {
        long h=HashUtil.mix64(seed ^ (x*0x9E3779B97F4A7C15L) ^ (y*0xC2B2AE3D27D4EB4FL) ^ (z*0x165667B19E3779F9L));
        return HashUtil.unitDouble(h)*2.0-1.0;
    }
    private static double fade(double t){ return t*t*t*(t*(t*6-15)+10); }
}
