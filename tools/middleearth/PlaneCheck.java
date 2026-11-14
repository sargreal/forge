import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Json;
import forge.GuiMobile;
import forge.adventure.data.*;
import forge.adventure.util.CardUtil;
import forge.adventure.util.Config;
import forge.adventure.util.Paths;
import forge.card.CardDb;
import forge.card.CardEdition;
import forge.card.CardRarity;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.gui.GuiBase;
import forge.item.PaperCard;
import forge.model.FModel;

import java.util.*;

/**
 * Headless consistency check for an adventure plane (default: MiddleEarth).
 *
 * Run via tools/middleearth/check.sh (needs the mobile-dev fat jar, see game.sh build).
 * Runs with cwd = forge-gui so that Config resolves ./res/adventure/<plane>.
 *
 * Checks:
 *  1. Every plane data file parses into Forge's data classes (unknown fields = error).
 *  2. Every enemy / POI referenced by a biome exists.
 *  3. Card pool: starter decks and enemy decks are measured against the plane's
 *     allowedEditions. A card with no printing in an allowed edition is "off-pool".
 *  4. Card resolution: CardUtil.getFullCardPool / ensureAllowedEdition only yield allowed printings.
 *  5. Reprint editions (allowed editions whose entries carry ${"artSet","artNum"}, e.g. MEAR):
 *     every entry has a card script, its art source printing exists, and it isn't already in
 *     another allowed edition. Also lists tokens that no allowed edition prints (no token art).
 *     Flavor names (${"flavorName"}) are unique, are no real card name, and the printing still resolves by
 *     real name, flavor name and image key; [tokens] art sources (${"artSet","artNum"}) exist.
 *
 * Exit code: 1 if there are ERRORs (parse failures, missing references, wrong printings),
 * 0 otherwise. Off-pool cards are reported as WARN (content work, see docs/MiddleEarth/PLAN.md).
 */
public class PlaneCheck {
    static int errors = 0, warnings = 0;
    static boolean verbose = false;

    static void error(String m) { errors++; System.out.println("ERROR " + m); }
    static void warn(String m) { warnings++; System.out.println("WARN  " + m); }
    static void info(String m) { System.out.println("      " + m); }

