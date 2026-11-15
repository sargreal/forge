#!/usr/bin/env python3
"""Grow the chunked Middle-earth Tiled world by whole chunks on any side (no content moves relative to itself).

    python3 tools/middleearth/worldmap/resize_world.py --east 2 --north 1 --south 1 [--west 0] [--dry-run]

What it does (chunk size and paths are read from the .world / world.json):
  * renames chunk_<c>_<r>.tmx so existing chunks keep their content (shifted by --west columns / --north rows),
    including the path of each chunk's Reference crop layer;
  * creates the new chunks: Biomes = ocean (tile 0 of biomes.tsx), empty Roads, Clutter and POI layers,
    a hidden Reference layer pointing at docs/MiddleEarth/reference/chunks/chunk_<c>_<r>.png;
  * rewrites middleearth.world, world.json width/height/playerStartPos, the biome JSON fractions
    (startPointX/Y, width, height) and the overview's Reference layer offset.
Afterwards: re-export the world from the game (exportWorldTmx) to refresh overview.tmx, regenerate the
reference crops (uv run tools/middleearth/worldmap/make_reference_crops.py) and run check.sh + WorldSim.
The engine needs a square world (SpritesDataMap), so the script refuses non-square results.
"""
import argparse, base64, json, os, re, subprocess, zlib

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..', '..'))
PLANE = os.path.join(ROOT, 'forge-gui/res/adventure/MiddleEarth')
WDIR = os.path.join(PLANE, 'world/middleearth')
CROPS_REL = '../../../../../../docs/MiddleEarth/reference/chunks'


def git_mv(a, b):
    subprocess.run(['git', 'mv', a, b], cwd=WDIR, check=True)


