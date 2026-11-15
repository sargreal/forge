#!/usr/bin/env python3
"""Regression test: corruption meter pieces (story engine run 2).

1. Signed, clamped add (console `flag add`, same code as the dialog actions addQuestFlag/addCharacterFlag/addMapFlag):
   clamps at min/max and at the byte range instead of wrapping; reaching 0 deletes the flag.
2. config.json statusFlags: the "Shadow" readout of quest flag `corruption` is hidden while it is 0 (showWhen) and
   the HUD label follows the value live (/state statusFlags[].hud is the label text as drawn).
3. config.json flagEffects: with character flag test_flag_effect the player starts duels with +7 life and
   "the Ring tempts you" (startBattleScript DB$ RingTemptsYou): checked in the duel state.

Usage: tools/middleearth/tests/corruption.py [--keep]  (own game unless one answers; DISPLAY_NUM)
"""
import sys
import time

from _harness import check, step, main, duel_with_spawn, leave_duel


def con(g, cmd):
    return g.console(cmd).get("output", "").strip()


def shadow(g, want_hud=None, timeout=3.0):
    """The corruption status flag from /state; waits for the HUD label (polled every 0.25 s) to show want_hud."""
    t_end = time.time() + timeout
    while True:
        sf = [f for f in g.state().get("statusFlags", []) if f["flag"] == "corruption"]
        check(sf, "no corruption status flag in /state")
        if want_hud is None or sf[0]["hud"] == want_hud or time.time() > t_end:
            return sf[0]
        time.sleep(0.2)


def run(g):
    step("new game with origin Test")
    g.new_game(plane="MiddleEarth", name="Tester", difficulty="Normal", colors="G", mode="Constructed", origin="Test")
    g.wait("inMap=forest_capital.tmx", 30000)
    if g.dialog():
        g.close_dialog()

    # --- 1. signed, clamped add ---
    check(con(g, "flag add quest test_c 5 0 10") == "quest:test_c = 5", "add from unset")
    check(con(g, "flag add quest test_c 20 0 10") == "quest:test_c = 10", "clamp at max")
    check(con(g, "flag add quest test_c -3 0 10") == "quest:test_c = 7", "negative add")
    check(con(g, "flag add quest test_c -30 0 10") == "quest:test_c = 0", "clamp at min")
    check("test_c" not in g.state()["questFlags"], "0 deletes the flag")
    check(con(g, "flag add character test_c 100") == "character:test_c = 100", "default range")
    check(con(g, "flag add character test_c 100") == "character:test_c = 127", "clamps at 127 (no wrap to -128)")
    check(con(g, "flag add character test_c -300") == "character:test_c = -128", "clamps at -128")
    con(g, "flag set map test_m 3")
    check(con(g, "flag add map test_m 4 0 5") == "map:test_m = 5", "map flag add")
    step("flag add clamps (min/max, byte range), 0 deletes")

    # --- 2. HUD readout ---
    sf = shadow(g)
    check(sf["value"] == 0 and not sf["visible"] and sf["hud"] is None, "hidden while corruption is 0: %s" % sf)
    con(g, "flag add quest corruption 12 0 100")
    sf = shadow(g, "[%95]Shadow 12/100")
    check(sf["visible"] and sf["value"] == 12 and sf["hud"] == "[%95]Shadow 12/100", "HUD after +12: %s" % sf)
    con(g, "flag add quest corruption -5 0 100")
    sf = shadow(g, "[%95]Shadow 7/100")
    check(sf["hud"] == "[%95]Shadow 7/100", "HUD follows live: %s" % sf)
    con(g, "flag add quest corruption -50 0 100")
    sf = shadow(g, None)
    time.sleep(0.5)
    sf = shadow(g)
    check(not sf["visible"] and sf["hud"] is None, "hidden again at 0: %s" % sf)
    con(g, "flag set quest corruption 9")
    shadow(g, "[%95]Shadow 9/100")
    step("statusFlags: hidden at 0, live HUD label 12 -> 7 -> hidden -> 9")

    # --- 3. flagEffects at duel start ---
    con(g, "flag set character test_flag_effect 1")
    con(g, "leave")
    g.wait("onOverworld", 20000)
    p = g.state()["location"]["pos"]["px"]
    g.walk(p["x"], p["y"] - 90, unit="px", timeout_ms=6000)  # off Hobbiton's entrance
    life = g.state()["player"]["life"]
    d = duel_with_spawn(g, "Badger")
    me = [x for x in d["players"] if x["name"] == "Tester"][0]
    check(me["startingLife"] == life + 7, "flagEffect lifeModifier +7: starting life %s, adventure life %s" % (me["startingLife"], life))
    check(me["ringTempted"] >= 1 and "The Ring" in me["command"], "flagEffect RingTemptsYou: %s" % me)
    g.duel("concede")
    leave_duel(g)
    step("flagEffects: starting life %d (+7), the Ring tempted %dx, emblem %s" % (me["startingLife"], me["ringTempted"], me["command"]))

    con(g, "heal full")
    con(g, "flag set character test_flag_effect 0")
    life = g.state()["player"]["life"]
    d = duel_with_spawn(g, "Badger")
    me = [x for x in d["players"] if x["name"] == "Tester"][0]
    check(me["ringTempted"] == 0 and me["startingLife"] == life, "no flagEffect without the flag (life %d): %s" % (life, me))
    g.duel("concede")
    leave_duel(g)
    step("without test_flag_effect: no extra life, no tempting")


if __name__ == "__main__":
    sys.exit(main(run))
