import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Json;
import forge.adventure.data.BiomeData;
import forge.adventure.data.PointOfInterestData;
import forge.adventure.data.WorldData;
import forge.adventure.world.OpenSimplexNoise;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;
import java.util.List;

/**
 * Offline replica of World.generateNew (biome painting + POI placement) for layout work.
 * Usage: java -cp FORGE_JAR WorldSim.java <planeDir> <seed|0> <out.png|-> [runs]
 */
public class WorldSim {
    static int width, height;
    static long[][] biomeMap;

    static int highestBiome(long biome) {
        return (int) (Math.log(Long.highestOneBit(biome)) / Math.log(2));
    }

    static long getBiome(int x, int y) { // world coords, y up
        try {
            return biomeMap[x][height - y - 1];
        } catch (ArrayIndexOutOfBoundsException e) {
            return biomeMap[width - 1][height - 1];
        }
    }

    record Placed(String name, String label, String type, float x, float y, int biome) {}

    public static void main(String[] args) throws Exception {
        String planeDir = args[0];
        long seedArg = Long.parseLong(args[1]);
        String out = args[2];
        int runs = args.length > 3 ? Integer.parseInt(args[3]) : 1;
        Json json = new Json();
        json.setIgnoreUnknownFields(true);
        WorldData data = json.fromJson(WorldData.class, new FileHandle(new File(planeDir, "world/world.json")));
        List<BiomeData> biomes = new ArrayList<>();
        for (String n : data.biomesNames) biomes.add(json.fromJson(BiomeData.class, new FileHandle(new File(planeDir, n))));
        Array<PointOfInterestData> allPois = json.fromJson(Array.class, PointOfInterestData.class, new FileHandle(new File(planeDir, "world/points_of_interest.json")));
        width = data.width;
        height = data.height;

        int totalReruns = 0, maxReruns = 0, totalUnreach = 0, runsWithUnreach = 0;
        Map<String, Integer> dropped = new TreeMap<>();
        Random seeder = new Random(seedArg == 0 ? System.nanoTime() : seedArg);
        for (int run = 0; run < runs; run++) {
            long seed = (runs == 1 && seedArg != 0) ? seedArg : seeder.nextLong();
            Random random = new Random(seed);
            OpenSimplexNoise noise = new OpenSimplexNoise(seed);
            float noiseZoom = data.noiseZoomBiome;
            biomeMap = new long[width][height];
            int bi = -1;
            for (BiomeData biome : biomes) {
                bi++;
                int biomeXStart = (int) Math.round(biome.startPointX * (double) width);
                int biomeYStart = (int) Math.round(biome.startPointY * (double) height);
                int biomeWidth = (int) Math.round(biome.width * (double) width);
                int biomeHeight = (int) Math.round(biome.height * (double) height);
                int beginX = Math.max(biomeXStart - biomeWidth / 2, 0);
                int beginY = Math.max(biomeYStart - biomeHeight / 2, 0);
                int endX = Math.min(biomeXStart + biomeWidth / 2, width);
                int endY = Math.min(biomeYStart + biomeHeight / 2, height);
                if (biome.width == 1.0 && biome.height == 1.0) {
                    beginX = 0; beginY = 0; endX = width; endY = height;
                }
                for (int x = beginX; x < endX; x++)
                    for (int y = beginY; y < endY; y++) {
                        float noiseValue = ((float) noise.eval(x / (float) width * noiseZoom, y / (float) height * noiseZoom) + 1) / 2f;
                        noiseValue *= biome.noiseWeight;
                        float distanceValue = ((float) Math.sqrt((x - biomeXStart) * (x - biomeXStart) + (y - biomeYStart) * (y - biomeYStart))) / (Math.max(biomeWidth, biomeHeight) / 2f);
                        distanceValue *= biome.distWeight;
                        if (noiseValue + distanceValue < 1.0 || biome.invertHeight && (1 - noiseValue) + distanceValue < 1.0)
                            biomeMap[x][y] |= (1L << bi);
                    }
            }
            // POIs
            List<Placed> placed = new ArrayList<>();
            List<float[]> otherPoints = new ArrayList<>();
            Set<String> placedNames = new HashSet<>();
            int tile = data.tileSize;
            boolean running = true;
            int reruns = 0;
            Map<String, Integer> runDropped = new TreeMap<>();
            here:
            while (running) {
                running = false;
                runDropped.clear();
                int bi2 = -1;
                for (BiomeData biome : biomes) {
                    bi2++;
                    for (PointOfInterestData poi : poisOf(biome, allPois)) {
                        for (int i = 0; i < poi.count; i++) {
                            boolean ok = false;
                            for (int counter = 0; counter < 500; counter++) {
                                float radius = (float) Math.sqrt(((random.nextDouble()) / 2 * poi.radiusFactor));
                                float theta = (float) (random.nextDouble() * 2 * Math.PI);
                                float x = (float) (radius * Math.cos(theta));
                                x *= (biome.width * width / 2);
                                x += (biome.startPointX * width);
                                float y = (float) (radius * Math.sin(theta));
                                y *= (biome.height * height / 2);
                                y += (height - (biome.startPointY * height));
                                y += (poi.offsetY * (biome.height * height));
                                x += (poi.offsetX * (biome.width * width));
                                if ((int) x < 0 || (int) y <= 0 || (int) y >= height || (int) x >= width || bi2 != highestBiome(getBiome((int) x, (int) y)))
                                    continue;
                                x *= tile;
                                y *= tile;
                                boolean breakNextLoop = false;
                                for (float[] r : otherPoints) if (contains(r, x, y)) { breakNextLoop = true; break; }
                                if (breakNextLoop) {
                                    boolean foundSolution = false, noSolution = false;
                                    for (int xi = -1; xi < 2 && !foundSolution; xi++)
                                        for (int yi = -1; yi < 2 && !foundSolution; yi++) {
                                            for (float[] r : otherPoints)
                                                if (contains(r, x + xi * tile, y + yi * tile)) { noSolution = true; break; }
                                            if (!noSolution) { foundSolution = true; x = x + xi * tile; y = y + yi * tile; }
                                        }
                                    if (!foundSolution) {
                                        if (counter == 499) {
                                            System.err.println("Can not place POI " + poi.name + "...Rerunning..");
                                            reruns++;
                                            running = true;
                                            placed.clear();
                                            otherPoints.clear();
                                            if (reruns > 200) { System.err.println("giving up"); break here; }
                                            continue here;
                                        }
                                        continue;
                                    }
                                }
                                otherPoints.add(new float[]{x - tile * 4, y - tile * 4, tile * 8, tile * 8});
                                random.nextInt(Integer.SIZE - 1); // PointOfInterest sprite pick
                                placed.add(new Placed(poi.name, poi.getDisplayName(), poi.type, x / tile, y / tile, bi2));
                                ok = true;
                                break;
                            }
                            if (!ok) runDropped.merge(biome.name + ":" + poi.name, 1, Integer::sum);
                        }
                    }
                }
            }
            int unreachable = reach(data, placed, runs == 1);
            totalUnreach += unreachable;
            if (unreachable > 0) runsWithUnreach++;
            totalReruns += reruns;
            maxReruns = Math.max(maxReruns, reruns);
            for (Map.Entry<String, Integer> e : runDropped.entrySet()) dropped.merge(e.getKey(), e.getValue(), Integer::sum);
            if (run == 0 && !out.equals("-")) render(biomes, placed, out);
            if (runs == 1) {
                for (Placed p : placed)
                    if (!p.type().equals("cave") && !p.type().equals("dungeon") || !p.label().equals(p.name()))
                        System.out.printf("  %-26s %-22s %-8s x=%.3f y(north=0)=%.3f  [%s]%n", p.name(), p.label(), p.type(), p.x() / width, 1 - p.y() / height, biomes.get(p.biome()).name);
                System.out.println("POIs placed: " + placed.size());
            }
        }
        System.out.println("runs=" + runs + " reruns total=" + totalReruns + " max=" + maxReruns);
        System.out.println("unreachable POIs total=" + totalUnreach + " runs with unreachable=" + runsWithUnreach + " " + unreachNames);
        System.out.println("dropped (POI could not be placed, summed over runs): " + dropped);
        // land coverage stats
        int[] counts = new int[biomes.size()];
        for (int x = 0; x < width; x++) for (int y = 0; y < height; y++) if (biomeMap[x][y] != 0) counts[highestBiome(biomeMap[x][y])]++;
        StringBuilder sb = new StringBuilder("coverage%: ");
        for (int i = 0; i < biomes.size(); i++) sb.append(biomes.get(i).name).append('=').append(String.format("%.1f", 100.0 * counts[i] / (width * height))).append(' ');
        System.out.println(sb);
    }

