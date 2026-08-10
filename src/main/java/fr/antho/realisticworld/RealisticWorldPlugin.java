package fr.antho.realisticworld;

import fr.antho.realisticworld.api.RealisticWorldApi;
import fr.antho.realisticworld.biome.RealisticBiomeProvider;
import fr.antho.realisticworld.config.WorldGenConfig;
import fr.antho.realisticworld.gen.ContextRegistry;
import fr.antho.realisticworld.gen.RealisticChunkGenerator;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;

/** Plugin principal : monde naturel uniquement. Les structures restent 100% vanilla. */
public final class RealisticWorldPlugin extends JavaPlugin {
    private static final int CONFIG_VERSION=9;
    private volatile WorldGenConfig settings;
    private volatile ContextRegistry contexts;
    private volatile RealisticWorldApi api;
    private static volatile RealisticWorldPlugin instance;

    @Override public void onLoad(){ instance=this; initialize(); }
    @Override public void onEnable(){
        initialize();
        getLogger().info("RealisticWorldGenerator v1.6.0 actif : rivieres v4 meandrantes, grottes vanilla+, vegetation biome-aware et structures vanilla preservees.");
    }

    private synchronized void initialize(){
        if(contexts!=null) return;
        saveDefaultConfig(); migrateConfigIfNeeded();
        settings=WorldGenConfig.load(getConfig());
        contexts=new ContextRegistry(settings);
        api=new RealisticWorldApi(contexts);
    }

    private void migrateConfigIfNeeded(){
        FileConfiguration c=getConfig();
        int version=c.getInt("config-version",0);
        if(version>=CONFIG_VERSION) return;

        // v1.6 : plus de détail local, sans augmenter brutalement les amplitudes du relief.
        c.set("terrain.micro-relief",4.2);

        // Hydrologie v4 : réseau D8 conservé pour le débit, mais coordonnées déformées,
        // largeur variable, vraies plaines alluviales et bras secondaires occasionnels.
        c.set("rivers.sample-spacing",5);
        c.set("rivers.margin-samples",28);
        c.set("rivers.accumulation-threshold",46.0);
        c.set("rivers.max-carve-depth",7.2);
        c.set("rivers.max-width",13.0);
        c.set("rivers.min-width",1.4);
        c.set("rivers.bank-buffer",1.75);
        c.set("rivers.max-water-depth",2.6);
        c.set("rivers.max-wet-grade",0.17);
        c.set("rivers.waterfall-grade",0.11);
        c.set("rivers.meander-scale",0.00115);
        c.set("rivers.meander-strength",0.94);
        c.set("rivers.floodplain-width",7.5);

        // Caves vanilla+ : les carvers vanilla redeviennent la base. RWG ajoute seulement
        // de rares connecteurs et salles moyennes, sans aquifère custom.
        c.set("caves.enabled",true);
        c.set("caves.tunnel-scale",0.024);
        c.set("caves.tunnel-radius",0.090);
        c.set("caves.chamber-spacing",176);
        c.set("caves.chamber-frequency",0.055);
        c.set("caves.max-chamber-radius",8.0);
        c.set("compatibility.vanilla-caves",true);

        // Forêts : densité forte mais contrastée par de vraies clairières. Les cerisiers
        // sont maintenant strictement liés au biome CHERRY_GROVE.
        c.set("vegetation.tree-density",0.074);
        c.set("vegetation.shrub-density",0.32);
        c.set("vegetation.ground-cover-density",0.62);
        c.set("vegetation.boulder-density",0.018);
        c.set("vegetation.grove-scale",0.00120);
        c.set("vegetation.succession-scale",0.00048);
        c.set("vegetation.deadwood-density",0.042);
        c.set("vegetation.parametric-variation",0.92);

        // Structures et décorations restent 100% vanilla. Les biomes de villages sont
        // réservés aux zones macro ouvertes/plates et RWG n'y ajoute pas ses gros objets.
        c.set("compatibility.vanilla-decorations",true);
        c.set("settlements",null); c.set("roads",null); c.set("compatibility.vanilla-structures",null);
        c.set("performance.route-cache",null); c.set("performance.settlement-cache-cells",null);

        c.set("config-version",CONFIG_VERSION); saveConfig();
        getLogger().info("Configuration migree vers v1.6.0 (rivieres v4, caves vanilla+, cherry strict, zones villages ouvertes).");
    }

