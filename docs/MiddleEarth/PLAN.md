# Middle-earth Adventure: Plan & Status

> **Living document. Update it with every change**: tick off tasks, adjust the plan, and add a line to the change log.
> How to build, test and find your way around the code: [DEVELOPMENT.md](DEVELOPMENT.md).

**Goal:** a Middle-earth adventure plane for Forge's Adventure mode, using the Lord of the Rings and The Hobbit card sets, with Middle-earth regions, enemies, towns and a story.
**Scope:** stays in this fork (Tolkien IP). It will not be announced or upstreamed.

## Decisions

| # | Decision | Date |
| --- | --- | --- |
| D1 | Card pool: `LTR`, `LTC`, `PLTC` (Deluxe Commander Kit), `HOB`, `HOC`, plus the custom reprint set `MEAR` (D5). Other Middle-earth printings are welcome when they exist as their own edition. Secret Lair LotR cards are in the shared `SLD` set and so are excluded for now. | 2026-11-13 |
| D2 | Content is built on the **random world generator first**. The fixed Tiled world (Phase 2) can be developed in parallel by a separate agent. | 2026-11-13 |
| D3 | The plane stays in the fork and isn't published. Engine changes are kept minimal and isolated so that upstream merges stay cheap. | 2026-11-13 |
| D4 | Edition restriction uses upstream's native `allowedEditions` support. The fork's earlier custom `legalCards.editions` Java code was dropped during the upstream sync. | 2026-11-13 |
| D5 | A **custom reprint set** (Forge `Type=Custom` edition) adds existing non-LotR cards that fit Middle-earth (wolves, spiders, eagles, treefolk, trolls, horses, wizards…). No new artwork: each reprint reuses a fitting existing printing's art. The goal is a good Middle-earth experience, not strict LTR-only. | 2026-11-13 |
| D6 | New decks are tested **AI vs AI**. Starter decks should be about as strong as the official LTR Starter Kit decks, not stronger. Enemy decks get a strength rating that matches their spawn difficulty. | 2026-11-13 |
| D7 | Upstream enemy decks that fit Middle-earth thematically can be reused with small card swaps (off-theme or off-pool cards replaced) instead of being built from scratch. | 2026-11-13 |
| D8 | Custom (new) cards are deferred to Phase 3/4. | 2026-11-13 |
| D9 | Strength targets (from WP1). 40-card starter decks are rated with `--g40` against 40-card cuts of the two LTR Starter Kit decks (Gondor GW, Mordor BR), target **45–55%** (on-par band 42–58%). Enemy decks are rated with `--enemy-life`: <45% easy, 45–60% normal, 60–75% hard, >75% elite/boss (rough calibration). Upstream difficulty comes mostly from enemy `life`, not the deck. | 2026-11-13 |
| D10 | MEAR follow-up (user): keep all 223 cards. Original dual lands and shock lands may be added. Prefer Tolkien names: cards whose names clearly don't fit get a Tolkien **flavor name**, using Forge's existing per-printing `${"flavorName": ...}` support (rules identity unchanged), or are swapped out. Tokens should get fitting art where it's cheap; small engine changes are OK, but avoid heavy workarounds. | 2026-11-14 |

## Current status (after Wave 1: WP1, WP2 and WP3 merged)

`tools/middleearth/check.sh` → **0 errors, 743 warnings**. Almost all warnings are unknown or off-pool enemies, which Wave 2 replaces. `game.sh smoke` → new game, ME intro and overworld work. `WorldSim` → 0 placement reruns and 0 unreachable POIs over 20 seeds.

