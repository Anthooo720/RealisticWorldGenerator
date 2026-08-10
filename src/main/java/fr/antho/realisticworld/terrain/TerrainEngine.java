package fr.antho.realisticworld.terrain;

import fr.antho.realisticworld.config.WorldGenConfig;
import fr.antho.realisticworld.coast.CoastEngine;
import fr.antho.realisticworld.geology.GeologyMap;
import fr.antho.realisticworld.geology.GeologyMap.GeologySample;
import fr.antho.realisticworld.landscape.LandscapeRegionSystem;
import fr.antho.realisticworld.landscape.LandscapeRegionSystem.RegionFactors;
import fr.antho.realisticworld.noise.FractalNoise;
import fr.antho.realisticworld.noise.SimplexNoise;
import fr.antho.realisticworld.util.MathUtil;

/**
 * Relief géomorphologique multi-échelle. Les grandes formes sont décidées avant le
 * détail : continents -> régions paysagères -> frontières tectoniques -> vallées ->
 * crêtes -> érosion -> micro-relief. Cette hiérarchie évite l'aspect "bruit empilé".
 */
public final class TerrainEngine {
    private final WorldGenConfig.Terrain cfg;
    private final MountainGraph mountains;
    private final ValleyGraph valleys;
    private final GeologyMap geology;
    private final LandscapeRegionSystem landscape;
    private final CoastEngine coast;

    private final FractalNoise broadNoise;
    private final FractalNoise hillNoise;
    private final FractalNoise ridgeNoise;
    private final FractalNoise spurNoise;
    private final FractalNoise microNoise;
    private final SimplexNoise continent;
    private final SimplexNoise reliefRegion;
    private final SimplexNoise summitField;
    private final SimplexNoise gullyA;
    private final SimplexNoise gullyB;
    private final SimplexNoise cliffField;
    private final SimplexNoise warpX;
    private final SimplexNoise warpZ;
    private final SimplexNoise terraceNoise;
    private final SimplexNoise hummockNoise;
    private final SimplexNoise swaleNoise;
    private final SimplexNoise detailPatchNoise;

    private volatile ErosionEngine erosion;

    public TerrainEngine(long seed, WorldGenConfig.Terrain cfg, MountainGraph mountains,
                         ValleyGraph valleys, GeologyMap geology, LandscapeRegionSystem landscape,
                         CoastEngine coast) {
        this.cfg = cfg;
        this.mountains = mountains;
        this.valleys = valleys;
        this.geology = geology;
        this.landscape = landscape;
        this.coast = coast;
        this.broadNoise = new FractalNoise(seed ^ 0x1234A11CE55L, Math.max(4, cfg.octaves()));
        this.hillNoise = new FractalNoise(seed ^ 0x2197A5C3D14L, Math.max(4, cfg.octaves()));
        this.ridgeNoise = new FractalNoise(seed ^ 0x72ACBEEFL, Math.max(5, cfg.octaves()));
        this.spurNoise = new FractalNoise(seed ^ 0x61D9A822F3L, Math.max(4, cfg.octaves()));
        this.microNoise = new FractalNoise(seed ^ 0x4D4943524F4C46L, 3);
        this.continent = new SimplexNoise(seed ^ 0x5A11D00DL);
        this.reliefRegion = new SimplexNoise(seed ^ 0x3157D91A22L);
        this.summitField = new SimplexNoise(seed ^ 0x1F422AC931L);
        this.gullyA = new SimplexNoise(seed ^ 0x6C51DD811BL);
        this.gullyB = new SimplexNoise(seed ^ 0x7E19BDA273L);
        this.cliffField = new SimplexNoise(seed ^ 0x434C49464647454FL);
        this.warpX = new SimplexNoise(seed ^ 0x1111222233334444L);
        this.warpZ = new SimplexNoise(seed ^ 0x5555666677778888L);
        this.terraceNoise = new SimplexNoise(seed ^ 0x7E22ACE55L);
        this.hummockNoise = new SimplexNoise(seed ^ 0x48A11A0CCL);
        this.swaleNoise = new SimplexNoise(seed ^ 0x5A41E5A11EL);
        this.detailPatchNoise = new SimplexNoise(seed ^ 0xD37A11A11L);
    }

    public void attachErosion(ErosionEngine erosion) { this.erosion = erosion; }
    public int seaLevel() { return cfg.seaLevel(); }

