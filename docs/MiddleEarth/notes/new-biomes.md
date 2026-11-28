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

## POIs

Added after the 11 biomes were painted (placements are first guesses, move them in Tiled). World tile = (x, y) from the north-west corner; chunk = `chunk_<col>_<row>` with the local tile in brackets (world tile minus 150 per chunk). New entries copy a similar ME entry of `points_of_interest.json` (same type, sprite and map family) under a unique `ME <Place>` name, all with `count: 1`. New POIs sit at least 5 tiles inside their biome (3 in the small `isengard`) and at least 8 tiles from every other POI. Objects are written at the tile centre (`x = 16 * local x + 8`, `y = 16 * local y + 8`).

| biome | POI (display name) | status | type | map | world tile | chunk (local tile) |
|---|---|---|---|---|---|---|
| arnor | `ME Annuminas` (Annúminas) | new | dungeon | `fort/fort_colorless_1_snow.tmx` | 265,275 | chunk_1_1 (115,125) |
| arnor | `ME North Downs Camp` (Ranger Camp) | new | town | `towns/waste_town_generic.tmx` | 306,305 | chunk_2_2 (6,5) |
| dagorlad | `ME Dagorlad War-camp` (Dagorlad War-camp) | new | dungeon | `barbariancamp/barbariancamp_orc.tmx` | 755,625 | chunk_5_4 (5,25) |
| dagorlad | `ME Towers of the Teeth` (Towers of the Teeth) | new | dungeon | `fort/fort_colorless_3_human.tmx` | 713,648 | chunk_4_4 (113,48) |
| dunland | `ME Dunlending Village` (Dunlending Village) | new | town | `towns/swamp_town_generic.tmx` | 430,560 | chunk_2_3 (130,110) |
| dunland | `ME Dunlending Hillfort` (Dunlending Hill-fort) | new | dungeon | `barbariancamp/barbariancamp_bandit.tmx` | 419,595 | chunk_2_3 (119,145) |
| emyn_muil | `ME Gollums Cave` (Gollum's Cave) | new | cave | `cave/cave_snake.tmx` | 655,613 | chunk_4_4 (55,13) |
| isengard | `ME Wolf-Gate Camp` (Wolf-Gate Camp) | new | dungeon | `barbariancamp/barbariancamp_orc.tmx` | 449,603 | chunk_2_4 (149,3) |
| ithilien | `ME Henneth Annun` (Henneth Annûn) | new | town | `towns/forest_town_tribal.tmx` | 673,703 | chunk_4_4 (73,103) |
| ithilien | `ME Ithilien Haradrim` (Haradrim Raiders) | new | dungeon | `barbariancamp/barbariancamp_bandit.tmx` | 649,780 | chunk_4_5 (49,30) |
| ithilien | `ME Morannon` (Black Gate) | reassigned | dungeon | `skullcave/skullcave_1.tmx` | 664,679 | chunk_4_4 (64,79) |
| ithilien | `ME Cirith Ungol` (Cirith Ungol) | reassigned | cave | `cave/cave_spider.tmx` | 661,791 | chunk_4_5 (61,41) |
| old_forest | `ME Bombadils House` (Tom Bombadil's House) | new | town | `towns/forest_town_generic.tmx` | 315,440 | chunk_2_2 (15,140) |
| old_forest | `ME Old Man Willow` (Old Man Willow) | new | cave | `evilgrove/evilgrove_4_cursedforest.tmx` | 320,420 | chunk_2_2 (20,120) |
| orocarni | `ME Orocarni Warband` (Easterling Warband) | new | dungeon | `barbariancamp/barbariancamp_bandit.tmx` | 1153,433 | chunk_7_2 (103,133) |
| orocarni | `ME Orocarni Hold` (Hold of the Red Mountains) | new | town | `towns/mountain_town_generic.tmx` | 1151,532 | chunk_7_3 (101,82) |
| trollshaws | `ME Troll-hoard` (Troll-hoard) | new | cave | `evilgrove/evilgrove_8_troll.tmx` | 480,394 | chunk_3_2 (30,94) |
| trollshaws | `ME Last Bridge` (Last Bridge) | new | town | `towns/waste_town_generic.tmx` | 455,417 | chunk_3_2 (5,117) |
| trollshaws | `ME Goblin-town` (Goblin-town) | reassigned | cave | `cave/cave_goblin.tmx` | 457,385 | chunk_3_2 (7,85) |
| weather_hills | `ME Amon Sul` (Amon Sûl Ruins) | new | dungeon | `graveyard_crypt/crypt_3.tmx` | 395,420 | chunk_2_2 (95,120) |
| weather_hills | `ME Trollshaws` (Trollshaws) | reassigned | cave | `cave/cave_multilevel_3/cave_21.tmx` | 394,394 | chunk_2_2 (94,94) |
| withered_heath | `ME Dragon Lair` (Dragon's Lair) | new | cave | `evilgrove/evilgrove_7_dragon.tmx` | 900,250 | chunk_6_1 (0,100) |
| withered_heath | `ME Dwarf Ruin` (Ruined Dwarf-hold) | new | cave | `cave/cave_dwarf.tmx` | 780,300 | chunk_5_2 (30,0) |
| withered_heath | `ME Dale` (Dale) | reassigned | town | `towns/plains_town_generic.tmx` | 711,291 | chunk_4_1 (111,141) |
| withered_heath | `ME Thranduils Halls` (Thranduil's Halls) | reassigned | town | `towns/island_town_identity.tmx` | 640,293 | chunk_4_1 (40,143) |
| withered_heath | `ME Iron Hills` (Iron Hills) | reassigned | town | `towns/mountain_town_identity.tmx` | 809,268 | chunk_5_1 (59,118) |
| withered_heath | `ME Iron Mine` (Deep Mine) | reassigned | cave | `cave/cave_huge.tmx` | 832,259 | chunk_5_1 (82,109) |

**Reassigned** = an existing POI object whose tile now lies in a new biome; its name moved from the old biome's `pointsOfInterest` to the new one, its position is unchanged. The four `withered_heath` ones (Dale, Thranduil's Halls, Iron Hills, Iron Mine) are reassigned only because the painted `withered_heath` covers x 603-1089, y 220-339 (about 30,000 tiles, the whole northern strip around Erebor); if that is a painting accident, repaint and move the names back to `wilderland`, `mirkwood` and `iron_hills`.

**Existing POIs not duplicated.** Weathertop (334,427), the Old Forest cave (267,458), Fornost Ruins (265,324), ME Isengard (439,639) and the Emyn Muil cave (588,586) exist but still lie outside the new biomes (in `eriador`, `rohan`...), so the new regions got different POIs instead (Amon Sûl Ruins, Old Man Willow, Annúminas, Wolf-Gate Camp, Gollum's Cave). Move the old objects into the new biomes and switch their biome list if you want them there.

**Check state:** `check.sh` 0 errors; WorldSim reports no `OUTSIDE BIOME` and no unreachable for the new POIs. 55 older POIs are still `OUTSIDE BIOME` (63 before): they were placed for the old biome shapes (many now on ocean tiles or in a neighbouring biome) and are left for repositioning in Tiled.
