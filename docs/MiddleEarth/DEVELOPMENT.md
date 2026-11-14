# Middle-earth plane: development guide

This guide is for anyone working on the Middle-earth adventure plane in this fork, people and agents alike.
Read [PLAN.md](PLAN.md) first: it holds the phase plan, the current status and the change log, and it has to be updated with every change.

> **Do not publish.** The plane uses Tolkien IP and stays in this fork. It is not meant for upstream. Don't open PRs to Card-Forge/forge with plane content.

## 1. Repository orientation

Forge is a multi-module Maven project (Java 17). Only a few parts matter for the adventure plane:

| Path | What it is |
| --- | --- |
| `forge-gui/res/adventure/MiddleEarth/` | **The plane itself (our content).** Pure data: JSON, Tiled maps, images. |
| `forge-gui/res/adventure/common/` | Shared adventure data. A plane falls back to it for every file it doesn't define. |
| `forge-gui/res/adventure/Innistrad/` | The best **template** for a single-set plane: own decks, enemies, heroes, items, structures and `allowedEditions`. |
| `forge-gui/res/adventure/common/maps/world/middleearth/` | Tiled world (`middleearth.world`) with region maps (Gondor, Mordor). Used by Phase 2. |
| `forge-gui/res/editions/*.txt` | Set lists. Our sets: `LTR`, `LTC`, `PLTC`, `HOB`, `HOC`. |
| `forge-gui/res/cardsfolder/` | Card scripts, one file per card. |
| `forge-gui-mobile/src/forge/adventure/` | **Adventure mode engine** (libGDX), shared by desktop and mobile. |
| `forge-gui-mobile-dev/` | Desktop launcher for the libGDX/adventure client. This is what we run. |
| `forge-gui-desktop/` | Classic Swing desktop client. Not used for adventure. |
| `docs/Adventure/*.md` | Upstream docs for the plane file formats. Read them before editing data. |
| `tools/middleearth/` | Our dev and test tooling (see §3). |

### Adventure engine map (`forge-gui-mobile/src/forge/adventure/`)

- `util/Config.java`: selects the plane (`settings.json` → `plane`) and resolves files. `Config.getFile(path)` tries `<plane>/path` first, then `common/path`.
- `data/*Data.java`: one class per JSON format (`ConfigData`, `WorldData`, `BiomeData`, `EnemyData`, `PointOfInterestData`, `ShopData`, `ItemData`, `AdventureQuestData`, `DifficultyData`, `RewardData`…). The JSON field names are exactly the Java field names.
- `world/World.java`: random world generation (biomes from noise and start points, POI placement, roads, minimap). Phase 2 (fixed Tiled world) mostly touches this.
- `stage/WorldStage.java`: renders the overworld and spawns enemies. `stage/MapStage.java` renders POI maps (towns and dungeons, which are Tiled `.tmx` files).
- `stage/ConsoleCommandInterpreter.java`: the in-game console (F9). This fork adds `exportMap`.
- `util/CardUtil.java`: card pools and decks. It honours `allowedEditions` (`getFullCardPool`, `getCardByName`, `ensureAllowedEdition`, `sanitizeCardPool`).
- `scene/*Scene.java`: UI screens (new game, shop, deck editor, duel…).

## 2. Build

```bash
tools/middleearth/game.sh build      # ≈1.5 min warm; builds forge-gui-mobile-dev and its dependencies
```

This produces `forge-gui-mobile-dev/target/forge-gui-mobile-dev-*-jar-with-dependencies.jar`.

Notes for the cloud sandbox:

- Maven needs `jitpack.io`. The `forge` cloud environment allows it.
- The script forces `-Dmaven.resolver.transport=wagon`. Maven's default HTTP transport drops jar downloads behind the sandbox proxy.
- After any change under `forge-gui-mobile/src` you must rebuild. Data-only changes under `forge-gui/res` don't need a rebuild, because the game reads `../forge-gui/res` at runtime.

## 3. Testing

There are two layers of tests, and both must pass (or not get worse) before you commit.

### 3.1 Headless data check (fast, about 10 s)

```bash
tools/middleearth/check.sh           # summary
tools/middleearth/check.sh -v        # list every off-pool card per deck
tools/middleearth/check.sh Innistrad # check another plane for comparison
```

`tools/middleearth/PlaneCheck.java` boots the card database headlessly, loads the plane through the real `Config`, and checks the following:

