# Story engine audit: what STORY.md needs from Forge's adventure engine

> Research note (read-only audit, 2026-11-15). Branch base: `claude/happy-heisenberg-moqu93` at STORY v0.3.
> Scope: which story features work through **data** (quests.json, `.tmx` dialogs, points_of_interest, enemies, shops, items, config) and which need **engine (Java) changes**.
> All paths are relative to `forge-gui-mobile/src/forge/adventure/` unless they start with `forge-gui/` or `docs/`. `file:line` refs are from this checkout.
> Quest, dialog, flag and map-object formats are **not documented** in `docs/Adventure/` (only effect layers, enemies, rewards, planes and map basics are). The code and upstream's Shandalar content are the real spec.
> Everything below is read from code. Nothing was run, so the card-script snippets and the "feasible" duel tricks need a test with the sim harness before they are relied on.

## 1. Summary

| # | Feature | Verdict | Mechanism / gap | Effort |
| --- | --- | --- | --- | --- |
| 1 | Quest structure (stages, follow-ups, story vs side, givers) | **Data** (partly for branching) | Stage DAG via `prerequisiteIDs`, `issueQuest` follow-ups, `storyQuest`, dialog-based givers. No exclusive branch inside one quest; branch with flags and separate quests. | 0 |
| 2 | Flags (quest / character / map, numbers, comparisons) | **Data** with sharp edges | Byte values, `=`,`<`,`>`,`<=`,`>=`, conditions at every dialog level. Only `+1` and absolute set. Unset flags fail `get*Flag` even with `not`. `advance*Flag` does not wake stages. | 0 (add `addQuestFlag` delta: 0.25 d) |
| 3a | Act: overworld enemy lists per flag | **Engine** (small hack in data) | `BiomeData.enemies` is static. Data hack: an active `Defeat` stage injects tagged enemies globally. | 0.5 d |
| 3b | Act: shop stock per flag | **Engine** | No condition on `RewardData`. | 0.25 d |
| 3c | Act: POI appears | **Data** | `questFlagsToActivate` (quest flag `>=`). | 0 |
| 3d | Act: POI disappears / town variants | **Partly / engine** | No deactivate. Map objects have no flag condition (`spawnCondition` is a stub). Dialog text can change by flag. | 0.25 d + 0.5 d |
| 4 | Origins (start location + first quest per race/colour) | **Engine** | Start is hard-wired to world start then POI `Spawn`, quest 28. Race list is data (add a race for free). | 1 d |
| 5a | Corruption as a number, thresholds, NPC refusals | **Data** (coarse) | Quest/character flag as meter. | 0 |
| 5b | Corruption HUD | **Engine** | No custom HUD stat. Quest log/notification workaround only. | 0.5–1 d |
| 5c | Corruption in duels ("Ring tempts you") | **Partly** | Effect layers are static; `startBattleScript` can do anything but is not flag-aware. Item-swap trick is fragile. | 0.5 d |
| 6 | Scripted cameo events | **Data**, mostly | Stage prologue/epilogue dialogs, conditional dialogs, `hidden`/`inactive` enemies + `activateMapObject`, `deleteMapObject` (one-time), Hunt stage for world-map cameos. Flag-conditioned object presence needs `spawnCondition`. | 0 (0.5 d for `spawnCondition`) |
| 7a | Boss duel with special rules | **Data** | `EnemyData.life/effect/ai/boss/gamesPerMatch/nextEnemy`, `startBattleScript`, `opponent` block. | 0 |
| 7b | Lose the duel without the normal defeat | **Partly** | Loss can fail a stage and run a `failureDialog` (data). The life/gold penalty and respawn teleport still happen. | 0.5 d |
| 8 | Endings | **Data** (no end screen) | Final dialog with options gated by flags. No ending scene; game simply continues. | 0 (screen: 1–2 d, optional) |
| 9a | One Ring as a quest reward | **Data** | `addItem` / `grantRewards` Item. `unique` and `questItem` already exist. | 0 |
| 9b | Ring equip-locked | **Engine** | Player can unequip. | 0.25 d |
| 9c | Pursuit (forced Nazgûl) | **Data** | `getExtraQuestSpawns`: an active `Defeat` stage tagged `Nazgul`. | 0 |
| 10a | Allegiance as a flag, dialogs and quests by allegiance | **Data** (side-quest pool: engine) | Character flag + conditions. `allSideQuests` filter ignores flags. | 0 (0.25 d) |
| 10b | Hostile vs neutral enemies by allegiance | **Engine** | All overworld enemies chase and fight. Only map NPCs with `dialog` are non-hostile. | 0.5 d |

Pragmatic reading: **almost all quest content is data**. The engine gaps are about *world state* (things that must differ by act, allegiance or corruption) and *start-of-game*. One shared mechanism, a `FlagCondition` (key, scope, op, val) usable in a few more places, covers 3a, 3b, 3d, 5c and 10b.

## 2. Per-feature details

### 1. Quest structure

**Data model.** `world/quests.json` is an array of `AdventureQuestData` (`data/AdventureQuestData.java`) loaded by `AdventureQuestController.loadData` (`util/AdventureQuestController.java:~326`). A quest has `id`, `isTemplate: true`, `name`, `description`, `storyQuest`, `questSourceTags`, `offerDialog`, `prologue`, `epilogue`, `failureDialog`, `declinedDialog`, `stages[]`. Template ids are looked up by `generateQuest(id)` (`AdventureQuestController.java:~520`).

**Stage objectives** (`AdventureQuestController.ObjectiveTypes`, `:114`; handled in `AdventureQuestStage.handleEvent`, `data/AdventureQuestStage.java:268`):

