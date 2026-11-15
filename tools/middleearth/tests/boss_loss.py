#!/usr/bin/env python3
"""Regression test: non-fatal boss loss (enemy `onDefeat`, story engine run 2).

1. A normal enemy (Badger): conceding costs life and gold as before.
2. The fixture enemy "Test Boss Loss" (boss, `onDefeat.noPenalty`): conceding costs nothing, the player is not
   respawned, the `onDefeat.action`s run (setQuestFlag, addQuestFlag) and the `onDefeat.dialog` is shown; its option
   runs its action. `runCommand` runs an allow-listed command (give gold 7) and refuses another (resetQuests, which
   would wipe the quest flags just set).
3. The dialog option's `endGame` action sets its flag and shows the ending screen; "Continue playing" returns to the game.

Usage: tools/middleearth/tests/boss_loss.py [--keep]  (own game unless one answers; DISPLAY_NUM)
"""
import sys

from _harness import check, step, main, duel_with_spawn, leave_duel


def to_overworld(g):
    s = g.state()
    if s["location"].get("where") != "overworld":
        g.console("leave")
        g.wait("onOverworld", 20000)
        s = g.state()
    p = s["location"]["pos"]["px"]
    g.walk(p["x"], p["y"] - 90, unit="px", timeout_ms=6000)  # step south, off Hobbiton's entrance
    check(g.state()["location"].get("where") == "overworld", "re-entered a POI while stepping away")


def run(g):
    step("new game with origin Test")
    g.new_game(plane="MiddleEarth", name="Tester", difficulty="Normal", colors="G", mode="Constructed", origin="Test")
    g.wait("inMap=forest_capital.tmx", 30000)
    if g.dialog():
        g.close_dialog()
    to_overworld(g)
    g.console("give gold 100")

    p0 = g.state()["player"]
    duel_with_spawn(g, "Badger")
    g.duel("concede")
    s = leave_duel(g)
    p1 = s["player"]
    check(p1["life"] < p0["life"] or p1["gold"] < p0["gold"], "a normal loss must cost life or gold: %s -> %s" % (p0, p1))
    step("normal loss: life %d -> %d, gold %d -> %d" % (p0["life"], p1["life"], p0["gold"], p1["gold"]))

    g.console("heal full")
    to_overworld(g)
    s = g.state()
    p0, pos0 = s["player"], s["location"]["pos"]["px"]
    check(not s["questFlags"].get("test_boss_lost"), "fixture flag already set")
    duel_with_spawn(g, "Test Boss Loss")
    g.duel("concede")
    s = leave_duel(g)
    g.wait("dialogOpen", 15000)
    s = g.state()
    p1 = s["player"]
    check(p1["life"] == p0["life"] and p1["gold"] == p0["gold"] + 7, "onDefeat.noPenalty (+7 gold from runCommand): %s -> %s" % (p0, p1))
    check(s["location"].get("where") == "overworld", "no respawn expected: %s" % s["location"])
    pos1 = s["location"]["pos"]["px"]
    check(abs(pos1["x"] - pos0["x"]) < 200 and abs(pos1["y"] - pos0["y"]) < 200, "player moved: %s -> %s" % (pos0, pos1))
    check(s["questFlags"].get("test_boss_lost") == 1, "onDefeat action setQuestFlag: %s" % s["questFlags"])
    check(s["questFlags"].get("test_boss_corruption") == 5, "onDefeat action addQuestFlag: %s" % s["questFlags"])
    check("story goes on" in (g.dialog() or {}).get("text", ""), "onDefeat dialog: %s" % g.dialog())
    g.choose(0)
    check(g.state()["questFlags"].get("test_boss_dialog") == 1, "onDefeat dialog option action did not run")
    step("boss loss without penalty: life %d, gold %d, flags %s" % (p1["life"], p1["gold"], s["questFlags"]))

    # the option also has an endGame action: flag at once, ending screen once the dialog is closed
    g.wait("dialogOpen", 10000)
    s = g.state()
    d = s.get("dialog") or {}
    check(s["characterFlags"].get("test_game_ended") == 1, "endGame flag: %s" % s["characterFlags"])
    check("tale ends here" in d.get("text", ""), "ending screen text: %s" % d)
    labels = [o["label"] for o in d.get("options", [])]
    check(labels == ["Continue playing", "Main menu"], "ending screen options: %s" % labels)
    g.choose(0)
    s = g.state()
    check(not s.get("dialog") and s["scene"] == "GameScene", "continue playing: %s %s" % (s["scene"], s.get("dialog")))
    step("endGame: flag set, ending screen, continue playing")


if __name__ == "__main__":
    sys.exit(main(run))