    public double continentalness(double x, double z) {
        double wx = warpedX(x, z), wz = warpedZ(x, z);
        double raw = continent.sample(wx * cfg.continentScale(), wz * cfg.continentScale());
        return coast.adjustContinentalness(raw, wx, wz);
    }

    public double mountainInfluence(double x, double z) {
        double land = MathUtil.smootherstep(-0.23, 0.10, continentalness(x, z));
        return mountains.sample(x, z).strength() * land;
    }

    public double valleyInfluence(double x, double z) {
        ValleyGraph.ValleySample v = valleys.sample(x, z);
        return MathUtil.clamp(Math.max(v.main(), v.tributary() * 0.65), 0, 1);
    }

    public double alpineRockiness(double x, double z) {
        GeologySample g = geology.sample(x, z);
        double n = broadNoise.fbm(warpedX(x,z) * cfg.baseScale() * 1.55,
                warpedZ(x,z) * cfg.baseScale() * 1.55, 2.04, 0.48) * 0.5 + 0.5;
        return MathUtil.clamp(n * 0.58 + g.cliffFactor() * 0.42, 0, 1);
    }

    public double alpineGully(double x, double z) {
        double wx = warpedX(x, z), wz = warpedZ(x, z);
        return alpineGullyRaw(wx, wz);
    }

