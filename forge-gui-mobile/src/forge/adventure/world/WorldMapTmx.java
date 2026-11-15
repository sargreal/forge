package forge.adventure.world;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.graphics.g2d.TextureAtlas;
import com.badlogic.gdx.utils.Base64Coder;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import com.badlogic.gdx.utils.XmlReader;
import forge.adventure.data.BiomeData;
import forge.adventure.data.BiomeSpriteData;
import forge.adventure.data.WorldData;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.*;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Fork (Middle-earth): a fixed, Tiled-editable world ({@code world.json -> "worldMap"}).
 * <p>
 * The .tmx file holds a tile layer "Biomes" (one tile per world tile; each tileset tile carries a property
 * {@code biome=<biome name>}), an object layer "POI" (one object per placed point of interest, object name = the
 * points_of_interest.json entry, properties {@code displayName} and {@code spriteIndex}; one object of type
 * {@code start} for the player start) and a map property {@code seed} that fixes the world generation seed.
 * <p>
 * Phase 2c: {@code worldMap} may also name a Tiled world ({@code .world}, JSON) whose maps are chunks of the world, each
 * with its own "Biomes" and "POI" layers (object coordinates local to the chunk). The chunks are assembled into the same
 * data as a single map. Map properties such as {@code seed} may be on any one chunk (by convention chunk_0_0).
 * {@link #writeWorld} writes the chunks, the .world file, the tileset and a generated low-res {@code overview.tmx}.
 * <p>
 * Phase 2d-1: optional per-map layers that replace a generation step for the tiles of that map. Tile layer "Roads"
 * (any non-empty cell is a road tile; tileset {@code roads.tsx}), object layer "Clutter" (tile objects of the image
 * collection {@code clutter.tsx}, tile property {@code sprite=<map_sprites name>}, positioned like the game sprite:
 * bottom-left corner). A map without the layer keeps the generated roads/clutter. Image layer "Reference" (a crop of the
 * reference map, hidden) is for editing only and ignored here.
 * <p>
 * Parsing uses libGDX's XmlReader/JsonReader plus java.util.zip only (no TmxMapLoader, which loads textures and needs a
 * GL context). Tiled coordinates are y-down (row 0 = north); the world's pixel coordinates are y-up.
 */
public final class WorldMapTmx {
    public static final String BIOME_LAYER = "Biomes";
    public static final String POI_LAYER = "POI";
    public static final String START_TYPE = "start";
    public static final String TILESET_NAME = "biomes";
    public static final String WORLD_EXTENSION = "world";
    public static final String OVERVIEW_NAME = "overview";
    public static final String REFERENCE_LAYER = "Reference";
    public static final String ROADS_LAYER = "Roads";
    public static final String CLUTTER_LAYER = "Clutter";
    public static final String ROADS_TILESET = "roads";
    public static final String CLUTTER_TILESET = "clutter";
    /** Where the per-chunk reference crops are, relative to the canonical world folder (forge-gui/res/adventure/MiddleEarth/world/middleearth). */
    public static final String REFERENCE_CHUNKS = "../../../../../../docs/MiddleEarth/reference/chunks/";
    /** Clutter objects may stick out of their map by this much (the generator puts sprites up to 4 px outside their tile). */
    public static final float CLUTTER_TOLERANCE = 8;
    /** Default chunk size in tiles: 150x150 = 22,500 tiles per chunk, 6x6 chunks for a 900x900 world. */
    public static final int DEFAULT_CHUNK_SIZE = 150;

    /** A POI object; x/y are the Tiled (y-down) pixel coordinates of the POI position. */
    public static final class Poi {
        public String name;
        public String displayName;
        public float x, y;
        public int spriteIndex = -1;
    }

    /** A chunk map of a .world; x/y/width/height in tiles. */
    public static final class Chunk {
        public String fileName;
        public int x, y, width, height, pois;
        /** Phase 2d-1: the map has a Roads layer / a Clutter layer (with this many objects). */
        public boolean hasRoads, hasClutter;
        public int clutter;
    }

    /**
     * A clutter sprite (Phase 2d-1). x/y: Tiled pixel coordinates of the whole world (y-down) of the sprite's bottom-left
     * corner, as doubles so that the game's float positions survive the round trip exactly. sprite: map_sprites name, or
     * null if the object's tile has no "sprite" property. id/file: object id and map file, for messages.
     */
    public static final class Clutter {
        public String sprite;
        public double x, y;
        public int id;
        public String file;
    }

    /** Explicit seed from the map property "seed", or null. */
    public Long seed;
    /** Own biome index per tile, [x][row], row 0 = north (same layout as World.biomeMap). */
    public int[][] biomes;
    /** POI objects (world coordinates), sorted by name, then x, then y (the order does not depend on the file). */
    public final List<Poi> pois = new ArrayList<>();
    /** Player start in Tiled pixel coordinates (y-down), or null. */
    public float[] start;
    public int mapWidth, mapHeight, tileWidth, tileHeight;
    /** The chunks of a .world in file order (empty for a single tmx). */
    public final List<Chunk> chunks = new ArrayList<>();
    /** Structural problems found while reading (gaps, overlaps, objects outside their chunk, several starts or seeds). Also logged. */
    public final List<String> problems = new ArrayList<>();
    /** Phase 2d-1, per world tile [x][row] (row 0 = north): the tile lies in a map with a Roads layer; null if no map has one. */
    public boolean[][] roadsDefined;
    /** Road tiles from the Roads layers ([x][row]); only meaningful where {@link #roadsDefined}. Null if no map has the layer. */
    public boolean[][] roads;
    /** Per world tile [x][row]: the tile lies in a map with a Clutter layer; null if no map has one. */
    public boolean[][] clutterDefined;
    /** Clutter objects of all Clutter layers, world coordinates, in file order. */
    public final List<Clutter> clutter = new ArrayList<>();

    private static final Comparator<Poi> ORDER = Comparator.<Poi, String>comparing(p -> p.name)
            .thenComparingDouble(p -> p.x).thenComparingDouble(p -> p.y);

    /** One parsed tmx: own biome per cell (row-major, row 0 = north), objects in the map's own pixel coordinates. */
    private static final class Parsed {
        int width, height, tileWidth, tileHeight;
        int[] biomes;
        String seed;
        final List<Poi> pois = new ArrayList<>();
        final List<float[]> starts = new ArrayList<>();
        /** Roads layer: 1 = road, or null without the layer. */
        byte[] roads;
        /** Clutter layer objects (map coordinates), or null without the layer. */
        List<Clutter> clutter;
    }

    // ------------------------------------------------------------------ reading

