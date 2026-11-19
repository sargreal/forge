package forge.adventure.data;

import com.badlogic.gdx.utils.Json;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.stage.MapStage;
import forge.adventure.util.Current;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Fork (Middle-earth): one flag condition model shared by dialog conditions ({@code flag}), quest stages
 * ({@code condition}), biome {@code enemyOverrides}, reward {@code condition}, POI {@code condition} and the
 * map object property {@code spawnCondition}.
 * <p>
 * JSON: {@code {"scope": "quest|character|map", "key": "act", "op": ">=", "val": 2, "not": false}} or a group
 * {@code {"all": [...]}} / {@code {"any": [...]}}. A JSON array is an AND of its entries.
 * <p>
 * Semantics: an unset flag reads as 0 (flags are bytes and setting one to 0 deletes it); {@code op} defaults
 * to {@code !=} and {@code val} to 0, so {@code {"key": "x"}} means "x is set". {@code not} inverts the whole
 * node. Map flags are those of the map the player is in; on the world map they read as 0.
 */
public class FlagCondition implements Serializable {
    private static final long serialVersionUID = 7311820424466718111L;

    public String scope = "quest";
    public String key;
    public String op = "!=";
    public int val = 0;
    public boolean not = false;
    public FlagCondition[] all;
    public FlagCondition[] any;

    public FlagCondition() {
    }

    public FlagCondition(String scope, String key, String op, int val) {
        this.scope = scope;
        this.key = key;
        this.op = op;
        this.val = val;
    }

    /** Evaluates against the current player and, for map flags, the map the player is in. */
    public boolean eval() {
        return eval(null);
    }

    /** Evaluates with map flags from {@code stage} (null: the current map stage, if the player is in a map). */
    public boolean eval(MapStage stage) {
        boolean result;
        if (all != null || any != null) {
            result = allHold(all, stage) && (any == null || any.length == 0 || anyHolds(any, stage));
        } else {
            result = compare(flagValue(scope, key, stage), op, val);
        }
        return result != not;
    }

    /** True when every condition holds; null or empty is true. */
    public static boolean allHold(FlagCondition[] conditions, MapStage stage) {
        if (conditions == null) return true;
        for (FlagCondition c : conditions)
            if (c != null && !c.eval(stage)) return false;
        return true;
    }

    public static boolean allHold(FlagCondition[] conditions) {
        return allHold(conditions, null);
    }

    private static boolean anyHolds(FlagCondition[] conditions, MapStage stage) {
        for (FlagCondition c : conditions)
            if (c != null && c.eval(stage)) return true;
        return false;
    }

    /** The value of a flag, 0 when unset (or when a map flag is read outside a map). */
    public static int flagValue(String scope, String key, MapStage stage) {
        if (key == null) return 0;
        String s = normalizeScope(scope);
        if ("map".equals(s)) {
            MapStage m = stage != null ? stage : MapStage.getInstance();
            if (m == null || (stage == null && !m.isInMap()) || m.getChanges() == null) return 0;
            return m.getQuestFlag(key);
        }
        AdventurePlayer p = Current.player();
        if (p == null) return 0;
        return "character".equals(s) ? p.getCharacterFlag(key) : p.getQuestFlag(key);
    }

    /** Fork (run 2): {@code current + delta} clamped to {@code [min, max]} and to the byte range -128..127. */
    public static int clampedAdd(int current, int delta, int min, int max) {
        int lo = Math.max(Byte.MIN_VALUE, Math.min(min, max));
        int hi = Math.min(Byte.MAX_VALUE, Math.max(min, max));
        long v = (long) current + delta;
        return (int) Math.max(lo, Math.min(hi, v));
    }

    /**
     * Fork (run 2): adds a signed delta to a flag, clamped (never wraps), through the normal set methods, so flag
     * quest stages wake like after {@code set*Flag}. Map flags need the player to be in a map. Returns the new value,
     * or null when the scope is unknown or a map flag is written outside a map.
     */
    public static Integer addFlag(String scope, String key, int delta, int min, int max) {
        String s = normalizeScope(scope);
        if (s == null || key == null || key.isEmpty()) return null;
        int v = clampedAdd(flagValue(s, key, null), delta, min, max);
        if ("map".equals(s)) {
            MapStage m = MapStage.getInstance();
            if (m == null || !m.isInMap()) return null;
            m.setQuestFlag(key, v);
        } else if ("character".equals(s)) {
            Current.player().setCharacterFlag(key, v);
        } else {
            Current.player().setQuestFlag(key, v);
        }
        return v;
    }

