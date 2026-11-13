#!/usr/bin/env python3
"""AI-vs-AI deck strength harness for the Middle-earth plane.

Called by tools/middleearth/deckstrength.sh (run that with -h for usage). Plays every candidate
deck against a gauntlet of reference decks (default: the official LTR Starter Kit decks) with
Forge's AI on both sides, both play/draw orders, in parallel worker JVMs (DeckSim.java), and
writes CSV + markdown results to .me-test/deckstrength/<timestamp>/.
"""
import argparse
import csv
import datetime as dt
import glob
import math
import os
import random
import re
import shutil
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
RES = os.path.join(ROOT, "forge-gui", "res")
REF_DIR = os.path.join(RES, "adventure", "MiddleEarth", "decks", "reference")
QUIET = re.compile(r"was not assigned to any set|dated in the future|^Read cards:|^Looking for resource|"
                   r"^Parsing from main code|^Attempting to lookup|^Correcting zone|JAVA_TOOL_OPTIONS")

# Score bands relative to the gauntlet. Score = (wins + draws/2) / games.
BANDS = [
    (0.00, 0.30, "far too weak"),
    (0.30, 0.42, "weaker than the starter kits"),
    (0.42, 0.58, "on par with the starter kits"),
    (0.58, 0.70, "stronger than the starter kits"),
    (0.70, 1.01, "far too strong"),
]
# Rough enemy tiers on the same scale (vs the starter-kit gauntlet at 20 life each). See DEVELOPMENT.md.
ENEMY_TIERS = [
    (0.00, 0.30, "easy"),
    (0.30, 0.50, "normal"),
    (0.50, 0.70, "hard"),
    (0.70, 1.01, "elite / boss"),
]


def band(score, table):
    for lo, hi, name in table:
        if lo <= score < hi:
            return name
    return table[-1][2]


def wilson(k, n, z=1.96):
    """95% Wilson score interval for k successes out of n (k may be fractional, draws = 1/2)."""
    if n == 0:
        return (0.0, 1.0)
    p = k / n
    d = 1 + z * z / n
    c = (p + z * z / (2 * n)) / d
    h = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / d
    return (max(0.0, c - h), min(1.0, c + h))


def find_jar(env, pattern_list):
    if os.environ.get(env):
        return os.environ[env]
    for pat in pattern_list:
        hits = sorted(glob.glob(pat))
        if hits:
            return hits[0]
    return None


def deck_name(path):
    try:
        with open(path, encoding="utf-8") as f:
            for line in f:
                if line.startswith("Name="):
                    return line[5:].strip()
                if line.startswith("[Main]"):
                    break
    except OSError:
        pass
    return os.path.splitext(os.path.basename(path))[0]


def deck_size(path):
    n, main = 0, False
    with open(path, encoding="utf-8") as f:
        for line in f:
            s = line.strip()
            if s.startswith("["):
                main = s.lower() == "[main]"
            elif main and s and s[0].isdigit():
                n += int(s.split(" ", 1)[0])
    return n


def log(msg):
    print(msg, flush=True)


# ---------------------------------------------------------------------------------- inputs

def adventure_relative(path):
    """decks/... path as the plane data uses it, for a file under res/adventure/<plane>/."""
    ap = os.path.abspath(path)
    adv = os.path.join(RES, "adventure") + os.sep
    if ap.startswith(adv):
        parts = ap[len(adv):].split(os.sep)
        return "/".join(parts[1:])
    return path


