package forge.adventure.world;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.utils.Base64Coder;
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
 * Parsing uses libGDX's XmlReader plus java.util.zip only (no TmxMapLoader, which loads textures and needs a GL context).
 * Tiled coordinates are y-down (row 0 = north); the world's pixel coordinates are y-up.
 */
public final class WorldMapTmx {
    public static final String BIOME_LAYER = "Biomes";
    public static final String POI_LAYER = "POI";
    public static final String START_TYPE = "start";
    public static final String TILESET_NAME = "biomes";

    /** A POI object; x/y are the Tiled (y-down) pixel coordinates of the POI position. */
    public static final class Poi {
        public String name;
        public String displayName;
        public float x, y;
        public int spriteIndex = -1;
    }

    /** Explicit seed from the map property "seed", or null. */
    public Long seed;
    /** Own biome index per tile, [x][row], row 0 = north (same layout as World.biomeMap). */
    public int[][] biomes;
    /** POI objects, sorted by name, then x, then y (the order does not depend on the file). */
    public final List<Poi> pois = new ArrayList<>();
    /** Player start in Tiled pixel coordinates (y-down), or null. */
    public float[] start;
    public int mapWidth, mapHeight, tileWidth, tileHeight;

    private static final Comparator<Poi> ORDER = Comparator.<Poi, String>comparing(p -> p.name)
            .thenComparingDouble(p -> p.x).thenComparingDouble(p -> p.y);

    // ------------------------------------------------------------------ reading

    /** Parses the tmx; biome names are resolved against {@code biomes} (unknown names and empty cells: biome 0). */
    public static WorldMapTmx read(FileHandle tmx, List<BiomeData> biomes, int worldWidth, int worldHeight) {
        XmlReader.Element map = new XmlReader().parse(tmx);
        WorldMapTmx result = new WorldMapTmx();
        result.mapWidth = map.getIntAttribute("width");
        result.mapHeight = map.getIntAttribute("height");
        result.tileWidth = map.getIntAttribute("tilewidth");
        result.tileHeight = map.getIntAttribute("tileheight");

        String seed = properties(map).get("seed");
        if (seed != null && !seed.trim().isEmpty())
            result.seed = Long.parseLong(seed.trim());

        // gid -> biome index
        Map<String, Integer> biomeByName = new HashMap<>();
        for (int i = 0; i < biomes.size(); i++)
            biomeByName.putIfAbsent(biomes.get(i).name, i);
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
                gids = decodeLayer(layer, result.mapWidth * result.mapHeight);
        if (gids == null)
            throw new IllegalArgumentException("worldMap " + tmx.path() + ": no tile layer '" + BIOME_LAYER + "'");
        result.biomes = new int[worldWidth][worldHeight];
        Set<Integer> unknown = new TreeSet<>();
        for (int x = 0; x < worldWidth; x++)
            for (int y = 0; y < worldHeight; y++) {
                int gid = gids[(y * result.mapHeight / worldHeight) * result.mapWidth + x * result.mapWidth / worldWidth] & 0x0FFFFFFF;
                Integer biome = gidToBiome.get(gid);
                if (biome == null && gid != 0)
                    unknown.add(gid);
                result.biomes[x][y] = biome == null ? 0 : biome;
            }
        if (!unknown.isEmpty())
            System.err.println("worldMap: gids without a biome property (using biome 0): " + unknown);

        // POIs and start
        for (XmlReader.Element group : map.getChildrenByName("objectgroup")) {
            if (!POI_LAYER.equals(group.getAttribute("name", "")))
                continue;
            for (XmlReader.Element object : group.getChildrenByName("object")) {
                float x = object.getFloatAttribute("x", 0), y = object.getFloatAttribute("y", 0);
                String type = object.getAttribute("type", object.getAttribute("class", ""));
                if (START_TYPE.equals(type)) {
                    if (result.start != null)
                        System.err.println("worldMap: more than one start object, using the first");
                    else
                        result.start = new float[]{x, y};
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
        result.pois.sort(ORDER);
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
     * Writes the world as tmx plus its tileset ({@code biomes.tsx}, {@code biomes.png}) into the tmx's directory.
     *
     * @param ownBiome own biome index per tile, [x][row], row 0 = north
     * @param pois     POI objects in Tiled coordinates (sorted here)
     * @param start    player start in Tiled coordinates, or null
     */
    public static void write(FileHandle tmx, WorldData data, int[][] ownBiome, List<Poi> pois, float[] start, long seed) {
        List<BiomeData> biomes = data.GetBiomes();
        int width = ownBiome.length, height = ownBiome[0].length, tile = data.tileSize;
        writeTileset(tmx.parent(), biomes, tile);

        byte[] raw = new byte[width * height * 4];
        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++) {
                int gid = ownBiome[x][y] + 1, i = (y * width + x) * 4;
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

        List<Poi> sorted = new ArrayList<>(pois);
        sorted.sort(ORDER);
        int objects = sorted.size() + (start != null ? 1 : 0);

        StringBuilder s = new StringBuilder();
        s.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        s.append("<map version=\"1.10\" tiledversion=\"1.10.2\" orientation=\"orthogonal\" renderorder=\"right-down\" width=\"")
                .append(width).append("\" height=\"").append(height).append("\" tilewidth=\"").append(tile).append("\" tileheight=\"").append(tile)
                .append("\" infinite=\"0\" nextlayerid=\"3\" nextobjectid=\"").append(objects + 1).append("\">\n");
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
        for (Poi poi : sorted) {
            s.append("  <object id=\"").append(id++).append("\" name=\"").append(esc(poi.name)).append("\" type=\"poi\" x=\"").append(num(poi.x))
                    .append("\" y=\"").append(num(poi.y)).append("\">\n   <properties>\n");
            if (poi.displayName != null)
                s.append("    <property name=\"displayName\" value=\"").append(esc(poi.displayName)).append("\"/>\n");
            if (poi.spriteIndex >= 0)
                s.append("    <property name=\"spriteIndex\" type=\"int\" value=\"").append(poi.spriteIndex).append("\"/>\n");
            s.append("   </properties>\n   <point/>\n  </object>\n");
        }
        s.append(" </objectgroup>\n</map>\n");
        tmx.writeString(s.toString(), false, "UTF-8");
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
