# WP4 starter decks: report

Work package WP4 from [PLAN.md](../PLAN.md) (decisions D1, D5, D6, D9, D10).
Branch `worktree-agent-ac206f8d82cc5306a`.

## Summary

- The plane no longer uses any common/Shandalar starter deck. All 60 starter references in `config.json` (4 difficulties × 3 modes × 5 colours) point at `MiddleEarth/decks/starter/ME/`.
- **Constructed mode** (the default mode on the new-game screen): 15 hand-built 40-card `.dck` decks, one Middle-earth faction each, every card from LTR/LTC/PLTC/HOB/HOC/MEAR. Easy = mono-coloured, Normal = allied pairs, Hard/Insane = enemy pairs, the same colour mapping as upstream.
- **Standard mode**: plane-local jumpstart templates. With the plane's starter edition LTR they build from the official **LTR Jumpstart** packs (Courageous, Tricksy, Mordor, Marauders, Journey), so they are in-pool. WP1's off-pool finding came from loading them without the starter edition (see below).
- **Pile mode**: plane-local copies of the upstream pile templates. Random cards are drawn from the plane's pool (`allowedEditions` filter in `RewardData`); basics are LTR.
- `check.sh -v`: every starter shows 0 off-pool cards, 0 errors. Warnings 743 → 713 (the 30 off-pool starter warnings are gone).
- `PlaneCheck.java` now loads starters exactly as `Config.starterDeck` does: Standard-mode jumpstart decks with each `starterEditions` entry, generated `.json` starters sampled 8 times, and a `minDeckSize` check.

- **Strength (coordinator re-measure after the agent stopped mid-tuning, `--g40 -n 40`):** 7 of 15 constructed decks are on par (44–59%). Too strong: Mirkwood 74%, Corsairs of Umbar 71%, Rivendell 68%, Wilderland 68%, Mordor 65%. Too weak: the Shire 35%, Isengard 39%, Lothlórien 39%. Tuning continues as a follow-up.

## 1. Inventory: how a new game picks its deck