| Area | State |
| --- | --- |
| Card pool | ✅ 1,067 unique cards: LTR, LTC, HOB, HOC, PLTC and the custom reprint set MEAR (223 cards). |
| World layout | ✅ 900×900 world with 43 biomes placed against the LotR map. Start in the Shire. Waste is down from about 40% to about 3%. No rivers or inland seas (needs Phase 2). |
| Towns / POIs | ✅ 90 POIs (about 97 placed per world). Named towns and dungeons sit at their map positions. Middle-earth town name lists per region. |
| Intro | ✅ "Welcome to Middle-earth" quest and tutorial. The wizard dialog is overridden through `MiddleEarth/languages/adventure-en-US.properties` (en-US only; the speaker title still reads "Adept Black Wizard"). |
| Starter decks | ❌ Common (Shandalar) decks, about 60% off-pool (WP4). |
| Enemies | ❌ Shandalar lists, partly with names that no longer exist (WP5/WP6, then Wave 3). |
| Shops / items / heroes | ✅ 250 ME shops (all 243 shop names requested by the plane's maps exist), 107 items incl. 27 ME items (Sting, Mithril Coat, Andúril, The One Ring…), races Hobbit/Man/Elf/Dwarf/Wizard (reused sprites). |
| Fixed Tiled world | ❌ Not started (Phase 2). |

World after WP3 (generated, north is up):

![world map after WP3](img/worldmap-wp3.png)

Baseline before Phase 1: [worldmap-baseline.png](img/worldmap-baseline.png)

## Phases

### Phase 0: Baseline and test setup ✅

- [x] Sync with upstream Card-Forge/forge (Hobbit sets, `allowedEditions`, new planes).
- [x] Switch the plane config to `allowedEditions` and add HOB, HOC and PLTC.
- [x] Fix `config.json` for the current schema (`staringMoney` → `startingMoney`). Before this fix the plane crashed on start.
- [x] Fix the biome data: `south_gondor` had the name `northern_waste` and was listed twice in `world.json`.
- [x] Reproducible build in the cloud sandbox (`game.sh build`).
- [x] Headless data checker (`check.sh` / `PlaneCheck.java`).
- [x] Headless GUI driver and smoke test (`game.sh smoke`: Xvfb, xdotool, console).
- [x] `exportMap` console command for full-world images.
- [x] Docs: this plan and DEVELOPMENT.md.
- [x] Verified that issue #1 (printings from wrong sets) is solved by upstream's implementation. `check.sh` guards it.

### Phase 1: Middle-earth content on the random generator

Use `Innistrad/` as the template. Phase 1 is split into work packages (WPs). Each WP is done by one agent in its own git worktree and branch, and the coordinator merges them. Every WP touches its own set of files (listed under "Owns"). An agent writes its report to `docs/MiddleEarth/notes/<wp>.md`. Only the coordinator edits this plan.

**Wave 1 (parallel)**

| WP | Task | Owns | Status |
| --- | --- | --- | --- |
| WP1 deck-lab | AI-vs-AI strength harness built on Forge `sim` mode (`tools/middleearth/deckstrength.sh`). Reference gauntlet of the official LTR Starter Kit decks. Strength rating for decks. | `tools/middleearth/deck*`, `forge-gui/res/adventure/MiddleEarth/decks/reference/`, `notes/wp1-deck-lab.md` | ✅ ([notes](notes/wp1-deck-lab.md)) |
| WP2 reprint-set | Custom Middle-earth reprint edition (art reuse mechanism, card selection), added to `allowedEditions`, checker support. | `forge-gui/res/editions/<custom>.txt` (or the plane-local equivalent), `config.json` → `allowedEditions` only, `notes/wp2-reprint-set.md` | ✅ MEAR, 223 cards ([notes](notes/wp2-reprint-set.md)) |
| WP3 world-layout | Retune biome positions and sizes against the real map, shrink the waste, fix the start area. ME towns and POIs (names, Erebor town, dungeons from existing `.tmx`), town name lists, intro text. | `world/world.json`, biome fields other than `enemies`, `world/points_of_interest.json`, `world/town_names_*.txt`, intro/quest texts, `notes/wp3-world-layout.md` | ✅ 43 biomes, 90 POIs, ME intro ([notes](notes/wp3-world-layout.md)) |

**WP2b mearfix (parallel to Wave 2):** MEAR follow-up per D10: Tolkien flavor names, dual and shock lands, token art.

**Wave 2 (after WP1 and WP2)**

| WP | Task | Owns | Status |
| --- | --- | --- | --- |
| WP4 starter-decks | 5 colours × starter/constructed/pile × difficulty, matched to the reference strength. W = Gondor/Rohan, U = Elves, B = Mordor, R = Dwarves, G = Hobbits/Ents. | `MiddleEarth/decks/starter/`, `config.json` → `difficulties[*].*Decks` |
| WP5 enemies-shadow | Mordor, Isengard, Moria, Nazgûl, trolls, orcs/goblins, Easterlings, Haradrim, Corsairs, Balrog, Smaug, Shelob. Reuse upstream decks where they fit (D7). | `MiddleEarth/world/enemies.json` (shadow section), `MiddleEarth/decks/enemy/shadow/` |
| WP6 enemies-free | Bandits/ruffians, Dunlendings, wolves/wargs, spiders, Huorns, Barrow-wights, Gollum, Saruman, plus allied duelists (rangers, elves, dwarves). | `MiddleEarth/decks/enemy/free/` and an enemies fragment that the coordinator merges |
| WP7 shops-items-heroes | Regional and faction card shops, ME items (Sting, mithril shirt, elven cloak, phial, the One Ring), hero avatars (hobbit, human, elf, dwarf, wizard). | `world/shops.json`, `world/items.json`, `world/heroes.json`, sprites | ✅ 250 shops, 107 items (27 ME), 5 races ([notes](notes/wp7-shops-items-heroes.md)) |

**Wave 3 (integration):** fill the biome `enemies` lists with ME enemies per region (all 43 biomes; the 15 biomes added in WP3 hold verbatim copies of their parent region's Shandalar list), run a full smoke run, and play-test.
### Phase 2: Fixed Tiled world (issue #2), can run in parallel

The approach from issue #2: load the Tiled world into the existing `World` data structures instead of abstracting `World`/`WorldStage`.

1. Investigate what `World.generateNew` produces (biome map, terrain, POI positions, roads, minimap) and what `WorldStage` consumes.
2. Add an opt-in `world.json` field (for example `"tiledWorld": "maps/world/middleearth/middleearth.world"`) that creates a `World` from Tiled layers: a biome or terrain layer, plus an object layer for POIs and the start position.
3. Keep random generation as the default. Keep the engine diff small (see "Engine changes").
4. Draw out the full Middle-earth map in Tiled. The Gondor and Mordor regions already exist.

### Phase 3: Story and quests

- A main quest along the Fellowship's route (Shire → Bree → Rivendell → Moria → Lothlórien → Rohan/Gondor → Mordor).
- Side quests per region. Possibly a separate *The Hobbit* storyline (Erebor, Smaug).
- Bosses with unique rewards. The One Ring is a cursed item.

## Engine changes in this fork

Keep this list complete, because it is what makes upstream merges predictable.

| Change | Files | Why |
| --- | --- | --- |
| Per-card art source for reprint editions: an edition entry may carry `${"artSet": "M10", "artNum": "185"}`. Download URLs (cardforge, Scryfall, art crop, sleeve) are built from that printing, while the image is cached under the reprint's own key. | `forge-core/.../card/CardEdition.java` (`EditionEntry.hasArtSource/getArtSourceSet/getArtSourceNumber`), `forge-gui/.../util/ImageFetcher.java` (`getArtSourcePrinting`) | Lets MEAR (D5) show a chosen existing printing's art without new artwork (WP2). |
| `exportMap [file]` console command | `forge-gui-mobile/src/forge/adventure/stage/ConsoleCommandInterpreter.java` | Writes the whole generated world to a PNG for layout work and tests. |

## Known issues / backlog

- Side quests are an interim copy of 34 generic Shandalar side quests (Kiora's and Teferi's quests dropped). Re-theme them in Phase 3. Without them, the town job boards crashed (NPE in `AdventureQuestController.getQuestNPCResponse` on an empty side-quest list).
- The One Ring is currently sold in Umbar's black market (30,000 gold). It should become a unique boss or quest reward (WP5 / Phase 3). A "can't unequip" curse would need an engine change.
- Hobbit and Wizard races reuse farmer/peasant and wizard enemy sprites with human portraits. Proper sprites need new art.
- The wizard intro and the rune display names are en-US only (`MiddleEarth/languages/adventure-en-US.properties`).
- The LTR Starter Kit decklists come from github.com/taw/magic-preconstructed-decks (citing the Wizards article of 2023-06-23). Do one check against the official page with web access.
- Generated (`.json` template) decks break down under the restricted pool (`enemy:Troll` → 32× The Lonely Mountain). The current `*_e.json` starters pull in non-ME jumpstart cards, and `check.sh` skips generated decks.
- Engine bugs found in WP3. They are not fixed; workarounds are in the data:
  1. `BiomeStructure.initialize` uses the width as the bound of the height loop, so a biome with structures that is wider than tall loops forever. Workaround: square biomes.
  2. `World.generateNew` road loops stop one short, so the last non-town POI gets no cleared path. Workaround: `dead_marshes` (open swamp) is the last biome.
  3. A POI with `radiusFactor` 0 always lands on the biome centre, and if that spot is taken the whole placement reruns.
  4. A POI that can't be placed in its biome after 500 tries is dropped silently.
- World generation is now 3.7–4.8 s (was 1.8 s), mostly from decoration structures (Mordor alone takes about 2.5 s).
- MEAR: 8 token types made by MEAR cards have no printing in the allowed sets (generic token image). The desktop bulk "download all pictures" ignores the art source. The art choices were made without seeing the images; review them in-game with network access.
- The main checkout's prebuilt jar predates the art-source engine change. Rebuild it after the Wave 1 agents finish.
- 44 enemy names in the biomes no longer exist upstream. They go away with the Phase 1 enemy rework, so they are not being remapped.
- The world generator sometimes retries POI placement ("Can not place POI Spawn…Rerunning"), once per smoke run so far. Watch this once the ME POIs exist.
- Upstream DB quirk: `Grave Researcher` (SOS) has a face named "Reanimate", which collides with the LTC *Reanimate*. This is harmless. The checker ignores it.
- `F4` (exit) in the intro cave doesn't leave the cave. Use the `leave` console command in scripts.

## Change log

| Date | Change |
| --- | --- |
| 2026-11-14 | WP7 merged: shops, items and heroes; PlaneCheck section 6 (shops, items, heroes). Coordinator fixes: added 34 generic side quests to the plane's `quests.json` (fixes the job-board NPE found by WP7) and ME display names for the six runes. |
| 2026-11-14 | User decisions on the WP2 questions recorded (D10). WP2b started. |
| 2026-11-13 | WP1 merged: `tools/middleearth/deckstrength.sh` (DeckSim worker with forced play/draw, DeckExport for .json and `enemy:` decks), LTR Starter Kit reference decks plus 40-card cuts and baselines. Measured: kits 55/45 against each other; upstream starters range 31–73%; ~2–4 s per game. Strength bands are in D9. |
| 2026-11-13 | WP3 merged: world 900×900, 43 biomes (15 new, enemy lists copied from their parent regions), 90 ME POIs, per-region town names, ME intro and tutorial quest, Oasis map path fix. Added `tools/middleearth/WorldSim.java` (offline layout simulator). Fixed the biome Y-axis note in DEVELOPMENT.md. |
| 2026-11-13 | WP2 merged: custom reprint set `MEAR` ("Middle-earth Adventure Reprints", 223 cards, C120/U70/R28/M5) with per-card art sources (engine change). Unique pool 844 → 1,067 cards. Open questions for the user are in the WP2 notes. |
| 2026-11-13 | Merged upstream master (≈9,200 commits). Dropped the fork's prototype Java changes. Plane config now uses `allowedEditions` (+HOB, HOC, PLTC). |
| 2026-11-13 | Phase 1 planned (D5–D8, work packages). Tooling made safe for parallel agents (`FORGE_JAR`, isolated game home per `OUT_DIR`, `DISPLAY_NUM`). |
| 2026-11-13 | Phase 0: config schema fix, biome name and duplicate fix, test tooling (`tools/middleearth/`), `exportMap` command, DEVELOPMENT.md and this plan. Baseline: 0 errors, 556 warnings. |