    public RealisticWorldApi getApi(){ initialize(); return api; }
    public static RealisticWorldPlugin getInstance(){ return instance; }

    @Override public ChunkGenerator getDefaultWorldGenerator(String worldName,String id){ initialize(); return new RealisticChunkGenerator(contexts); }
    @Override public BiomeProvider getDefaultBiomeProvider(String worldName,String id){ initialize(); return new RealisticBiomeProvider(contexts); }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args){
        if(!command.getName().equalsIgnoreCase("rwg")) return false;
        if(!(sender instanceof Player player)) { sender.sendMessage("Commande disponible en jeu : /rwg inspect"); return true; }
        if(args.length==0 || args[0].equalsIgnoreCase("help")) {
            sender.sendMessage("/rwg inspect - resume terrain, climat, eau et geologie");
            sender.sendMessage("/rwg debug <terrain|climate|water|geology> - diagnostic cible");
            sender.sendMessage("Les structures ne sont pas gerees par RWG : /locate structure reste vanilla.");
            return true;
        }
        if(args[0].equalsIgnoreCase("inspect")) {
            int x=(int)Math.floor(player.getX()), z=(int)Math.floor(player.getZ());
            var s=api.sample(player.getWorld(),x,z);
            sender.sendMessage("RWG @ "+x+", "+z+" | Y="+Math.round(s.surfaceHeight())+" pente="+String.format("%.2f",s.slope()));
            sender.sendMessage("Paysage="+s.landscape()+" roche="+s.geology().type()+" montagne="+String.format("%.2f",s.mountainInfluence()));
            sender.sendMessage("Temp="+String.format("%.2f",s.climate().temperature())+" humidite="+String.format("%.2f",s.climate().humidity()));
            sender.sendMessage("Riviere="+s.river().isRiver()+" debit="+String.format("%.1f",s.river().discharge())+" lac="+s.lake().isLake());
            return true;
        }
        if(args[0].equalsIgnoreCase("debug") && args.length>=2) {
            int x=(int)Math.floor(player.getX()), z=(int)Math.floor(player.getZ());
            var s=api.sample(player.getWorld(),x,z);
            switch(args[1].toLowerCase()) {
                case "terrain" -> sender.sendMessage("Terrain: Y="+Math.round(s.surfaceHeight())+" pente="+String.format("%.3f",s.slope())+" montagne="+String.format("%.2f",s.mountainInfluence())+" vallee="+String.format("%.2f",s.valleyInfluence())+" paysage="+s.landscape());
                case "climate" -> sender.sendMessage("Climat: T="+String.format("%.3f",s.climate().temperature())+" H="+String.format("%.3f",s.climate().humidity())+" continentalite="+String.format("%.3f",s.climate().continentalness())+" exposition="+String.format("%.2f",s.climate().solarAspect()));
                case "water" -> sender.sendMessage("Eau: river="+s.river().isRiver()+" largeur="+String.format("%.2f",s.river().approximateWidth())+" debit="+String.format("%.1f",s.river().discharge())+" pente="+String.format("%.3f",s.river().grade())+" lake="+s.lake().isLake()+" niveau="+(s.lake().isLake()?Math.round(s.lake().waterSurface()):"-") );
                case "geology" -> sender.sendMessage("Geologie: "+s.geology().type()+" resistance="+String.format("%.2f",s.geology().erosionResistance())+" falaise="+String.format("%.2f",s.geology().cliffFactor())+" sol="+String.format("%.2f",s.geology().soilDepth()));
                default -> sender.sendMessage("Usage: /rwg debug <terrain|climate|water|geology>");
            }
            return true;
        }
        sender.sendMessage("Usage: /rwg inspect ou /rwg debug <terrain|climate|water|geology>"); return true;
    }
}
