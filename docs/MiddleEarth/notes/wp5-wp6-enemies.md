# WP5 + WP6 enemies: report

*Written by the coordinator. Both agents hit the usage limit before writing their reports. Their work is on main: commits 2450cfac6 (WP5) and ab9794c40 / 62a11d162 (WP6).*

## What exists

- **WP5 (Shadow):** 34 new enemies (orcs, Uruk-hai, goblins, trolls, wargs, Nazgûl, Easterlings, Haradrim, Corsairs, Dol Guldur) and 7 bosses: the Great Goblin, the Mouth of Sauron, the Necromancer, Shelob, the Balrog, Smaug, Saruman and the Witch-king. Decks are in `decks/enemy/shadow/`. WP5 also gave 15 generic enemies used by maps (Goblin, Troll, Pirate, …) Shadow decks, and extended `PlaneCheck` to verify enemy references in maps, arenas and quests.
- **WP6 (Free Peoples and wild):** 34 enemies, named with an `ME ` prefix and a `nameOverride` display name. They cover wolves, wargs, spiders, bears, Beornings, Huorns, Ents, Barrow-wights, the Dead Marshes, Gollum, Bill Ferny and ruffians, Dunlendings, eagles, cold-drakes, Rangers, Rohirrim, Gondor, Elves and Dwarves. Mini-bosses: Old Man Willow, the Barrow-wight Lord, the Watcher in the Water and the Grey Mountain Wyrm. Decks are in `decks/enemy/free/`. They were delivered as `world/enemies-free.fragment.json` and merged into `enemies.json` with `tools/middleearth/merge-enemies.py`. Drops use WP7's ME items.
- **Integration (coordinator):** every biome's `enemies` list holds only ME enemies (see the change log in PLAN.md). Generic map enemies were re-themed separately ([wave3-map-enemies.md](wave3-map-enemies.md)).

## Strength (first measurement)

`deckstrength.sh --enemy-life -n 16`: each enemy at its own life against both LTR Starter Kit decks at 16 life, 32 games per enemy, about ±15 points of noise. The intended tier comes from the entry: `boss` → elite, `difficulty` ≤0.1 easy, ≤0.5 normal, otherwise hard. Tier bands (D9): <45% easy, 45–60% normal, 60–75% hard, >75% elite.

