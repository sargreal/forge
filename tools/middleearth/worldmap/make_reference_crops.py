#!/usr/bin/env python3
# /// script
# requires-python = ">=3.10"
# dependencies = ["pillow>=10"]
# ///
"""Cut the reference map into one crop per chunk of the Tiled world (Phase 2d-1).

The reference image (docs/MiddleEarth/reference/map-of-middle-earth.jpg) is placed in the generated
overview map (forge-gui/res/adventure/MiddleEarth/world/middleearth/overview.tmx), image layer
"Reference": in the overview, 1 px = 1 world tile, and the layer's offsetx/offsety say where the image's
top-left corner is (in world tiles). This script reads that placement and the chunk list of
middleearth.world and writes docs/MiddleEarth/reference/chunks/chunk_<c>_<r>.png: the part of the image
under each chunk, scaled to SCALE px per world tile (default 16 = the chunk's tile size, so the crop lines up
1:1 with the chunk's tiles in Tiled). Areas outside the image are transparent.

Each chunk map shows its crop in a hidden image layer "Reference" (written by exportWorldTmx; the game
ignores it). Tiled can't scale image layers, so the crops are stored at full chunk size (2400x2400 px).
They are upscaled with nearest neighbour (the source has about 1 px per world tile, so smoothing adds no
detail) and saved as 256-colour PNGs, about 60 KB each.

Rerun it whenever the Reference layer in overview.tmx is moved (offset changed) or the image is replaced:

    python3 tools/middleearth/worldmap/make_reference_crops.py

Options: --world <file.world> --overview <overview.tmx> --out <dir> --scale <px per tile>.
Requires Pillow.
"""
import argparse
import json
import os
import sys
import xml.etree.ElementTree as ET

from PIL import Image

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
WORLD_DIR = os.path.join(ROOT, "forge-gui", "res", "adventure", "MiddleEarth", "world", "middleearth")


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--world", default=os.path.join(WORLD_DIR, "middleearth.world"))
    ap.add_argument("--overview", default=os.path.join(WORLD_DIR, "overview.tmx"))
    ap.add_argument("--out", default=os.path.join(ROOT, "docs", "MiddleEarth", "reference", "chunks"))
    ap.add_argument("--scale", type=int, default=16, help="output px per world tile (default 16 = 1:1 with the chunk tiles)")
    args = ap.parse_args()

    overview = ET.parse(args.overview).getroot()
    layer = next((l for l in overview.findall("imagelayer") if l.get("name") == "Reference"), None)
    if layer is None or layer.find("image") is None:
        sys.exit("overview has no Reference image layer with an image")
    image_path = os.path.normpath(os.path.join(os.path.dirname(args.overview), layer.find("image").get("source")))
    off_x, off_y = float(layer.get("offsetx", 0)), float(layer.get("offsety", 0))
    src = Image.open(image_path).convert("RGBA")

    world = json.load(open(args.world))
    tile = None
    os.makedirs(args.out, exist_ok=True)
    total = 0
    for m in world["maps"]:
        chunk = ET.parse(os.path.join(os.path.dirname(args.world), m["fileName"])).getroot()
        tile = int(chunk.get("tilewidth"))
        x0, y0 = m["x"] / tile, m["y"] / tile          # chunk origin in world tiles
        w, h = int(chunk.get("width")), int(chunk.get("height"))
        k = 1.0 / args.scale                            # source px (= world tiles) per output px
        # output (u, v) -> source (u*k + x0 - off_x, v*k + y0 - off_y)
        crop = src.transform((w * args.scale, h * args.scale), Image.AFFINE, (k, 0, x0 - off_x, 0, k, y0 - off_y),
                             resample=Image.NEAREST, fillcolor=(0, 0, 0, 0))
        crop = crop.quantize(colors=256, method=Image.Quantize.FASTOCTREE)
        name = os.path.splitext(m["fileName"])[0] + ".png"
        path = os.path.join(args.out, name)
        crop.save(path, optimize=True)
        total += os.path.getsize(path)
    print(f"{len(world['maps'])} crops of {image_path} (offset {off_x},{off_y}) at {args.scale} px/tile -> {args.out}, {total // 1024} KB")


if __name__ == "__main__":
    main()
