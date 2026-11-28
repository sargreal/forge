# Story engine run 2: corruption meter, flag effects, non-fatal loss, locked items, runCommand, endings

> Agent report, 2026-11-15. Branch `story-engine-2` (from `claude/happy-heisenberg-moqu93` at `f7e1332f0`).
> Spec: [story-engine-audit.md](story-engine-audit.md) §3 items 4–8 and the run 1 follow-ups ([story-engine-run1.md](story-engine-run1.md)).
> Data reference for authors: [DEVELOPMENT.md §4.2](../DEVELOPMENT.md#42-story-data-reference).

## Result

| Item | Status |
| --- | --- |
| 1. Signed, clamped add | Done: dialog actions `addQuestFlag` / `addCharacterFlag` / `addMapFlag {key, val, min, max}`, console `flag add`. Clamps to `[min,max]` ∩ −128..127, writes through `set*Flag` (wakes stages, 0 deletes). |
| 2. Corruption HUD | Done: `config.json statusFlags[] {flag, scope, label, icon, max, hidden, showWhen}`, one HUD label per entry under life/shards/gold, polled 4×/s. ME: "Shadow n/100" (quest flag `corruption`), shown once > 0. `/state statusFlags`. |
| 3. `flagEffects` | Done: `config.json flagEffects[] {condition, effect}`, every matching effect goes to the player (its `opponent` to the enemies) at duel start. `DB$ RingTemptsYou` in `startBattleScript` verified in a real duel (emblem "The Ring", `ringTempted` 1). |
| 4. Non-fatal boss loss | Done: enemy `onDefeat {noPenalty, action[], dialog}` (enemy-level, see the design note below). |
| 5. Locked items | Done: item `locked`, `lockCondition`; no unequip / slot replacement / drop / crack, kept across loadout switches, auto-equips when gained, red "Locked" line in the description, equip button "Locked". The One Ring is `locked: true`. |
| 6. `runCommand` | Done: dialog action, allowlist `ConsoleCommandInterpreter.DATA_COMMANDS`; others refused and logged, `check.sh` error. |
| 7. Quest `condition` | Done: quest boards skip side quests whose condition fails (fallback: whole pool when none holds). |
| 8. Ending screen (optional) | Done: dialog action `endGame {title, text, image, flag}`: sets the character flag (default `gameEnded`), then a closing screen with "Continue playing" / "Main menu". |
| Tests | `tests/corruption.py`, `tests/boss_loss.py`, `tests/locked_item.py` (all in `run_all.sh`), 14 new headless checks in `FlagConditionCheck.java` (37 total), PlaneCheck validation of every new field. |

Design note, item 4: `onDefeat` sits on the enemy, not on a quest stage. The penalty is applied by the post-duel code (`WorldStage`/`MapStage.setWinner`, `DuelScene`), which knows the enemy and not the stage; the Sauron and Ring duels are the same encounter however they are reached (quest stage, map object, dialog `battleWithActorID`); and stage-level loss branching already exists (`failureDialog` of `Defeat`/`Hunt` stages, which still runs).

## Engine changes (for the PLAN "Engine changes" table)

| Change | Files | Why |
| --- | --- | --- |
| Signed, clamped flag add: `FlagCondition.clampedAdd` / `addFlag(scope, key, delta, min, max)` (writes through `set*Flag`, so stages wake; 0 deletes); dialog actions `addQuestFlag` / `addCharacterFlag` / `addMapFlag` (`DialogData.ActionData.FlagAdd {key, val, min, max}`, run in `MapDialog.setEffects`); console `flag add <scope> <key> <delta> [min] [max]`. | `data/FlagCondition.java`, `data/DialogData.java`, `util/MapDialog.java`, `stage/ConsoleCommandInterpreter.java` | Run 2 item 1: a corruption meter that never wraps. |
| HUD status flags: `ConfigData.statusFlags[]` (`StatusFlag {flag, scope, label, icon, max, hidden, showWhen}`); `GameHUD.addStatusFlagLabels` (labels continue the life/shards/gold column), `updateStatusFlags` (polled every 0.25 s in `act`), `statusFlagText`, `getStatusFlagTexts` (dev API). No `statusFlags` = nothing added. | `data/ConfigData.java`, `stage/GameHUD.java` | Item 2: corruption readout (STORY S5), generic for any flag. |
| Duel effects by flag: `ConfigData.flagEffects[]` (`FlagEffect {condition, effect}`); `DuelScene` adds every matching effect to the player side (opponent block to the enemies) before the global layer, outside events. | `data/ConfigData.java`, `scene/DuelScene.java` | Item 3: "the Ring tempts you" by corruption tier. |
| Non-fatal loss: `EnemyData.onDefeat` (`DefeatData {noPenalty, action[], dialog}`, fixed UID, copied by the copy constructor). `AdventureQuestController.handleDefeatBy(enemy)` queues the actions (as an action-only dialog) and the dialog in the quest dialog queue and returns `noPenalty`. `WorldStage.setWinner`: no `defeated()` (life/gold), no respawn, no boss popup when `noPenalty`. `MapStage.setWinner`: with `noPenalty` the enemy is removed for this visit and the player stays in the map (no `defeated()`, no dungeon exit). `DuelScene`: no run-modifier card loss. | `data/EnemyData.java`, `util/AdventureQuestController.java`, `stage/WorldStage.java`, `stage/MapStage.java`, `scene/DuelScene.java` | Item 4: Sauron and Ring duels (S12). |
| Locked items: `ItemData.locked`, `lockCondition`, `isLocked()` (reads the current `items.json` definition, since saves hold item snapshots; red line in `getDescription`). `AdventurePlayer.equip` returns false instead of taking off or replacing a locked item; new `isSlotLocked`, `dropItem`; `addItem` auto-equips a locked item; loadout switches keep locked items; `getRandomEquippedItem`/`hasEquippedItem` skip them (no cracking). `InventoryScene`: delete uses `dropItem` and is disabled, equip button "Locked"/disabled. | `data/ItemData.java`, `player/AdventurePlayer.java`, `scene/InventoryScene.java` | Item 5: the One Ring (S9). |
| `runCommand` dialog action: `ConsoleCommandInterpreter.DATA_COMMANDS`, `isDataCommand`, `runFromData` (refuses and logs commands off the allowlist). | `data/DialogData.java`, `util/MapDialog.java`, `stage/ConsoleCommandInterpreter.java` | Item 6: data-side glue (teleports, spawns). |
| Side-quest pool condition: `AdventureQuestData.condition` (copied); `AdventureQuestController.isOffered`, used in `getQuestNPCResponse` (fallback to the whole pool when nothing qualifies). **`AdventureQuestData` gets an explicit `serialVersionUID = -859190699184917225L`**, the default UID the class had before (checked with `serialver` on the pre-run-2 jar), so saves keep loading. | `data/AdventureQuestData.java`, `util/AdventureQuestController.java` | Item 7: allegiance/act-aware job boards. |
| Ending screen: dialog action `endGame` (`DialogData.EndGameData {title, text, image, flag}`, fixed UID): sets the character flag, then `GameStage.showEndingDialogWhenFree` waits until no dialog/reward/duel is up and `showEndingDialog` shows picture, title, text, "Continue playing" / "Main menu" (`openMenu`). | `data/DialogData.java`, `util/MapDialog.java`, `stage/GameStage.java` | Item 8: endings (S12). |
| Dev API: `/state` `statusFlags`; duel players `startingLife`, `ringTempted`, `ringBearer`, `battlefield`, `command`; `duelPopup` + `/duel/popup` (boss intro/insult FOptionPane); `/inventory {action, item}`. `gameOver` now reads `Game.getAge()` instead of the synchronized `isGameOver()` (a `/state` during a concede in the coin flip deadlocked the GL thread). | `devapi/DevApi.java` | Tests. |

Engine diff: 17 files, +471/−20 (no new classes; nested data classes in `ConfigData`, `DialogData`, `EnemyData`).

## Compatibility

- **Saves.** New keys are optional and nothing new is written to the save outside the existing Java-serialized objects. Classes that gained fields: `DialogData.ActionData` (fixed UID; new nested `FlagAdd`, `EndGameData` with fixed UIDs), `EnemyData` (fixed UID; new `DefeatData` with fixed UID), `ItemData` (fixed UID 1L), `AdventureQuestData` (had **no** UID: pinned to its previous default, see above). Round trip checked: objects serialized by the main checkout's jar (before run 2) deserialize with the run 2 jar (`condition`, `locked`, `onDefeat`, `runCommand` read as null/false). Upstream builds compute their own default UID for `AdventureQuestData`; that was already true before (any upstream change to the class breaks it).
- **Other planes.** No `statusFlags`, `flagEffects`, `onDefeat`, `locked`, quest `condition` → upstream behaviour. Shandalar: new game through the API, prologue dialog, overworld, `statusFlags: []`, no exceptions; `check.sh Shandalar` story section reports nothing (its errors are the pre-existing upstream ones).
- **`equip` now returns `boolean`** (was `void`); all callers ignore the result. Cracking skips locked items, so `Current.generateDefeatMessage` and the `crack` console command can't unequip them.
- **libGDX `Json` is strict about unknown fields** (the game's own loader too): a plane on an older engine can't load data that uses the new fields.
- `MenuScene` has its own small dialog-action runner (blessings, items, gold); the new actions only run in `MapDialog` dialogs (maps, quests, item `dialogOnUse`, enemy `onDefeat`).

## Fixtures in plane data (inert in normal play)

`config.json` `flagEffects` entry on character flag `test_flag_effect` (+7 life, Ring tempts you); item `Test Cursed Band` (`locked`, `lockCondition` on `test_lock`; sold nowhere); enemy `Test Boss Loss` (`boss`, `spawnRate` 0, in no biome; `onDefeat` with set/add flag actions, `runCommand: give gold 7`, a refused `runCommand: resetQuests`, and a dialog whose option runs `endGame` with flag `test_game_ended`). Real content: `statusFlags` "Shadow" (corruption, shown once > 0) and `"locked": true` on The One Ring.

## Test results

- `tools/middleearth/tests/run_all.sh` (DISPLAY_NUM=117): check.sh 0 errors / 2 warnings (unchanged), FlagConditionCheck 37/37, `intro_walkthrough.py`, `origin_start.py`, `flags_and_world_state.py` PASS (unchanged), `corruption.py` PASS (~64 s), `boss_loss.py` PASS (~60 s), `locked_item.py` PASS (~27 s). **ALL PASSED** (final run below).
- `corruption.py`: `flag add` clamps (min/max, 127, −128), 0 deletes, map scope; HUD label hidden at 0, "[%95]Shadow 12/100" → 7 → hidden → 9 live; duel vs Badger with `test_flag_effect`: starting life 19 = 12 + 7, `ringTempted` 1, command zone "The Ring"; without the flag: no change.
- `boss_loss.py`: normal loss to Badger costs 3 life / 35 gold; loss to `Test Boss Loss` (boss intro and insult popups clicked through `/duel/popup`): life and gold unchanged (+7 from `runCommand`), still on the overworld at the same spot, `test_boss_lost` = 1, `test_boss_corruption` = 5, `resetQuests` refused (flags kept), dialog shown, its option ran; ending screen with "Continue playing" / "Main menu", flag set, continue returns to the game.
- `locked_item.py`: without `test_lock` equip/unequip/drop work; with it the band auto-equips, unequip / drop / sell / equipping Life Amulet over it / `crack` are refused; flag cleared → unequip and drop work; The One Ring is locked and auto-equips.
- Shandalar: new game, prologue, overworld OK, no exceptions.

## Left open / risks

- `onDefeat` in a **map** (enemy object inside a POI map) is implemented but only the world-map path is covered by a GUI test.
- The ending screen shares the stage dialog: if a quest dialog opens right after it (e.g. a queued epilogue), that dialog replaces it. Put `endGame` on the last dialog of a chain.
- "Main menu" goes to the adventure start screen without saving (like the menu button); the autosave from the duel or the player's own save covers it.
- `runCommand "teleport to poi …"` starts a transition while the dialog closes; tested only through the console, not from a dialog.
- HUD layout: the labels continue the life/shards/gold column; the second and later entries sit below the HUD panel (on the map view). Portrait/Android layouts are not visually checked.
- Duel tests on the world map can meet wandering enemies; the helper hides the player, removes other enemies, teleports next to the target and retries (restoring life/gold) if another enemy gets there first.
