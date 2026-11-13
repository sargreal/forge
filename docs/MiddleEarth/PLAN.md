# Middle-earth Adventure: Plan & Status

> **Living document. Update it with every change**: tick off tasks, adjust the plan, and add a line to the change log.
> How to build, test and find your way around the code: [DEVELOPMENT.md](DEVELOPMENT.md).

**Goal:** a Middle-earth adventure plane for Forge's Adventure mode, using the Lord of the Rings and The Hobbit card sets, with Middle-earth regions, enemies, towns and a story.
**Scope:** stays in this fork (Tolkien IP). It will not be announced or upstreamed.

## Decisions

| # | Decision | Date |
| --- | --- | --- |
| D1 | Card pool: `LTR`, `LTC`, `PLTC` (Deluxe Commander Kit), `HOB`, `HOC`. Other Middle-earth printings are welcome when they exist as their own edition. Secret Lair LotR cards are in the shared `SLD` set and so are excluded for now. | 2026-11-13 |
| D2 | Content is built on the **random world generator first**. The fixed Tiled world (Phase 2) can be developed in parallel by a separate agent. | 2026-11-13 |
| D3 | The plane stays in the fork and isn't published. Engine changes are kept minimal and isolated so that upstream merges stay cheap. | 2026-11-13 |
| D4 | Edition restriction uses upstream's native `allowedEditions` support. The fork's earlier custom `legalCards.editions` Java code was dropped during the upstream sync. | 2026-11-13 |
| D5 | A **custom reprint set** (Forge `Type=Custom` edition) adds existing non-LotR cards that fit Middle-earth (wolves, spiders, eagles, treefolk, trolls, horses, wizards…). No new artwork: each reprint reuses a fitting existing printing's art. The goal is a good Middle-earth experience, not strict LTR-only. | 2026-11-13 |
| D6 | New decks are tested **AI vs AI**. Starter decks should be about as strong as the official LTR Starter Kit decks, not stronger. Enemy decks get a strength rating that matches their spawn difficulty. | 2026-11-13 |
| D7 | Upstream enemy decks that fit Middle-earth thematically can be reused with small card swaps (off-theme or off-pool cards replaced) instead of being built from scratch. | 2026-11-13 |
| D8 | Custom (new) cards are deferred to Phase 3/4. | 2026-11-13 |

## Current status (baseline after Phase 0)

`tools/middleearth/check.sh` → **0 errors, 556 warnings**. `game.sh smoke` → new game, intro and overworld all work, with no exceptions.