1. **Strict parse** of `config.json`, `world.json`, every biome, and the enemies, POIs, shops, items and quests files. Unknown fields are errors, so typos like `staringMoney` get caught.
2. **References**: duplicate biome names are an error. Enemy or POI names in biomes that don't exist are warnings, because the game silently ignores them.
3. **Card pool**: starter decks and the decks of every enemy the plane spawns are measured against `allowedEditions`. A card is *off-pool* if it has no printing in an allowed set.
4. **Printing resolution**: the unique card pool and the reprint replacement (`ensureAllowedEdition`) only give allowed printings. This guards issue #1.

The exit code is non-zero on errors. Warnings are the content backlog. Track their count in PLAN.md.

### 3.1b Offline layout simulator (seconds)

```bash
J=forge-gui-mobile-dev/target/forge-gui-mobile-dev-2.0.17-SNAPSHOT-jar-with-dependencies.jar
java -cp $J tools/middleearth/WorldSim.java forge-gui/res/adventure/MiddleEarth 0 .me-test/sim.png 20
```

This is a replica of `World.generateNew`'s biome painting and POI placement, using the engine's own noise class. It runs N seeds and reports the following: placement reruns, POIs that were dropped or are unreachable from the start, and biome coverage in percent. Use it before `smoke` when you change biomes or POIs. It is a replica, so confirm the final result with `smoke`.

### 3.2 GUI smoke test (about 1 min)

```bash
tools/middleearth/game.sh smoke
```

This starts the real game under Xvfb, makes a new character, clicks through the intro, goes to the overworld, and writes the following to `.me-test/` (gitignored):

- `smoke-overworld.png`: a screenshot of the overworld.
- `smoke-worldmap.png`: the **whole generated world** (4000×4000, from the `exportMap` console command). Use it to judge biome layout against the Middle-earth map.
- `game.log`, and a summary of exceptions and POI-placement retries.

To look at screenshots, downscale them first:
`python3 -c "from PIL import Image; Image.open('.me-test/smoke-worldmap.png').resize((800,800)).save('.me-test/wm.png')"`

### 3.3 Driving the game by hand or from scripts

`tools/middleearth/game.sh` has these building blocks (screen is 1280×720, origin top-left):

```bash
G=tools/middleearth/game.sh
$G start [plane]          # Xvfb :$DISPLAY_NUM + game; isolated game home in $OUT_DIR/home (settings, saves)
$G wait-menu              # waits for splash, clicks "Adventure Mode", waits for the title menu
$G click 533 87           # New Game   (title menu)
$G click 1115 447         # Start      (character creation)
$G skip-dialog 12         # Return advances dialog options
$G console leave "give gold 500" "spawn enemy Bandit"   # F9 console, see docs/Adventure/Console-and-cheats.md
$G key e                  # deck screen; i = inventory, m = map, Escape = back, w/a/s/d = move
$G hold d 2               # walk east for 2 s
$G shot .me-test/x.png    # screenshot
$G errors                 # exceptions in the log
$G stop
```

Useful console commands: `leave` (exit a POI), `teleport to X Y`, `fly 30`, `hide 60`, `spawn enemy <name>`, `give card <name>`, `give set LTR`, `listPOI`, `exportMap [file]`, `sanitize editions`.

**Save data:** the game runs with `user.home=$OUT_DIR/home`, so settings and saves are in `.me-test/home/.forge/adventure/`. `check.sh` uses `.me-test/check-home`. Neither touches the real `~/.forge`.

### 3.4 Deck strength testing (AI vs AI)

`tools/middleearth/deckstrength.sh` rates a deck by letting Forge's AI play it against a **gauntlet** of reference decks, with the AI on both sides. The default gauntlet is the two official *LTR Starter Kit* decks in `forge-gui/res/adventure/MiddleEarth/decks/reference/` (Gondor GW and Mordor BR, 60 cards, LTR/LTC printings). Decision D6: starter decks should score about 50% against them, and enemy decks get a tier from their score.