    /**
     * Parses a tmx, or a Tiled .world of chunk maps. Biome names are resolved against {@code biomes} (unknown names,
     * empty cells and tiles that no chunk covers: biome 0). Another map size is sampled nearest-neighbour.
     */
    public static WorldMapTmx read(FileHandle file, List<BiomeData> biomes, int worldWidth, int worldHeight) {
        Map<String, Integer> biomeByName = new HashMap<>();
        for (int i = 0; i < biomes.size(); i++)
            biomeByName.putIfAbsent(biomes.get(i).name, i);
        WorldMapTmx result = new WorldMapTmx();
        List<String> seeds = new ArrayList<>();
        List<float[]> starts = new ArrayList<>();
        int[] own;
        if (WORLD_EXTENSION.equalsIgnoreCase(file.extension())) {
            JsonValue maps = new JsonReader().parse(file).get("maps");
            if (maps == null || maps.child == null)
                throw new IllegalArgumentException("worldMap " + file.path() + ": no \"maps\" (world \"patterns\" are not supported)");
            List<Parsed> parsed = new ArrayList<>();
            for (JsonValue m = maps.child; m != null; m = m.next) {
                Chunk chunk = new Chunk();
                chunk.fileName = m.getString("fileName");
                Parsed p = parseMap(file.parent().child(chunk.fileName), biomeByName);
                int px = m.getInt("x", 0), py = m.getInt("y", 0);
                if (parsed.isEmpty()) {
                    result.tileWidth = p.tileWidth;
                    result.tileHeight = p.tileHeight;
                } else if (p.tileWidth != result.tileWidth || p.tileHeight != result.tileHeight)
                    result.problem(chunk.fileName + ": tile size " + p.tileWidth + "x" + p.tileHeight + " differs from " + result.tileWidth + "x" + result.tileHeight);
                if (px < 0 || py < 0 || px % p.tileWidth != 0 || py % p.tileHeight != 0)
                    result.problem(chunk.fileName + ": position " + px + "," + py + " is negative or not on the tile grid");
                chunk.x = Math.max(0, px / p.tileWidth);
                chunk.y = Math.max(0, py / p.tileHeight);
                chunk.width = p.width;
                chunk.height = p.height;
                chunk.pois = p.pois.size();
                result.chunks.add(chunk);
                parsed.add(p);
                result.mapWidth = Math.max(result.mapWidth, chunk.x + chunk.width);
                result.mapHeight = Math.max(result.mapHeight, chunk.y + chunk.height);
                // objects: chunk-local -> world
                int w = p.width * p.tileWidth, h = p.height * p.tileHeight, ox = chunk.x * p.tileWidth, oy = chunk.y * p.tileHeight;
                for (Poi poi : p.pois) {
                    if (poi.x < 0 || poi.y < 0 || poi.x > w || poi.y > h)
                        result.problem(chunk.fileName + ": POI '" + poi.name + "' at " + num(poi.x) + "," + num(poi.y) + " is outside the chunk (0.." + w + ", 0.." + h + ")");
                    poi.x += ox;
                    poi.y += oy;
                    result.pois.add(poi);
                }
                for (float[] st : p.starts) {
                    if (st[0] < 0 || st[1] < 0 || st[0] > w || st[1] > h)
                        result.problem(chunk.fileName + ": start at " + num(st[0]) + "," + num(st[1]) + " is outside the chunk");
                    starts.add(new float[]{st[0] + ox, st[1] + oy});
                }
                chunk.hasRoads = p.roads != null;
                chunk.hasClutter = p.clutter != null;
                if (p.clutter != null) {
                    chunk.clutter = p.clutter.size();
                    float t = CLUTTER_TOLERANCE;
                    for (Clutter c : p.clutter) {
                        if (c.x < -t || c.y < -t || c.x > w + t || c.y > h + t)
                            result.problem(chunk.fileName + ": Clutter object " + c.id + " at " + c.x + "," + c.y + " is outside the chunk (0.." + w + ", 0.." + h + ")");
                        c.x += ox;
                        c.y += oy;
                        c.file = chunk.fileName;
                        result.clutter.add(c);
                    }
                }
                if (p.seed != null)
                    seeds.add(p.seed);
            }
            // assemble, checking that the chunks tile the world without gaps or overlaps
            own = new int[result.mapWidth * result.mapHeight];
            boolean[] covered = new boolean[own.length];
            byte[] roadOwn = new byte[own.length]; // 0 = no Roads layer, 1 = layer without road, 2 = road
            boolean[] clutterOwn = new boolean[own.length];
            for (int i = 0; i < parsed.size(); i++) {
                Chunk c = result.chunks.get(i);
                Parsed p = parsed.get(i);
                for (int j = 0; j < i; j++) {
                    Chunk o = result.chunks.get(j);
                    if (c.x < o.x + o.width && o.x < c.x + c.width && c.y < o.y + o.height && o.y < c.y + c.height)
                        result.problem("chunks " + o.fileName + " and " + c.fileName + " overlap");
                }
                for (int y = 0; y < c.height; y++)
                    for (int x = 0; x < c.width; x++) {
                        int g = (c.y + y) * result.mapWidth + c.x + x;
                        if (!covered[g]) { // first chunk wins on overlaps
                            own[g] = p.biomes[y * p.width + x];
                            if (p.roads != null)
                                roadOwn[g] = (byte) (1 + p.roads[y * p.width + x]);
                            clutterOwn[g] = p.clutter != null;
                        }
                        covered[g] = true;
                    }
            }
            int gaps = 0;
            for (boolean b : covered)
                if (!b)
                    gaps++;
            if (gaps > 0)
                result.problem(gaps + " tiles of the " + result.mapWidth + "x" + result.mapHeight + " world are in no chunk (biome 0)");
            result.setLayers(roadOwn, clutterOwn, worldWidth, worldHeight);
        } else {
            Parsed p = parseMap(file, biomeByName);
            result.mapWidth = p.width;
            result.mapHeight = p.height;
            result.tileWidth = p.tileWidth;
            result.tileHeight = p.tileHeight;
            own = p.biomes;
            result.pois.addAll(p.pois);
            starts.addAll(p.starts);
            if (p.seed != null)
                seeds.add(p.seed);
            byte[] roadOwn = new byte[own.length];
            boolean[] clutterOwn = new boolean[own.length];
            for (int i = 0; i < own.length; i++) {
                if (p.roads != null)
                    roadOwn[i] = (byte) (1 + p.roads[i]);
                clutterOwn[i] = p.clutter != null;
            }
            if (p.clutter != null)
                for (Clutter c : p.clutter) {
                    c.file = file.name();
                    result.clutter.add(c);
                }
            result.setLayers(roadOwn, clutterOwn, worldWidth, worldHeight);
        }
        if (new HashSet<>(seeds).size() > 1)
            result.problem("different 'seed' map properties " + seeds + ", using the first");
        if (!seeds.isEmpty())
            result.seed = Long.parseLong(seeds.get(0));
        if (starts.size() > 1)
            result.problem(starts.size() + " start objects, using the first");
        if (!starts.isEmpty())
            result.start = starts.get(0);

        result.biomes = new int[worldWidth][worldHeight];
        for (int x = 0; x < worldWidth; x++)
            for (int y = 0; y < worldHeight; y++)
                result.biomes[x][y] = own[(y * result.mapHeight / worldHeight) * result.mapWidth + x * result.mapWidth / worldWidth];
        result.pois.sort(ORDER);
        return result;
    }