| Objective | Fires when | Notes |
| --- | --- | --- |
| `Travel` / `Delivery` | entering the target POI (`POITags`, `POIToken`, `here`, `anyPOI`; or world map with `worldMapOK`) | `Delivery` is a plain `Travel` (no item logic). |
| `Leave` | leaving a POI | |
| `Defeat` (`:295`) | win over a matching enemy (`enemyTags`/`enemyExcludeTags`, `mixedEnemies`), `count3` wins; `count4` losses fail the stage | `count3` 0 means the first win. On the world map set `worldMapOK`. |
| `Hunt` (`:342`) | defeating one spawned named sprite; despawn after `count3` seconds fails it | Spawns the sprite 2.5x further than normal enemies (`WorldStage.spawnQuestSprite`). |
| `Clear` | all enemies in a dungeon defeated | |
| `Fetch` | receive (`RECEIVEITEM`) or use (`USEITEM`) the `itemNames`; auto-completes if the items are already held (`:269`) | |
| `Use` | use an item | |
| `QuestFlag` / `CharacterFlag` / `MapFlag` | the named flag (`mapFlag` field) is **set** to `>= mapFlagValue` | See the flag quirks in §2. |
| `CompleteQuest` | another quest (from a POI id) completes | |
| `Arena`, `EventFinish`, `EventWin`, `EventWinMatches` | arena or inn events | |
| `HaveReputation`, `HaveReputationInCurrentLocation` | town reputation `>= count3` | |

`None`, `Escort`, `Find`, `Gather`, `Give`, `Patrol`, `Rescue`, `Siege` are in the enum but have **no handler** in the switch. Do not use them.

**Flow.** `prerequisiteIDs` is an AND-join (`AdventureQuestStage.checkPrerequisites`, `:72`). Several stages with the same prerequisite run in parallel. The quest completes when **all** stages are `COMPLETE` (`AdventureQuestData.updateStages`, `:393`). So there is **no exclusive branch inside a quest**: a stage that never activates blocks completion. Branching is done with:

- dialog **options** that set flags and/or `issueQuest` a *different* follow-up quest (Shandalar: `quests.json` quest 45 issues quest 46 from a stage `epilogue`);
- a stage failing (`count4`) → `failureDialog` actions → `issueQuest` (see §7).

**Follow-up quests.** Action `issueQuest: "<id>"` in any dialog (`util/MapDialog.java:470`, `DialogData.ActionData.issueQuest`) adds the quest; stage and quest dialogs are queued by `AdventureQuestController.showQuestDialogs` (`:160`), which runs actions of option-less dialogs too (`displayNextDialog`).

**Story vs side.** `storyQuest: true` means: no abandon button (`scene/QuestLogScene.java:166`), excluded from the random side-quest pool (`AdventureQuestController.java:336`), ignored by the town quest NPC (`:578`). Story quests are given by dialogs (map `dialog` objects with conditions, stage epilogues, other quests). Shandalar's main chain is quests 28–33 and 43–53, all `storyQuest`.

**Town quest givers and the job board.** A map object of `type: "quest"` with property `questtype` (`stage/MapStage.java:679`) creates an invisible `QuestActor` (`character/QuestActor.java`). Touching it calls `getQuestNPCResponse` (`:573`): a random non-story template filtered by `questSourceTags` (match on `questtype`; empty tags match everywhere), max 5 open side quests, and "come back tomorrow" using **the real-world date** (`LocalDate.now()`), with a 15% chance of none (`locationHasMoreQuests`). That is the job board. It is flag-blind (see 10a).

**Tokens** available in quest text: `$(poi_N)`, `$(biome_N)`, `$(enemy_N)`, `$(playername)`, `$(playerrace)`, `$(currentbiome)` (`AdventureQuestData.initializeStage`).

**Quest rewards.** The quest-level `reward` field is **not granted by any code**. Rewards come from dialog actions: `grantRewards` / `grantRewardsChoice` (`MapDialog.java:454`) with `RewardData` (`"type": "Item", "itemName": "..."`), or `addItem`, `addGold`, `addShards`, `addLife`, `giveBlessing`, `addMapReputation`.

**Verdict: data.** Needs for STORY.md: origin arcs of 2–4 quests (chain with `issueQuest`), act milestones (stage → flag), hubs (Rivendell) as POIs with conditional dialogs. All covered.

### 2. Flags

| Kind | Storage | Set / read | Triggers stages |
| --- | --- | --- | --- |
| Quest flag | `AdventurePlayer.questFlags` (`player/AdventurePlayer.java:69`), cleared by `resetQuestFlags` (New Game+ / console `resetQuests`) | actions `setQuestFlag{key,val}`, `advanceQuestFlag`; conditions `checkQuestFlag`, `getQuestFlag{key,op,val}` | `QuestFlag` stage, on **set** only |
| Character flag | `characterFlags` (`:70`), permanent | `setCharacterFlag`, `advanceCharacterFlag`; `checkCharacterFlag`, `getCharacterFlag` | `CharacterFlag` stage, on **set** only |
| Map flag | per POI, in `PointOfInterestChanges.mapFlags`, only visible inside that map | `setMapFlag`, `advanceMapFlag`; `checkMapFlag`, `getMapFlag` | `MapFlag` stage, on set **and** advance (`stage/MapStage.java:1272,1279`) |

Operators (`MapDialog.checkFlagCondition`, `:587`): `=`/`EQUAL`/`EQUALS`, `<`/`LESSTHAN`, `>`/`MORETHAN`, `<=`/`LE_THAN`, `>=`/`ME_THAN`.