    /** quest | character | map, or null when unknown. Null/empty means quest. */
    public static String normalizeScope(String scope) {
        if (scope == null || scope.isEmpty()) return "quest";
        switch (scope.toLowerCase(Locale.ROOT)) {
            case "quest": case "questflag": return "quest";
            case "character": case "char": case "characterflag": return "character";
            case "map": case "mapflag": return "map";
            default: return null;
        }
    }

    /** Canonical operator (= != < <= > >=) for any accepted spelling, or null when unknown. */
    public static String normalizeOp(String op) {
        if (op == null || op.isEmpty()) return "!=";
        switch (op.toUpperCase(Locale.ROOT)) {
            case "=": case "==": case "EQUAL": case "EQUALS": return "=";
            case "!=": case "<>": case "NOT_EQUAL": case "NOTEQUAL": return "!=";
            case "<": case "LESSTHAN": return "<";
            case "<=": case "LE_THAN": return "<=";
            case ">": case "MORETHAN": return ">";
            case ">=": case "ME_THAN": return ">=";
            default: return null;
        }
    }

    public static boolean compare(int flag, String op, int value) {
        String o = normalizeOp(op);
        if (o == null) return false;
        switch (o) {
            case "=": return flag == value;
            case "!=": return flag != value;
            case "<": return flag < value;
            case "<=": return flag <= value;
            case ">": return flag > value;
            default: return flag >= value;
        }
    }

    /** Appends problems (unknown scope/op, missing key, value outside the byte range) to {@code out}. */
    public void validate(String where, List<String> out) {
        boolean group = all != null || any != null;
        if (group) {
            if (key != null) out.add(where + ": a condition has both 'key' and 'all'/'any'");
            if (all != null) for (int i = 0; i < all.length; i++)
                if (all[i] == null) out.add(where + ".all[" + i + "]: empty"); else all[i].validate(where + ".all[" + i + "]", out);
            if (any != null) for (int i = 0; i < any.length; i++)
                if (any[i] == null) out.add(where + ".any[" + i + "]: empty"); else any[i].validate(where + ".any[" + i + "]", out);
            return;
        }
        if (key == null || key.trim().isEmpty()) out.add(where + ": missing 'key'");
        if (normalizeScope(scope) == null) out.add(where + ": unknown scope '" + scope + "' (quest, character, map)");
        if (normalizeOp(op) == null) out.add(where + ": unknown op '" + op + "' (= != < <= > >=)");
        if (val < Byte.MIN_VALUE || val > Byte.MAX_VALUE) out.add(where + ": val " + val + " is outside the flag range -128..127");
    }

    public static List<String> validate(String where, FlagCondition[] conditions) {
        List<String> out = new ArrayList<>();
        if (conditions != null) for (int i = 0; i < conditions.length; i++)
            if (conditions[i] == null) out.add(where + "[" + i + "]: empty");
            else conditions[i].validate(conditions.length == 1 ? where : where + "[" + i + "]", out);
        return out;
    }

    /**
     * Parses a condition written as a JSON object or array (the Tiled property {@code spawnCondition}).
     * Unknown fields are errors. Throws {@link IllegalArgumentException} on malformed input.
     */
    public static FlagCondition[] parse(String text) {
        if (text == null || text.trim().isEmpty()) return new FlagCondition[0];
        Json json = new Json();
        json.setIgnoreUnknownFields(false);
        try {
            JsonValue v = new JsonReader().parse(text);
            if (v.isArray()) return json.readValue(FlagCondition[].class, v);
            if (v.isObject()) return new FlagCondition[]{json.readValue(FlagCondition.class, v)};
        } catch (Exception e) {
            throw new IllegalArgumentException("malformed flag condition " + text.trim() + ": " + e.getMessage(), e);
        }
        throw new IllegalArgumentException("a flag condition must be a JSON object or array: " + text.trim());
    }

    @Override
    public String toString() {
        String s;
        if (all != null || any != null) {
            List<String> parts = new ArrayList<>();
            if (all != null) for (FlagCondition c : all) parts.add(String.valueOf(c));
            String a = String.join(" and ", parts);
            parts.clear();
            if (any != null) for (FlagCondition c : any) parts.add(String.valueOf(c));
            String o = parts.isEmpty() ? "" : "(" + String.join(" or ", parts) + ")";
            s = a.isEmpty() ? o : o.isEmpty() ? a : a + " and " + o;
        } else {
            s = normalizeScope(scope) + ":" + key + " " + normalizeOp(op) + " " + val;
        }
        return not ? "not(" + s + ")" : s;
    }
}
