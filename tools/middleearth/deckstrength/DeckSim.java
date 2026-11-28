import forge.LobbyPlayer;
import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
import forge.deck.Deck;
import forge.deck.io.DeckSerializer;
import forge.game.*;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.gui.GuiBase;
import forge.model.FModel;
import forge.util.MyRandom;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Headless AI-vs-AI game worker for tools/middleearth/deckstrength.sh.
 *
 * It does what Forge's own `sim` mode (forge.view.SimulateMatch) does, with three differences the
 * strength harness needs:
 *  - every game is a fresh one-game match, and the player who goes first is set per game
 *    (sim mode lets the loser of the previous game start, which skews play/draw);
 *  - one JVM plays a whole task list, so the ~8 s card database start-up is paid once;
 *  - results are appended as CSV lines as soon as a game ends, so a crashed or killed worker
 *    can be resumed (tasks whose id is already in the output file are skipped).
 *
 * Usage (cwd must be forge-gui, so that ./res is found; classpath = forge-gui-desktop fat jar):
 *   java -Djava.awt.headless=true -Duser.home=<isolated> -cp <desktop jar> DeckSim.java TASKS.tsv OUT.csv [timeoutSec]
 *
 * TASKS.tsv: id \t deckA.dck \t deckB.dck \t first (A|B) \t seed [\t lifeA \t lifeB]   (life 0 = default 20)
 * OUT.csv:   id,first,result(A|B|draw),reason,turns,ms,started (who actually went first: A|B|?)
 *
 * Exit code 3: a game did not stop after its timeout (stuck AI thread); the driver restarts the worker.
 */
public class DeckSim {
    /** The lobby player that goes first in the game being set up. Read by the controllers below. */
    static volatile LobbyPlayer designatedFirst;

    /** AI lobby player whose controller starts when told to instead of always choosing itself. */
    static class FixedStartAi extends LobbyPlayerAi {
        FixedStartAi(String name) { super(name, null); setAiProfile("Default"); }

        @Override
        public Player createIngamePlayer(Game game, int id) {
            Player ai = new Player(getName(), game, id);
            ai.setFirstController(new PlayerControllerAi(game, ai, this) {
                @Override
                public Player chooseStartingPlayer(boolean isFirstGame) {
                    for (Player p : game.getPlayers())
                        if (p.getLobbyPlayer() == designatedFirst) return p;
                    return super.chooseStartingPlayer(isFirstGame);
                }
            });
            return ai;
        }
    }

    public static void main(String[] args) throws Exception {
        Path tasksFile = Paths.get(args[0]);
        Path outFile = Paths.get(args[1]);
        int timeoutSec = args.length > 2 ? Integer.parseInt(args[2]) : 120;

        Set<String> done = new HashSet<>();
        if (Files.exists(outFile))
            for (String l : Files.readAllLines(outFile, StandardCharsets.UTF_8))
                if (!l.isBlank()) done.add(l.split(",", 2)[0]);

        List<String[]> tasks = new ArrayList<>();
        for (String l : Files.readAllLines(tasksFile, StandardCharsets.UTF_8)) {
            if (l.isBlank()) continue;
            String[] t = l.split("\t");
            if (!done.contains(t[0])) tasks.add(t);
        }
        if (tasks.isEmpty()) { System.out.println("nothing to do"); return; }

        GuiBase.setInterface(new forge.GuiDesktop());
        FModel.initialize(null, null);

        Map<String, Deck> decks = new HashMap<>();
        try (PrintWriter out = new PrintWriter(new OutputStreamWriter(
                new FileOutputStream(outFile.toFile(), true), StandardCharsets.UTF_8), true)) {
            for (String[] t : tasks) {
                String id = t[0];
                Deck a = decks.computeIfAbsent(t[1], p -> DeckSerializer.fromFile(new File(p)));
                Deck b = decks.computeIfAbsent(t[2], p -> DeckSerializer.fromFile(new File(p)));
                if (a == null || b == null) {
                    out.println(String.join(",", id, t[3], "draw", "deckerror", "0", "0", "?"));
                    continue;
                }
                long seed = Long.parseLong(t[4]);
                int lifeA = t.length > 5 ? Integer.parseInt(t[5]) : 0;
                int lifeB = t.length > 6 ? Integer.parseInt(t[6]) : 0;
                String r = playGame(a, b, "A".equals(t[3]), seed, timeoutSec, lifeA, lifeB);
                out.println(id + "," + t[3] + "," + r);
                System.out.println("game " + id + ": " + r);
                if (r.contains(",stuck,")) {
                    System.out.println("game thread did not stop after timeout, exiting so the driver can restart");
                    out.flush();
                    System.exit(3);
                }
            }
        }
        System.exit(0); // AI helper threads are not all daemons
    }

    /** Returns "result,reason,turns,ms,started". Turns count both players' turns. */
    static String playGame(Deck a, Deck b, boolean aFirst, long seed, int timeoutSec, int lifeA, int lifeB) throws InterruptedException {
        MyRandom.setRandom(new Random(seed));
        GameRules rules = new GameRules(GameType.Constructed);
        rules.setAppliedVariants(EnumSet.of(GameType.Constructed));
        rules.setGamesPerMatch(1);

        FixedStartAi pa = new FixedStartAi("A");
        FixedStartAi pb = new FixedStartAi("B");
        RegisteredPlayer ra = new RegisteredPlayer(a);
        ra.setPlayer(pa);
        RegisteredPlayer rb = new RegisteredPlayer(b);
        rb.setPlayer(pb);
        if (lifeA > 0) ra.setStartingLife(lifeA);
        if (lifeB > 0) rb.setStartingLife(lifeB);
        designatedFirst = aFirst ? pa : pb;

        Match mc = new Match(rules, List.of(ra, rb), "DeckLab");
        Game g = mc.createGame();
        g.setNoGUIUser();
        final Throwable[] err = new Throwable[1];
        long t0 = System.currentTimeMillis();
        Thread th = new Thread(() -> {
            try { mc.startGame(g); } catch (Throwable e) { err[0] = e; }
        }, "decksim-game");
        th.setDaemon(true);
        th.start();
        th.join(timeoutSec * 1000L);
        String reason = "normal";
        if (th.isAlive()) {
            reason = "timeout";
            g.setGameOver(GameEndReason.Draw);
            th.interrupt();
            th.join(15000);
            if (th.isAlive()) reason = "stuck";
        } else if (err[0] != null) {
            reason = "error";
            System.out.println("game error: " + err[0]);
            err[0].printStackTrace(System.out);
            if (!g.isGameOver()) g.setGameOver(GameEndReason.Draw);
        }
        long ms = System.currentTimeMillis() - t0;
        int turns = g.getPhaseHandler().getTurn();
        String result = "draw";
        GameOutcome o = g.getOutcome();
        if (!"timeout".equals(reason) && !"stuck".equals(reason) && o != null && !o.isDraw()) {
            LobbyPlayer w = o.getWinningLobbyPlayer();
            result = w == pa ? "A" : w == pb ? "B" : "draw";
        } else if ("normal".equals(reason)) {
            reason = "draw";
        }
        Player sp = g.getStartingPlayer();
        String started = sp == null ? "?" : sp.getLobbyPlayer() == pa ? "A" : sp.getLobbyPlayer() == pb ? "B" : "?";
        return String.join(",", result, reason, String.valueOf(turns), String.valueOf(ms), started);
    }
}
