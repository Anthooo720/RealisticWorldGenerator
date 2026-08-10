package fr.antho.realisticworld;

import fr.antho.realisticworld.api.RealisticWorldApi;
import fr.antho.realisticworld.biome.RealisticBiomeProvider;
import fr.antho.realisticworld.config.WorldGenConfig;
import fr.antho.realisticworld.gen.ContextRegistry;
import fr.antho.realisticworld.gen.RealisticChunkGenerator;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;

/** Plugin principal : monde naturel uniquement. Les structures restent 100% vanilla. */
public final class RealisticWorldPlugin extends JavaPlugin {
    private static final int CONFIG_VERSION=13;
    private volatile WorldGenConfig settings;
    private volatile ContextRegistry contexts;
    private volatile RealisticWorldApi api;
    private static volatile RealisticWorldPlugin instance;

    @Override public void onLoad(){ instance=this; initialize(); }

    @Override public void onEnable() {
        initialize();
        getLogger().info("RealisticWorldGenerator v1.9.1 actif : rivieres smooth v7, watershed optimise et structures vanilla sur terrains ouverts plus stricts.");
    }

    private synchronized void initialize() {
        if(contexts!=null) return;
        saveDefaultConfig();
        migrateConfigIfNeeded();
        settings=WorldGenConfig.load(getConfig());
        contexts=new ContextRegistry(settings);
        api=new RealisticWorldApi(contexts);
    }

    private void migrateConfigIfNeeded() {
        FileConfiguration c=getConfig();
        int version=c.getInt("config-version",0);
        if(version>=CONFIG_VERSION) return;

        if(version<12) migrateLegacyToV12(c);
        if(version<13) migrateV13(c);

        c.set("config-version",CONFIG_VERSION);
        saveConfig();
        getLogger().info("Configuration migree vers v1.9.1 (rivieres plus douces, generation acceleree, biomes de structures plus plats).");
    }

    private static void migrateLegacyToV12(FileConfiguration c) {
        c.set("terrain.micro-relief",4.2);
        c.set("rivers.sample-spacing",5);
        c.set("rivers.margin-samples",28);
        c.set("rivers.accumulation-threshold",46.0);
        c.set("rivers.max-carve-depth",5.4);
        c.set("rivers.max-width",15.0);
        c.set("rivers.min-width",1.5);
        c.set("rivers.bank-buffer",1.55);
        c.set("rivers.max-water-depth",2.5);
        c.set("rivers.max-wet-grade",0.17);
        c.set("rivers.waterfall-grade",0.11);
        c.set("rivers.meander-scale",0.0030);
        c.set("rivers.meander-strength",1.05);
        c.set("rivers.floodplain-width",9.5);
        c.set("rivers.profile-exponent",1.85);
        c.set("rivers.bank-slope-width",4.2);
        c.set("rivers.bank-max-cut",2.4);
        c.set("rivers.floodplain-max-cut",1.8);
        c.set("rivers.edge-roughness",0.72);
        c.set("rivers.secondary-channel-frequency",0.14);
        c.set("rivers.coastal-merge-height",14.0);
        c.set("rivers.coastal-water-gradient",0.34);
        c.set("rivers.channel-bed-flatness",0.52);
        c.set("rivers.bank-height-jitter",0.42);

        c.set("lakes.coast-guard-height",12.0);
        c.set("lakes.rim-samples",28);

        c.set("caves.enabled",true);
        c.set("caves.tunnel-scale",0.024);
        c.set("caves.tunnel-radius",0.094);
        c.set("caves.chamber-spacing",152);
        c.set("caves.chamber-frequency",0.068);
        c.set("caves.max-chamber-radius",10.0);
        c.set("caves.overlay-strength",0.76);
        c.set("caves.vertical-link-frequency",0.038);
        c.set("caves.aquifer-frequency",0.045);
        c.set("caves.aquifer-max-y",-18);
        c.set("caves.protect-ocean-carvers",true);
        c.set("caves.ocean-carver-max-land-ratio",0.34);
        c.set("compatibility.vanilla-caves",true);
        c.set("compatibility.vanilla-decorations",true);

        c.set("landscape.plateau-strength",0.68);
        c.set("landscape.landmark-strength",0.74);
        c.set("biomes.open-flat-max-slope",0.145);
        c.set("biomes.open-flat-min-openness",0.43);
        c.set("biomes.temperate-forest-humidity",0.70);
        c.set("biomes.dark-forest-humidity",0.86);
        c.set("biomes.open-region-bias",0.16);

        c.set("vegetation.tree-density",0.074);
        c.set("vegetation.shrub-density",0.32);
        c.set("vegetation.ground-cover-density",0.62);
        c.set("vegetation.boulder-density",0.018);
        c.set("vegetation.grove-scale",0.00120);
        c.set("vegetation.succession-scale",0.00048);
        c.set("vegetation.deadwood-density",0.042);
        c.set("vegetation.parametric-variation",0.92);
        c.set("vegetation.open-ground-cover-density",0.78);
        c.set("vegetation.open-shrub-density",0.11);
        c.set("performance.column-cache-chunks",192);

        c.set("landscape.regional-contrast",1.18);
        c.set("rivers.thalweg-offset",0.18);
        c.set("rivers.bank-transition-power",1.35);
        c.set("lakes.river-connect-distance",20.0);
        c.set("lakes.shore-blend-width",6.0);
        c.set("lakes.bed-roughness",0.42);
        c.set("biomes.transition-radius",14);
        c.set("biomes.transition-patch-scale",0.045);
        c.set("biomes.rare-biome-frequency",0.18);
        c.set("caves.surface-detail-strength",0.34);
        c.set("caves.decoration-frequency",0.018);
        c.set("caves.step-frequency",0.009);
        c.set("vegetation.azalea-frequency",0.006);
        c.set("vegetation.custom-flora-bias",0.82);

        c.set("settlements",null);
        c.set("roads",null);
        c.set("compatibility.vanilla-structures",null);
        c.set("performance.route-cache",null);
        c.set("performance.settlement-cache-cells",null);
    }

