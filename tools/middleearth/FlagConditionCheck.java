import forge.adventure.data.BiomeData;
import forge.adventure.data.FlagCondition;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.util.Current;

import java.util.ArrayList;
import java.util.List;

/**
 * Headless unit checks for the story engine (run 1): FlagCondition parsing, validation and evaluation against a
 * real AdventurePlayer, flag byte semantics, and BiomeData.enemyOverrides ordering and cache refresh.
 * Map-scope flags need a MapStage (GL) and are covered by tests/flags_and_world_state.py.
 * <p>
 * Run from forge-gui/: java -Djava.awt.headless=true -cp $FORGE_JAR ../tools/middleearth/FlagConditionCheck.java
 * (tools/middleearth/tests/run_all.sh does this). Exit code 1 on a failure.
 */
public class FlagConditionCheck {
    static int failures = 0, checks = 0;

    static void ok(boolean cond, String what) {
        checks++;
        if (!cond) { failures++; System.out.println("FAIL " + what); }
    }

    static boolean holds(String json) {
        return FlagCondition.allHold(FlagCondition.parse(json));
    }

    public static void main(String[] args) {
        // run from forge-gui/ (like check.sh): the player needs the GUI interface and the localizer
        forge.gui.GuiBase.setInterface(new forge.GuiMobile("./"));
        forge.util.Localizer.getInstance().initialize("en-US", forge.localinstance.properties.ForgeConstants.LANG_DIR);
        AdventurePlayer p = Current.player();

        // --- unset flags read as 0, not, default op ---
        ok(holds("{\"key\":\"u\",\"op\":\"<\",\"val\":3}"), "unset < 3");
        ok(!holds("{\"key\":\"u\",\"op\":\"<\",\"val\":3,\"not\":true}"), "not(unset < 3)");
        ok(holds("{\"key\":\"u\",\"op\":\"=\",\"val\":0}"), "unset = 0");
        ok(!holds("{\"key\":\"u\"}"), "default op != 0 on unset");
        ok(holds("{\"key\":\"u\",\"not\":true}"), "not set");

        // --- quest / character scope against a real player; set 0 deletes ---
        p.setQuestFlag("q", 5);
        p.setCharacterFlag("c", -2);
        ok(holds("{\"key\":\"q\",\"op\":\">=\",\"val\":5}"), "quest q >= 5");
        ok(!holds("{\"scope\":\"character\",\"key\":\"q\"}"), "q is not a character flag");
        ok(holds("{\"scope\":\"character\",\"key\":\"c\",\"op\":\"<\",\"val\":0}"), "negative character flag");
        ok(holds("[{\"key\":\"q\",\"op\":\"!=\",\"val\":4},{\"scope\":\"char\",\"key\":\"c\",\"op\":\"LESSTHAN\",\"val\":-1}]"), "array AND + legacy op/scope names");
        ok(holds("{\"any\":[{\"key\":\"nope\"},{\"key\":\"q\",\"op\":\"=\",\"val\":5}]}"), "any");
        ok(!holds("{\"all\":[{\"key\":\"q\"},{\"key\":\"nope\"}]}"), "all");
        p.setQuestFlag("q", 0);
        ok(!p.checkQuestFlag("q") && holds("{\"key\":\"q\",\"op\":\"=\",\"val\":0}"), "set 0 deletes the flag, reads 0");

        // --- advance: +1 from unset, byte wrap documented (127 + 1 = -128) ---
        p.advanceQuestFlag("a");
        p.advanceQuestFlag("a");
        ok(p.getQuestFlag("a") == 2, "advance twice = 2");
        p.setQuestFlag("w", 127);
        p.advanceQuestFlag("w");
        ok(p.getQuestFlag("w") == -128, "advance wraps at 127 (byte), documented");
        p.setQuestFlag("big", 300);
        ok(p.getQuestFlag("big") == 44, "set casts to byte (300 -> 44), documented");

        // --- validation ---
        List<String> probs = FlagCondition.validate("c", FlagCondition.parse("[{\"key\":\"x\",\"op\":\"=>\"},{\"scope\":\"moon\",\"key\":\"y\"},{\"op\":\"=\"},{\"key\":\"z\",\"val\":128}]"));
        ok(probs.size() == 4, "4 validation problems, got " + probs);
        ok(FlagCondition.validate("c", FlagCondition.parse("{\"scope\":\"map\",\"key\":\"m\",\"op\":\">=\",\"val\":-3}")).isEmpty(), "valid map condition");
        boolean threw = false;
        try { FlagCondition.parse("{\"key\":\"x\",\"bogus\":1}"); } catch (IllegalArgumentException e) { threw = true; }
        ok(threw, "unknown field is rejected");
        threw = false;
        try { FlagCondition.parse("{\"key\":"); } catch (IllegalArgumentException e) { threw = true; }
        ok(threw, "broken JSON is rejected");

        // --- BiomeData.enemyOverrides: applied in order, cache follows flags ---
        BiomeData b = new BiomeData();
        b.enemies = new String[]{"A", "B", "C"};
        b.enemyOverrides = new BiomeData.EnemyOverride[]{
                override("{\"key\":\"act\",\"op\":\">=\",\"val\":1}", "add", "D"),
                override("{\"key\":\"act\",\"op\":\">=\",\"val\":2}", "replace", "E", "F"),
                override("{\"key\":\"act\",\"op\":\">=\",\"val\":3}", "remove", "E"),
                override(null, "remove", "C")};
        ok(b.getEffectiveEnemyNames().equals(List.of("A", "B")), "act 0: base minus unconditional remove: " + b.getEffectiveEnemyNames());
        p.setQuestFlag("act", 1);
        ok(b.getEffectiveEnemyNames().equals(List.of("A", "B", "D")), "act 1: add: " + b.getEffectiveEnemyNames());
        p.setQuestFlag("act", 2);
        ok(b.getEffectiveEnemyNames().equals(List.of("E", "F")), "act 2: replace: " + b.getEffectiveEnemyNames());
        p.setQuestFlag("act", 3);
        ok(b.getEffectiveEnemyNames().equals(List.of("F")), "act 3: remove: " + b.getEffectiveEnemyNames());

        System.out.println((failures == 0 ? "OK " : "FAILED ") + (checks - failures) + "/" + checks + " flag condition checks passed");
        System.exit(failures == 0 ? 0 : 1);
    }

    static BiomeData.EnemyOverride override(String cond, String mode, String... enemies) {
        BiomeData.EnemyOverride o = new BiomeData.EnemyOverride();
        o.condition = cond == null ? null : FlagCondition.parse(cond)[0];
        o.mode = mode;
        o.enemies = enemies;
        return o;
    }
}
