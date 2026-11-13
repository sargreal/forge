import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Json;
import forge.GuiMobile;
import forge.adventure.data.EnemyData;
import forge.adventure.util.CardUtil;
import forge.adventure.util.Config;
import forge.adventure.util.Paths;
import forge.deck.Deck;
import forge.deck.io.DeckSerializer;
import forge.gui.GuiBase;
import forge.model.FModel;

import java.io.File;
import java.util.*;

/**
 * Turns adventure deck references into plain .dck files for tools/middleearth/deckstrength.sh.
 *
 * Adventure `.json` decks are generated (jumpstart packs, card templates...) through
 * CardUtil.getDeck, the same call the game uses, so each export is one random sample.
 *
 * Usage (cwd = forge-gui, classpath = forge-gui-mobile-dev fat jar, user.home with
 * .forge/adventure/settings.json naming the plane):
 *   java -cp <mobile jar> DeckExport.java OUT_DIR SAMPLES ITEM...
 * ITEM is one of
 *   decks/starter/white_e.json   a deck path as the plane's data uses it (plane dir first, then common)
 *   enemy:Name                   every deck of that enemy (by name or nameOverride) in the plane's enemies.json
 * Prints one line per written file: "<file>\t<life or 0>\t<label>".
 */
public class DeckExport {
    public static void main(String[] args) throws Exception {
        File outDir = new File(args[0]);
        int samples = Integer.parseInt(args[1]);
        outDir.mkdirs();
        GuiBase.setInterface(new GuiMobile("./"));
        FModel.initialize(null, null);
        Config.instance();

        Map<String, EnemyData> enemies = new HashMap<>();
        Array<EnemyData> list = new Json().fromJson(Array.class, EnemyData.class, Config.instance().getFile(Paths.ENEMIES));
        for (EnemyData e : list) { enemies.putIfAbsent(e.name, e); enemies.put(e.getName(), e); }

        int n = 0;
        for (int i = 2; i < args.length; i++) {
            String item = args[i];
            List<String> paths = new ArrayList<>();
            int life = 0;
            String label = item;
            if (item.startsWith("enemy:")) {
                EnemyData e = enemies.get(item.substring(6));
                if (e == null) { System.err.println("unknown enemy: " + item); System.exit(2); }
                if (e.deck != null) paths.addAll(Arrays.asList(e.deck));
                life = e.life;
            } else {
                paths.add(item);
            }
            for (String p : paths) {
                int k = p.endsWith(".json") ? samples : 1;
                for (int s = 0; s < k; s++) {
                    Deck d = CardUtil.getDeck(p, true, false, "", false, false);
                    String base = (item.startsWith("enemy:") ? item.substring(6) + "__" : "")
                            + new File(p).getName().replaceAll("\\.(json|dck)$", "")
                            + (k > 1 ? "__s" + (s + 1) : "");
                    base = base.replaceAll("[^A-Za-z0-9_.-]+", "_");
                    String name = (item.startsWith("enemy:") ? item.substring(6) + " / " : "") + d.getName()
                            + (k > 1 ? " #" + (s + 1) : "");
                    d.setName(name);
                    File f = new File(outDir, String.format("%03d_%s.dck", n++, base));
                    DeckSerializer.writeDeck(d, f);
                    System.out.println("EXPORT\t" + f.getAbsolutePath() + "\t" + life + "\t" + label);
                }
            }
        }
        System.exit(0);
    }
}