    /** Ne remplace une valeur que si le serveur utilise encore exactement le default v1.9. */
    private static void migrateV13(FileConfiguration c) {
        replaceInt(c,"erosion.sample-spacing",8,10);
        replaceInt(c,"erosion.margin-samples",10,9);
        replaceInt(c,"erosion.hydraulic-iterations",42,28);
        replaceInt(c,"erosion.thermal-iterations",6,4);

        replaceInt(c,"rivers.sample-spacing",5,6);
        replaceInt(c,"rivers.margin-samples",28,22);
        replaceDouble(c,"rivers.max-carve-depth",5.4,5.2);
        replaceDouble(c,"rivers.max-width",15.0,17.0);
        replaceDouble(c,"rivers.min-width",1.5,1.7);
        replaceDouble(c,"rivers.bank-buffer",1.55,1.45);
        replaceDouble(c,"rivers.max-water-depth",2.5,2.6);
        replaceDouble(c,"rivers.meander-scale",0.0030,0.0027);
        replaceDouble(c,"rivers.meander-strength",1.05,1.02);
        replaceDouble(c,"rivers.floodplain-width",9.5,11.0);
        replaceDouble(c,"rivers.profile-exponent",1.85,1.55);
        replaceDouble(c,"rivers.bank-slope-width",4.2,7.0);
        replaceDouble(c,"rivers.bank-max-cut",2.4,2.0);
        replaceDouble(c,"rivers.floodplain-max-cut",1.8,1.4);
        replaceDouble(c,"rivers.edge-roughness",0.72,0.40);
        replaceDouble(c,"rivers.secondary-channel-frequency",0.14,0.07);
        replaceDouble(c,"rivers.channel-bed-flatness",0.52,0.38);
        replaceDouble(c,"rivers.bank-height-jitter",0.42,0.26);
        replaceDouble(c,"rivers.thalweg-offset",0.18,0.14);
        replaceDouble(c,"rivers.bank-transition-power",1.35,1.0);

        replaceDouble(c,"biomes.open-flat-max-slope",0.145,0.095);
        replaceDouble(c,"biomes.open-flat-min-openness",0.43,0.50);
        replaceDouble(c,"biomes.open-region-bias",0.16,0.12);

        replaceInt(c,"performance.erosion-cache-tiles",64,96);
        replaceInt(c,"performance.watershed-cache-tiles",48,64);
        replaceInt(c,"performance.column-cache-chunks",192,224);
    }

    private static void replaceInt(FileConfiguration c,String path,int oldValue,int newValue) {
        if(!c.contains(path)||c.getInt(path)==oldValue) c.set(path,newValue);
    }

    private static void replaceDouble(FileConfiguration c,String path,double oldValue,double newValue) {
        if(!c.contains(path)||Math.abs(c.getDouble(path)-oldValue)<1.0e-9) c.set(path,newValue);
    }

