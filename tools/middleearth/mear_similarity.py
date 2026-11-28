#!/usr/bin/env python3
"""Find MEAR reprints that are functionally close to cards already in LTR/LTC/PLTC/HOB/HOC.

Usage: tools/middleearth/mear_similarity.py [--out docs/MiddleEarth/notes/mear-similarity.md]

Parses the card scripts (forge-gui/res/cardsfolder), the MEAR edition file and the pool
edition files, scores every MEAR card against every pool card, and writes a Markdown report
with two tiers:
  tier 1  functionally identical, or strictly better/worse (same text, small stat/cost delta)
  tier 2  very similar (high Oracle similarity plus same type/colours/mana value)
It never changes the edition file.
"""
import argparse, difflib, json, os, re, sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
RES = os.path.join(ROOT, 'forge-gui', 'res')
CARDS = os.path.join(RES, 'cardsfolder')
ED = os.path.join(RES, 'editions')
MEAR = 'Middle-earth Adventure Reprints.txt'
POOL = {  # set code -> edition file
    'LTR': 'The Lord of the Rings Tales of Middle-earth.txt',
    'LTC': 'The Lord of the Rings Tales of Middle-earth Commander.txt',
    'PLTC': 'Tales of Middle-earth Deluxe Commander Kit.txt',
    'HOB': 'The Hobbit.txt',
    'HOC': 'The Hobbit Eternal.txt',
}
BASICS = {'Plains', 'Island', 'Swamp', 'Mountain', 'Forest', 'Snow-Covered Plains', 'Snow-Covered Island',
          'Snow-Covered Swamp', 'Snow-Covered Mountain', 'Snow-Covered Forest', 'Wastes'}
CARD_LINE = re.compile(r'^\s*(\S+)\s+([CURMSLTB]|[A-Z])\s+(.+?)\s*(?:@[^$]*)?(\$\{.*\})?\s*$')
TYPES = ['Creature', 'Instant', 'Sorcery', 'Artifact', 'Enchantment', 'Land', 'Planeswalker', 'Battle']
COLORS = 'WUBRG'


def read_edition(fname, with_flavor=False):
    """-> list of (name, flavor or None) from the [cards] section."""
    out, in_cards = [], False
    for line in open(os.path.join(ED, fname), encoding='utf-8'):
        s = line.strip()
        if s.startswith('['):
            in_cards = s == '[cards]'
            continue
        if not in_cards or not s or s.startswith('#'):
            continue
        m = re.match(r'^(\S+)\s+(\S+)\s+(.+?)(?:\s+@[^$]*?)?(?:\s*\$(\{.*\}))?\s*$', s)
        if not m:
            continue
        name = m.group(3).split(' @')[0].strip()
        flavor = None
        if m.group(4):
            try:
                flavor = json.loads(m.group(4)).get('flavorName')
            except ValueError:
                pass
        out.append((name, flavor))
    return out


def load_scripts():
    """name -> dict(first face) for every card script."""
    cards = {}
    for letter in os.listdir(CARDS):
        d = os.path.join(CARDS, letter)
        if not os.path.isdir(d):
            continue
        for f in os.listdir(d):
            c = parse_script(os.path.join(d, f))
            if c:
                cards[c['name']] = c
                # a split/DFC card is also found by its first face only; fine for this set
    return cards


def parse_script(path):
    c = {'k': [], 'lines': [], 'oracle': ''}
    try:
        text = open(path, encoding='utf-8').read()
    except OSError:
        return None
    for line in text.split('\n'):
        if line.startswith(('ALTERNATE', 'SPECIALIZE')):
            break  # only the front face
        if line.startswith('Name:'):
            c['name'] = line[5:].strip()
        elif line.startswith('ManaCost:'):
            c['cost'] = line[9:].strip()
        elif line.startswith('Types:'):
            c['types'] = line[6:].strip()
        elif line.startswith('PT:'):
            c['pt'] = line[3:].strip()
        elif line.startswith('Loyalty:'):
            c['pt'] = 'L' + line[8:].strip()
        elif line.startswith('K:'):
            c['k'].append(line[2:].strip())
        elif line.startswith('Oracle:'):
            c['oracle'] = line[7:].strip().replace('\\n', '\n')
        elif line.startswith(('A:', 'T:', 'S:', 'R:', 'SVar:')):
            c['lines'].append(line)
    if 'name' not in c:
        return None
    c.setdefault('cost', 'no cost')
    c.setdefault('types', '')
    c.setdefault('pt', '')
    return c