    /** Hauteur avant la simulation d'érosion et avant l'incision des cours d'eau. */
    public double baseHeightRaw(double x, double z) {
        double wx = warpedX(x, z), wz = warpedZ(x, z);
        double rawContinent = continent.sample(wx * cfg.continentScale(), wz * cfg.continentScale());
        double c = coast.adjustContinentalness(rawContinent, wx, wz);
        double land = MathUtil.smootherstep(-0.24, 0.10, c);
        double deepOcean = MathUtil.smootherstep(-0.96, -0.36, c);
        double interior = MathUtil.smootherstep(-0.12, 0.58, c);

        RegionFactors region = landscape.factors(x, z);
        GeologySample geo = geology.sample(x, z);
        MountainGraph.MountainSample mg = mountains.sample(x, z);
        ValleyGraph.ValleySample vg = valleys.sample(x, z);

        double broad = broadNoise.fbm(wx * cfg.baseScale() * 0.20, wz * cfg.baseScale() * 0.20, 2.0, 0.55);
        double mid = hillNoise.fbm(wx * cfg.baseScale() * 0.44, wz * cfg.baseScale() * 0.44, 2.02, 0.51);
        double fine = broadNoise.fbm(wx * cfg.baseScale() * 0.86, wz * cfg.baseScale() * 0.86, 2.03, 0.46);
        double province = reliefRegion.sample(wx * cfg.continentScale() * 1.08, wz * cfg.continentScale() * 1.08);

        double oceanFloor = coast.oceanFloor(wx, wz, c, cfg.seaLevel());

        // Les plaines restent vivantes : ondulations de 5-12 blocs, jamais une dalle plate.
        double basin = 1.0 - MathUtil.smootherstep(-0.72, -0.10, province);
        double plainBase = cfg.seaLevel() + 6.0 + interior * 6.5
                + broad * 8.5 + mid * 3.4 + fine * 1.15 - basin * 3.5;

        // Les zones humides abaissent et aplanissent légèrement les grands bassins.
        plainBase -= region.wetland() * (3.5 + Math.abs(mid) * 2.0);

        double rollingMask = MathUtil.smootherstep(-0.62, 0.38, province) * (0.55 + region.rolling() * 0.45);
        double highlandMask = MathUtil.smootherstep(0.14, 0.72, province) * (0.40 + region.ruggedness() * 0.60);
        double hills = rollingMask * cfg.hillHeight()
                * (0.32 + 0.34 * (mid * 0.5 + 0.5) + 0.20 * (broad * 0.5 + 0.5));
        hills += highlandMask * cfg.hillHeight() * 0.30;

        // Frontières de plaques convergentes : longues chaînes continues et embranchements.
        double range = MathUtil.clamp(mg.strength() * land, 0.0, 1.0);
        double foothillMask = Math.pow(range, 0.46);
        double foothills = foothillMask * cfg.foothillHeight()
                * (0.62 + region.ruggedness() * 0.20 + (broad * 0.5 + 0.5) * 0.18);

        // Coordonnées locales alignées avec la tangente de la frontière tectonique :
        // les crêtes s'allongent dans le sens du massif au lieu de former des bosses isotropes.
        double u = wx * mg.tangentX() + wz * mg.tangentZ();
        double v = -wx * mg.tangentZ() + wz * mg.tangentX();
        double ridge = ridgeNoise.ridged(u * cfg.baseScale() * 0.30, v * cfg.baseScale() * 0.82, 2.04, 0.50);
        double spur = spurNoise.ridged(u * cfg.baseScale() * 0.58, v * cfg.baseScale() * 1.34, 2.10, 0.48);
        double ridgeMain = MathUtil.smootherstep(0.34, 0.73, ridge);
        double ridgeSecondary = MathUtil.smootherstep(0.40, 0.77, spur);
        double crest = MathUtil.clamp(Math.max(ridgeMain, ridgeSecondary * 0.70), 0, 1);

        double summit = summitField.sample(u * cfg.baseScale() * 0.16, v * cfg.baseScale() * 0.23) * 0.5 + 0.5;
        double summitClusters = MathUtil.smootherstep(0.18, 0.82, summit);
        double hardRock = 0.72 + geo.erosionResistance() * 0.28;
        double core = Math.pow(MathUtil.smootherstep(0.16, 0.82, range), 1.10);
        double peakProfile = 0.18 + Math.pow(crest, 1.34) * 0.82;
        double mountainsHeight = core * cfg.mountainHeight() * peakProfile
                * (0.62 + summitClusters * 0.38) * hardRock;
        mountainsHeight *= 0.92 + mg.junction() * 0.14; // nœuds tectoniques = massifs plus complexes.

        double alpineTexture = core * cfg.alpineRuggedness()
                * (((spur - 0.5) * 2.0) * 5.6 + ((ridge - 0.5) * 2.0) * 4.2)
                * (0.65 + geo.cliffFactor() * 0.35);

        // ValleyGraph : vallées principales + tributaires, sculptées AVANT les rivières.
        double valleyAccess = 0.44 + 0.56 * (1.0 - Math.pow(core, 2.2));
        double valleyCut = vg.main() * cfg.valleyDepth() * (0.52 + foothillMask * 0.78 + core * 0.50);
        valleyCut += vg.tributary() * cfg.valleyDepth() * 0.46 * (0.55 + core * 0.65);
        valleyCut *= valleyAccess + core * 0.44;

        // Vallées glaciaires en U : le masque principal possède volontairement un fond large,
        // puis l'incision augmente dans les massifs froids/hauts sans créer un simple V.
        double glacial = vg.glacialPotential() * MathUtil.smootherstep(0.34, 0.82, range)
                * cfg.glacialValleyStrength();
        double glacialCut = glacial * cfg.valleyDepth() * (0.78 + core * 0.95);

        // Ravines fines sur les versants, en complément de l'érosion hydraulique par tuiles.
        double gully = alpineGullyRaw(wx, wz);
        double gullyCut = gully * core * cfg.valleyDepth() * 0.54 * (0.65 + (1.0 - ridgeMain) * 0.35);

        double height = plainBase + hills + foothills + mountainsHeight + alpineTexture
                - valleyCut - glacialCut - gullyCut;

        // Canyons et escarpements géologiques : géométrie, pas seulement changement de bloc.
        if (region.canyon() > 0.08 && land > 0.72) {
            double canyonAxis = Math.max(vg.main(), vg.tributary());
            height -= region.canyon() * canyonAxis * (10.0 + 18.0 * (1.0 - geo.erosionResistance()));
        }
        if (region.cliff() > 0.06 && land > 0.45) {
            double f = cliffField.sample(wx * 0.00082, wz * 0.00082);
            double step = MathUtil.smootherstep(-0.065, 0.065, f) - 0.5;
            double cliffAmplitude = (8.0 + 11.0 * region.ruggedness()) * geo.cliffFactor() * region.cliff();
            height += step * cliffAmplitude;
        }

        // Plateaux rares : grandes épaules hautes avec rebord lisible, sans quantifier tout
        // le terrain. Ils introduisent une rupture d'échelle mémorable entre plaine/collines.
        if(region.plateau()>0.04 && land>0.66 && core<0.62) {
            double plateauTarget=cfg.seaLevel()+24.0+broad*7.0+mid*3.0+region.plateau()*22.0;
            double lifted=Math.max(height,plateauTarget);
            height=MathUtil.lerp(height,lifted,MathUtil.smootherstep(0.04,0.72,region.plateau())*0.72);
        }

        // Relief-signature très rare : inselberg/épaule rocheuse de grande longueur d'onde.
        // Ce n'est pas du micro-bruit supplémentaire ; un joueur peut réellement reconnaître
        // la région à plusieurs centaines de blocs.
        if(region.landmark()>0.02 && land>0.72 && core<0.54) {
            double landmarkShape=Math.pow(MathUtil.smootherstep(0.02,0.88,region.landmark()),1.35);
            height+=landmarkShape*(9.0+16.0*geo.erosionResistance())*(0.70+region.ruggedness()*0.30);
        }

        return MathUtil.lerp(oceanFloor, height, land);
    }

