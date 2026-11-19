#!/usr/bin/env python3
"""Regression test: the Middle-earth intro, played only through the dev API.

New game -> prologue -> portal closed -> talk to the wizard (reward screen) -> portal active ->
enter the portal -> overworld in the Shire with the epilogue (2 disabled options, 1 enabled) ->
choose the tutorial -> quest 30 active.

Usage: tools/middleearth/tests/intro_walkthrough.py [--keep]
Starts its own game (game.sh start, DISPLAY_NUM default 99) unless one with the dev API already
answers; stops it at the end unless --keep. Exit code 0 = pass, 1 = fail.
"""
import os
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
import gamectl  # noqa: E402

GAME_SH = os.path.join(os.path.dirname(HERE), "game.sh")
PORTAL_ID = 78  # spawn.tmx portal (the way out of the hollow)


class Fail(Exception):
    pass


def check(cond, msg):
    if not cond:
        raise Fail(msg)


def step(msg):
    print("[%5.1fs] %s" % (time.time() - T0, msg), flush=True)


def portal_state(g):
    objs = g.objects(id=PORTAL_ID)
    check(objs, "portal %d not found on the map" % PORTAL_ID)
    return objs[0]["state"]


def option_index(dialog, text):
    for o in dialog["options"]:
        if text.lower() in o["label"].lower():
            return o["index"]
    raise Fail("dialog has no option %r: %s" % (text, [o["label"] for o in dialog["options"]]))


def run(g):
    step("new game")
    g.new_game(plane="MiddleEarth", name="Tester", difficulty="Normal", colors="W", mode="Constructed")
    g.wait("inMap=spawn.tmx", 30000)
    g.wait("dialogOpen", 15000)
    d = g.dialog()
    check("Darkness and silence" in d["text"], "prologue text not shown: %r" % d["text"])
    step("prologue: %s" % d["text"][:50])
    g.choose(option_index(d, "Where am I"))
    d = g.dialog()
    check(d and "bed of dry bracken" in d["text"], "prologue page 2 missing: %r" % d)
    g.choose(0)  # (Continue) -> closes the dialog and closes the portal
    g.wait("dialogClosed", 5000)
    check(portal_state(g) == "closed", "portal should be closed after the prologue, is %r" % portal_state(g))
    step("portal closed after prologue")

    wizards = [o for o in g.state()["objects"] if o["type"] == "enemy" and o.get("hasDialog")]
    check(len(wizards) == 1, "expected one wizard with a dialog, got %s" % wizards)
    step("interact with %s (id %s)" % (wizards[0]["name"], wizards[0]["id"]))
    r = g.interact(wizards[0]["id"])
    check(r["reason"] == "dialog", "talking to the wizard did not open a dialog: %s" % r)

    # follow the wizard dialog to the end, accepting the reward screen on the way
    rewards = 0
    for _ in range(40):
        s = g.state()
        if s.get("scene") == "RewardScene":
            g.accept_reward()
            rewards += 1
            continue
        d = s.get("dialog")
        if not d:
            break
        enabled = [o for o in d["options"] if o["enabled"]]
        check(enabled, "dialog without enabled options: %s" % d)
        g.choose(enabled[0]["index"])
    else:
        raise Fail("wizard dialog did not end")
    check(rewards >= 1, "no reward screen during the wizard dialog")
    check(s.get("scene") == "TileMapScene", "expected to be back on the map, scene %s" % s.get("scene"))
    check(portal_state(g) == "active", "portal should be active after the wizard, is %r" % portal_state(g))
    step("portal active after the wizard (%d reward screen)" % rewards)

    step("enter the portal")
    r = g.interact(PORTAL_ID)
    check(r["reason"] in ("sceneChanged", "mapChanged"), "entering the portal did nothing: %s" % r)
    g.wait("onOverworld", 30000)
    g.wait("dialogOpen", 15000)
    s = g.state()
    check(s["location"]["where"] == "overworld", "not on the overworld")
    check(s["location"].get("biome") == "shire", "expected the Shire, biome is %r" % s["location"].get("biome"))
    d = s["dialog"]
    check("green hills of the Shire" in d["text"], "epilogue text not shown: %r" % d["text"])
    enabled = [o for o in d["options"] if o["enabled"]]
    disabled = [o for o in d["options"] if not o["enabled"]]
    check(len(enabled) == 1 and len(disabled) == 2,
          "epilogue options: expected 1 enabled + 2 disabled, got %s" % [(o["label"], o["enabled"]) for o in d["options"]])
    check("tutorial" in enabled[0]["label"], "the enabled option is not the tutorial: %s" % enabled[0]["label"])
    step("overworld, Shire, epilogue options ok")

    g.choose(enabled[0]["index"])
    g.wait("quest=30", 10000)
    s = g.state()
    q30 = [q for q in s["quests"] if q["id"] == 30]
    check(q30 and q30[0]["currentStage"], "quest 30 has no active stage: %s" % q30)
    step("quest 30 active: %s" % q30[0]["currentStage"][:60])


def main():
    global T0
    T0 = time.time()
    keep = "--keep" in sys.argv
    g = gamectl.Game()
    started = False
    if not g.alive():
        step("starting the game (DISPLAY_NUM=%s, port %s)" % (os.environ.get("DISPLAY_NUM", "99"), g.base))
        subprocess.run([GAME_SH, "start", "MiddleEarth"], check=True, stdout=subprocess.DEVNULL)
        subprocess.run([GAME_SH, "wait-menu"], check=True, stdout=subprocess.DEVNULL)
        started = True
    try:
        run(g)
        step("PASS")
        return 0
    except (Fail, gamectl.ApiError) as e:
        step("FAIL: %s" % e)
        try:
            print(gamectl.summary(g.state()))
        except Exception:
            pass
        return 1
    finally:
        if started and not keep:
            subprocess.run([GAME_SH, "stop"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


if __name__ == "__main__":
    sys.exit(main())
