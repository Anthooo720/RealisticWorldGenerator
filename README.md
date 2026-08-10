# RealisticWorldGenerator 1.8.0

Générateur naturel custom pour Paper 26.2 / Minecraft 26.2.

## Orientation v1.8

- **Eau unifiée** : `WaterColumnEngine` arbitre une seule fois sol final, rivière, lac et niveau marin. `generateNoise`, `generateSurface`, `generateCaves`, l'API et `getBaseHeight` consomment la même colonne.
- **Sécurité côtière** : les estuaires convergent progressivement vers le niveau marin, les lacs côtiers sont rejetés et les carvers vanilla sont désactivés uniquement dans les chunks très majoritairement océaniques afin d'éviter les grandes poches d'air sous une mer custom.
- **Rivières v6** : profil plus large en U, fond moins en V, berges à raccord progressif, jitter lent de berge, plaines alluviales organiques, largeur selon le débit.
- **Structures vanilla alignées** : `getBaseHeight()` retourne désormais exactement la hauteur de la colonne qui sera réellement générée, y compris érosion et hydrologie, avec cache par colonne.
- **Biomes plus variés** : davantage de PLAINS/SAVANNA/MEADOW ouverts, tandis que les forêts restent regroupées en régions plus nettes.
- **Plaines vivantes** : herbes, fleurs et petits buissons peuvent décorer les biomes ouverts sans les transformer en forêts. Les gros éléments RWG restent exclus des secteurs compatibles villages.
- **Grottes vanilla+** : réseau principal vanilla conservé sur terre ; la surcouche RWG reste légère avec quelques connecteurs/salles rares. Les chunks océaniques custom sont protégés des carvers vanilla incompatibles avec leur bathymétrie.
- Structures 100% vanilla : `shouldGenerateStructures=true`, décorations vanilla actives, aucune interception de `/locate structure`.

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

4. Tester sur un **nouveau monde**. Les chunks déjà générés ne sont jamais recalculés.

## Build

Le projet Gradle cible `paper-api:26.2.build.111-stable`. Le JAR est compilé en bytecode Java 21 et est destiné à Paper 26.2.
