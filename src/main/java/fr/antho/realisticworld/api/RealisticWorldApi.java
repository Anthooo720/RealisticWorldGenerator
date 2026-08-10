package fr.antho.realisticworld.api;

import fr.antho.realisticworld.climate.ClimateEngine;
import fr.antho.realisticworld.gen.ContextRegistry;
import fr.antho.realisticworld.gen.GenerationContext;
import fr.antho.realisticworld.geology.GeologyMap;
import fr.antho.realisticworld.hydrology.LakeEngine;
import fr.antho.realisticworld.hydrology.RiverEngine;
import fr.antho.realisticworld.landscape.LandscapeRegionSystem;
import org.bukkit.generator.WorldInfo;

/**
 * API lecture seule prévue pour les futurs addons (structures, routes, villes...).
 * Elle n'altère jamais le terrain et partage exactement les mêmes samplers que le générateur.
 */
public final class RealisticWorldApi {
    private final ContextRegistry contexts;
    public RealisticWorldApi(ContextRegistry contexts){ this.contexts=contexts; }

    public NaturalSample sample(WorldInfo world,int x,int z) {
        GenerationContext c=contexts.forWorld(world);
        double elevation=c.terrain.heightWithoutRivers(x,z);
        RiverEngine.RiverSample river=c.rivers.sample(x,z);
        LakeEngine.LakeSample lake=c.lakes.sample(x,z);
        double surface=elevation-river.carveDepth()-lake.carveDepth();
        double slope=c.terrain.slope(x,z);
        ClimateEngine.ClimateSample climate=c.climate.sample(x,z,elevation);
        GeologyMap.GeologySample geology=c.geology.sample(x,z);
        double mountain=c.terrain.mountainInfluence(x,z), valley=c.terrain.valleyInfluence(x,z);
        LandscapeRegionSystem.LandscapeType landscape=c.landscape.classify(x,z,elevation,c.terrain.seaLevel(),
                climate.continentalness(),mountain,valley);
        return new NaturalSample(x,z,surface,slope,mountain,valley,climate,geology,landscape,river,lake);
    }

    public record NaturalSample(int x,int z,double surfaceHeight,double slope,double mountainInfluence,
                                double valleyInfluence, ClimateEngine.ClimateSample climate,
                                GeologyMap.GeologySample geology, LandscapeRegionSystem.LandscapeType landscape,
                                RiverEngine.RiverSample river, LakeEngine.LakeSample lake) {}
}
