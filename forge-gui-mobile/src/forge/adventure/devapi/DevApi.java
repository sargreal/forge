package forge.adventure.devapi;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputProcessor;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.ui.Button;
import com.badlogic.gdx.scenes.scene2d.ui.Cell;
import com.badlogic.gdx.scenes.scene2d.ui.Dialog;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import com.github.tommyettinger.textra.TextraButton;
import com.github.tommyettinger.textra.TextraLabel;
import forge.Forge;
import forge.adventure.character.DialogActor;
import forge.adventure.character.EnemySprite;
import forge.adventure.character.EntryActor;
import forge.adventure.character.MapActor;
import forge.adventure.character.PlayerSprite;
import forge.adventure.character.PortalActor;
import forge.adventure.character.RewardSprite;
import forge.adventure.character.ShopActor;
import forge.adventure.data.AdventureQuestData;
import forge.adventure.data.AdventureQuestStage;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.pointofintrest.PointOfInterest;
import forge.adventure.scene.DuelScene;
import forge.adventure.scene.GameScene;
import forge.adventure.scene.NewGameScene;
import forge.adventure.scene.RewardScene;
import forge.adventure.scene.Scene;
import forge.adventure.scene.TileMapScene;
import forge.adventure.scene.UIScene;
import forge.adventure.stage.ConsoleCommandInterpreter;
import forge.adventure.stage.GameStage;
import forge.adventure.stage.MapStage;
import forge.adventure.stage.WorldStage;
import forge.adventure.util.Config;
import forge.adventure.util.Current;
import forge.adventure.util.Reward;
import forge.adventure.util.RewardActor;
import forge.adventure.util.pathfinding.NavigationMap;
import forge.adventure.util.pathfinding.NavigationVertex;
import forge.adventure.util.pathfinding.ProgressableGraphPath;
import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.player.Player;
import forge.gamemodes.match.HostedMatch;
import forge.gui.FThreads;
import forge.player.PlayerControllerHuman;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.zip.Deflater;

/**
 * Dev-only game control API (see docs/MiddleEarth/DEVELOPMENT.md, "Driving the game via the dev API").
 * <p>
 * Plain Java facade with no HTTP dependency, so this shared module stays Android/iOS safe. The desktop
 * launcher (forge-gui-mobile-dev, forge.app.DevApiServer) serves it on 127.0.0.1 only when
 * -Dforge.devapi.port is set; otherwise nothing here is ever called.
 * <p>
 * Threading: {@link #handle} runs on an HTTP worker thread. Every game read or action runs on the libGDX
 * thread through {@link #gl} (postRunnable + future + timeout). Long operations (walk, wait, newGame) poll
 * from the worker thread or run as a per-frame runnable on the GL thread that completes a future.
 */
public final class DevApi {
    private DevApi() {
    }

    /** HTTP status + JSON body. */
    public static final class Result {
        public final int status;
        public final String json;

        Result(int status, Object body) {
            this.status = status;
            this.json = DevJson.write(body);
        }
    }

    static final class ApiException extends RuntimeException {
        ApiException(String m) {
            super(m);
        }
    }

    // ------------------------------------------------------------------ entry point

    public static Result handle(String method, String path, String body) {
        try {
            JsonValue args = body == null || body.trim().isEmpty() ? new JsonValue(JsonValue.ValueType.object) : new JsonReader().parse(body);
            Object out = route(method.toUpperCase(), path, args);
            return new Result(200, out);
        } catch (ApiException e) {
            return new Result(400, err(e.getMessage()));
        } catch (TimeoutException e) {
            return new Result(504, err("timeout: game thread did not answer (loading or frozen?)"));
        } catch (Throwable t) {
            t.printStackTrace();
            return new Result(500, err(t.toString()));
        }
    }

