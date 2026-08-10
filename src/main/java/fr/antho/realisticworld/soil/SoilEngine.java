package fr.antho.realisticworld.soil;

import fr.antho.realisticworld.climate.ClimateEngine;
import fr.antho.realisticworld.geology.GeologyMap;
import fr.antho.realisticworld.hydrology.LakeEngine;
import fr.antho.realisticworld.hydrology.RiverEngine;
import fr.antho.realisticworld.landscape.LandscapeRegionSystem;
import fr.antho.realisticworld.terrain.TerrainEngine;
import fr.antho.realisticworld.util.MathUtil;
import fr.antho.realisticworld.util.HashUtil;
import org.bukkit.Material;

/** Sols dérivés de la roche mère, pente, humidité, altitude et dépôts alluviaux. */
public final class SoilEngine {
    private final TerrainEngine terrain;
    private final GeologyMap geology;
    private final LandscapeRegionSystem landscape;

    public SoilEngine(TerrainEngine terrain, GeologyMap geology, LandscapeRegionSystem landscape) {
        this.terrain=terrain; this.geology=geology; this.landscape=landscape;
    }

    public SoilProfile sample(int x,int z,double elevation,double slope, ClimateEngine.ClimateSample c,
                              RiverEngine.RiverSample river, LakeEngine.LakeSample lake) {
        int sea=terrain.seaLevel();
        double altitude=elevation-sea;
        double mountain=terrain.mountainInfluence(x,z), valley=terrain.valleyInfluence(x,z);
        double rockiness=terrain.alpineRockiness(x,z), gully=terrain.alpineGully(x,z);
        GeologyMap.GeologySample geo=geology.sample(x,z);
        LandscapeRegionSystem.LandscapeType region=landscape.classify(x,z,elevation,sea,c.continentalness(),mountain,valley);

        if(lake.isLake()) return new SoilProfile(c.humidity()>0.66?Material.CLAY:Material.GRAVEL,Material.GRAVEL,geo.deepRock(),3,false);
        if(river.isRiver() && elevation>sea+1) {
            double r=HashUtil.unitDouble(HashUtil.hash(0x5249564552424544L,x,z,river.basinId()));
            Material top;
            if(mountain>0.48||slope>0.34) top=r<0.62?Material.GRAVEL:geo.surfaceRock();
            else if(river.estuary() && r<0.22) top=Material.SAND;
            else if(c.humidity()>0.74 && r<0.20) top=Material.CLAY;
            else top=r<0.72?Material.GRAVEL:Material.COARSE_DIRT;
            Material sub=(c.humidity()>0.68 && r<0.35)?Material.CLAY:(r<0.78?Material.GRAVEL:geo.deepRock());
            return new SoilProfile(top,sub,geo.deepRock(),3,false);
        }
        if(river.isBank() && elevation>sea+1) {
            double r=HashUtil.unitDouble(HashUtil.hash(0x524956455242414EL,x,z,river.basinId()));
            // Les berges sont d'abord du terrain végétalisé : gravier/sable/boue ne forment
            // que des plages discontinues et non un ruban géométrique autour du chenal.
            Material bankTop;
            if(river.estuary() && r<0.24) bankTop=Material.SAND;
            else if(c.humidity()>0.80 && r<0.10) bankTop=Material.MUD;
            else if(r<0.22) bankTop=Material.GRAVEL;
            else if(r<0.30) bankTop=Material.COARSE_DIRT;
            else bankTop=Material.GRASS_BLOCK;
            return new SoilProfile(bankTop,c.humidity()>0.72&&r<0.38?Material.CLAY:Material.DIRT,geo.deepRock(),4,false);
        }
        if(river.isFloodplain() && elevation>sea+1) {
            double r=HashUtil.unitDouble(HashUtil.hash(0x414C4C555649554DL,x,z,river.basinId()));
            Material top=(c.humidity()>0.84&&r<0.10)?Material.MUD:Material.GRASS_BLOCK;
            return new SoilProfile(top,c.humidity()>0.78&&r<0.42?Material.CLAY:Material.DIRT,geo.deepRock(),5,false);
        }
        if(elevation<sea-2) return new SoilProfile(Material.GRAVEL,Material.SAND,geo.deepRock(),3,false);
        if(elevation<=sea+3) {
            if(slope>0.50 || region==LandscapeRegionSystem.LandscapeType.COASTAL_CLIFFS)
                return new SoilProfile(geo.surfaceRock(),geo.deepRock(),geo.deepRock(),2,false);
            return new SoilProfile(Material.SAND,Material.SAND,Material.SANDSTONE,5,c.temperature()<0.20);
        }
        if(region==LandscapeRegionSystem.LandscapeType.CANYONLANDS && c.humidity()<0.48)
            return slope>0.38?new SoilProfile(Material.TERRACOTTA,Material.RED_SAND,Material.SANDSTONE,3,false)
                    :new SoilProfile(Material.RED_SAND,Material.TERRACOTTA,Material.SANDSTONE,4,false);
        if(region==LandscapeRegionSystem.LandscapeType.PLATEAU) {
            double r=HashUtil.unitDouble(HashUtil.hash(0x504C41544541554CL,x,z,(long)Math.floor(elevation)));
            if(slope>0.34 || r<0.16) return new SoilProfile(r<0.52?geo.surfaceRock():Material.GRAVEL,geo.deepRock(),geo.deepRock(),2,false);
            if(r<0.30) return new SoilProfile(Material.COARSE_DIRT,Material.DIRT,geo.deepRock(),3,false);
        }
        if(region==LandscapeRegionSystem.LandscapeType.HIGHLANDS && slope>0.18) {
            double r=HashUtil.unitDouble(HashUtil.hash(0x484947484C414E44L,x,z,(long)Math.floor(elevation)));
            if(r<0.07) return new SoilProfile(Material.GRAVEL,geo.surfaceRock(),geo.deepRock(),2,false);
            if(r<0.15) return new SoilProfile(Material.COARSE_DIRT,Material.DIRT,geo.deepRock(),3,false);
        }
        if(region==LandscapeRegionSystem.LandscapeType.WETLAND_BASIN && slope<0.22)
            return new SoilProfile(c.humidity()>0.74?Material.MOSS_BLOCK:Material.GRASS_BLOCK,Material.CLAY,Material.STONE,5,false);

        double altitudeSnow=MathUtil.smootherstep(68,124,altitude);
        double coldSnow=1.0-MathUtil.smoothstep(0.14,0.36,c.temperature());
        double snow=MathUtil.clamp(altitudeSnow*(0.42+0.78*coldSnow),0,1);
        if(altitude>46 && mountain>0.24) {
            double exposure=slope*0.82+mountain*0.20+rockiness*0.27+gully*0.30;
            if(gully>0.56 && (slope>0.24||altitude>80))
                return snow>0.76&&slope<0.40?new SoilProfile(Material.SNOW_BLOCK,Material.GRAVEL,geo.deepRock(),2,false)
                        :new SoilProfile(Material.GRAVEL,geo.surfaceRock(),geo.deepRock(),2,false);
            if(exposure>0.74||slope>0.58)
                return snow>0.80&&slope<0.50?new SoilProfile(Material.SNOW_BLOCK,geo.surfaceRock(),geo.deepRock(),2,false)
                        :new SoilProfile(geo.surfaceRock(),geo.deepRock(),geo.deepRock(),1,false);
            if(snow>0.64&&slope<0.43) return new SoilProfile(Material.SNOW_BLOCK,Material.DIRT,geo.deepRock(),3,false);
            if(altitude>72&&rockiness>0.66&&slope>0.30)
                return new SoilProfile(Material.COARSE_DIRT,Material.GRAVEL,geo.deepRock(),2,snow>0.44);
        }

        // Profondeur de sol : épaisse en fond de vallée humide, mince sur roche/pentes.
        double soil=2.0 + geo.soilDepth()*3.5 + valley*1.4 + c.humidity()*1.1 - slope*4.1 - mountain*0.7;
        int depth=MathUtil.clamp((int)Math.round(soil),1,6);
        if(altitude>26&&slope>0.46&&rockiness>0.58)
            return new SoilProfile(geo.surfaceRock(),geo.deepRock(),geo.deepRock(),1,false);
        if(c.temperature()<0.17) return new SoilProfile(Material.SNOW_BLOCK,Material.DIRT,geo.deepRock(),depth,false);
        if(c.temperature()>0.68&&c.humidity()<0.22) return new SoilProfile(Material.SAND,Material.SAND,Material.SANDSTONE,depth,false);
        if(c.temperature()>0.54&&c.humidity()<0.32&&elevation>sea+12)
            return new SoilProfile(Material.RED_SAND,Material.TERRACOTTA,Material.TERRACOTTA,depth,false);
        Material top=c.humidity()>0.78&&slope<0.18?Material.MOSS_BLOCK:Material.GRASS_BLOCK;
        Material sub=c.humidity()>0.82&&valley>0.48?Material.CLAY:Material.DIRT;
        return new SoilProfile(top,sub,geo.deepRock(),depth,c.temperature()<0.27&&altitude>40);
    }

    public record SoilProfile(Material top, Material sub, Material deep, int depth, boolean snowCap) {}
}