| Enemy | Life | Difficulty | Boss | Score | Measured | Intended | Note |
| --- | ---: | ---: | --- | ---: | --- | --- | --- |
| Snaga / Snaga of Mordor | 11 | 0.1 |  | 19% | easy | easy |  |
| Brood of Shelob / Brood of Shelob | 12 | 0.1 |  | 34% | easy | easy |  |
| Uruk-hai Berserker / Uruk-hai Berserker | 16 | 0.3 |  | 38% | easy | normal | too weak (-1) |
| ME Dwarf of Erebor / ME Dwarves of Erebor | 15 | 0.5 |  | 38% | easy | normal | too weak (-1) |
| Crebain / Crebain from Dunland | 10 | 0.1 |  | 41% | easy | easy |  |
| Uruk-hai / Uruk-hai of Isengard | 16 | 0.3 |  | 41% | easy | normal | too weak (-1) |
| ME Hobbit Troublemaker / ME Hobbit Troublema | 11 | 0.1 |  | 41% | easy | easy |  |
| ME Mirkwood Spiders / ME Mirkwood Spiders | 13 | 0.1 |  | 42% | easy | easy |  |
| Mordor Orc / Mordor Orc | 15 | 0.3 |  | 44% | easy | normal | too weak (-1) |
| Hill Troll / Hill Troll | 17 | 0.3 |  | 47% | normal | normal |  |
| ME Wild Bear / ME Wild Bears | 13 | 0.1 |  | 47% | normal | easy | TOO STRONG (+1) |
| ME Ranger of the North / ME Ranger of the No | 16 | 0.5 |  | 47% | normal | normal |  |
| Misty Mountains Goblin / Goblins of the Mist | 12 | 0.1 |  | 50% | normal | easy | TOO STRONG (+1) |
| Moria Orc / Orc of Moria | 15 | 0.3 |  | 50% | normal | normal |  |
| Haradrim Warrior / Haradrim Warrior | 16 | 0.3 |  | 50% | normal | normal |  |
| Uruk-hai Captain / Uruk-hai Captain | 21 | 0.6 |  | 50% | normal | hard | too weak (-1) |
| ME Barrow-wight / ME Barrow-wights | 15 | 0.5 |  | 50% | normal | normal |  |
| ME Dead Marshes Lights / ME Dead Marshes Lig | 13 | 0.1 |  | 53% | normal | easy | TOO STRONG (+1) |
| ME Great Eagle / ME Great Eagles | 16 | 0.5 |  | 53% | normal | normal |  |
| ME Dwarf Prospector / ME Dwarf Prospectors | 12 | 0.1 |  | 53% | normal | easy | TOO STRONG (+1) |
| Mumak of Harad / Mûmak of Harad | 24 | 0.6 |  | 56% | normal | hard | too weak (-1) |
| ME Wild Wolves / ME Wild Wolves | 12 | 0.1 |  | 56% | normal | easy | TOO STRONG (+1) |
| ME Beorning / ME Beorning | 17 | 0.5 |  | 56% | normal | normal |  |
| ME Dunlending Raider / ME Dunlendings | 13 | 0.1 |  | 56% | normal | easy | TOO STRONG (+1) |
| ME Ranger of Ithilien / ME Ranger of Ithilie | 16 | 0.5 |  | 56% | normal | normal |  |
| Olog-hai / Olog-hai | 22 | 0.6 |  | 59% | normal | hard | too weak (-1) |
| Saruman / Saruman | 30 | 1 | yes | 59% | normal | elite | too weak (-2) |
| ME Huorn / ME Huorns | 16 | 0.5 |  | 59% | normal | normal |  |
| ME Bill Ferny / ME Bill Ferny | 15 | 0.5 |  | 59% | normal | normal |  |
| ME Cold-drake / ME Cold-drakes | 20 | 1 |  | 59% | normal | hard | too weak (-1) |
| ME Galadhrim Warden / ME Galadhrim Wardens | 15 | 0.5 |  | 59% | normal | normal |  |
| Orc Captain / Orc Captain of Mordor | 21 | 0.6 |  | 62% | hard | hard |  |
| ME Old Man Willow / ME Old Man Willow | 30 | 2 | yes | 62% | hard | elite | too weak (-1) |
| ME Southern Ruffian / ME Southern Ruffians | 12 | 0.1 |  | 62% | hard | easy | TOO STRONG (+2) |
| ME Sharkeys Ruffian / ME Sharkeys Men | 15 | 0.5 |  | 62% | hard | normal | TOO STRONG (+1) |
| ME Captain of Gondor / ME Captain of Gondor | 22 | 1 |  | 62% | hard | hard |  |
| ME Gollum / ME Gollum | 14 | 0.5 |  | 66% | hard | normal | TOO STRONG (+1) |
| ME Guard of the Citadel / ME Guard of the Ci | 13 | 0.1 |  | 66% | hard | easy | TOO STRONG (+2) |
| ME Woodland Realm Guard / ME Woodland Realm | 15 | 0.5 |  | 66% | hard | normal | TOO STRONG (+1) |
| Warg Rider / Warg Rider | 15 | 0.3 |  | 69% | hard | normal | TOO STRONG (+1) |
| Spirit of Dol Guldur / Spirit of Dol Guldur | 15 | 0.3 |  | 69% | hard | normal | TOO STRONG (+1) |
| Shelob / Shelob | 30 | 1 | yes | 69% | hard | elite | too weak (-1) |
| ME Great Spider / ME Great Spiders | 22 | 1 |  | 69% | hard | hard |  |
| ME Watcher in the Water / ME Watcher in the | 32 | 2 | yes | 69% | hard | elite | too weak (-1) |
| Cave Troll / Cave Troll | 22 | 0.6 |  | 72% | hard | hard |  |
| Necromancer of Dol Guldur / Necromancer of D | 21 | 0.6 |  | 72% | hard | hard |  |
| ME Warg Pack / ME Warg Pack | 16 | 0.5 |  | 72% | hard | normal | TOO STRONG (+1) |
| ME Elf-lord of Rivendell / ME Elf-lord of Ri | 22 | 1 |  | 72% | hard | hard |  |
| ME Ent / ME Ents of Fangorn | 24 | 1 |  | 72% | hard | hard |  |
| Fell Beast / Fell Beast | 15 | 0.3 |  | 75% | elite | normal | TOO STRONG (+2) |
| ME Iron Hills Warlord / ME Iron Hills Warlor | 22 | 1 |  | 75% | elite | hard | TOO STRONG (+1) |
| Easterling Warrior / Easterling Warrior | 16 | 0.3 |  | 78% | elite | normal | TOO STRONG (+2) |
| Corsair of Umbar / Corsair of Umbar | 16 | 0.3 |  | 78% | elite | normal | TOO STRONG (+2) |
| Stone Troll / Stone Troll | 17 | 0.3 |  | 78% | elite | normal | TOO STRONG (+2) |
| ME Barrow-wight Lord / ME Barrow-wight Lord | 32 | 2 | yes | 78% | elite | elite |  |
| ME Rohirrim Rider / ME Rohirrim Riders | 16 | 0.5 |  | 78% | elite | normal | TOO STRONG (+2) |
| Black Rider / Black Rider | 16 | 0.3 |  | 81% | elite | normal | TOO STRONG (+2) |
| Winged Nazgul / Winged Nazgûl | 24 | 0.8 |  | 81% | elite | hard | TOO STRONG (+1) |
| Corsair Captain / Corsair Captain | 22 | 0.6 |  | 84% | elite | hard | TOO STRONG (+1) |
| The Great Goblin / The Great Goblin | 26 | 0.8 | yes | 84% | elite | elite |  |
| The Mouth of Sauron / The Mouth of Sauron | 26 | 0.8 | yes | 84% | elite | elite |  |
| The Necromancer / The Necromancer | 32 | 1 | yes | 84% | elite | elite |  |
| The Balrog / The Balrog | 35 | 1 | yes | 84% | elite | elite |  |
| Ringwraith / Ringwraith | 20 | 0.6 |  | 88% | elite | hard | TOO STRONG (+1) |
| ME Grey Mountain Wyrm / ME Grey Mountain Wyr | 32 | 2 | yes | 88% | elite | elite |  |
| ME Marshal of the Mark / ME Marshal of the M | 22 | 1 |  | 88% | elite | hard | TOO STRONG (+1) |
| Smaug / Smaug | 35 | 1 | yes | 94% | elite | elite |  |
| The Witch-king of Angmar / The Witch-king of | 35 | 1 | yes | 97% | elite | elite |  |

