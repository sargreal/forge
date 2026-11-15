"""Shared bits of the dev-API GUI tests: assertions, step log, game start/stop around run(g)."""
import os
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
import gamectl  # noqa: E402

GAME_SH = os.path.join(os.path.dirname(HERE), "game.sh")
T0 = time.time()


class Fail(Exception):
    pass


def check(cond, msg):
    if not cond:
        raise Fail(msg)


def step(msg):
    print("[%5.1fs] %s" % (time.time() - T0, msg), flush=True)


def main(run, plane="MiddleEarth"):
    """Starts a game (unless one answers on the port), calls run(g), stops it unless --keep. Returns the exit code."""
    global T0
    T0 = time.time()
    keep = "--keep" in sys.argv
    g = gamectl.Game()
    started = False
    if not g.alive():
        step("starting the game (DISPLAY_NUM=%s, port %s)" % (os.environ.get("DISPLAY_NUM", "99"), g.base))
        subprocess.run([GAME_SH, "start", plane], check=True, stdout=subprocess.DEVNULL)
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


def _start_duel(g, timeout):
    """Waits for the duel to load, clicks the boss intro, hands the human seat to the AI and waits for turn 1."""
    t_end = time.time() + timeout
    autoplaying = False
    while time.time() < t_end:
        st = g.state()
        if st.get("duelPopup"):  # boss intro
            try:
                g.call("/duel/popup", {})
            except gamectl.ApiError:
                pass
            time.sleep(0.5)
            continue
        d = st.get("duel") or {}
        if d.get("players") and not autoplaying:
            g.duel("autoplay")
            autoplaying = True
        if autoplaying and d.get("turn", 0) >= 1:  # past the coin flip and mulligans (conceding earlier can hang)
            return d
        time.sleep(0.5)
    raise Fail("duel did not load")


def duel_with_spawn(g, enemy, timeout=60, attempts=3):
    """Overworld: spawns `enemy` by name (console), walks into it, hands the human seat to the AI and returns the duel
    state once turn 1 has begun. If a wandering enemy gets there first, that duel is conceded, life and gold are
    restored and it tries again."""
    for attempt in range(attempts):
        s = g.state()
        check(s["location"].get("where") == "overworld", "duel_with_spawn needs the overworld: %s" % s["location"])
        before = s["player"]
        g.console("remove enemy all")  # so no wandering enemy gets in the way
        g.console("hide 60")           # and none walks up to the player meanwhile
        out = g.console("spawn enemy '%s'" % enemy).get("output", "")
        check("Spawn" in out, "could not spawn %s: %s" % (enemy, out))
        t_end = time.time() + timeout
        while time.time() < t_end:
            s = g.state()
            if s["scene"] == "DuelScene":
                break
            es = [o for o in s.get("objects", []) if o["type"] == "enemy" and o.get("enemy") == enemy]
            check(es, "spawned %s not on the map" % enemy)
            g.walk(es[0]["x"], es[0]["y"], unit="px", timeout_ms=4000)
        else:
            raise Fail("no duel with %s" % enemy)
        d = _start_duel(g, timeout)
        names = [p["name"] for p in d["players"]]
        if enemy in names:
            return d
        step("a wandering enemy got there first (%s): conceding, retrying" % names)
        g.duel("concede")
        after = leave_duel(g)["player"]
        g.console("heal full")
        if after["gold"] < before["gold"]:
            g.console("give gold %d" % (before["gold"] - after["gold"]))
    raise Fail("no duel with %s after %d attempts" % (enemy, attempts))


def leave_duel(g, timeout=60):
    """Waits until the duel scene is gone, clicking through boss popups (modal dialogs). Returns the state."""
    t_end = time.time() + timeout
    while time.time() < t_end:
        s = g.state()
        if s.get("duelPopup"):  # boss insult after a loss
            try:
                g.call("/duel/popup", {})
            except gamectl.ApiError:
                pass
            time.sleep(0.5)
        elif s.get("modal"):
            try:
                g.choose(0)
            except gamectl.ApiError:
                pass
        elif s["scene"] != "DuelScene" and not s.get("transition") and not s.get("frozen"):
            time.sleep(2.5)  # the stage applies the result after the hit animation
            return g.state()
        time.sleep(0.5)
    raise Fail("still in the duel after %ss" % timeout)
