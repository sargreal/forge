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
    // --all-enemies: also check enemies no biome uses yet whose decks are plane-local (decks/enemy/...), e.g. a new fragment.
    static boolean allEnemies = false;

    static void error(String m) { errors++; System.out.println("ERROR " + m); }
    static void warn(String m) { warnings++; System.out.println("WARN  " + m); }
    static void info(String m) { System.out.println("      " + m); }

    public static void main(String[] args) throws Exception {
        for (String a : args) if (a.equals("-v")) verbose = true;
        for (String a : args) if (a.equals("--all-enemies")) allEnemies = true;
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
        if (allEnemies) {
            int extra = 0;
            for (EnemyData e : enemyByName.values())
                if (e.deck != null && e.deck.length > 0 && Arrays.stream(e.deck).allMatch(d -> d.startsWith("decks/enemy/"))
                        && usedEnemies.add(e.getName())) extra++;
            info("--all-enemies: " + extra + " more enemies with plane-local decks checked");
        }

        // ---------- 2b. enemies referenced by POI maps, arenas and quests ----------
        System.out.println("== POI map / arena / quest enemy references");
        Set<String> mapEnemies = checkMapEnemyReferences(pois, enemies);
        checkQuestEnemyTags(json, enemies);

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

        System.out.println("== Enemy decks (enemies referenced by POI maps and arenas, not used by biomes)");
        int offPoolMap = 0;
        for (String name : mapEnemies) {
            EnemyData e = enemyByRawName(enemies, name);
            if (e == null || e.deck == null || usedEnemies.contains(e.getName())) continue;
            for (String p : e.deck) {
                if (p.endsWith(".json")) continue; // generated from the pool
                if (reportDeck("map enemy " + name + " <" + p + ">", loadDeck(p), allowed) > 0) offPoolMap++;
            }
        }
        info(offPoolMap + " map-referenced enemy decks contain off-pool cards");

        // Decks that live in the plane itself are hand-made for it: off-pool cards there are errors.
        System.out.println("== Plane-local enemy decks (must be fully in-pool)");
        int planeDecks = 0;
        Set<String> seenPlaneDecks = new HashSet<>();
        if (enemies != null) for (EnemyData e : enemies) {
            if (e.deck == null) continue;
            for (String p : e.deck) {
                FileHandle h = Config.instance().getFile(p);
                if (h == null || !h.exists() || h.path().contains("/common/") || p.endsWith(".json") || !seenPlaneDecks.add(p)) continue;
                planeDecks++;
                int before = warnings;
                int off = reportDeck("plane deck " + p + " (" + e.name + ")", loadDeck(p), allowed);
                if (off > 0) { warnings = before; error("plane deck " + p + " has " + off + " off-pool card(s)"); }
            }
        }
        info(planeDecks + " plane-local enemy decks checked");

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

        // ---------- 6. shops, items, heroes ----------
        checkShopsItemsHeroes(json, cd, pois, enemyByName, usedEnemies);
        finish();
    }

    /** WorldData.getEnemy: exact match on name first, then on getName() (nameOverride). */
    static EnemyData enemyByRawName(Array<EnemyData> enemies, String n) {
        if (enemies == null) return null;
        for (EnemyData e : enemies) if (n.equals(e.name)) return e;
        for (EnemyData e : enemies) if (n.equals(e.getName())) return e;
        return null;
    }

    /**
     * Walks every POI map (.tmx) and the maps reachable from it through "teleport" properties, and checks
     * that every "enemy" object property and every arena "enemyPool" entry names an existing enemy
     * (MapStage / ArenaScene look them up with WorldData.getEnemy). A missing map enemy is an ERROR, except
     * when only surrounding whitespace differs from an existing name (upstream map typos such as "Goblin "):
     * the game then picks a random biome enemy, so that is a WARN. Arena pools only need one valid name.
     * @return the referenced names that exist
     */
    static Set<String> checkMapEnemyReferences(Array<PointOfInterestData> pois, Array<EnemyData> enemies) {
        Set<String> found = new TreeSet<>();
        if (pois == null) return found;
        Map<String, Set<String>> missing = new TreeMap<>();
        Set<String> visited = new HashSet<>();
        int maps = 0, refs = 0;
        for (PointOfInterestData p : pois) {
            Deque<String> todo = new ArrayDeque<>();
            todo.add(p.map);
            while (!todo.isEmpty()) {
                String path = todo.poll();
                if (path == null || path.isEmpty()) continue;
                FileHandle h = Config.instance().getFile(path);
                if (h == null || !h.exists()) { error("POI " + p.name + ": map not found: " + path); continue; }
                String key;
                try { key = h.file().getCanonicalPath(); } catch (Exception ex) { key = h.path(); }
                if (!visited.add(key)) continue;
                maps++;
                org.w3c.dom.Document doc;
                try {
                    doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(h.file());
                } catch (Exception ex) { error("map " + path + ": cannot parse: " + ex.getMessage()); continue; }
                org.w3c.dom.NodeList props = doc.getElementsByTagName("property");
                for (int i = 0; i < props.getLength(); i++) {
                    org.w3c.dom.Element pr = (org.w3c.dom.Element) props.item(i);
                    String name = pr.getAttribute("name");
                    String value = pr.hasAttribute("value") ? pr.getAttribute("value") : pr.getTextContent();
                    if (value == null || value.isEmpty()) continue;
                    String where = h.name() + " (" + p.name + ")";
                    switch (name) {
                        case "enemy" -> {
                            refs++;
                            if (enemyByRawName(enemies, value) != null) found.add(value);
                            else missing.computeIfAbsent(value, k -> new TreeSet<>()).add(where);
                        }
                        case "arena" -> {
                            ArenaData a;
                            try { a = new Json().fromJson(ArenaData.class, value); }
                            catch (Exception ex) { error("arena in " + where + ": cannot parse: " + ex.getMessage()); continue; }
                            int ok = 0;
                            if (a.enemyPool != null) for (String n : a.enemyPool) {
                                refs++;
                                if (enemyByRawName(enemies, n) != null) { ok++; found.add(n); }
                                else warn("arena in " + where + ": unknown enemy '" + n + "' (skipped by the arena)");
                            }
                            if (ok == 0) error("arena in " + where + ": no valid enemy in enemyPool");
                        }
                        case "teleport" -> todo.add(value);
                        default -> { }
                    }
                }
            }
        }
        for (Map.Entry<String, Set<String>> m : missing.entrySet()) {
            String n = m.getKey();
            if (!n.trim().equals(n) && enemyByRawName(enemies, n.trim()) != null)
                warn("map enemy '" + n + "' not found (whitespace typo of '" + n.trim() + "'; the game uses a random biome enemy) in " + m.getValue());
            else
                error("map enemy '" + n + "' does not exist in enemies.json, referenced in " + m.getValue());
        }
        info(maps + " maps, " + refs + " enemy references, " + found.size() + " distinct enemies found");
        return found;
    }

    /** Every quest stage that targets enemies by tag must match at least one enemy. */
    @SuppressWarnings("unchecked")
    static void checkQuestEnemyTags(Json json, Array<EnemyData> enemies) {
        Array<AdventureQuestData> quests;
        try { quests = json.fromJson(Array.class, AdventureQuestData.class, Config.instance().getFile(Paths.QUESTS)); }
        catch (Exception ex) { return; } // parse errors are reported in section 1
        if (quests == null || enemies == null) return;
        int stages = 0;
        for (AdventureQuestData q : quests) {
            if (q.stages == null) continue;
            for (AdventureQuestStage s : q.stages) {
                if (s.objective == null || (s.enemyTags.isEmpty() && s.enemyExcludeTags.isEmpty())) continue;
                stages++;
                boolean any = false;
                for (EnemyData e : enemies) {
                    List<String> t = Arrays.asList(e.questTags);
                    if (t.containsAll(s.enemyTags) && Collections.disjoint(t, s.enemyExcludeTags)) { any = true; break; }
                }
                if (!any) error("quest " + q.getID() + " stage '" + s.name + "': no enemy has tags " + s.enemyTags + " without " + s.enemyExcludeTags);
            }
        }
        info(stages + " quest stages target enemies by tag, all matched");
    }

    // =====================================================================================
    // 6. Shops, items and heroes
    // =====================================================================================

    /** Card rewards whose pool is smaller than this are reported (the shop repeats cards a lot). */
    static final int MIN_SHOP_POOL = 6;
    /** Item names the engine looks up by name (EventScene challenge coins, GameHUD key display). */
    static final List<String> ENGINE_ITEMS = List.of("Challenge Coin", "Silver Challenge Coin", "Bronze Challenge Coin",
            "Red Key", "Green Key", "Blue Key", "Black Key", "White Key", "Strange Key");

    /** What the POI maps of the plane reference: shop names (from shop / Rotating objects) and item names. */
    static class MapRefs {
        final Map<String, Set<String>> shops = new TreeMap<>();   // shop name -> maps
        final Map<String, Set<String>> items = new TreeMap<>();   // item name -> maps
        final Set<String> maps = new TreeSet<>();
    }

    static void checkShopsItemsHeroes(Json json, ConfigData cd, Array<PointOfInterestData> pois,
                                      Map<String, EnemyData> enemyByName, Set<String> usedEnemies) {
        System.out.println("== Shops (names requested by the plane's town and dungeon maps)");
        MapRefs refs = scanPoiMaps(pois);
        info(refs.maps.size() + " maps reachable from the plane's POIs, " + refs.shops.size() + " distinct shop names requested");

        json.setIgnoreUnknownFields(true);
        Array<ShopData> shops = json.fromJson(Array.class, ShopData.class, Config.instance().getFile(Paths.SHOPS));
        Array<ItemData> items = json.fromJson(Array.class, ItemData.class, Config.instance().getFile(Paths.ITEMS));
        Map<String, ItemData> itemByName = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (ItemData i : items) {
            if (itemByName.put(i.name, i) != null) error("items: '" + i.name + "' is defined more than once");
        }
        Map<String, ShopData> shopByName = new HashMap<>();
        for (ShopData s : shops) {
            if (shopByName.put(s.name, s) != null) error("shops: '" + s.name + "' is defined more than once");
        }
        for (Map.Entry<String, Set<String>> e : refs.shops.entrySet())
            if (!shopByName.containsKey(e.getKey()))
                error("shop '" + e.getKey() + "' requested by " + e.getValue() + " is not in " + Paths.SHOPS + " (the building stays empty)");

        Set<String> signs = atlasRegions("maps/tileset/buildings.atlas");
        Collection<PaperCard> pool = cardPool(cd);
        int cardShops = 0, totalPool = 0;
        for (ShopData s : shops) {
            if (!signs.isEmpty()) {
                if (s.sprite != null && !signs.contains(s.sprite)) error("shop " + s.name + ": sign sprite '" + s.sprite + "' not in buildings.atlas");
                if (s.overlaySprite != null && !s.overlaySprite.isEmpty() && !signs.contains(s.overlaySprite))
                    error("shop " + s.name + ": overlay sprite '" + s.overlaySprite + "' not in buildings.atlas");
            }
            if (s.rewards == null || s.rewards.isEmpty()) { error("shop " + s.name + ": no rewards"); continue; }
            Set<PaperCard> shopPool = new HashSet<>();
            boolean hasCards = false;
            StringBuilder parts = new StringBuilder();
            for (RewardData r : s.rewards) {
                String type = r.type == null || r.type.isEmpty() ? "card" : r.type;
                switch (type) {
                    case "card", "randomCard", "Union" -> {
                        hasCards = true;
                        List<PaperCard> p = rewardPool(r, pool, "shop " + s.name);
                        shopPool.addAll(p);
                        parts.append(' ').append(r.count).append('x').append(p.size());
                        if (p.isEmpty()) error("shop " + s.name + " (" + s.description + "): a card reward matches no card in the pool");
                        else if (r.cardName == null && p.size() < Math.min(MIN_SHOP_POOL, r.count) )
                            warn("shop " + s.name + ": a reward draws " + r.count + " cards from only " + p.size());
                    }
                    case "item" -> {
                        List<String> names = new ArrayList<>();
                        if (r.itemName != null) names.add(r.itemName);
                        if (r.itemNames != null) names.addAll(Arrays.asList(r.itemNames));
                        for (String n : names) if (!itemByName.containsKey(n)) error("shop " + s.name + ": unknown item '" + n + "'");
                    }
                    case "cardPackShop" -> {
                        if (r.colors != null && r.colors.length > 0)
                            warn("shop " + s.name + ": colour booster packs ignore allowedEditions (cards from any set)");
                        else if (r.editions == null || r.editions.length == 0)
                            warn("shop " + s.name + ": booster packs without editions pick any set");
                        else for (String ed : r.editions) {
                            java.io.PrintStream out = System.out;
                            try {
                                System.setOut(new java.io.PrintStream(java.io.OutputStream.nullOutputStream())); // booster sheet parsing is chatty
                                if (forge.adventure.util.AdventureOverrides.instance().getBoosterTemplate(ed) == null)
                                    error("shop " + s.name + ": edition " + ed + " has no booster template");
                            } catch (Exception ex) {
                                error("shop " + s.name + ": booster for " + ed + " fails: " + ex);
                            } finally {
                                System.setOut(out);
                            }
                        }
                    }
                    case "landSketchbookShop" -> {
                        if (items.select(i -> i.name.contains("Landscape Sketchbook") && !i.questItem).iterator().hasNext() == false)
                            error("shop " + s.name + ": landSketchbookShop but the plane has no Landscape Sketchbook items");
                    }
                    case "life", "gold", "shards", "mana" -> { }
                    default -> warn("shop " + s.name + ": reward type '" + type + "' not checked");
                }
            }
            if (hasCards) {
                cardShops++;
                totalPool += shopPool.size();
                if (verbose) info(String.format("%-22s %-36s pool %4d  (%s )", s.name, s.description, shopPool.size(), parts.toString().trim()));
            }
        }
        info(shops.size + " shops, " + cardShops + " sell cards, average pool " + (cardShops == 0 ? 0 : totalPool / cardShops) + " cards per shop (-v lists them)");

        System.out.println("== Items");
        Map<String, Set<String>> wanted = new TreeMap<>();
        for (String n : ENGINE_ITEMS) wanted.computeIfAbsent(n, k -> new TreeSet<>()).add("engine");
        if (cd.difficulties != null) for (DifficultyData d : cd.difficulties)
            if (d.startItems != null) for (String n : d.startItems) wanted.computeIfAbsent(n, k -> new TreeSet<>()).add("config startItems (" + d.name + ")");
        for (Map.Entry<String, Set<String>> e : refs.items.entrySet()) wanted.computeIfAbsent(e.getKey(), k -> new TreeSet<>()).addAll(e.getValue());
        for (String n : textItemRefs(Config.instance().getFile(Paths.QUESTS))) wanted.computeIfAbsent(n, k -> new TreeSet<>()).add("quests.json");
        for (String en : usedEnemies) {
            EnemyData e = enemyByName.get(en);
            if (e.equipment != null) for (String n : e.equipment) wanted.computeIfAbsent(n, k -> new TreeSet<>()).add("enemy " + en + " equipment");
            if (e.rewards != null) for (RewardData r : e.rewards) {
                if (!"item".equals(r.type)) continue;
                if (r.itemName != null) wanted.computeIfAbsent(r.itemName, k -> new TreeSet<>()).add("enemy " + en + " reward");
                if (r.itemNames != null) for (String n : r.itemNames) wanted.computeIfAbsent(n, k -> new TreeSet<>()).add("enemy " + en + " reward");
            }
        }
        for (Map.Entry<String, Set<String>> e : wanted.entrySet())
            if (!itemByName.containsKey(e.getKey())) error("item '" + e.getKey() + "' used by " + e.getValue() + " is not in " + Paths.ITEMS);
        info(wanted.size() + " item names referenced (engine, config, maps, quests, enemies), all checked");

        Set<String> icons = atlasRegions("sprites/items.atlas");
        int effects = 0;
        for (ItemData i : items) {
            if (!icons.isEmpty() && i.iconName != null && !icons.contains(i.iconName))
                error("item " + i.name + ": icon '" + i.iconName + "' not in sprites/items.atlas");
            effects += checkEffectCards("item " + i.name, i.effect);
            if (i.commandOnUse != null && i.commandOnUse.startsWith("teleport to poi")) {
                String poi = i.commandOnUse.substring("teleport to poi".length()).trim().replace("\"", "");
                boolean found = false;
                for (PointOfInterestData p : pois) if (p.name.equals(poi)) { found = true; break; }
                if (!found) error("item " + i.name + ": teleports to unknown POI '" + poi + "'");
            }
        }
        info(items.size + " items, " + effects + " effect cards resolved");

        System.out.println("== Heroes");
        com.badlogic.gdx.utils.JsonValue heroes = new com.badlogic.gdx.utils.JsonReader().parse(Config.instance().getFile(Paths.HEROES));
        String avatarAtlas = heroes.getString("avatar", null);
        Set<String> avatars = avatarAtlas == null ? Set.of() : atlasRegions(avatarAtlas);
        int nHeroes = 0;
        for (com.badlogic.gdx.utils.JsonValue h = heroes.get("heroes").child; h != null; h = h.next) {
            nHeroes++;
            String name = h.getString("name", "?");
            for (String k : new String[]{"male", "female"}) {
                String a = h.getString(k, null);
                if (a == null || atlasRegions(a).isEmpty()) error("hero " + name + ": " + k + " sprite atlas '" + a + "' missing or empty");
                else if (!atlasRegions(a).contains("Walk") && !atlasRegions(a).contains("WalkRight")) error("hero " + name + ": " + k + " atlas '" + a + "' has no Walk animation");
            }
            for (String k : new String[]{"maleAvatar", "femaleAvatar"}) {
                String a = h.getString(k, null);
                if (a == null || !avatars.contains(a)) error("hero " + name + ": " + k + " '" + a + "' not in " + avatarAtlas);
            }
        }
        info(nHeroes + " hero races");
    }

    /** The cards the game draws shop and reward cards from (RewardData.initializeAllCards, minus player-state filters). */
    static Collection<PaperCard> cardPool(ConfigData cd) {
        Set<String> restricted = new HashSet<>(Arrays.asList(cd.restrictedCards == null ? new String[0] : cd.restrictedCards));
        List<PaperCard> out = new ArrayList<>();
        for (PaperCard c : CardUtil.getFullCardPool(false)) {
            if (restricted.contains(c.getName())) continue;
            if (c.getRules().isCustom() && c.getImageKey(false).startsWith(forge.ImageKeys.ADVENTURECARD_PREFIX)) continue;
            if (c.getName().startsWith("A-")) continue; // alchemy rebalanced, excluded by default settings
            out.add(c);
        }
        return out;
    }

    static List<PaperCard> rewardPool(RewardData r, Collection<PaperCard> pool, String label) {
        if ("Union".equals(r.type)) {
            Set<PaperCard> u = new LinkedHashSet<>();
            if (r.cardUnion != null) for (RewardData x : r.cardUnion) u.addAll(rewardPool(x, pool, label));
            return new ArrayList<>(u);
        }
        if (r.cardName != null && !r.cardName.isEmpty()) {
            PaperCard pc = null;
            if (r.editions != null && r.editions.length > 0) {
                for (String ed : r.editions) {
                    pc = CardUtil.getCardByNameAndEdition(r.cardName, ed);
                    if (pc == null || !pc.getEdition().equals(ed)) error(label + ": card '" + r.cardName + "' has no printing in " + ed);
                }
            } else pc = FModel.getMagicDb().getCommonCards().getCard(r.cardName);
            return pc == null ? List.of() : List.of(pc);
        }
        return CardUtil.getPredicateResult(pool, r);
    }

    /** Checks that every card an item effect puts into play exists (card or token script). */
    static int checkEffectCards(String label, EffectData e) {
        if (e == null) return 0;
        int n = 0;
        for (String[] arr : new String[][]{e.startBattleWithCard, e.startBattleWithCardInCommandZone}) {
            if (arr == null) continue;
            for (String name : arr) {
                n++;
                boolean ok = FModel.getMagicDb().getCommonCards().getCard(name) != null;
                if (!ok) {
                    try { ok = FModel.getMagicDb().getAllTokens().getToken(name) != null; } catch (Exception ignored) { }
                }
                if (!ok) ok = customCardNames().contains(name); // adventure custom cards load with the game UI only
                if (!ok) warn(label + ": effect card '" + name + "' not found (it is skipped in battle)");
            }
        }
        return n + checkEffectCards(label + " (opponent)", e.opponent);
    }

    static Set<String> customNames;

    /** Names of the adventure custom cards (common/custom_cards and the plane's custom_cards). */
    static Set<String> customCardNames() {
        if (customNames != null) return customNames;
        customNames = new HashSet<>();
        for (String dir : new String[]{Config.instance().getCommonFilePath(Paths.CUSTOM_CARDS), Config.instance().getFilePath(Paths.CUSTOM_CARDS)}) {
            java.io.File[] fs = new java.io.File(dir).listFiles();
            if (fs == null) continue;
            for (java.io.File f : fs) {
                try {
                    for (String l : java.nio.file.Files.readAllLines(f.toPath()))
                        if (l.startsWith("Name:")) customNames.add(l.substring(5).trim());
                } catch (Exception ignored) { }
            }
        }
        return customNames;
    }

    /** Region names of a libGDX atlas text file (resolved through Config, so plane first, then common). */
    static Set<String> atlasRegions(String path) {
        Set<String> out = new HashSet<>();
        FileHandle h = Config.instance().getFile(path);
        if (h == null || !h.exists()) return out;
        boolean header = true;
        for (String line : h.readString("UTF-8").split("\\R")) {
            if (line.isBlank()) { header = true; continue; }
            if (line.startsWith(" ") || line.startsWith("\t") || line.contains(":")) continue;
            if (header && line.trim().endsWith(".png")) { header = false; continue; }
            out.add(line.trim());
        }
        return out;
    }

    static final java.util.regex.Pattern ITEM_REF = java.util.regex.Pattern.compile(
            "\"(?:addItem|removeItem|item)\"\\s*:\\s*\"([^\"]+)\"");
    static final java.util.regex.Pattern INNER_OBJECT = java.util.regex.Pattern.compile("\\{[^{}]*\\}");
    static final java.util.regex.Pattern ITEM_TYPE = java.util.regex.Pattern.compile("\"type\"\\s*:\\s*\"item\"", java.util.regex.Pattern.CASE_INSENSITIVE);
    static final java.util.regex.Pattern ITEM_NAME = java.util.regex.Pattern.compile("\"itemName\"\\s*:\\s*\"([^\"]+)\"");
    static final java.util.regex.Pattern ITEM_NAMES = java.util.regex.Pattern.compile("\"itemNames\"\\s*:\\s*\\[([^\\]]*)\\]");

    static Set<String> textItemRefs(FileHandle h) {
        return h == null || !h.exists() ? Set.of() : itemRefsInText(h.readString("UTF-8"));
    }

    /**
     * Item names in JSON text (rewards, dialogs, quests): dialog addItem / removeItem / condition "item",
     * and itemName / itemNames of innermost {...} objects whose type is "item" (a card reward with a
     * stray itemName is not an item reference).
     */
    static Set<String> itemRefsInText(String v) {
        Set<String> out = new TreeSet<>();
        if (v == null || v.indexOf('{') < 0) return out;
        java.util.regex.Matcher m = ITEM_REF.matcher(v);
        while (m.find()) out.add(m.group(1));
        java.util.regex.Matcher ob = INNER_OBJECT.matcher(v);
        while (ob.find()) {
            String obj = ob.group();
            if (!ITEM_TYPE.matcher(obj).find()) continue;
            java.util.regex.Matcher n1 = ITEM_NAME.matcher(obj);
            while (n1.find()) out.add(n1.group(1));
            java.util.regex.Matcher n2 = ITEM_NAMES.matcher(obj);
            while (n2.find()) {
                java.util.regex.Matcher q = java.util.regex.Pattern.compile("\"([^\"]+)\"").matcher(n2.group(1));
                while (q.find()) out.add(q.group(1));
            }
        }
        return out;
    }

    /** Walks the .tmx maps of all POIs (and the maps their entries/portals teleport to). */
    static MapRefs scanPoiMaps(Array<PointOfInterestData> pois) {
        MapRefs refs = new MapRefs();
        Deque<String> queue = new ArrayDeque<>();
        if (pois != null) for (PointOfInterestData p : pois) if (p.map != null) queue.add(p.map);
        Map<String, Map<String, String>> templates = new HashMap<>();
        while (!queue.isEmpty()) {
            String path = queue.poll();
            FileHandle h = Config.instance().getFile(path);
            if (h == null || !h.exists()) { error("map '" + path + "' not found"); continue; }
            String key = h.file().toPath().normalize().toString();
            if (!refs.maps.add(key)) continue;
            String mapName = h.name();
            org.w3c.dom.Document doc;
            try {
                doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(h.file());
            } catch (Exception ex) { error("map " + path + ": " + ex); continue; }
            org.w3c.dom.NodeList objs = doc.getElementsByTagName("object");
            for (int i = 0; i < objs.getLength(); i++) {
                org.w3c.dom.Element o = (org.w3c.dom.Element) objs.item(i);
                Map<String, String> props = new HashMap<>();
                String tpl = o.getAttribute("template");
                if (!tpl.isEmpty()) {
                    java.io.File tf = new java.io.File(h.file().getParentFile(), tpl);
                    props.putAll(templates.computeIfAbsent(tf.toPath().normalize().toString(), k -> templateProps(tf)));
                }
                if (!o.getAttribute("type").isEmpty()) props.put("type", o.getAttribute("type"));
                props.putAll(elementProps(o));
                String type = props.getOrDefault("type", "");
                if (type.equals("shop") || type.equals("Rotating")) {
                    for (String k : new String[]{"commonShopList", "uncommonShopList", "rareShopList", "mythicShopList", "shopList", "rotation"})
                        for (String n : props.getOrDefault(k, "").replaceAll("\\s", "").split(","))
                            if (!n.isEmpty()) refs.shops.computeIfAbsent(n, x -> new TreeSet<>()).add(mapName);
                }
                String tel = props.get("teleport");
                if (tel != null && !tel.isEmpty()) queue.add(tel);
                // Item names inside any JSON-valued property (reward, dialog, arena, enemy rewards...)
                for (String v : props.values())
                    for (String n : itemRefsInText(v)) refs.items.computeIfAbsent(n, x -> new TreeSet<>()).add(mapName);
            }
        }
        return refs;
    }

    static Map<String, String> templateProps(java.io.File f) {
        try {
            org.w3c.dom.Document d = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f);
            org.w3c.dom.Element o = (org.w3c.dom.Element) d.getElementsByTagName("object").item(0);
            Map<String, String> p = new HashMap<>();
            if (!o.getAttribute("type").isEmpty()) p.put("type", o.getAttribute("type"));
            p.putAll(elementProps(o));
            return p;
        } catch (Exception ex) {
            error("template " + f + ": " + ex);
            return Map.of();
        }
    }

    /** The &lt;properties&gt; directly under an object element. */
    static Map<String, String> elementProps(org.w3c.dom.Element o) {
        Map<String, String> p = new HashMap<>();
        for (org.w3c.dom.Node c = o.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (!(c instanceof org.w3c.dom.Element pe) || !pe.getTagName().equals("properties")) continue;
            for (org.w3c.dom.Node q = pe.getFirstChild(); q != null; q = q.getNextSibling()) {
                if (!(q instanceof org.w3c.dom.Element prop) || !prop.getTagName().equals("property")) continue;
                String v = prop.hasAttribute("value") ? prop.getAttribute("value") : prop.getTextContent();
                p.put(prop.getAttribute("name"), v);
            }
        }
        return p;
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
