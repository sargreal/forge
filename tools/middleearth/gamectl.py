#!/usr/bin/env python3
"""Drive the adventure client through its dev API (standard library only).

The game must run with the dev API enabled: `tools/middleearth/game.sh start` does that
(port 8700 + DISPLAY_NUM, see `game.sh api`). Docs: docs/MiddleEarth/DEVELOPMENT.md,
"Driving the game via the dev API".

CLI examples:
  gamectl.py state                 # short summary; --json for raw data, --radius PX for overworld objects
  gamectl.py newgame --plane MiddleEarth --difficulty Normal --colors W --mode Constructed
  gamectl.py choose 0              # dialog option 0
  gamectl.py close                 # close the dialog
  gamectl.py walk 12 30            # walk to tile (12,30); --px for pixels
  gamectl.py interact 69           # walk into map object 69 (NPC, portal, chest...)
  gamectl.py interact --poi "Hobbiton"   # overworld: walk into a POI
  gamectl.py teleport 12 30
  gamectl.py console "give gold 100"
  gamectl.py wait dialogOpen --timeout 10000
  gamectl.py reward                # accept the reward screen
  gamectl.py key Escape --hold 100
  gamectl.py screenshot /abs/path.png
  gamectl.py duel concede|autoplay

As a module:
  import gamectl; g = gamectl.Game(); s = g.state(); g.choose(0); g.wait("dialogOpen")
"""
import argparse
import json
import os
import sys
import urllib.error
import urllib.request


def default_port():
    if os.environ.get("FORGE_DEVAPI_PORT"):
        return int(os.environ["FORGE_DEVAPI_PORT"])
    return 8700 + int(os.environ.get("DISPLAY_NUM", "99"))


class ApiError(Exception):
    pass


