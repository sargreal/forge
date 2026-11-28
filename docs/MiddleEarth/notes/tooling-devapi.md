# Tooling: game control API (dev API)

Agents can drive the adventure client programmatically and read its state as text. Usage, endpoint table and examples: [DEVELOPMENT.md §3.5](../DEVELOPMENT.md#35-driving-the-game-via-the-dev-api).

## Design

- `forge-gui-mobile/src/forge/adventure/devapi/DevApi.java` (+ `DevJson.java`, a small JSON writer): a plain Java facade, `DevApi.handle(method, path, body) → Result(status, json)`. No HTTP or `com.sun` types, so the shared module stays Android/iOS safe; nothing calls it unless the server runs.
- `forge-gui-mobile-dev/src/forge/app/DevApiServer.java`: the JDK `com.sun.net.httpserver.HttpServer` front end, desktop launcher only. `GameLauncher` calls `DevApiServer.startIfEnabled()` before creating the libGDX app. It returns at once unless `-Dforge.devapi.port` (or env `FORGE_DEVAPI_PORT`) is set; it binds `InetAddress.getLoopbackAddress()` only.
- Client: `tools/middleearth/gamectl.py` (stdlib), CLI + module. Test: `tools/middleearth/tests/intro_walkthrough.py`; runner `tests/run_all.sh`.
- `game.sh start` passes `-Dforge.devapi.port=$((8700+DISPLAY_NUM))` and writes `UI_SELECTOR_MODE=Adventure` (forge.preferences) and `disableWinLose=true` (adventure settings.json) into the isolated game home. `NO_DEVAPI=1` restores the old behaviour (chooser, no port, win/lose screen). `game.sh api` prints the port, `smoke` now uses the API.

### Threading

- HTTP requests run on daemon worker threads. Every read or action runs on the libGDX thread via `Gdx.app.postRunnable` + `CompletableFuture`, with a timeout (5 s for reads; a timeout gives HTTP 504, e.g. while the world is generated on the GL thread).
- Long operations wait on the worker thread: `newGame` and `wait` poll short GL calls (100–500 ms); `reward/accept` presses `done()` and polls.
- `walkTo`/`interact` run a per-frame autopilot (`DevApi.Walker`) that re-posts itself with `postRunnable` every frame and completes a future. It steers through `GameStage.setTouchKnobInput(dx, dy)`, the on-screen joystick input, so movement speed, collisions, sprint/fly modifiers and collision triggers are exactly those of normal play. In POI maps it follows `MapStage.navMaps` (`NavigationMap.findShortestPath`, the graph enemies use); on the overworld it walks straight and reports `blocked` when it stops making progress. It stops on a dialog, scene or map change, contact with the target (`touched` after 20 frames of overlap with nothing happening), no progress for 1.5 s, or the timeout.
- The server dispatcher thread is created from a daemon thread, so it never keeps the JVM alive after the game exits.

### How actions are done

- **New game**: `NewGameScene.devApiStart(...)` sets the scene's own selectors (mode first, so the color list matches) and calls the normal `start()`; world generation, starter deck and quest 28 are the real code path.
- **Dialog choose**: fires a real `touchDown`/`touchUp` pair at the button centre (button made visible first, as a player's tap skips the typing animation). All listeners run as for a click, including the dialog's skip listener. Disabled buttons are refused.
- **Dialog read**: the open dialog is `MapStage`'s or `WorldStage`'s `GameStage.dialog` (on stage and `isDialogOnlyInput()`); quest dialogs on the overworld also use `MapStage`'s. Texts are `TextraLabel.storedText` with markup stripped and `{VAR=player_name}` substituted. UI-scene dialogs (`UIScene.dialogs`) are reported as `modal`.
- **Reward**: `RewardScene.done()`; items read from the scene's `generated` actors (reflection).
- **Duel**: state from `DuelScene.hostedMatch` (reflection). `concede` calls `PlayerControllerHuman.concede()` on a background thread. `autoplay` swaps the human seat's controller for a `PlayerControllerAi`, the same technique as `FServerManager.convertToAI`, and clears the human input queue (also unblocks the mulligan prompt).

## Engine changes (for PLAN.md → "Engine changes")

| Change | Files | Why |
| --- | --- | --- |
| Dev API: new package `forge.adventure.devapi` (`DevApi`, `DevJson`) and desktop server `forge.app.DevApiServer`, started from `GameLauncher` only with `-Dforge.devapi.port`/`FORGE_DEVAPI_PORT` (127.0.0.1). Accessors for it: `MapStage.getMapActors()`, `AdventurePlayer.getCharacterFlags()/getQuestFlags()` (unmodifiable views), `NewGameScene.devApiStart(name, race, gender, difficulty, mode, colors)`. No behaviour change without the flag. | `forge-gui-mobile/.../devapi/*` (new), `.../stage/MapStage.java`, `.../player/AdventurePlayer.java`, `.../scene/NewGameScene.java`, `forge-gui-mobile-dev/.../app/DevApiServer.java` (new), `.../app/GameLauncher.java` (one line) | Tooling: agents drive and observe the game without screenshots. |

Everything else (protected fields such as `EntryActor.targetMap`, `RewardScene.generated/type`, `DuelScene.hostedMatch`) is read by reflection inside `DevApi`, to keep the engine diff small.

## Test results (2026-11-14, DISPLAY_NUM=113, worktree build)

- `tests/intro_walkthrough.py`: **PASS 6/6 runs**, 32 s each including game start (≈15 s for the new game and the whole intro).
- Revert check: with quest 28 from `3d616c1a1^` (scratch copy, restored with `git checkout`) the test **fails** at the right step: `FAIL: portal should be closed after the prologue, is 'active'`.
- `tests/run_all.sh`: check.sh 0 errors (2 warnings) + intro test, ALL PASSED, 42 s.
- `game.sh smoke` via the API: 30 s, world map + tmx exported, no exceptions.
- `NO_DEVAPI=1 game.sh start`: the Classic/Adventure chooser shows as before, no `[devapi]` line in the log, nothing listens on 8813.
- Shandalar with the API: `newgame --plane MiddleEarth` is refused with a clear message; `newgame --difficulty Easy` starts Shandalar (prologue dialog, wizard and portal listed).
- Duel: walked into a spawned Goblin on the overworld → `duel` state (life totals, turn, phase); `duel autoplay` → the AI played the human side to the end (turn 11+), the game returned to the overworld (with `disableWinLose`) and the achievement dialog was readable via `/state`.

## Known gaps

- **Plane** can't be switched by `/newGame`: `Config` is fixed at startup, so use `game.sh start <plane>`.
- **Duels**: only state, `concede` and `autoplay` (AI for the whole game). No card-level control (play a card, choose targets); with `disableWinLose` off the win/lose screen needs a click (`/key` or xdotool).
- **Overworld walking** has no pathfinding (straight line, `blocked` on water/mountains); use `teleport` or the console `teleport to` (px) for setup. Walking past a POI enters it (`sceneChanged`).
- `/dialog/close` just hides the dialog: quest dialog queues (`AdventureQuestController`) may not continue, use `choose` for quest dialogs.
- `/dialog/choose` works on the map dialog even while the reward screen covers it (a real player must accept the reward first); tests should check `scene == RewardScene` first, as the intro test does.
- `/key` sends events to the input processor; polled keys (movement) don't see them. Shops, deck editor, inventory and other UI scenes have no dedicated endpoints yet (state shows the scene and `modal` dialogs only).
- `/screenshot` reads the back buffer after the frame; it works under Xvfb/Mesa, other drivers may give a black image.
