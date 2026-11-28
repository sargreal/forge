#!/usr/bin/env python3
# /// script
# requires-python = ">=3.10"
# dependencies = ["numpy>=1.24"]
# ///
"""Editor palette for the biome tiles of the Tiled world (biome_palette.json).

The biome colours of the game (biome JSON "color") are many near-identical greens, greys and blues, which makes
the biome tiles hard to tell apart in Tiled. This script picks one clearly distinct colour per biome for editing
only and writes forge-gui/res/adventure/MiddleEarth/world/middleearth/biome_palette.json:

    {"_comment": "...", "<biome name>": {"color": "#rrggbb", "label": "ABC"}, ...}

WorldMapTmx (exportWorldTmx, and the BiomeTiles tool this script calls) draws biomes.png and overview.png in these
colours, with each tile's label in a 3x5 pixel font. The game's own biome colours (minimap etc.) are unchanged, and
reading the map never uses colours: tiles are matched by their tile property "biome".

How the colours are chosen:
  * Each biome belongs to a family that keeps the map readable: water (ocean only, blue), snow, forest (greens),
    plains/grassland (light colours of many hues, never water-blue), desert/Harad (yellow/orange/tan), mountains
    (warm reds, oranges, browns), shadow (Mordor: dark or purple). See FAMILY below; unknown biomes default to plains.
  * Candidates: an sRGB grid (24 levels per channel), filtered per family.
  * Greedy max-min: the most constrained biome takes the candidate farthest (CIEDE2000) from every colour chosen so far,
    then a few improvement passes re-pick each biome's colour against all others. Deterministic.
  * It reports the closest pair overall and per family (Delta E 2000).

Usage:
    uv run tools/middleearth/worldmap/make_biome_palette.py            # write the palette and regenerate the tiles
    uv run tools/middleearth/worldmap/make_biome_palette.py --dry-run  # only report
    ... --no-render   # write the palette but don't call the Java writer (needs the fat jar: game.sh build)

Labels and fixed colours: LABEL below; to pin a colour by hand, edit the JSON and run with --keep (existing entries
stay, only missing biomes are picked).
"""
import argparse
import json
import os
import subprocess
import sys

import numpy as np

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
PLANE = os.path.join(ROOT, "forge-gui", "res", "adventure", "MiddleEarth")

FAMILY = {
    "ocean": "water",
    "northern_waste": "snow",
    "fangorn": "forest", "old_forest": "forest", "lothlorien": "forest", "mirkwood": "forest", "mirkwood_south": "forest", "rivendell": "forest",
    "harad_waith": "desert", "south_gondor": "desert", "umbar": "desert", "brown_lands": "desert",
    "mordor": "shadow", "ered_lithui": "shadow", "ered_lithui_east": "shadow", "ephel_duath_1": "shadow",
    "ephel_duath_mid": "shadow", "isengard": "shadow", "ephel_duath_2": "shadow", "dead_marshes": "shadow",
    **{n: "mountain" for n in ["ered_luin", "ered_luin_south", "ered_mithrin", "ered_mithrin_east", "misty_mountains_north",
                               "misty_mountains", "misty_mountains_moria", "misty_mountains_south", "erebor", "iron_hills",
                               "ered_nimrais_west", "ered_nimrais", "ered_nimrais_east", "orocarni", "withered_heath"]},
    # everything else: plains (eriador, wilderland, anduin_vale, rhun, minhiriath, enedwaith, gondor, belfalas, tolfalas,
    # rohan, shire, harlindon, forlindon)
}

LABEL = {
    "ocean": "OCN", "northern_waste": "NW", "eriador": "ERI", "wilderland": "WIL", "anduin_vale": "AV", "brown_lands": "BL",
    "rhun": "RHN", "minhiriath": "MIN", "enedwaith": "EDW", "harad_waith": "HAR", "south_gondor": "SG", "umbar": "UMB",
    "gondor": "GON", "belfalas": "BEL", "tolfalas": "TOL", "rohan": "ROH", "mordor": "MOR", "fangorn": "FAN",
    "lothlorien": "LOR", "mirkwood": "MK", "mirkwood_south": "MKS", "rivendell": "RIV", "shire": "SHI", "harlindon": "HLN",
    "forlindon": "FLN", "ered_luin": "EL", "ered_luin_south": "ELS", "ered_mithrin": "EM", "ered_mithrin_east": "EME",
    "misty_mountains_north": "MMN", "misty_mountains": "MM", "misty_mountains_moria": "MOA", "misty_mountains_south": "MMS",
    "erebor": "ERE", "iron_hills": "IH", "ered_nimrais_west": "ENW", "ered_nimrais": "EN", "ered_nimrais_east": "ENE",
    "ered_lithui": "LI", "ered_lithui_east": "LIE", "ephel_duath_1": "ED1", "ephel_duath_mid": "EDM", "ephel_duath_2": "ED2",
    "dead_marshes": "DM",
    "dagorlad": "DAG", "orocarni": "ORO", "emyn_muil": "EMU", "old_forest": "OLF", "trollshaws": "TRS", "weather_hills": "WEA",
    "arnor": "ARN", "ithilien": "ITH", "withered_heath": "WHE", "dunland": "DUN", "isengard": "ISE",
}