    /** Sets roadsDefined/roads/clutterDefined from the assembled per-map-tile flags (only if a map has the layer). */
    private void setLayers(byte[] roadOwn, boolean[] clutterOwn, int worldWidth, int worldHeight) {
        boolean anyRoads = false, anyClutter = false;
        for (int i = 0; i < roadOwn.length; i++) {
            anyRoads |= roadOwn[i] != 0;
            anyClutter |= clutterOwn[i];
        }
        if ((anyRoads || anyClutter) && (mapWidth != worldWidth || mapHeight != worldHeight)) {
            problem("the map is " + mapWidth + "x" + mapHeight + " tiles, the world " + worldWidth + "x" + worldHeight + ": Roads and Clutter layers are ignored");
            clutter.clear();
            return;
        }
        if (anyRoads) {
            roadsDefined = new boolean[worldWidth][worldHeight];
            roads = new boolean[worldWidth][worldHeight];
        }
        if (anyClutter)
            clutterDefined = new boolean[worldWidth][worldHeight];
        for (int x = 0; x < worldWidth; x++)
            for (int y = 0; y < worldHeight; y++) {
                int g = y * mapWidth + x;
                if (anyRoads && roadOwn[g] != 0) {
                    roadsDefined[x][y] = true;
                    roads[x][y] = roadOwn[g] == 2;
                }
                if (anyClutter)
                    clutterDefined[x][y] = clutterOwn[g];
            }
    }

    /** Phase 2d-1: the tile [x][row] that holds a point in world Tiled coordinates (y-down), clamped to the world. */
    public static int[] tileOf(double x, double y, int tileSize, int width, int height) {
        return new int[]{Math.max(0, Math.min(width - 1, (int) Math.floor(x / tileSize))), Math.max(0, Math.min(height - 1, (int) Math.floor(y / tileSize)))};
    }

    private void problem(String message) {
        problems.add(message);
        System.err.println("worldMap: " + message);
    }

    /** Parses one tmx: tilesets (gid -> biome), the Biomes layer and the POI layer (objects in this map's coordinates). */
    private static Parsed parseMap(FileHandle tmx, Map<String, Integer> biomeByName) {
        XmlReader.Element map = new XmlReader().parse(tmx);
        Parsed result = new Parsed();
        result.width = map.getIntAttribute("width");
        result.height = map.getIntAttribute("height");
        result.tileWidth = map.getIntAttribute("tilewidth");
        result.tileHeight = map.getIntAttribute("tileheight");

        String seed = properties(map).get("seed");
        if (seed != null && !seed.trim().isEmpty())
            result.seed = seed.trim();

        // gid -> biome index, gid -> clutter sprite
        Map<Integer, Integer> gidToBiome = new HashMap<>();
        Map<Integer, String> gidToSprite = new HashMap<>();
        for (XmlReader.Element ts : map.getChildrenByName("tileset")) {
            int firstGid = ts.getIntAttribute("firstgid", 1);
            XmlReader.Element tileset = ts;
            String source = ts.getAttribute("source", null);
            if (source != null)
                tileset = new XmlReader().parse(tmx.parent().child(source));
            for (XmlReader.Element tile : tileset.getChildrenByName("tile")) {
                Map<String, String> tileProps = properties(tile);
                if (tileProps.containsKey("sprite"))
                    gidToSprite.put(firstGid + tile.getIntAttribute("id"), tileProps.get("sprite"));
                String biome = tileProps.get("biome");
                if (biome == null)
                    continue;
                Integer index = biomeByName.get(biome);
                if (index == null) {
                    System.err.println("worldMap: tileset tile " + tile.getAttribute("id") + " names unknown biome '" + biome + "', using biome 0");
                    index = 0;
                }
                gidToBiome.put(firstGid + tile.getIntAttribute("id"), index);
            }
        }

        // biome layer (and Phase 2d-1: Roads, any non-empty cell is a road)
        int[] gids = null;
        for (XmlReader.Element layer : map.getChildrenByName("layer")) {
            if (BIOME_LAYER.equals(layer.getAttribute("name", "")))
                gids = decodeLayer(layer, result.width * result.height);
            else if (ROADS_LAYER.equals(layer.getAttribute("name", ""))) {
                int[] road = decodeLayer(layer, result.width * result.height);
                result.roads = new byte[road.length];
                for (int i = 0; i < road.length; i++)
                    result.roads[i] = (byte) ((road[i] & 0x0FFFFFFF) != 0 ? 1 : 0);
            }
        }
        if (gids == null)
            throw new IllegalArgumentException("worldMap " + tmx.path() + ": no tile layer '" + BIOME_LAYER + "'");
        result.biomes = new int[gids.length];
        Set<Integer> unknown = new TreeSet<>();
        for (int i = 0; i < gids.length; i++) {
            int gid = gids[i] & 0x0FFFFFFF;
            Integer biome = gidToBiome.get(gid);
            if (biome == null && gid != 0)
                unknown.add(gid);
            result.biomes[i] = biome == null ? 0 : biome;
        }
        if (!unknown.isEmpty())
            System.err.println("worldMap " + tmx.name() + ": gids without a biome property (using biome 0): " + unknown);

        // POIs and start (and Phase 2d-1: Clutter; the sprite comes from the object's tile, or a "sprite" property on the object)
        for (XmlReader.Element group : map.getChildrenByName("objectgroup")) {
            if (CLUTTER_LAYER.equals(group.getAttribute("name", ""))) {
                result.clutter = new ArrayList<>();
                for (XmlReader.Element object : group.getChildrenByName("object")) {
                    Clutter c = new Clutter();
                    c.id = object.getIntAttribute("id", 0);
                    c.x = Double.parseDouble(object.getAttribute("x", "0"));
                    c.y = Double.parseDouble(object.getAttribute("y", "0"));
                    String sprite = properties(object).get("sprite");
                    if (sprite == null)
                        sprite = gidToSprite.get((int) (Long.parseLong(object.getAttribute("gid", "0")) & 0x0FFFFFFFL));
                    c.sprite = sprite;
                    result.clutter.add(c);
                }
                continue;
            }
            if (!POI_LAYER.equals(group.getAttribute("name", "")))
                continue;
            for (XmlReader.Element object : group.getChildrenByName("object")) {
                float x = object.getFloatAttribute("x", 0), y = object.getFloatAttribute("y", 0);
                String type = object.getAttribute("type", object.getAttribute("class", ""));
                if (START_TYPE.equals(type)) {
                    result.starts.add(new float[]{x, y});
                    continue;
                }
                Poi poi = new Poi();
                poi.name = object.getAttribute("name", "");
                poi.x = x;
                poi.y = y;
                Map<String, String> props = properties(object);
                poi.displayName = props.get("displayName");
                if (props.containsKey("spriteIndex"))
                    poi.spriteIndex = Integer.parseInt(props.get("spriteIndex").trim());
                result.pois.add(poi);
            }
        }
        return result;
    }