`NewGameScene` builds the mode list from the **first** difficulty (`starterDecks` → Standard, `constructedStarterDecks` → Constructed, `pileDecks` → Pile, `commanderDecks` → Commander), then Precon / Commander precon (only if `decks/starter/precon/` or `commanderprecon/` holds decks, plane first, then common), Chaos, and Custom (the user's own custom starter decks). Constructed is preselected. `WorldSave` then calls `Config.starterDeck(color, difficulty, mode, index, starterEdition)`:

| Mode | Source | How it is loaded |
| --- | --- | --- |
| Standard | `difficulties[d].starterDecks[colour]` (or `starterDecksByEdition[edition][colour]` if set) | `CardUtil.getDeck(path, …, starterEdition, true)`. For `jumpstartPacks` JSON: one random pack per entry from the special boosters whose edition starts with the starter edition code (with no edition: JMP, J22, DMU, BRO, ONE, MOM). Each pack is padded to 20 cards. |
| Constructed | `constructedStarterDecks[colour]` | `.dck` file as is (printings are not filtered). |
| Pile | `pileDecks[colour]` | `mainDeck` reward templates (`colors`, `rarity`, `manaCosts`) drawn from `RewardData.getAllCards()`, which is filtered by `allowedEditions`. |
| Commander | `commanderDecks[colour]` | not configured for the plane, so the mode is hidden. |
| Precon / Commander precon | `decks/starter/precon/`, `commanderprecon/` | neither exists in the plane or in common, so hidden. |
| Chaos | `DeckgenUtil.getRandomOrPreconOrThemeDeck("", …, allowedEditions)` | engine code, not touched (see open questions). |
| Custom | user's custom starter decks | user content. |

`starterEditions` = `["LTR"]` makes the Standard mode show a "Starter Edition: Lord of the Rings" selector (seen in the GUI) and pass LTR to the jumpstart generator. The LTR Jumpstart sheets (`blockdata/boosters-special.txt`, `printsheets.txt`) are all LTR printings: 11 cards + 8 basics + 1 rare/mythic insert per pack, two variants per colour.

Innistrad, the template plane, keeps the upstream structure: jumpstart JSON per difficulty (`INNwhite_e.json` …, same pack colours as common), 15 `.dck` constructed decks (Easy mono "INN Low <colour>", Normal tribal, Hard named precons) and pile templates with an explicit `editions` filter. (Its config points at `decks/starter/INN/` while the folder is `inn/`; that only works on case-insensitive file systems. Not our plane, just noted.)

### Upstream intent per difficulty (kept)

| | Standard (jumpstart) | Constructed | Pile |
| --- | --- | --- | --- |
| Easy | 3 packs of the colour (60 cards) | mono-coloured "Low" deck | 60 cards: main colour + 1 splash colour, C/U, plus 9 rares of MV 5+ |
| Normal | 2 packs of the colour + 1 allied pack (60) | allied two-colour deck (W→WU, B→BR, U→UB, R→RG, G→GW) | 3 colours, C/U, plus 9 rares of MV 5+ |
| Hard / Insane | 1 pack + 1 pack of another colour (40) | enemy two-colour deck (W→WB, B→BG, U→UR, R→RW, G→GU) | 3 colours, commons, 9 C/U of MV 5–6 |

Upstream does not make the constructed decks weaker by difficulty; the colour pair changes and the difficulty comes from life, enemy life and money. I kept that and aimed all constructed decks at the D9 band, with the mono Easy decks allowed at the top of it and the Hard decks at the bottom.

## 2. Files and mapping

All under `forge-gui/res/adventure/MiddleEarth/decks/starter/ME/`.

_(Not written: the agent hit the usage limit. See the files in `decks/starter/ME/` and `config.json`.)_

## 3. Constructed decks

Construction rules: 40 cards, 17 lands (two-colour decks: 2 gain lands of the pair + 8/7 basics), about 15–17 creatures, 2–4 removal spells, at most 1–2 rares, no mythics. Set-specific printings (`Card|LTR`, first of LTR, HOB, LTC, HOC, PLTC, MEAR that prints the card); basics `|LTR`.

_(Not written: the agent hit the usage limit. See the files in `decks/starter/ME/` and `config.json`.)_

## 4. Strength

Constructed decks, final state of the branch, 80 games each against the 40-card kit cuts (run `wp4-final`):

| Deck | Cards | Games | W-L-D | Score | 95% CI | On play | On draw | Avg turns | Timeouts | Rating |
| --- | ---: | ---: | --- | ---: | --- | ---: | ---: | ---: | ---: | --- |
| Bree | 40 | 80 | 39-41-0 | **49%** | 38%–60% | 60% | 38% | 23.4 | 0 | on par with the starter kits |
| Erebor | 40 | 80 | 46-34-0 | **57%** | 47%–68% | 62% | 52% | 17.5 | 0 | on par with the starter kits |
| Gondor | 40 | 80 | 40-40-0 | **50%** | 39%–61% | 62% | 38% | 20.3 | 0 | on par with the starter kits |
| Isengard | 40 | 80 | 31-49-0 | **39%** | 29%–50% | 40% | 38% | 21.4 | 0 | weaker than the starter kits |
| The Istari | 40 | 80 | 44-36-0 | **55%** | 44%–65% | 60% | 50% | 19.9 | 0 | on par with the starter kits |
| Ithilien | 40 | 80 | 35-45-0 | **44%** | 33%–55% | 52% | 35% | 20.7 | 0 | on par with the starter kits |
| Lothlórien | 40 | 80 | 31-49-0 | **39%** | 29%–50% | 42% | 35% | 22.7 | 0 | weaker than the starter kits |
| Mirkwood | 40 | 80 | 59-21-0 | **74%** | 63%–82% | 75% | 72% | 22.6 | 0 | far too strong |
| Mordor | 40 | 80 | 52-28-0 | **65%** | 54%–75% | 68% | 62% | 18.3 | 0 | stronger than the starter kits |
| The Oathbreakers | 40 | 80 | 47-33-0 | **59%** | 48%–69% | 50% | 68% | 19.8 | 0 | stronger than the starter kits |
| Rivendell | 40 | 80 | 54-26-0 | **68%** | 57%–77% | 72% | 62% | 19.8 | 0 | stronger than the starter kits |
| Riders of Rohan | 40 | 80 | 47-33-0 | **59%** | 48%–69% | 72% | 45% | 18.8 | 0 | stronger than the starter kits |
| The Shire | 40 | 80 | 28-52-0 | **35%** | 25%–46% | 42% | 28% | 22.5 | 0 | weaker than the starter kits |
| Corsairs of Umbar | 40 | 80 | 57-23-0 | **71%** | 61%–80% | 65% | 78% | 18.8 | 0 | far too strong |
| Wilderland | 40 | 80 | 54-26-0 | **68%** | 57%–77% | 70% | 65% | 19.1 | 0 | stronger than the starter kits |

Standard mode builds from the official LTR Jumpstart packs. They vary a lot by pack (9–75% in runs r3/r4) and aren't tuned, because they are official product.

## 5. Tuning notes

_(Not written: the agent hit the usage limit. See the files in `decks/starter/ME/` and `config.json`.)_

## 6. Checker change (`tools/middleearth/PlaneCheck.java`)

- New `reportStarter`: loads a starter with `CardUtil.getDeck(path, …, starterEdition, true)` like `Config.starterDeck`. Standard-mode decks are checked once per `starterEditions` entry (or with no edition if the plane has none, which reproduces the JMP/J22 fallback). Generated `.json` starters are sampled 8 times; the report shows the union of off-pool cards and the size range. A deck smaller than `minDeckSize` is a warning; a deck that fails to generate (for example a starter edition without jumpstart packs) is an error.
- Also checks `commanderDecks` and `starterDecksByEdition` when a plane sets them.
- Booster-sheet parsing output is muted during generation.
- Enemy decks are unchanged (generated enemy decks are still skipped, WP5/WP6 territory).

## 7. GUI check

`game.sh` on display :105 with the main checkout's jar, three new characters, deck editor opened with `e` + Return:

| Character | Deck shown | Result |
| --- | --- | --- |
| Normal / Blue / Constructed | "Corsairs of Umbar", 40/40, LTR/LTC/MEAR printings | OK |
| Easy / Red / Pile | "Pile: Red", 60/60, MEAR/HOB/LTR/LTC cards | OK |
| Hard / Red / Standard (Starter Edition: Lord of the Rings) | "LTR Jumpstart: Marauders", 40/40, LTR | OK |

No exceptions in the game log. (Screenshots in `.me-test/gui/`, not committed.)

## 8. Open questions

_(Not written: the agent hit the usage limit. See the files in `decks/starter/ME/` and `config.json`.)_

## Tuning round 2

Target 45-55% against the two LTR starter kits (`deckstrength.sh --g40`). Iterations at n=30 (60 games); the final numbers are n=40 (80 games, 95% CI about +/-10 points), so single runs are noisy (the same deck moved 53% to 77% between runs).

| Deck | Before | After (n=40) | Swaps (out -> in) |
| --- | ---: | ---: | --- |
| Mirkwood | 74% | 52% | Shelob, Assassinate, Bitter Downfall, Claim the Precious, Gollum's Bite, 2 Shelob's Ambush, Old Fat Spider, most deathtouch/reach spiders (Attercop, Deadly Recluse, Netcaster, Penumbra) -> Mirkwood Spider, Twin-Silk/Canopy Spider, Front Porch Sentries, Carrion Crow, Grizzly Bears, Drudge Skeletons, Wargling |
| Umbar | 71% | 54% | The Mouth of Sauron, Essence Scatter, Unsummon, Claim the Precious, Deceive the Messenger, Gollum's Bite, Kitesail Freebooter, Mist Raven, Bloodhunter Bat -> Ruin Raider/Pelargir Survivor/Spectral Sailor, Dread Rider, Gravedigger, Fog Bank, Lakeshore Apothecary, Sanitarium Skeleton, Drudge Skeletons, Sea Gate Oracle, Duty-Bound Dead |
| Rivendell | 68% | 52% | Elrond Lord of Rivendell, Bewitching Leechcraft, Arcane Denial, Meneldor, one Knights of Dol Amroth, Horses of the Bruinen -> Grey Havens Navigator, Ithilien Kingfisher, Mirkwood Meditator, Long Lake Nuisance, Lakeshore Apothecary, Ravenhill Flock |
| Wilderland | 68% | 45% | Beorn Reluctant Host, Smite the Deathless, Fear Fire Foes -> Ordinary Bear, Little Bear, Iron Hills Stalwart |
| Mordor | 65% | 55% | Gothmog, Black Breath, Morgul-Knife Wound, Claim the Precious, Orcish Medicine, Mordor Muster, Troll of Khazad-dum, one Nazgul, Cirith Ungol Patrol, Snarling Warg, Uruk-hai Berserker, Gorbag, Mirkwood Bats -> cheap black bodies (Easterling Vanguard, Haunt of the Dead Marshes, skeletons, Front Porch Sentries, Dirtwater Wraith). The deck is mono-black and consistent, so it only came down once the removal and the efficient bodies were gone. |
| Shire | 35% | 48% | Brandywine Farmer, Revive the Shire, Gift of Strands, Stew the Coneys -> Cradle Guard, Cloudcrown Oak, Warden of the Woods, Nightpack Ambusher (rare) |
| Isengard | 39% | 45% | Goblin Fireleaper, Foray of Orcs, Orcish Medicine, Battle-Scarred Goblin -> Mardu Outrider, Headstrong Brute, Terror, Brassclaw Orcs |
| Lothlorien | 39% | 42% | Hithlain Knots, Glorious Gale, Elven Farsight, Isolation at Orthanc, Stew the Coneys, Galadhrim Bow, Shower of Arrows, Murder of Crows -> Galion, Elvish Warrior, Mirkwood Meditator, Elven Raft-Steerer, Galadriel Gift-Giver (rare), Knights of Dol Amroth, Gandalf Wandering Wizard |

Notes: Lothlorien is just under the band at n=40 (42%; 43% and 55-60% in earlier n=30 runs with Murder of Crows). All decks keep 16-17 lands, 40 cards, at most 2 rares and no mythics. `check.sh -v`: 0 errors, starter decks show no off-pool cards. In the simulation, cheap deathtouch/reach blockers and efficient removal inflate a deck's score far more than raw card quality.