    public static void main(String[] args) throws Exception {
        for (String a : args) if (a.equals("-v")) verbose = true;
        GuiBase.setInterface(new GuiMobile("./"));
        FModel.initialize(null, null);

        Config cfg = Config.instance();
        ConfigData cd = cfg.getConfigData();
        System.out.println("== Plane: " + cfg.getPlane() + " (" + cfg.getPlanePath(cfg.getPlane()) + ")");
        Set<String> allowed = cd.allowedEditions == null ? Set.of() : new LinkedHashSet<>(Arrays.asList(cd.allowedEditions));
        System.out.println("   allowedEditions: " + allowed);
        for (String e : allowed)
            if (FModel.getMagicDb().getEditions().get(e) == null) error("allowedEditions: unknown edition code " + e);

        Json json = new Json();
        // ---------- 1. parse ----------
        System.out.println("== Parsing data files");
        strictParse(json, "config.json", ConfigData.class, false);
        WorldData world = strictParse(json, "world/world.json", WorldData.class, false);
        Array<EnemyData> enemies = strictParse(json, Paths.ENEMIES, EnemyData.class, true);
        Array<PointOfInterestData> pois = strictParse(json, Paths.POINTS_OF_INTEREST, PointOfInterestData.class, true);
        strictParse(json, Paths.SHOPS, ShopData.class, true);
        strictParse(json, Paths.ITEMS, ItemData.class, true);
        strictParse(json, Paths.QUESTS, AdventureQuestData.class, true);
        List<BiomeData> biomes = new ArrayList<>();
        if (world != null && world.biomesNames != null)
            for (String b : world.biomesNames) {
                BiomeData bd = strictParse(json, b, BiomeData.class, false);
                if (bd != null) biomes.add(bd);
            }

        // ---------- 2. references ----------
        System.out.println("== Biome references");
        Map<String, EnemyData> enemyByName = new HashMap<>();
        // BiomeData.getEnemyList() matches on getName() (nameOverride if set, else name)
        if (enemies != null) for (EnemyData e : enemies) enemyByName.put(e.getName(), e);
        Set<String> poiNames = new HashSet<>();
        if (pois != null) for (PointOfInterestData p : pois) poiNames.add(p.name);
        // The game silently skips unknown names, so these are content gaps rather than crashes.
        Set<String> usedEnemies = new TreeSet<>();
        Set<String> seenBiomes = new HashSet<>();
        for (BiomeData b : biomes) {
            if (!seenBiomes.add(b.name)) { error("biome " + b.name + " is listed more than once in world.json"); continue; }
            if (b.enemies != null) for (String e : b.enemies) {
                if (!enemyByName.containsKey(e)) warn("biome " + b.name + ": unknown enemy '" + e + "' (ignored by the game)");
                else usedEnemies.add(e);
            }
            if (b.pointsOfInterest != null) for (String p : b.pointsOfInterest)
                if (!poiNames.contains(p)) warn("biome " + b.name + ": unknown point of interest '" + p + "' (not placed)");
        }
        info(biomes.size() + " biomes, " + usedEnemies.size() + " distinct enemies used");

        if (allowed.isEmpty()) {
            warn("no allowedEditions configured - skipping card pool checks");
            finish();
            return;
        }

        // ---------- 3. card pool ----------
        System.out.println("== Starter decks vs allowedEditions");
        Set<String> starterPaths = new TreeSet<>();
        if (cd.difficulties != null) for (DifficultyData d : cd.difficulties) {
            for (ObjectMapValues m : List.of(new ObjectMapValues(d.starterDecks), new ObjectMapValues(d.constructedStarterDecks), new ObjectMapValues(d.pileDecks)))
                starterPaths.addAll(m.values);
        }
        for (String p : starterPaths) reportDeck("starter " + p, loadDeck(p), allowed);

        System.out.println("== Enemy decks (enemies used by the plane's biomes)");
        int offPoolEnemies = 0;
        for (String name : usedEnemies) {
            EnemyData e = enemyByName.get(name);
            if (e.deck == null) continue;
            for (String p : e.deck) {
                if (p.endsWith(".json") && p.contains("generated")) continue; // generated decks are built from the pool
                if (reportDeck("enemy " + name + " <" + p + ">", loadDeck(p), allowed) > 0) offPoolEnemies++;
            }
        }
        info(offPoolEnemies + " enemy decks contain off-pool cards");

        // ---------- 4. resolution ----------
        System.out.println("== Card resolution respects allowedEditions");
        Collection<PaperCard> pool = CardUtil.getFullCardPool(false);
        long bad = pool.stream().filter(c -> !allowed.contains(c.getEdition())).count();
        if (bad > 0) error("getFullCardPool(false) returned " + bad + " cards from non-allowed editions");
        info("unique card pool size: " + pool.size());
        Map<String, Long> perEdition = new TreeMap<>();
        for (PaperCard c : CardUtil.getFullCardPool(true)) perEdition.merge(c.getEdition(), 1L, Long::sum);
        info("printings per edition: " + perEdition);
        // reprints in LTC must resolve to the LTC printing, not the original set (issue #1)
        int checked = 0;
        for (PaperCard c : pool) {
            for (PaperCard p : FModel.getMagicDb().getCommonCards().getAllCards(c.getName())) {
                if (allowed.contains(p.getEdition())) continue;
                if (!p.getRules().getName().equals(c.getRules().getName())) continue; // face-name collision, different card
                PaperCard r = CardUtil.ensureAllowedEdition(p);
                checked++;
                if (!allowed.contains(r.getEdition()))
                    error("ensureAllowedEdition(" + p + ") -> " + r.getEdition());
                break;
            }
        }
        info("checked " + checked + " reprints for printing replacement");

        // ---------- 5. reprint editions ----------
        System.out.println("== Reprint editions (art sources)");
        checkReprintEditions(allowed);
        finish();
    }

