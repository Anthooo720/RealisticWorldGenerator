package fr.antho.realisticworld.biome;

import fr.antho.realisticworld.climate.ClimateEngine;
import fr.antho.realisticworld.gen.GenerationContext;
import fr.antho.realisticworld.geology.GeologyMap;
import fr.antho.realisticworld.landscape.LandscapeRegionSystem;
import fr.antho.realisticworld.noise.SimplexNoise;
import fr.antho.realisticworld.util.MathUtil;
import org.bukkit.block.Biome;

/** Classification 3D des biomes vanilla avec écotones et variantes régionales RWG. */
public final class BiomeEngine {
    private final GenerationContext ctx;
    private final SimplexNoise opennessNoise;
    private final SimplexNoise cherryNoise;
    private final SimplexNoise woodlandNoise;
    private final SimplexNoise rareNoise;
    private final SimplexNoise transitionNoise;
    private final SimplexNoise transitionDirection;
    private final SimplexNoise caveNoise;

    public BiomeEngine(GenerationContext ctx) {
        this.ctx=ctx;
        long seed=ctx.seed;
        this.opennessNoise=new SimplexNoise(seed ^ 0x42494F4D454F504EL);
        this.cherryNoise=new SimplexNoise(seed ^ 0x4348455252594752L);
        this.woodlandNoise=new SimplexNoise(seed ^ 0x574F4F444C414E44L);
        this.rareNoise=new SimplexNoise(seed ^ 0x5241524542494F4DL);
        this.transitionNoise=new SimplexNoise(seed ^ 0x45434F544F4E4531L);
        this.transitionDirection=new SimplexNoise(seed ^ 0x45434F4449524543L);
        this.caveNoise=new SimplexNoise(seed ^ 0x4341564542494F4DL);
    }

    public Biome getBiome(int x,int z){ return blendedSurfaceBiome(x,z); }

    public Biome getBiome(int x,int y,int z) {
        double surface=ctx.terrain.baseHeightRaw(x,z);
        if(y<surface-14) {
            Biome underground=undergroundBiome(x,y,z,surface);
            if(underground!=null) return underground;
        }
        return blendedSurfaceBiome(x,z);
    }

    private Biome blendedSurfaceBiome(int x,int z) {
        Biome primary=classifySurface(x,z);
        int radius=MathUtil.clamp(ctx.config.biomes().transitionRadius(),0,32);
        if(radius<=0||!blendable(primary)) return primary;
        double dir=transitionDirection.sample(x*0.0011,z*0.0011);
        int dx,dz;
        if(dir<-0.50){dx=-radius;dz=0;}
        else if(dir<0.0){dx=0;dz=-radius;}
        else if(dir<0.50){dx=radius;dz=0;}
        else {dx=0;dz=radius;}
        Biome neighbour=classifySurface(x+dx,z+dz);
        if(primary==neighbour||!compatibleTransition(primary,neighbour)) return primary;
        double scale=MathUtil.clamp(ctx.config.biomes().transitionPatchScale(),0.012,0.12);
        double patch=transitionNoise.sample(x*scale,z*scale);
        return patch>0.18?neighbour:primary;
    }

