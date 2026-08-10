package fr.antho.realisticworld.vegetation.community;

import fr.antho.realisticworld.util.MathUtil;

/** Classification écologique continue utilisée pour choisir peuplement, sous-bois et densité. */
public final class VegetationCommunitySystem {
    private VegetationCommunitySystem() {}

    public static CommunitySample classify(double temperature, double humidity, double altitude,
                                           double slope, double mountain, double waterDistance,
                                           double forestDensity, double oldGrowth) {
        Community type;
        if (waterDistance < 8 && humidity > 0.52 && altitude < 34) type = Community.RIPARIAN;
        else if (altitude > 94 || (mountain > 0.55 && altitude > 72)) type = Community.ALPINE_MEADOW;
        else if (mountain > 0.24 && altitude > 34 && temperature < 0.55) type = Community.SUBALPINE_CONIFER;
        else if (temperature < 0.34) type = Community.BOREAL_FOREST;
        else if (temperature > 0.68 && humidity > 0.68) type = Community.RAINFOREST;
        else if (temperature > 0.66 && humidity < 0.40) type = Community.SAVANNA_SCRUB;
        else if (humidity > 0.79 && slope < 0.20) type = Community.WET_WOODLAND;
        else if (forestDensity > 0.62 && oldGrowth > 0.58) type = Community.OLD_GROWTH_FOREST;
        else if (forestDensity > 0.42) type = Community.TEMPERATE_MIXED;
        else type = Community.MEADOW_EDGE;

        double slopePenalty = 1.0 - MathUtil.smoothstep(0.28, 0.58, slope) * 0.74;
        double tree = switch (type) {
            case RIPARIAN -> 1.10; case OLD_GROWTH_FOREST -> 1.28; case TEMPERATE_MIXED -> 1.0;
            case BOREAL_FOREST -> 1.08; case SUBALPINE_CONIFER -> 0.82; case RAINFOREST -> 1.34;
            case WET_WOODLAND -> 1.08; case SAVANNA_SCRUB -> 0.48; case MEADOW_EDGE -> 0.34;
            case ALPINE_MEADOW -> 0.05;
        } * slopePenalty;
        double shrub = switch (type) {
            case RIPARIAN, WET_WOODLAND -> 1.36; case OLD_GROWTH_FOREST -> 0.86; case TEMPERATE_MIXED -> 1.0;
            case BOREAL_FOREST -> 0.82; case SUBALPINE_CONIFER -> 0.72; case RAINFOREST -> 1.18;
            case SAVANNA_SCRUB -> 1.28; case MEADOW_EDGE -> 1.20; case ALPINE_MEADOW -> 0.48;
        };
        double ground = switch (type) {
            case ALPINE_MEADOW, MEADOW_EDGE -> 1.42; case RIPARIAN, WET_WOODLAND -> 1.28;
            case RAINFOREST -> 1.12; case OLD_GROWTH_FOREST -> 0.88; default -> 1.0;
        };
        return new CommunitySample(type, tree, shrub, ground);
    }

    public enum Community { RIPARIAN, OLD_GROWTH_FOREST, TEMPERATE_MIXED, BOREAL_FOREST,
        SUBALPINE_CONIFER, ALPINE_MEADOW, RAINFOREST, WET_WOODLAND, SAVANNA_SCRUB, MEADOW_EDGE }
    public record CommunitySample(Community type, double treeMultiplier, double shrubMultiplier,
                                  double groundMultiplier) {}
}