def resolve_inputs(items, out_decks, samples, plane, run_dir):
    """Return a list of dicts {path, name, life, label}. .dck files are copied into out_decks."""
    plain, export = [], []
    for it in items:
        if it.startswith("enemy:"):
            export.append(it)
        elif os.path.isdir(it):
            plain += sorted(glob.glob(os.path.join(it, "*.dck")))
        elif it.endswith(".json"):
            export.append(adventure_relative(it))
        elif it.endswith(".dck"):
            if not os.path.exists(it):
                sys.exit("no such deck file: " + it)
            plain.append(it)
        else:
            sys.exit("don't know how to read " + it + " (want .dck, .json, enemy:<Name> or a directory)")
    decks = []
    for p in plain:
        decks.append(dict(src=os.path.abspath(p), name=deck_name(p), life=0, label=os.path.relpath(p, ROOT)))
    if export:
        decks += export_decks(export, out_decks, samples, plane, run_dir)
    used = set()
    for d in decks:
        base = re.sub(r"[^A-Za-z0-9_.-]+", "_", os.path.basename(d["src"]))
        while base in used:
            base = "_" + base
        used.add(base)
        dst = os.path.join(out_decks, base)
        if os.path.abspath(d["src"]) != os.path.abspath(dst):
            shutil.copyfile(d["src"], dst)
        d["path"] = dst
        d["size"] = deck_size(dst)
    return decks


def export_decks(items, out_decks, samples, plane, run_dir):
    jar = find_jar("FORGE_JAR", [
        os.path.join(ROOT, "forge-gui-mobile-dev/target/forge-gui-mobile-dev-*-jar-with-dependencies.jar"),
        "/home/user/forge/forge-gui-mobile-dev/target/forge-gui-mobile-dev-*-jar-with-dependencies.jar"])
    if not jar:
        sys.exit("JSON / enemy: inputs need the adventure fat jar: tools/middleearth/game.sh build (or set FORGE_JAR)")
    home = os.path.join(run_dir, "home-export")
    os.makedirs(os.path.join(home, ".forge", "adventure"), exist_ok=True)
    with open(os.path.join(home, ".forge", "adventure", "settings.json"), "w") as f:
        f.write('{"plane":"%s"}' % plane)
    exp_dir = os.path.join(run_dir, "exported")
    cmd = ["java", "-Xmx2g", "-Djava.awt.headless=true", "-Duser.home=" + home, "-cp", jar,
           os.path.join(HERE, "DeckExport.java"), exp_dir, str(samples)] + items
    log("exporting adventure decks: " + " ".join(items))
    with open(os.path.join(run_dir, "logs", "export.log"), "w") as lf:
        p = subprocess.run(cmd, cwd=os.path.join(ROOT, "forge-gui"), stdout=subprocess.PIPE, stderr=lf, text=True)
    out = []
    for line in p.stdout.splitlines():
        if line.startswith("EXPORT\t"):
            _, f, life, label = line.split("\t")
            out.append(dict(src=f, name=deck_name(f), life=int(life), label=label))
    if p.returncode != 0 or not out:
        sys.exit("deck export failed, see " + os.path.join(run_dir, "logs", "export.log") + "\n" + p.stdout[-2000:])
    return out


# ---------------------------------------------------------------------------------- running

def build_tasks(cands, gauntlet, n, round_robin, seed):
    pairs = []
    if round_robin:
        allp = cands
        for i in range(len(allp)):
            for j in range(i + 1, len(allp)):
                pairs.append((allp[i], allp[j]))
    else:
        for c in cands:
            for g in gauntlet:
                if os.path.realpath(c["src"]) == os.path.realpath(g["src"]):
                    continue
                pairs.append((c, g))
    rng = random.Random(seed)
    tasks = []
    gid = 0
    half = (n + 1) // 2
    for k in range(half):            # interleave so partial results stay balanced
        for a, b in pairs:
            for first in ("A", "B"):
                if first == "B" and k >= n - half:
                    continue
                gid += 1
                tasks.append([str(gid), a["path"], b["path"], first, str(rng.randrange(1 << 40)),
                              str(a["start_life"]), str(b["start_life"])])
    return pairs, tasks