    private Biome classifySurface(int x,int z) {
        double elevation=ctx.terrain.baseHeightRaw(x,z);
        ClimateEngine.ClimateSample c=ctx.climate.sampleFast(x,z,elevation);
        int sea=ctx.terrain.seaLevel();
        double rare=rareField(x,z);

        if(elevation<sea-3) {
            boolean deep=elevation<sea-24;
            double t=c.temperature();
            if(t<0.18) return deep?Biome.DEEP_FROZEN_OCEAN:Biome.FROZEN_OCEAN;
            if(t<0.34) return deep?Biome.DEEP_COLD_OCEAN:Biome.COLD_OCEAN;
            if(t>0.82&&!deep) return Biome.WARM_OCEAN;
            if(t>0.62) return deep?Biome.DEEP_LUKEWARM_OCEAN:Biome.LUKEWARM_OCEAN;
            return deep?Biome.DEEP_OCEAN:Biome.OCEAN;
        }

        double altitude=elevation-sea;
        double mountain=ctx.terrain.mountainInfluence(x,z);
        double valley=ctx.terrain.valleyInfluence(x,z);

        if(altitude>-2&&altitude<12&&c.continentalness()<0.02&&c.continentalness()>-0.24
                &&rare>0.965) return Biome.MUSHROOM_FIELDS;

        if(altitude>2&&altitude<42&&valley>0.84&&mountain<0.56)
            return c.temperature()<0.24?Biome.FROZEN_RIVER:Biome.RIVER;

        if(elevation<=sea+3) {
            if(mountain>0.62) return Biome.STONY_SHORE;
            return c.temperature()<0.26?Biome.SNOWY_BEACH:Biome.BEACH;
        }

        LandscapeRegionSystem.LandscapeType region=ctx.landscape.classify(x,z,elevation,sea,
                c.continentalness(),mountain,valley);

        if(altitude>105) {
            if(c.temperature()<0.25) return Biome.FROZEN_PEAKS;
            if(mountain>0.72) return Biome.JAGGED_PEAKS;
            return Biome.STONY_PEAKS;
        }
        if(altitude>62) {
            if(c.temperature()<0.27&&mountain<0.70) return Biome.GROVE;
            if(c.temperature()<0.31) return Biome.SNOWY_SLOPES;
            if(c.humidity()>0.44&&mountain<0.72) return Biome.MEADOW;
            return Biome.STONY_PEAKS;
        }

        double slope=macroSlope(x,z,elevation);
        double openness=openness(x,z);
        var biomeCfg=ctx.config.biomes();
        double openScore=openness+biomeCfg.openRegionBias()*(1.0-MathUtil.clamp(mountain*2.2,0,1));
        boolean openFlat=slope<biomeCfg.openFlatMaxSlope()&&mountain<0.27
                &&openScore>biomeCfg.openFlatMinOpenness();

        double cherry=cherryNoise.sample(x*0.00058+17.0,z*0.00058-43.0)*0.5+0.5;
        if(altitude>24&&altitude<76&&slope<0.24&&mountain>0.08&&mountain<0.55
                &&c.temperature()>0.40&&c.temperature()<0.64
                &&c.humidity()>0.54&&c.humidity()<0.86&&cherry>0.79) return Biome.CHERRY_GROVE;

        if(c.temperature()<0.16&&openFlat&&c.humidity()<0.56&&rare>0.86) return Biome.ICE_SPIKES;

        if(region==LandscapeRegionSystem.LandscapeType.WETLAND_BASIN&&c.humidity()>0.62) {
            if(c.temperature()>0.66&&c.humidity()>0.78&&rare>0.56) return Biome.MANGROVE_SWAMP;
            return Biome.SWAMP;
        }
        if(region==LandscapeRegionSystem.LandscapeType.CANYONLANDS&&c.humidity()<0.40) {
            if(c.temperature()>0.68&&rare>0.68) return Biome.ERODED_BADLANDS;
            return c.temperature()>0.62?Biome.BADLANDS:Biome.WOODED_BADLANDS;
        }
        if(region==LandscapeRegionSystem.LandscapeType.PLATEAU) {
            if(c.temperature()>0.62&&c.humidity()<0.52) return Biome.SAVANNA_PLATEAU;
            if(slope>0.20&&c.humidity()<0.42) return Biome.WINDSWEPT_GRAVELLY_HILLS;
        }
        if(region==LandscapeRegionSystem.LandscapeType.HIGHLANDS&&slope>0.19) {
            if(c.temperature()>0.66&&c.humidity()<0.50) return Biome.WINDSWEPT_SAVANNA;
            if(c.humidity()>0.58) return Biome.WINDSWEPT_FOREST;
            return Biome.WINDSWEPT_HILLS;
        }

        if(altitude>26&&mountain>0.18) {
            // TAIGA accepte les villages vanilla : sur versant non constructible on choisit
            // une variante old-growth qui conserve le climat sans autoriser leur placement.
            if(c.temperature()<0.58&&c.humidity()>0.34)
                return openFlat?Biome.TAIGA:Biome.OLD_GROWTH_SPRUCE_TAIGA;
            if(c.temperature()<0.70&&c.humidity()>0.56) return Biome.FOREST;
            if(valley>0.42&&c.humidity()>0.44) return Biome.MEADOW;
        }

        Biome base=whittaker(c.temperature(),c.humidity(),openFlat,slope,openScore);
        return rareVariant(base,c,openFlat,slope,rare);
    }

