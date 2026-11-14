package forge.adventure.world;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.utils.Base64Coder;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import com.badlogic.gdx.utils.XmlReader;
import forge.adventure.data.BiomeData;
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

    private static final Comparator<Poi> ORDER = Comparator.<Poi, String>comparing(p -> p.name)
            .thenComparingDouble(p -> p.x).thenComparingDouble(p -> p.y);

    /** One parsed tmx: own biome per cell (row-major, row 0 = north), objects in the map's own pixel coordinates. */
    private static final class Parsed {
        int width, height, tileWidth, tileHeight;
        int[] biomes;
        String seed;
        final List<Poi> pois = new ArrayList<>();
        final List<float[]> starts = new ArrayList<>();
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
                if (p.seed != null)
                    seeds.add(p.seed);
            }
            // assemble, checking that the chunks tile the world without gaps or overlaps
            own = new int[result.mapWidth * result.mapHeight];
            boolean[] covered = new boolean[own.length];
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
                        if (!covered[g]) // first chunk wins on overlaps
                            own[g] = p.biomes[y * p.width + x];
                        covered[g] = true;
                    }
            }
            int gaps = 0;
            for (boolean b : covered)
                if (!b)
                    gaps++;
            if (gaps > 0)
                result.problem(gaps + " tiles of the " + result.mapWidth + "x" + result.mapHeight + " world are in no chunk (biome 0)");
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

        // gid -> biome index
        Map<Integer, Integer> gidToBiome = new HashMap<>();
        for (XmlReader.Element ts : map.getChildrenByName("tileset")) {
            int firstGid = ts.getIntAttribute("firstgid", 1);
            XmlReader.Element tileset = ts;
            String source = ts.getAttribute("source", null);
            if (source != null)
                tileset = new XmlReader().parse(tmx.parent().child(source));
            for (XmlReader.Element tile : tileset.getChildrenByName("tile")) {
                String biome = properties(tile).get("biome");
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

        // biome layer
        int[] gids = null;
        for (XmlReader.Element layer : map.getChildrenByName("layer"))
            if (BIOME_LAYER.equals(layer.getAttribute("name", "")))
                gids = decodeLayer(layer, result.width * result.height);
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

        // POIs and start
        for (XmlReader.Element group : map.getChildrenByName("objectgroup")) {
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

    /**
     * Writes the world as one tmx plus its tileset ({@code biomes.tsx}, {@code biomes.png}) into the tmx's directory.
     *
     * @param ownBiome own biome index per tile, [x][row], row 0 = north
     * @param pois     POI objects in Tiled coordinates (sorted here)
     * @param start    player start in Tiled coordinates, or null
     */
    public static void write(FileHandle tmx, WorldData data, int[][] ownBiome, List<Poi> pois, float[] start, long seed) {
        writeTileset(tmx.parent(), data.GetBiomes(), data.tileSize);
        List<Poi> sorted = new ArrayList<>(pois);
        sorted.sort(ORDER);
        tmx.writeString(mapXml(ownBiome, 0, 0, ownBiome.length, ownBiome[0].length, data.tileSize, sorted, start, seed), false, "UTF-8");
    }

    /**
     * Writes the world as a Tiled world into {@code dir}: {@code <worldName>.world}, chunk maps {@code chunk_<col>_<row>.tmx}
     * of {@code chunkSize} tiles (the last column/row may be smaller), the tileset, and the generated overview
     * ({@code overview.tmx} + {@code overview.png}; an existing overview's "Reference" image layer is kept).
     * Each POI goes into the chunk that contains its position, with chunk-local coordinates; {@code seed} goes on chunk_0_0.
     */
    public static void writeWorld(FileHandle dir, String worldName, WorldData data, int[][] ownBiome, List<Poi> pois, float[] start, long seed, int chunkSize) {
        int width = ownBiome.length, height = ownBiome[0].length, tile = data.tileSize;
        int columns = (width + chunkSize - 1) / chunkSize, rows = (height + chunkSize - 1) / chunkSize;
        dir.mkdirs();
        writeTileset(dir, data.GetBiomes(), tile);
        List<Poi> sorted = new ArrayList<>(pois);
        sorted.sort(ORDER);
        int span = chunkSize * tile;
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
                dir.child(name).writeString(mapXml(ownBiome, x0, y0, w, h, tile, local, localStart, col == 0 && row == 0 ? seed : null), false, "UTF-8");
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

    public static String chunkName(int column, int row) {
        return "chunk_" + column + "_" + row + ".tmx";
    }

    private static int chunkOf(float v, int span, int count) {
        return Math.max(0, Math.min(count - 1, (int) Math.floor(v / span)));
    }

    /** One map: the Biomes layer for tiles [x0, x0+w) x [y0, y0+h), the POI layer with the given (local, sorted) objects. */
    private static String mapXml(int[][] ownBiome, int x0, int y0, int width, int height, int tile, List<Poi> pois, float[] start, Long seed) {
        byte[] raw = new byte[width * height * 4];
        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++) {
                int gid = ownBiome[x0 + x][y0 + y] + 1, i = (y * width + x) * 4;
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
        int objects = pois.size() + (start != null ? 1 : 0);

        StringBuilder s = new StringBuilder();
        s.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        s.append("<map version=\"1.10\" tiledversion=\"1.10.2\" orientation=\"orthogonal\" renderorder=\"right-down\" width=\"")
                .append(width).append("\" height=\"").append(height).append("\" tilewidth=\"").append(tile).append("\" tileheight=\"").append(tile)
                .append("\" infinite=\"0\" nextlayerid=\"3\" nextobjectid=\"").append(objects + 1).append("\">\n");
        if (seed != null)
            s.append(" <properties>\n  <property name=\"seed\" value=\"").append(seed).append("\"/>\n </properties>\n");
        s.append(" <tileset firstgid=\"1\" source=\"").append(TILESET_NAME).append(".tsx\"/>\n");
        s.append(" <layer id=\"1\" name=\"").append(BIOME_LAYER).append("\" width=\"").append(width).append("\" height=\"").append(height).append("\">\n");
        s.append("  <data encoding=\"base64\" compression=\"zlib\">\n   ")
                .append(Base64Coder.encode(compressed.toByteArray())).append("\n  </data>\n </layer>\n");
        s.append(" <objectgroup id=\"2\" name=\"").append(POI_LAYER).append("\">\n");
        int id = 1;
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
                        reference = imageLayerXml(layer);
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

    /** The Reference image layer of an existing overview, normalised (attribute values copied verbatim). */
    private static String imageLayerXml(XmlReader.Element layer) {
        StringBuilder s = new StringBuilder(" <imagelayer id=\"2\" name=\"").append(REFERENCE_LAYER).append("\"");
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