Dialog `condition[]` is evaluated by `MapDialog.isConditionOk` (`:487`) for every top-level dialog entry of an object **and** every option, at any depth. All fields of one condition object are ANDed, `not: true` inverts each check. Other condition fields: `item`, `hasGold`, `hasShards`, `hasLife`, `hasMapReputation`, `hasBlessing`, `actorID` (is an object still in the map), `colorIdentity`.

**Quirks to design around:**

1. Flags are `Byte` (-128..127). `setQuestFlag` casts `(byte) value`, and `advance` has no overflow check (`AdventurePlayer.java:1488-1544`). Fine for acts and a 0–100 meter.
2. **Setting a flag to 0 deletes it**, and `getQuestFlag`/`getCharacterFlag`/`getMapFlag` conditions return **false when the flag is not set, even with `not: true`** (`MapDialog.java:549-556`). So a meter at 0 fails `corruption < 3`. Use `checkQuestFlag` + `not` for "is zero", or keep the meter offset by +1.
3. `advanceQuestFlag` and `advanceCharacterFlag` do **not** call `AdventureQuestController.updateQuests*Flag` (only `set*` does, `AdventurePlayer.java:1493,1528`), so a `QuestFlag` stage never completes from an advance. Use `set`, or fix (1 line each).
4. No add-N, no subtract, no flag-to-flag compare. `+3` is three `advanceQuestFlag` entries in the same `action[]`; `-1` is only possible as an absolute set under a condition for each value.
5. The `colorIdentity` condition looks inverted (`MapDialog.java:497-503`: returns false when the player *has* the colours unless `not` is set). Test before using.
6. There is no condition on race, gender or difficulty.

**Visibility by flag today:**

- POI on the world map: `questFlagsToActivate: [{"key","val"}]` in `points_of_interest.json` (`data/PointOfInterestData.java:31`), checked every frame by `PointOfInterest.getActive` (`pointofintrest/PointOfInterest.java:137`: quest flag `>= val`) for drawing and entry (`stage/PointOfInterestMapSprite.java:35`, `stage/WorldStage.java:241`). Example (in our fork, `Black Castle`): `"questFlagsToActivate": [{"key": "mainQuest", "val": 2}]`. The `active` field of the same file is **ignored** by `getActive`.
- Map objects: portals take `activeQuestFlag` / `inactiveQuestFlag` / `closedQuestFlag` (`MapStage.java:482-488`, set means on). Enemies take `hidden` and `inactive` and can be revealed with the action `activateMapObject` (`:930`, enemies and portals only). Dialog actors take `hidden` but cannot be revealed. `deleteMapObject` removes an object for good (persisted in `PointOfInterestChanges.deletedObjects`).
- A `spawnCondition` map property is read in `canSpawn` (`:404`) but the body is **empty**. `EnemySprite.spawnCondition` (`character/EnemySprite.java:70`) and `CharacterSprite.spawnConditions` (`:32`) are declared but unused. These are ready-made hooks (see recommendations).

### 3. Acts and world state

There is no global clock. A calendar act is a flag set by a milestone stage (e.g. quest flag `act` = 1..3, or a character flag `act` so it survives `resetQuestFlags`; note POI activation reads **quest** flags only).

**3a. Enemy lists per flag: engine.** `BiomeData.enemies` is a static list of names (`data/BiomeData.java:36`), turned into `enemyList` once and cached (`getEnemyList`, `:51`; `getEnemy`, `:106`, reads the cached field). `WorldStage.handleMonsterSpawn` (`stage/WorldStage.java:294`) uses it, `MapStage` uses `getEnemy` as a fallback (`:528`). Nothing consults a flag.

*Data-only partial*: `BiomeData.getExtraSpawnEnemy` (`:98`) adds enemies for every **active `Defeat` stage** of any quest in the log (`AdventureQuestController.getExtraQuestSpawns`, `:37`), globally across all biomes and with no difficulty filter. They spawn on about 80% of spawn ticks (`WorldStage.java:321-338`: 50% both, 30% extra only; ticks every 4–8 s). A "background" quest per act with a never-finishing `Defeat` stage on tag `ActII_Uruk` injects Uruk-hai everywhere. Cons: not per biome, base enemies stay, the quest shows in the log, the stage needs a large `count3` (max 127 is fine) and the quest cannot be cancelled by a flag.

*Minimal engine change*: add to `BiomeData` an optional list, for example:

```json
"enemyOverrides": [
  { "when": { "scope": "character", "key": "act", "op": ">=", "val": 2 },
    "mode": "replace", "enemies": ["Uruk-hai Warband", "Dunlending Raider"] }
]
```

`getEnemyList()` (and the cache) picks the last matching override or merges (`mode: add|replace`) per call, keyed by the active rule index so the cache survives. Needs a small shared class `FlagCondition` (key, scope quest|character, op, val) with a static `eval()` extracted from `MapDialog.isConditionOk`/`checkFlagCondition`. Files: `data/BiomeData.java`, new `data/FlagCondition.java`, `util/MapDialog.java` (reuse), `tools/middleearth/PlaneCheck` (validate names). **~0.5 day.** The same override mechanism gives allegiance variants (10b).

**3b. Shop stock per flag: engine.** Stock is generated lazily on first shop entry from the shop's `rewards` (`character/ShopActor.java:84-93`), so a late check sees the current flags. `RewardData` has no condition field (`data/RewardData.java`). Add `"condition": FlagCondition` to `RewardData` and return an empty array at the top of `generate(...)` (`:172`). That also works for enemy drops and chests. **~0.25 day.** Data-only alternative: separate shops per act (`shops.json`) chosen by the map's `commonShopList`... but that list is random by rarity, not by flag, so no.

