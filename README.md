# RealisticWorldGenerator 1.6.0

Générateur naturel custom pour Paper 26.2 / Minecraft 26.2.

## Orientation v1.6

- Rivières v4 : tracé déformé en continu, méandres, largeur selon le débit, plaines alluviales, berges irrégulières et bras secondaires rares.
- Grottes vanilla+ : les carvers vanilla redeviennent le réseau principal ; RWG ajoute seulement quelques connecteurs et salles moyennes rares.
- Végétation : forêts denses mais structurées par clairières, plus de sous-bois, feuilles custom persistantes.
- Cerisiers : exclusivement dans le biome vanilla `CHERRY_GROVE`.
- Villages/structures : 100% vanilla. RWG réserve les biomes de village à des secteurs macro ouverts/plats et n'y place pas ses gros arbres/rochers custom.
- `/locate structure` n'est pas intercepté par RWG.

## Installation

1. Placer le JAR dans `plugins/`.
2. Garder dans `bukkit.yml` :

```yaml
worlds:
  world:
    generator: RealisticWorldGenerator
```

3. Vérifier dans `server.properties` :

```properties
generate-structures=true
```

4. Pour comparer proprement les versions, utiliser un nouveau monde : les chunks déjà générés ne sont jamais recalculés.

## Build officiel du projet

Le projet Gradle cible `paper-api:26.2.build.111-stable`, Java 25, avec bytecode Java 21.
