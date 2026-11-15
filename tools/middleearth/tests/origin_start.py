#!/usr/bin/env python3
"""Regression test: data-driven origins (config.json origins[], story engine run 1).

1. New game with the hidden test origin "Test": starts in its POI (Forest Capital = Hobbiton), with
   its intro quest 9001 (fixture), its character/quest flags and its respawn POI.
2. Quest 9001 stage 1 (QuestFlag, mapFlagValue 2) completes from two `advanceQuestFlag`s (console
   `flag advance`), stage 2 (CharacterFlag with a `condition` that already holds) on activation.
3. An unknown origin is refused.
4. A default new game picks the first non-hidden origin ("Wizard's Hollow": Spawn + quest 28).

Usage: tools/middleearth/tests/origin_start.py [--keep]  (own game unless one answers; DISPLAY_NUM)
"""
import sys

from _harness import Fail, check, step, main, gamectl


def flags(s, kind):
    return s.get(kind) or {}


def run(g):
    step("new game with origin Test")
    g.new_game(plane="MiddleEarth", name="Tester", difficulty="Normal", colors="G", mode="Constructed", origin="Test")
    g.wait("inMap=forest_capital.tmx", 30000)
    if g.dialog():  # a dialog left open by an earlier game in the same session
        g.close_dialog()
    s = g.state()
    check(s["player"].get("origin") == "Test", "origin is %r" % s["player"].get("origin"))
    check(s["player"].get("respawnPOI") == "Forest Capital", "respawnPOI is %r" % s["player"].get("respawnPOI"))
    poi = s["location"].get("poi") or {}
    check(poi.get("name") == "Forest Capital", "start POI is %r" % poi)
    check(flags(s, "characterFlags").get("test_origin") == 1, "character flags: %s" % flags(s, "characterFlags"))
    check(flags(s, "questFlags").get("test_origin_q") == 3, "quest flags: %s" % flags(s, "questFlags"))
    q = [x for x in s["quests"] if x["id"] == 9001]
    check(q, "intro quest 9001 not issued: %s" % [x["id"] for x in s["quests"]])
    check(not [x for x in s["quests"] if x["id"] == 28], "quest 28 must not be issued for the Test origin")
    check(q[0]["stages"][0]["status"] == "ACTIVE", "stage 1 should be active: %s" % q[0]["stages"])
    step("in %s with quest 9001, flags %s / %s" % (poi.get("displayName"), flags(s, "characterFlags"), flags(s, "questFlags")))

    g.console("flag advance quest test_origin_adv")
    s = g.state()
    q = [x for x in s["quests"] if x["id"] == 9001]
    check(q and q[0]["stages"][0]["status"] == "ACTIVE", "stage 1 must stay active at 1/2: %s" % q)
    g.console("flag advance quest test_origin_adv")
    s = g.state()
    q = [x for x in s["quests"] if x["id"] == 9001]
    check(not q or all(st["status"] == "COMPLETE" for st in q[0]["stages"]),
          "advanceQuestFlag to 2 should complete stage 1, and stage 2's condition holds: %s" % q)
    step("quest 9001 complete (advance woke stage 1, stage 2 condition held on activation)")

    try:
        g.new_game(plane="MiddleEarth", name="Tester", origin="NoSuchOrigin")
        raise Fail("unknown origin was accepted")
    except gamectl.ApiError as e:
        check("unknown origin" in str(e), "unexpected error: %s" % e)
    step("unknown origin refused")

    step("default new game")
    g.new_game(plane="MiddleEarth", name="Tester", difficulty="Normal", colors="W", mode="Constructed")
    g.wait("inMap=spawn.tmx", 30000)
    s = g.state()
    check(s["player"].get("origin") == "Wizard's Hollow", "default origin is %r" % s["player"].get("origin"))
    check(s["player"].get("respawnPOI") == "Spawn", "default respawnPOI is %r" % s["player"].get("respawnPOI"))
    check([x for x in s["quests"] if x["id"] == 28], "quest 28 not issued by the default origin")
    check("test_origin" not in flags(s, "characterFlags"), "test flags leaked into a default game")
    step("default origin: Spawn + quest 28")


if __name__ == "__main__":
    sys.exit(main(run))