**3c. POI appears: data** (see §2). Example in `points_of_interest.json`:

```json
{ "name": "Helm's Deep Wall", "type": "town", "map": "...", "questTags": ["Cameo"],
  "questFlagsToActivate": [ { "key": "act", "val": 2 } ] }
```

**3d. POI disappears, town variants: partly.**
- Disappear: `getActive` only checks `>=`. Add `questFlagsToDeactivate` (hide when `>= val`). **~0.25 day** (`PointOfInterest.getActive`, `PointOfInterestData`, copy ctor). Workaround today: none.
- Town maps per act: a POI has one `map` path. The cheap route is **one map with objects conditioned per act**: implement the existing `spawnCondition` stub for every object type in `MapStage.loadObjects` (`:411`): parse the property as a `FlagCondition[]` and `continue` the loop when false (objects are rebuilt on every map entry, so no refresh problem). **~0.5 day** and it also covers NPC presence, shop presence and refusals (10b). Alternative: `PointOfInterestData.mapVariants` keyed by flag, resolved in `WorldStage.loadPOI`.
- Dialogs: already data. An NPC dialog list can contain several top-level entries with different `condition`s, e.g. act-I text and act-II text on the same object.

### 4. Origins

**Today.** `scene/NewGameScene.start()` (`:390`) calls `WorldSave.generateNewWorld(...)` which places the player at the world's start (`world/WorldSave.java:227-228`, from `playerStartPosX/Y`; for the Tiled world from the map's `start` object, `world/World.java:358-367`). Then:

- `WorldStage.enterSpawnPOI()` (`NewGameScene.java:411`, `stage/WorldStage.java:402`) makes `WorldStage.enter()` load the POI named `Spawn` (`findPointsOfInterest("Spawn")`, `:461`), which in our plane is "Wizard's Hollow" (`forge-gui/res/adventure/MiddleEarth/world/points_of_interest.json:2`, map `main_story/spawn.tmx`);
- the intro quest is hard-wired: `addQuest("28", true)` (`NewGameScene.java:413`, and for old saves `scene/SaveLoadScene.java:325`);
- death respawn also teleports to `Spawn` (`stage/GameStage.java:715-737`).

Race and colour are plain character-creation choices (`race` index into `HeroListData.getRaces()` from `heroes.json`, `colorId`). `AdventurePlayer.raceName()` (`:992`) exposes the race. Quest text can use `$(playerrace)` but **no condition can test race**.

**Data-only partial.** Add a race entry per origin in `heroes.json` (reusing sprites) so the creation screen already offers "Dunlending". Everyone still starts in the Hollow, where quest 28 could ask "who are you" via dialog options that set `allegiance` / `origin` character flags and `issueQuest` the origin arc. This cannot move the start (no teleport action, no way to key on race).

**Minimal engine change** ("start in Dunland with quest X"):

1. `config.json` gets `origins: [{ "race": "Dunlending", "colors": "B" (optional), "startPOI": "Dunland Camp", "introQuest": 60, "characterFlags": [{"key":"allegiance","val":2},{"key":"origin","val":2}], "respawnPOI": "Dunland Camp" }]`, class `data/OriginData.java`, field in `ConfigData` (`data/ConfigData.java`).
2. `NewGameScene.start()`: after `generateNewWorld`, look up the origin by race (and colour), apply flags, replace `"28"` by `introQuest`, call `WorldStage.enterSpawnPOI(startPOI)` (a name parameter, default `"Spawn"`) and set the world position to that POI.
3. `AdventurePlayer`: persist `respawnPOI` (save key + load); `GameStage.resetPlayerLocation` uses it. `SaveLoadScene:325` keeps 28 as a fallback.
4. The start POI needs a map where the intro dialogs run; any town `.tmx` with an entry works, the intro quest can be `prologue` dialogs.

Files: `ConfigData`, new `OriginData`, `NewGameScene`, `WorldStage`, `GameStage`, `AdventurePlayer`, `SaveLoadScene`. **~1 day.** Convergence on the act I hubs is then plain quest content.

### 5. Corruption meter

**5a. As a flag: data.** Yes, a quest/character flag (`corruption`, offset by 1 so 0 never means "unset") works as the meter with no engine work:

- raise: `advanceQuestFlag` (repeat in `action[]` for +N) or `setQuestFlag` to an absolute value under conditions; lower (cleansing): `setQuestFlag` under `getQuestFlag` conditions (verbose), no subtract action;
- sources: dialog actions at any choice point, quest-stage epilogues, item use (`ItemData.dialogOnUse`, `InventoryScene.triggerUse`, `:291-310`: a full dialog with actions and conditions; the item is **not** consumed unless the dialog does `removeItem`; `usableOnWorldMap` / `usableInPoi` enable the button, `commandOnUse` runs a console command, `shardsNeeded` costs shards), `grantRewards`;
- thresholds: dialog conditions (`getQuestFlag {key:"corruption", op:">=", val:6}`) give refusals ("the Elves will not speak to you"), shadow-only options, cleansing offers. Quest `QuestFlag` stages react to thresholds only when **set** (quirk 3);
- passive threshold checks: an option-less, text-less dialog with only `action[]` is run as an **area trigger** (`MapDialog.loadDialog`, empty dialog case). Placing such a `dialog` object near each town entrance gives "on entering a town, if corruption >= N and not yet flagged, issue quest X". There is no such trigger for the open world map.