    /** Editions in the pool whose entries name an art source: the fork's custom reprint set(s). */
    static void checkReprintEditions(Set<String> allowed) {
        CardDb db = FModel.getMagicDb().getCommonCards();
        for (String code : allowed) {
            CardEdition ed = FModel.getMagicDb().getEditions().get(code);
            if (ed == null) continue;
            List<CardEdition.EditionEntry> entries = ed.getAllCardsInSet();
            if (entries.stream().noneMatch(e -> e.extraParams() != null && e.extraParams().containsKey("artset")))
                continue;
            Map<CardRarity, Integer> rarities = new TreeMap<>();
            Set<String> names = new HashSet<>();
            Set<String> noTokenArt = new TreeSet<>();
            int artOk = 0;
            for (CardEdition.EditionEntry e : entries) {
                String label = code + " #" + e.collectorNumber() + " " + e.name();
                rarities.merge(e.rarity(), 1, Integer::sum);
                if (!names.add(e.name())) error(label + ": listed more than once");
                PaperCard pc = db.getCard(e.name(), code, e.collectorNumber());
                if (pc == null || !pc.getEdition().equals(code)) {
                    error(label + ": not in the card DB (no card script?)");
                    continue;
                }
                for (PaperCard p : db.getAllCards(e.name()))
                    if (!p.getEdition().equals(code) && allowed.contains(p.getEdition())) {
                        warn(label + ": already in allowed edition " + p.getEdition());
                        break;
                    }
                Map<String, String> xp = e.extraParams();
                String set = xp == null ? null : xp.get("artset");
                String num = xp == null ? null : xp.get("artnum");
                if (set == null || num == null) {
                    warn(label + ": no art source (artSet/artNum)");
                } else {
                    PaperCard src = db.getCard(e.name(), set, num);
                    if (src == null || !src.getEdition().equalsIgnoreCase(set) || !src.getCollectorNumber().equalsIgnoreCase(num)) {
                        error(label + ": art source " + set + " " + num + " is not a printing of this card");
                    } else {
                        artOk++;
                        if (e.artistName() != null && !e.artistName().equals(src.getArtist()))
                            warn(label + ": artist '" + e.artistName() + "' differs from art source '" + src.getArtist() + "'");
                        if (verbose) info(label + " -> art " + set + " " + num);
                    }
                }
                for (String t : pc.getRules().getTokens())
                    if (FModel.getMagicDb().getAllTokens().getTokenFromEditions(t, x -> allowed.contains(x.getCode()), null) == null)
                        noTokenArt.add(t);
            }
            info(code + ": " + entries.size() + " cards " + rarities + ", " + artOk + " art sources resolved");
            checkFlavorNames(ed, entries);
            checkTokenArtSources(ed);
            if (!noTokenArt.isEmpty())
                warn(code + ": tokens without a printing in the allowed editions (generic image): " + noTokenArt);
            else
                info(code + ": 0 tokens without art (every token its cards make has a printing in the allowed editions)");
        }
    }