    public RealisticWorldApi getApi(){ initialize(); return api; }
    public static RealisticWorldPlugin getInstance(){ return instance; }

    @Override public ChunkGenerator getDefaultWorldGenerator(String worldName,String id){ initialize(); return new RealisticChunkGenerator(contexts); }
    @Override public BiomeProvider getDefaultBiomeProvider(String worldName,String id){ initialize(); return new RealisticBiomeProvider(contexts); }

    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args) {
        if(!command.getName().equalsIgnoreCase("rwg")) return false;
        if(!(sender instanceof Player player)) {
            sender.sendMessage("Commande disponible en jeu : /rwg inspect");
            return true;
        }
        if(args.length==0||args[0].equalsIgnoreCase("help")) {
            sender.sendMessage("/rwg inspect - resume terrain, climat, eau et geologie");
            sender.sendMessage("/rwg debug <terrain|height|climate|water|geology> - diagnostic cible");
            sender.sendMessage("Les structures ne sont pas gerees par RWG : /locate structure reste vanilla.");
            return true;
        }
        if(args[0].equalsIgnoreCase("inspect")) {
            int x=(int)Math.floor(player.getX()),z=(int)Math.floor(player.getZ());
            var s=api.sample(player.getWorld(),x,z);
            sender.sendMessage("RWG @ "+x+", "+z+" | Y="+Math.round(s.surfaceHeight())+" pente="+String.format("%.2f",s.slope()));
            sender.sendMessage("Paysage="+s.landscape()+" roche="+s.geology().type()+" montagne="+String.format("%.2f",s.mountainInfluence()));
            sender.sendMessage("Temp="+String.format("%.2f",s.climate().temperature())+" humidite="+String.format("%.2f",s.climate().humidity()));
            sender.sendMessage("Riviere="+s.river().isRiver()+" debit="+String.format("%.1f",s.river().discharge())+" lac="+s.lake().isLake()+" eau="+(s.hasWater()?s.waterTop():"-"));
            return true;
        }
        if(args[0].equalsIgnoreCase("debug")&&args.length>=2) {
            int x=(int)Math.floor(player.getX()),z=(int)Math.floor(player.getZ());
            var s=api.sample(player.getWorld(),x,z);
            switch(args[1].toLowerCase()) {
                case "terrain" -> sender.sendMessage("Terrain: Y="+Math.round(s.surfaceHeight())+" pente="+String.format("%.3f",s.slope())+" montagne="+String.format("%.2f",s.mountainInfluence())+" vallee="+String.format("%.2f",s.valleyInfluence())+" paysage="+s.landscape());
                case "height" -> {
                    var ctx=contexts.forWorld(player.getWorld());
                    double raw=ctx.terrain.baseHeightRaw(x,z);
                    var column=ctx.waterColumns.sample(x,z);
                    double eroded=column.naturalHeight(),finalY=column.groundHeight();
                    sender.sendMessage("Height: raw="+String.format("%.2f",raw)+" eroded="+String.format("%.2f",eroded)+" delta="+String.format("%+.2f",eroded-raw)+" final="+String.format("%.2f",finalY)+" waterTop="+(column.hasWater()?column.waterTop():"-"));
                }
                case "climate" -> sender.sendMessage("Climat: T="+String.format("%.3f",s.climate().temperature())+" H="+String.format("%.3f",s.climate().humidity())+" continentalite="+String.format("%.3f",s.climate().continentalness())+" exposition="+String.format("%.2f",s.climate().solarAspect()));
                case "water" -> sender.sendMessage("Eau: river="+s.river().isRiver()+" largeur="+String.format("%.2f",s.river().approximateWidth())+" debit="+String.format("%.1f",s.river().discharge())+" pente="+String.format("%.3f",s.river().grade())+" riverLevel="+(s.river().isRiver()?String.format("%.2f",s.river().waterSurface()):"-")+" lake="+s.lake().isLake()+" finalWater="+(s.hasWater()?String.format("%.2f",s.waterSurface()):"-"));
                case "geology" -> sender.sendMessage("Geologie: "+s.geology().type()+" resistance="+String.format("%.2f",s.geology().erosionResistance())+" falaise="+String.format("%.2f",s.geology().cliffFactor())+" sol="+String.format("%.2f",s.geology().soilDepth()));
                default -> sender.sendMessage("Usage: /rwg debug <terrain|height|climate|water|geology>");
            }
            return true;
        }
        sender.sendMessage("Usage: /rwg inspect ou /rwg debug <terrain|height|climate|water|geology>");
        return true;
    }
}
