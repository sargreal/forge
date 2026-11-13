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
$G start [plane]          # Xvfb :99 + game; sets the plane in ~/.forge/adventure/settings.json
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

**Save data:** `~/.forge/adventure/` holds `settings.json` and the saves. `check.sh` uses an isolated home under `.me-test/check-home`.

## 4. Plane data: how-to

Read the upstream format docs in `docs/Adventure/` first (`Configure-Planes.md`, `Create-Enemies.md`, `Create-Rewards.md`, `Create-new-Maps.md`, `Towns-&-Capitals.md`, `Tutorial-*`).

- **Card pool**: `config.json` → `allowedEditions` (currently `LTR, LTC, PLTC, HOB, HOC`) restricts rewards, shops and printings. Fixed deck lists (`.dck` / `.json`) are *not* filtered. Only their printings are swapped, so every hand-made deck must use in-pool cards (check with `check.sh -v`). Secret Lair LotR cards live in the shared `SLD` set and can't be allowed without allowing all of SLD.
- **Overrides**: to replace a common file, create the same relative path under `MiddleEarth/`. Common enemies, items and decks stay reachable for anything we don't override, so prefer plane-local copies with ME names.
- **Enemies**: `world/enemies.json` (plane-local once created). Biomes reference enemies by `getName()`, which is `nameOverride` if set, otherwise `name`. Decks go under `MiddleEarth/decks/...`. Sprites are `.atlas` + `.png` under `sprites/` (see `Create-Enemies.md`).
- **Biomes**: `world/biomes/*.json`, listed in `world/world.json` → `biomesNames`. `startPointX/Y` and `width/height` are fractions of the world. Y = 0 is the **bottom** (south). The biome `name` must be unique.
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
- Parallel agents: split work by **disjoint files**, for example one agent per region's enemies or one on `World.java` for Phase 2. Shared files (`world.json`, `enemies.json`, PLAN.md) are edited by one agent at a time, or merged afterwards by the coordinator.