# ---------------------------------------------------------------- colour science
def srgb_to_lab(rgb):
    """rgb: (..., 3) in 0..1 -> CIELAB (D65)."""
    c = np.where(rgb <= 0.04045, rgb / 12.92, ((rgb + 0.055) / 1.055) ** 2.4)
    m = np.array([[0.4124564, 0.3575761, 0.1804375], [0.2126729, 0.7151522, 0.0721750], [0.0193339, 0.1191920, 0.9503041]])
    xyz = c @ m.T / np.array([0.95047, 1.0, 1.08883])
    f = np.where(xyz > (6 / 29) ** 3, np.cbrt(xyz), xyz / (3 * (6 / 29) ** 2) + 4 / 29)
    return np.stack([116 * f[..., 1] - 16, 500 * (f[..., 0] - f[..., 1]), 200 * (f[..., 1] - f[..., 2])], axis=-1)


def de2000(lab1, lab2):
    """CIEDE2000 between broadcastable (..., 3) arrays."""
    L1, a1, b1 = lab1[..., 0], lab1[..., 1], lab1[..., 2]
    L2, a2, b2 = lab2[..., 0], lab2[..., 1], lab2[..., 2]
    C1, C2 = np.hypot(a1, b1), np.hypot(a2, b2)
    Cm = (C1 + C2) / 2
    G = 0.5 * (1 - np.sqrt(Cm ** 7 / (Cm ** 7 + 25.0 ** 7)))
    a1p, a2p = (1 + G) * a1, (1 + G) * a2
    C1p, C2p = np.hypot(a1p, b1), np.hypot(a2p, b2)
    h1p = np.degrees(np.arctan2(b1, a1p)) % 360
    h2p = np.degrees(np.arctan2(b2, a2p)) % 360
    dLp, dCp = L2 - L1, C2p - C1p
    dh = h2p - h1p
    dh = np.where(dh > 180, dh - 360, np.where(dh < -180, dh + 360, dh))
    dh = np.where(C1p * C2p == 0, 0, dh)
    dHp = 2 * np.sqrt(C1p * C2p) * np.sin(np.radians(dh / 2))
    Lpm, Cpm = (L1 + L2) / 2, (C1p + C2p) / 2
    hs = h1p + h2p
    hpm = np.where(C1p * C2p == 0, hs, np.where(np.abs(h1p - h2p) <= 180, hs / 2, np.where(hs < 360, (hs + 360) / 2, (hs - 360) / 2)))
    T = (1 - 0.17 * np.cos(np.radians(hpm - 30)) + 0.24 * np.cos(np.radians(2 * hpm)) + 0.32 * np.cos(np.radians(3 * hpm + 6))
         - 0.20 * np.cos(np.radians(4 * hpm - 63)))
    dtheta = 30 * np.exp(-(((hpm - 275) / 25) ** 2))
    Rc = 2 * np.sqrt(Cpm ** 7 / (Cpm ** 7 + 25.0 ** 7))
    Sl = 1 + 0.015 * (Lpm - 50) ** 2 / np.sqrt(20 + (Lpm - 50) ** 2)
    Sc, Sh = 1 + 0.045 * Cpm, 1 + 0.015 * Cpm * T
    Rt = -np.sin(np.radians(2 * dtheta)) * Rc
    return np.sqrt((dLp / Sl) ** 2 + (dCp / Sc) ** 2 + (dHp / Sh) ** 2 + Rt * (dCp / Sc) * (dHp / Sh))


