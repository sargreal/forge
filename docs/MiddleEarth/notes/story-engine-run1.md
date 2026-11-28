# Story engine run 1: flag conditions, world state, spawnCondition, origins

> Agent report, 2026-11-15. Branch `story-engine-1` (from `claude/happy-heisenberg-moqu93` at `92020310d`).
> Spec: [story-engine-audit.md](story-engine-audit.md) §3 items 0–3 and the flag findings in §2.
> Data reference for authors: [DEVELOPMENT.md §4.2](../DEVELOPMENT.md#42-story-data-reference).

## Result

| Item | Status |
| --- | --- |
| 1. Shared `FlagCondition` + flag fixes | Done. Used by dialog conditions (new `flag` field), quest flag stages (`condition`), biome overrides, rewards, POIs and `spawnCondition`. `advance*Flag` wakes stages. Console `flag set/advance/get/check`. |
| 2a. `BiomeData.enemyOverrides` | Done. Every matching entry applies in list order (`add`, `replace`, `remove`); the cache is keyed by the set of matching entries. |
| 2b. `RewardData.condition` | Done, for every reward list (all go through `RewardData.generate`). |
| 2c. POI deactivation | Done: `questFlagsToDeactivate`, `condition`; `active: false` now honoured. |
| 3. `spawnCondition` on map objects | Done, for every object type. |
| 4. Origins | Done: `config.json origins[]`, dev API `/newGame {origin}`, respawn POI, New Game+. Middle-earth: `Wizard's Hollow` (today's start) + hidden `Test`. |
| 5. Tests | `FlagConditionCheck.java` (23 headless checks), `tests/origin_start.py`, `tests/flags_and_world_state.py`, PlaneCheck validation; all in `run_all.sh`. |
| 6. Docs | DEVELOPMENT.md §4.2 "Story data reference", dev API table, console list, PlaneCheck list. |

## Engine changes (for the PLAN "Engine changes" table)

| Change | Files | Why |
| --- | --- | --- |
| Shared flag condition: new `data/FlagCondition` (`scope` quest/character/map, `key`, `op` `= != < <= > >=` plus the upstream names, `val`, `not`, `all`/`any` groups; unset reads 0; `eval`, `allHold`, `parse` for Tiled strings, `validate`). Dialog `ConditionData.flag` (checked in `MapDialog.isConditionOk`). Quest stages `condition` for `QuestFlag`/`CharacterFlag`/`MapFlag` objectives (`AdventureQuestStage.flagConditionHolds`, also checked on activation in `AdventureQuestData.activateNextStages`). | new `data/FlagCondition.java`; `DialogData`, `MapDialog`, `AdventureQuestStage`, `AdventureQuestData` | Story run 1, item 1: one condition model with sane unset/`not` semantics. |
| `advanceQuestFlag` / `advanceCharacterFlag` call `updateQuests*Flag` like `set*`. | `player/AdventurePlayer.java` | Audit quirk 3: `QuestFlag` stages never completed from an advance. |
| Console `flag set|advance|get|check` (`setFlag` helper fires the quest updates and `showQuestDialogs` in maps). | `stage/ConsoleCommandInterpreter.java` | Play-testing and tests. |
| `BiomeData.enemyOverrides[] {condition, mode replace|add|remove, enemies}`, `getEffectiveEnemyNames()`; `getEnemyList()` rebuilds its cache when the set of matching overrides changes; `getEnemy()` reads through `getEnemyList()` (was the raw field: null before the first `getEnemyList`). | `data/BiomeData.java` | Item 2a: per-act / per-allegiance spawns. |
| `RewardData.condition` (copied by the copy constructor): `generate()` returns nothing unless it holds. | `data/RewardData.java` | Item 2b: shop stock, drops, chests, `grantRewards` per flag. |
| `PointOfInterestData.questFlagsToDeactivate` and `condition`; `PointOfInterest.getActive()` also checks them and `data.active`, all read from the POI data (not the save). | `data/PointOfInterestData.java`, `pointofintrest/PointOfInterest.java` | Item 2c: POIs disappear by flag; the ignored `active` field. |
| `spawnCondition` property for every map object type: `MapStage.spawnConditionHolds` in `loadObjects` (after the deleted-object check); the empty stub in `canSpawn` is removed. | `stage/MapStage.java` | Item 3: town variants, cameo NPCs, refusing inns/shops. |
| Origins: `ConfigData.origins`, new `data/OriginData` (`select`, `byName`, `beginNewGame`). `NewGameScene.start` uses it (none = the old Spawn + quest 28 code, moved into `beginNewGame(null)`); `devApiStart(..., origin)`. `WorldStage.enterSpawnPOI(String)` (old no-arg = `"Spawn"`). `AdventurePlayer` `originName`/`respawnPOI` (save keys `originName`, `respawnPOI`, written only when set) and `getRespawnPOI()`; `GameStage.resetPlayerLocation` uses it (fallback `Spawn`). New Game+ (`SaveLoadScene`) restarts the save's origin. | `data/OriginData.java` (new), `ConfigData`, `NewGameScene`, `WorldStage`, `AdventurePlayer`, `GameStage`, `SaveLoadScene` | Item 4: data-driven starts per race/colour. |
| Dev API: `/newGame {origin}`; `/state` player `origin`, `respawnPOI`; overworld `location.biomeEnemies`; POI objects `active`. | `devapi/DevApi.java` | Tests. |

Engine diff: 19 files, +531/−15 lines; 2 new classes carry most of it. No save format change: new save keys are optional, `FlagCondition` has a fixed `serialVersionUID`, and the classes that gained fields (`AdventureQuestStage`, `RewardData`, `PointOfInterestData`, `DialogData.ConditionData`) already had fixed UIDs; `BiomeData` and `ConfigData` are never serialized.

## Compatibility (other planes, old saves)

Checked against every plane's `quests.json`, `points_of_interest.json` and every `.tmx` dialog (script: all `get*Flag` conditions, `advance*Flag` actions, `colorIdentity`, `active`):

- **Legacy `getQuestFlag`/`getCharacterFlag`/`getMapFlag` are unchanged.** 69 of 367 upstream conditions would flip if unset read as 0 (e.g. `common/.../spawn.tmx`: `Ch1CastlesComplete < 5`; `temple_of_*`: `placesAlreadyChecked < 6`; items `exploreShand1 > 6, not`). So the unset-as-0 semantics live only in the new `flag` field and the other new fields.
- **advance wakes stages:** safe. The advanced keys in content (`Ch1CastlesComplete`, `FE_DwarvenHold_Defeated`, `riddlesLairComplete`; map flags already woke stages) are not the `mapFlag` of any `QuestFlag`/`CharacterFlag` stage in any plane (those use `Ch1BlackCastleComplete`…, `FE_DwarvenHold_Victory`, `mainQuest`, …). No upstream stage changes behaviour.
- **`colorIdentity` is inverted, not fixed:** Shandalar Old Border's `spawn.tmx` (5 uses) and the debug maps use `not: true` to mean "has the colour". Documented in §4.2.
- **POI `active`:** no plane sets `active: false`, so honouring it changes nothing.
- **`questFlagsToActivate` after loading a save:** upstream stores it in the save but `PointOfInterest.load` never reads it back, so loaded POIs are always active. Left as is (fixing it changes when Shandalar's Black Castle etc. appear for existing saves); the new fields read the POI data and are not affected. Recommendation for content: use `condition` instead.
- **No `origins`:** `beginNewGame(null)` is the old code (enter `Spawn`, add quest 28 if missing). The respawn falls back to `Spawn`. Old saves have no `originName` → New Game+ uses the old path.
- Flag range: bytes, -128..127. `set` casts (300 → 44), `advance` wraps at 127 → -128 (unchanged; run 2's signed add should clamp). Setting quest/character flags to 0 deletes them; map flags keep a 0 entry.

## Fixtures in plane data (inert in normal play)

Keyed on `test_*` flags that nothing sets outside the tests: origin `Test` (hidden) and quest 9001 (`storyQuest`, only issued by it) in `config.json` / `quests.json`; `enemyOverrides` in `biomes/shire.json`; a conditioned Lembas at the end of the `GreenItems` shop; `questFlagsToDeactivate` + `condition` on `Forest Capital`. The `spawnCondition` test patches `common/maps/map/main_story/forest_capital.tmx` while it runs and restores it in a `finally`. The Middle-earth plane's own start is the `Wizard's Hollow` origin, identical to before.

## Test results

- `tools/middleearth/tests/run_all.sh` (DISPLAY_NUM=116): check.sh 0 errors / 2 warnings (unchanged), FlagConditionCheck 23/23, `intro_walkthrough.py` PASS, `origin_start.py` PASS (25 s), `flags_and_world_state.py` PASS (31 s): flag semantics via console, `spawnCondition` on/off, shop Lembas only with `test_shop`, enemyOverrides add/replace/remove in `/state` and in real spawns (only Badgers spawn with the remove override), POI hidden by `questFlagsToDeactivate` and by `condition`.
- PlaneCheck negative run (broken mode, enemy, op, value range, POI condition without key, shop condition scope, origin POI and quest; malformed `spawnCondition` and dialog `flag` in a map): every case reported as an error; data restored.
- Shandalar: `game.sh start Shandalar` + `/newGame`: `spawn.tmx`, prologue dialog, origin none, respawn `Spawn`; same quest list (empty until the prologue issues quest 28) as the main checkout's jar; leave → overworld, biome enemies and active POIs reported. `check.sh Shandalar`: the story section reports nothing; its other errors are the pre-existing upstream ones (e.g. `Vulcano Dragon`).

## Left for run 2 / open points

- Run 2 scope (not started): signed, clamped `addQuestFlag`/`addCharacterFlag` action (note the byte wrap above), corruption HUD, `config.json flagEffects` (reuse `FlagCondition` for `when`), `EnemyData.noDefeatPenalty`, equip-locked Ring, `runCommand` dialog action. `FlagCondition` is ready to be reused for `flagEffects` and the HUD.
- Origin content (Dunland arc, Hobbit start, a Dunlending race in `heroes.json`) is data work now.
- The side-quest pool (`getQuestNPCResponse`) is still flag-blind (audit item 8); a `condition` on quests would reuse `FlagCondition` in 5 lines.
- Shop stock is generated once per map visit; conditioned shop entries should be last in `rewards` (index-based purchase memory).
- `questFlagsToActivate` save/load quirk (above) if the coordinator wants it fixed.