def run_workers(run_dir, tasks, jobs, timeout, xmx):
    jar = find_jar("FORGE_DESKTOP_JAR", [
        os.path.join(ROOT, "forge-gui-desktop/target/forge-gui-desktop-*-jar-with-dependencies.jar"),
        "/home/user/forge/forge-gui-desktop/target/forge-gui-desktop-*-jar-with-dependencies.jar"])
    if not jar:
        sys.exit("no forge-gui-desktop fat jar; build it with: tools/middleearth/deckstrength.sh build")
    log("engine jar: " + jar)
    home = os.path.join(run_dir, "home")
    os.makedirs(home, exist_ok=True)
    jobs = max(1, min(jobs, len(tasks)))
    workers = []
    for w in range(jobs):
        tf = os.path.join(run_dir, "tasks", "worker%d.tsv" % w)
        if not os.path.exists(tf):
            with open(tf, "w", encoding="utf-8") as f:
                for t in tasks[w::jobs]:
                    f.write("\t".join(t) + "\n")
        workers.append(dict(id=w, tasks=tf, out=os.path.join(run_dir, "results", "worker%d.csv" % w),
                            log=os.path.join(run_dir, "logs", "worker%d.log" % w), proc=None, restarts=0,
                            n=len(tasks[w::jobs])))

    def start(wk):
        cmd = ["java", "-Xmx%s" % xmx, "-XX:+UseSerialGC", "-Djava.awt.headless=true", "-Duser.home=" + home,
               "-cp", jar, os.path.join(HERE, "DeckSim.java"), wk["tasks"], wk["out"], str(timeout)]
        lf = open(wk["log"], "a", encoding="utf-8")
        wk["proc"] = subprocess.Popen(cmd, cwd=os.path.join(ROOT, "forge-gui"), stdout=lf, stderr=subprocess.STDOUT)
        wk["lf"] = lf

    def done_count(wk):
        try:
            with open(wk["out"], encoding="utf-8") as f:
                return sum(1 for l in f if l.strip())
        except OSError:
            return 0

    for wk in workers:
        start(wk)
    total = len(tasks)
    t0 = time.time()
    last = 0
    try:
        while True:
            running = 0
            for wk in workers:
                p = wk["proc"]
                if p is None:
                    continue
                rc = p.poll()
                if rc is None:
                    running += 1
                    continue
                wk["lf"].close()
                wk["proc"] = None
                if done_count(wk) < wk["n"]:
                    if wk["restarts"] < 5:
                        wk["restarts"] += 1
                        log("worker %d exited (code %s) with games left, restarting" % (wk["id"], rc))
                        start(wk)
                        running += 1
                    else:
                        log("worker %d failed too often, giving up on its remaining games; see %s" % (wk["id"], wk["log"]))
            done = sum(done_count(wk) for wk in workers)
            if time.time() - last > 20 or running == 0:
                el = time.time() - t0
                eta = el / done * (total - done) if done else 0
                log("  %d/%d games, %.0f s elapsed, ~%.0f s left" % (done, total, el, eta))
                last = time.time()
            if running == 0:
                break
            time.sleep(2)
    except KeyboardInterrupt:
        for wk in workers:
            if wk["proc"]:
                wk["proc"].terminate()
        raise
    return time.time() - t0


# ---------------------------------------------------------------------------------- report

def collect(run_dir, tasks):
    by_id = {t[0]: t for t in tasks}
    games = []
    for f in sorted(glob.glob(os.path.join(run_dir, "results", "worker*.csv"))):
        with open(f, encoding="utf-8") as fh:
            for row in csv.reader(fh):
                if not row or row[0] not in by_id:
                    continue
                t = by_id[row[0]]
                games.append(dict(id=int(row[0]), a=t[1], b=t[2], first=row[1], result=row[2], reason=row[3],
                                  turns=int(row[4]), ms=int(row[5]), started=row[6] if len(row) > 6 else "?"))
    games.sort(key=lambda g: g["id"])
    return games


class Acc:
    def __init__(self):
        self.n = self.w = self.l = self.d = self.timeouts = self.errors = 0
        self.turns = self.ms = 0
        self.play_n = self.play_w = self.draw_n = self.draw_w = 0

    def add(self, g, me):
        self.n += 1
        win = g["result"] == me
        loss = g["result"] not in (me, "draw")
        self.w += win
        self.l += loss
        self.d += g["result"] == "draw"
        self.timeouts += g["reason"] in ("timeout", "stuck")
        self.errors += g["reason"] in ("error", "deckerror")
        self.turns += g["turns"]
        self.ms += g["ms"]
        if g["first"] == me:
            self.play_n += 1
            self.play_w += win + 0.5 * (g["result"] == "draw")
        else:
            self.draw_n += 1
            self.draw_w += win + 0.5 * (g["result"] == "draw")

    @property
    def score(self):
        return (self.w + 0.5 * self.d) / self.n if self.n else 0.0

    def ci(self):
        return wilson(self.w + 0.5 * self.d, self.n)


