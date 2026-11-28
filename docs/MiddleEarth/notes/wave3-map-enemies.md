# Wave 3: re-theme generic map enemies

## Approach

Town and dungeon `.tmx` maps spawn enemies by `name`, and ~178 of the generic upstream enemies in
`world/enemies.json` still used `decks/standard/...` decks full of off-pool cards. For each of them
`tools/middleearth/retheme-map-enemies.py` (mapping table inside, rerunnable, `--dry-run` supported)
now sets:

- `deck`: one or two existing Middle-earth decks (`decks/enemy/shadow|free/*.dck`) chosen by theme and
  by the enemy's difficulty/life/boss flag (easy: snaga, misty_goblin, wild_wolves, dead_marshes,
  southern_ruffians; stronger: captains, olog_hai, ringwraith, great_spiders; bosses only for `boss: true`).
- `nameOverride`: a Middle-earth display name where the old name did not fit; kept where it did.

`name`, `sprite`, `life`, `difficulty`, `spawnRate`, `rewards` are untouched. Result:
170 enemies re-themed (deck changed), 143 renamed (`nameOverride` set or changed), 178 table rows
(8 already used in-pool decks and only got additional ones or kept theirs).

## Mapping summary

| Theme | Count | Decks | Examples |
|---|---|---|---|
| Wizards / mages / cultists | ~38 | dol_guldur_necromancer, easterling, haradrim, huorns, gondor_captain, uruk_captain | Apprentice Black Wizard -> Dol Guldur Acolyte; Master White Wizard -> Loremaster of Minas Tirith; High Cultist -> High Cultist of the Eye |
| Undead | ~28 | barrow_wights, barrow_lord, dead_marshes, dol_guldur_spirit, ringwraith, black_rider | Zombie -> Barrow-wight Thrall; Lich -> Barrow-lich; Ancient Vampire -> Ancient Blood-lord |
| Goblins / orcs / kobolds | 15 | snaga, misty_goblin, moria_orc, mordor_orc, uruk_*, orc_captain | Kobold Shaman -> Snaga Shaman; Scourgemaster -> Orc Taskmaster |
| Soldiers / knights / archers | 18 | gondor_guard, gondor_captain, rohirrim_riders, ranger_* | Armored Knight -> Gondorian Knight; Centaur Warrior -> Horse-lord Warrior |
| Bandits | 5 | southern_ruffians, sharkeys_men, bill_ferny | Bandit Leader -> Ruffian Chief |
| Pirates / sea | 7 | corsair, corsair_captain, watcher, dead_marshes | Pirate -> Corsair; Merfolk Lord -> Umbar Sea-lord |
| Dwarves | 5 | iron_hills, erebor_dwarves, dwarf_prospectors | Dwarf Demolisher -> Dwarf Sapper |
| Dragons / fire | 4 | cold_drakes, fell_beast, warg_pack | Efreet -> Fire-drake of Mordor |
| Spiders / insects | 6 | mirkwood_spiders, great_spiders, shelob_brood | Scarab -> Carrion Beetle |
| Beasts | ~27 | wild_wolves, warg_pack, wild_bears, beorning, crebain, great_eagles, mumak | Werewolf -> Were-warg; Rhino -> War-beast of Harad |
| Trolls / giants / horrors | ~14 | hill_troll, cave_troll, stone_troll, olog_hai, mouth_of_sauron, ents | Mountain Ogre -> Mountain Troll; Eldrazi -> Fell Horror |
| Elementals / constructs / plants | 5 | cold_drakes, watcher, stone_troll, huorns | Ice Elemental -> Frost-wraith |
| Challengers and bosses | 5 | Challengers: uruk_captain, rohan_marshal, ringwraith; Emrakul: balrog; Griselbrand: witch_king | |

(Counts per theme are approximate; the script table is authoritative.)

## Leftovers

- `check.sh -v | grep "map enemy" | grep off-pool`: empty. `check.sh`: 0 errors.
- Display-name duplicates: Challenger 20/21/22 share "Challenger" and Cathar shares "Human Raider" with one
  existing entry; both pre-date this change and were left alone.
- Some enemies (e.g. Hippo, Rhino, Parrot) are thematically loose fits (mumak/crebain) since the plane
  has no closer deck; sprites are unchanged, so visuals may not match the new names exactly.
- Decks were not play-tested or strength-simulated (CPU was reserved for another agent).
- PLAN.md change log was not updated in this wave, per task scope.