def mana_value(cost):
    if cost == 'no cost':
        return 0
    mv = 0
    for t in cost.split():
        if t.isdigit():
            mv += int(t)
        elif t.startswith('X'):
            pass
        else:
            mv += 1
    return mv


def colors(cost, card=None):
    if card is not None and 'Land' in card['types'].split(' '):
        # lands: colours of the mana they can produce
        adds = ' '.join(re.findall(r'Add ([^.\n]*)', card['oracle']))
        return ''.join(ch for ch in COLORS if '{%s}' % ch in adds)
    return ''.join(ch for ch in COLORS if ch in cost)


def ctype(types):
    return tuple(t for t in TYPES if t in types.split(' '))


def norm_text(c):
    t = c['oracle'] or '\n'.join(c['k'])
    t = re.sub(r'\([^)]*\)', '', t)  # drop reminder text
    t = t.replace(c['name'], 'CARDNAME')
    first = c['name'].split(',')[0].split(' ')[0]
    t = re.sub(r'\b' + re.escape(c['name'].split(',')[0]) + r'\b', 'CARDNAME', t)
    t = t.lower()
    t = re.sub(r'[^a-z0-9+/\-{}\n ]', ' ', t)
    return re.sub(r'\s+', ' ', t).strip()


def kwset(c):
    return frozenset(k.split(':')[0].strip().lower() for k in c['k'])


def kw_text(c):
    return [re.sub(r'[^a-z0-9+/\-{}\n ]', ' ', k.split(':')[0].lower()).strip() for k in c['k']]


def pt_nums(pt):
    m = re.match(r'^(-?\d+)/(-?\d+)$', pt)
    return (int(m.group(1)), int(m.group(2))) if m else None


def jaccard(a, b):
    sa, sb = set(a.split()), set(b.split())
    return len(sa & sb) / len(sa | sb) if (sa | sb) else 1.0


def text_sim(a, b):
    if a == b:
        return 1.0
    return 0.5 * difflib.SequenceMatcher(None, a, b).ratio() + 0.5 * jaccard(a, b)