    static Map<String, Integer> unreachNames = new TreeMap<>();
    static boolean[][] walk;

    static void line(int x0, int y0, int x1, int y1) { // world tile coords, y up (Bresenham as in World.java)
        int dx = Math.abs(x1 - x0), dy = Math.abs(y1 - y0), sx = x0 < x1 ? 1 : -1, sy = y0 < y1 ? 1 : -1, err = dx - dy;
        for (int i = 0; i < 1000; i++) {
            if (x0 >= 0 && y0 > 0 && x0 < width && y0 <= height) walk[x0][height - y0] = true;
            if (x0 == x1 && y0 == y1) break;
            int e2 = 2 * err;
            if (e2 > -dy) { err -= dy; x0 += sx; } else if (e2 < dx) { err += dx; y0 += sy; }
        }
    }

    static int reach(WorldData data, List<Placed> placed, boolean verbose) {
        walk = new boolean[width][height];
        for (int x = 0; x < width; x++) for (int y = 0; y < height; y++) walk[x][y] = highestBiome(biomeMap[x][y]) != 0;
        List<Placed> towns = new ArrayList<>(), others = new ArrayList<>();
        for (Placed p : placed) (p.type().equals("town") || p.type().equals("capital") ? towns : others).add(p);
        float tile = data.tileSize;
        for (int i = 0; i < towns.size() - 1; i++) { // nearest town within maxRoadDistance (World.java)
            Placed c = towns.get(i); int best = -1; float bd = Float.MAX_VALUE;
            for (int j = 0; j < towns.size(); j++) {
                if (i == j) continue;
                float d = (float) Math.hypot((c.x() - towns.get(j).x()) * tile, (c.y() - towns.get(j).y()) * tile);
                if (d > data.maxRoadDistance) continue;
                if (d < bd) { bd = d; best = j; }
            }
            if (best >= 0) line((int) c.x(), (int) c.y(), (int) towns.get(best).x(), (int) towns.get(best).y());
        }
        for (int i = 0; i < others.size() - 1; i++) {
            Placed c = others.get(i); int best = -1; float bd = Float.MAX_VALUE;
            for (int j = 0; j < towns.size(); j++) {
                float d = (float) Math.hypot(c.x() - towns.get(j).x(), c.y() - towns.get(j).y());
                if (d < bd) { bd = d; best = j; }
            }
            if (best >= 0) line((int) c.x(), (int) c.y(), (int) towns.get(best).x(), (int) towns.get(best).y());
        }
        Placed spawn = placed.stream().filter(p -> p.name().equals("Spawn")).findFirst().orElse(null);
        if (spawn == null) return -1;
        boolean[][] seen = new boolean[width][height];
        ArrayDeque<int[]> q = new ArrayDeque<>();
        int sx = (int) spawn.x(), sy = height - (int) spawn.y();
        q.add(new int[]{sx, sy}); seen[sx][sy] = true;
        while (!q.isEmpty()) {
            int[] p = q.poll();
            int[][] nb = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
            for (int[] d : nb) {
                int nx = p[0] + d[0], ny = p[1] + d[1];
                if (nx < 0 || ny < 0 || nx >= width || ny >= height || seen[nx][ny] || !walk[nx][ny]) continue;
                seen[nx][ny] = true; q.add(new int[]{nx, ny});
            }
        }
        int bad = 0;
        for (Placed p : placed) {
            int px = (int) p.x(), py = height - (int) p.y();
            boolean ok = false;
            for (int dx = -2; dx <= 2 && !ok; dx++) for (int dy = -2; dy <= 2 && !ok; dy++) {
                int x = px + dx, y = py + dy;
                if (x >= 0 && y >= 0 && x < width && y < height && seen[x][y]) ok = true;
            }
            if (!ok) { bad++; unreachNames.merge(p.label(), 1, Integer::sum); if (verbose) System.out.println("  UNREACHABLE " + p.label()); }
        }
        return bad;
    }

