# New named biomes (11)

Data only; nothing is painted yet (`check.sh` warns "biome X is not painted" for each). Paint them in Tiled with the new palette tiles; the biome boxes then follow the painting (the JSON box is a fallback). No POIs are assigned: place them in Tiled and add them to the biome's `pointsOfInterest`. Registered at the end of `world.json` `biomesNames` (tile ids 42-52; existing ids unchanged, the decoded biome of all 1,440,000 chunk tiles is identical before and after). Town names: `world/town_names_<biome>.txt`.

| biome | tileset | structures | enemies (spawn difficulty) | label / editor colour / game colour |
|---|---|---|---|---|
| dagorlad | Colorless | none | Orc Warrior (0.1), Mordor Orc (0.3), Easterling Warrior (0.3), Orc Captain (0.6), Olog-hai (0.6), Fell Beast (0.8), Black Rider (0.8) | DAG `#c8b1d3` / 8a8478 |
| orocarni | Red | mountain | Easterling Warrior (0.3), Warg Pack (0.5), Cold-drake (1), Hill Troll (0.3), Dwarf of the White Hills (0.1), Dwarf of Erebor (0.5), Iron Hills Warlord (1) | ORO `#905959` / 8c3a22 |
| emyn_muil | Colorless | mountain | Gollum (0.5), Snaga Scout (0.1), Mordor Orc (0.3), Crebain (0.1), Wild Wolves (0.1), Orc Hunter (0.1) | EMU `#90bc9b` / a09a82 |
| old_forest | Green | forest | Wild Wolves, Wolf, Wild Bear, Badger (all 0.1), Huorn (0.5), Barrow-wight (0.5), Old Man Willow (2) | OLF `#7a9037` / 2a5e2a |
| trollshaws | Green | none | Troll, Stone-turned Troll, Wild Wolves, Wild Bear (all 0.1), Ranger of the North (0.5), Hill Troll (0.3), Stone Troll (0.6) | TRS `#ffbce9` / 5a7a3a |
| weather_hills | Green | none | Wild Wolves, Wolf, Southern Ruffian, Dunlending Raider (all 0.1), Ranger of the North (0.5), Bill Ferny (0.5), Black Rider (0.8) | WEA `#9bde90` / 7a8a4a |
| arnor | Green | none | Restless Barrow-wight, Barrow-wight Thrall, Wild Wolves (all 0.1), Ranger of the North (0.5), Barrow-wight (0.5), Spirit of Dol Guldur (0.3) | ARN `#c8c885` / 6e8a78 |
| ithilien | Green | none | Ranger of Ithilien (0.5), Haradrim Warrior (0.3), Mordor Orc (0.3), Wild Wolves (0.1), Haradrim Assassin (0.3), Mûmak of Harad (0.6) | ITH `#d3a6b1` / 3aa070 |
| withered_heath | Red | mountain | Snaga (0.1), Cold-land Bear (0.1), Warg Pack (0.5), Cold-drake (1), Grey Mountain Wyrm (2) | WHE `#854e0b` / 7a2a38 |
| dunland | Colorless | none | Dunlending Raider, Dunlending Hedge-wizard, Crebain, Wild Wolves (all 0.1), Uruk-hai (0.3), Sharkey's Ruffian (0.5) | DUN `#dea69b` / 8c7a6a |
| isengard | Black | none | Uruk-hai (0.3), Uruk Flayer (0.1), Crebain (0.1), Warg Rider (0.3), Spiked Uruk Ravager (0.1), Uruk-hai Berserker (0.3), Uruk-hai Captain (0.6) | ISE `#210016` / 3a3a40 |

Palette: `make_biome_palette.py --keep` (existing colours kept). Closest pair is now dE2000 9.0 (`harlindon` / `dunland`; was 13.4), 4 pairs below 10, all in the crowded plains family. The script got the new families (old_forest forest, isengard shadow, orocarni and withered_heath mountain) and the labels. Re-pick `dunland` by hand in `biome_palette.json` if that pair is too close. The contact sheet is `img/biome-palette-tiles.png`.