| Area | State |
| --- | --- |
| Card pool | ✅ 844 unique cards (LTR 836, LTC 558, HOB 321, HOC 158, PLTC 4 printings). Rewards, shops and printings are restricted correctly. |
| Biomes | 🟡 29 ME regions with hand-set positions. The layout is roughly recognisable but too much of it is grey "waste" (see image). Enemy lists are still Shandalar's. |
| Starter decks | ❌ All 30 come from common (Shandalar) decks with about 55–65% off-pool cards. |
| Enemies | ❌ 206 enemy decks spawned in the plane contain off-pool cards. 44 enemy names referenced by biomes no longer exist upstream (renamed in upstream's big adventure update), so those spawns are silently dropped. |
| Towns / POIs | ❌ Copied from Shandalar (255 POIs). `Mountain Town Erebor` is referenced but doesn't exist, so Erebor has no town. The start area is a desert packed with identical castles. |
| Shops / items / quests | ❌ Generic Shandalar content. The intro is "Welcome to Shandalar". |
| Fixed Tiled world | ❌ Not started (issue #2). The Gondor and Mordor region `.tmx` files exist. |

Baseline world (generated, north is up):

![baseline world map](img/worldmap-baseline.png)

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
| WP1 deck-lab | AI-vs-AI strength harness built on Forge `sim` mode (`tools/middleearth/deckstrength.sh`). Reference gauntlet of the official LTR Starter Kit decks. Strength rating for decks. | `tools/middleearth/deck*`, `forge-gui/res/adventure/MiddleEarth/decks/reference/`, `notes/wp1-deck-lab.md` | ⏳ |
| WP2 reprint-set | Custom Middle-earth reprint edition (art reuse mechanism, card selection), added to `allowedEditions`, checker support. | `forge-gui/res/editions/<custom>.txt` (or the plane-local equivalent), `config.json` → `allowedEditions` only, `notes/wp2-reprint-set.md` | ⏳ |
| WP3 world-layout | Retune biome positions and sizes against the real map, shrink the waste, fix the start area. ME towns and POIs (names, Erebor town, dungeons from existing `.tmx`), town name lists, intro text. | `world/world.json`, biome fields other than `enemies`, `world/points_of_interest.json`, `world/town_names_*.txt`, intro/quest texts, `notes/wp3-world-layout.md` | ⏳ |

**Wave 2 (after WP1 and WP2)**

| WP | Task | Owns |
| --- | --- | --- |
| WP4 starter-decks | 5 colours × starter/constructed/pile × difficulty, matched to the reference strength. W = Gondor/Rohan, U = Elves, B = Mordor, R = Dwarves, G = Hobbits/Ents. | `MiddleEarth/decks/starter/`, `config.json` → `difficulties[*].*Decks` |
| WP5 enemies-shadow | Mordor, Isengard, Moria, Nazgûl, trolls, orcs/goblins, Easterlings, Haradrim, Corsairs, Balrog, Smaug, Shelob. Reuse upstream decks where they fit (D7). | `MiddleEarth/world/enemies.json` (shadow section), `MiddleEarth/decks/enemy/shadow/` |
| WP6 enemies-free | Bandits/ruffians, Dunlendings, wolves/wargs, spiders, Huorns, Barrow-wights, Gollum, Saruman, plus allied duelists (rangers, elves, dwarves). | `MiddleEarth/decks/enemy/free/` and an enemies fragment that the coordinator merges |
| WP7 shops-items-heroes | Regional and faction card shops, ME items (Sting, mithril shirt, elven cloak, phial, the One Ring), hero avatars (hobbit, human, elf, dwarf, wizard). | `world/shops.json`, `world/items.json`, `world/heroes.json`, sprites |

**Wave 3 (integration):** fill the biome `enemies` lists with ME enemies per region, run a full smoke run, and play-test.
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
| `exportMap [file]` console command | `forge-gui-mobile/src/forge/adventure/stage/ConsoleCommandInterpreter.java` | Writes the whole generated world to a PNG for layout work and tests. |

## Known issues / backlog

- 44 enemy names in the biomes no longer exist upstream. They go away with the Phase 1 enemy rework, so they are not being remapped.
- The world generator sometimes retries POI placement ("Can not place POI Spawn…Rerunning"), once per smoke run so far. Watch this once the ME POIs exist.
- Upstream DB quirk: `Grave Researcher` (SOS) has a face named "Reanimate", which collides with the LTC *Reanimate*. This is harmless. The checker ignores it.
- `F4` (exit) in the intro cave doesn't leave the cave. Use the `leave` console command in scripts.

## Change log

| Date | Change |
| --- | --- |
| 2026-11-13 | Merged upstream master (≈9,200 commits). Dropped the fork's prototype Java changes. Plane config now uses `allowedEditions` (+HOB, HOC, PLTC). |
| 2026-11-13 | Phase 1 planned (D5–D8, work packages). Tooling made safe for parallel agents (`FORGE_JAR`, isolated game home per `OUT_DIR`, `DISPLAY_NUM`). |
| 2026-11-13 | Phase 0: config schema fix, biome name and duplicate fix, test tooling (`tools/middleearth/`), `exportMap` command, DEVELOPMENT.md and this plan. Baseline: 0 errors, 556 warnings. |