    /**
     * WP2b: flavor names (${"flavorName": ...}) are unique, don't collide with a real card name or another
     * edition's flavor name, and the printing still resolves by its real name, by its flavor name and by its
     * image key, with the art source intact.
     */
    static void checkFlavorNames(CardEdition ed, List<CardEdition.EditionEntry> entries) {
        CardDb db = FModel.getMagicDb().getCommonCards();
        String code = ed.getCode();
        Set<String> realNames = new HashSet<>();
        for (PaperCard u : db.getUniqueCards()) for (forge.card.ICardFace f : u.getRules().getAllFaces()) if (f != null) realNames.add(f.getName().toLowerCase());
        Map<String, String> otherFlavors = new HashMap<>();
        for (CardEdition other : FModel.getMagicDb().getEditions()) {
            if (other == ed) continue;
            for (CardEdition.EditionEntry e : other.getAllCardsInSet())
                if (e.getFlavorName() != null)
                    for (String part : e.getFlavorName().split("\\s*//\\s*")) otherFlavors.put(part.toLowerCase(), other.getCode() + " " + e.name());
        }
        Map<String, String> seen = new HashMap<>();
        int ok = 0, n = 0;
        for (CardEdition.EditionEntry e : entries) {
            String fl = e.getFlavorName();
            if (fl == null) continue;
            n++;
            String label = code + " #" + e.collectorNumber() + " " + e.name() + " \"" + fl + "\"";
            boolean good = true;
            for (String part : fl.split("\\s*//\\s*")) {
                String k = part.toLowerCase();
                if (seen.containsKey(k)) { error(label + ": flavor name also used by " + seen.get(k)); good = false; }
                seen.put(k, e.name());
                if (realNames.contains(k)) { error(label + ": flavor name is the name of a real card"); good = false; }
                if (otherFlavors.containsKey(k)) { error(label + ": flavor name already used by " + otherFlavors.get(k)); good = false; }
            }
            PaperCard byReal = db.getCard(e.name(), code, e.collectorNumber());
            if (byReal == null || !byReal.getName().equals(e.name()) || !byReal.hasFlavorName()) {
                error(label + ": real-name lookup " + e.name() + "|" + code + " gives " + byReal); continue;
            }
            String front = fl.split("\\s*//\\s*")[0];
            // CardFace.assignMissingFieldsToVariant rewrites the oracle text: real name -> flavor name, then the real
            // name's "nickname" (first word) -> the flavor's first word. A flavor name containing that word gets mangled.
            String realNick = forge.util.Lang.getInstance().getNickName(e.name().split(" // ")[0]);
            if (!realNick.equals(forge.util.Lang.getInstance().getNickName(front)) && java.util.regex.Pattern.compile("\\b" + java.util.regex.Pattern.quote(realNick) + "\\b").matcher(front).find()) {
                error(label + ": flavor name contains '" + realNick + "', which the oracle-text rewrite would replace"); good = false;
            }
            if (!byReal.getDisplayName().equals(front)) { error(label + ": display name is '" + byReal.getDisplayName() + "'"); good = false; }
            PaperCard byFlavor = db.getCard(front);
            if (byFlavor == null || !byFlavor.equals(byReal)) { error(label + ": flavor-name lookup gives " + byFlavor); good = false; }
            PaperCard byKey = forge.util.ImageUtil.getPaperCardFromImageKey(byReal.getImageKey(false));
            if (byKey == null || !byKey.equals(byReal)) { error(label + ": image key " + byReal.getImageKey(false) + " gives " + byKey); good = false; }
            if (e.hasArtSource() && forge.util.ImageFetcher.getArtSourcePrinting(byReal) == byReal) { error(label + ": art source lost"); good = false; }
            if (verbose) info(label + " -> file " + byReal.getCardImageKey());
            if (good) ok++;
        }
        info(code + ": " + n + " flavor names, " + ok + " unique and resolving (real name, flavor name, image key, art source)");
    }