class Game:
    def __init__(self, port=None, host="127.0.0.1"):
        self.base = "http://%s:%d" % (host, port or default_port())

    def call(self, path, body=None, timeout=200):
        data = json.dumps(body or {}).encode()
        req = urllib.request.Request(self.base + path, data=data, method="POST",
                                     headers={"Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                return json.loads(r.read().decode())
        except urllib.error.HTTPError as e:
            try:
                msg = json.loads(e.read().decode()).get("error")
            except Exception:
                msg = str(e)
            raise ApiError("%s: %s" % (path, msg))
        except (urllib.error.URLError, ConnectionError) as e:
            raise ApiError("cannot reach the dev API at %s (%s). Is the game running with game.sh start?" % (self.base, e))

    def alive(self):
        try:
            self.call("/state", timeout=5)
            return True
        except ApiError:
            return False

    # endpoints
    def state(self, radius=0):
        return self.call("/state", {"radius": radius} if radius else {})

    def new_game(self, **kw):
        return self.call("/newGame", {k: v for k, v in kw.items() if v is not None}, timeout=300)

    def choose(self, index):
        return self.call("/dialog/choose", {"index": index})

    def close_dialog(self):
        return self.call("/dialog/close")

    def accept_reward(self):
        return self.call("/reward/accept")

    def walk(self, x, y, unit="tile", timeout_ms=30000, pathfinding=True):
        return self.call("/walkTo", {"x": x, "y": y, "unit": unit, "timeoutMs": timeout_ms, "pathfinding": pathfinding},
                         timeout=timeout_ms / 1000 + 30)

    def interact(self, object_id=None, poi=None, timeout_ms=30000):
        body = {"timeoutMs": timeout_ms}
        if object_id is not None:
            body["objectId"] = object_id
        if poi is not None:
            body["poi"] = poi
        return self.call("/interact", body, timeout=timeout_ms / 1000 + 30)

    def teleport(self, x, y, unit="tile"):
        return self.call("/teleport", {"x": x, "y": y, "unit": unit})

    def console(self, command):
        return self.call("/console", {"command": command})

    def key(self, key, hold_ms=100):
        return self.call("/key", {"key": key, "holdMs": hold_ms})

    def wait(self, condition, timeout_ms=10000, check=True):
        r = self.call("/wait", {"condition": condition, "timeoutMs": timeout_ms}, timeout=timeout_ms / 1000 + 30)
        if check and not r.get("ok"):
            raise ApiError(r.get("error", "wait failed"))
        return r

    def screenshot(self, path):
        return self.call("/screenshot", {"path": os.path.abspath(path)})

    def duel(self, action):
        return self.call("/duel/" + action)

    # helpers for tests
    def dialog(self):
        return self.state().get("dialog")

    def objects(self, **match):
        out = []
        for o in self.state().get("objects", []):
            if all(o.get(k) == v for k, v in match.items()):
                out.append(o)
        return out


def summary(s):
    """Compact text summary of /state."""
    lines = []
    head = "scene=%s" % s.get("scene")
    if s.get("transition"):
        head += " (transition)"
    if s.get("frozen"):
        head += " (frozen)"
    head += " plane=%s" % s.get("plane")
    lines.append(head)
    p = s.get("player")
    if p:
        deck = p.get("deck") or {}
        lines.append("player %s  life %s/%s  gold %s  shards %s  deck %s (%s)  %s" % (
            p.get("name"), p.get("life"), p.get("maxLife"), p.get("gold"), p.get("shards"),
            deck.get("name"), deck.get("size"), p.get("difficulty", "")))
    loc = s.get("location")
    if loc:
        pos = loc["pos"]
        where = loc["where"]
        if where == "map":
            poi = loc.get("poi") or {}
            lines.append("in map %s  poi=%s (%s, %s)  tile %d,%d  px %.0f,%.0f" % (
                loc.get("map"), poi.get("displayName"), poi.get("name"), poi.get("type"),
                pos["tile"]["x"], pos["tile"]["y"], pos["px"]["x"], pos["px"]["y"]))
            if loc.get("mapFlags"):
                lines.append("mapFlags %s" % loc["mapFlags"])
        else:
            lines.append("overworld  biome=%s  %s  tile %d,%d  px %.0f,%.0f (teleport to takes px)" % (
                loc.get("biome"), loc.get("locationName", ""), pos["tile"]["x"], pos["tile"]["y"],
                pos["px"]["x"], pos["px"]["y"]))
    for k in ("locationError", "questsError"):
        if s.get(k):
            lines.append("%s: %s" % (k, s[k]))
    for q in s.get("quests", []):
        lines.append("quest %s %s: %s" % (q["id"], q["name"], q.get("currentStage")))
    if s.get("questFlags"):
        lines.append("questFlags %s" % s["questFlags"])
    if s.get("characterFlags"):
        lines.append("characterFlags %s" % s["characterFlags"])
    for key in ("dialog", "modal"):
        d = s.get(key)
        if d:
            lines.append("%s%s: %s" % (key.upper(), " [" + d["speaker"] + "]" if d.get("speaker") else "",
                                       d["text"].replace("\n", " / ")))
            for o in d["options"]:
                lines.append("  %d) %s%s" % (o["index"], o["label"], "" if o["enabled"] else "  (disabled)"))
    r = s.get("reward")
    if r:
        lines.append("REWARD %s: %s" % (r["type"], ", ".join("%s %s x%s" % (i["type"], i["name"] or "", i["count"]) for i in r["items"])))
    du = s.get("duel")
    if du:
        if du.get("players"):
            lines.append("DUEL turn %s %s%s: %s" % (du["turn"], du["phase"], " (over)" if du["gameOver"] else "",
                                                    ", ".join("%s %s%s" % (p["name"], p["life"], " (AI)" if p["ai"] else "") for p in du["players"])))
        else:
            lines.append("DUEL loading")
    objs = s.get("objects") or []
    if objs:
        lines.append("objects:")
        for o in objs[:40]:
            if o["type"] == "poi":
                lines.append("  poi %s %r (%s) at %.0f,%.0f dist %s" % (o["id"], o["displayName"], o["name"], o["x"], o["y"], o["dist"]))
                continue
            extra = []
            for k in ("name", "enemy", "state", "target"):
                if o.get(k):
                    extra.append("%s=%s" % (k, o[k]))
            for k in ("inactive", "hidden", "deleted"):
                if o.get(k):
                    extra.append(k)
            if o.get("onStage") is False:
                extra.append("offStage")
            lines.append("  %s %s %s at %.0f,%.0f dist %s" % (
                o.get("id", ""), o["type"], " ".join(extra), o["x"], o["y"], o["dist"]))
    return "\n".join(lines)


def brief(r):
    s = r.get("state") or {}
    out = []
    if "reason" in r:
        out.append("reason=%s" % r["reason"])
    if "chosen" in r:
        out.append("chose %r" % r["chosen"])
    if "output" in r:
        out.append(str(r["output"]))
    if s:
        out.append("scene=%s %s %s" % (s.get("scene"), s.get("where", ""), s.get("map", "")))
        if s.get("pos"):
            out.append("tile %d,%d" % (s["pos"]["tile"]["x"], s["pos"]["tile"]["y"]))
        d = s.get("dialog")
        if d:
            out.append("\nDIALOG: " + d["text"].replace("\n", " / "))
            for o in d["options"]:
                out.append("\n  %d) %s%s" % (o["index"], o["label"], "" if o["enabled"] else "  (disabled)"))
    if not r.get("ok", True):
        out.insert(0, "FAILED: %s" % r.get("error", ""))
    return " ".join(out) if out else json.dumps(r)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--port", type=int, default=None)
    ap.add_argument("--json", action="store_true", help="print raw JSON")
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("state"); p.add_argument("--radius", type=float, default=0)
    p = sub.add_parser("newgame")
    for k in ("plane", "name", "race", "gender", "difficulty", "colors", "mode"):
        p.add_argument("--" + k)
    p = sub.add_parser("choose"); p.add_argument("index", type=int)
    sub.add_parser("close")
    sub.add_parser("reward")
    p = sub.add_parser("walk"); p.add_argument("x", type=float); p.add_argument("y", type=float)
    p.add_argument("--px", action="store_true"); p.add_argument("--timeout", type=int, default=30000)
    p.add_argument("--no-path", action="store_true")
    p = sub.add_parser("interact"); p.add_argument("objectId", type=int, nargs="?"); p.add_argument("--poi")
    p.add_argument("--timeout", type=int, default=30000)
    p = sub.add_parser("teleport"); p.add_argument("x", type=float); p.add_argument("y", type=float); p.add_argument("--px", action="store_true")
    p = sub.add_parser("console"); p.add_argument("command", nargs="+")
    p = sub.add_parser("key"); p.add_argument("key"); p.add_argument("--hold", type=int, default=100)
    p = sub.add_parser("wait"); p.add_argument("condition"); p.add_argument("--timeout", type=int, default=10000)
    p = sub.add_parser("screenshot"); p.add_argument("path")
    p = sub.add_parser("duel"); p.add_argument("action", choices=["concede", "autoplay"])
    a = ap.parse_args(argv)
    g = Game(a.port)
    try:
        c = a.cmd
        if c == "state":
            r = g.state(a.radius)
            print(json.dumps(r, indent=1) if a.json else summary(r))
            return 0
        if c == "newgame":
            r = g.new_game(plane=a.plane, name=a.name, race=a.race, gender=a.gender, difficulty=a.difficulty,
                           colors=a.colors, mode=a.mode)
        elif c == "choose":
            r = g.choose(a.index)
        elif c == "close":
            r = g.close_dialog()
        elif c == "reward":
            r = g.accept_reward()
        elif c == "walk":
            r = g.walk(a.x, a.y, "px" if a.px else "tile", a.timeout, not a.no_path)
        elif c == "interact":
            r = g.interact(a.objectId, a.poi, a.timeout)
        elif c == "teleport":
            r = g.teleport(a.x, a.y, "px" if a.px else "tile")
        elif c == "console":
            r = g.console(" ".join(a.command))
        elif c == "key":
            r = g.key(a.key, a.hold)
        elif c == "wait":
            r = g.wait(a.condition, a.timeout, check=False)
        elif c == "screenshot":
            r = g.screenshot(a.path)
        elif c == "duel":
            r = g.duel(a.action)
        print(json.dumps(r, indent=1) if a.json else brief(r))
        return 0 if r.get("ok", True) else 1
    except ApiError as e:
        print("error: %s" % e, file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