    public static Map<String, String> properties(XmlReader.Element element) {
        Map<String, String> result = new HashMap<>();
        XmlReader.Element props = element.getChildByName("properties");
        if (props != null)
            for (XmlReader.Element p : props.getChildrenByName("property"))
                result.put(p.getAttribute("name"), p.getAttribute("value", p.getText()));
        return result;
    }

    public static int[] decodeLayer(XmlReader.Element layer, int count) {
        XmlReader.Element data = layer.getChildByName("data");
        String encoding = data.getAttribute("encoding", "");
        String compression = data.getAttribute("compression", "");
        int[] gids = new int[count];
        String text = data.getText() == null ? "" : data.getText().trim();
        if ("csv".equals(encoding)) {
            String[] parts = text.split("[,\\s]+");
            for (int i = 0; i < count && i < parts.length; i++)
                gids[i] = (int) Long.parseLong(parts[i]);
            return gids;
        }
        if (!"base64".equals(encoding))
            throw new IllegalArgumentException("worldMap: unsupported layer encoding '" + encoding + "' (use base64 or csv)");
        byte[] bytes = Base64Coder.decode(text.replaceAll("\\s", ""));
        try {
            if (!compression.isEmpty()) {
                InputStream in;
                if ("zlib".equals(compression))
                    in = new InflaterInputStream(new ByteArrayInputStream(bytes));
                else if ("gzip".equals(compression))
                    in = new GZIPInputStream(new ByteArrayInputStream(bytes));
                else
                    throw new IllegalArgumentException("worldMap: unsupported layer compression '" + compression + "' (use zlib, gzip or none)");
                ByteArrayOutputStream out = new ByteArrayOutputStream(count * 4);
                byte[] buffer = new byte[65536];
                for (int n; (n = in.read(buffer)) > 0; )
                    out.write(buffer, 0, n);
                in.close();
                bytes = out.toByteArray();
            }
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("worldMap: cannot decompress layer", e);
        }
        for (int i = 0; i < count && i * 4 + 3 < bytes.length; i++)
            gids[i] = (bytes[i * 4] & 0xFF) | (bytes[i * 4 + 1] & 0xFF) << 8 | (bytes[i * 4 + 2] & 0xFF) << 16 | (bytes[i * 4 + 3] & 0xFF) << 24;
        return gids;
    }

    // ------------------------------------------------------------------ writing

    /** Phase 2d-1: a placed clutter sprite as the game holds it (world pixels, y-up, bottom-left corner). */
    public static final class Sprite {
        public final String name;
        public final float x, yUp;

        public Sprite(String name, float x, float yUp) {
            this.name = name;
            this.x = x;
            this.yUp = yUp;
        }
    }

    /**
     * Phase 2d-1: what goes into the Roads and Clutter layers on export. {@code roads}: road tiles [x][row] (row 0 = north),
     * {@code clutter}: every placed sprite. Either may be null to leave the layer out.
     */
    public static final class Layers {
        public boolean[][] roads;
        public List<Sprite> clutter;
    }

    /** The image tiles of the clutter tileset: per sprite name its first tile id and number of variants. */
    static final class SpriteTiles {
        final Map<String, int[]> firstAndCount = new HashMap<>();

        /** Tile id of the variant the game draws at this position (MapSprite/BiomeSprites: ((int) x + (int) y * 11483) % count). */
        int tileId(Sprite s) {
            int[] fc = firstAndCount.get(s.name);
            if (fc == null || fc[1] == 0)
                return -1;
            int index = ((int) s.x + (int) s.yUp * 11483) % fc[1];
            return fc[0] + Math.max(0, index);
        }
    }

    /** Order of clutter objects in a layer: the generator's order (tile column, then south to north), then name. */
    public static final Comparator<Sprite> SPRITE_ORDER = Comparator.<Sprite>comparingInt(s -> (int) Math.floor(s.x / 16f))
            .thenComparingDouble(s -> s.yUp).thenComparingDouble(s -> s.x).thenComparing(s -> s.name);

    /**
     * Writes the world as one tmx plus its tilesets into the tmx's directory.
     *
     * @param ownBiome own biome index per tile, [x][row], row 0 = north
     * @param pois     POI objects in Tiled coordinates (sorted here)
     * @param start    player start in Tiled coordinates, or null
     * @param layers   Phase 2d-1 Roads/Clutter content, or null
     */
    public static void write(FileHandle tmx, WorldData data, int[][] ownBiome, List<Poi> pois, float[] start, long seed, Layers layers) {
        Tilesets tilesets = writeTilesets(tmx.parent(), data);
        List<Poi> sorted = new ArrayList<>(pois);
        sorted.sort(ORDER);
        List<Sprite> clutter = layers == null || layers.clutter == null ? null : new ArrayList<>(layers.clutter);
        if (clutter != null)
            clutter.sort(SPRITE_ORDER);
        tmx.writeString(mapXml(ownBiome, 0, 0, ownBiome.length, ownBiome[0].length, data.tileSize, sorted, start, seed,
                tilesets, layers == null ? null : layers.roads, clutter, null), false, "UTF-8");
    }