# ---------------------------------------------------------------- candidates per family
def candidates():
    levels = np.linspace(0, 1, 24)
    rgb = np.stack(np.meshgrid(levels, levels, levels, indexing="ij"), axis=-1).reshape(-1, 3)
    lab = srgb_to_lab(rgb)
    L = lab[:, 0]
    C = np.hypot(lab[:, 1], lab[:, 2])
    h = np.degrees(np.arctan2(lab[:, 2], lab[:, 1])) % 360  # CIELAB hue: red ~30-40, orange ~55-70, yellow ~95, green ~135, cyan ~195, blue ~285-306, purple/magenta ~315-330
    fam = {
        "water": (h >= 260) & (h <= 290) & (L >= 38) & (L <= 50) & (C >= 50),
        "snow": (L >= 93) & (C <= 6),
        "forest": (h >= 115) & (h <= 160) & (L >= 22) & (L <= 62) & (C >= 25),
        "plains": (L >= 72) & (L <= 93) & (C >= 18) & (C <= 55) & ~((h >= 50) & (h < 85)) & ~((h >= 160) & (h < 315)),
        "desert": (h >= 60) & (h <= 100) & (L >= 55) & (L <= 88) & (C >= 25),
        "mountain": (h >= 20) & (h <= 70) & (L >= 25) & (L <= 68) & (C >= 18) & (C <= 62),
        "shadow": ((L <= 22) & (C <= 25) & ~((h >= 220) & (h < 315))) | ((h >= 315) & (h <= 350) & (L >= 15) & (L <= 42) & (C >= 20)),
    }
    return rgb, lab, fam


def pick(names, fixed, rgb, lab, fam, passes=12):
    family = {n: FAMILY.get(n, "plains") for n in names}
    idx = {n: np.nonzero(fam[family[n]])[0] for n in names}
    for n in names:
        if len(idx[n]) == 0:
            sys.exit(f"family {family[n]} has no candidates")
    chosen = {n: lab_of_hex(c) for n, c in fixed.items()}
    chosen_rgb = {n: hex_to_rgb(c) for n, c in fixed.items()}

    def best_for(n, others):
        cand = lab[idx[n]]
        if not others:
            # first colour of its family: the one nearest the family's median (a typical colour of the family)
            d = -np.linalg.norm(cand - np.median(cand, axis=0), axis=1)
        else:
            o = np.array(others)
            d = de2000(cand[:, None, :], o[None, :, :]).min(axis=1)
        k = int(np.argmax(d))
        return idx[n][k], float(d[k])

    todo = [n for n in names if n not in chosen]
    # water first, so the ocean gets a typical blue
    if "ocean" in todo:
        i, _ = best_for("ocean", [])
        chosen["ocean"], chosen_rgb["ocean"] = lab[i], rgb[i]
        todo.remove("ocean")
    while todo:
        others = list(chosen.values())
        scored = [(best_for(n, others), n) for n in todo]
        (i, _), n = min(scored, key=lambda t: (t[0][1], todo.index(t[1])))  # most constrained biome first
        chosen[n], chosen_rgb[n] = lab[i], rgb[i]
        todo.remove(n)

    def global_min():
        keys = list(chosen)
        a = np.array([chosen[k] for k in keys])
        d = de2000(a[:, None, :], a[None, :, :])
        np.fill_diagonal(d, np.inf)
        return float(d.min())

    best = global_min()
    for _ in range(passes):
        improved = False
        for n in names:
            if n in fixed or n == "ocean":
                continue
            others = [v for k, v in chosen.items() if k != n]
            i, dist = best_for(n, others)
            old = de2000(chosen[n][None, :], np.array(others)).min()
            if dist > old + 1e-9:
                prev = chosen[n], chosen_rgb[n]
                chosen[n], chosen_rgb[n] = lab[i], rgb[i]
                g = global_min()
                if g + 1e-9 < best:
                    chosen[n], chosen_rgb[n] = prev
                else:
                    best, improved = g, True
        if not improved:
            break
    return chosen_rgb, family


def hex_to_rgb(h):
    h = h.lstrip("#")
    return np.array([int(h[i:i + 2], 16) / 255 for i in (0, 2, 4)])


def lab_of_hex(h):
    return srgb_to_lab(hex_to_rgb(h))


def to_hex(rgb):
    return "#" + "".join(f"{int(round(v * 255)):02x}" for v in rgb)