    static boolean contains(float[] r, float x, float y) {
        return r[0] <= x && r[0] + r[2] >= x && r[1] <= y && r[1] + r[3] >= y;
    }

    static List<PointOfInterestData> poisOf(BiomeData biome, Array<PointOfInterestData> all) {
        List<PointOfInterestData> l = new ArrayList<>();
        if (biome.pointsOfInterest == null) return l;
        for (PointOfInterestData d : all)
            for (String n : biome.pointsOfInterest)
                if (d.name.equals(n)) { l.add(d); break; }
        List<PointOfInterestData> caves = new ArrayList<>();
        for (PointOfInterestData d : l) if (d.isDungeon()) caves.add(d);
        l.removeAll(caves);
        l.addAll(caves);
        return l;
    }

    static void render(List<BiomeData> biomes, List<Placed> placed, String out) throws Exception {
        int S = 1000;
        BufferedImage img = new BufferedImage(S, S, BufferedImage.TYPE_INT_RGB);
        Map<String, Color> tc = Map.of("Base", new Color(0x2a6fb5), "Colorless", new Color(0x8a8a8a), "White", new Color(0xe3cf9a),
                "Green", new Color(0x5dbb48), "Black", new Color(0x2b2b33), "Red", new Color(0xb5653a), "Blue", new Color(0xdfeef5));
        for (int px = 0; px < S; px++)
            for (int py = 0; py < S; py++) {
                int x = px * width / S, y = py * height / S;
                long b = biomeMap[x][y];
                Color c = Color.MAGENTA;
                if (b != 0) {
                    BiomeData bd = biomes.get(highestBiome(b));
                    c = tc.getOrDefault(bd.tilesetName, Color.MAGENTA);
                    // tint slightly by biome colour to tell biomes apart
                    Color bc = Color.decode("#" + bd.color);
                    c = new Color((c.getRed() * 3 + bc.getRed()) / 4, (c.getGreen() * 3 + bc.getGreen()) / 4, (c.getBlue() * 3 + bc.getBlue()) / 4);
                }
                img.setRGB(px, py, c.getRGB());
            }
        // biome borders
        for (int px = 1; px < S; px++)
            for (int py = 1; py < S; py++) {
                int x = px * width / S, y = py * height / S, x0 = (px - 1) * width / S, y0 = (py - 1) * height / S;
                if (highestBiome(biomeMap[x][y]) != highestBiome(biomeMap[x0][y]) || highestBiome(biomeMap[x][y]) != highestBiome(biomeMap[x][y0]))
                    img.setRGB(px, py, 0x202020);
            }
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
        for (Placed p : placed) {
            int px = (int) (p.x() / width * S), py = (int) ((height - p.y()) / height * S);
            boolean town = p.type().equals("town") || p.type().equals("capital");
            g.setColor(town ? Color.WHITE : (p.type().equals("cave") ? Color.ORANGE : Color.RED));
            int r = p.type().equals("capital") ? 5 : 3;
            g.fillOval(px - r, py - r, 2 * r, 2 * r);
            g.setColor(Color.BLACK);
            g.drawOval(px - r, py - r, 2 * r, 2 * r);
            if (!p.label().equals(p.name()) && !p.label().equals("Cave")) {
                g.setColor(Color.BLACK);
                g.drawString(p.label(), px + 5, py + 4);
                g.setColor(town ? Color.WHITE : Color.YELLOW);
                g.drawString(p.label(), px + 4, py + 3);
            }
        }
        // biome names
        g.setFont(new Font(Font.SANS_SERIF, Font.ITALIC, 10));
        for (BiomeData b : biomes) {
            if (b.width == 1) continue;
            g.setColor(new Color(0, 0, 0, 160));
            g.drawString(b.name, (int) (b.startPointX * S) - 15, (int) (b.startPointY * S));
        }
        g.dispose();
        ImageIO.write(img, "png", new File(out));
    }
}
