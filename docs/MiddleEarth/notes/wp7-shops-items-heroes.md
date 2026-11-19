# WP7 shops, items, heroes

Branch `worktree-agent-aff81cf1a26390204`. Data only, no engine changes.

Files: `MiddleEarth/world/shops.json` (rewritten, 250 shops), `MiddleEarth/world/items.json` (new, plane-local, 107 items), `MiddleEarth/world/heroes.json` (new, 5 races), `tools/middleearth/PlaneCheck.java` (new section 6). `config.json` is unchanged: the start items `Manasight Amulet` and `Leather Boots` are kept under their names in the plane's items.

The JSON files were produced by small generator scripts (kept outside the repo). The JSON is the source of truth now, so edit it directly.

## 1. How shops are wired

- **Town maps decide which shop names are possible.** Every shop building in a `.tmx` is an object made from `obj/shop.tx` (`type=shop`) or `obj/RotatingShop.tx` (`type=Rotating`). Its properties `commonShopList`, `uncommonShopList`, `rareShopList` and `mythicShopList` (plus the legacy `shopList`) list shop **names**, and a rotating shop has a `rotation` list.
- **Selection** (`MapStage.loadObjects`): for each shop object the game rolls 0–99 on the world random. Above 95 it uses the mythic list (restock price 5 shards), above 85 the rare list (4), above 55 the uncommon list (3), and otherwise the common list (2). It then drops names already used in this town (`shopsAlreadyPresent`) and picks one of the remaining names that exist in `world/shops.json` (`WorldData.getShopList()`, plane file first, common otherwise). A rotating shop picks from `rotation` with a seed that changes daily (restock price 7).
  - If **no listed name exists** in shops.json, the object is skipped (`if (shops.size == 0) continue`). The building then has no shop at all. That is what "missing shop" means here.
  - If the chosen list is empty, the game picks among **all** shops of the plane.
  - So the slot names are fixed by the common maps (243 distinct names over the 88 maps reachable from the plane's POIs). They are names, not types: the plane decides what a slot sells.
- **Stock** is generated when the player first opens the shop (`ShopActor.getRewardData`): each `RewardData` in `rewards` is generated with the shop's seed. Card rewards draw `count` cards **with replacement** from `CardUtil.getPredicateResult(allCards, reward)`. `allCards` is the plane pool (`allowedEditions`, restricted cards removed). `colors` means "no colour outside these, and not colourless". `cardText` is a case-insensitive regex on the oracle text. A `Union` merges the pools of its parts. An empty pool simply yields nothing.
- **Special reward types used:** `item` (item shops), `life` (+1 max life, capital item shops), and `cardPackShop`. With `editions` the latter makes a booster of one of those sets through `AdventureOverrides.getBoosterTemplate`. With `colors` it makes a colour booster from **any** set, ignoring `allowedEditions`, so the plane only uses the `editions` form (LTR and HOB; LTC/HOC/MEAR have no boosters). `landSketchbookShop` (Shandalar sketchbooks) is no longer used.
- **The shard trader, inn, arena and smith** are separate object types (`shardtrader`, `inn`, `arena`, `spellsmith`). They don't use shops.json and keep working.
- **Display:** the header is `description` (localizable as `adv.shop.<name>.description`). The sign is `sprite` + `overlaySprite` from `maps/tileset/buildings.atlas`. Every ME shop sets a sign that matches its new content (ElfShop, DwarfShop, WolfShop, InsectSpiderShop…).

## 2. Shop design

The slot names come from Shandalar (colour, guild, shard, tribe and card-type names). Each slot got Middle-earth content built on the real pool:

- **Plain colour slots** (`White`, `Blue`…, the most common slot in every town) are the regional market of that town colour (WP3/WP4 mapping): The White Tree (Gondor), Elven Lore, Shadow of Mordor, Dwarven Market, Shire Market. Each has 3 faction cards (matched by oracle text such as `Gondor|Minas Tirith|…`), 4 cards of the colour and 1 random card.
- **`Creature2X`** (also common) are regional creature halls: Muster of the West (Gondor/Rohan, Soldiers, Knights), Mariners of Lindon (blue Elves and Birds), **Easterling Warband** (black/white Humans, Easterling text), **Haradrim Bazaar** (red/black Humans and Warriors, Harad/Mûmak text, Elephants), Beorn's Hall (Bears and Beasts).
- **Guild slots** (rare) are the factions: Market of the Four Farthings (Shire), Market of Minas Tirith, Stables of Edoras (Rohan), Rivendell Outfitters, Lake-town Traders (Dale/Esgaroth), Iron Hills Smithy, Goblin-town Black Market, Corsairs of Umbar, Easterling Caravan, Mirkwood Webs. Each has 4 faction cards (oracle-text and subtype union) and 4 cards of the two colours.
- **Shard slots** (mythic) are three-colour faction shops weighted to rares: Haradrim War-camp, Dale Market, Riders of the Mark, Pirates of the Anduin, Warg-riders, Blue Mountain Dwarves, Mirkwood Elves, Dol Amroth Harbour, Grey Havens, Woodmen of Rhovanion. `WUBRG` is Treasures of the Elder Days (rares and mythics).
- **Tribal slots** are creature-type shops: Elves, Dwarves (Treasury of Erebor, Iron Hills Armoury in `Minotaur`, Halls of the Blue Mountains in `Dinosaur4Red`), Hobbits (Shire Pantry, Gollum's Cave), Humans/Soldiers (Bree Trader, Tower Guard Barracks, Guards of the Citadel, Esgaroth Garrison), Orcs/Goblins, Wolves/Wargs (×3), Spiders (Shelob's Lair, Spiders of Mirkwood), Treefolk (Ent-moot, Huorns of Fangorn), Wizards (Order of Wizards, Hedge-wizards, Maiar and Valar), Horses/Knights (Riders of Rohan, Eored of the Mark, Black Riders, Mearas Stables, Knights of Dol Amroth), Undead/Spirits (Barrow-downs, Dead Marshes, Oathbreakers, Shadows of the Nine, Fell Beasts), Trolls/Giants, Eagles/Birds, Dragons (Smaug's Hoard), Balrog/Demons (Shadow and Flame, Durin's Bane), Pirates, Rogues/Burglars.
- **Card-type slots** keep their meaning, with ME names: creatures, instants/sorceries, enchantments/sagas, artifacts, equipment (Arms & Armour), legendary (Kings of Men, Lords of the Eldar…), rare/mythic (`Planeswalker*`: Hoard of the Elder Days, The Steward's Vault…), nonbasic lands of a colour, gold cards, Ring-tempts cards (`Phyrexian*`), token makers / amass (`Sliver*`).
- **Shandalar-only slots** (Vehicle, Sliver, Ninja, Eldrazi, Mutants, Attraction/Contraption, Snow, Flip, Gods, Sphinx…) are re-themed rather than left empty: e.g. `AttractionShop` → Bilbo's Birthday Party, `ContraptionShop` → Gandalf's Fireworks, `Sphinx` → Riddles in the Dark, `SnowShop` → Forochel Trading Post.
- **Basic land slots** (`Plains`…) are unlimited shops with the LTR and HOB basics.
- **Booster slots** sell LTR and HOB boosters.
- **Item slots** (`<Colour>Items`, `<Colour>Equipment` in the capitals, `Equipment` in the waste towns, which here are Bree/Eriador) sell the ME items (see 3).

The approximate pool is the number of distinct cards the shop can show. The "Draws" column is `count × pool` per reward. A small pool next to a larger one means the shop shows mostly its theme with a few broader cards. The basic land shops have a pool of 2 by design (the LTR and HOB printing). Every slot reached by the plane's maps exists and every card reward matches at least one card (`check.sh` enforces both). Totals: 250 shops, 229 sell cards, the median pool is about 215 cards, and the smallest themed pools are the five `Vehicle2X` shops (22–38) and Durin's Bane / Maiar and Valar (34–39).

## Shop list

### Faction shops (two-colour slots, rare in towns)

| Slot | Shop | Pool | Draws (count×pool) |
| --- | --- | ---: | --- |
| Selesnya | Market of the Four Farthings | 363 | 4x83 4x328 |
| Azorius | Market of Minas Tirith | 277 | 4x36 4x273 |
| Boros | Stables of Edoras | 312 | 4x26 4x312 |
| Simic | Rivendell Outfitters | 320 | 4x69 4x313 |
| Izzet | Lake-town Traders | 278 | 4x30 4x276 |
| Gruul | Iron Hills Smithy | 378 | 4x79 4x332 |
| Rakdos | Goblin-town Black Market | 370 | 4x156 4x311 |
| Dimir | Corsairs of Umbar | 308 | 4x64 4x262 |
| Orzhov | Easterling Caravan | 284 | 4x73 4x284 |
| Golgari | Mirkwood Webs | 337 | 4x51 4x323 |

### Faction shops (three-colour slots, mythic)

| Slot | Shop | Pool | Draws (count×pool) |
| --- | --- | ---: | --- |
| RWB | Haradrim War-camp | 478 | 3x201 5x478 |
| RWU | Dale Market | 453 | 3x186 5x453 |
| RWG | Riders of the Mark | 504 | 3x211 5x504 |
| RUB | Pirates of the Anduin | 448 | 3x183 5x448 |
| RGB | Warg-riders | 504 | 3x246 5x504 |
| RGU | Blue Mountain Dwarves | 476 | 3x191 5x476 |
| UGB | Mirkwood Elves | 471 | 3x190 5x471 |
| UWB | Dol Amroth Harbour | 425 | 3x176 5x425 |
| UWG | Grey Havens | 480 | 3x228 5x480 |
| GWB | Woodmen of Rhovanion | 485 | 3x233 5x485 |
| WUBRG | Treasures of the Elder Days | 521 | 4x478 2x121 2x142 |

### Colour / regional shops (common in towns)

| Slot | Shop | Pool | Draws (count×pool) |
| --- | --- | ---: | --- |
| White | The White Tree | 1065 | 3x16 4x142 1x1065 |
| Creature2White | Muster of the West | 102 | 4x49 4x84 |
| Creature6White | Citadel Barracks | 542 | 2x542 6x84 |
| Blue | Elven Lore | 1065 | 3x29 4x118 1x1065 |
| Creature2Blue | Mariners of Lindon | 69 | 4x39 4x48 |
| Creature6Blue | Hall of Fire | 542 | 2x542 6x48 |
| Black | Shadow of Mordor | 1065 | 3x111 4x135 1x1065 |
| Creature2Black | Easterling Warband | 133 | 4x73 4x80 |
| Creature6Black | Pits of Barad-dur | 542 | 2x542 6x80 |
| Red | Dwarven Market | 1065 | 3x55 4x152 1x1065 |
| Creature2Red | Haradrim Bazaar | 114 | 4x58 4x97 |
| Creature6Red | Halls of Erebor | 542 | 2x542 6x97 |
| Green | Shire Market | 1065 | 3x60 4x175 1x1065 |
| Creature2Green | Beorn's Hall | 128 | 4x21 4x117 |
| Creature6Green | Woodmen's Lodge | 542 | 2x542 6x117 |
| Creature2Eldrazi | Deeps of Moria | 553 | 6x124 2x542 |
| Colorless | The Prancing Pony | 1065 | 4x48 2x201 2x1065 |
| Creature2Colorless | Rangers of the North | 543 | 6x42 2x542 |

### Rotating shops (capitals, daily stock)

| Slot | Shop | Pool | Draws (count×pool) |
| --- | --- | ---: | --- |
| White1 | Houses of Healing | 112 | 6x29 2x112 |
| White2 | Muster of the Rohirrim | 218 | 6x40 2x218 |
| White3 | Swords of the West | 91 | 6x21 2x91 |
| White4 | Return of the King | 102 | 6x18 2x102 |
| White5 | Oaths of Fealty | 89 | 6x26 2x89 |
| White6 | Beacons of Gondor | 490 | 6x67 2x490 |
| Blue1 | Mirror of Galadriel | 136 | 6x43 2x136 |
| Blue2 | Elven Smithcraft | 148 | 6x19 2x148 |
| Blue3 | Cloaks of Lorien | 193 | 6x30 2x193 |
| Blue4 | Songs of Power | 328 | 6x52 2x328 |
| Blue5 | Scrolls of Rivendell | 311 | 6x65 2x311 |
| Blue6 | Waters of the Bruinen | 485 | 6x44 2x485 |
| Black1 | Certain Death | 202 | 6x45 2x202 |
| Black2 | Barrow-wights' Treasure | 172 | 6x32 2x172 |
| Black3 | Morgul Blades | 100 | 6x56 2x100 |
| Black4 | Whispers of Sauron | 191 | 6x23 2x191 |
| Black5 | Wings of Shadow | 179 | 6x54 2x179 |
| Black6 | Black Breath | 292 | 6x44 2x292 |
| Red1 | Dragonfire | 179 | 6x57 2x179 |
| Red2 | Fury of the Forge | 110 | 6x40 2x110 |
| Red3 | Dwarven Mine | 146 | 6x38 2x146 |
| Red4 | Siege of Erebor | 153 | 6x32 2x153 |
| Red5 | Seized Plunder | 36 | 6x11 2x36 |
| Red6 | Fortunes of War | 66 | 6x11 2x66 |
| Green1 | Second Breakfast | 122 | 6x29 2x122 |
| Green2 | Paths of the Wild | 164 | 6x37 2x164 |
| Green3 | Beorn's Wrath | 103 | 6x29 2x103 |
| Green4 | Old Forest | 122 | 6x52 2x122 |
| Green5 | Cycle of the Seasons | 406 | 6x66 2x406 |
| Green6 | Woodland Bounty | 217 | 6x25 2x217 |

### Card-type shops

| Slot | Shop | Pool | Draws (count×pool) |
| --- | --- | ---: | --- |
| Enchantment4White | Banners & Oaths | 175 | 4x175 4x19 |
| Enchantment6White | Hall of Kings | 175 | 2x175 6x19 |
| Instant4White | Herb-masters of Gondor | 230 | 4x230 4x35 |
| Instant6White | Wisdom of Numenor | 230 | 2x230 6x35 |
| Legend4White | Kings of Men | 245 | 4x245 4x28 |
| Planeswalker4White | The Steward's Vault | 478 | 4x478 4x62 |
| Artifact4White | Smithy of the Citadel | 126 | 6x96 2x33 |
| Land4White | Lands of Gondor | 121 | 4x30 4x121 |
| Multicolor8White | Allies of Gondor | 49 | 8x49 |
| PhyrexianWhite | Burden of the West | 59 | 6x12 2x56 |
| Sliver2White | Muster of Gondor | 215 | 6x37 2x213 |
| Sliver4White | Muster of Gondor | 215 | 6x37 2x213 |
| Wand4White | Beacon-keepers | 117 | 4x96 4x21 |
| Vehicle2White | Wains of Gondor | 24 | 2x16 6x9 |
| Enchantment4Blue | Elven Tapestries | 175 | 4x175 4x14 |
| Enchantment6Blue | Lothlorien Weavers | 175 | 2x175 6x14 |
| Instant4Blue | Lore of the Wise | 230 | 4x230 4x54 |
| Instant6Blue | Library of Rivendell | 230 | 2x230 6x54 |
| Legend4Blue | Lords of the Eldar | 245 | 4x245 4x17 |
| Planeswalker4Blue | Treasures of the Noldor | 478 | 4x478 4x41 |
| Artifact4Blue | Elven Smiths | 101 | 6x96 2x7 |
| Land4Blue | Hidden Valleys | 121 | 4x27 4x121 |
| Multicolor8Blue | Council of Elrond | 48 | 8x48 |
| PhyrexianBlue | Whispers of the Ring | 57 | 6x10 2x56 |
| Sliver2Blue | Elven Host | 214 | 6x13 2x213 |
| Sliver4Blue | Elven Host | 214 | 6x13 2x213 |
| Wand4Blue | Elven Artificers | 108 | 4x96 4x12 |
| Vehicle2Blue | Swan-ships | 23 | 2x16 6x8 |
| Enchantment4Black | Morgul Curios | 175 | 4x175 4x12 |
| Enchantment6Black | Dark Sorceries | 175 | 2x175 6x12 |
| Instant4Black | Poisoner's Den | 230 | 4x230 4x41 |
| Instant6Black | Black Speech | 230 | 2x230 6x41 |
| Legend4Black | Lords of the Shadow | 245 | 4x245 4x20 |
| Planeswalker4Black | Hoard of the Dark Lord | 478 | 4x478 4x48 |
| Artifact4Black | Forges of Mordor | 110 | 6x96 2x17 |
| Land4Black | Lands of Shadow | 121 | 4x34 4x121 |
| Multicolor8Black | Pacts of the Enemy | 53 | 8x53 |
| PhyrexianBlack | The Ring's Temptation | 74 | 6x30 2x56 |
| Sliver2Black | Armies of Mordor | 215 | 6x32 2x213 |
| Sliver4Black | Armies of Mordor | 215 | 6x32 2x213 |
| Wand4Black | Siege-works of Mordor | 128 | 4x96 4x34 |
| Vehicle2Black | Black Ships | 22 | 2x16 6x7 |
| Enchantment4Red | Fires of the Forge | 175 | 4x175 4x15 |
| Enchantment6Red | Durin's Legacy | 175 | 2x175 6x15 |
| Instant4Red | Burning Words | 230 | 4x230 4x36 |
| Instant6Red | Blasting Powder | 230 | 2x230 6x36 |
| Legend4Red | Lords under the Mountain | 245 | 4x245 4x22 |
| Planeswalker4Red | Arkenstone Hoard | 478 | 4x478 4x53 |
| Artifact4Red | Dwarven Smithy | 131 | 6x96 2x39 |
| Land4Red | Mountain Holds | 121 | 4x27 4x121 |
| Multicolor8Red | Allies of Durin's Folk | 53 | 8x53 |
| PhyrexianRed | Dragon-sickness | 72 | 6x22 2x56 |
| Sliver2Red | March of the Longbeards | 213 | 6x36 2x213 |
| Sliver4Red | March of the Longbeards | 213 | 6x36 2x213 |
| Wand4Red | Dwarven Toymakers | 131 | 4x96 4x35 |
| Vehicle2Red | Mine Carts | 38 | 2x16 6x23 |
| Enchantment4Green | Old Forest Lore | 175 | 4x175 4x15 |
| Enchantment6Green | Gardens of Bag End | 175 | 2x175 6x15 |
| Instant4Green | Herbalist's Garden | 230 | 4x230 4x40 |
| Instant6Green | Ent-draughts | 230 | 2x230 6x40 |
| Legend4Green | Heroes of the Shire | 245 | 4x245 4x22 |
| Planeswalker4Green | Radagast's Treasures | 478 | 4x478 4x63 |
| Artifact4Green | Hobbit Workshop | 119 | 6x96 2x24 |
| Land4Green | Green Hills | 121 | 4x28 4x121 |
| Multicolor8Green | Friends of the Shire | 49 | 8x49 |
| PhyrexianGreen | Shadow on the Shire | 76 | 6x26 2x56 |
| Sliver2Green | Gathering of the Ents | 213 | 6x34 2x213 |
| Sliver4Green | Gathering of the Ents | 213 | 6x34 2x213 |
| Wand4Green | Garden Tools | 134 | 4x96 4x40 |
| Vehicle2Green | Farmer's Carts | 28 | 2x16 6x12 |
| Creature | Hiring Hall | 542 | 8x542 |
| Instant | Tricks of the Trade | 230 | 8x230 |
| Enchantment | Songs and Sagas | 79 | 5x79 3x22 |
| Saga | Red Book of Westmarch | 93 | 6x36 2x79 |
| Artifact | Tinker's Stall | 96 | 8x96 |
| Wand | Wizard's Workshop | 96 | 6x38 2x96 |
| Equip | Arms & Armour | 63 | 6x37 2x63 |
| Vehicle | Wains & Waggons | 128 | 4x38 4x96 |
| Land | Wandering Cartographer | 121 | 8x121 |
| Land4Colorless | Roads of Eriador | 121 | 4x121 4x121 |
| Multicolor | Free Peoples' Exchange | 142 | 8x142 |
| Legend | Heroes of the Third Age | 245 | 8x245 |
| Planeswalker | Hoard of the Elder Days | 478 | 6x478 2x176 |
| PhyrexianColorless | Rings of Power | 133 | 6x60 2x78 |
| Sliver | Fellowship Muster | 650 | 6x261 2x542 |
| Everything | General Store of Bree | 1065 | 2x319 2x258 2x542 1x121 1x478 |
| Battle | Battlefields of the Third Age | 323 | 6x219 2x113 |
| SnowShop | Forochel Trading Post | 292 | 4x184 4x118 |
| FlipShop | Two-faced Bargains | 1065 | 6x41 2x1065 |
| Sphinx | Riddles in the Dark | 278 | 6x216 2x118 |
| AttractionShop | Bilbo's Birthday Party | 1065 | 6x90 2x1065 |
| ContraptionShop | Gandalf's Fireworks | 203 | 6x118 2x96 |

### Basic land shops (unlimited)

| Slot | Shop | Pool | Draws (count×pool) |
| --- | --- | ---: | --- |
| Plains | The Cartographer of Gondor | 2 | 4x1 4x1 |
| Island | The Cartographer of Lindon | 2 | 4x1 4x1 |
| Swamp | The Cartographer of Mordor | 2 | 4x1 4x1 |
| Mountain | The Cartographer of Erebor | 2 | 4x1 4x1 |
| Forest | The Cartographer of the Shire | 2 | 4x1 4x1 |

### Booster shops

| Slot | Shop | Packs |
| --- | --- | --- |
| BoosterPackShop | Pack Peddler | 8× LTR/HOB |
| AlphaBoosterPackShop | Tales of Middle-earth | 8× LTR |
| BetaBoosterPackShop | There and Back Again | 8× HOB |
| GammaBoosterPackShop | Pack Peddler of the West | 8× LTR/HOB |
| DeltaBoosterPackShop | Red Book Boosters | 8× LTR/HOB |
| WhiteBoosterPackShop | Boosters of the West | 4× LTR + 4× HOB |
| BlueBoosterPackShop | Elven Packs | 4× LTR + 4× HOB |
| BlackBoosterPackShop | Packs from the Dark | 4× LTR + 4× HOB |
| RedBoosterPackShop | Dwarven Packs | 4× LTR + 4× HOB |
| GreenBoosterPackShop | Shire Packs | 4× LTR + 4× HOB |

### Item and equipment shops

| Slot | Shop | Sells |
| --- | --- | --- |
| WhiteItems | Stores of Minas Tirith | White rune, White Staff, Horn of Gondor, Palantir, Life Amulet, Spell Book + 1 life |
| WhiteEquipment | Armoury of the Citadel | Anduril, Gold Armor, Gold Shield, Kite Shield, Rohirrim Shield, Steel Sword |
| BlueItems | Treasures of Imladris | Blue rune, Blue Staff, Phial of Galadriel, Lembas, Evenstar, Elfstone + 1 life |
| BlueEquipment | Elven Smithy | Glamdring, Bow of the Galadhrim, Elven Cloak, Mithril Coat, Mithril Boots, Traveler's Amulet |
| BlackItems | Black Market of Umbar | Black rune, Black Staff, The One Ring, Cursed Ring, Death Ring, Treasure + 1 life |
| BlackEquipment | Corsair Armoury | Dark Armor, Dark Boots, Dark Shield, Dark Amulet, Mad Staff, Barrow-blade |
| RedItems | Vaults of Erebor | Red rune, Red Staff, The Arkenstone, Thror's Map, Dungeon Map, Fortune Coin + 1 life |
| RedEquipment | Forge of Durin | Dwarven Axe, Orcrist, The Black Arrow, Axt, Heavy Arbalest, Jandor's Ring |
| GreenItems | Hobbiton Provisions | Green rune, Green Staff, Longbottom Leaf, Ent-draught, Snack, Hobbit Feet + 1 life |
| GreenEquipment | Bag End Smials | Sting, Frying Pan, Unerring Sling, Wood Bow, Ranger Boots, Ranger Blade |
| Equipment | Bree Outfitter | Steel Boots, Steel Shield, Steel Armor, Iron Boots, Iron Shield, Iron Armor, Wizard Staff |

### Creature-type shops

| Slot | Shop | Pool | Draws (count×pool) |
| --- | --- | ---: | --- |
| Elf | Elven Market | 549 | 6x74 2x542 |
| Faerie | Silvan Elves | 327 | 6x74 2x262 |
| Dwarf | Treasury of Erebor | 546 | 6x52 2x542 |
| Minotaur | Iron Hills Armoury | 220 | 6x98 2x152 |
| Dinosaur4Red | Halls of the Blue Mountains | 202 | 6x89 2x152 |
| Dinosaur4Green | Mumakil Pens | 213 | 6x61 2x175 |
| Dinosaur | Great Beasts of the Wild | 552 | 6x61 2x542 |
| Human | Bree Trader | 543 | 6x109 2x542 |
| Human4White | Men of the West | 212 | 4x109 4x142 |
| Soldier | Tower Guard Barracks | 556 | 6x139 2x542 |
| Soldier4White | Guards of the Citadel | 178 | 4x60 4x142 |
| Soldier4Red | Esgaroth Garrison | 234 | 4x120 4x152 |
| Knight | Knights of Dol Amroth | 552 | 6x54 2x542 |
| Knight4White | Riders of Rohan | 164 | 4x34 4x142 |
| Knight4Red | Eored of the Mark | 211 | 4x93 4x152 |
| Knight4Black | Black Riders | 165 | 4x43 4x135 |
| SmallCats | Mearas Stables | 552 | 6x36 2x542 |
| Goblin | Goblin-town Scavengers | 592 | 6x147 2x542 |
| Ogre | Troll-shaws | 574 | 6x59 2x542 |
| Ogre4Red | Stone Trolls | 197 | 4x80 4x152 |
| Giant | Stone-giants of the Misty Mountains | 570 | 6x55 2x542 |
| Wolf | Wargs of the Wild | 542 | 6x25 2x542 |
| Wolf4Red | Warg Kennels | 174 | 4x24 4x152 |
| Wolf4Green | Wolves of Rhovanion | 189 | 4x31 4x175 |
| Spiders | Shelob's Lair | 542 | 6x16 2x542 |
| Insects | Spiders of Mirkwood | 201 | 6x50 2x175 |
| Druid | Ent-moot | 547 | 6x40 2x542 |
| Hydra | Huorns of Fangorn | 548 | 6x35 2x542 |
| Wizard | Order of Wizards | 547 | 6x35 2x542 |
| Shaman | Hedge-wizards | 547 | 6x56 2x542 |
| Gods | Maiar and Valar | 34 | 8x34 |
| Skeleton | Barrow-downs | 544 | 6x34 2x542 |
| Zombie | Dead Marshes | 602 | 6x129 2x542 |
| Spirit4White | Oathbreakers | 174 | 4x38 4x142 |
| Spirit4Blue | Spirits of the West | 141 | 4x26 4x118 |
| Vampire | Fell Beasts | 544 | 6x34 2x542 |
| Demon | Shadow and Flame | 542 | 6x43 2x542 |
| Devil | Udun Forge | 183 | 6x50 2x152 |
| Dragon | Smaug's Hoard | 559 | 6x53 2x542 |
| Angel | Eagles of Manwe | 215 | 6x90 2x142 |
| Bird | Great Eagles | 545 | 6x27 2x542 |
| Bird4White | Eyrie of the Eagles | 215 | 4x90 4x142 |
| Bird4Blue | Crebain from Dunland | 196 | 4x90 4x118 |
| Bird4Azorius | Eagles of the Misty Mountains | 332 | 4x90 4x273 |
| Merfolk | Anduin Ferrymen | 126 | 6x18 2x118 |
| LargeSeaCreatures | Watcher in the Water | 119 | 4x8 4x118 |
| Pirate | Corsair Docks | 556 | 6x50 2x542 |
| Pirate4Blue | Havens of Umbar | 164 | 4x50 4x118 |
| Rogue | Burglars & Rogues | 549 | 6x75 2x542 |
| Rogue4Blue | The Burglar's Guild | 216 | 4x107 4x118 |
| Rogue4Black | Spies of Isengard | 194 | 4x69 4x135 |
| Assassin | Knives in the Dark | 573 | 6x78 2x542 |
| Ninja | Shadows of the Nine | 544 | 6x31 2x542 |
| Squirrel | Shire Pantry | 556 | 6x77 2x542 |
| Nobles | Court of the King | 556 | 6x96 2x542 |
| Horrors | Nameless Things | 543 | 6x24 2x542 |
| Mutants | Gollum's Cave | 568 | 6x98 2x542 |
| Eldrazi | Durin's Bane | 39 | 8x39 |
| Shapeshifter | Skin-changers | 552 | 6x39 2x542 |
| Shapeshifters | Beornings | 552 | 6x39 2x542 |
| Golem | Stone Wardens | 550 | 6x15 2x542 |
| Wall | Walls of Helm's Deep | 546 | 6x11 2x542 |
| Assembly | Toymakers of Dale | 595 | 6x129 2x542 |

## 3. Items

`ItemListData` loads **one** items file (`Config.getFile("world/items.json")`: the plane file *replaces* common, it doesn't extend it). The plane file therefore has to hold every item the plane can ask for. `check.sh` now collects these names and fails on any that are missing:

- **Engine names:** `Challenge Coin`, `Silver Challenge Coin`, `Bronze Challenge Coin` (EventScene / AdventureEventData) and the HUD keys `Red/Green/Blue/Black/White Key` and `Strange Key` (GameHUD).
- **config.json `startItems`:** `Manasight Amulet`, `Leather Boots`.
- **Maps** reachable from the plane's POIs (reward objects, dialog `addItem`/`removeItem`/conditions, arena rewards): about 40 names. Examples: the colour keys and coins in `spawn.tmx`, the capitals' arena prizes (Gold Armor/Boots/Shield, Axt, Bronze Sword, Dagger, Jandor's Ring…), the Moria multilevel cave's `First`–`Fifth Shard`, Barad-dûr's Basilisk Collar / Evil Ankh / Staff of the Demoncaller.
- **Enemies used by the biomes** (`equipment` and item rewards): only `Steel Armor` today.
- **Rune teleports** (`commandOnUse: teleport to poi "<POI>"`): the target POI must exist.

**Kept from common (80 items)**, by exact name, with Middle-earth descriptions where the description is free text. These are the runes (White rune → Minas Tirith, Blue → Rivendell, Black → Umbar, Red → Erebor, Green → Hobbiton, Colorless → Wizard's Hollow), the five map staffs, keys, coins, shards, the map-referenced gear, and generic gear sold in shops (Iron/Steel/Gold armour and boots, Kite Shield, Wood Bow, Heart-Piercer, Unerring Sling, Spell Book, Life Amulet, Mithril Boots, Cursed Ring, Snack/Charm/Change, Fortune Coin, Robes of Omniscience). Effects are unchanged, with one fix: `Change` pointed at the token `c_a_gold_draw`, which doesn't exist, and now uses `c_a_gold_sac`.

**Dropped:** Shandalar-specific loot that no ME map, quest or enemy references (planeswalker-lair items such as Chandra's Tome, Kiora's Bident, Teferi's Staff, Sorin's Amulet; the Moxen, Black Lotus and Sol Ring; Cheat; 42 Landscape Sketchbooks; Sir Donovan's Amulet). If WP5/WP6 reuse a common enemy that drops one of these, `check.sh` will report it.

**New Middle-earth items (27)**, all with existing effect fields only (`lifeModifier`, `changeStartCards`, `moveSpeed`, `colorView`, `startBattleWithCard`, `opponent`). Wherever possible the battlefield card is the actual LTR/HOB/HOC card. Icons are existing `sprites/items.atlas` regions.

| Item | Slot | Icon | Cost | Effect |
| --- | --- | --- | ---: | --- |
| Sting (unique) | Left | Dagger | 4000 | starts with Sting, the Glinting Dagger |
| Mithril Coat (unique) | Body | MithrilArmor | 15000 | life +5 |
| Elven Cloak (unique) | Body | Conjurer's Mantle | 4000 | life +2; speed +10% |
| Lembas | Right | Clue | 2500 | life +1; starts with Lembas |
| Phial of Galadriel (unique) | Neck | TravelersAmulet | 8000 | starts with Phial of Galadriel |
| Anduril (unique) | Left | FlameSword | 14000 | life +1; starts with Andúril, Flame of the West |
| Horn of Gondor (unique) | Right | PipersCharm | 7000 | starts with Horn of Gondor |
| Dwarven Axe | Left | GarrukAxe | 3500 | starts with Gimli's Axe |
| Ent-draught | Right | Status | 5000 | life +3 |
| Palantir (unique) | Right | ChickenEgg | 10000 | life -2; hand +1; manasight |
| The One Ring (unique) | Neck | RingofRenewal | 30000 | life -3; starts with The One Ring; enemy: starts with Nazgûl |
| Glamdring (unique) | Left | SteelSword | 9000 | starts with Glamdring, Foe-hammer |
| Orcrist (unique) | Left | BronzeSword | 9000 | starts with Orcrist, Goblin-cleaver |
| Barrow-blade | Left | Dagger | 2000 | starts with Barrow-Blade |
| Bow of the Galadhrim (unique) | Left | CompositeBow | 3000 | starts with Galadhrim Bow |
| The Arkenstone (unique) | Right | Treasure | 25000 | life -2; starts with The Arkenstone |
| Thror's Map (unique) | Right | DungeonMap | 3000 | starts with Thrór's Map |
| The Black Arrow (unique) | Left | HeavyArbalest | 5000 | starts with The Black Arrow |
| Elfstone (unique) | Neck | MoxEmerald | 6000 | life +2; manasight |
| Evenstar (unique) | Neck | MoxPearl | 8000 | life +4 |
| Longbottom Leaf | Right | GreenLeaf | 1500 | life +1; starts with c_a_food_sac |
| Frying Pan (unique) | Left | FarmersTools | 2500 | starts with Field-Tested Frying Pan |
| Hobbit Feet (unique) | Boots | Sandals | 800 | life +1; speed +15% |
| Ranger Boots (unique) | Boots | MetallicBoots | 6000 | speed +25%; starts with Trailblazer's Boots |
| Ranger Blade | Left | BronzeSword | 2500 | starts with Dúnedain Blade |
| Rohirrim Shield | Right | KiteShield | 3500 | life +2 |
| Wizard Staff (unique) | Left | WildMagusStaff | 5000 | starts with Wizard's Staff |

**Where they are sold** (the same way Shandalar does it: capital item and equipment shops, plus `Equipment` in the colourless towns):

| Slot | Shop | Items |
| --- | --- | --- |
| WhiteItems | Stores of Minas Tirith | White rune, White Staff, Horn of Gondor, Palantir, Life Amulet, Spell Book + 1 max life |
| WhiteEquipment | Armoury of the Citadel | Anduril, Gold Armor, Gold Shield, Kite Shield, Rohirrim Shield, Steel Sword |
| BlueItems | Treasures of Imladris | Blue rune, Blue Staff, Phial of Galadriel, Lembas, Evenstar, Elfstone + 1 max life |
| BlueEquipment | Elven Smithy | Glamdring, Bow of the Galadhrim, Elven Cloak, Mithril Coat, Mithril Boots, Traveler's Amulet |
| BlackItems | Black Market of Umbar | Black rune, Black Staff, The One Ring, Cursed Ring, Death Ring, Treasure + 1 max life |
| BlackEquipment | Corsair Armoury | Dark Armor, Dark Boots, Dark Shield, Dark Amulet, Mad Staff, Barrow-blade |
| RedItems | Vaults of Erebor | Red rune, Red Staff, The Arkenstone, Thror's Map, Dungeon Map, Fortune Coin + 1 max life |
| RedEquipment | Forge of Durin | Dwarven Axe, Orcrist, The Black Arrow, Axt, Heavy Arbalest, Jandor's Ring |
| GreenItems | Hobbiton Provisions | Green rune, Green Staff, Longbottom Leaf, Ent-draught, Snack, Hobbit Feet + 1 max life |
| GreenEquipment | Bag End Smials | Sting, Frying Pan, Unerring Sling, Wood Bow, Ranger Boots, Ranger Blade |
| Equipment | Bree Outfitter | Steel Boots, Steel Shield, Steel Armor, Iron Boots, Iron Shield, Iron Armor, Wizard Staff |

**The One Ring** (Neck, 30,000 gold, unique, sold in Umbar's black market): life −3, you start every duel with the HOC card *The One Ring* (indestructible; `{1},{T}`: add a burden counter and draw a card per burden counter; you lose 1 life per burden counter each upkeep), and **every enemy starts with a Nazgûl** (LTR, 1/2 deathtouch, which makes the Ring tempt *them*). So it's a strong card-draw engine that slowly kills you and brings a Ringwraith into every fight. Verified in a duel (screenshot `.me-test/wp7-duel-onering.png`: Sting, Lembas and The One Ring on the player's side, Nazgûl on the enemy's, player life 12 − 3 + 1 = 10). Like any equipped item it can crack on defeat (engine default).
The item system has no real "can't unequip" curse or ongoing overworld drawback. A stronger version would need an engine change, for example an `EffectData` flag that blocks unequipping, or `goldModifier`/`moveSpeed` drawbacks that the inventory shows as curses. The data could also add a `startBattleScript` line, such as an emblem "at the beginning of your upkeep the Ring tempts you", if wanted.

## 4. Heroes

`heroes.json` = `{ "avatar": <atlas with portrait regions>, "heroes": [ {name, male, female, maleAvatar, femaleAvatar} ] }`. `male`/`female` are walking-sprite atlases. `CharacterSprite` accepts both the directional hero atlases (`WalkRight`…) and the non-directional enemy atlases (`Walk`, `Idle`), which it mirrors. The portrait is a region name in the single `avatar` atlas. The player cycles through all regions of that name. The race label is `lbl<name>` if localized, otherwise the name.

Innistrad and the other planes simply copy common's list (Shandalar races). Amonkhet has no heroes file and falls back to common.

| Race | Walking sprite (m / f) | Portraits |
| --- | --- | --- |
| Hobbit | `enemy/humanoid/human/peasant/farmer` / `peasant2` (small hooded farmer folk) | Human_m / Human_f |
| Man | `heroes/human_m` / `human_f` | Human_m / Human_f |
| Elf | `heroes/elf_m` / `elf_f` | Elf_m / Elf_f |
| Dwarf | `heroes/dwarf_m` / `dwarf_f` | Dwarf_m / Dwarf_f |
| Wizard | `enemy/humanoid/human/wizard/wizard` (grey-blue robe, white beard) / `white_wiz2` | Human_m / Human_f |

No new art. The hobbit and wizard walking sprites are enemy sprites without directional or attack frames, so they look simpler than the hero sprites. Hobbits and wizards share the human portraits; the Human_m set includes a grey-bearded old man. Checked in game: the race list shows the 5 races, and the Hobbit (farmer sprite) walks the overworld and towns.

## 5. Tests

- `tools/middleearth/check.sh` → **0 errors, 743 warnings**, the same warning count as before WP7 (all of them are WP5/6 enemy backlog). New section 6 in `PlaneCheck.java`:
  - walks the `.tmx` of every POI and every map their entries/portals teleport to (templates resolved). **ERROR** for a requested shop name that isn't in shops.json.
  - for every shop: sign and overlay sprites exist in `buildings.atlas`; **ERROR** if a card reward matches no card in the plane pool; WARN if a reward draws more cards than its pool holds (below 6); `cardName` + `editions` must resolve; `cardPackShop` editions need a booster template, and colour boosters are flagged; `item` rewards must exist. `-v` lists every shop with its pool size.
  - items: every name referenced by the engine, config start items, maps, quests and the biomes' enemies must exist (**ERROR**); icons exist in `items.atlas`; every `startBattleWithCard`/command-zone card resolves (card, token or adventure custom card); rune teleport targets are existing POIs.
  - heroes: sprite atlases exist and have a walk animation; portrait regions exist in the avatar atlas.
  - Cross-check: `check.sh Innistrad` reports Innistrad's real gaps (missing `Werewolf` shop, 20+ shops with empty pools), so the checks fire.
- GUI (`game.sh`, DISPLAY_NUM=104), screenshots in `.me-test/` (gitignored):
  - `wp7-newgame.png`, `wp7-races.png`: character creation with Hobbit / Man / Elf / Dwarf / Wizard.
  - `wp7-ow-zoom.png`: Hobbit hero on the overworld.
  - `wp7-hobbiton.png`: Hobbiton (Forest Capital) via `teleport to poi "Forest Capital"`.
  - `wp7-shop1.png`: rotating card shop "Paths of the Wild" in Hobbiton: 8 cards, all from LTC/HOC/HOB/MEAR.
  - `wp7-shop-equipment.png`: "Bag End Smials" (GreenEquipment) selling Sting, Frying Pan, Ranger Boots, Ranger Blade, Wood Bow, Unerring Sling.
  - `wp7-inventory.png`, `wp7-onering2.png`: inventory with the new items (`give item "<name>"`, quotes needed for names with spaces); The One Ring's description.
  - `wp7-duel-onering.png`: duel start with Sting, Lembas, The One Ring and the enemy's Nazgûl.
  - No exceptions from shops, items or heroes. One unrelated exception, see open questions.

## 6. Open questions / follow-ups

1. **Quest board NPE in the capitals (not WP7):** walking into Hobbiton's job board ("?" building) throws `NullPointerException … AdventureQuestData.<init>` in `AdventureQuestController.getQuestNPCResponse`. The plane's `quests.json` (WP3) seems to lack quests that the capital job boards draw from. It needs a look by whoever owns quests.
2. **Item display names:** the rune names (`White rune`…) and other map-referenced items must keep their names. The engine supports `adv.item.<Key>.displayName` in `MiddleEarth/languages/adventure-en-US.properties` (WP3's file), e.g. `adv.item.Whiterune.displayName=Rune of Minas Tirith`. I didn't touch that file. A similar key could localize the race names (`lblHobbit`, `lblWizard`).
3. **The One Ring as a reward:** it is sold in the Umbar black market for now. It would fit better as a unique reward (Gollum, Mount Doom, a quest), which belongs to WP5/Phase 3. Stronger curse mechanics need an engine change (see 3).
4. **Hero art:** a proper hobbit/wizard sprite set (directional frames) and plane-local portrait atlas would need new art or a new atlas file under `MiddleEarth/sprites/heroes/` (outside WP7's file list).
5. **WP5/WP6:** new enemies may reference common items that the plane dropped. `check.sh` reports them. Prefer the ME items listed above for drops (e.g. Barrow-blade from barrow-wights, Thror's Map/Arkenstone from Erebor bosses, Orcrist/Glamdring from the Trollshaws).
6. Shops with Union filters are counted as a whole. A Union part that is empty doesn't fail the check.
7. Unused shops `Black1`–`Black6` (no swamp map has a rotating shop) and `Sliver4Blue` are kept for maps that may use them later.
