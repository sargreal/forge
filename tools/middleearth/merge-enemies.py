#!/usr/bin/env python3
"""Merge enemy fragments into one plane enemies.json and check name collisions.

  tools/middleearth/merge-enemies.py [--base FILE] [-o OUT] FRAGMENT...

Default base: forge-gui/res/adventure/common/world/enemies.json.
Default output: forge-gui/res/adventure/MiddleEarth/world/enemies.json.
Used by WP6 to test enemies-free.fragment.json before the coordinator merges it; when the
output is the plane's enemies.json, do not commit it unless you own that file.

Errors (exit 1): a fragment enemy whose `name` or display name (nameOverride, else name)
equals any name or display name already in the base or in an earlier fragment. The game looks
enemies up by `name` first, then by display name, so a clash silently shadows one of them.
"""
import argparse, json, os, sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
ADV = os.path.join(ROOT, 'forge-gui', 'res', 'adventure')


def display(e):
    return e.get('nameOverride') or e.get('name')


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--base', default=os.path.join(ADV, 'common', 'world', 'enemies.json'))
    ap.add_argument('-o', '--out', default=os.path.join(ADV, 'MiddleEarth', 'world', 'enemies.json'))
    ap.add_argument('fragments', nargs='+')
    a = ap.parse_args()

    merged = json.load(open(a.base, encoding='utf8'))
    seen = {}
    for e in merged:
        for n in {e.get('name'), display(e)}:
            seen.setdefault(n, 'base')
    errors = 0
    for frag in a.fragments:
        items = json.load(open(frag, encoding='utf8'))
        for e in items:
            for n in {e.get('name'), display(e)}:
                if n in seen:
                    print('ERROR %s: name "%s" already used (%s)' % (os.path.basename(frag), n, seen[n]))
                    errors += 1
            for n in {e.get('name'), display(e)}:
                seen[n] = os.path.basename(frag)
        merged.extend(items)
        print('%s: %d enemies' % (frag, len(items)))
    if errors:
        sys.exit(1)
    with open(a.out, 'w', encoding='utf8') as w:
        json.dump(merged, w, indent=2, ensure_ascii=False)
        w.write('\n')
    print('wrote %s (%d enemies)' % (a.out, len(merged)))


if __name__ == '__main__':
    main()
