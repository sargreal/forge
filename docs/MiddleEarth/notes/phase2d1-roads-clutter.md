# Phase 2d-1: reference crops, Roads and Clutter layers

Goal (user): edit the whole world in Tiled, including clutter, with the reference map behind each chunk. This run adds the per-chunk `Reference`, `Roads` and `Clutter` layers. The `Terrain` layer (terrain variants and WFC structures) is Phase 2d-2, planned at the end.

## Reference image

- `map-of-middle-earth.jpg` moved from the game resources to `docs/MiddleEarth/reference/` (not shipped). `overview.tmx` → `Reference` now points at `../../../../../../docs/MiddleEarth/reference/map-of-middle-earth.jpg`; offset (−218.25, 38.73), opacity 0.6 and size are unchanged. Nothing in the game reads the overview or the image (the engine reads only the `.world` and its chunks; `grep` finds no other reference).
- **Per-chunk crops:** `tools/middleearth/worldmap/make_reference_crops.py` reads the overview's `Reference` placement (offset, image) and the chunk list of `middleearth.world` and writes `docs/MiddleEarth/reference/chunks/chunk_<c>_<r>.png`: the part of the image under the chunk, at **16 px per world tile**, so a crop is 2400×2400 px and lines up 1:1 with the chunk's tiles. Outside the image: transparent. Run it again after moving the overview's `Reference` layer or replacing the image (about 6 s; the chunk files don't change).
- **Size trade-off.** Tiled can't scale image layers, so a smaller crop (1 px per tile) would not line up. Measured for one chunk at 16 px/tile: bilinear PNG 3.2 MB, bilinear JPEG 390 KB, nearest-neighbour PNG 120 KB, **nearest-neighbour 256-colour PNG 57 KB** (used; 36 crops = 1.8 MB). The source image has about 1 px per world tile, so smoothing would add no detail, only blur; nearest gives 16-px blocks, the same as Tiled shows the overview when zoomed in. A tile object with a scaled 150-px image would also work, but it is an object, not an image layer, and easy to move by accident.
- Each chunk has a hidden image layer `Reference` (opacity 0.5, `visible="0"`) pointing at `../../../../../../docs/MiddleEarth/reference/chunks/chunk_<c>_<r>.png`. The game reader ignores image layers. `exportWorldTmx` keeps a chunk's existing `Reference` layer verbatim (visibility, opacity, offset, image), and writes the default for a chunk that has none.

## Layer formats

Chunk layers, in file (drawing) order, with their fixed ids: `Biomes` (1), `Roads` (3), `Clutter` (4), `Reference` (5), `POI` (2); `nextlayerid="6"`. 2d-2 adds `Terrain` as id 6 between `Biomes` and `Roads`. Tilesets per chunk, with the firstgids Tiled itself assigns on save (consecutive): `biomes.tsx` (1–44), `roads.tsx` (45), `clutter.tsx` (46–172).