    /**
     * Writes the world as a Tiled world into {@code dir}: {@code <worldName>.world}, chunk maps {@code chunk_<col>_<row>.tmx}
     * of {@code chunkSize} tiles (the last column/row may be smaller), the tilesets, and the generated overview
     * ({@code overview.tmx} + {@code overview.png}; an existing overview's "Reference" image layer is kept).
     * Each POI and clutter sprite goes into the chunk that contains its position, with chunk-local coordinates;
     * {@code seed} goes on chunk_0_0. Phase 2d-1: each chunk also gets the Roads and Clutter layers (from {@code layers})
     * and a hidden "Reference" image layer (kept from the existing chunk file, else pointing at {@link #REFERENCE_CHUNKS}).
     */
    public static void writeWorld(FileHandle dir, String worldName, WorldData data, int[][] ownBiome, List<Poi> pois, float[] start, long seed, int chunkSize, Layers layers) {
        int width = ownBiome.length, height = ownBiome[0].length, tile = data.tileSize;
        int columns = (width + chunkSize - 1) / chunkSize, rows = (height + chunkSize - 1) / chunkSize;
        dir.mkdirs();
        Tilesets tilesets = writeTilesets(dir, data);
        List<Poi> sorted = new ArrayList<>(pois);
        sorted.sort(ORDER);
        int span = chunkSize * tile;
        double heightPx = (double) height * tile;
        // clutter per chunk, by the position (Tiled y-down) of the sprite's bottom-left corner
        List<List<Sprite>> clutterByChunk = null;
        if (layers != null && layers.clutter != null) {
            clutterByChunk = new ArrayList<>();
            for (int i = 0; i < columns * rows; i++)
                clutterByChunk.add(new ArrayList<>());
            for (Sprite s : layers.clutter)
                clutterByChunk.get(chunkOf(heightPx - s.yUp, span, rows) * columns + chunkOf(s.x, span, columns)).add(s);
            for (List<Sprite> list : clutterByChunk)
                list.sort(SPRITE_ORDER);
        }
        StringBuilder world = new StringBuilder("{\n    \"maps\": [\n");
        for (int row = 0; row < rows; row++)
            for (int col = 0; col < columns; col++) {
                int x0 = col * chunkSize, y0 = row * chunkSize;
                int w = Math.min(chunkSize, width - x0), h = Math.min(chunkSize, height - y0);
                List<Poi> local = new ArrayList<>();
                for (Poi poi : sorted)
                    if (chunkOf(poi.x, span, columns) == col && chunkOf(poi.y, span, rows) == row) {
                        Poi l = new Poi();
                        l.name = poi.name;
                        l.displayName = poi.displayName;
                        l.spriteIndex = poi.spriteIndex;
                        l.x = poi.x - x0 * tile;
                        l.y = poi.y - y0 * tile;
                        local.add(l);
                    }
                float[] localStart = start != null && chunkOf(start[0], span, columns) == col && chunkOf(start[1], span, rows) == row
                        ? new float[]{start[0] - x0 * tile, start[1] - y0 * tile} : null;
                String name = chunkName(col, row);
                FileHandle chunkFile = dir.child(name);
                String reference = chunkReference(chunkFile, name, w * tile, h * tile);
                chunkFile.writeString(mapXml(ownBiome, x0, y0, w, h, tile, local, localStart, col == 0 && row == 0 ? seed : null, tilesets,
                        layers == null ? null : layers.roads, clutterByChunk == null ? null : clutterByChunk.get(row * columns + col), reference), false, "UTF-8");
                // Tiled's own .world layout (QJsonDocument: keys sorted, 4-space indent), so a save from Tiled changes nothing
                world.append("        {\n            \"fileName\": \"").append(name).append("\",\n            \"height\": ").append(h * tile)
                        .append(",\n            \"width\": ").append(w * tile).append(",\n            \"x\": ").append(x0 * tile)
                        .append(",\n            \"y\": ").append(y0 * tile).append("\n        }").append(row == rows - 1 && col == columns - 1 ? "\n" : ",\n");
            }
        // onlyShowAdjacentMaps: Tiled loads and shows only the current chunk and the 8 around it, not all chunks
        world.append("    ],\n    \"onlyShowAdjacentMaps\": true,\n    \"type\": \"world\"\n}\n");
        dir.child(worldName + "." + WORLD_EXTENSION).writeString(world.toString(), false, "UTF-8");
        writeOverview(dir, data, ownBiome, sorted, start, chunkSize, columns, rows);
    }

    /** The chunk's Reference image layer: kept from the existing chunk file (attributes and image verbatim), else the default crop. */
    private static String chunkReference(FileHandle chunkFile, String name, int widthPx, int heightPx) {
        if (chunkFile.exists()) {
            try {
                for (XmlReader.Element layer : new XmlReader().parse(chunkFile).getChildrenByName("imagelayer"))
                    if (REFERENCE_LAYER.equals(layer.getAttribute("name", "")))
                        return imageLayerXml(layer, REFERENCE_LAYER_ID);
            } catch (Exception e) {
                System.err.println("exportWorldTmx: cannot read the old " + chunkFile.path() + " (" + e + "), Reference layer reset");
            }
        }
        String crop = name.substring(0, name.length() - 4) + ".png";
        return " <imagelayer id=\"" + REFERENCE_LAYER_ID + "\" name=\"" + REFERENCE_LAYER + "\" opacity=\"0.5\" visible=\"0\">\n  <image source=\""
                + REFERENCE_CHUNKS + crop + "\" width=\"" + widthPx + "\" height=\"" + heightPx + "\"/>\n </imagelayer>\n";
    }

    public static String chunkName(int column, int row) {
        return "chunk_" + column + "_" + row + ".tmx";
    }

    private static int chunkOf(double v, int span, int count) {
        return Math.max(0, Math.min(count - 1, (int) Math.floor(v / span)));
    }

    // Layer ids of a map. Phase 2d-2 (Terrain) will take id 6, between Biomes and Roads.
    private static final int BIOME_LAYER_ID = 1, POI_LAYER_ID = 2, ROADS_LAYER_ID = 3, CLUTTER_LAYER_ID = 4, REFERENCE_LAYER_ID = 5;

