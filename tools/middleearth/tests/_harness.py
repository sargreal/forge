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