def compare(m, p):
    ta, tb = norm_text(m), norm_text(p)
    ts = text_sim(ta, tb)
    mva, mvb = mana_value(m['cost']), mana_value(p['cost'])
    ca, cb = colors(m['cost'], m), colors(p['cost'], p)
    ty = ctype(m['types']) == ctype(p['types'])
    pa, pb = pt_nums(m['pt']), pt_nums(p['pt'])
    kw = kwset(m) == kwset(p)
    pt_same = m['pt'] == p['pt']
    score = (0.55 * ts + 0.10 * (mva == mvb) + 0.10 * (ca == cb) + 0.10 * ty + 0.05 * pt_same + 0.10 * kw)
    diffs = []
    if mva != mvb:
        diffs.append('MV %d vs %d' % (mva, mvb))
    if ca != cb:
        diffs.append('colours %s vs %s' % (ca or 'C', cb or 'C'))
    if not ty:
        diffs.append('type %s vs %s' % ('/'.join(ctype(m['types'])), '/'.join(ctype(p['types']))))
    if not pt_same and (m['pt'] or p['pt']):
        diffs.append('P/T %s vs %s' % (m['pt'] or '-', p['pt'] or '-'))
    if not kw:
        diffs.append('keywords %s vs %s' % (sorted(kwset(m) - kwset(p)) or '-', sorted(kwset(p) - kwset(m)) or '-'))
    if ts < 1.0:
        diffs.append('text %.0f%% alike' % (ts * 100))
    # tiers
    tier = 0
    if 'Land' in ctype(m['types']) and ca != cb:
        return score, 0, ts, diffs
    same_frame = ty and ca == cb
    stat_close = abs(mva - mvb) <= 1 and (pa is None or pb is None or (abs(pa[0] - pb[0]) <= 1 and abs(pa[1] - pb[1]) <= 1))
    if not ta and not tb:
        # both vanilla: only comparable when mana value matches and the bodies are close
        if same_frame and mva == mvb and stat_close:
            tier = 1
    elif not ta or not tb:
        pass
    elif same_frame and ts >= 0.97 and stat_close:
        tier = 1
    elif same_frame and ts >= 0.88 and abs(mva - mvb) <= 1 and kw and stat_close and (pa is None or pb is None or pa == pb or True):
        tier = 1 if (ts >= 0.93 and mva == mvb and pt_same) else 2
    elif ty and ta and tb and ts >= 0.68 and abs(mva - mvb) <= 2 and (ca == cb or ts >= 0.9):
        tier = 2
    elif same_frame and kw and kwset(m) and ta == ' '.join(sorted(kw_text(m))) and tb == ' '.join(sorted(kw_text(p))) and abs(mva - mvb) <= 1 and stat_close and 'Land' not in ctype(m['types']):
        tier = 2  # same keyword package, same colour and body
    if tier == 1 and ts < 1.0 and not (mva == mvb and pt_same):
        tier = 2 if ts < 0.97 else 1
    return score, tier, ts, diffs


# Reviewer-confirmed pairs. The automatic text score cannot see that "Destroy target creature" and
# "Destroy target nonartifact creature" are the same role, so each pair below was read by hand.
# MEAR card -> (pool card, tier, relation, recommendation)
JUDGED = {
    'Murder': ('Bilbo\'s Deadly Slice', 1, 'identical effect and cost (1BB destroy target creature)',
               'drop, or swap for Grasp of Darkness (BB instant, -4/-4) or Sengir Vampire'),
    'Hero\'s Downfall': ('Bilbo\'s Deadly Slice', 1, 'same cost; MEAR strictly better (also hits planeswalkers)',
                         'keep (the better of the two; the planeswalker clause is irrelevant in adventure, but the card is harmless)'),
    'Doom Blade': ('Go for the Throat', 1, 'same cost; nonblack vs nonartifact',
                   'keep: black has few cheap removal spells in the pool, and the restriction differs'),
    'Cancel': ('Saruman\'s Trickery', 1, 'same cost; MEAR strictly worse (no Amass)',
               'drop, or swap for Remand (1U, tempo counter that cantrips)'),
    'Counterspell': ('Saruman\'s Trickery', 1, 'MEAR cheaper (UU vs 1UU) but no Amass',
                     'keep (cheap counter; Trickery is the only other hard counter)'),
    'Pacifism': ('Fog on the Barrow-Downs', 1, 'MEAR strictly better (1W vs 2W, no type change)',
                 'keep (white aura removal staple; Fog is the pool\'s only copy of the effect)'),
    'Unsummon': ('Soothing of Sm\u00e9agol', 1, 'trade-off: U vs 1U; no "Ring tempts you", no nontoken clause',
                 'keep (a 1-mana trick, plays differently)'),
    'Think Twice': ('Plunder the Trollshaws', 1, 'near-identical cantrip with flashback (1U, flashback 2U vs 3U)',
                    'drop, or swap for Mulldrifter (4U, 2/2 flyer that draws two)'),
    'Ranger\'s Guile': ('Bombadil\'s Song', 1, 'same effect (+1/+1 and hexproof); MEAR costs G and has no Ring tempts',
                        'keep (cheap trick that 40-card green decks want a few of)'),
    'Claustrophobia': ('Enchanted River\'s Grasp', 1, 'Grasp is strictly better (2U vs 1UU, and removes abilities)',
                       'drop, or swap for a different blue card such as Mulldrifter'),
    'Blossoming Sands': ('Graypelt Refuge', 1, 'identical (GW gain-land)',
                         'keep: a land, so the duplicate adds colour fixing for the 40-card GW decks rather than extra game text'),
    # tier 2 judgements the text score under-rates
    'Battleflight Eagle': ('Soldier of the Grey Host', 2, 'both 2/2 flyers with an ETB pump; Soldier has flash and costs 1 less', ''),
    'Cloudchaser Eagle': ('Velvetwing Butterflies', 2, 'vanilla 2/2 flyer for 2W vs the same body plus destroy target enchantment for 3W', ''),
    'Raging Goblin': ('Goblin-town Flunkies', 2, 'R 1/1 haste vs 1R 1/1 haste + Amass 1', ''),
    'Treefolk Harbinger': ('Elvish Harbinger', 2, 'ETB tutor to the top of the library for a tribe card', ''),
    'Bonesplitter': ('Well-Worn Spatula', 2, 'cheap Equipment for 1 (+2/+0 vs +1/+1 and 2 life)', ''),
    'Coldsteel Heart': ('Mind Stone', 2, 'two-mana mana rock', ''),
    'Sylvan Ranger': ('Farhaven Elf', 2, 'basic-land fetch on a 1/1 body (to hand vs onto the battlefield)', ''),
    'Borderland Ranger': ('Farhaven Elf', 2, 'basic-land fetch on a body (to hand vs onto the battlefield)', ''),
    'Mammoth Spider': ('Ishkanah, Grafwidow', 2, '3/5 reach for 4G; Ishkanah adds tokens and a drain', ''),
    'Control Magic': ('Fealty to the Realm', 2, 'Aura that steals a creature (2UU, no drawback) vs 4U with monarch and attack restrictions', ''),
    'Mind Control': ('Fealty to the Realm', 2, 'Aura that steals a creature (3UU) vs 4U with monarch and attack restrictions', ''),
}