Example (Athelas item, cleansing by one step when the meter is exactly 3; conditions repeated per value):

```json
{ "name": "Athelas", "iconName": "...", "usableOnWorldMap": true, "usableInPoi": true,
  "dialogOnUse": {
    "text": "The sweet smell of the kingsfoil clears your head.",
    "action": [ { "removeItem": "Athelas" } ],
    "options": [
      { "name": "(Breathe)", "condition": [ { "getQuestFlag": { "key": "corruption", "op": "=", "val": 4 } } ],
        "action": [ { "setQuestFlag": { "key": "corruption", "val": 3 } } ] },
      { "name": "(Breathe)", "condition": [ { "getQuestFlag": { "key": "corruption", "op": "=", "val": 3 } } ],
        "action": [ { "setQuestFlag": { "key": "corruption", "val": 2 } } ] }
    ] } }
```

(`dialogOnUse.text` must be non-empty or `InventoryScene` skips the dialog, `:302`.) The cleaner fix is a delta action: `addQuestFlag: { "key": "corruption", "val": -1, "min": 1, "max": 20 }` in `DialogData.ActionData` + `MapDialog.setEffects` (+ `AdventurePlayer.addQuestFlag`, firing `updateQuestsQuestFlag`). **~0.25 day**, and it removes quirks 3 and 4 for the meter.

**5b. HUD: engine.** `GameHUD` has fixed labels `lifePoints`, `money`, `shards`, `keyCollection`, `enemyCounterText` bound to widgets in `ui/hud.json` (+ `hud_landscape.json`, `hud_portrait.json`; `stage/GameHUD.java:166-175`), refreshed through `AdventurePlayer.onGoldChange(...)` style signal lists (`:203`). A proper meter needs: a new `TypingLabel` (or small bar) in the three HUD layouts (the plane can override `ui/*.json`, `Config.getFile` looks in the plane folder first), a `GameHUD` field + `findActor("corruption")`, and a refresh trigger, either a new signal on `AdventurePlayer` fired from `set/addQuestFlag` for a configured key, or a cheap poll in `GameHUD.act`. Make the key and the icon/text configurable (`config.json` `hudFlag: {key, label}`) so the HUD stays generic. **~0.5–1 day** including the three layouts. Data-only workaround: a pseudo "burden" quest whose sequential `QuestFlag` stages are named by level; each stage activation fires a HUD notification (`GameHUD.addNotification`, `AdventureQuestData.activateNextStages`) and the quest log shows the current level. Coarse, and needs the stage triggers set (not advance).

**5c. Duel effects.** Effect layers (`docs/Adventure/Configure-Planes.md` "Duel effect layers", `DuelScene.java:505-534`): player's equipped items and blessing, then global (`config.json` `globalEffect`), modifiers, biome, dungeon, enemy `effect`. Fields: `lifeModifier`, `changeStartCards`, `startBattleWithCard`, `startBattleWithCardInCommandZone`, `startBattleScript`, `extraManaShards`, `opponent` (`data/EffectData.java:24-39`). `startBattleScript` lines are real card-script abilities run for that side after mulligans (`forge-game/.../Player.java:3163 runStartGameScripts`, host = a `DetachedCardEffect` in the command zone), so `DB$ RingTemptsYou` (effect class exists: `RingTemptsYouEffect`), `DB$ Effect` with triggers (per-turn emblems) and `DB$ MakeCard` are all reachable. Not verified in a duel yet.

None of the layers is flag-aware. Data workarounds: (a) `giveBlessing` (an `EffectData`) from a dialog, which lasts for the **next battle only** (`clearBlessing()` runs after each duel, `DuelScene.GameEnd`); (b) swap tiered items "Burden I/II/III" with `removeItem`/`addItem` at thresholds, but they only apply when **equipped**, and the player can unequip (see 9b). Engine: add `config.json` `flagEffects: [{ "when": FlagCondition, "effect": EffectData }]`, applied in `DuelScene` next to the global layer (`addEffect(oppEffects, playerEffects, ...)`). Tiers then cost no inventory tricks, and Free and Shadow players both get "the Ring tempts you". **~0.5 day** (an inventory-wide `passive: true` item flag, rec. in 9b, is the alternative route).

**World reactions.** Enemy spawn changes: via 3a overrides keyed on `corruption`. NPC refusals: dialog conditions (data). Nazgûl/Gollum following at a threshold: a quest issued by a trigger dialog whose `Defeat`/`Hunt` stage spawns them (see 9c).

### 6. Scripted cameo events

What the engine gives, all data:

- **Cutscene-like dialog at a place and time.** A `Travel` stage whose target is a tagged POI (`POITags`, `allowInactivePOI`) with `epilogue` (shown on completion) or the next stage's `prologue` (shown at activation). Dialogs chain through `options` (text pages), can run any action, and have optional `voiceFile`. Stage dialogs are queued by `showQuestDialogs` (`AdventureQuestController.java:160`) right after events.
- **Act-conditioned.** Issue the cameo quest from a conditional dialog or a trigger dialog (§5a), gated on `act` and "not yet seen" flags. Or set `questFlagsToActivate` on a cameo-only POI (3c).
- **One-time.** Quest completes once; set a `seen_<cameo>` flag; for objects inside maps `deleteMapObject` persists.
- **An NPC appears.** An `enemy` map object with `hidden: true`/`inactive: true`, a `dialog` property (contact dialog replacing the battle) and an `activateMapObject` action from a trigger (`MapStage.java:518-615, 930`). The NPC can have `waypoints` for movement. Static presence by flag needs `spawnCondition` (3d).
- **A forced duel with special rules.** A dialog option with `battleWithActorID` (`MapDialog.java:422`) against an `enemy` object that carries `effect`, `deckOverride`, `reward`, `defeatDialog` properties (`MapStage.java:535-596`); or a `Hunt`/`Defeat` stage on an `EnemyData` that has its own `effect` (§7).
- **World-map cameo.** `Hunt` stage: a named `EnemyData` sprite spawns near the player and chases for `count3` seconds. Use for "a Rider on the road".
- **An object activation.** Portals toggle with `activateMapObject` (open/close gates).