```bash
tools/middleearth/deckstrength.sh build                         # once: builds the engine jar (forge-gui-desktop), 3-5 min cold
tools/middleearth/deckstrength.sh my_deck.dck                   # 60-card deck: 60 games per gauntlet deck = 120 games
tools/middleearth/deckstrength.sh --g40 my_deck40.dck           # 40-card deck: against the 40-card cuts of the kits
tools/middleearth/deckstrength.sh -n 100 a.dck b.dck            # several candidates, more games
tools/middleearth/deckstrength.sh decks/starter/white_e.json    # adventure JSON deck: 3 generated samples (--samples)
tools/middleearth/deckstrength.sh --enemy-life enemy:Bandit     # enemy tier: enemy at its own life, gauntlet at 16
tools/middleearth/deckstrength.sh --round-robin -n 40 some/dir  # every deck in a directory against every other
tools/middleearth/deckstrength.sh -g other_refs/ my_deck.dck    # a different gauntlet
tools/middleearth/deckstrength.sh -h                            # all options
```

How it works:

- `deckstrength/deckstrength.py` builds the game list, runs **3 worker JVMs** (`-j`, keep it at 3 or less when other agents share the machine), restarts a worker that dies, and writes the report.
- `deckstrength/DeckSim.java` is the worker. It does what Forge's `forge-gui-desktop` `sim` mode does (`forge.view.SimulateMatch`), but every game is a fresh one-game match with a **fixed player on the play**: half of each pairing's games are on the play, half on the draw. (In `sim` mode the loser of the previous game goes first, so play/draw isn't controlled.) It loads the card database once per worker, and a game that runs past the timeout (`-t`, default 120 s) counts as a draw.
- `deckstrength/DeckExport.java` turns adventure `.json` decks and `enemy:<Name>` decks into `.dck` through `CardUtil.getDeck`, the same call the game uses. It needs the adventure jar (`game.sh build` or `FORGE_JAR`). JSON decks are generated randomly, so each sample is a different deck.
- Everything runs headless, with `user.home` under the run directory. Results go to `.me-test/deckstrength/<timestamp>/`: `summary.md`, `summary.csv` (one row per deck and opponent plus an `ALL` row), `games.csv` (one row per game), the exact decks played (`decks/`), and the worker logs. If a run is interrupted, run the same command with `-o <that dir>` to finish the remaining games.

Reading the result:

- **Score** = (wins + ½ draws) / games against the gauntlet. **50% = as strong as the official starter decks.** The report gives a 95% confidence interval. With the default 120 games (60 per gauntlet deck) it is about ±9 points, so use the default to screen decks and `-n 150` or more (±6) before a final decision. Differences under ~10 points between two decks are noise at the default.
- **Starter decks** (Rating column): 42–58% is *on par* and is the target. 30–42% is weaker and 58–70% stronger. Below 30% or above 70% is far off. A new starter deck should land on par, and preferably not above ~55%.
- **40 vs 60 cards:** the plane uses `minDeckSize: 40`, while the kits have 60 cards. A smaller deck draws its best cards more often, and that is worth roughly **+8 to +15 points** (measured: proportional 40-card cuts of the kits score 60% and 66% against the 60-card originals). Rate **40-card decks with `--g40`**, which plays them against the 40-card cuts in `reference/size40/`. Use the 60-card gauntlet for 60-card decks. The bands are the same in both cases.
- **Enemy tiers:** use `--enemy-life enemy:<Name>`, or `--enemy-life --life <its life> deck.dck` for a deck that isn't in `enemies.json` yet. The enemy plays at its own `life` from `enemies.json`, and the gauntlet plays at 16 (the player's starting life on Easy). The Rating column then shows a tier: <45% easy, 45–60% normal, 60–75% hard, >75% elite/boss. These tiers are calibrated on upstream enemies: Bandit and Goblin (13 life) scored 27% and 48%, Abyssal Baron (30) 61%, and Adult Green Dragon (25) 72%. At 20 life on both sides, upstream "easy" and "hard" enemy decks all score 38–55%, so upstream difficulty comes mostly from enemy life, not from the deck.
- Also check *Avg turns* (Forge counts both players' turns: 18 turns is 9 rounds) and *Timeouts* (should be about 0; many timeouts mean the AI loops on some card). The *Sanity* section checks that the designated player went first and gives the on-the-play win rate (51–56% so far).
- **MEAR reprints** (WP2's custom edition) need nothing special. Run the harness from a tree that has `forge-gui/res/editions/Middle-earth Adventure Reprints.txt`, so that `Card|MEAR` lines resolve. The cards are existing cards, so strength doesn't depend on the printing.

Runtime: one game averages 1.6–2 s on an idle machine and about 3.7 s when other agents load all 4 cores (median 1.4–2.8 s, slowest 40 s; 1 timeout in 3,000 games). Each worker JVM needs about 10 s to start. The default run (one candidate, 120 games, 3 workers) takes **about 1.5 minutes on an idle machine and 3–4 minutes under load**. A round robin of n decks plays n(n−1)/2 pairings.

Caveats: this measures **AI vs AI** strength. Forge's AI plays some decks (aggro, simple creatures) much better than others (combo, complex triggers, decks that need restraint). Treat the rating as a guide for comparable decks, and play-test anything that lands near a band edge. Notes and measurements: [notes/wp1-deck-lab.md](notes/wp1-deck-lab.md).

## 4. Plane data: how-to

Read the upstream format docs in `docs/Adventure/` first (`Configure-Planes.md`, `Create-Enemies.md`, `Create-Rewards.md`, `Create-new-Maps.md`, `Towns-&-Capitals.md`, `Tutorial-*`).

- **Card pool**: `config.json` → `allowedEditions` (currently `LTR, LTC, PLTC, HOB, HOC`) restricts rewards, shops and printings. Fixed deck lists (`.dck` / `.json`) are *not* filtered. Only their printings are swapped, so every hand-made deck must use in-pool cards (check with `check.sh -v`). Secret Lair LotR cards live in the shared `SLD` set and can't be allowed without allowing all of SLD.
- **Overrides**: to replace a common file, create the same relative path under `MiddleEarth/`. Common enemies, items and decks stay reachable for anything we don't override, so prefer plane-local copies with ME names.
- **Enemies**: `world/enemies.json` (plane-local once created). Biomes reference enemies by `getName()`, which is `nameOverride` if set, otherwise `name`. Decks go under `MiddleEarth/decks/...`. Sprites are `.atlas` + `.png` under `sprites/` (see `Create-Enemies.md`).
- **Biomes**: `world/biomes/*.json`, listed in `world/world.json` → `biomesNames`. `startPointX/Y` and `width/height` are fractions of the world. **Careful, the axes differ:** for biome `startPointY`, Y = 0 is **north** (top). For `world.json` `playerStartPosY` and POI `offsetY`, Y = 0 is **south**. The biome `name` must be unique, and it also selects the town name list (`world/town_names_<biome name>.txt`).
- **Biome shapes:** keep biomes with `structures` square (`width == height`). The engine's `BiomeStructure` loops forever otherwise (see PLAN backlog). Long ranges are chains of round blobs.
- **POIs and towns**: `world/points_of_interest.json` gives the name, type, `map` (`.tmx`), sprite and count. Town names come from `world/town_names_<color>.txt`.
- **Validating JSON**: always run `check.sh`. The game itself often swallows parse errors (it falls back to defaults or skips entries).

## 5. Keeping in sync with upstream

```bash
git remote add upstream https://github.com/Card-Forge/forge.git   # once
git fetch upstream master
git merge upstream/master
```

- Keep engine changes **small and isolated**, ideally new methods or new console commands, so merges stay cheap. List every engine change in PLAN.md → "Engine changes".
- The fork's early history (2024) merged upstream in a way that left a stale merge base. The 2026-11 sync merged upstream wholesale (`abe987d38`). Future merges should be normal.

## 6. Conventions

- Branch: `claude/happy-heisenberg-moqu93` (current dev branch) and `middleearth` (older base).
- Every change: update PLAN.md (status and change log), run `check.sh`, and run `game.sh smoke` if the change touches world generation or engine code.
- Agent budget (PLAN D11): routine content work runs on Sonnet, at most 2 agents in parallel, and each task is sized to finish in one run.
- Parallel agents: each one works in its own git worktree or branch and uses its own `DISPLAY_NUM` (for example 101, 102…). Data-only agents can skip the build: `export FORGE_JAR=/home/user/forge/forge-gui-mobile-dev/target/forge-gui-mobile-dev-2.0.17-SNAPSHOT-jar-with-dependencies.jar` (the main checkout's jar). It still reads the game data from the agent's own tree. Agents write their report to `docs/MiddleEarth/notes/<wp>.md` and don't edit PLAN.md.
- Parallel agents: split work by **disjoint files**, for example one agent per region's enemies or one on `World.java` for Phase 2. Shared files (`world.json`, `enemies.json`, PLAN.md) are edited by one agent at a time, or merged afterwards by the coordinator.