def verdict(m, p, diffs):
    """Describe the relation for tier 1."""
    mva, mvb = mana_value(m['cost']), mana_value(p['cost'])
    pa, pb = pt_nums(m['pt']), pt_nums(p['pt'])
    better = worse = False
    if mva < mvb: better = True
    if mva > mvb: worse = True
    if pa and pb:
        if pa[0] >= pb[0] and pa[1] >= pb[1] and pa != pb: better = True
        if pa[0] <= pb[0] and pa[1] <= pb[1] and pa != pb: worse = True
    if better and worse:
        return 'trade-off'
    if better:
        return 'MEAR strictly better'
    if worse:
        return 'MEAR strictly worse'
    return 'identical'


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--out', default=os.path.join(ROOT, 'docs', 'MiddleEarth', 'notes', 'mear-similarity.md'))
    ap.add_argument('--stdout', action='store_true')
    a = ap.parse_args()
    scripts = load_scripts()
    mear = read_edition(MEAR)
    mear = [(n.split(' // ')[0], f) for n, f in mear]
    pool = {}  # name -> list of sets
    for code, f in POOL.items():
        for n, _ in read_edition(f):
            if n not in BASICS:
                pool.setdefault(n, []).append(code)
    missing = [n for n, _ in mear if n not in scripts] + [n for n in pool if n not in scripts]
    rows = []
    for name, flavor in mear:
        m = scripts.get(name)
        if not m or name in BASICS or 'Land' in ctype(m['types']) and not (m['oracle'] or m['k']):
            continue
        if name in pool:
            continue  # exact duplicate, handled upstream
        best = None
        for pn, sets in pool.items():
            p = scripts.get(pn)
            if not p:
                continue
            score, tier, ts, diffs = compare(m, p)
            if tier and (best is None or (tier, score) > (best[0], best[1])):
                best = (tier, score, pn, sets, diffs, ts)
        if name in JUDGED and JUDGED[name][0] in scripts:
            pn, jt, why, rec = JUDGED[name]
            _, _, ts, diffs = compare(m, scripts[pn])
            best = (jt, 2.0, pn, pool.get(pn, ['?']), diffs, ts, why, rec)
        if best:
            rows.append((name, flavor, m, scripts[best[2]], best))
    # MEAR against MEAR (redundancy inside the set)
    names = [n for n, _ in mear if n in scripts and n not in BASICS]
    intra = []
    for i, a_ in enumerate(names):
        for b_ in names[i + 1:]:
            sc, tr, ts, df = compare(scripts[a_], scripts[b_])
            if tr == 1 and 'Land' not in ctype(scripts[a_]['types']):
                intra.append((a_, b_, sc, df))
    t1 = sorted([r for r in rows if r[4][0] == 1], key=lambda r: -r[4][1])
    t2 = sorted([r for r in rows if r[4][0] == 2], key=lambda r: -r[4][1])
    lines = ['# MEAR similarity report', '',
             'Generated by `tools/middleearth/mear_similarity.py` (re-run it after edition changes; the recommendations below are hand-written in the sibling section). '
             'Each MEAR card is scored against all %d distinct cards of LTR/LTC/PLTC/HOB/HOC (basic lands excluded) on Oracle text '
             '(own name replaced by CARDNAME, reminder text dropped; mean of difflib ratio and token Jaccard), mana value, colours, card type, P/T and keyword set. '
             'The closest pool card is shown when it passes a tier threshold.' % len(pool), '',
             '- **Tier 1**: same frame (type and colours) and near-identical text (at least 97% text similarity), mana value within 1 and P/T within 1: functionally identical or strictly better/worse.',
             '- **Tier 2**: very similar (text a hand-reviewed pair or same-keyword body, or at least 68% text similarity with the same type): a different card, but it plays the same role.', '',
             'Counts: **%d tier 1**, **%d tier 2** (of %d MEAR cards checked).' % (len(t1), len(t2), len(mear)), '']
    def table(rs, relcol):
        out = ['| MEAR card | Flavor name | Closest pool card (set) | Relation / what differs / recommendation |', '|---|---|---|---|']
        for name, flavor, m, p, b in rs:
            if len(b) > 6:
                rel = b[6] + ('. ' + b[7] if relcol and b[7] else '')
            else:
                rel = (verdict(m, p, b[4]) + '; ' if relcol else '') + ('; '.join(b[4]) or 'no differences')
            out.append('| %s (%s %s %s) | %s | %s (%s %s %s) | %s |' % (
                name, m['cost'], '/'.join(ctype(m['types'])), m['pt'], flavor or '', p['name'],
                '/'.join(sorted(set(b[3]))), p['cost'], p['pt'], rel.strip()))
        return out
    lines += ['## Tier 1: functionally identical or strictly better/worse', ''] + table(t1, True) + ['']
    lines += ['## Tier 2: very similar', ''] + table(t2, False) + ['']
    lines += ['## Inside MEAR: tier-1 pairs', '',
              'Same measure, MEAR card against MEAR card (lands excluded). Counted separately from the tiers above.', '',
              '| Card A | Card B | What differs |', '|---|---|---|']
    for a_, b_, sc, df in sorted(intra, key=lambda x: -x[2]):
        lines.append('| %s | %s | %s |' % (a_, b_, '; '.join(df) or 'identical'))
    lines.append('')
    if missing:
        lines += ['Skipped (no card script found): ' + ', '.join(sorted(set(missing))), '']
    text = '\n'.join(lines)
    if a.stdout:
        print(text)
    else:
        open(a.out, 'w', encoding='utf-8').write(text)
        print('wrote %s: tier1=%d tier2=%d' % (a.out, len(t1), len(t2)))


if __name__ == '__main__':
    main()