    /**
     * One map: layers Biomes, Roads, Clutter, Reference (image, if given), POI for tiles [x0, x0+w) x [y0, y0+h).
     * pois/start: local, sorted. roads: whole world [x][row] or null. clutter: this map's sprites (sorted) or null.
     */
    private static String mapXml(int[][] ownBiome, int x0, int y0, int width, int height, int tile, List<Poi> pois, float[] start, Long seed,
                                 Tilesets tilesets, boolean[][] roads, List<Sprite> clutter, String reference) {
        int objects = pois.size() + (start != null ? 1 : 0) + (clutter != null ? clutter.size() : 0);
        StringBuilder s = new StringBuilder();
        s.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        s.append("<map version=\"1.10\" tiledversion=\"1.10.2\" orientation=\"orthogonal\" renderorder=\"right-down\" width=\"")
                .append(width).append("\" height=\"").append(height).append("\" tilewidth=\"").append(tile).append("\" tileheight=\"").append(tile)
                .append("\" infinite=\"0\" nextlayerid=\"").append(roads != null || clutter != null || reference != null ? REFERENCE_LAYER_ID + 1 : 3)
                .append("\" nextobjectid=\"").append(objects + 1).append("\">\n");
        if (seed != null)
            s.append(" <properties>\n  <property name=\"seed\" value=\"").append(seed).append("\"/>\n </properties>\n");
        // firstgids as Tiled assigns them on save (consecutive)
        s.append(" <tileset firstgid=\"1\" source=\"").append(TILESET_NAME).append(".tsx\"/>\n");
        if (roads != null || clutter != null) {
            s.append(" <tileset firstgid=\"").append(tilesets.roadFirstGid).append("\" source=\"").append(ROADS_TILESET).append(".tsx\"/>\n");
            s.append(" <tileset firstgid=\"").append(tilesets.clutterFirstGid).append("\" source=\"").append(CLUTTER_TILESET).append(".tsx\"/>\n");
        }
        tileLayer(s, BIOME_LAYER_ID, BIOME_LAYER, width, height, (x, y) -> ownBiome[x0 + x][y0 + y] + 1);
        if (roads != null)
            tileLayer(s, ROADS_LAYER_ID, ROADS_LAYER, width, height, (x, y) -> roads[x0 + x][y0 + y] ? tilesets.roadFirstGid : 0);
        int id = 1;
        if (clutter != null) {
            // exact positions: chunk-local Tiled coordinates; the reader adds the chunk origin and the game converts to y-up
            double left = (double) x0 * tile, top = (double) y0 * tile, heightPx = (double) ownBiome[0].length * tile;
            s.append(" <objectgroup id=\"").append(CLUTTER_LAYER_ID).append("\" name=\"").append(CLUTTER_LAYER).append("\">\n");
            int poiObjects = pois.size() + (start != null ? 1 : 0);
            id += poiObjects;
            for (Sprite sprite : clutter) {
                int tileId = tilesets.sprites.tileId(sprite);
                s.append("  <object id=\"").append(id++).append("\"");
                if (tileId >= 0)
                    s.append(" gid=\"").append(tilesets.clutterFirstGid + tileId).append("\"");
                s.append(" x=\"").append(exact((double) sprite.x - left, v -> (float) (v + left), sprite.x))
                        .append("\" y=\"").append(exact(heightPx - top - sprite.yUp, v -> (float) (heightPx - (v + top)), sprite.yUp)).append("\"");
                if (tileId < 0) // unknown sprite: a plain point with the name, so it is not lost
                    s.append(">\n   <properties>\n    <property name=\"sprite\" value=\"").append(esc(sprite.name)).append("\"/>\n   </properties>\n   <point/>\n  </object>\n");
                else
                    s.append("/>\n");
            }
            s.append(" </objectgroup>\n");
            id = 1;
        }
        if (reference != null)
            s.append(reference);
        s.append(" <objectgroup id=\"").append(POI_LAYER_ID).append("\" name=\"").append(POI_LAYER).append("\">\n");
        if (start != null)
            s.append("  <object id=\"").append(id++).append("\" name=\"Start\" type=\"").append(START_TYPE).append("\" x=\"").append(num(start[0]))
                    .append("\" y=\"").append(num(start[1])).append("\">\n   <point/>\n  </object>\n");
        for (Poi poi : pois) {
            s.append("  <object id=\"").append(id++).append("\" name=\"").append(esc(poi.name)).append("\" type=\"poi\" x=\"").append(num(poi.x))
                    .append("\" y=\"").append(num(poi.y)).append("\">\n   <properties>\n");
            if (poi.displayName != null)
                s.append("    <property name=\"displayName\" value=\"").append(esc(poi.displayName)).append("\"/>\n");
            if (poi.spriteIndex >= 0)
                s.append("    <property name=\"spriteIndex\" type=\"int\" value=\"").append(poi.spriteIndex).append("\"/>\n");
            s.append("   </properties>\n   <point/>\n  </object>\n");
        }
        s.append(" </objectgroup>\n</map>\n");
        return s.toString();
    }

    private interface Cell {
        int gid(int x, int y);
    }

    private static void tileLayer(StringBuilder s, int id, String name, int width, int height, Cell cell) {
        byte[] raw = new byte[width * height * 4];
        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++) {
                int gid = cell.gid(x, y), i = (y * width + x) * 4;
                raw[i] = (byte) gid;
                raw[i + 1] = (byte) (gid >> 8);
                raw[i + 2] = (byte) (gid >> 16);
                raw[i + 3] = (byte) (gid >> 24);
            }
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (DeflaterOutputStream out = new DeflaterOutputStream(compressed, new java.util.zip.Deflater(9))) {
            out.write(raw);
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
        s.append(" <layer id=\"").append(id).append("\" name=\"").append(name).append("\" width=\"").append(width).append("\" height=\"").append(height).append("\">\n");
        s.append("  <data encoding=\"base64\" compression=\"zlib\">\n   ")
                .append(Base64Coder.encode(compressed.toByteArray())).append("\n  </data>\n </layer>\n");
    }

    private interface Back {
        float toGame(double value);
    }

    /**
     * The shortest text for a coordinate that gives back the game's exact float: the float's own shortest form if
     * reading it back (parse as double, {@code back}) is exact, else the full double (always exact: the local value is
     * computed exactly in double).
     */
    private static String exact(double local, Back back, float target) {
        String text = num((float) local);
        if (back.toGame(Double.parseDouble(text)) == target)
            return text;
        text = local == Math.rint(local) ? Long.toString((long) local) : Double.toString(local);
        if (back.toGame(Double.parseDouble(text)) != target)
            System.err.println("exportWorldTmx: clutter coordinate " + text + " does not round-trip to " + target);
        return text;
    }

    /** Tileset ids of a map (firstgids as Tiled numbers them: consecutive in file order) and the clutter tile ids. */
    static final class Tilesets {
        int roadFirstGid, clutterFirstGid;
        SpriteTiles sprites;
    }

    /** Writes biomes, roads and clutter tilesets into dir. */
    static Tilesets writeTilesets(FileHandle dir, WorldData data) {
        writeTileset(dir, data.GetBiomes(), data.tileSize);
        Tilesets t = new Tilesets();
        t.roadFirstGid = 1 + data.GetBiomes().size();
        t.clutterFirstGid = t.roadFirstGid + 1;
        writeRoadTileset(dir, data);
        t.sprites = writeClutterTileset(dir, data);
        return t;
    }