Limits: no time-of-day or date triggers (the only clock is real-time `LocalDate` in the town quest NPC), no actor scripting beyond waypoints and dialogs, no camera control. Dialog actors cannot be shown by flag (3d `spawnCondition` fixes that). **Verdict: data, with `spawnCondition` as the one useful engine add.**

### 7. Boss duel with special rules

All of this is `EnemyData` (`data/EnemyData.java`, entry in `enemies.json`):

| Need | Field | Ref |
| --- | --- | --- |
| Extra life | `life` (scaled by difficulty `enemyLifeFactor`, `DuelScene.java` ~`setStartingLife`) and/or `effect.lifeModifier` | `EnemyData.java` life; `EffectData.java:24` |
| Starting permanents / emblems / a Command-zone card | `effect.startBattleWithCard`, `startBattleWithCardInCommandZone` | `EffectData.java:26-27` |
| Rules effects every turn, "the Ring tempts you" | `effect.startBattleScript` (card-script; `DB$ Effect` + trigger, `DB$ RingTemptsYou`) | `EffectData.java:28`, `Player.java:3163` |
| Handicap on the player | `effect.opponent` block (player side for enemy effects) | `DuelScene.java:~79` |
| Strongest AI | `ai: "Hardest"` (also the default for `boss: true`) | `DuelScene.selectAI`, `:979` |
| Boss music, intro and loss popups | `boss: true`, `bossIntro`, `bossInsult` | `EnemyData.java:31,50`, `DuelScene.GameEnd` `:213` |
| Best of N | `gamesPerMatch` | `EnemyData.java:48` |
| Several AIs / teams | `nextEnemy`, `teamNumber` | `EnemyData.java:43`, `DuelScene` enemy loop |
| Chosen deck | `deck[]`, `deckOverride` map property | |

Example (a Sauron-style boss, untested script lines):

```json
{ "name": "Sauron, the Eye", "ai": "Hardest", "boss": true, "life": 45, "gamesPerMatch": 1,
  "bossIntro": "The Eye turns upon you. The Ring answers.", "deck": ["decks/enemy/shadow/sauron.dck"],
  "questTags": ["Boss", "Sauron"],
  "effect": { "lifeModifier": 10, "startBattleWithCard": ["Barad-dûr"],
    "startBattleScript": ["DB$ RingTemptsYou"],
    "opponent": { "startBattleScript": ["DB$ RingTemptsYou"] } } }
```

**"Lose the duel without the normal defeat": partly.**
- Data side works: on a loss `updateQuestsLose` runs (`stage/WorldStage.java:209`, `stage/MapStage.java:872`). A `Defeat` or `Hunt` stage with `count4: 1` becomes `FAILED` (`AdventureQuestStage.java:300-303`), the stage's `failureDialog` and the quest's `failureDialog` are queued (`AdventureQuestController.java:171-190`) and their **actions run** (`setQuestFlag: sauronWon`, `issueQuest: "<captured quest>"`), then the quest is removed. So "lose → different outcome" is plain data.
- But the stock penalties still happen: `AdventurePlayer.defeated()` (`:1144`) takes `difficulty.lifeLoss` and `goldLoss`; if life < 1 the player is teleported to `Spawn` with the defeat image (`GameStage.resetPlayerLocation`, `:715`); `boss: true` shows the insult popup (`DuelScene.java:213`) and `defeatedFromBoss` (`GameStage.java:739`); inside a map the player is thrown out (`MapStage.java:879`).
- Minimal engine change: `EnemyData.noDefeatPenalty` (or `onLose: "quest"`) skipping `defeated()`, the respawn and the insult/image popups in `WorldStage.setWinner` (`:199-219`), `MapStage.setWinner` (`:857-883`) and `DuelScene.GameEnd` (`:213`). **~0.5 day.** Without it, make the finale a non-`boss` enemy (no insult) and accept the life/gold hit, or set the lifeLoss low on the difficulty.

### 8. Endings

- No ending scene, credits, victory state or hall of fame exists in the engine (searched `adventure/**`). Quest completion just removes the quest from the log and the game goes on.
- **Multiple endings by flags: data.** The final stage `epilogue` (or `failureDialog`) is a dialog tree whose top-level entries/options carry `condition[]` (e.g. `getQuestFlag corruption >= 14` plus `checkCharacterFlag ringWon`); only matching branches show (`MapDialog.activate`, `:~352`). Each branch's `action[]` sets `ending = n`, grants a title item, and can `issueQuest` an epilogue quest (e.g. the Scouring of the Shire). Condition fields are ANDed; use several branches in order for the matrix in STORY.md S12.
- **After the ending: continue playing.** There is no end state, so the world just stays at the last act. That matches STORY.md's open epilogue. The engine also has a New Game+ path (`newGamePlus` character flag, `resetQuestFlags`, `AdventurePlayer.java:1553-1560`).
- **An ending screen** (image, text, "continue / main menu") would be a new `Scene` plus an action `showEnding: "<id>"` in `ActionData`. **~1–2 days, optional.** A cheap data version: an "epilogue" POI map (a `.tmx` with `dialog` objects) unlocked by `questFlagsToActivate`, with music and a final dialog.

