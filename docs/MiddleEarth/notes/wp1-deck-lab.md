# WP1 deck-lab: report

AI-vs-AI deck strength harness for decision D6. New starter decks should be about as strong as the official *LTR Starter Kit* decks, and enemy decks get a strength tier. Usage is documented in [DEVELOPMENT.md §3.4](../DEVELOPMENT.md#34-deck-strength-testing-ai-vs-ai).

## What was built

| File | Purpose |
| --- | --- |
| `tools/middleearth/deckstrength.sh` | Entry point. `build` builds the engine jar (`mvn package -pl forge-gui-desktop -am`, wagon transport, 4 retries). Everything else is passed to the Python driver. |
| `tools/middleearth/deckstrength/deckstrength.py` | Driver. Resolves inputs (`.dck`, adventure `.json`, `enemy:<Name>`, directories), stages the decks, builds the game list (every candidate × every gauntlet deck, half on the play and half on the draw, interleaved so partial results stay balanced), runs 3 worker JVMs, restarts dead workers, and writes `summary.md`, `summary.csv` and `games.csv`. A run can be resumed with `-o <dir>`. |
| `tools/middleearth/deckstrength/DeckSim.java` | Game worker (single-file Java on the `forge-gui-desktop` fat jar, cwd `forge-gui`). It does the same as `forge.view.SimulateMatch` (`sim` mode), with these changes: one fresh 1-game match per game; a **forced starting player** (a `LobbyPlayerAi` subclass whose controller overrides `chooseStartingPlayer`); a per-game timeout in its own thread, counted as a draw; starting life per side; and CSV output appended per game. It loads the card DB once per JVM. |
| `tools/middleearth/deckstrength/DeckExport.java` | Exports adventure decks to `.dck` with `CardUtil.getDeck`, the game's own loader, on the mobile-dev jar, with the plane set in an isolated `settings.json`. JSON decks are random, so it writes `--samples` of them. `enemy:<Name>` takes all decks of that enemy plus its `life`. |
| `forge-gui/res/adventure/MiddleEarth/decks/reference/` | The gauntlet: `ltr_starter_gondor_gw.dck` and `ltr_starter_mordor_br.dck`. |
| `.../reference/size40/` | 40-card proportional cuts of both kits (gauntlet for `--g40`). |
| `.../reference/baselines/` | Upstream scale references, copied unchanged except for the name: adventure constructed starters Low White and Low Orzhov, and enemy decks Goblin and Bandit (easy, 13 life), Abyssal Baron (hard, 30) and Adult Green Dragon (hard, 25). |

Why not plain `sim` mode? It works (first run: `java -jar forge-gui-desktop-…-jar-with-dependencies.jar sim -D <dir> -d a.dck b.dck -n 4 -q -c 120`, from `forge-gui/`, about 3 s per game). But within a match the **loser of the previous game goes first**, and the AI always chooses to play, so play/draw can't be controlled. Every JVM call also pays about 8 s of start-up. Also, `Main` initialises Sentry crash reporting. `DeckSim` reuses the same engine classes without any of that. **No engine change was needed.**

Gotchas found on the way:

- The desktop jar must be run with cwd `forge-gui/`. Otherwise `Localizer` fails: "Can't find bundle for base name en-US".
- `sim -d` resolves a deck file as `<-D dir>/<name>`, so an absolute path in `-d` without `-D` fails.
- The cloud build needed 3 attempts (byte-buddy and plexus-classworlds downloads dropped). The retry loop in `deckstrength.sh build` covers this.

## Reference decks: source

Forge ships neither kit: not in `res/quest/precons`, `res/quest/commanderprecons`, or the adventure decks. `magic.wizards.com` and `mtg.wiki` are blocked in the sandbox (DNS fails). The lists come from [taw/magic-preconstructed-decks](https://github.com/taw/magic-preconstructed-decks) `data/starter-kit/ltr/` (cloned through the git proxy), whose files cite the official Wizards article *"The Lord of the Rings: Tales of Middle-earth™ Starter Kit Decklists"* (2023-06-23). Both lists have exactly 60 cards and match the structure that web search snippets show (Star City Games: GW with 11 Forest, 11 Plains, 2 Shire Terrace, 2 Graypelt Refuge; BR headed by Sauron, the Lidless Eye). Printings: LTR, except Banishing Light, Graypelt Refuge, Feed the Swarm, Mind Stone and Evolving Wilds, which are LTC, as in the kit. All cards are in the plane's pool. Someone with web access should do one check against the Wizards page.

- **Gondor (GW)**: Aragorn and Arwen, Wed; Frodo, Determined Hero; Gandalf, White Rider; Galadriel, Gift-Giver; Bilbo's Ring; creatures of the go-wide/food style, 26 lands.
- **Mordor (BR)**: Sauron, the Lidless Eye; Gollum, Scheming Guide; Witch-king, Bringer of Ruin; Fires of Mount Doom; The Balrog, Flame of Udûn; orcs and removal, Mind Stone, 26 lands (incl. 4 Evolving Wilds).

## Measurements

All runs at 20 life unless noted, Forge's default AI profile, Constructed rules. Raw results: `.me-test/deckstrength/v1…v5/` (not committed).

**Gauntlet balance and noise (v1, 200 games, Gondor vs Mordor):** Gondor 55% (95% CI 48–61%), Mordor 45%. Split into 4 blocks of 50 games, Gondor scored 52 / 54 / 60 / 52%. The block-to-block spread matches the binomial noise (±7 points at n = 50). The two kits are close in strength, which makes a sensible gauntlet. On the play: 51% wins.

**Upstream decks vs the 60-card gauntlet (v2, 120 games each):**

| Deck | Cards | Score | 95% CI |
| --- | ---: | ---: | --- |
| Adventure starter Low Orzhov (Hard/Insane constructed starter) | 40 | 73% | 65–80 |
| Adventure starter Low Black | 40 | 70% | 61–77 |
| Adventure starter Low White (Easy constructed starter) | 40 | 69% | 60–77 |
| Adventure starter Low Red | 40 | 40% | 32–49 |
| Adventure starter Low Blue | 40 | 38% | 30–47 |
| Adventure starter Low Green | 40 | 31% | 23–40 |
| ME `decks/starter/white_e.json`, sample 1 / 2 (3 JMP packs) | 60 | 48% / 72% | |
| ME `decks/starter/black_h.json`, sample 1 / 2 | 40 | 55% / 50% | |
| Enemy Adult Green Dragon (hard) | 71 | 55% | 46–64 |
| Enemy Bandit (easy) | 60 | 45% | 36–54 |
| Enemy Goblin (easy) | 60 | 43% | 34–52 |
| Enemy Abyssal Baron (hard) | 80 | 38% | 30–47 |

**40 vs 60 cards (v3):** proportional 40-card cuts of the kits (same land ratio, one copy fewer of each multiple, a mix of good and weak singletons removed) against the 60-card gauntlet: Gondor-40 60% (it was 55% at 60 cards), Mordor-40 66% (was 45%). Against its own 60-card original: 60% and 68%. **Going to 40 cards alone is worth about +8 to +15 points.** Retesting the 40-card adventure starters against the 40-card cuts (`--g40`, v5) gives Low White 62%, Low Black 59%, Low Red 32% and Low Green 32%, about 8 points lower than against the 60-card gauntlet.

**Enemies at their real life (v4, `--enemy-life`, gauntlet at 16 life):** Bandit (13) 27%, Goblin (13) 48%, Abyssal Baron (30) 61%, Adult Green Dragon (25) 72%. Upstream easy and hard enemy decks are indistinguishable at 20 life (38–55%). The difficulty comes from the `life` field.

**Runtime:** average game 1.6–2.0 s on an idle machine and 3.6–3.9 s with other agents loading all cores (median 1.4–2.8 s, p95 3–10 s, max 43 s). 1 timeout in about 3,000 games (Goblin vs Gondor, 120 s), and 0 errors. 3 workers play about 1.5 games/s idle and about 0.8 games/s under load. In every game the designated player went first. The play advantage is small for these decks (51–56% wins on the play).

## Recommendations

- **Default N = 60 games per gauntlet deck** (120 per candidate, ±9 points, 1.5–4 min). Use it for screening and tuning loops. For a final sign-off use `-n 150` (300 games, ±6 points, 4–10 min).
- **Starter decks:** WP4 starters are 40 cards, so rate them with `--g40` and aim for **42–58%, ideally 45–55%**. The equivalent target against the 60-card gauntlet is about 52–68%. Several current upstream adventure starters (Low White, Black, Orzhov) are clearly stronger than the LTR kits, and Low Green/Blue/Red are weaker. "LTR kit level" is a real constraint, not just the status quo.
- **Bands** (vs the gauntlet, 20 life): <30% far too weak, 30–42% weaker, **42–58% on par**, 58–70% stronger, >70% far too strong.
- **Enemy tiers** (`--enemy-life`, enemy at its own life vs the gauntlet at 16): <45% easy, 45–60% normal, 60–75% hard, >75% elite/boss. These are calibrated on only 4 upstream enemies, so treat them as rough and recalibrate once WP5/WP6 have more decks. Two levers set an enemy's tier: its deck (score at 20 life) and its life. Keep easy enemies at ≤ about 45% deck score with 10–14 life.
- **MEAR decks (WP2):** no harness change needed. Run from a tree where `forge-gui/res/editions/Middle-earth Adventure Reprints.txt` exists, so `Card|MEAR` resolves to the reprint printing. MEAR cards are existing cards, so the score doesn't depend on the printing.

## Caveats

- **AI vs AI only.** Forge's AI plays aggressive creature decks well and plays combo/control, complex triggers and "the Ring tempts you" lines less well. A deck that relies on clever play is underrated. Play-test decks near a band edge.
- The 40-card cuts are my own judgment cuts (documented in each file's `Comment`). A different cut would move the `--g40` baseline by a few points.
- JSON decks (jumpstart/template generators) vary a lot between samples (ME `white_e.json`: 48% vs 72%). Rate them with several `--samples` and look at the spread, not just the mean.
- Constructed rules at 20 life by default. The game itself uses difficulty-dependent player life (Easy 16, Normal 12, Hard 8) and enemy life × `enemyLifeFactor`, plus items and boons, which the harness doesn't model.

## Findings for other work packages

- **WP4:** the plane's current `decks/starter/*_e.json` (jumpstart packs) produce **non-ME cards** (JMP/M21/J22 printings such as Basri Ket and Thriving Heath), because jumpstart packs aren't filtered by `allowedEditions`. `check.sh` doesn't catch this, since it skips generated decks. The new starters should be fixed `.dck` lists.
- **WP5/WP6:** template JSON enemy decks degenerate under the restricted pool. `enemy:Troll` (`troll.json`, R/G Troll tribe, 60 cards) exported as **32 × The Lonely Mountain + 27 Mountains + 1 Olog-hai Crusher**. Avoid template `.json` decks for ME enemies, or check them with `DeckExport` before use.
- The upstream `difficulty` field of enemies is mostly 0.1 and doesn't track strength. `life` does.

## Open questions

- Should the official 60-card kits remain the reference for 40-card starters (target about 52–68% vs 60), or the 40-card cuts (`--g40`, target 42–58%)? I recommend `--g40`.
- Is a third, weaker reference wanted (for example an LTR Jumpstart pair) to pin down the "easy" end of the scale?
