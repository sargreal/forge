# Phase 2b: a fixed, Tiled-editable world

The Middle-earth world is now one Tiled file, `MiddleEarth/world/middleearth.tmx`. It holds every tile's biome, every POI with its exact position, town name and sprite variant, the player start, and the generation seed. Every new game builds the same world from it, and the file can be edited by hand in Tiled. The Phase 2a biome-map script is retired: it is kept only to regenerate the world from scratch. Random generation and the `biomeMap` PNG path still work for other planes.

User request: *"I'd like to later make changes manually in Tiled, so saving the generated map and reusing it would be nice. Also with all of the town placements and everything, so that this isn't regenerated afterwards."*

## Format

`world.json` → `"worldMap": "world/middleearth.tmx"`. It replaces `biomeMap`; if both are set, `worldMap` wins.

| Part | Content |
| --- | --- |
| Map | Tiled 1.10, orthogonal, 900×900 tiles, 16 px (= `world.json` width/height/tileSize; another size is sampled nearest-neighbour for biomes). Property `seed` (string, a Java `long`: Tiled's `int` type is only 32 bit). |
| Tileset `biomes.tsx` + `biomes.png` | 44 tiles, one per biome in `biomesNames` order: the biome `color` with a 1-px darker border, tile property `biome=<biome name>`. Tile 0 is `ocean` (`base.json`), used for sea, lakes and rivers. The engine maps gid → tile → `biome` → biome **by name**, so reordering `biomesNames` does not break the map. |
| Tile layer `Biomes` | Each cell = the tile's own biome. Encoding base64 + zlib (the reader also takes gzip, uncompressed base64 and CSV; not zstd). Empty cells and unknown gids → biome 0, with a log line. |
| Object layer `POI` | One point object per POI. `name` = `points_of_interest.json` entry name; `type="poi"`; properties `displayName` (the name shown, e.g. the generated town name) and `spriteIndex` (int, the sprite variant). x/y = the POI position (sprite bottom-left), Tiled pixels with y down (world y = 14400 − tmx y). The object with `type` (or `class`) `start` is the player start. |

`PointOfInterest` stores name, position, rectangle (from the sprite), spriteIndex, active (from the entry), displayName and quest flags (from the entry), so name + position + displayName + spriteIndex reproduce it exactly. The file is **48,986 bytes** (the zlib biome layer is about 45 KB).

Import order doesn't matter: the reader sorts POI objects by name, then x, then y (the town list order drives road building), and the export writes them in the same order, so a round trip gives a byte-identical file.

## Engine changes (for PLAN.md → "Engine changes")

| Change | Files | Behaviour |
| --- | --- | --- |
| New class `WorldMapTmx`: `read(FileHandle, biomes, w, h)` parses the tmx and its external tsx with libGDX `XmlReader` + `java.util.zip` (no `TmxMapLoader`, so no textures or GL context); `write(...)` writes the tmx plus `biomes.tsx`/`biomes.png`. | `forge-gui-mobile/.../world/WorldMapTmx.java` (new) | Format above. |
| `WorldData.worldMap` field. | `.../data/WorldData.java` | Unset = old behaviour. |
| `World.generateNew`: `loadWorldMap()` reads the tmx and applies the `start` object to `playerStartPosX/Y` (+0.5 px so the int cast in `WorldSave` gives the exact pixel). If the map has `seed`, generation (random, noise, WFC structures) uses it; `World.seed` stays the save's own seed, so the per-save seeding of dungeon/town interiors (`setSeed(offset + seed)`) is unchanged. `loadFixedBiomeMap(worldMap)` takes the own-biome array from the tmx or the PNG, and the new `buildBiomeMasks(own)` builds the masks (the Phase 2a code, moved). With a world map the random POI loop is skipped (`running = worldMap == null`) and `placeWorldMapPois()` places the objects: unknown names are logged and skipped, sprite index and display name are set, terrain is cleared (3 tiles) and minimap markers are drawn as before. Roads and POI paths are computed by the existing code. The marker drawing moved to `drawPoiMarker()`, shared by both paths. | `.../world/World.java` | Same world in every new game. |
| `World.exportWorldTmx(FileHandle)`: own biome per tile (highest mask bit without the road bit), all placed POIs (rounded pixel positions, display name, sprite index), the start position, and the generation seed (for a loaded save it falls back to the tmx's seed, else the save's seed). Console command `exportWorldTmx [file]` next to `exportMap` (default `~/.forge/adventure/world.tmx`). | `.../world/World.java`, `.../stage/ConsoleCommandInterpreter.java` | Works for every plane, including random worlds. |
| `PointOfInterest.getSpriteIndex()` / `setSpriteIndex(int)`. | `.../pointofintrest/PointOfInterest.java` | Needed to store and restore the sprite variant. |

The save format is unchanged; saves don't depend on the tmx after creation.

## Workflow

See DEVELOPMENT.md §4.1 "Editing the world in Tiled": paint biomes, move/add/remove POI objects (name = entry, `displayName`), save, then `check.sh`, WorldSim and smoke. Decorations, WFC structures, roads, POI paths and biome borders are still generated, but from the fixed `seed`, so they are the same every time.

How the committed tmx was made: the Phase 2a `biome_map.png` world generated in the real game (smoke run, seed `-8110469294258918603`), then `exportWorldTmx` into `world/`. `make_biome_map.py` has a header marking it as retired, with the steps to regenerate from scratch, and it no longer adds `biomeMap` to `world.json` when `worldMap` is set.

## Tools

- **PlaneCheck** (`check.sh`) worldMap section: the file, the tsx and the tileset image exist; every tileset tile names an existing biome; every gid in `Biomes` maps to a biome; every biome is painted (warning); every POI object names an existing POI entry; exactly one start; every POI that a biome lists has at least one object (error if none, warning if fewer than `count`); `seed` is present and a long.
- **WorldSim**: with `worldMap` it reads the tmx through `WorldMapTmx.read` (engine code, no replica) and reports POIs outside a biome that lists them, overlaps (< 2 tiles), missing POI entries, a missing start, and unreachable POIs (both reachability modes). `problems: N`, exit 1 on problems.
- **game.sh smoke** now also writes `smoke-world.tmx` (exportWorldTmx), for determinism and round-trip checks.

## Test results

| Test | Result |
| --- | --- |
| Build (`game.sh build`) | OK |
| `check.sh` | 0 errors, 2 warnings (unchanged upstream map typos). `worldMap world/middleearth.tmx: 900x900, seed -8110469294258918603, 44/44 biomes painted, 97 POI objects (89 distinct), 1 start` |
| WorldSim | 97 POIs, 0 outside biome, 0 overlaps, 0 missing entries, 0 unreachable (0 over land and roads only): `problems: 0`. Parse time 69 ms. |
| Smoke × 2 (MiddleEarth, DISPLAY 112) | No exceptions, 0 POI retries. `exportMap` PNGs (3600×3600) are **pixel-identical**. Exported tmx files (all POIs, names, sprite indices, biomes) are **byte-identical**. Overworld screenshots at the start are identical except the randomly chosen avatar (same decorations and roads). |
| Round trip | The tmx exported from the loaded tmx world is **byte-identical** to the committed source; the regenerated `biomes.tsx`/`biomes.png` are identical too. |
| Smoke (Shandalar, random generation) | Works, no exceptions, 0 POI retries (export of a random world works too: 196 KB). |
| `biomeMap` PNG path | The first smoke run (before switching `world.json`) generated the Phase 2a world from the PNG without errors; that is the world that was exported. |

Generation time (`Generating world took`): 2.89 s and 2.81 s with the tmx (loading data incl. tmx parse 0.32–0.35 s, biomes 1.83–1.91 s, POI placement 0.03–0.04 s, minimap 0.29–0.32 s, sprites 0.27–0.32 s). Phase 2a with the PNG: 3.37 s in this session, 2.83–3.62 s before. So no measurable change.

## Open issues

- **Roads layer:** roads are still computed from the town positions (nearest town within `maxRoadDistance`). An optional `Roads` tile or object layer could fix them too.
- **Bridges/fords:** rivers are ocean tiles with collision; crossings are land gaps (Phase 2a). Bridge tiles would need an engine change.
- **Structures** (mountain clusters, the Mordor ring) are WFC output anchored on the biome bounding box from the biome JSON. Painting a biome far outside its box won't extend its structures; then update the biome's `startPointX/Y`/`width`/`height`.
- **Tiled editing checked by format only:** Tiled isn't installed here, so the file was not opened in Tiled itself. The tmx/tsx follow the Tiled 1.10 format (relative tileset path, base64+zlib layer, point objects, typed properties), and the reader accepts what Tiled writes back (`type` or `class`, CSV/zlib/gzip, multiline property values). Worth a quick open/save test on the user's machine.
- POI positions are rounded to whole pixels on export (the first export moved each POI by < 1 px from the random placement).
- New town names are not removed from the biome's town-name list (not needed: names come from the tmx).
- `seed` is a string property because Tiled's int is 32 bit.
