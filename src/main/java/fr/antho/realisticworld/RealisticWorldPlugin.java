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
    private static final int CONFIG_VERSION=11;
    private volatile WorldGenConfig settings;
    private volatile ContextRegistry contexts;
    private volatile RealisticWorldApi api;
    private static volatile RealisticWorldPlugin instance;

    @Override public void onLoad(){ instance=this; initialize(); }
    @Override public void onEnable(){
        initialize();
        getLogger().info("RealisticWorldGenerator v1.8.0 actif : colonnes eau unifiees, estuaires/berges v6, structures alignees, oceans proteges et biomes ouverts vivants.");
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

        // v1.7 : profil fluvial en U et raccord progressif au terrain.
        c.set("rivers.max-carve-depth",5.4);
        c.set("rivers.max-width",15.0);
        c.set("rivers.min-width",1.5);
        c.set("rivers.bank-buffer",1.55);
        c.set("rivers.max-water-depth",2.5);
        c.set("rivers.meander-scale",0.0030);
        c.set("rivers.meander-strength",1.05);
        c.set("rivers.floodplain-width",9.5);
        c.set("rivers.profile-exponent",1.85);
        c.set("rivers.bank-slope-width",4.2);
        c.set("rivers.bank-max-cut",2.4);
        c.set("rivers.floodplain-max-cut",1.8);
        c.set("rivers.edge-roughness",0.72);
        c.set("rivers.secondary-channel-frequency",0.14);

        // Caves : base vanilla conservée, surcouche plus variée mais toujours secondaire.
        c.set("caves.chamber-spacing",164);
        c.set("caves.chamber-frequency",0.060);
        c.set("caves.max-chamber-radius",9.0);
        c.set("caves.overlay-strength",0.72);
        c.set("caves.vertical-link-frequency",0.032);
        c.set("caves.aquifer-frequency",0.055);
        c.set("caves.aquifer-max-y",-18);

        // Variance régionale : plateaux et reliefs-signatures rares.
        c.set("landscape.plateau-strength",0.68);
        c.set("landscape.landmark-strength",0.74);

        // v1.8 : arbitrage unique des niveaux d'eau + vraie transition estuaire/océan.
        c.set("rivers.coastal-merge-height",14.0);
        c.set("rivers.coastal-water-gradient",0.34);
        c.set("rivers.channel-bed-flatness",0.52);
        c.set("rivers.bank-height-jitter",0.42);
        c.set("lakes.coast-guard-height",12.0);
        c.set("lakes.rim-samples",28);

        // Protection des carvers vanilla uniquement sous les chunks très océaniques :
        // sur terre, le réseau de grottes reste vanilla+ comme avant.
        c.set("caves.tunnel-radius",0.094);
        c.set("caves.chamber-spacing",152);
        c.set("caves.chamber-frequency",0.068);
        c.set("caves.max-chamber-radius",10.0);
        c.set("caves.overlay-strength",0.76);
        c.set("caves.vertical-link-frequency",0.038);
        c.set("caves.aquifer-frequency",0.045);
        c.set("caves.protect-ocean-carvers",true);
        c.set("caves.ocean-carver-max-land-ratio",0.34);

        // Plus de régions ouvertes et une décoration basse plus riche dans les plaines.
        c.set("biomes.open-flat-max-slope",0.145);
        c.set("biomes.open-flat-min-openness",0.43);
        c.set("biomes.temperate-forest-humidity",0.70);
        c.set("biomes.dark-forest-humidity",0.86);
        c.set("biomes.open-region-bias",0.16);
        c.set("vegetation.open-ground-cover-density",0.78);
        c.set("vegetation.open-shrub-density",0.11);
        c.set("performance.column-cache-chunks",192);

        c.set("config-version",CONFIG_VERSION); saveConfig();
        getLogger().info("Configuration migree vers v1.8.0 (eau cotiere securisee, rivieres v6, heightmaps exactes, biomes ouverts mieux repartis).");
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
            sender.sendMessage("/rwg debug <terrain|height|climate|water|geology> - diagnostic cible");
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
                case "height" -> {
                    var ctx=contexts.forWorld(player.getWorld());
                    double raw=ctx.terrain.baseHeightRaw(x,z);
                    var column=ctx.waterColumns.sample(x,z);
                    double eroded=column.naturalHeight(), finalY=column.groundHeight();
                    sender.sendMessage("Height: raw="+String.format("%.2f",raw)+" eroded="+String.format("%.2f",eroded)+" delta="+String.format("%+.2f",eroded-raw)+" final="+String.format("%.2f",finalY)+" waterTop="+(column.hasWater()?column.waterTop():"-"));
                }
                case "climate" -> sender.sendMessage("Climat: T="+String.format("%.3f",s.climate().temperature())+" H="+String.format("%.3f",s.climate().humidity())+" continentalite="+String.format("%.3f",s.climate().continentalness())+" exposition="+String.format("%.2f",s.climate().solarAspect()));
                case "water" -> {
                    var ctx=contexts.forWorld(player.getWorld());
                    var column=ctx.waterColumns.sample(x,z);
                    sender.sendMessage("Eau: river="+s.river().isRiver()+" largeur="+String.format("%.2f",s.river().approximateWidth())+" debit="+String.format("%.1f",s.river().discharge())+" pente="+String.format("%.3f",s.river().grade())+" riverLevel="+(s.river().isRiver()?Math.round(s.river().waterSurface()):"-")+" lake="+s.lake().isLake()+" finalWater="+(column.hasWater()?column.waterTop():"-"));
                }
                case "geology" -> sender.sendMessage("Geologie: "+s.geology().type()+" resistance="+String.format("%.2f",s.geology().erosionResistance())+" falaise="+String.format("%.2f",s.geology().cliffFactor())+" sol="+String.format("%.2f",s.geology().soilDepth()));
                default -> sender.sendMessage("Usage: /rwg debug <terrain|height|climate|water|geology>");
            }
            return true;
        }
        sender.sendMessage("Usage: /rwg inspect ou /rwg debug <terrain|climate|water|geology>"); return true;
    }
}
