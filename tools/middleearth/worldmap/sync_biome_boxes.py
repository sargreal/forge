#!/usr/bin/env python3
"""Write the painted biome boxes back into the biome JSONs (startPointX/Y, width, height).

With a world map (world.json -> "worldMap"), the game anchors each biome's WFC structures (mountains, forests, Mordor)
on the bounding box of the biome's painted tiles (World.applyPaintedBiomeBoxes), not on the JSON box. The JSON box is
then only a fallback for unpainted biomes. This script makes the JSONs match what the game uses, so they stay
meaningful (e.g. for random generation or other tools). It edits only those four numbers in place, keeps the rest of
each file as it is, and skips the full-world base biome (width = height = 1) and unpainted biomes.

Box convention (as World.generateNew): fractions of the world, startPointY 0 = north; startPointX = (minX + w // 2) / W,
width = w / W, where minX and w are the painted box's first column and width in tiles (likewise for Y with rows).

    python3 tools/middleearth/worldmap/sync_biome_boxes.py            # rewrite the JSONs that differ
    python3 tools/middleearth/worldmap/sync_biome_boxes.py --dry-run  # only list the differences

Standard library only. tools/middleearth/check.sh -v lists the same differences.
"""
import argparse
import base64
import gzip
import json
import math
import os
import re
import struct
import xml.etree.ElementTree as ET
import zlib

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
PLANE = os.path.join(ROOT, "forge-gui", "res", "adventure", "MiddleEarth")
KEYS = ("startPointX", "startPointY", "width", "height")


def decode_layer(d, n):
    if d.get("encoding") == "csv":
        return [int(v) for v in d.text.replace("\n", "").split(",") if v.strip()]
    b = base64.b64decode(d.text.strip())
    c = d.get("compression")
    b = zlib.decompress(b) if c == "zlib" else gzip.decompress(b) if c == "gzip" else b
    return [int.from_bytes(b[i:i + 4], "little") for i in range(0, 4 * n, 4)]


def read_map(path, index):
    """Own biome per cell (row-major, row 0 = north) of one tmx, as WorldMapTmx reads it (tile property 'biome')."""
    root = ET.parse(path).getroot()
    w, h = int(root.get("width")), int(root.get("height"))
    gid_biome = {}
    for ts in root.findall("tileset"):
        first = int(ts.get("firstgid", 1))
        t = ET.parse(os.path.join(os.path.dirname(path), ts.get("source"))).getroot() if ts.get("source") else ts
        for tile in t.findall("tile"):
            for p in tile.findall("properties/property"):
                if p.get("name") == "biome":
                    gid_biome[first + int(tile.get("id"))] = index.get(p.get("value"), 0)
    for layer in root.findall("layer"):
        if layer.get("name") == "Biomes":
            return w, h, [gid_biome.get(g & 0x0FFFFFFF, 0) for g in decode_layer(layer.find("data"), w * h)]
    raise SystemExit(f"{path}: no Biomes layer")


def painted_boxes(world, names):
    """{biome index: [minX, minRow, maxX, maxRow]} over the whole world."""
    index = {n: i for i, n in enumerate(names)}
    path = os.path.join(PLANE, world["worldMap"])
    tile = world.get("tileSize", 16)
    maps = json.load(open(path))["maps"] if path.endswith(".world") else [{"fileName": os.path.basename(path), "x": 0, "y": 0}]
    boxes = {}
    for m in maps:
        w, h, cells = read_map(os.path.join(os.path.dirname(path), m["fileName"]), index)
        ox, oy = m["x"] // tile, m["y"] // tile
        for r in range(h):
            for c in range(w):
                b = cells[r * w + c]
                x, y = ox + c, oy + r
                box = boxes.get(b)
                if box is None:
                    boxes[b] = [x, y, x, y]
                else:
                    box[0], box[1], box[2], box[3] = min(box[0], x), min(box[1], y), max(box[2], x), max(box[3], y)
    return boxes


def json_box(b, W, H):
    """The tile box World.generateNew derives from the JSON fractions: BiomeData holds floats, then
    Math.round(float * (double) size) = floor(x + 0.5)."""
    def f32(v):
        return struct.unpack("f", struct.pack("f", float(v)))[0]

    w, h = math.floor(f32(b["width"]) * W + 0.5), math.floor(f32(b["height"]) * H + 0.5)
    x, y = math.floor(f32(b["startPointX"]) * W + 0.5) - w // 2, math.floor(f32(b["startPointY"]) * H + 0.5) - h // 2
    return [x, y, x + w - 1, y + h - 1]


NUMBER = re.compile(r'\s*:\s*(-?[0-9.]+(?:[eE][-+]?[0-9]+)?)')


def top_level_values(text, key):
    """Spans of the numeric values of "key" in the top-level object only (not inside "structures" etc.)."""
    spans, depth, i, quoted = [], 0, 0, '"' + key + '"'
    while i < len(text):
        c = text[i]
        if c == '"':
            end = i + 1
            while text[end] != '"':
                end += 2 if text[end] == "\\" else 1
            if depth == 1 and text[i:end + 1] == quoted:
                m = NUMBER.match(text, end + 1)
                if m:
                    spans.append(m.span(1))
            i = end + 1
            continue
        if c in "{[":
            depth += 1
        elif c in "}]":
            depth -= 1
        i += 1
    return spans


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--dry-run", action="store_true")
    a = ap.parse_args()
    world = json.load(open(os.path.join(PLANE, "world", "world.json")))
    if not world.get("worldMap"):
        raise SystemExit("world.json has no worldMap")
    W, H = world["width"], world["height"]
    files = world["biomesNames"]
    biomes = [json.load(open(os.path.join(PLANE, f))) for f in files]
    boxes = painted_boxes(world, [b["name"] for b in biomes])
    changed = 0
    for i, (f, b) in enumerate(zip(files, biomes)):
        if b.get("width") == 1 and b.get("height") == 1:
            continue
        box = boxes.get(i)
        if box is None:
            print(f"{b['name']}: not painted, kept")
            continue
        if json_box(b, W, H) == box:
            continue
        w, h = box[2] - box[0] + 1, box[3] - box[1] + 1
        new = {"startPointX": (box[0] + w // 2) / W, "startPointY": (box[1] + h // 2) / H, "width": w / W, "height": h / H}
        old = json_box(b, W, H)
        print(f"{b['name']}: {old[0]},{old[1]} {old[2] - old[0] + 1}x{old[3] - old[1] + 1} -> {box[0]},{box[1]} {w}x{h}")
        changed += 1
        if a.dry_run:
            continue
        path = os.path.join(PLANE, f)
        text = open(path, encoding="utf-8").read()
        for k in KEYS:
            spans = top_level_values(text, k)
            if len(spans) != 1:
                raise SystemExit(f"{path}: top-level '{k}' is not there exactly once; edit it by hand")
            s, e = spans[0]
            text = text[:s] + f"{new[k]:.6f}" + text[e:]
        check = json.loads(text)
        assert json_box(check, W, H) == box, (b["name"], json_box(check, W, H), box)
        open(path, "w", encoding="utf-8").write(text)
    print(f"{changed} biome boxes {'differ' if a.dry_run else 'updated'}")


if __name__ == "__main__":
    main()