    private Biome undergroundBiome(int x,int y,int z,double surface) {
        ClimateEngine.ClimateSample c=ctx.climate.sampleFast(x,z,surface);
        GeologyMap.GeologySample geo=ctx.geology.sample(x,z);
        double n=caveNoise.sample(x*0.0031+y*0.0007,z*0.0031-y*0.0005)*0.5+0.5;
        double rare=MathUtil.clamp(ctx.config.biomes().rareBiomeFrequency(),0.02,0.48);

        if(y<-30&&ctx.terrain.mountainInfluence(x,z)>0.32&&n>0.90-rare*0.18) return Biome.DEEP_DARK;
        if(geo.type()==GeologyMap.RockType.VOLCANIC&&y<42&&y>-54&&n>0.80-rare*0.16) return Biome.SULFUR_CAVES;
        if(geo.type()==GeologyMap.RockType.LIMESTONE&&y<54&&n>0.66-rare*0.12) return Biome.DRIPSTONE_CAVES;

        double azalea=MathUtil.clamp(ctx.config.vegetation().azaleaFrequency(),0.0,0.05);
        double lushGate=MathUtil.clamp(0.92-rare*0.04-azalea*1.5,0.80,0.95);
        if(c.humidity()>0.70&&c.temperature()>0.24&&y<48&&y>-32&&n>lushGate) return Biome.LUSH_CAVES;
        return null;
    }

    private Biome rareVariant(Biome base,ClimateEngine.ClimateSample c,boolean openFlat,double slope,double rare) {
        double frequency=MathUtil.clamp(ctx.config.biomes().rareBiomeFrequency(),0.02,0.48);
        double gate=1.0-frequency;
        if(rare<gate) return base;
        if(base==Biome.PLAINS) return c.humidity()>0.62?Biome.SUNFLOWER_PLAINS:Biome.PLAINS;
        if(base==Biome.FOREST) {
            if(c.humidity()>0.84&&rare>0.94) return Biome.PALE_GARDEN;
            if(c.humidity()>0.70&&slope<0.16) return Biome.FLOWER_FOREST;
            return Biome.OLD_GROWTH_BIRCH_FOREST;
        }
        if(base==Biome.BIRCH_FOREST&&c.humidity()>0.58) return Biome.OLD_GROWTH_BIRCH_FOREST;
        if(base==Biome.TAIGA) return c.humidity()>0.66?Biome.OLD_GROWTH_SPRUCE_TAIGA:Biome.OLD_GROWTH_PINE_TAIGA;
        if(base==Biome.JUNGLE&&c.humidity()>0.84) return Biome.BAMBOO_JUNGLE;
        if(base==Biome.SAVANNA&&!openFlat) return Biome.SAVANNA_PLATEAU;
        return base;
    }

    public boolean isVillageOpenBiome(Biome biome) {
        return biome==Biome.PLAINS||biome==Biome.SAVANNA||biome==Biome.DESERT
                ||biome==Biome.SNOWY_PLAINS||biome==Biome.TAIGA;
    }

    public double openness(int x,int z) {
        double a=opennessNoise.sample(x*0.00074,z*0.00074)*0.5+0.5;
        double b=woodlandNoise.sample(x*0.00155+61,z*0.00155-29)*0.5+0.5;
        return MathUtil.clamp(a*0.72+(1.0-b)*0.28,0,1);
    }

    private double rareField(int x,int z) {
        double base=rareNoise.sample(x*0.00072+211,z*0.00072-157)*0.5+0.5;
        double detail=rareNoise.sample(x*0.0019-37,z*0.0019+71)*0.5+0.5;
        return MathUtil.clamp(base*0.76+detail*0.24,0,1);
    }