def pct(x):
    return "%.0f%%" % (100 * x)


def report(run_dir, decks, gauntlet, pairs, games, args, elapsed):
    by_path = {d["path"]: d for d in decks + gauntlet}
    focus = decks  # rows of the report
    rows_csv = []
    md = []
    md.append("# Deck strength run %s\n" % os.path.basename(run_dir))
    mode = "round robin" if args.round_robin else "vs gauntlet"
    md.append("- Mode: %s, %d games per pairing (half on the play, half on the draw), timeout %d s, %d workers"
              % (mode, args.games, args.timeout, args.jobs))
    if not args.round_robin:
        md.append("- Gauntlet: " + ", ".join("%s (%d cards)" % (g["name"], g["size"]) for g in gauntlet))
    md.append("- Starting life: candidate %s, gauntlet %s" % (
        "enemy's own" if args.life == "enemy" else (args.life or "20"), args.opp_life or "20"))
    md.append("- %d games in %.0f s wall time (%.1f s per game per worker)\n"
              % (len(games), elapsed, elapsed * args.jobs / max(1, len(games))))
    md.append("Score = (wins + draws/2) / games. 50% = as strong as the gauntlet on average. "
              "CI = 95% Wilson interval. Turns = Forge turn counter (both players' turns).\n")

    md.append("## Overall\n")
    md.append("| Deck | Cards | Games | W-L-D | Score | 95% CI | On play | On draw | Avg turns | Timeouts | Rating | Enemy tier |")
    md.append("| --- | ---: | ---: | --- | ---: | --- | ---: | ---: | ---: | ---: | --- | --- |")
    per_opp = []
    for d in focus:
        tot = Acc()
        opps = {}
        for g in games:
            if g["a"] == d["path"]:
                me, opp = "A", g["b"]
            elif g["b"] == d["path"]:
                me, opp = "B", g["a"]
            else:
                continue
            tot.add(g, me)
            opps.setdefault(opp, Acc()).add(g, me)
        if not tot.n:
            continue
        lo, hi = tot.ci()
        rating = band(tot.score, BANDS)
        if band(lo, BANDS) != band(hi, BANDS):
            rating += " (uncertain: CI spans %s .. %s)" % (band(lo, BANDS), band(hi, BANDS))
        tier = band(tot.score, ENEMY_TIERS)
        md.append("| %s | %d | %d | %d-%d-%d | **%s** | %s–%s | %s | %s | %.1f | %d | %s | %s |" % (
            d["name"], d["size"], tot.n, tot.w, tot.l, tot.d, pct(tot.score), pct(lo), pct(hi),
            pct(tot.play_w / tot.play_n) if tot.play_n else "-", pct(tot.draw_w / tot.draw_n) if tot.draw_n else "-",
            tot.turns / tot.n, tot.timeouts, rating, tier))
        rows_csv.append(dict(deck=d["name"], file=d["label"], cards=d["size"], opponent="ALL", games=tot.n,
                             wins=tot.w, losses=tot.l, draws=tot.d, score="%.3f" % tot.score, ci_low="%.3f" % lo,
                             ci_high="%.3f" % hi, avg_turns="%.1f" % (tot.turns / tot.n),
                             avg_ms=int(tot.ms / tot.n), timeouts=tot.timeouts, errors=tot.errors, rating=rating))
        for opp, a in sorted(opps.items(), key=lambda kv: by_path[kv[0]]["name"]):
            olo, ohi = a.ci()
            per_opp.append("| %s | %s | %d | %d-%d-%d | %s | %s–%s | %.1f | %d |" % (
                d["name"], by_path[opp]["name"], a.n, a.w, a.l, a.d, pct(a.score), pct(olo), pct(ohi),
                a.turns / a.n, a.timeouts))
            rows_csv.append(dict(deck=d["name"], file=d["label"], cards=d["size"], opponent=by_path[opp]["name"],
                                 games=a.n, wins=a.w, losses=a.l, draws=a.d, score="%.3f" % a.score,
                                 ci_low="%.3f" % olo, ci_high="%.3f" % ohi, avg_turns="%.1f" % (a.turns / a.n),
                                 avg_ms=int(a.ms / a.n), timeouts=a.timeouts, errors=a.errors, rating=""))
    md.append("\n## Per opponent\n")
    md.append("| Deck | Opponent | Games | W-L-D | Score | 95% CI | Avg turns | Timeouts |")
    md.append("| --- | --- | ---: | --- | ---: | --- | ---: | ---: |")
    md += per_opp
    bad_start = sum(1 for g in games if g["started"] not in ("?", g["first"]))
    allfirst = Acc()
    for g in games:
        allfirst.add(g, g["first"])
    md.append("\n## Sanity\n")
    md.append("- Player on the play won %s of all games (%d games)." % (pct(allfirst.score), allfirst.n))
    md.append("- Games where the designated player did not go first: %d." % bad_start)
    md.append("- Timeouts/stuck: %d, errors: %d, draws: %d." % (
        sum(g["reason"] in ("timeout", "stuck") for g in games), sum(g["reason"] in ("error", "deckerror") for g in games),
        sum(g["result"] == "draw" for g in games)))
    md.append("\n## Rating bands\n")
    md.append("Rating (starter decks): " + "; ".join("%s–%s %s" % (pct(lo), pct(min(hi, 1)), n) for lo, hi, n in BANDS) + ".")
    md.append("Enemy tier (rough): " + "; ".join("%s–%s %s" % (pct(lo), pct(min(hi, 1)), n) for lo, hi, n in ENEMY_TIERS) + ".")

    with open(os.path.join(run_dir, "summary.md"), "w", encoding="utf-8") as f:
        f.write("\n".join(md) + "\n")
    with open(os.path.join(run_dir, "summary.csv"), "w", newline="", encoding="utf-8") as f:
        if rows_csv:
            w = csv.DictWriter(f, fieldnames=list(rows_csv[0].keys()))
            w.writeheader()
            w.writerows(rows_csv)
    with open(os.path.join(run_dir, "games.csv"), "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["id", "deck_a", "deck_b", "first", "result", "reason", "turns", "ms", "started"])
        for g in games:
            w.writerow([g["id"], by_path[g["a"]]["name"], by_path[g["b"]]["name"], g["first"], g["result"],
                        g["reason"], g["turns"], g["ms"], g["started"]])
    print("\n".join(md[:md.index("## Per opponent") - 1] if "## Per opponent" in md else md))
    log("\nresults: " + run_dir + "/summary.md (summary.csv, games.csv)")


# ---------------------------------------------------------------------------------- main

def main():
    ap = argparse.ArgumentParser(prog="deckstrength.sh", formatter_class=argparse.RawDescriptionHelpFormatter,
                                 description=__doc__, epilog="""examples:
  tools/middleearth/deckstrength.sh my_deck.dck                    # vs the LTR Starter Kit gauntlet
  tools/middleearth/deckstrength.sh -n 100 a.dck b.dck             # more games, two candidates
  tools/middleearth/deckstrength.sh decks/starter/white_e.json     # adventure JSON deck (3 samples)
  tools/middleearth/deckstrength.sh --enemy-life enemy:Bandit      # an enemy, with its own life total
  tools/middleearth/deckstrength.sh --round-robin -n 40 DIR        # every deck in DIR vs every other
  tools/middleearth/deckstrength.sh build                          # build the engine jar first
""")
    ap.add_argument("inputs", nargs="+", help=".dck files, adventure .json decks, enemy:<Name>, or directories of .dck")
    ap.add_argument("-n", "--games", type=int, default=int(os.environ.get("DS_GAMES", 60)),
                    help="games per pairing, split evenly between play and draw (default 60)")
    ap.add_argument("-j", "--jobs", type=int, default=int(os.environ.get("DS_JOBS", 3)), help="parallel game JVMs (default 3)")
    ap.add_argument("-t", "--timeout", type=int, default=120, help="per-game timeout in seconds, counted as a draw (default 120)")
    ap.add_argument("-g", "--gauntlet", nargs="+", default=None,
                    help="opponent decks or dirs (default: the .dck files in MiddleEarth/decks/reference/)")
    ap.add_argument("--round-robin", action="store_true", help="play the inputs against each other instead of a gauntlet")
    ap.add_argument("--samples", type=int, default=3, help="decks generated per adventure .json input (default 3)")
    ap.add_argument("--life", default="", help="starting life of the candidate decks (default 20)")
    ap.add_argument("--enemy-life", action="store_true", help="candidates from enemy:<Name> start with the enemy's life")
    ap.add_argument("--opp-life", default="", help="starting life of the gauntlet decks (default 20)")
    ap.add_argument("--plane", default="MiddleEarth", help="plane used to resolve .json / enemy: inputs")
    ap.add_argument("--seed", type=int, default=None)
    ap.add_argument("--xmx", default="1536m", help="heap per worker JVM (default 1536m)")
    ap.add_argument("-o", "--out", default=None, help="output dir (default .me-test/deckstrength/<timestamp>)")
    args = ap.parse_args()
    if args.enemy_life:
        args.life = "enemy"

    stamp = dt.datetime.now().strftime("%Y%m%d-%H%M%S")
    run_dir = os.path.abspath(args.out or os.path.join(ROOT, ".me-test", "deckstrength", stamp))
    for sub in ("decks", "tasks", "results", "logs"):
        os.makedirs(os.path.join(run_dir, sub), exist_ok=True)
    seed = args.seed if args.seed is not None else random.randrange(1 << 30)

    cands = resolve_inputs(args.inputs, os.path.join(run_dir, "decks"), args.samples, args.plane, run_dir)
    gauntlet = []
    if not args.round_robin:
        gdir = os.path.join(run_dir, "decks", "gauntlet")
        os.makedirs(gdir, exist_ok=True)
        gauntlet = resolve_inputs(args.gauntlet or [REF_DIR], gdir, args.samples, args.plane, run_dir)
        # the same file given as candidate and gauntlet deck: never play a deck against itself
        cand_srcs = {os.path.realpath(c["src"]) for c in cands}
        for g in gauntlet:
            if os.path.realpath(g["src"]) in cand_srcs:
                log("note: %s is also in the gauntlet; it is not played against itself" % g["name"])
    for d in cands:
        d["start_life"] = d["life"] if args.life == "enemy" else int(args.life or 0)
    for d in gauntlet:
        d["start_life"] = int(args.opp_life or 0)
    pairs, tasks = build_tasks(cands, gauntlet, args.games, args.round_robin, seed)
    if not tasks:
        sys.exit("nothing to play")
    log("%d decks, %d pairings, %d games, seed %d -> %s" % (len(cands), len(pairs), len(tasks), seed, run_dir))
    with open(os.path.join(run_dir, "decks.csv"), "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["role", "name", "cards", "life", "source", "file"])
        for d in cands:
            w.writerow(["candidate", d["name"], d["size"], d["life"], d["label"], d["path"]])
        for d in gauntlet:
            w.writerow(["gauntlet", d["name"], d["size"], d["life"], d["label"], d["path"]])
    with open(os.path.join(run_dir, "command.txt"), "w") as f:
        f.write(" ".join(sys.argv) + "\nseed=%d\n" % seed)

    elapsed = run_workers(run_dir, tasks, args.jobs, args.timeout, args.xmx)
    games = collect(run_dir, tasks)
    report(run_dir, cands, gauntlet, pairs, games, args, elapsed)


if __name__ == "__main__":
    main()