    /**
     * {@code roads.tsx} + {@code roads.png}: one tile, the centre tile of world.json's roadTileset autotile (what the game
     * draws inside a road), property road=true.
     */
    private static void writeRoadTileset(FileHandle dir, WorldData data) {
        int tile = data.tileSize;
        Pixmap image = new Pixmap(tile, tile, Pixmap.Format.RGBA8888);
        try {
            image.setBlending(Pixmap.Blending.None);
            image.setColor(0.6f, 0.5f, 0.35f, 1); // fallback colour if the atlas can't be read
            image.fill();
            try {
                FileHandle atlasFile = forge.adventure.util.Config.instance().getFile(data.roadTileset.tilesetAtlas);
                TextureAtlas.TextureAtlasData atlas = new TextureAtlas.TextureAtlasData(atlasFile, atlasFile.parent(), false);
                for (TextureAtlas.TextureAtlasData.Region region : atlas.getRegions())
                    if (region.name.equals(data.roadTileset.tilesetName)) {
                        Pixmap page = new Pixmap(region.page.textureFile);
                        try {
                            page.setBlending(Pixmap.Blending.None);
                            // BiomeTexture: the autotile is 3x4 tiles, the centre is column 1, row 2
                            image.drawPixmap(page, 0, 0, region.left + tile, region.top + 2 * tile, tile, tile);
                        } finally {
                            page.dispose();
                        }
                        break;
                    }
            } catch (Exception e) {
                System.err.println("exportWorldTmx: road tile from " + data.roadTileset.tilesetAtlas + ": " + e + " (using a plain colour)");
            }
            PixmapIO.writePNG(dir.child(ROADS_TILESET + ".png"), image);
        } finally {
            image.dispose();
        }
        dir.child(ROADS_TILESET + ".tsx").writeString("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<tileset version=\"1.10\" tiledversion=\"1.10.2\" name=\"" + ROADS_TILESET + "\" tilewidth=\"" + tile + "\" tileheight=\"" + tile
                + "\" tilecount=\"1\" columns=\"1\">\n <image source=\"" + ROADS_TILESET + ".png\" width=\"" + tile + "\" height=\"" + tile + "\"/>\n"
                + " <tile id=\"0\">\n  <properties>\n   <property name=\"road\" type=\"bool\" value=\"true\"/>\n  </properties>\n </tile>\n</tileset>\n", false, "UTF-8");
    }

    /**
     * {@code clutter.tsx}: an image collection with one tile per sprite variant of world.json's biomesSprites (in the order
     * of its sprites list, variants in atlas order as the game's createSprites), each a sub-rectangle of a copy of the
     * atlas page ({@code clutter.png}, or {@code clutter_<n>.png} for more pages). Tile properties: sprite, variant.
     */
    private static SpriteTiles writeClutterTileset(FileHandle dir, WorldData data) {
        SpriteTiles result = new SpriteTiles();
        BiomeSprites sprites = data.GetBiomeSprites();
        FileHandle atlasFile = forge.adventure.util.Config.instance().getFile(sprites.textureAtlas);
        TextureAtlas.TextureAtlasData atlas = new TextureAtlas.TextureAtlasData(atlasFile, atlasFile.parent(), false);
        List<TextureAtlas.TextureAtlasData.Page> pages = new ArrayList<>();
        for (TextureAtlas.TextureAtlasData.Page page : atlas.getPages())
            pages.add(page);
        StringBuilder tiles = new StringBuilder();
        int id = 0, maxW = 1, maxH = 1;
        for (BiomeSpriteData sprite : sprites.sprites) {
            int first = id;
            for (TextureAtlas.TextureAtlasData.Region region : atlas.getRegions()) {
                if (!region.name.equals(sprite.name))
                    continue;
                int pageIndex = pages.indexOf(region.page);
                String image = pages.size() == 1 ? CLUTTER_TILESET + ".png" : CLUTTER_TILESET + "_" + pageIndex + ".png";
                tiles.append(" <tile id=\"").append(id).append("\" x=\"").append(region.left).append("\" y=\"").append(region.top)
                        .append("\" width=\"").append(region.width).append("\" height=\"").append(region.height).append("\">\n  <properties>\n")
                        .append("   <property name=\"sprite\" value=\"").append(esc(sprite.name)).append("\"/>\n")
                        .append("   <property name=\"variant\" type=\"int\" value=\"").append(id - first).append("\"/>\n  </properties>\n")
                        .append("  <image source=\"").append(image).append("\" width=\"").append((int) region.page.width).append("\" height=\"")
                        .append((int) region.page.height).append("\"/>\n </tile>\n");
                maxW = Math.max(maxW, region.width);
                maxH = Math.max(maxH, region.height);
                id++;
            }
            result.firstAndCount.put(sprite.name, new int[]{first, id - first});
        }
        for (int i = 0; i < pages.size(); i++)
            pages.get(i).textureFile.copyTo(dir.child(pages.size() == 1 ? CLUTTER_TILESET + ".png" : CLUTTER_TILESET + "_" + i + ".png"));
        dir.child(CLUTTER_TILESET + ".tsx").writeString("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<tileset version=\"1.10\" tiledversion=\"1.10.2\" name=\"" + CLUTTER_TILESET + "\" tilewidth=\"" + maxW + "\" tileheight=\"" + maxH
                + "\" tilecount=\"" + id + "\" columns=\"0\">\n <grid orientation=\"orthogonal\" width=\"1\" height=\"1\"/>\n" + tiles + "</tileset>\n", false, "UTF-8");
        return result;
    }