    private double alpineGullyRaw(double wx, double wz) {
        double a = 1.0 - MathUtil.smootherstep(0.018, 0.122,
                Math.abs(gullyA.sample(wx * cfg.baseScale() * 0.82, wz * cfg.baseScale() * 0.82)));
        double b = 1.0 - MathUtil.smootherstep(0.020, 0.112,
                Math.abs(gullyB.sample(wx * cfg.baseScale() * 1.06 + 37.0, wz * cfg.baseScale() * 0.91 - 73.0)));
        return MathUtil.clamp(Math.max(a, b * 0.78), 0.0, 1.0);
    }

    private double warpedX(double x, double z) {
        return x + warpX.sample(x * cfg.warpScale(), z * cfg.warpScale()) * cfg.warpStrength();
    }

    private double warpedZ(double x, double z) {
        return z + warpZ.sample(x * cfg.warpScale(), z * cfg.warpScale()) * cfg.warpStrength();
    }

    /**
     * Micro-relief après érosion : bosses, talus doux, terrasses et petites dépressions.
     * L'amplitude reste volontairement faible pour ne jamais casser les bassins versants.
     */
    private double microRelief(double x, double z) {
        if (cfg.microRelief() <= 0) return 0;
        RegionFactors r = landscape.factors(x, z);
        double mountain = mountains.sample(x, z).strength();
        double n = microNoise.fbm(x * 0.0105, z * 0.0105, 2.0, 0.48);
        double hummock = hummockNoise.sample(x * 0.021, z * 0.021);
        double swale = swaleNoise.sample(x * 0.0052, z * 0.0052);
        double patch = detailPatchNoise.sample(x * 0.0019, z * 0.0019) * 0.5 + 0.5;
        double terraceField = terraceNoise.sample(x * 0.0047, z * 0.0047);
        double terrace = (Math.rint(terraceField * 3.0) / 3.0 - terraceField) * 0.42;
        // Les détails ne sont pas uniformes : certaines zones ont des ondulations, d'autres
        // des creux doux. Ce masque à grande échelle casse l'impression de motif répété.
        double localVariety = 0.62 + patch * 0.58;
        double amp = cfg.microRelief() * (0.48 + r.ruggedness() * 0.42) * (1.0 - mountain * 0.27) * localVariety;
        double wetlandDamping = 1.0 - r.wetland() * 0.62;
        return (n * 0.48 + hummock * 0.22 + swale * 0.24 + terrace * 0.06) * amp * wetlandDamping;
    }

    public double heightWithoutRivers(double x, double z) {
        ErosionEngine e = erosion;
        double h = e == null ? baseHeightRaw(x, z) : e.height(x, z);
        return h + microRelief(x, z);
    }

    /** Pente macro sans érosion : chemin très léger pour /locate et la planification. */
    public double baseSlope(double x, double z) {
        double s = 10.0;
        double dx = baseHeightRaw(x + s, z) - baseHeightRaw(x - s, z);
        double dz = baseHeightRaw(x, z + s) - baseHeightRaw(x, z - s);
        return Math.hypot(dx, dz) / (2.0 * s);
    }

    public double slope(double x, double z) {
        double s = 4.0;
        double dx = heightWithoutRivers(x + s, z) - heightWithoutRivers(x - s, z);
        double dz = heightWithoutRivers(x, z + s) - heightWithoutRivers(x, z - s);
        return Math.hypot(dx, dz) / (2.0 * s);
    }

    /** Direction de pente : gradient normalisé. */
    public SlopeVector slopeVector(double x, double z) {
        double s = 8.0;
        double dx = heightWithoutRivers(x + s, z) - heightWithoutRivers(x - s, z);
        double dz = heightWithoutRivers(x, z + s) - heightWithoutRivers(x, z - s);
        double len = Math.max(1.0e-6, Math.hypot(dx, dz));
        return new SlopeVector(dx / len, dz / len, len / (2.0 * s));
    }

    public record SlopeVector(double dx, double dz, double grade) {}
}