## Re-tuning

Only clear outliers were changed: two tiers off, or bosses below elite. One-tier differences are within the noise. The levers were `life` (the main difficulty driver upstream) and `difficulty`/`spawnRate` where the measured strength fits the lore (Black Riders, Fell Beasts and Ringwraiths are meant to be dangerous):

| Enemy | Before | After | Measured tier | New values |
| --- | ---: | ---: | --- | --- |
| Black Rider / Black Rider | 81% | 66% | hard | life 14, difficulty 0.8 |
| Corsair Captain / Corsair Captain | 84% | 84% | elite | life 22, difficulty 0.8 |
| Corsair of Umbar / Corsair of Umbar | 78% | 50% | normal | life 13, difficulty 0.3 |
| Easterling Warrior / Easterling Warrior | 78% | 62% | hard | life 13, difficulty 0.3 |
| Fell Beast / Fell Beast | 75% | 56% | normal | life 15, difficulty 0.8 |
| ME Guard of the Citadel / ME Guard of the Citadel | ?% | 62% | hard | life 10, difficulty 0.1 |
| ME Iron Hills Warlord / ME Iron Hills Warlord | ?% | 62% | hard | life 20, difficulty 1 |
| ME Marshal of the Mark / ME Marshal of the Mark | ?% | 81% | elite | life 19, difficulty 1 |
| ME Old Man Willow / ME Old Man Willow | 62% | 81% | elite | life 35, difficulty 2 |
| ME Rohirrim Rider / ME Rohirrim Riders | 78% | 66% | hard | life 13, difficulty 0.5 |
| ME Southern Ruffian / ME Southern Ruffians | 62% | 41% | easy | life 9, difficulty 0.1 |
| ME Watcher in the Water / ME Watcher in the Water | ?% | 69% | hard | life 36, difficulty 2 |
| Ringwraith / Ringwraith | 88% | 75% | elite | life 20, difficulty 0.8 |
| Saruman / Saruman | 59% | 56% | normal | life 38, difficulty 1 |
| Shelob / Shelob | 69% | 78% | elite | life 35, difficulty 1 |
| Stone Troll / Stone Troll | 78% | 69% | hard | life 14, difficulty 0.6 |
| Winged Nazgul / Winged Nazgûl | 81% | 84% | elite | life 24, difficulty 1 |

Afterwards Guard of the Citadel was rated difficulty 0.3 (normal; it spawns in Gondor, not the start area).

**Still off after re-tuning:** **Saruman** (56% even at 38 life). The AI plays his control deck poorly, so it needs a deck rework (the AI favours proactive creature decks). **Watcher in the Water** is at the hard/elite border (69%). Check both in the play test.

## Open

- The deck contents were not changed in this pass. If play-testing shows an enemy feels wrong, rebuild its deck rather than only adjusting life.
- Sprites are reused from common. Some display names (e.g. Mûmak of Harad, Winged Nazgûl) use stand-in sprites.