    /** WP2b: [tokens] entries with ${"artSet","artNum"} must point at the same token script in that set. */
    static void checkTokenArtSources(CardEdition ed) {
        int n = 0, ok = 0;
        for (Map.Entry<String, CardEdition.EditionEntry> en : ed.getTokens().entries()) {
            CardEdition.EditionEntry t = en.getValue();
            n++;
            String label = ed.getCode() + " token #" + t.collectorNumber() + " " + en.getKey();
            if (!FModel.getMagicDb().getAllTokens().containsRule(en.getKey())) { error(label + ": no token script"); continue; }
            if (!t.hasArtSource()) { warn(label + ": no art source (artSet/artNum)"); continue; }
            org.apache.commons.lang3.tuple.Pair<CardEdition, String> src = forge.util.ImageFetcher.getTokenArtSource(ed, en.getKey(), t.collectorNumber());
            if (src.getLeft() == ed) { error(label + ": art source " + t.getArtSourceSet() + " " + t.getArtSourceNumber() + " does not print this token"); continue; }
            ok++;
            if (verbose) info(label + " -> art " + src.getLeft().getCode() + " token " + src.getRight() + " (" + src.getLeft().getTokensCode() + "/" + src.getRight() + ")");
        }
        if (n > 0) info(ed.getCode() + ": " + n + " tokens, " + ok + " token art sources resolved");
    }

    static void finish() {
        System.out.println("== Result: " + errors + " error(s), " + warnings + " warning(s)");
        System.exit(errors > 0 ? 1 : 0);
    }

    /** Collects the values of a libGDX ObjectMap<String,String> (may be null). */
    static class ObjectMapValues {
        final List<String> values = new ArrayList<>();
        ObjectMapValues(com.badlogic.gdx.utils.ObjectMap<String, String> m) {
            if (m != null) for (String v : m.values()) values.add(v);
        }
    }

    static Deck loadDeck(String path) {
        try {
            return CardUtil.getDeck(path, false, false, "", false, false);
        } catch (Exception ex) {
            error("cannot load deck " + path + ": " + ex);
            return null;
        }
    }

    /** @return number of off-pool card copies */
    static int reportDeck(String label, Deck deck, Set<String> allowed) {
        if (deck == null) return 0;
        Map<String, Integer> off = new TreeMap<>();
        int total = 0;
        for (DeckSection s : new DeckSection[]{DeckSection.Main, DeckSection.Sideboard, DeckSection.Commander}) {
            if (!deck.has(s)) continue;
            for (Map.Entry<PaperCard, Integer> en : deck.get(s)) {
                PaperCard c = en.getKey();
                total += en.getValue();
                if (c.getRules().getType().isBasicLand()) continue;
                boolean inPool = false;
                for (PaperCard p : FModel.getMagicDb().getCommonCards().getAllCards(c.getName()))
                    if (allowed.contains(p.getEdition())) { inPool = true; break; }
                if (!inPool) off.merge(c.getName(), en.getValue(), Integer::sum);
            }
        }
        int offCount = off.values().stream().mapToInt(Integer::intValue).sum();
        if (offCount > 0) {
            warn(label + ": " + offCount + "/" + total + " cards off-pool"
                    + (verbose ? " " + off : ""));
        } else if (verbose) info(label + ": OK (" + total + " cards)");
        return offCount;
    }

    /**
     * Parse a plane file the way the game does (Config.getFile falls back to common/),
     * but fail on unknown fields so typos in the JSON are caught.
     */
    @SuppressWarnings("unchecked")
    static <T> T strictParse(Json json, String path, Class<?> type, boolean isArray) {
        FileHandle h = Config.instance().getFile(path);
        if (h == null || !h.exists()) { error(path + ": not found"); return null; }
        String src = h.path().contains("/common/") ? "common" : "plane";
        try {
            json.setIgnoreUnknownFields(false);
            Object o = isArray ? json.fromJson(Array.class, type, h) : json.fromJson(type, h);
            int n = isArray ? ((Array<?>) o).size : 1;
            info(String.format("%-36s %-6s %s", path, src, isArray ? n + " entries" : "ok"));
            return (T) o;
        } catch (Exception ex) {
            Throwable t = ex;
            while (t.getCause() != null) t = t.getCause();
            error(path + " (" + src + "): " + t.getMessage());
            return null;
        }
    }
}