    /**
     * The overview: one map tile per chunk (so Tiled's grid shows the chunk borders), 1 px per world tile. Layers: image
     * layer "Biomes" (overview.png, the biome colours), image layer "Reference" (kept from an existing overview.tmx, else an
     * empty slot), object layers "Chunks" (one rectangle per chunk map) and "POI" (labels only). Generated, never read back.
     */
    private static void writeOverview(FileHandle dir, WorldData data, int[][] ownBiome, List<Poi> pois, float[] start, int chunkSize, int columns, int rows) {
        int width = ownBiome.length, height = ownBiome[0].length, tile = data.tileSize;
        List<BiomeData> biomes = data.GetBiomes();
        Pixmap image = new Pixmap(width, height, Pixmap.Format.RGBA8888);
        try {
            int[] rgba = new int[biomes.size()];
            for (int i = 0; i < rgba.length; i++)
                rgba[i] = Color.rgba8888(biomes.get(i).GetColor());
            for (int x = 0; x < width; x++)
                for (int y = 0; y < height; y++)
                    image.drawPixel(x, y, rgba[ownBiome[x][y]]);
            PixmapIO.writePNG(dir.child(OVERVIEW_NAME + ".png"), image);
        } finally {
            image.dispose();
        }

        FileHandle tmx = dir.child(OVERVIEW_NAME + ".tmx");
        String reference = " <imagelayer id=\"2\" name=\"" + REFERENCE_LAYER + "\" opacity=\"0.6\"/>\n";
        if (tmx.exists()) {
            try {
                for (XmlReader.Element layer : new XmlReader().parse(tmx).getChildrenByName("imagelayer"))
                    if (REFERENCE_LAYER.equals(layer.getAttribute("name", "")))
                        reference = imageLayerXml(layer, 2);
            } catch (Exception e) {
                System.err.println("overview: cannot read the old " + tmx.path() + " (" + e + "), Reference layer reset");
            }
        }
        int objects = columns * rows + pois.size() + (start != null ? 1 : 0);
        StringBuilder s = new StringBuilder();
        s.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        s.append("<map version=\"1.10\" tiledversion=\"1.10.2\" orientation=\"orthogonal\" renderorder=\"right-down\" width=\"").append(columns)
                .append("\" height=\"").append(rows).append("\" tilewidth=\"").append(chunkSize).append("\" tileheight=\"").append(chunkSize)
                .append("\" infinite=\"0\" nextlayerid=\"5\" nextobjectid=\"").append(objects + 1).append("\">\n");
        s.append(" <properties>\n  <property name=\"generated\" value=\"by exportWorldTmx from the chunks: 1 px = 1 world tile, one grid cell = one chunk. Edits are not read back, except the Reference layer, which a new export keeps.\"/>\n </properties>\n");
        s.append(" <imagelayer id=\"1\" name=\"").append(BIOME_LAYER).append("\" locked=\"1\">\n  <image source=\"").append(OVERVIEW_NAME)
                .append(".png\" width=\"").append(width).append("\" height=\"").append(height).append("\"/>\n </imagelayer>\n");
        s.append(reference);
        s.append(" <objectgroup id=\"3\" name=\"Chunks\" locked=\"1\">\n");
        int id = 1;
        for (int row = 0; row < rows; row++)
            for (int col = 0; col < columns; col++)
                s.append("  <object id=\"").append(id++).append("\" name=\"").append(chunkName(col, row)).append("\" x=\"").append(col * chunkSize)
                        .append("\" y=\"").append(row * chunkSize).append("\" width=\"").append(Math.min(chunkSize, width - col * chunkSize))
                        .append("\" height=\"").append(Math.min(chunkSize, height - row * chunkSize)).append("\"/>\n");
        s.append(" </objectgroup>\n <objectgroup id=\"4\" name=\"").append(POI_LAYER).append("\" locked=\"1\">\n");
        if (start != null)
            s.append("  <object id=\"").append(id++).append("\" name=\"Start\" type=\"").append(START_TYPE).append("\" x=\"").append(num(start[0] / tile))
                    .append("\" y=\"").append(num(start[1] / tile)).append("\">\n   <point/>\n  </object>\n");
        for (Poi poi : pois)
            s.append("  <object id=\"").append(id++).append("\" name=\"").append(esc(poi.displayName != null ? poi.displayName : poi.name))
                    .append("\" type=\"").append(esc(poi.name)).append("\" x=\"").append(num(poi.x / tile)).append("\" y=\"").append(num(poi.y / tile))
                    .append("\">\n   <point/>\n  </object>\n");
        s.append(" </objectgroup>\n</map>\n");
        tmx.writeString(s.toString(), false, "UTF-8");
    }

    /** An existing Reference image layer (overview or chunk), normalised (attribute values copied verbatim). */
    private static String imageLayerXml(XmlReader.Element layer, int id) {
        StringBuilder s = new StringBuilder(" <imagelayer id=\"").append(id).append("\" name=\"").append(REFERENCE_LAYER).append("\"");
        for (String a : new String[]{"offsetx", "offsety", "opacity", "visible", "locked", "tintcolor"})
            if (layer.getAttribute(a, null) != null)
                s.append(" ").append(a).append("=\"").append(esc(layer.getAttribute(a))).append("\"");
        XmlReader.Element image = layer.getChildByName("image");
        if (image == null || image.getAttribute("source", "").isEmpty())
            return s.append("/>\n").toString();
        s.append(">\n  <image source=\"").append(esc(image.getAttribute("source"))).append("\"");
        for (String a : new String[]{"width", "height"})
            if (image.getAttribute(a, null) != null)
                s.append(" ").append(a).append("=\"").append(esc(image.getAttribute(a))).append("\"");
        return s.append("/>\n </imagelayer>\n").toString();
    }

    /** One solid tile per biome (its colour, 1-px darker border), tile id = biome index, property biome = name. */
    static void writeTileset(FileHandle dir, List<BiomeData> biomes, int tile) {
        int columns = 8, rows = (biomes.size() + columns - 1) / columns;
        Pixmap image = new Pixmap(columns * tile, rows * tile, Pixmap.Format.RGBA8888);
        try {
            image.setColor(0, 0, 0, 0);
            image.fill();
            for (int i = 0; i < biomes.size(); i++) {
                Color color = biomes.get(i).GetColor();
                int x = (i % columns) * tile, y = (i / columns) * tile;
                image.setColor(color.r * 0.6f, color.g * 0.6f, color.b * 0.6f, 1);
                image.fillRectangle(x, y, tile, tile);
                image.setColor(color.r, color.g, color.b, 1);
                image.fillRectangle(x + 1, y + 1, tile - 2, tile - 2);
            }
            PixmapIO.writePNG(dir.child(TILESET_NAME + ".png"), image);
        } finally {
            image.dispose();
        }
        StringBuilder s = new StringBuilder();
        s.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        s.append("<tileset version=\"1.10\" tiledversion=\"1.10.2\" name=\"").append(TILESET_NAME).append("\" tilewidth=\"").append(tile)
                .append("\" tileheight=\"").append(tile).append("\" tilecount=\"").append(biomes.size()).append("\" columns=\"").append(columns).append("\">\n");
        s.append(" <image source=\"").append(TILESET_NAME).append(".png\" width=\"").append(columns * tile).append("\" height=\"").append(rows * tile).append("\"/>\n");
        for (int i = 0; i < biomes.size(); i++)
            s.append(" <tile id=\"").append(i).append("\">\n  <properties>\n   <property name=\"biome\" value=\"").append(esc(biomes.get(i).name))
                    .append("\"/>\n  </properties>\n </tile>\n");
        s.append("</tileset>\n");
        dir.child(TILESET_NAME + ".tsx").writeString(s.toString(), false, "UTF-8");
    }

    static String num(float v) {
        return v == Math.rint(v) ? Long.toString((long) v) : Float.toString(v);
    }

    static String esc(String v) {
        return v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