def report(names, colors, family, game_colors):
    labs = np.array([lab_of_hex(colors[n]) for n in names])
    d = de2000(labs[:, None, :], labs[None, :, :])
    np.fill_diagonal(d, np.inf)
    i, j = np.unravel_index(np.argmin(d), d.shape)
    print(f"closest pair: {names[i]} {colors[names[i]]} / {names[j]} {colors[names[j]]}: dE2000 = {d[i, j]:.1f}")
    g = np.array([srgb_to_lab(hex_to_rgb(game_colors[n])) for n in names])
    gd = de2000(g[:, None, :], g[None, :, :])
    np.fill_diagonal(gd, np.inf)
    gi, gj = np.unravel_index(np.argmin(gd), gd.shape)
    print(f"(game colours: closest pair {names[gi]} / {names[gj]}: dE2000 = {gd[gi, gj]:.1f}; "
          f"{int((gd < 10).sum() // 2)} pairs below 10)")
    print(f"pairs below 10: {int((d < 10).sum() // 2)}, below 15: {int((d < 15).sum() // 2)}")
    for f in sorted(set(family.values())):
        members = [k for k, n in enumerate(names) if family[n] == f]
        if len(members) > 1:
            sub = d[np.ix_(members, members)]
            print(f"  {f:9s} {len(members):2d} biomes, closest within family dE2000 = {sub.min():.1f}")
        else:
            print(f"  {f:9s} {len(members):2d} biome")
    return float(d[i, j])


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--dry-run", action="store_true", help="only print the palette and the report")
    ap.add_argument("--keep", action="store_true", help="keep the colours already in the palette file, pick only missing ones")
    ap.add_argument("--no-render", action="store_true", help="don't regenerate biomes.tsx/png and overview.png")
    a = ap.parse_args()

    world = json.load(open(os.path.join(PLANE, "world", "world.json")))
    biomes = [json.load(open(os.path.join(PLANE, n))) for n in world["biomesNames"]]
    names = [b["name"] for b in biomes]
    game_colors = {b["name"]: b["color"] for b in biomes}
    out = os.path.join(PLANE, os.path.dirname(world["worldMap"]), "biome_palette.json")

    fixed = {}
    if a.keep and os.path.exists(out):
        old = json.load(open(out))
        fixed = {k: (v if isinstance(v, str) else v["color"]) for k, v in old.items() if not k.startswith("_") and k in names}
    rgb, lab, fam = candidates()
    chosen, family = pick(names, fixed, rgb, lab, fam)
    colors = {n: fixed.get(n) or to_hex(chosen[n]) for n in names}

    labels = {}
    for n in names:
        lab_ = LABEL.get(n) or "".join(w[0] for w in n.split("_"))[:3].upper()
        if lab_ in labels.values():
            sys.exit(f"duplicate label {lab_} ({n})")
        labels[n] = lab_
    for n in names:
        print(f"{n:24s} {family[n]:9s} {labels[n]:3s} {colors[n]}  (game #{game_colors[n]})")
    min_de = report(names, colors, family, game_colors)
    if a.dry_run:
        return
    data = {"_comment": "Editor-only biome colours for the Tiled tileset (biomes.png) and overview.png, generated by "
                        "tools/middleearth/worldmap/make_biome_palette.py (greedy max-min CIEDE2000 within family hue ranges; "
                        f"closest pair dE2000 = {min_de:.1f}). The game keeps each biome's own JSON colour; maps are read by "
                        "the tile property 'biome', never by colour."}
    for n in names:
        data[n] = {"color": colors[n], "label": labels[n]}
    with open(out, "w", encoding="utf-8") as f:
        f.write("{\n" + ",\n".join(f"  {json.dumps(k)}: {json.dumps(v)}" for k, v in data.items()) + "\n}\n")
    print("wrote", os.path.relpath(out, ROOT))
    if not a.no_render:
        render()


def render():
    """biomes.tsx/png + overview.png with the engine's writer (tools/middleearth/worldmap/BiomeTiles.java)."""
    jars = sorted(f for f in os.listdir(os.path.join(ROOT, "forge-gui-mobile-dev", "target")) if f.endswith("jar-with-dependencies.jar")) \
        if os.path.isdir(os.path.join(ROOT, "forge-gui-mobile-dev", "target")) else []
    jar = os.environ.get("FORGE_JAR") or (os.path.join(ROOT, "forge-gui-mobile-dev", "target", jars[0]) if jars else None)
    if not jar:
        print("no fat jar (tools/middleearth/game.sh build); biomes.png/overview.png not regenerated "
              "(or run exportWorldTmx in the game)")
        return
    home = os.path.join(ROOT, ".me-test", "check-home")
    os.makedirs(os.path.join(home, ".forge", "adventure"), exist_ok=True)
    with open(os.path.join(home, ".forge", "adventure", "settings.json"), "w") as f:
        f.write('{"plane":"MiddleEarth"}')
    subprocess.run(["java", "-Xmx2g", f"-Duser.home={home}", "-Djava.awt.headless=true", "-cp", jar,
                    os.path.join(ROOT, "tools", "middleearth", "worldmap", "BiomeTiles.java")],
                   cwd=os.path.join(ROOT, "forge-gui"), check=True)


if __name__ == "__main__":
    main()