    private Biome whittaker(double t,double h,boolean openFlat,double slope,double openness) {
        var cfg=ctx.config.biomes();
        double forestH=MathUtil.clamp(cfg.temperateForestHumidity(),0.48,0.88);
        double darkH=Math.max(forestH+0.08,MathUtil.clamp(cfg.darkForestHumidity(),0.64,0.96));
        if(t<0.17) {
            if(openFlat&&h<0.68) return Biome.SNOWY_PLAINS;
            return Biome.SNOWY_TAIGA;
        }
        if(t<0.34) {
            if(openFlat&&openness>0.52&&h<0.72) return Biome.PLAINS;
            if(h>0.76) return Biome.SNOWY_TAIGA;
            if(h>0.52&&openness<0.62) return Biome.FOREST;
            return openFlat?Biome.PLAINS:Biome.BIRCH_FOREST;
        }
        if(t<0.62) {
            if(h>darkH&&!openFlat) return Biome.DARK_FOREST;
            if(h>forestH&&(!openFlat||openness<0.48)) return Biome.FOREST;
            if(h>0.30) {
                // PLAINS porte des villages : l'ouverture visuelle seule ne suffit plus,
                // il faut également satisfaire la pente macro de openFlat.
                if(openFlat) return Biome.PLAINS;
                return openness<0.34?Biome.FOREST:Biome.BIRCH_FOREST;
            }
            if(openFlat) return Biome.PLAINS;
            return slope>0.18?Biome.WOODED_BADLANDS:Biome.BIRCH_FOREST;
        }
        if(t<0.82) {
            if(h>0.86&&!openFlat) return Biome.JUNGLE;
            if(h>0.70&&openness<0.56) return Biome.SPARSE_JUNGLE;
            if(h>0.34) return openFlat?Biome.SAVANNA:Biome.SPARSE_JUNGLE;
            if(h<0.19) return openFlat?Biome.DESERT:Biome.BADLANDS;
            return openFlat?Biome.SAVANNA:Biome.BADLANDS;
        }
        if(h<0.25) return openFlat?Biome.DESERT:Biome.BADLANDS;
        if(h<0.56) return openFlat?Biome.SAVANNA:Biome.BADLANDS;
        if(h>0.88&&openness<0.48) return Biome.JUNGLE;
        return Biome.SPARSE_JUNGLE;
    }

    private static boolean blendable(Biome b) {
        return b!=Biome.RIVER&&b!=Biome.FROZEN_RIVER&&b!=Biome.BEACH&&b!=Biome.SNOWY_BEACH
                &&b!=Biome.STONY_SHORE&&!isOcean(b)&&b!=Biome.MUSHROOM_FIELDS;
    }

    private static boolean compatibleTransition(Biome a,Biome b){ return family(a)==family(b); }

    private static int family(Biome b) {
        if(isOcean(b)) return 0;
        if(b==Biome.DESERT||b==Biome.BADLANDS||b==Biome.ERODED_BADLANDS||b==Biome.WOODED_BADLANDS) return 1;
        if(b==Biome.SAVANNA||b==Biome.SAVANNA_PLATEAU||b==Biome.WINDSWEPT_SAVANNA) return 2;
        if(b==Biome.JUNGLE||b==Biome.SPARSE_JUNGLE||b==Biome.BAMBOO_JUNGLE) return 3;
        if(b==Biome.SNOWY_PLAINS||b==Biome.SNOWY_TAIGA||b==Biome.ICE_SPIKES||b==Biome.GROVE) return 4;
        if(b==Biome.FROZEN_PEAKS||b==Biome.JAGGED_PEAKS||b==Biome.STONY_PEAKS||b==Biome.SNOWY_SLOPES
                ||b==Biome.WINDSWEPT_HILLS||b==Biome.WINDSWEPT_GRAVELLY_HILLS) return 5;
        if(b==Biome.SWAMP||b==Biome.MANGROVE_SWAMP) return 6;
        if(b==Biome.PLAINS||b==Biome.SUNFLOWER_PLAINS||b==Biome.MEADOW) return 7;
        return 8;
    }

    private static boolean isOcean(Biome b) {
        return b==Biome.OCEAN||b==Biome.DEEP_OCEAN||b==Biome.COLD_OCEAN||b==Biome.DEEP_COLD_OCEAN
                ||b==Biome.FROZEN_OCEAN||b==Biome.DEEP_FROZEN_OCEAN||b==Biome.LUKEWARM_OCEAN
                ||b==Biome.DEEP_LUKEWARM_OCEAN||b==Biome.WARM_OCEAN;
    }

    private double macroSlope(int x,int z,double center) {
        final int d=12;
        double dx=Math.max(Math.abs(ctx.terrain.baseHeightRaw(x+d,z)-center),
                Math.abs(ctx.terrain.baseHeightRaw(x-d,z)-center))/d;
        double dz=Math.max(Math.abs(ctx.terrain.baseHeightRaw(x,z+d)-center),
                Math.abs(ctx.terrain.baseHeightRaw(x,z-d)-center))/d;
        return Math.max(dx,dz);
    }
}