    private static Map<String, Object> err(String m) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", false);
        r.put("error", m);
        return r;
    }

    private static Object route(String method, String path, JsonValue a) throws Exception {
        if (Gdx.app == null)
            throw new ApiException("game not started yet");
        switch (path) {
            case "/state":
            case "/":
                return gl(() -> state(a.getFloat("radius", 0)), 5000);
            case "/newGame":
                return newGame(a);
            case "/dialog/choose":
                return dialogChoose(a.getInt("index", -1));
            case "/dialog/close":
                return gl(DevApi::dialogClose, 5000);
            case "/reward/accept":
                return rewardAccept();
            case "/walkTo":
                return walk(a, false);
            case "/interact":
                return walk(a, true);
            case "/teleport":
                return gl(() -> teleport(a), 5000);
            case "/console":
                return console(a.getString("command", ""));
            case "/key":
                return key(a.getString("key", ""), a.getInt("holdMs", 100));
            case "/wait":
                return waitFor(a.getString("condition", ""), a.getLong("timeoutMs", 10000));
            case "/screenshot":
                return gl(() -> screenshot(a.getString("path", "")), 5000);
            case "/duel/concede":
                return gl(() -> duel(false), 5000);
            case "/duel/autoplay":
                return gl(() -> duel(true), 5000);
            default:
                throw new ApiException("unknown endpoint " + method + " " + path);
        }
    }

    /** Runs c on the libGDX thread and waits for it. */
    static <T> T gl(Callable<T> c, long timeoutMs) throws Exception {
        CompletableFuture<T> f = new CompletableFuture<>();
        Gdx.app.postRunnable(() -> {
            try {
                f.complete(c.call());
            } catch (Throwable t) {
                f.completeExceptionally(t);
            }
        });
        try {
            return f.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof Exception) throw (Exception) e.getCause();
            throw new RuntimeException(e.getCause());
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static Map<String, Object> ok() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true);
        return r;
    }

    // ------------------------------------------------------------------ helpers (GL thread)

    private static Map<String, Object> m(Object... kv) {
        Map<String, Object> r = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2)
            r.put((String) kv[i], kv[i + 1]);
        return r;
    }

    private static Object field(Object o, String name) {
        Class<?> c = o.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (IllegalAccessException e) {
                return null;
            }
        }
        return null;
    }

    static String clean(CharSequence s) {
        if (s == null) return "";
        String t = s.toString();
        if (t.toLowerCase().contains("{var=player_name}")) {
            String name = "";
            try {
                name = AdventurePlayer.current().getName();
            } catch (Exception ignored) {
            }
            t = t.replaceAll("(?i)\\{var=player_name\\}", java.util.regex.Matcher.quoteReplacement(name));
        }
        return t.replaceAll("\\[[^\\]]*\\]", "").replaceAll("\\{[^}]*\\}", "").trim();
    }

    private static boolean gameLoaded() {
        Scene s = Forge.getCurrentScene();
        return s instanceof GameScene || s instanceof TileMapScene || s instanceof RewardScene || s instanceof DuelScene;
    }

    /** The stage the player currently walks on, or null if neither map is shown. */
    private static GameStage activeStage() {
        Scene s = Forge.getCurrentScene();
        if (s instanceof TileMapScene) return MapStage.getInstance();
        if (s instanceof GameScene) return WorldStage.getInstance();
        return null;
    }

    private static boolean inMap() {
        return MapStage.getInstance().isInMap();
    }

    private static float mapTileSize() {
        try {
            return Float.parseFloat(MapStage.getInstance().tiledMap.getProperties().get("tilewidth").toString());
        } catch (Exception e) {
            return 16f;
        }
    }

    private static float tileSize() {
        return inMap() ? mapTileSize() : Current.world().getTileSize();
    }

    private static Map<String, Object> pos(float x, float y, float ts) {
        return m("px", m("x", x, "y", y), "tile", m("x", (int) Math.floor(x / ts), "y", (int) Math.floor(y / ts)));
    }

    // ------------------------------------------------------------------ state

    private static Map<String, Object> state(float radius) {
        Map<String, Object> st = new LinkedHashMap<>();
        Scene scene = Forge.getCurrentScene();
        st.put("scene", scene == null ? null : scene.getClass().getSimpleName());
        st.put("transition", Forge.getTransitionScreen() != null);
        st.put("adventure", Forge.isMobileAdventureMode);
        try {
            st.put("plane", Config.instance().getPlane());
        } catch (Exception ignored) {
        }
        if (!Forge.isMobileAdventureMode || scene == null)
            return st;
        Map<String, Object> modal = modalDialog(scene);
        if (modal != null) st.put("modal", modal);
        if (scene instanceof RewardScene) st.put("reward", reward());
        if (scene instanceof DuelScene) st.put("duel", duelState());
        if (!gameLoaded())
            return st;
        st.put("frozen", Forge.advFreezePlayerControls);
        try {
            st.put("player", player());
        } catch (Exception e) {
            st.put("player", m("error", e.toString()));
        }
        try {
            location(st, radius);
        } catch (Exception e) {
            st.put("locationError", e.toString());
        }
        try {
            st.put("quests", quests());
            st.put("questFlags", AdventurePlayer.current().getQuestFlags());
            st.put("characterFlags", AdventurePlayer.current().getCharacterFlags());
        } catch (Exception e) {
            st.put("questsError", e.toString());
        }
        Map<String, Object> dlg = gameDialog();
        if (dlg != null) st.put("dialog", dlg);
        return st;
    }

    private static Map<String, Object> player() {
        AdventurePlayer p = AdventurePlayer.current();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("name", p.getName());
        r.put("life", p.getLife());
        r.put("maxLife", p.getMaxLife());
        r.put("gold", p.getGold());
        r.put("shards", p.getShards());
        Deck d = p.getSelectedDeck();
        if (d != null) r.put("deck", m("name", d.getName(), "size", d.getMain().countAll()));
        try {
            r.put("difficulty", p.getDifficulty().name);
        } catch (Exception ignored) {
        }
        return r;
    }

    private static void location(Map<String, Object> st, float radius) {
        if (inMap()) {
            MapStage ms = MapStage.getInstance();
            PointOfInterest poi = TileMapScene.instance().rootPoint;
            Map<String, Object> loc = new LinkedHashMap<>();
            loc.put("where", "map");
            loc.put("map", ms.getCurrentMap());
            if (poi != null)
                loc.put("poi", m("id", poi.getID(), "name", poi.getData().name, "displayName", poi.getDisplayName(), "type", poi.getData().type));
            PlayerSprite pl = ms.getPlayerSprite();
            float ts = mapTileSize();
            loc.put("pos", pos(pl.getX(), pl.getY(), ts));
            loc.put("tileSize", ts);
            loc.put("mapFlags", ms.getChanges() == null ? null : ms.getChanges().getMapFlags());
            st.put("location", loc);
            st.put("objects", mapObjects(ms, pl, radius));
        } else {
            WorldStage ws = WorldStage.getInstance();
            PlayerSprite pl = ws.getPlayerSprite();
            float ts = Current.world().getTileSize();
            Map<String, Object> loc = new LinkedHashMap<>();
            loc.put("where", "overworld");
            loc.put("pos", pos(pl.getX(), pl.getY(), ts));
            loc.put("tileSize", ts);
            loc.put("worldTiles", m("w", Current.world().getWidthInTiles(), "h", Current.world().getHeightInTiles()));
            Vector2 c = new Vector2(pl.getX() + pl.getWidth() / 2, pl.getY() + pl.getHeight() / 2);
            loc.put("biome", GameScene.instance().getBiomeByPosition(c));
            try {
                loc.put("locationName", clean(GameScene.instance().getAdventurePlayerLocation(true, false)));
            } catch (Exception ignored) {
            }
            st.put("location", loc);
            st.put("objects", worldObjects(ws, pl, radius > 0 ? radius : 30 * ts));
        }
    }

    private static List<Object> mapObjects(MapStage ms, PlayerSprite pl, float radius) {
        List<Object> out = new ArrayList<>();
        for (MapActor a : ms.getMapActors()) {
            if (a == null) continue;
            if (radius > 0 && Vector2.dst(a.getX(), a.getY(), pl.getX(), pl.getY()) > radius) continue;
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("id", a.getObjectId());
            String type;
            if (a instanceof EnemySprite) {
                EnemySprite e = (EnemySprite) a;
                type = "enemy";
                o.put("name", e.getName());
                o.put("enemy", e.getData() == null ? null : e.getData().getName());
                o.put("hasDialog", e.dialog != null);
                o.put("inactive", e.inactive);
                o.put("hidden", e.hidden);
            } else if (a instanceof PortalActor) {
                type = "portal";
                o.put("state", ((PortalActor) a).getAnimation());
                o.put("target", field(a, "targetMap"));
            } else if (a instanceof EntryActor) {
                type = "entry";
                o.put("target", field(a, "targetMap"));
            } else if (a instanceof DialogActor) {
                type = "dialog";
                o.put("name", a.getName());
            } else if (a instanceof RewardSprite) {
                type = "reward";
            } else if (a instanceof ShopActor) {
                type = "shop";
                o.put("name", ((ShopActor) a).getName());
            } else {
                type = a.getClass().getSimpleName();
            }
            o.put("type", type);
            o.put("x", a.getX());
            o.put("y", a.getY());
            o.put("w", a.getWidth());
            o.put("h", a.getHeight());
            o.put("onStage", a.getStage() != null);
            o.put("deleted", ms.getChanges() != null && ms.getChanges().isObjectDeleted(a.getObjectId()));
            o.put("dist", Math.round(Vector2.dst(a.getX(), a.getY(), pl.getX(), pl.getY())));
            out.add(o);
        }
        return out;
    }

    private static List<Object> worldObjects(WorldStage ws, PlayerSprite pl, float radius) {
        List<Object> out = new ArrayList<>();
        Vector2 p = new Vector2(pl.getX(), pl.getY());
        for (PointOfInterest poi : Current.world().getAllPointOfInterest()) {
            float d = poi.getPosition().dst(p);
            if (d > radius) continue;
            out.add(m("type", "poi", "id", poi.getID(), "name", poi.getData().name, "displayName", poi.getDisplayName(),
                    "poiType", poi.getData().type, "x", poi.getPosition().x, "y", poi.getPosition().y, "dist", Math.round(d)));
        }
        for (Actor a : ws.getSpriteGroup().getChildren()) {
            if (!(a instanceof EnemySprite)) continue;
            EnemySprite e = (EnemySprite) a;
            float d = Vector2.dst(a.getX(), a.getY(), p.x, p.y);
            if (d > radius) continue;
            out.add(m("type", "enemy", "name", e.getName(), "enemy", e.getData() == null ? null : e.getData().getName(),
                    "x", a.getX(), "y", a.getY(), "dist", Math.round(d)));
        }
        out.sort((x, y) -> Long.compare(((Number) ((Map<?, ?>) x).get("dist")).longValue(), ((Number) ((Map<?, ?>) y).get("dist")).longValue()));
        return out;
    }

    private static List<Object> quests() {
        List<Object> out = new ArrayList<>();
        for (AdventureQuestData q : AdventurePlayer.current().getQuests()) {
            List<Object> stages = new ArrayList<>();
            String current = null;
            for (AdventureQuestStage s : q.stages) {
                String status = String.valueOf(s.getStatus());
                stages.add(m("id", s.id, "name", s.name, "status", status));
                if (current == null && "ACTIVE".equalsIgnoreCase(status)) current = s.name + ": " + s.description;
            }
            out.add(m("id", q.getID(), "name", q.getName(), "currentStage", current, "stages", stages));
        }
        return out;
    }

    // ------------------------------------------------------------------ dialogs

    /** The open map/quest dialog (MapStage or WorldStage), or null. */
    private static Dialog openGameDialog() {
        for (GameStage s : new GameStage[]{MapStage.getInstance(), WorldStage.getInstance()}) {
            Dialog d = s.getDialog();
            if (d != null && d.getStage() != null && s.isDialogOnlyInput())
                return d;
        }
        return null;
    }

    private static Dialog openModal(Scene scene) {
        if (!(scene instanceof UIScene)) return null;
        Dialog found = null;
        for (Dialog d : ((UIScene) scene).dialogs)
            if (d.getStage() != null && d.isVisible()) found = d;
        return found;
    }

    private static Map<String, Object> gameDialog() {
        Dialog d = openGameDialog();
        return d == null ? null : describe(d);
    }

    private static Map<String, Object> modalDialog(Scene scene) {
        Dialog d = openModal(scene);
        return d == null ? null : describe(d);
    }

    private static void texts(Actor a, List<String> out) {
        if (a instanceof Button) return;
        if (a instanceof TextraLabel) {
            String t = clean(((TextraLabel) a).storedText);
            if (!t.isEmpty()) out.add(t);
        } else if (a instanceof Label) {
            String t = clean(((Label) a).getText());
            if (!t.isEmpty()) out.add(t);
        } else if (a instanceof Group) {
            for (Actor c : ((Group) a).getChildren()) texts(c, out);
        }
    }

    private static List<Button> buttons(Dialog d) {
        List<Button> out = new ArrayList<>();
        for (Cell<?> c : d.getButtonTable().getCells())
            if (c.getActor() instanceof Button) out.add((Button) c.getActor());
        return out;
    }

    private static String buttonLabel(Button b) {
        if (b instanceof TextraButton) return clean(((TextraButton) b).getTextraLabel().storedText);
        if (b instanceof TextButton) return clean(((TextButton) b).getText());
        List<String> t = new ArrayList<>();
        for (Actor c : b.getChildren()) texts(c, t);
        return String.join(" ", t);
    }

    private static Map<String, Object> describe(Dialog d) {
        List<String> title = new ArrayList<>(), text = new ArrayList<>();
        texts(d.getTitleTable(), title);
        texts(d.getContentTable(), text);
        String speaker = String.join(" ", title).replaceAll("^-|-$", "").trim();
        List<Object> options = new ArrayList<>();
        int i = 0;
        for (Button b : buttons(d))
            options.add(m("index", i++, "label", buttonLabel(b), "enabled", !b.isDisabled(), "visible", b.isVisible()));
        return m("speaker", speaker.isEmpty() ? null : speaker, "text", String.join("\n", text), "options", options);
    }

    /** Simulates a real tap (touchDown + touchUp at the centre) so all listeners run as for a click. */
    private static void click(Actor a) {
        a.setVisible(true); // buttons appear after the typing animation; a real player taps to skip it
        Vector2 c = a.localToStageCoordinates(new Vector2(a.getWidth() / 2f, a.getHeight() / 2f));
        for (InputEvent.Type t : new InputEvent.Type[]{InputEvent.Type.touchDown, InputEvent.Type.touchUp}) {
            InputEvent e = new InputEvent();
            e.setType(t);
            e.setStage(a.getStage());
            e.setStageX(c.x);
            e.setStageY(c.y);
            e.setPointer(0);
            e.setButton(Input.Buttons.LEFT);
            a.fire(e);
        }
    }

    private static Object dialogChoose(int index) throws Exception {
        Map<String, Object> r = gl(() -> {
            Dialog d = openGameDialog();
            if (d == null) d = openModal(Forge.getCurrentScene());
            if (d == null) throw new ApiException("no dialog open");
            List<Button> bs = buttons(d);
            if (index < 0 || index >= bs.size()) throw new ApiException("option index " + index + " out of range 0.." + (bs.size() - 1));
            Button b = bs.get(index);
            if (b.isDisabled()) throw new ApiException("option " + index + " (" + buttonLabel(b) + ") is disabled");
            String label = buttonLabel(b);
            click(b);
            return m("ok", true, "chosen", label);
        }, 5000);
        sleep(150); // let the next dialog page / follow-up action settle
        r.put("state", brief());
        return r;
    }

    private static Object dialogClose() {
        Dialog d = openGameDialog();
        if (d != null) {
            MapStage.getInstance().hideDialog();
            WorldStage.getInstance().hideDialog();
            return ok();
        }
        d = openModal(Forge.getCurrentScene());
        if (d == null) throw new ApiException("no dialog open");
        d.hide();
        ((UIScene) Forge.getCurrentScene()).dialogs.removeValue(d, true);
        return ok();
    }

    /** A small state summary for action responses. */
    private static Map<String, Object> brief() throws Exception {
        return gl(() -> {
            Map<String, Object> r = new LinkedHashMap<>();
            Scene s = Forge.getCurrentScene();
            r.put("scene", s == null ? null : s.getClass().getSimpleName());
            if (gameLoaded()) {
                GameStage gs = inMap() ? MapStage.getInstance() : WorldStage.getInstance();
                PlayerSprite p = gs.getPlayerSprite();
                r.put("where", inMap() ? "map" : "overworld");
                if (inMap()) r.put("map", MapStage.getInstance().getCurrentMap());
                r.put("pos", pos(p.getX(), p.getY(), tileSize()));
            }
            Map<String, Object> d = gameDialog();
            if (d == null && s != null) d = modalDialog(s);
            r.put("dialog", d);
            return r;
        }, 5000);
    }

    // ------------------------------------------------------------------ reward

    private static Map<String, Object> reward() {
        RewardScene rs = RewardScene.instance();
        List<Object> items = new ArrayList<>();
        Object gen = field(rs, "generated");
        if (gen instanceof com.badlogic.gdx.utils.Array) {
            for (Object o : (com.badlogic.gdx.utils.Array<?>) gen) {
                if (!(o instanceof RewardActor)) continue;
                Reward rw = ((RewardActor) o).getReward();
                if (rw == null) continue;
                String name = rw.getCard() != null ? rw.getCard().getName() : rw.getItem() != null ? rw.getItem().name : rw.getDeck() != null ? rw.getDeck().getName() : null;
                items.add(m("type", String.valueOf(rw.getType()), "count", rw.getCount(), "name", name));
            }
        }
        return m("type", String.valueOf(field(rs, "type")), "items", items);
    }

    private static Object rewardAccept() throws Exception {
        long end = System.currentTimeMillis() + 15000;
        int presses = 0;
        while (System.currentTimeMillis() < end) {
            boolean onReward = gl(() -> Forge.getCurrentScene() instanceof RewardScene, 5000);
            if (!onReward) {
                if (presses == 0) throw new ApiException("no reward screen open");
                Map<String, Object> r = ok();
                r.put("presses", presses);
                r.put("state", brief());
                return r;
            }
            gl(() -> RewardScene.instance().done(), 5000);
            presses++;
            sleep(1500);
        }
        throw new ApiException("reward screen did not close after " + presses + " presses");
    }

    // ------------------------------------------------------------------ new game

    private static Object newGame(JsonValue a) throws Exception {
        String plane = a.getString("plane", null);
        long end = System.currentTimeMillis() + 180000;
        // wait for the adventure title menu (UI_SELECTOR_MODE=Adventure skips the mode chooser)
        while (true) {
            Boolean ready = gl(() -> Forge.isMobileAdventureMode && Forge.getCurrentScene() != null && Forge.getTransitionScreen() == null, 5000);
            if (ready) break;
            if (System.currentTimeMillis() > end) throw new ApiException("adventure mode never opened (set UI_SELECTOR_MODE=Adventure, game.sh start does)");
            sleep(500);
        }
        String err = gl(() -> {
            if (plane != null && !plane.equalsIgnoreCase(Config.instance().getPlane()))
                return "running plane is " + Config.instance().getPlane() + "; the plane is fixed at startup (game.sh start " + plane + ")";
            NewGameScene ng = NewGameScene.instance();
            if (Forge.getCurrentScene() != ng) Forge.switchScene(ng);
            return ng.devApiStart(a.getString("name", null), a.getString("race", null), a.getString("gender", null),
                    a.getString("difficulty", null), a.getString("mode", null), a.getString("colors", null));
        }, 30000);
        if (err != null) throw new ApiException(err);
        // wait until the first map is shown
        sleep(1000);
        while (System.currentTimeMillis() < end) {
            Boolean done = gl(() -> {
                Scene s = Forge.getCurrentScene();
                return (s instanceof TileMapScene || s instanceof GameScene) && Forge.getTransitionScreen() == null;
            }, 30000);
            if (done) {
                sleep(1500); // first frames: quest prologue gets queued on enter
                Map<String, Object> r = ok();
                r.put("state", brief());
                return r;
            }
            sleep(500);
        }
        throw new ApiException("new game did not reach the first map in time");
    }

    // ------------------------------------------------------------------ movement

    private static Object teleport(JsonValue a) {
        GameStage gs = activeStage();
        if (gs == null) throw new ApiException("not on a map");
        boolean tile = !"px".equals(a.getString("unit", "tile"));
        float ts = tileSize();
        float x = a.getFloat("x"), y = a.getFloat("y");
        Vector2 v = tile ? new Vector2(x * ts, y * ts) : new Vector2(x, y);
        if (gs instanceof WorldStage) gs.setPosition(v);
        else gs.getPlayerSprite().setPosition(v);
        gs.stop();
        Map<String, Object> r = ok();
        r.put("pos", pos(v.x, v.y, ts));
        return r;
    }

    private static Object walk(JsonValue a, boolean interact) throws Exception {
        long timeout = a.getLong("timeoutMs", 30000);
        Walker w = gl(() -> Walker.create(a, interact, timeout), 5000);
        Gdx.app.postRunnable(w::frame);
        Map<String, Object> r;
        try {
            r = w.done.get(timeout + 5000, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            w.cancelled = true;
            throw e;
        }
        sleep(200);
        r.put("state", brief());
        return r;
    }

    /** Per-frame autopilot on the GL thread: steers via the touch-knob input like the on-screen joystick. */
    static final class Walker {
        final CompletableFuture<Map<String, Object>> done = new CompletableFuture<>();
        volatile boolean cancelled;
        GameStage stage;
        Scene scene;
        String startMap;
        boolean map;
        final List<Vector2> waypoints = new ArrayList<>();
        int wp;
        MapActor target;
        Rectangle targetRect;
        long deadline;
        final Vector2 last = new Vector2();
        float stuck;
        int touchFrames;
        boolean pathfinding;

        static Walker create(JsonValue a, boolean interact, long timeout) {
            Walker w = new Walker();
            w.stage = activeStage();
            if (w.stage == null) throw new ApiException("not on a map (scene " + Forge.getCurrentScene() + ")");
            if (openGameDialog() != null) throw new ApiException("a dialog is open; choose or close it first");
            w.scene = Forge.getCurrentScene();
            w.map = w.stage instanceof MapStage;
            w.startMap = w.map ? MapStage.getInstance().getCurrentMap() : null;
            w.deadline = System.currentTimeMillis() + timeout;
            PlayerSprite p = w.stage.getPlayerSprite();
            Vector2 goal;
            if (interact) {
                if (w.map) {
                    int id = a.getInt("objectId", -1);
                    for (MapActor ma : MapStage.getInstance().getMapActors())
                        if (ma != null && ma.getObjectId() == id) w.target = ma;
                    if (w.target == null || w.target.getStage() == null) throw new ApiException("no object with id " + id + " on this map");
                    w.targetRect = new Rectangle(w.target.boundingRect());
                } else {
                    String poiName = a.getString("poi", "");
                    PointOfInterest poi = null;
                    for (PointOfInterest x : Current.world().getAllPointOfInterest())
                        if (x.getID().equals(poiName) || x.getData().name.equalsIgnoreCase(poiName) || poiName.equalsIgnoreCase(x.getDisplayName()))
                            if (poi == null || x.getPosition().dst(p.getX(), p.getY()) < poi.getPosition().dst(p.getX(), p.getY())) poi = x;
                    if (poi == null) throw new ApiException("on the overworld, interact takes {poi: id|name|displayName}; not found: " + poiName);
                    w.targetRect = new Rectangle(poi.getBoundingRectangle());
                }
                goal = new Vector2(w.targetRect.x + w.targetRect.width / 2f - p.getWidth() / 2f, w.targetRect.y + w.targetRect.height / 2f - p.getHeight() / 2f);
            } else {
                boolean tile = !"px".equals(a.getString("unit", "tile"));
                float ts = tileSize();
                goal = tile ? new Vector2(a.getFloat("x") * ts, a.getFloat("y") * ts) : new Vector2(a.getFloat("x"), a.getFloat("y"));
            }
            if (w.map && a.getBoolean("pathfinding", true)) {
                try {
                    MapStage ms = MapStage.getInstance();
                    Map.Entry<Float, NavigationMap> nav = ms.navMaps.entrySet().iterator().next();
                    ProgressableGraphPath<NavigationVertex> path = nav.getValue().findShortestPath(nav.getKey(), p.pos(), goal);
                    if (path != null)
                        for (int i = 0; i < path.getCount(); i++)
                            if (path.get(i) != null && path.get(i).pos != null) w.waypoints.add(new Vector2(path.get(i).pos));
                    w.pathfinding = !w.waypoints.isEmpty();
                } catch (Exception e) {
                    w.waypoints.clear();
                }
            }
            if (w.waypoints.isEmpty() || !w.waypoints.get(w.waypoints.size() - 1).epsilonEquals(goal, 0.5f))
                w.waypoints.add(goal);
            w.last.set(p.getX(), p.getY());
            return w;
        }

        void frame() {
            if (done.isDone()) return;
            String r;
            try {
                r = cancelled ? "cancelled" : step();
            } catch (Throwable t) {
                r = "error: " + t;
            }
            if (r != null) {
                finish(r);
                return;
            }
            Gdx.app.postRunnable(this::frame);
        }

        private String step() {
            if (Forge.getCurrentScene() != scene) return "sceneChanged";
            if (map && !Objects.equals(MapStage.getInstance().getCurrentMap(), startMap)) return "mapChanged";
            if (openGameDialog() != null || openModal(Forge.getCurrentScene()) != null) return "dialog";
            if (System.currentTimeMillis() > deadline) return "timeout";
            PlayerSprite p = stage.getPlayerSprite();
            Vector2 pos = new Vector2(p.getX(), p.getY());
            if (stage.isPaused() || Forge.advFreezePlayerControls || Forge.getTransitionScreen() != null) {
                last.set(pos);
                return null; // e.g. duel start animation, teleport pause
            }
            if (targetRect != null) {
                if (target != null && target.getStage() == null) return "targetGone";
                if (p.boundingRect().overlaps(target != null ? target.boundingRect() : targetRect)) {
                    if (++touchFrames > 20) return "touched";
                } else {
                    touchFrames = 0;
                }
            }
            while (wp < waypoints.size() && pos.dst(waypoints.get(wp)) < 1.5f) wp++;
            if (wp >= waypoints.size()) {
                if (targetRect == null) return "arrived";
                if (touchFrames > 0) {
                    stage.setTouchKnobInput(0, 0);
                    return null; // standing on the target, wait for the trigger
                }
                return "arrivedNoContact";
            }
            Vector2 dir = new Vector2(waypoints.get(wp)).sub(pos).nor();
            stage.setTouchKnobInput(dir.x, dir.y);
            if (pos.dst(last) < 0.05f) stuck += Gdx.graphics.getDeltaTime();
            else stuck = 0;
            last.set(pos);
            if (stuck > 1.5f) return "blocked";
            return null;
        }

        private void finish(String reason) {
            try {
                stage.setTouchKnobInput(0, 0);
                stage.stop();
            } catch (Exception ignored) {
            }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("ok", reason.equals("arrived") || reason.equals("touched") || (targetRect != null && (reason.equals("dialog") || reason.equals("mapChanged") || reason.equals("sceneChanged"))));
            r.put("reason", reason);
            r.put("pathfinding", pathfinding);
            r.put("waypoints", waypoints.size());
            done.complete(r);
        }
    }

    // ------------------------------------------------------------------ console, keys, wait, screenshot

    private static Object console(String command) throws Exception {
        if (command.isEmpty()) throw new ApiException("command missing");
        String out = gl(() -> ConsoleCommandInterpreter.getInstance().command(command), 60000);
        Map<String, Object> r = ok();
        r.put("output", out);
        return r;
    }

    private static Object key(String name, int holdMs) throws Exception {
        int code = Input.Keys.valueOf(name);
        if (code < 0 && name.length() == 1) code = Input.Keys.valueOf(name.toUpperCase());
        if (code < 0) throw new ApiException("unknown key " + name + " (libGDX Input.Keys names, e.g. Escape, Enter, W, F9)");
        final int k = code;
        gl(() -> {
            InputProcessor ip = Gdx.input.getInputProcessor();
            if (ip != null) ip.keyDown(k);
            return null;
        }, 5000);
        sleep(Math.max(0, holdMs));
        gl(() -> {
            InputProcessor ip = Gdx.input.getInputProcessor();
            if (ip != null) ip.keyUp(k);
            return null;
        }, 5000);
        return ok();
    }

    /** Conditions: scene=X, dialogOpen, dialogClosed, onOverworld, inMap[=file substring], quest=ID, questStage=ID:STAGE_ID|text, idle, rewardOpen, duel. */
    private static Object waitFor(String cond, long timeoutMs) throws Exception {
        if (cond.isEmpty()) throw new ApiException("condition missing");
        long end = System.currentTimeMillis() + timeoutMs;
        while (true) {
            Boolean ok;
            try {
                ok = gl(() -> check(cond), 5000);
            } catch (TimeoutException e) {
                ok = false; // loading
            }
            if (ok) {
                Map<String, Object> r = ok();
                r.put("condition", cond);
                r.put("state", brief());
                return r;
            }
            if (System.currentTimeMillis() > end) {
                Map<String, Object> r = err("timeout waiting for " + cond);
                try {
                    r.put("state", brief());
                } catch (Exception ignored) {
                }
                return r;
            }
            sleep(100);
        }
    }

    private static boolean check(String cond) {
        String key = cond, val = null;
        int eq = cond.indexOf('=');
        if (eq >= 0) {
            key = cond.substring(0, eq);
            val = cond.substring(eq + 1);
        }
        Scene s = Forge.getCurrentScene();
        boolean idle = Forge.getTransitionScreen() == null;
        switch (key) {
            case "scene":
                return idle && s != null && s.getClass().getSimpleName().equalsIgnoreCase(val);
            case "dialogOpen":
                return openGameDialog() != null || (s != null && openModal(s) != null);
            case "dialogClosed":
                return openGameDialog() == null && (s == null || openModal(s) == null);
            case "onOverworld":
                return idle && s instanceof GameScene && !inMap();
            case "inMap":
                return idle && s instanceof TileMapScene && inMap() && (val == null || String.valueOf(MapStage.getInstance().getCurrentMap()).contains(val));
            case "rewardOpen":
                return s instanceof RewardScene;
            case "duel":
                return s instanceof DuelScene;
            case "idle":
                return idle && (s instanceof GameScene || s instanceof TileMapScene) && openGameDialog() == null && !Forge.advFreezePlayerControls;
            case "quest":
            case "questStage": {
                if (val == null) throw new ApiException(key + " needs a value");
                String[] parts = val.split(":", 2);
                for (AdventureQuestData q : AdventurePlayer.current().getQuests()) {
                    if (key.equals("quest")) {
                        if (String.valueOf(q.getID()).equals(val)) return true;
                        continue;
                    }
                    for (AdventureQuestStage st : q.stages) {
                        if (!"ACTIVE".equalsIgnoreCase(String.valueOf(st.getStatus()))) continue;
                        if (parts.length == 2 && String.valueOf(q.getID()).equals(parts[0]) && String.valueOf(st.id).equals(parts[1])) return true;
                        if (parts.length == 1 && st.name.toLowerCase().contains(val.toLowerCase())) return true;
                    }
                }
                return false;
            }
            default:
                throw new ApiException("unknown condition " + cond);
        }
    }

    private static Object screenshot(String path) {
        if (path.isEmpty()) throw new ApiException("path missing");
        int w = Gdx.graphics.getBackBufferWidth(), h = Gdx.graphics.getBackBufferHeight();
        Pixmap pm = Pixmap.createFromFrameBuffer(0, 0, w, h);
        try {
            PixmapIO.writePNG(Gdx.files.absolute(path), pm, Deflater.DEFAULT_COMPRESSION, true);
        } finally {
            pm.dispose();
        }
        Map<String, Object> r = ok();
        r.put("path", path);
        return r;
    }

    // ------------------------------------------------------------------ duel

    private static HostedMatch hostedMatch() {
        Object hm = field(DuelScene.instance(), "hostedMatch");
        return hm instanceof HostedMatch ? (HostedMatch) hm : null;
    }

    private static Map<String, Object> duelState() {
        HostedMatch hm = hostedMatch();
        if (hm == null || hm.getGame() == null) return m("loading", true);
        Game g = hm.getGame();
        List<Object> players = new ArrayList<>();
        for (Player p : g.getPlayers())
            players.add(m("name", p.getName(), "life", p.getLife(), "human", p.getController() instanceof PlayerControllerHuman,
                    "ai", p.getController() instanceof PlayerControllerAi, "hand", p.getCardsIn(forge.game.zone.ZoneType.Hand).size(),
                    "library", p.getCardsIn(forge.game.zone.ZoneType.Library).size()));
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("players", players);
        r.put("turn", g.getPhaseHandler().getTurn());
        r.put("phase", String.valueOf(g.getPhaseHandler().getPhase()));
        r.put("gameOver", g.isGameOver());
        return r;
    }

    private static Object duel(boolean autoplay) {
        if (!(Forge.getCurrentScene() instanceof DuelScene)) throw new ApiException("not in a duel");
        HostedMatch hm = hostedMatch();
        if (hm == null || hm.getGame() == null) throw new ApiException("duel still loading");
        List<PlayerControllerHuman> humans = new ArrayList<>(hm.getHumanControllers());
        if (humans.isEmpty()) throw new ApiException("no human controller (already autoplaying?)");
        Game g = hm.getGame();
        for (PlayerControllerHuman pch : humans) {
            if (autoplay) {
                // same technique as FServerManager.convertToAI: hand the human seat to the AI
                Player p = pch.getPlayer();
                p.dangerouslySetController(new PlayerControllerAi(g, p, new LobbyPlayerAi(p.getName(), null)));
                pch.getInputQueue().clearInputs();
            } else {
                FThreads.invokeInBackgroundThread(pch::concede);
            }
        }
        Map<String, Object> r = ok();
        r.put("action", autoplay ? "autoplay" : "concede");
        return r;
    }
}