| Layer | Format | Read | Written |
| --- | --- | --- | --- |
| `Roads` | Tile layer (base64 zlib, like `Biomes`). `roads.tsx`: one 16 px tile, the centre tile of `world.json` → `roadTileset` (`terrain.atlas` "Road", the 3×4 autotile's column 1, row 2, as `BiomeTexture` draws a road interior), tile property `road=true`. | Any non-empty cell is a road. | The road bit of every tile. |
| `Clutter` | Object layer of tile objects: `<object id gid x y width height/>` (width/height = the sprite's size, as Tiled writes tile objects), position = the sprite's bottom-left corner in chunk-local Tiled pixels (y-down), like POIs. `clutter.tsx`: image collection, one tile per sprite **variant** (127 tiles for the 33 sprites of `map_sprites.json`), each a sub-rectangle (`x/y/width/height`, Tiled ≥ 1.9) of `clutter.png`, a copy of the `map_sprites.atlas` page. Tile properties `sprite` (the `map_sprites.json` name) and `variant`. | Sprite from the object's tile (`sprite` property), or from a `sprite` property on the object (so a plain point object also works). | Every placed sprite, in generator order (tile column, then south to north). The tile is the variant the game draws at that position (`((int) x + (int) yUp * 11483) % variants`, as `MapSprite`). |

**Exact positions.** Generated clutter positions are random floats. The export writes each coordinate in its shortest float form when reading it back (parse as double, add the chunk origin, convert to the game's y-up float) gives the exact float, else as the full double. Only the bottom chunk row needs the long form for most objects (`y` far from the chunk's top in y-down = small y-up values), e.g. `2398.3726768493652`. After a save from Tiled, positions are rounded to Tiled's own precision (6 significant digits, i.e. 0.01 px): invisible, but the next export then shows these as changed.

**Chunk membership.** A clutter object belongs to the chunk that holds its position (like POIs). Sprites can stick out of their tile by up to 4 px, so objects at the world's south edge have `y` up to 2404; the reader accepts objects up to 8 px outside their chunk (`CLUTTER_TOLERANCE`) and reports anything further as a problem (`check.sh` error).

## Engine changes (for PLAN.md → "Engine changes")

| Change | Files |
| --- | --- |
| `WorldMapTmx` reads two optional layers per map: tile layer `Roads` and object layer `Clutter` (sprite from the tile property `sprite` or an object property). New public fields: `roadsDefined`/`roads` and `clutterDefined` (per world tile [x][row]: the tile lies in a map that has the layer; null if no map has it), `clutter` (`Clutter`: sprite, world Tiled x/y as double, id, file), `Chunk.hasRoads/hasClutter/clutter`. New problems: clutter object more than 8 px outside its chunk; layers ignored when the map size differs from the world. `tileOf(...)` helper. | `forge-gui-mobile/.../world/WorldMapTmx.java` |
| `WorldMapTmx.write`/`writeWorld` take a `Layers` (road tiles, list of `Sprite`s) and write the `Roads` and `Clutter` layers, the tilesets `roads.tsx/.png` (cut from the road atlas) and `clutter.tsx/.png` (copy of the sprite atlas page, one sub-rectangle tile per variant), and each chunk's `Reference` image layer (kept from the old chunk file, else the default). The tile layer writer is shared (`tileLayer`). | same |
| `World.generateNew`: after the town-road lines (which now skip tiles of maps with a `Roads` layer), the road tiles of the layers get the road bit and `terrainMap = 0`, exactly what a generated road does (so the minimap, rendering and collision follow). The road lines are skipped completely when every tile has a layer. The hidden POI-to-town paths are unchanged. | `.../world/World.java` |
| `World.generateNew` clutter: the random placement loop runs only if some map has no `Clutter` layer; it then draws the same random numbers as before and drops sprites whose position falls into a map with the layer. `placeWorldMapClutter` then adds the layer objects (unknown sprite names are logged and skipped), sorted in generator order, so each game chunk's sprite list has the same order as a generated one (the draw order of overlapping background sprites is unchanged). | same |
| `World.exportWorldTmx` passes the road bits and all sprites (`SpritesDataMap.allPositions()`, new) to the writer. | `World.java`, `.../world/SpritesDataMap.java` |

The save format is unchanged. One side effect: when every map has a `Clutter` layer, the clutter loop no longer consumes `World.random`, so its state after world generation differs from before. Only code that uses `World.getRandom()` without reseeding it first would notice (it is reseeded per POI, shop and reward scene).

## Migration

1. Game with the new build, world without the new layers → `exportWorldTmx <res>/world/middleearth`: writes all 36 chunks with `Roads` (2,360 road tiles), `Clutter` (**50,586 objects**) and `Reference`, plus `roads.tsx/.png` and `clutter.tsx/.png`. `.world`, `overview.tmx/.png`, `biomes.tsx/.png` and the `Biomes`/`POI` layers are byte-identical to before (only the map headers' `nextlayerid` and the new tileset lines changed).
2. **Same world:** `exportMap` of the game with the layers is **pixel-identical** to the baseline (the build *before* this change, on the old world files). The clutter: a smoke export from the generated world (new build, no layers) and from the layer world are **byte-identical**, so every sprite (name and exact float position) and every road tile is the same.
3. **Round trip:** game loads the layer world → `exportWorldTmx` into a scratch dir holding a copy of `overview.tmx` → `diff -r` against the committed folder: **byte-identical**.

File sizes: chunk files grew from about 14 KB (land) to 110–180 KB; the folder is 4.3 MB (was 0.5 MB), of which the clutter objects are about 4 MB (about 80 bytes per object; without `width`/`height` it would be 55 bytes and 3.2 MB in total, but Tiled adds them on the first save anyway). Each land chunk has up to 2,300 objects, which Tiled handles fine. A more compact encoding (e.g. one object per tile column, or properties packing many sprites) would no longer be editable as single sprites in Tiled, so it was not done; git compresses the text well.

## Tests

| Test | Result |
| --- | --- |
| Build (`game.sh build`) | OK |
| `check.sh` | 0 errors, 2 warnings (the old upstream map typos). New info line: `worldMap layers: Roads in 36 maps (2360 road tiles), Clutter in 36 maps (50586 objects), Reference in 36 maps` |
| `check.sh` negative test (broken chunk_1_1) | Roads layer 149×150 → error; Clutter object with sprite `Balrog` → error; object at x=3000 → "outside the chunk" error |
| WorldSim | `problems: 0`, 0 unreachable POIs (roads from the layers); parse time 239 ms (102 ms without clutter) |
| Same world / round trip | identical (see Migration) |
| Delete layers (`Roads` of chunk_3_4 and chunk_1_2, `Clutter` of chunk_1_2 and chunk_2_5) | Game regenerates them: export byte-identical to the committed world, `exportMap` pixel-identical |
| Edit layers (20 road cells added in chunk_1_2, 100 clutter objects deleted, one plain object with `sprite=Stone` added) | Export shows exactly these changes (the plain object comes back as a `Stone` tile object); minimap differs only in the 20 road tiles |
| Determinism | Two smoke runs: `smoke-world/` identical (`diff -r`), also between the generated and the layer world |
| `tests/run_all.sh` | ALL PASSED |

Generation time (`Generating world took`, smoke runs on the same machine): see the table below. Reading the clutter costs about 0.07 s (`loading data` 0.45 → 0.53 s) and the clutter loop drops from about 0.23 s to 0.03 s. The total is dominated by the WFC structures (about 2 s, parallel) and varies by ±0.2 s between runs; on average it got about 0.25 s faster.

| Run (alternating) | Before (baseline build, generated) | After (layers) |
| --- | --- | --- |
| 1 | 2.67 s (data 0.44, sprites 0.25) | 2.76 s (data 0.51, sprites 0.04) |
| 2 | 3.06 s (0.50, 0.19) | 2.65 s (0.57, 0.03) |
| 3 | 2.97 s (0.43, 0.26) | 2.52 s (0.51, 0.03) |
| mean | 2.90 s | 2.64 s |

All six runs gave a pixel-identical `exportMap`.

## Phase 2d-2 plan: Terrain layer (terrain variants and structures)

What it covers: `terrainMap` per tile = terrain variant (noise-picked `terrain[]` entry of the biome, e.g. `Green_1`), or a WFC structure tile (`mountain.png` in 17 biomes, `forest`, `swamp_forest`, `white`, `black`, `volcano`), plus the collision bit. All 44 biomes use `terrain.atlas` (88 terrain entries in total).

Design (as in the phase2c notes, refined):

- **Tilesets.** One generated tileset per atlas page: `terrain.tsx` from `terrain.atlas`/`autotiles.png` with one tile per terrain region, showing the region's centre tile (like the `Roads` tile), tile property `terrain=<region name>`; one per structure atlas (`world/structures/*.atlas`), one tile per `mappingInfo` entry, properties `structure=<sourcePath>#<name>` and `collision=true` where the structure has collision. Same pattern as `clutter.tsx` (sub-rectangle tiles, page image copied), so the code from this phase is reused.
- **Layer.** Tile layer `Terrain` between `Biomes` and `Roads` (id 6). An empty cell = the biome's base tile (index 0). Export: `terrainMap` index → biome-relative name (terrain entry or structure + mapping) → gid. Import: name → index in the tile's own biome (`terrain.length + 1 + structure offset + mapping index`), `isStructureBit`, `collisionBit` from the tile property; a name that the tile's biome doesn't have → `check.sh` error, the game uses the base tile.
- **Generation.** If a map has the layer: skip the noise and WFC for its tiles (the structure WFC can be skipped entirely when every map has the layer, which saves about 2 s of the 2.7 s generation). Biome masks, borders, `clearTerrain` around POIs, roads and the hidden paths still run after it as today, so the result stays identical on export → import.
- **Biome edits.** If a biome is repainted, its old terrain names may not exist in the new biome. Option: on import, a terrain name that the tile's biome lacks falls back to the base tile, and `check.sh` lists the count per chunk (warning), with "delete the Terrain layer of this chunk to regenerate" as the fix.
- **Risks.** The autotile borders between terrain variants are computed at render time and can't be shown in Tiled (Tiled shows each tile's centre only). The structure index mapping is per biome and fiddly; the minimap's structure lookup must keep working. File size: one more tile layer, about +10–20 KB per chunk.

Estimate: tilesets and export about 1 day, import and the generation skip about 1 day, checks, docs and the identity tests about 0.5–1 day: **about 2.5–3 days** (one Opus run if well scoped: the identity test harness from this phase is reusable).

## Open questions

- **Tiled check on a real machine** (still open from 2c): sub-rectangle image-collection tiles need Tiled ≥ 1.9. Worth one round: open a chunk, toggle `Reference`, insert and move a clutter tile, paint a road, save; then `check.sh` and a re-export (expect only rounded clutter coordinates and possibly attribute order changes in the diff).
- **Clutter variants:** the game picks a sprite's variant from its position, so a moved object can change variant in game while Tiled still shows the old tile. Making the game honour the chosen variant would need a small change in `MapSprite`/`SpritesDataMap` (store the variant) and the save format; not done.
- **Roads autotiling in Tiled:** the `Roads` layer shows the plain centre tile, not the game's road edges. A Tiled Wang set (terrain brush) for the road autotile would make it look like the game; possible later, it doesn't change the data.
- Should the hidden POI-to-town paths also become a layer (`Paths`)? They only clear collision through mountains/forests and are invisible; with a Terrain layer (2d-2) they could simply be part of the terrain (no collision where a path runs).