def empty_chunk(n, tile, gid_ocean, col, row):
    def layer(data_gid):
        raw = b''.join(int(data_gid).to_bytes(4, 'little') for _ in range(n * n))
        return base64.b64encode(zlib.compress(raw, 9)).decode()
    return f'''<?xml version="1.0" encoding="UTF-8"?>
<map version="1.10" tiledversion="1.10.2" orientation="orthogonal" renderorder="right-down" width="{n}" height="{n}" tilewidth="{tile}" tileheight="{tile}" infinite="0" nextlayerid="6" nextobjectid="1">
 <tileset firstgid="1" source="biomes.tsx"/>
 <tileset firstgid="45" source="roads.tsx"/>
 <tileset firstgid="46" source="clutter.tsx"/>
 <layer id="1" name="Biomes" width="{n}" height="{n}">
  <data encoding="base64" compression="zlib">
   {layer(gid_ocean)}
  </data>
 </layer>
 <layer id="3" name="Roads" width="{n}" height="{n}">
  <data encoding="base64" compression="zlib">
   {layer(0)}
  </data>
 </layer>
 <objectgroup id="4" name="Clutter">
 </objectgroup>
 <imagelayer id="5" name="Reference" opacity="0.5" visible="0">
  <image source="{CROPS_REL}/chunk_{col}_{row}.png" width="{n * tile}" height="{n * tile}"/>
 </imagelayer>
 <objectgroup id="2" name="POI">
 </objectgroup>
</map>
'''


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    for side in ('east', 'west', 'north', 'south'):
        ap.add_argument('--' + side, type=int, default=0, help='chunks to add on the ' + side)
    ap.add_argument('--dry-run', action='store_true')
    a = ap.parse_args()

    wj_path = os.path.join(PLANE, 'world/world.json')
    wj = json.load(open(wj_path))
    world = json.load(open(os.path.join(WDIR, 'middleearth.world')))
    first = world['maps'][0]
    tile = wj['tileSize']
    n = first['width'] // tile                      # tiles per chunk
    cols, rows = wj['width'] // n, wj['height'] // n
    ncols, nrows = cols + a.west + a.east, rows + a.north + a.south
    W, H = ncols * n, nrows * n
    if W != H:
        raise SystemExit(f'result {W}x{H} is not square; the engine needs a square world')
    print(f'{cols}x{rows} chunks ({wj["width"]}x{wj["height"]}) -> {ncols}x{nrows} chunks ({W}x{H}), chunk {n} tiles')
    if a.dry_run:
        return

    # 1. rename existing chunks (iterate so that targets are never existing files)
    order = sorted(((c, r) for c in range(cols) for r in range(rows)),
                   key=lambda cr: (-cr[0] if a.west else cr[0], -cr[1] if a.north else cr[1]))
    for c, r in order:
        nc, nr = c + a.west, r + a.north
        if (nc, nr) == (c, r):
            continue
        src, dst = f'chunk_{c}_{r}.tmx', f'chunk_{nc}_{nr}.tmx'
        git_mv(src, dst)
        p = os.path.join(WDIR, dst)
        t = open(p).read()
        t = t.replace(f'{CROPS_REL}/chunk_{c}_{r}.png', f'{CROPS_REL}/chunk_{nc}_{nr}.png')
        open(p, 'w').write(t)

    # 2. new ocean chunks
    old = {(c + a.west, r + a.north) for c in range(cols) for r in range(rows)}
    made = 0
    for c in range(ncols):
        for r in range(nrows):
            if (c, r) not in old:
                open(os.path.join(WDIR, f'chunk_{c}_{r}.tmx'), 'w').write(empty_chunk(n, tile, 1, c, r))
                made += 1
    print(f'renamed {len(old)} chunks, created {made} ocean chunks')

    # 3. .world
    world['maps'] = [{'fileName': f'chunk_{c}_{r}.tmx', 'height': n * tile, 'width': n * tile,
                      'x': c * n * tile, 'y': r * n * tile} for r in range(nrows) for c in range(ncols)]
    json.dump(world, open(os.path.join(WDIR, 'middleearth.world'), 'w'), indent=4)

    # 4. world.json (playerStartPos: Y = 0 is south) and biome fractions (startPointY: 0 is north)
    ow, oh = wj['width'], wj['height']
    wj['playerStartPosX'] = round((wj['playerStartPosX'] * ow + a.west * n) / W, 6)
    wj['playerStartPosY'] = round((wj['playerStartPosY'] * oh + a.south * n) / H, 6)
    wj['width'], wj['height'] = W, H
    txt = open(wj_path).read()
    for k in ('width', 'height', 'playerStartPosX', 'playerStartPosY'):
        txt = re.sub(rf'("{k}"\s*:\s*)[0-9.]+', lambda m: m.group(1) + str(wj[k]), txt, count=1)
    open(wj_path, 'w').write(txt)
    for b in wj['biomesNames']:
        p = os.path.join(PLANE, b)
        t = open(p).read()
        d = json.loads(t)
        if d.get('width') == 1.0 and d.get('height') == 1.0:
            continue                                # full-world base biome (ocean)
        new = {'startPointX': (d['startPointX'] * ow + a.west * n) / W,
               'startPointY': (d['startPointY'] * oh + a.north * n) / H,
               'width': d['width'] * ow / W, 'height': d['height'] * oh / H}
        for k, v in new.items():
            t = re.sub(rf'("{k}"\s*:\s*)-?[0-9.eE-]+', lambda m: m.group(1) + f'{v:.6f}', t, count=1)
        json.loads(t)
        open(p, 'w').write(t)

    # 5. overview Reference offset (1 px = 1 tile)
    op = os.path.join(WDIR, 'overview.tmx')
    t = open(op).read()
    def shift(m):
        x = float(m.group(2)) + a.west * n
        y = float(m.group(3)) + a.north * n
        return f'{m.group(1)}offsetx="{x:g}" offsety="{y:g}"'
    t = re.sub(r'(<imagelayer id="\d+" name="Reference"[^>]*?)offsetx="([-0-9.]+)" offsety="([-0-9.]+)"', shift, t, count=1)
    open(op, 'w').write(t)
    print('done: now re-export the world (exportWorldTmx), regenerate crops, run check.sh and WorldSim')


if __name__ == '__main__':
    main()