### 9. The One Ring

- **Item exists**: `items.json` "The One Ring" (Neck slot, `unique: true`, `lifeModifier: -3`, starts with the card on the battlefield, `opponent` starts with a Nazgûl; `forge-gui/res/adventure/MiddleEarth/world/items.json:969`). It is already out of the shop (STORY S9).
- **Grant via quest: data.** Epilogue action `{ "addItem": "The One Ring" }` (`MapDialog.java:~385`) or `grantRewards` with `{ "type": "Item", "itemName": "The One Ring" }`. `unique` blocks duplicates (`AdventurePlayer.addItem`, `:1457-1462`).
- **Unsellable / undeletable.** There is no item selling in `ShopScene` (it handles cards only), and `questItem: true` disables the delete button (`scene/InventoryScene.java:438`). Add `"questItem": true` to the item. Note `removeAllQuestItems` (`AdventurePlayer.java:1472`) wipes quest items: check its callers before relying on it (a New Game+ reset is the intended use).
- **Equip-locked: engine.** `AdventurePlayer.equip` (`:1396`) is a toggle and the Inventory shows "Unequip" (`InventoryScene.java:458`); deck loadout switching can also drop equipment (`AdventurePlayer.java:218-245`). Add `ItemData.cursed: true`: in `equip` refuse to unequip, on `addItem` auto-equip it (replacing the Neck item), keep it equipped in loadout switches. **~0.25 day.** Better alternative that also serves corruption tiers: `ItemData.passive: true`, where inventory items with it feed `DuelScene` effect collection (`:505`) and map speed without being equipped. **~0.25 day.**
- **Pursuit: data.** `getExtraQuestSpawns` (`AdventureQuestController.java:37`) adds every enemy matching an ACTIVE `Defeat` stage's `enemyTags` to the overworld spawn pool in every biome. The plane already tags Nazgûl enemies `"Nazgul"` (`enemies.json`, including "Winged Nazgul"). Issue a "The Hunted" quest in the same dialog that does `addItem`:

```json
{ "id": 70, "isTemplate": true, "storyQuest": true, "name": "The Nine Ride",
  "stages": [
    { "id": 1, "objective": "Defeat", "mixedEnemies": true, "worldMapOK": true,
      "enemyTags": ["Nazgul"], "count3": 5, "name": "Break the Nine's pursuit",
      "description": "They will not stop while you carry it." },
    { "id": 2, "objective": "Travel", "prerequisiteIDs": [1], "POITags": ["Mordor_Approach"],
      "name": "...", "description": "..." } ] }
```

  A `Hunt` stage (single named hunter that despawns after `count3` seconds, failing the stage) adds "the Witch-king himself". The pursuit ends when the stage's `count3` is reached or the quest otherwise completes; it cannot be cancelled by a flag today (`quest.fail()` is only reachable from the abandon button, hidden for story quests). If a flag-cancel is wanted: a 5-line `failIfFlag` check on stages. The spawn tick is 4–8 s (`WorldStage.spawnInterval`), so tune with `count3` and spawn weights.

### 10. Allegiance

- **A global choice: data.** `setCharacterFlag {key:"allegiance", val:1|2}` (character flags survive `resetQuestFlags`) set by the origin's intro dialog (or by 4's engine origin table). Dialog conditions `getCharacterFlag allegiance = 2` gate options, NPC lines, shop-keeper refusals ("we do not serve your kind", as a dialog on the NPC), and the issuing of quests from story dialogs.
- **Quest availability.** Story quests: gated by the dialog that issues them (data). The random side-quest pool is flag-blind (`getQuestNPCResponse` filters only by `questSourceTags`, `AdventureQuestController.java:619-632`). Add an optional `condition` (FlagCondition list) on quests and filter there. **~0.25 day.**
- **Hostile vs neutral: engine.** All overworld enemies chase and fight on contact (`WorldStage.java:100-122`); there is no faction, "friendly" or reputation-with-enemy concept in `EnemyData` or `EnemySprite`. What exists: (a) map `enemy` objects with a `dialog` property replace the battle with a conversation (so an orc in a town can talk, and the dialog can offer a fight with `battleWithActorID`); (b) the `hide` console command / item (`PlayerModification.Hide`, `stage/GameStage.java:285-310`) stops enemies approaching for N seconds; (c) town **reputation** per POI (`addMapReputation`, `hasMapReputation`, price modifier ±10%, `PointOfInterestChanges.java:135-166`) for town standing.
- **Minimal engine change**: reuse the flag-conditioned enemy lists (3a). Allegiance becomes `enemyOverrides` entries keyed on `allegiance`, so Shadow players get Free Peoples spawns instead of orcs, and vice versa. For a finer rule add `EnemyData.faction` plus `config.json` `allegianceRules` ("allegiance 2 ignores faction Shadow": skip in `BiomeData.getEnemy`, and make `WorldStage` not spawn them). For "towns refuse you" use `spawnCondition` on the shop/inn objects (3d). **~0.5 day on top of 3a.**

## 3. Recommended minimal engine additions

Ordered by value for STORY.md. Each is small and isolated, so upstream merges stay cheap (record each in PLAN.md "Engine changes"). Suggested shared building block first.

