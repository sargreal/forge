package forge.adventure.data;

import com.badlogic.gdx.utils.ObjectMap;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.stage.WorldStage;
import forge.adventure.util.Config;
import forge.adventure.util.Current;

/**
 * Fork (Middle-earth): a start of the game, from {@code config.json} → {@code origins[]}.
 * At new game the first non-hidden origin whose races and colors match the character creation choice is
 * used (a hidden origin only by name, e.g. through the dev API). Without {@code origins} the game starts as
 * upstream does: POI "Spawn" and quest 28.
 */
public class OriginData {
    public String name;
    /** Race names from heroes.json; empty matches every race. */
    public String[] races;
    /** Colour ids from config.json colorIds (e.g. "W"); empty matches every colour. */
    public String[] colors;
    /** Never picked automatically, only by name (tests, a future origin picker). */
    public boolean hidden;
    /** POI (points_of_interest.json name) the game starts in. Default "Spawn", unless startPosition is set. */
    public String startPOI;
    /** World tile (x, y; y grows north) to start on, without entering a POI. Used when startPOI is not set. */
    public int[] startPosition;
    /** Quest id issued at the start; none when empty. */
    public String introQuest;
    /** POI where defeat respawns the player. Default: startPOI, else "Spawn". */
    public String respawnPOI;
    public ObjectMap<String, Integer> characterFlags;
    public ObjectMap<String, Integer> questFlags;

    public static OriginData[] all() {
        OriginData[] origins = Config.instance().getConfigData().origins;
        return origins == null ? new OriginData[0] : origins;
    }

    public static OriginData byName(String name) {
        if (name == null) return null;
        for (OriginData o : all())
            if (name.equalsIgnoreCase(o.name)) return o;
        return null;
    }

    public boolean matches(String race, String colorId) {
        return matchesAny(races, race) && matchesAny(colors, colorId);
    }

    private static boolean matchesAny(String[] allowed, String value) {
        if (allowed == null || allowed.length == 0) return true;
        if (value == null) return false;
        for (String a : allowed)
            if (value.equalsIgnoreCase(a)) return true;
        return false;
    }

    /** The first non-hidden origin matching race and colour id, or null (then the upstream start applies). */
    public static OriginData select(String race, String colorId) {
        for (OriginData o : all())
            if (!o.hidden && o.matches(race, colorId)) return o;
        return null;
    }

    /**
     * Places the new player, issues the intro quest and sets the flags. {@code origin} null: upstream start
     * (enter POI "Spawn", quest 28). Call after the world and player are created, before switching to the game.
     */
    public static void beginNewGame(OriginData origin) {
        AdventurePlayer player = AdventurePlayer.current();
        if (origin == null) {
            WorldStage.getInstance().enterSpawnPOI();
            if (player.getQuests().stream().noneMatch(q -> q.getID() == 28)) {
                player.addQuest("28", true); //Temporary link to Shandalar main questline
            }
            return;
        }
        String startPOI = origin.startPOI;
        if ((startPOI == null || startPOI.isEmpty()) && origin.startPosition == null)
            startPOI = "Spawn";
        String respawn = origin.respawnPOI != null && !origin.respawnPOI.isEmpty() ? origin.respawnPOI : startPOI;
        player.setOrigin(origin.name, respawn);
        if (origin.characterFlags != null)
            for (ObjectMap.Entry<String, Integer> e : origin.characterFlags)
                player.setCharacterFlag(e.key, e.value);
        if (origin.questFlags != null)
            for (ObjectMap.Entry<String, Integer> e : origin.questFlags)
                player.setQuestFlag(e.key, e.value);
        if ((startPOI == null || startPOI.isEmpty()) && origin.startPosition != null && origin.startPosition.length >= 2) {
            int ts = Current.world().getTileSize();
            player.setWorldPosX(origin.startPosition[0] * ts);
            player.setWorldPosY(origin.startPosition[1] * ts);
        }
        WorldStage.getInstance().enterSpawnPOI(startPOI);
        if (origin.introQuest != null && !origin.introQuest.isEmpty()) {
            int id = Integer.parseInt(origin.introQuest.trim());
            if (player.getQuests().stream().noneMatch(q -> q.getID() == id))
                player.addQuest(id, true);
        }
    }
}