| Prio | Addition | Unlocks | Where | Effort |
| --- | --- | --- | --- | --- |
| **0** | **`FlagCondition` helper**: `{scope: quest\|character\|map, key, op, val, not}` plus `static boolean eval(...)`, extracted from `MapDialog.isConditionOk`/`checkFlagCondition`. Also fix the quirks: `advance*Flag` fires `updateQuests*Flag` (`AdventurePlayer.java:1496,1531`), and treat an unset flag as 0 in `get*Flag` conditions. | Base for everything below. Makes `QuestFlag` stages react to advances. | new `data/FlagCondition.java`, `util/MapDialog.java`, `player/AdventurePlayer.java` | 0.5 d |
| **1** | **Flag-conditioned world state**: `BiomeData.enemyOverrides[{when, mode: replace\|add, enemies}]`, `RewardData.condition`, `PointOfInterestData.questFlagsToDeactivate`. | Acts I–III (enemy lists, shop stock, POIs appearing and disappearing); allegiance spawn variants (10b). | `data/BiomeData.java` (`getEnemyList`), `data/RewardData.java:172`, `pointofintrest/PointOfInterest.java:137`, `PointOfInterestData.java`; checker validation in `tools/middleearth` | 1 d |
| **2** | **`spawnCondition` for map objects** (finish the stub: parse the existing `spawnCondition` property as `FlagCondition[]`, skip object creation when false). Applies to every object type. | Town variants per act, cameo NPCs present only in act N, shops/inns refusing Shadow or corrupted players, hub changes without duplicating maps. | `stage/MapStage.java:394-409` (`canSpawn`) and `loadObjects` (`:411`) | 0.5 d |
| **3** | **Origins**: `config.json` `origins[]` (race, optional colour, `startPOI`, `introQuest`, flags, `respawnPOI`); `NewGameScene.start`, `WorldStage.enterSpawnPOI(name)`, persisted respawn POI. Add the Dunlending race to `heroes.json` (data). | Hobbit/Dunlending start, per-origin first arc, allegiance seeded at creation. | `ConfigData`, new `OriginData`, `NewGameScene:390-418`, `WorldStage:402,461`, `GameStage:715`, `AdventurePlayer`, `SaveLoadScene:325` | 1 d |
| **4** | **Corruption: delta action + HUD + flag effects**: (a) `addQuestFlag/addCharacterFlag {key,val,min,max}` action (signed, clamped, fires quest events); (b) `GameHUD` label bound to a configurable flag (`config.json` `hudFlag`); (c) `config.json` `flagEffects[{when, effect}]` layer in `DuelScene` ("the Ring tempts you" by tier). Optional `ItemData.passive` for inventory-wide effects. | Meter with cleansing, visible readout, duel consequences at thresholds. | `DialogData.ActionData`, `MapDialog.setEffects:370`, `AdventurePlayer`, `stage/GameHUD.java:166+` and 3 HUD json files, `scene/DuelScene.java:505-534`, `ConfigData` | 1.5 d total (a 0.25, b 0.5–1, c 0.5) |
| **5** | **Non-fatal boss loss**: `EnemyData.noDefeatPenalty` skips `defeated()`, respawn, insult/defeat popups. | Sauron duel where losing branches to "Sauron's general" / "Cast down" instead of a normal defeat. | `stage/WorldStage.java:199-219`, `stage/MapStage.java:857-883`, `scene/DuelScene.java:213`, `data/EnemyData.java` | 0.5 d |
| 6 | **`ItemData.cursed`** (equip-locked, auto-equip on grant) or `passive`. | The One Ring cannot be taken off. | `AdventurePlayer.equip:1396`, `addItem:1457`, loadout code `:218-245` | 0.25 d |
| 7 | **`runCommand` dialog action** that calls `ConsoleCommandInterpreter.command(...)` (`teleport to poi`, `spawn enemy`, `give item`...). | Cameo teleports ("the Eagles carry you to Rivendell"), forced spawns, cheap glue without more actions. | `DialogData.ActionData`, `MapDialog.setEffects` (3 lines; `InventoryScene.triggerUse:300` already calls it for items) | 0.1 d |
| 8 | Side-quest pool `condition` (allegiance/act aware job board); optional ending screen (`showEnding` action + scene). | Job board per allegiance; a proper epilogue. | `AdventureQuestController:619-632`, new scene | 0.25 d / 1–2 d |

**Total for 0–7: about 5 days** of focused Java, in small commits, and almost all of STORY.md then becomes data work: acts, origins, hubs, cameos, corruption, the Ring branch and the endings.

What is data from day one (no engine work): quest chains and branching by quest, story/side split, all dialog gating by flags, one-time cameo quests and dialogs, boss duels with custom rules, the pursuit phase (extra Nazgûl spawns), granting the Ring, multiple endings by flags, a coarse flag-based corruption meter, and per-act POI appearance. A sensible order: start writing act I and the Hobbit/Dunlending arcs as data while items 0–3 are built, and add 4–6 before act III and the Ring branch.

## 4. Open points to verify when implementing

- Run the card-script lines (`RingTemptsYou`, per-turn `DB$ Effect` emblems) through the AI-vs-AI harness (`tools/middleearth/deckstrength.sh`) before designing the Sauron duel.
- `colorIdentity` dialog condition: confirm the inversion before using it for colour-based origin forks.
- `removeAllQuestItems` callers: make sure the Ring (`questItem`) is not wiped by a quest reset in normal play.
- Shop item selling: confirm there is no code path that sells `ItemData` (only cards were found).
- Flag range: keep the corruption meter within -128..127 and note `setQuestFlag(…, 0)` deletes the flag.
- `getExtraQuestSpawns` ignores enemy `difficulty` and biome: check that spawned Nazgûl do not appear in towns' immediate surroundings at low rank, or accept it as part of the pursuit.
