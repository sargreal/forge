# WP2: Middle-earth reprint set (MEAR)

Work package WP2 from [PLAN.md](../PLAN.md) (decisions D1, D5, D7, D8).
Branch `worktree-agent-a0a703d0c8563fe2a`.

## Summary

- **Set:** `MEAR`, "Middle-earth Adventure Reprints", file `forge-gui/res/editions/Middle-earth Adventure Reprints.txt`. 223 existing Magic cards (C 120, U 70, R 28, M 5), in 21 theme groups. Every card has a Forge script, and none of them was already in LTR/LTC/PLTC/HOB/HOC.
- **Art:** each entry names the printing whose image it shows, e.g. `15 C Giant Spider @Randy Gallegos ${"artSet": "M10", "artNum": "185"}`. Images are downloaded automatically from that printing (cardforge mirror, then Scryfall) and cached as `MEAR/<Card Name>.full.jpg`. No new artwork.
- **Engine change:** one small, isolated change in the image fetcher, plus three accessors on `CardEdition.EditionEntry`. It needs a row in PLAN.md → "Engine changes" (text below).
- **Plane:** `MEAR` is added to `allowedEditions` in `MiddleEarth/config.json`. The unique card pool grows from 844 to 1,067 cards. Rewards, shops, `getFullCardPool` and `ensureAllowedEdition` pick MEAR printings. `check.sh` still reports 0 errors and has a new "Reprint editions" section.
- **Enemy decks (D7):** of the 192 upstream `.dck` decks that fit Middle-earth, off-pool card copies drop from 8,164 to 7,251. Only 4 decks become "small swap" reuses and 9 more "moderate swap" reuses. Most upstream decks are built around long tails of set-specific cards, so a reprint set can't make them reusable on its own. The template (`.json`) decks benefit more: for example, Spiders go from 6 to 16 creatures in the pool, Trolls from 3 to 9, Treefolk from 11 to 25 and Wolves from 13 to 24.

## 1. Mechanism

### How editions and images work (findings)

- **Edition files** (`forge-core` `CardEdition.Reader`): `[metadata]` keys `Code`, `Code2`, `Name`, `Date`, `Type`, `ScryfallCode` (defaults to `Code`) and others. The `[cards]` lines are matched by `CARD_PATTERN`, `<cn> <rarity> <name> @<artist> ${"key": "value", ...}`. The `${…}` block is parsed into `EditionEntry.extraParams`, with keys lowercased. Upstream only uses `flavorName` and `variant` (Crimson Vow's Dracula cards). Unknown keys are kept and ignored, so new keys are backward compatible.
- **`Type`:** in `res/editions` the value goes through `Type.valueOf(upper)`. The documented `Type=Custom` therefore turns into `UNKNOWN` (logged as "Ignoring unknown type"). Only files in the user's `~/.forge/custom/editions` are forced to `CUSTOM_SET`. `CUSTOM_SET` only has a few effects: token images are skipped, and the deck list flags the deck as "custom format".
- **Image key and cache file:** `ImageUtil.getImageRelativePath` → `<Code2 of the edition>/<Card Name><art index>.full`. So a MEAR card is cached as `…/pics/cards/MEAR/Giant Spider.full.jpg`, separately from every other printing.
- **Download URLs** (`forge-gui` `ImageFetcher.fetchImage`, used by both the libGDX/adventure and the Swing client):
  1. `https://downloads.cardforge.org/images/cards/<Code2>/<name>.full.jpg` (`ImageUtil.getDownloadUrl`)
  2. a Scryfall CDN URL if the set's UUID cache is present
  3. `https://api.scryfall.com/cards/<ScryfallCode>/<collector number>/<lang>?format=image&version=normal|art_crop` (`ImageUtil.getScryfallDownloadUrl`)

  The Scryfall set code is **per edition**, and the collector number is the card's number **in that edition**. A set that mixes cards from many source printings therefore can't point at them without help.
- **`setlookup/`** (`res/setlookup/<CODE>.txt`) only lets a set's image folder fall back to *other local folders* with the same file name, and it lets the fetcher try *any* other printing. It can't select a specific printing.
- **Adventure-local options:** `MiddleEarth/editions/*.txt` is read by `AdventureOverrides`, but only for booster templates of existing editions. It doesn't add cards. `common/custom_cards` holds new-rules cards (D8) with the `UNKNOWN` edition and `ADVENTURECARD_PREFIX` images from `custom_card_pics`. Neither can hold a reprint set, so the edition has to be global, in `forge-gui/res/editions/`. It is visible in classic Forge too, which is fine for the fork.

### Options considered

| Option | Engine change | Verdict |
| --- | --- | --- |
| One edition per source set (`ScryfallCode=<source>`, same collector numbers as the source) | none | Works for downloads, but it needs about 60 tiny editions and 60 `allowedEditions` entries, and art changes mean moving cards between files. Rejected. |
| `setlookup` or hand-placed images in the cache | none | Not automatic, or it can't pick a specific printing. Rejected. |
| `Type=Custom` and user-supplied images | none | Images would have to be shipped by hand. Rejected. |
| **Per-card art source in the edition file, honoured by the image fetcher** | about 30 lines | **Chosen.** It reuses the existing `${…}` syntax, keeps one edition file and one `allowedEditions` entry, and downloads automatically. |

### The engine change (for PLAN.md → "Engine changes")

| Change | Files | Why |
| --- | --- | --- |
| Per-card art source for reprint editions: an edition entry may carry `${"artSet": "<set>", "artNum": "<collector number>"}`. `ImageFetcher` builds the download URLs (cardforge and Scryfall, both for full card and art crop, plus sleeve art) from that printing, while the file is still cached under the reprint's own key. | `forge-core/src/main/java/forge/card/CardEdition.java` (`EditionEntry.hasArtSource/getArtSourceSet/getArtSourceNumber`), `forge-gui/src/main/java/forge/util/ImageFetcher.java` (`getArtSourcePrinting`, used in `fetchImage` and `fetchSleeveArt`) | Lets the MEAR reprint set (D5) show a chosen existing printing's art, with no new artwork and automatic download. |

Details:

- `ImageFetcher.getArtSourcePrinting(PaperCard)` looks up the card's `EditionEntry` (by name and collector number) and returns `CardDb.getCard(name, artSet, artNum)`, but only if that exact printing exists, because `getCard` silently falls back to another printing. Otherwise it returns the card itself. For cards without the parameter, the behaviour is unchanged.
- In `fetchImage` only the *URL* inputs switch to the source printing (`getDownloadUrl`, the collector-number check, `getScryfallDownloadURL`). `filename`/`destFile` still come from the MEAR card, so the downloaded image ends up exactly where `ImageKeys` looks for it.
- Not covered: the desktop bulk "Download all pictures" (`GuiDownloadPictures*`) builds URLs from the edition's `ScryfallCode`, so it would try `mear/<n>` and fail for MEAR. The adventure client fetches on demand through `ImageFetcher`, so this doesn't affect the plane.

Verified by tracing (the sandbox proxy blocks Scryfall and cardforge, so no real download was possible). This uses the built jar and a scratch `ArtTrace.java` that calls the real `ImageFetcher.getArtSourcePrinting`, `ImageUtil.getImageRelativePath`, `getDownloadUrl` and `getScryfallDownloadUrl`:

```
Giant Spider    | file=MEAR/Giant Spider.full | artist=Randy Gallegos | rarity=C
   hosted   https://downloads.cardforge.org/images/cards/M10/Giant%20Spider.full.jpg
   scryfall https://api.scryfall.com/cards/m10/185/en?format=image&version=normal
Brassclaw Orcs  | file=MEAR/Brassclaw Orcs.full
   hosted   https://downloads.cardforge.org/images/cards/FE/Brassclaw%20Orcs1.full.jpg
   scryfall https://api.scryfall.com/cards/fem/49a/en?format=image&version=normal
Ambitious Farmhand (transforming DFC)
   scryfall https://api.scryfall.com/cards/mid/2/en?format=image&version=normal&face=front
art source resolved for 223/223
default-preference lookups that pick MEAR: 0
```

`game.sh smoke` with the rebuilt jar passes: menu, new game, overworld, world map, no exceptions and no POI retries.

## 2. The set and how it plugs into the plane

```
[metadata]
Code=MEAR
Code2=MEAR
Date=1993-01-01
Name=Middle-earth Adventure Reprints
Type=Other
```

- **Code:** `MEAR` doesn't collide with any `Code`, `Code2`, `Alias` or `ScryfallCode` in `forge-gui/res/editions` (checked with grep), and it isn't a Scryfall set code.
- **`Type=Other` and `Date=1993-01-01`** keep MEAR from becoming the preferred printing *outside* the plane. The default art preference (`LATEST_ART_ALL_EDITIONS`) picks the newest printing, and the "core/expansion only" preferences ignore `Other`. The trace above confirms that 0 of 223 name-only lookups pick MEAR. Only the non-default `ORIGINAL_ART_ALL_EDITIONS` preference would prefer it. A `CUSTOM_SET` type isn't possible from `res/editions` (see above), and it would also have hidden token art.
- **No booster** (`Other` has no booster defaults), so MEAR never shows up as an adventure event or booster set.
- **Pool:** `allowedEditions` = `LTR, LTC, PLTC, HOB, HOC, MEAR`. Everything in adventure (`RewardData` filter `printedInAnyEditions`, `CardUtil.getFullCardPool`, `getCardByName`, `ensureAllowedEdition`, `sanitizeCardPool`, the SpellSmith edition list, and the `TokenDb` edition filter) is edition-code based, so MEAR printings behave exactly like LTR printings. `check.sh` confirms this: getFullCardPool has 0 non-allowed printings, and ensureAllowedEdition maps the other printings of all 223 cards to MEAR ("checked 491 reprints", up from 268).

### Rarity policy

Adventure rewards and shops filter by the printing's rarity, so the MEAR rarity is what counts.

1. **Start from the card's usual rarity** in its regular core or expansion printings.
2. **Adjust for power inside a ~1,000-card pool**:
   - Old cards whose rarity reflects the 1993–97 print runs rather than their power go **down**: Will-o'-the-Wisp, Elvish Archers, Uthden Troll and Two-Headed Giant drop to U or C. Mardu Outrider and Ruin Raider go to U.
   - Efficient staples go **up**: Lightning Bolt and Counterspell are U.
   - Board wipes, lords, cheap bombs and the dual land stay R: Wrath of God, Damnation, Grave Pact, Death Baron, Goblin King, Royal Assassin, Control Magic, Rootbound Crag.
3. **Mythic** is reserved for 5 big set pieces, one per faction: Wingmate Roc (Great Eagles), Crested Sunmare (Shadowfax), Brutal Hordechief (orc warlord), Thundermaw Hellkite (Smaug-like dragon) and Dire Fleet Ravager (Corsair captain).
4. **Lands:** gain lands and cycling lands are C, the Fallen Empires ruins and Leechridden Swamp are U, and Rootbound Crag is R. LTC already supplies the other nine check lands, four gain lands, two cycling lands and Evolving Wilds/Terramorphic Expanse.

Result: C 120 (54%), U 70 (31%), R 28 (13%), M 5 (2%). This is close to a normal set's curve, so commons and uncommons support varied decks.

Colour spread (non-land): W 30, U 22, B 47, R 46, G 49, multicolour 4, colourless 8, lands 17. Black, red and green are deliberately heavier, because LTR/LTC are already rich in white (Gondor/Rohan) and blue (Elves) and short on monsters.

## 3. Card list (art source per card)

Collector numbers are MEAR's own. "Art source" is the printing whose image the card shows (set code, collector number, set name). To change the art of a card, edit its `artSet`/`artNum` (and `@artist`) in the edition file. `check.sh` verifies that the printing exists and that the artist matches.

Art selection rules I followed: prefer the printing whose art reads as Tolkien-like fantasy (forests, mountains, mail and leather, no cityscapes or sci-fi). When the art is the same, prefer the modern-frame reprint (for example 10E or M10 over Alpha) for readability. Avoid Kaladesh, New Phyrexia, Mirrodin and Ravnica city backgrounds where an alternative exists. Notable picks: Penumbra Spider (MMA reprint of the Jeff Easley art), Will-o'-the-Wisp (A25, the Rob Alexander marsh-light), Nightmare (M14 Vance Kovacs, as a fell beast), Pillage (A25 Jesper Ejsing), Raise the Alarm (M15, not the Mirrodin original), Furnace Whelp (IMA new art, not 5DN Mirrodin), and Prodigal Sorcerer (EMA).

Excluded on purpose: planeswalkers, legendary creatures, Phyrexians, Eldrazi, artifact creatures with a sci-fi look, and Un-cards. A few cards keep a non-Tolkien place name (Seven Dwarves, Witch's Cottage, Haunted Fengraf, Mardu Outrider, Dire Fleet …) because enemy decks need them and the art fits.

#### Wolves & Wargs

| # | Card | Cost | Rarity | Art source | Artist |
| ---: | --- | --- | :---: | --- | --- |
| 1 | Howlpack Wolf | 2 R | C | SOI 164 (Shadows over Innistrad) | Svetlin Velinov |
| 2 | Russet Wolves | 3 R | C | DKA 102 (Dark Ascension) | Christopher Moeller |
| 3 | Timberpack Wolf | 1 G | C | M13 194 (Magic 2013) | John Avon |
| 4 | Young Wolf | G | C | DKA 134 (Dark Ascension) | Ryan Pancoast |
| 5 | Arctic Wolves | 3 G G | U | WTH 118 (Weatherlight) | Steve White |
| 6 | Briarpack Alpha | 3 G | U | M14 166 (Magic 2014) | Daarken |
| 7 | Carrion Howler | 3 B | U | RAV 79 (Ravnica: City of Guilds) | John Zeleznik |
| 8 | Mardu Outrider | 1 B B | U | ANB 52 (Arena Beginner Set) | Evan Shipard |
| 9 | Pack Guardian | 2 G G | U | SOI 221 (Shadows over Innistrad) | Filip Burburan |
| 10 | Packsong Pup | 1 G | U | VOW 213 (Innistrad: Crimson Vow) | April Prime |
| 11 | Nightpack Ambusher | 2 G G | R | M20 185 (Core Set 2020) | Dan Murayama Scott |
| 12 | Wicked Wolf | 2 G G | R | ELD 181 (Throne of Eldraine) | Tomasz Jedruszek |

#### Spiders of Mirkwood

| # | Card | Cost | Rarity | Art source | Artist |
| ---: | --- | --- | :---: | --- | --- |
| 13 | Canopy Spider | 1 G | C | 10E 254 (Tenth Edition) | Christopher Rush |
| 14 | Deadly Recluse | 1 G | C | M10 175 (Magic 2010) | Warren Mahy |
| 15 | Giant Spider | 3 G | C | M10 185 (Magic 2010) | Randy Gallegos |
| 16 | Mammoth Spider | 4 G | C | DOM 171 (Dominaria) | Lars Grant-West |
| 17 | Netcaster Spider | 2 G | C | M15 186 (Magic 2015) | Yohann Schepacz |
| 18 | Twin-Silk Spider | 2 G | C | MH1 188 (Modern Horizons) | Ben Maier |
| 19 | Penumbra Spider | 2 G G | U | MMA 157 (Modern Masters) | Jeff Easley |
| 20 | Rotwidow Pack | 2 B G | U | MH1 212 (Modern Horizons) | Kev Walker |
| 21 | Doom Weaver | 4 B B | R | VOC 34 (Innistrad: Crimson Vow Commander) | Helge C. Balzer |
| 22 | Silklash Spider | 3 G G | R | M13 191 (Magic 2013) | Iain McCaig |

#### Eagles, Crebain & Bats

| # | Card | Cost | Rarity | Art source | Artist |
| ---: | --- | --- | :---: | --- | --- |
| 23 | Battleflight Eagle | 4 W | C | M13 7 (Magic 2013) | Kev Walker |
| 24 | Bloodhunter Bat | 3 B | C | M13 82 (Magic 2013) | Tomasz Jedruszek |
| 25 | Carrion Crow | 2 B | C | M15 88 (Magic 2015) | Aaron Miller |
| 26 | Cloudchaser Eagle | 3 W | C | TMP 15 (Tempest) | Una Fricker |
| 27 | Crow of Dark Tidings | 2 B | C | SOI 105 (Shadows over Innistrad) | Tianhua X |
| 28 | Duskhunter Bat | 1 B | C | M12 97 (Magic 2012) | Jesper Ejsing |
| 29 | Mist Raven | 2 U U | C | AVR 67 (Avacyn Restored) | John Avon |
| 30 | Murder of Crows | 3 U U | U | ISD 70 (Innistrad) | Drew Baker |
| 31 | Wingmate Roc | 3 W W | M | KTK 31 (Khans of Tarkir) | Mark Zug |

#### Ents & Huorns

| # | Card | Cost | Rarity | Art source | Artist |
| ---: | --- | --- | :---: | --- | --- |
| 32 | Ambassador Oak | 3 G | C | MOR 113 (Morningtide) | Steve Prescott |
| 33 | Blanchwood Treefolk | 4 G | C | USG 238 (Urza's Saga) | DiTerlizzi |
| 34 | Cloudcrown Oak | 2 G G | C | LRW 201 (Lorwyn) | Rebecca Guay |
| 35 | Ironroot Treefolk | 4 G | C | LEA 203 (Limited Edition Alpha) | Jesper Myrfors |
| 36 | Oakgnarl Warrior | 5 G G | C | LRW 232 (Lorwyn) | Jim Nelson |
| 37 | Redwood Treefolk | 4 G | C | POR 183 (Portal) | Steve Luke |
| 38 | Cradle Guard | 1 G G | U | USG 245 (Urza's Saga) | Mark Zug |
| 39 | Mossbeard Ancient | 5 G G | U | DMU 173 (Dominaria United) | Alexandre Honoré |
| 40 | Treefolk Harbinger | G | U | LRW 239 (Lorwyn) | Larry MacDougall |
| 41 | Treefolk Seedlings | 2 G | U | USG 278 (Urza's Saga) | John Matson |
| 42 | Warden of the Woods | 4 G G | U | M21 213 (Core Set 2021) | Svetlin Velinov |
| 43 | Yew Spirit | 4 G | U | AVR 207 (Avacyn Restored) | Dan Murayama Scott |
| 44 | Indomitable Ancients | 2 W W | R | MOR 13 (Morningtide) | Pete Venters |
| 45 | Willow Geist | G | R | MID 207 (Innistrad: Midnight Hunt) | Ryan Yee |

#### Trolls & Giants

| # | Card | Cost | Rarity | Art source | Artist |
| ---: | --- | --- | :---: | --- | --- |
| 46 | Craven Giant | 2 R | C | STH 78 (Stronghold) | Brian Snõddy |
| 47 | Hagi Mob | 4 R | C | KHM 140 (Kaldheim) | Steve Prescott |
| 48 | Hill Giant | 3 R | C | 10E 212 (Tenth Edition) | Kev Walker |
| 49 | Icehide Troll | 2 G | C | KHM 176 (Kaldheim) | Andrey Kuzinskiy |
| 50 | Uthden Troll | 2 R | C | LEA 180 (Limited Edition Alpha) | Douglas Shuler |
| 51 | Cudgel Troll | 2 G G | U | M10 174 (Magic 2010) | Jesper Ejsing |
| 52 | Stone Giant | 2 R R | U | M10 159 (Magic 2010) | Warren Mahy |
| 53 | Two-Headed Giant | 2 R R | U | DOM 147 (Dominaria) | Simon Dominic |
| 54 | Clackbridge Troll | 3 B B | R | ELD 84 (Throne of Eldraine) | Svetlin Velinov |
| 55 | Troll Ascetic | 1 G G | R | 10E 305 (Tenth Edition) | Puddnhead |

#### Orcs & Goblins

| # | Card | Cost | Rarity | Art source | Artist |
| ---: | --- | --- | :---: | --- | --- |
| 56 | Armory Veteran | 1 R | C | AFR 130 (Dungeons & Dragons: Adventures in the Forgotten Realms) | Caio Monteiro |
| 57 | Brassclaw Orcs | 2 R | C | FEM 49a (Fallen Empires) | Rob Alexander |
| 58 | Dragon Fodder | 1 R | C | ALA 97 (Shards of Alara) | Jaime Jones |
| 59 | Goblin Instigator | 1 R | C | M19 142 (Core Set 2019) | Filip Burburan |
| 60 | Ironclaw Orcs | 1 R | C | LEA 159 (Limited Edition Alpha) | Anson Maddocks |
| 61 | Krumar Bond-Kin | 3 B B | C | KTK 77 (Khans of Tarkir) | Kev Walker |
| 62 | Mogg Fanatic | R | C | 10E 219 (Tenth Edition) | Brom |
| 63 | Mogg War Marshal | 1 R | C | TSP 170 (Time Spiral) | Wayne England |
| 64 | Orcish Vandal | 1 R | C | DOM 137 (Dominaria) | Alex Konstad |
| 65 | Raging Goblin | R | C | 10E 224 (Tenth Edition) | Jeff Miracola |
| 66 | Beetleback Chief | 2 R R | U | EMA 119 (Eternal Masters) | Wayne England |
| 67 | Bloodmark Mentor | 1 R | U | SHM 83 (Shadowmoor) | Dave Allsop |
| 68 | Dragonlord's Servant | 1 R | U | DTK 138 (Dragons of Tarkir) | Steve Prescott |
| 69 | Orc Sureshot | 3 B | U | FRF 78 (Fate Reforged) | Kev Walker |
| 70 | Orcish Artillery | 1 R R | U | 10E 220 (Tenth Edition) | Jeff Miracola |
| 71 | Orcish Lumberjack | R | U | ICE 210 (Ice Age) | Dan Frazier |
| 72 | Tattermunge Maniac | RG | U | SHM 219 (Shadowmoor) | Matt Cavotta |
| 73 | Vengeful Warchief | 4 B | U | M20 121 (Core Set 2020) | Karl Kopinski |
| 74 | Blood-Chin Fanatic | 1 B B | R | DTK 88 (Dragons of Tarkir) | David Palumbo |
| 75 | Goblin King | 1 R R | R | 10E 207 (Tenth Edition) | Ron Spears |
| 76 | Pitiless Horde | 2 B | R | DTK 112 (Dragons of Tarkir) | Viktor Titov |
| 77 | Brutal Hordechief | 3 B | M | FRF 64 (Fate Reforged) | Tyler Jacobson |

#### Rohirrim & Men of Gondor

| # | Card | Cost | Rarity | Art source | Artist |
| ---: | --- | --- | :---: | --- | --- |
| 78 | Ambitious Farmhand | 1 W | C | MID 2 (Innistrad: Midnight Hunt) | Bryan Sola |
| 79 | Armored Warhorse | W W | C | M12 7 (Magic 2012) | rk post |
| 80 | Cavalry Drillmaster | 1 W | C | M19 8 (Core Set 2019) | Slawomir Maniak |
| 81 | Doomed Traveler | W | C | ISD 11 (Innistrad) | Lars Grant-West |
| 82 | Elite Vanguard | W | C | M10 9 (Magic 2010) | Mark Tedin |
| 83 | Gather the Townsfolk | 1 W | C | DKA 8 (Dark Ascension) | Dan Murayama Scott |
| 84 | Raise the Alarm | 1 W | C | M15 26 (Magic 2015) | Zoltan Boros |
| 85 | Veteran Swordsmith | 2 W | C | M10 39 (Magic 2010) | Michael Komarck |
| 86 | Cavalry Master | 2 W W | U | TSP 6 (Time Spiral) | Thomas M. Baxa |
| 87 | Courageous Outrider | 3 W | U | EMN 18 (Eldritch Moon) | Ryan Alexander Lee |
| 88 | Shield Mare | 1 W W | U | M19 37 (Core Set 2019) | Lucas Graciano |
| 89 | White Knight | W W | U | M10 41 (Magic 2010) | Christopher Moeller |
| 90 | Knight of the White Orchid | W W | R | ALA 16 (Shards of Alara) | Mark Zug |
| 91 | Crested Sunmare | 3 W W | M | HOU 6 (Hour of Devastation) | Lucas Graciano |

#### Rangers of the North

| # | Card | Cost | Rarity | Art source | Artist |
| ---: | --- | --- | :---: | --- | --- |
| 92 | Archers' Parapet | 1 G | C | KTK 128 (Khans of Tarkir) | Wayne Reynolds |
| 93 | Borderland Ranger | 2 G | C | M10 169 (Magic 2010) | Jesper Ejsing |
| 94 | Ranger's Guile | G | C | M15 193 (Magic 2015) | Steve Prescott |
| 95 | Sylvan Ranger | 1 G | C | M11 198 (Magic 2011) | Christopher Moeller |
| 96 | Longbow Archer | W W | U | VIS 12 (Visions) | Eric Peterson |

#### Elves

| # | Card | Cost | Rarity | Art source | Artist |
| ---: | --- | --- | :---: | --- | --- |
| 97 | Birchlore Rangers | G | C | ONS 248 (Onslaught) | Dany Orizio |
| 98 | Elvish Ranger | 2 G | C | ALL 88a (Alliances) | Terese Nielsen |
| 99 | Elvish Warrior | G G | C | ONS 260 (Onslaught) | Christopher Moeller |
| 100 | Elven Riders | 3 G G | U | ONS 254 (Onslaught) | Darrell Riche |
| 101 | Elvish Archers | 1 G | U | LEA 191 (Limited Edition Alpha) | Anson Maddocks |

#### Dwarves

| # | Card | Cost | Rarity | Art source | Artist |
| ---: | --- | --- | :---: | --- | --- |
| 102 | Axgard Braggart | 3 W | C | KHM 1 (Kaldheim) | Svetlin Velinov |
| 103 | Axgard Cavalry | 1 R | C | KHM 121 (Kaldheim) | Evyn Fong |
| 104 | Breakneck Berserker | 2 R | C | KHM 124 (Kaldheim) | Scott Murphy |
| 105 | Goldmaw Champion | 2 W | C | KHM 14 (Kaldheim) | Slawomir Maniak |
| 106 | Master Skald | 4 W | C | KHM 19 (Kaldheim) | Svetlin Velinov |
| 107 | Plundering Barbarian | 2 R | C | AFR 158 (Dungeons & Dragons: Adventures in the Forgotten Realms) | Andrew Mar |
| 108 | Rimrock Knight | 1 R | C | ELD 137 (Throne of Eldraine) | Chris Rallis |
| 109 | Seven Dwarves | 1 R | C | ELD 141 (Throne of Eldraine) | Jason Rainville |
| 110 | Dwarven Blastminer | 1 R | U | ONS 199 (Onslaught) | Gary Ruddell |
| 111 | Dwarven Driller | 3 R | U | JUD 85 (Judgment) | Edward P. Beard, Jr. |
| 112 | Dwarven Miner | 1 R | U | MIR 169 (Mirage) | Jock |
| 113 | Fearless Liberator | 1 R | U | KHM 135 (Kaldheim) | Zezhou Chen |
| 114 | Pillage | 1 R R | U | A25 144 (Masters 25) | Jesper Ejsing |
| 115 | Warchanter Skald | 2 W | U | KHM 381 (Kaldheim) | Daniel Zrom |
| 116 | Runeforge Champion | 2 W | R | KHM 26 (Kaldheim) | Andrey Kuzinskiy |

#### Undead, Wights & the Dead Marshes

| # | Card | Cost | Rarity | Art source | Artist |
| ---: | --- | --- | :---: | --- | --- |
| 117 | Dirtwater Wraith | 3 B | C | MIR 117 (Mirage) | Steve Luke |
| 118 | Dread Rider | 5 B | C | KHM 89 (Kaldheim) | Irina Nordsol |
| 119 | Drudge Skeletons | 1 B | C | M10 95 (Magic 2010) | Daarken |
| 120 | Duty-Bound Dead | B | C | M13 92 (Magic 2013) | Johannes Voss |
| 121 | Gravedigger | 3 B | C | M19 98 (Core Set 2019) | Dermot Power |
| 122 | Persistent Specimen | B | C | VOW 125 (Innistrad: Crimson Vow) | Scott Murphy |
| 123 | Rimebound Dead | B | C | CSP 69 (Coldsnap) | Dave Kendall |
| 124 | Sanitarium Skeleton | B | C | M20 112 (Core Set 2020) | Kev Walker |
| 125 | Bog Wraith | 3 B | U | 10E 130 (Tenth Edition) | Daarken |
| 126 | Lingering Souls | 2 W | U | DKA 12 (Dark Ascension) | Bud Cook |
| 127 | Phantom Warrior | 1 U U | U | 10E 96 (Tenth Edition) | Greg Staples |
| 128 | Reassembling Skeleton | 1 B | U | M19 116 (Core Set 2019) | Austin Hsu |
| 129 | Spectral Sailor | U | U | M20 76 (Core Set 2020) | Cristi Balanescu |
| 130 | Will-o'-the-Wisp | B | U | A25 115 (Masters 25) | Rob Alexander |
| 131 | Death Baron | 1 B B | R | ALA 70 (Shards of Alara) | Nils Hamm |
| 132 | Hypnotic Specter | 1 B B | R | 10E 151 (Tenth Edition) | Greg Staples |
| 133 | Lord of the Undead | 1 B B | R | PLS 44 (Planeshift) | Brom |
| 134 | Nightmare | 5 B | R | M14 108 (Magic 2014) | Vance Kovacs |
| 135 | Royal Assassin | 1 B B | R | 10E 174 (Tenth Edition) | Mark Zug |

#### Dragons, Drakes & Worms

| # | Card | Cost | Rarity | Art source | Artist |
| ---: | --- | --- | :---: | --- | --- |
| 136 | Craw Wurm | 4 G G | C | 10E 257 (Tenth Edition) | Richard Sardinha |
| 137 | Dragon Hatchling | 1 R | C | M13 128 (Magic 2013) | David Palumbo |
| 138 | Dragon Whelp | 2 R R | U | DMU 120 (Dominaria United) | Jokubas Uogintas |
| 139 | Furnace Whelp | 2 R R | U | IMA 129 (Iconic Masters) | Shreya Shetty |
| 140 | Volcanic Dragon | 4 R R | U | M12 158 (Magic 2012) | Chris Rahn |
| 141 | Thundermaw Hellkite | 3 R R | M | M13 150 (Magic 2013) | Svetlin Velinov |

#### Ships & Corsairs

| # | Card | Cost | Rarity | Art source | Artist |
| ---: | --- | --- | :---: | --- | --- |
| 142 | Headstrong Brute | 2 R | C | XLN 147 (Ixalan) | Yongjae Choi |
| 143 | Pirate's Cutlass | 3 | C | XLN 242 (Ixalan) | John Stanko |
| 144 | Dire Fleet Captain | B R | U | XLN 221 (Ixalan) | Yongjae Choi |
| 145 | Kitesail Freebooter | 1 B | U | XLN 110 (Ixalan) | Dan Murayama Scott |
| 146 | Ruin Raider | 2 B | U | XLN 118 (Ixalan) | Kieran Yanner |
| 147 | Sleek Schooner | 3 | U | XLN 247 (Ixalan) | Mark Winters |
| 148 | Fathom Fleet Captain | 1 B | R | XLN 106 (Ixalan) | Matt Stewart |
| 149 | Fell Flagship | 3 | R | XLN 238 (Ixalan) | Titus Lunter |
| 150 | Dire Fleet Ravager | 3 B B | M | XLN 104 (Ixalan) | Bram Sels |

#### Staples: White

| # | Card | Cost | Rarity | Art source | Artist |
| ---: | --- | --- | :---: | --- | --- |
| 151 | Disenchant | 1 W | C | LEA 18 (Limited Edition Alpha) | Amy Weber |
| 152 | Pacifism | 1 W | C | M20 32 (Core Set 2020) | Jesper Ejsing |
| 153 | Condemn | W | U | DIS 8 (Dissension) | Daren Bader |
| 154 | Oblivion Ring | 2 W | U | LRW 34 (Lorwyn) | Wayne England |
| 155 | Wrath of God | 2 W W | R | 10E 61 (Tenth Edition) | Kev Walker |

#### Staples: Blue

| # | Card | Cost | Rarity | Art source | Artist |
| ---: | --- | --- | :---: | --- | --- |
| 156 | Boomerang | U U | C | 10E 70 (Tenth Edition) | Arnie Swekel |
| 157 | Cancel | 1 U U | C | TSP 51 (Time Spiral) | Mark Poole |
| 158 | Claustrophobia | 1 U U | C | ISD 48 (Innistrad) | Ryan Pancoast |
| 159 | Divination | 2 U | C | M10 49 (Magic 2010) | Howard Lyon |
| 160 | Essence Scatter | 1 U | C | M10 51 (Magic 2010) | Jon Foster |
| 161 | Frost Breath | 2 U | C | M12 54 (Magic 2012) | Mike Bierek |
| 162 | Mana Leak | 1 U | C | M11 62 (Magic 2011) | Howard Lyon |
| 163 | Negate | 1 U | C | MOR 43 (Morningtide) | Jeremy Jarvis |
| 164 | Prodigal Sorcerer | 2 U | C | EMA 67 (Eternal Masters) | Eric Deschamps |
| 165 | Sea Gate Oracle | 2 U | C | ROE 85 (Rise of the Eldrazi) | Daniel Ljunggren |
| 166 | Think Twice | 1 U | C | TSP 86 (Time Spiral) | Jim Nelson |
| 167 | Unsummon | U | C | 10E 122 (Tenth Edition) | Ron Spencer |
| 168 | Counterspell | U U | U | LEA 54 (Limited Edition Alpha) | Mark Poole |
| 169 | Fog Bank | 1 U | U | M13 52 (Magic 2013) | Howard Lyon |
| 170 | Mind Control | 3 U U | U | M10 63 (Magic 2010) | Mark Tedin |
| 171 | Sleep | 2 U U | U | M10 71 (Magic 2010) | Chris Rahn |
| 172 | Control Magic | 2 U U | R | EMA 42 (Eternal Masters) | Terese Nielsen |
| 173 | Phantasmal Image | 1 U | R | M12 72 (Magic 2012) | Nils Hamm |

#### Staples: Black

| # | Card | Cost | Rarity | Art source | Artist |
| ---: | --- | --- | :---: | --- | --- |
| 174 | Assassinate | 2 B | C | TSP 95 (Time Spiral) | Kev Walker |
| 175 | Doom Blade | 1 B | C | M10 93 (Magic 2010) | Chippy |
| 176 | Drain Life | X 1 B | C | LEA 105 (Limited Edition Alpha) | Douglas Shuler |
| 177 | Duress | B | C | USG 132 (Urza's Saga) | Lawrence Snelly |
| 178 | Murder | 1 B B | C | M13 101 (Magic 2013) | Allen Williams |
| 179 | Sign in Blood | B B | C | M10 112 (Magic 2010) | Howard Lyon |
| 180 | Tendrils of Corruption | 3 B | C | M10 114 (Magic 2010) | Vance Kovacs |
| 181 | Terror | 1 B | C | 10E 182 (Tenth Edition) | Adam Rex |
| 182 | Unholy Strength | B | C | 10E 185 (Tenth Edition) | Terese Nielsen |
| 183 | Animate Dead | 1 B | U | EMA 78 (Eternal Masters) | Bastien L. Deharme |
| 184 | Hero's Downfall | 1 B B | U | THS 90 (Theros) | Ryan Pancoast |
| 185 | Damnation | 2 B B | R | PLC 85 (Planar Chaos) | Kev Walker |
| 186 | Grave Pact | 1 B B B | R | 10E 144 (Tenth Edition) | Puddnhead |

#### Staples: Red

| # | Card | Cost | Rarity | Art source | Artist |
| ---: | --- | --- | :---: | --- | --- |
| 187 | Act of Treason | 2 R | C | M10 124 (Magic 2010) | Eric Deschamps |
| 188 | Flame Slash | R | C | ROE 145 (Rise of the Eldrazi) | Raymond Swanland |
| 189 | Incinerate | 1 R | C | 10E 213 (Tenth Edition) | Zoltan Boros & Gabor Szikszai |
| 190 | Shock | R | C | 10E 232 (Tenth Edition) | Jon Foster |
| 191 | Fireball | X R | U | LEA 149 (Limited Edition Alpha) | Mark Tedin |
| 192 | Lightning Bolt | R | U | M10 146 (Magic 2010) | Christopher Moeller |
| 193 | Blood Moon | 2 R | R | DRK 58 (The Dark) | Tom Wänerstrand |

#### Staples: Green

| # | Card | Cost | Rarity | Art source | Artist |
| ---: | --- | --- | :---: | --- | --- |
| 194 | Fog | G | C | M10 182 (Magic 2010) | John Avon |
| 195 | Giant Growth | G | C | 10E 266 (Tenth Edition) | Matt Cavotta |
| 196 | Grizzly Bears | 1 G | C | 10E 268 (Tenth Edition) | D. J. Cleland-Hura |
| 197 | Naturalize | 1 G | C | 10E 282 (Tenth Edition) | Tim Hildebrandt |
| 198 | Prey Upon | G | C | ISD 200 (Innistrad) | Dave Kendall |
| 199 | Spidersilk Armor | 2 G | C | MMQ 273 (Mercadian Masques) | Scott Hampton |
| 200 | Wild Growth | G | C | LEA 229 (Limited Edition Alpha) | Mark Poole |

#### Staples: Multicolour

| # | Card | Cost | Rarity | Art source | Artist |
| ---: | --- | --- | :---: | --- | --- |
| 201 | Terminate | B R | U | ARB 46 (Alara Reborn) | Wayne Reynolds |

#### Artifacts

| # | Card | Cost | Rarity | Art source | Artist |
| ---: | --- | --- | :---: | --- | --- |
| 202 | Bonesplitter | 1 | C | MRD 146 (Mirrodin) | Darrell Riche |
| 203 | Coldsteel Heart | 2 | U | CSP 136 (Coldsnap) | Mark Romanoski |
| 204 | Elixir of Immortality | 1 | U | M11 206 (Magic 2011) | Zoltan Boros & Gabor Szikszai |
| 205 | Fellwar Stone | 2 | U | DRK 102 (The Dark) | Quinton Hoover |
| 206 | Dragon's Hoard | 3 | R | M19 232 (Core Set 2019) | Adam Paquette |

#### Lands

| # | Card | Cost | Rarity | Art source | Artist |
| ---: | --- | --- | :---: | --- | --- |
| 207 | Barren Moor |  | C | ONS 312 (Onslaught) | Heather Hudson |
| 208 | Bloodfell Caves |  | C | KTK 229 (Khans of Tarkir) | Adam Paquette |
| 209 | Blossoming Sands |  | C | KTK 231 (Khans of Tarkir) | Sam Burley |
| 210 | Dismal Backwater |  | C | KTK 232 (Khans of Tarkir) | Sam Burley |
| 211 | Dwarven Mine |  | C | ELD 243 (Throne of Eldraine) | Alexander Forssberg |
| 212 | Forgotten Cave |  | C | ONS 317 (Onslaught) | Tony Szczudlo |
| 213 | Haunted Fengraf |  | C | DKA 157 (Dark Ascension) | Adam Paquette |
| 214 | Jungle Hollow |  | C | KTK 235 (Khans of Tarkir) | Eytan Zana |
| 215 | Rugged Highlands |  | C | KTK 240 (Khans of Tarkir) | Eytan Zana |
| 216 | Secluded Steppe |  | C | ONS 324 (Onslaught) | Heather Hudson |
| 217 | Swiftwater Cliffs |  | C | KTK 243 (Khans of Tarkir) | Eytan Zana |
| 218 | Witch's Cottage |  | C | ELD 249 (Throne of Eldraine) | Gabor Szikszai |
| 219 | Dwarven Ruins |  | U | FEM 94 (Fallen Empires) | Mark Poole |
| 220 | Ebon Stronghold |  | U | FEM 95 (Fallen Empires) | Mark Poole |
| 221 | Havenwood Battleground |  | U | FEM 96 (Fallen Empires) | Mark Poole |
| 222 | Leechridden Swamp |  | U | SHM 273 (Shadowmoor) | Lars Grant-West |
| 223 | Rootbound Crag |  | R | M10 227 (Magic 2010) | Matt Stewart |

### Known limitation: tokens

Tokens get their art from an allowed edition that prints the token. These MEAR tokens have no such printing, so they show Forge's generated token image: `b_2_2_pirate_menace, b_2_4_spider_reach, g_1_2_spider_reach, r_1_1_dwarf, r_2_1_dwarf_berserker, w_1_1_soldier, w_3_4_bird_flying, w_5_5_horse`. A `[tokens]` section in MEAR wouldn't help, because token URLs use the edition's tokens code (`TMEAR`). If this matters, the follow-up is the same art-source idea for tokens (out of scope here). `check.sh` lists them.

## 4. Upstream enemy decks that fit Middle-earth (data for WP5/WP6)

I scanned all enemies in `common/world/enemies.json` and picked the ones that fit Middle-earth thematically (wolves, spiders, orcs/goblins, trolls/giants, undead, bandits, knights/riders, corsairs, elves, dwarves, treefolk, dragons/beasts, wizards, animals). That gives 223 deck references: 192 `.dck` lists and 31 `.json` templates. "Off-pool" means a non-basic card with no printing in the allowed editions, before MEAR (LTR, LTC, PLTC, HOB, HOC) and after it (+MEAR).

- `.dck` decks: 12,326 cards, of which **8,164 were off-pool before and 7,251 after** (−11%).
- Verdict: **4 decks are "small swaps"** (≤ 16 off-pool copies), **9 are "moderate swaps"** (≤ 26 copies and ≤ 10 distinct cards), and **179 are "rebuild"**. Most upstream decks are built from playsets of set-specific cards (Zurgo, Krenko, Blood Crypt, fetch lands, MH/Eldraine/Ixalan mechanics). D7 still holds as "keep the deck's concept, its AI profile and its in-pool skeleton", but expect to replace 20–35 cards per deck.
- I added cards that the fitting decks commonly need and that fit the theme. Examples: Death Baron, Reassembling Skeleton, Will-o'-the-Wisp, Drudge Skeletons, Bog Wraith, Dirtwater Wraith, Duty-Bound Dead, Rimebound Dead, Persistent Specimen, Sanitarium Skeleton, Two-Headed Giant, Craven Giant, Seven Dwarves, Plundering Barbarian, Dwarven Miner/Driller, Pillage, Orcish Vandal, Pitiless Horde, Armory Veteran, Blood-Chin Fanatic, Mogg War Marshal, Raging Goblin, Dragon Fodder, Tattermunge Maniac, Bloodmark Mentor, Dragonlord's Servant, Twin-Silk Spider, Packsong Pup, Duskhunter Bat, Bloodhunter Bat, Treefolk Harbinger, Ambassador Oak, Cradle Guard, Blanchwood Treefolk, Knight of the White Orchid, Veteran Swordsmith, Gather the Townsfolk, Ambitious Farmhand, the XLN pirates, Fell Flagship, Dragon's Hoard, Dragon Hatchling, Fellwar Stone, Coldsteel Heart, Wild Growth, Witch's Cottage, Dwarven Mine, Leechridden Swamp, Jungle Hollow, Rootbound Crag, plus the most-used staples (Murder, Doom Blade, Terror, Counterspell, Lightning Bolt, Shock, Unsummon, Boomerang, Wrath of God, Animate Dead, Unholy Strength, Drain Life, Sign in Blood, Naturalize, Condemn, Cancel …).
- **Not added**, even though they are frequent: Badlands, Underground Sea and the other original duals; shocklands, fetchlands and Triomes; Fatal Push; Murderous Rider; Llanowar Elves (Elvish Mystic is in LTC/HOC); Zurgo and Krenko (named legends); Darksteel Citadel; Gravecrawler and most zombies. These are either off-theme, named characters of other planes, or power/economy questions for the user (see open questions).

### Template decks (`.json`, built from the pool at spawn time)

These pick cards of a tribe and colours from the allowed pool, so MEAR directly increases their variety. The counts are creatures of that type in the pool, all colours.

| Enemy | Template | Colours | Tribe | Tribe creatures in pool before | after |
| --- | --- | --- | --- | ---: | ---: |
| Wolf Pup | `wolf.json` | Green | Wolf | 13 | 24 |
| Spider | `spider.json` | Green/Black | Spider | 6 | 16 |
| Goblin Pack | `goblin_bad.json` | Red | Goblin | 25 | 34 |
| Goblin Warrior | `goblin_mid.json` | Red | Goblin | 25 | 34 |
| Troll | `troll.json` | Red/Green | Troll | 3 | 9 |
| Big Zombie | `zombie_bad.json` | Black | Zombie | 0 | 6 |
| Ghost | `ghost.json` | Black | Spirit | 4 | 10 |
| Rogue | `rogue.json` | Black/Blue | Rogue | 14 | 15 |
| Human | `human_bad.json` | White/Black/Red | Human | 90 | 106 |
| Human guard | `human_mid.json` | White/Black | Human | 90 | 106 |
| Human elite | `human_good.json` | White | Human | 90 | 106 |
| Berserker | `berserker.json` | Red/Black/Green | Berserker | 1 | 7 |
| Knight | `knight.json` | White | knight | 16 | 22 |
| Elf | `elf_bad.json` | Green | Elf | 59 | 65 |
| Elf warrior | `elf_mid.json` | Green | Elf | 59 | 65 |
| High Elf | `elf_good.json` | Green/Black | Elf | 59 | 65 |
| Dwarf | `dwarf.json` | Red/White | Dwarf | 34 | 48 |
| Treefolk | `treefolk.json` | Green | Treefolk | 11 | 25 |
| Wurm | `wurm.json` | Green | Wurm | 1 | 2 |
| Demon | `demon.json` | Black | Demon | 6 | 6 |
| Apprentice Blue Wizard | `bluewizard_easy.json` | Blue | - | - | - |
| Apprentice Black Wizard | `blackwizard_easy.json` | Black | - | - | - |
| Apprentice Red Wizard | `redwizard_easy.json` | Red | - | - | - |
| Apprentice Green Wizard | `greenwizard_easy.json` | Green | - | - | - |
| Shaman | `shaman.json` | Red/Green | Shaman | 8 | 10 |
| Bear | `bear.json` | Green | Bear | 6 | 7 |
| Boar | `boar.json` | Green | Boar | 1 | 1 |
| Bird | `bird_blue.json` | Blue | Bird | 16 | 23 |
| Fox | `fox.json` | White | Fox | 0 | 0 |
| Wild Rat | `rat.json` | Black | Rat | 0 | 0 |
| Snake | `snake.json` | Green/Blue | Snake | 0 | 0 |

### Most reusable `.dck` decks: remaining off-pool cards

- `decks/standard/orc_brute.dck` (Orc Brute, 26/60 off): 4x Bloodstained Mire, 4x Brute Strength, 4x Savai Triome, 2x Badlands, 2x Frenzied Saddlebrute, 2x Power Word Kill, 2x Sprinting Warbrute, 2x Thrasher Brute, 2x Zurgo Bellstriker, 2x Zurgo Helmsmasher
- `decks/standard/orc_hunter.dck` (Orc Hunter, 15/60 off): 4x Alchemist's Gift, 4x Blood Crypt, 4x Bloodchief's Thirst, 2x Burakos, Party Leader, 1x Bleeding Edge
- `decks/standard/orc_warrior.dck` (Orc Warrior, 12/60 off): 4x Blood Crypt, 4x Zurgo Bellstriker, 2x Burakos, Party Leader, 1x Bleeding Edge, 1x Orcish Captain
- `decks/standard/wanderinggiant.dck` (Wandering Giant, 26/60 off): 4x Hammer Dropper, 4x Hundred-Handed One, 4x Two-Headed Giant of Foriys, 3x Crush Underfoot, 2x Bonebreaker Giant, 2x Bonecrusher Giant, 2x Desolation Giant, 2x Lowland Giant, 2x Stinkdrinker Daredevil, 1x Fire Giant's Fury
- `decks/standard/skeleton.dck` (Skeleton, 22/60 off): 4x Blood Scrivener, 4x Pontiff of Blight, 3x Sanguine Guard, 3x Urborg Skeleton, 2x Diregraf Ghoul, 2x Restless Dead, 2x Unworthy Dead, 2x Walking Dead
- `decks/standard/bloodiedskeleton.dck` (Bloodied Skeleton, 24/60 off): 4x Bone Shards, 4x Skeleton Archer, 4x Stitch Together, 4x Wall of Bone, 3x Underworld Sentinel, 2x Grafted Exoskeleton, 2x Wake the Dead, 1x Boneyard Aberration
- `decks/miniboss/torturer.dck` (Torturer, 26/60 off): 4x Hymn to Tourach, 4x Smallpox, 4x The Rack, 3x Pox, 3x Underworld Dreams, 2x Sinkhole, 2x Sudden Edict, 2x Tergrid, God of Fright, 2x Tinybones, Trinket Thief
- `decks/standard/dwarfdemolisher.dck` (Dwarf Demolisher, 13/60 off): 4x Mine Layer, 3x Lava Flow, 2x Ruination, 2x Torbran, Thane of Red Fell, 1x Impending Disaster, 1x Wake of Destruction
- `decks/standard/wanderingtreefolk.dck` (Wandering Treefolk, 10/60 off): 4x Regrowth, 2x Treefolk Mystic, 2x Weatherseed Treefolk, 1x Living Terrain, 1x Verdeloth the Ancient
- `decks/standard/bat.dck` (Bat, 20/60 off): 4x Basilica Screecher, 4x Blight Keeper, 4x Blighted Bat, 4x Dirge Bat, 4x Sinister Strength
- `decks/standard/demon5.dck` (Ancient Demon, 23/62 off): 4x Sengir Vampire, 4x Underground Sea, 3x Sindbad, 2x Junún Efreet, 2x Mana Drain, 2x Phantasmal Forces, 2x Phantom Monster, 2x Steal Artifact, 1x Air Elemental, 1x Mind Twist
- `decks/standard/bluewizard_medium_donate.dck` (Adept Blue Wizard, 23/60 off): 4x Donate, 4x Illusions of Grandeur, 4x Sapphire Medallion, 4x Vapor Snag, 3x Disdainful Stroke, 2x Seachrome Coast, 1x Enlightened Tutor, 1x Mystical Tutor
- `decks/standard/redwizard_easy_gauntletofmight.dck` (Adept Red Wizard, 24/60 off): 4x Flamekin Brawler, 4x Gauntlet of Might, 4x Moonveil Dragon, 4x Pyre Charger, 4x Rage Reflection, 2x Molten Ravager, 2x Volcanic Strength

### Full table

| Theme | Enemy | Deck | Cards | Off-pool before | Off-pool after | Distinct off-pool after | Verdict |
| --- | --- | --- | ---: | ---: | ---: | ---: | --- |
| Wolves & Wargs | Wolf | `decks/standard/wolfboss.dck` | 90 | 60 | 46 | 17 | rebuild (keep the idea) |
| Wolves & Wargs | Wolf Pup | `decks/standard/wolf.json` | - | - | - | - | generated from the pool (`.json`) |
| Wolves & Wargs | Werewolf | `decks/standard/werewolf.dck` | 60 | 36 | 34 | 23 | rebuild (keep the idea) |
| Wolves & Wargs | Werebear | `decks/standard/bearboss.dck` | 148 | 85 | 77 | 28 | rebuild (keep the idea) |
| Wolves & Wargs | Hellhound | `decks/standard/hellhound.dck` | 60 | 41 | 41 | 17 | rebuild (keep the idea) |
| Spiders | Spider | `decks/standard/spider.json` | - | - | - | - | generated from the pool (`.json`) |
| Spiders | Giant Spider | `decks/standard/spider_token.dck` | 60 | 36 | 28 | 17 | rebuild (keep the idea) |
| Spiders | Cave Spider | `decks/standard/cave_spider.dck` | 60 | 50 | 43 | 22 | rebuild (keep the idea) |
| Orcs & Goblins | Goblin | `decks/standard/goblin_warrior_easy.dck` | 60 | 34 | 27 | 16 | rebuild (keep the idea) |
| Orcs & Goblins | Goblin Chief | `decks/standard/goblin_chief_medium.dck` | 60 | 40 | 34 | 18 | rebuild (keep the idea) |
| Orcs & Goblins | Goblin King | `decks/standard/goblinking.dck` | 60 | 46 | 35 | 10 | rebuild (keep the idea) |
| Orcs & Goblins | Goblin Pack | `decks/standard/goblin_bad.json` | - | - | - | - | generated from the pool (`.json`) |
| Orcs & Goblins | Goblin Warrior | `decks/standard/goblin_mid.json` | - | - | - | - | generated from the pool (`.json`) |
| Orcs & Goblins | Goblin Stalker | `decks/standard/evilgoblin.dck` | 66 | 42 | 40 | 21 | rebuild (keep the idea) |
| Orcs & Goblins | Goblin Rager | `decks/standard/goblinrager.dck` | 60 | 36 | 28 | 9 | rebuild (keep the idea) |
| Orcs & Goblins | Goblin Fanatic | `decks/standard/goblin_fanatic.dck` | 78 | 58 | 50 | 18 | rebuild (keep the idea) |
| Orcs & Goblins | Orc Brute | `decks/standard/orc_brute.dck` | 60 | 37 | 26 | 10 | reuse, moderate swaps |
| Orcs & Goblins | Orc Hunter | `decks/standard/orc_hunter.dck` | 60 | 30 | 15 | 5 | reuse, small swaps |
| Orcs & Goblins | Orc Warrior | `decks/standard/orc_warrior.dck` | 60 | 32 | 12 | 5 | reuse, small swaps |
| Orcs & Goblins | Hulking Brute | `decks/standard/hulkingbrute.dck` | 60 | 35 | 29 | 21 | rebuild (keep the idea) |
| Orcs & Goblins | Boggart | `decks/standard/eyeblight.dck` | 60 | 42 | 41 | 25 | rebuild (keep the idea) |
| Orcs & Goblins | Undead Goblin | `decks/standard/monobgoblins.dck` | 60 | 45 | 38 | 14 | rebuild (keep the idea) |
| Trolls & Giants | Troll | `decks/standard/troll.json` | - | - | - | - | generated from the pool (`.json`) |
| Trolls & Giants | Swamp Troll | `decks/standard/swamptroll.dck` | 60 | 48 | 40 | 16 | rebuild (keep the idea) |
| Trolls & Giants | Mountain Ogre | `decks/standard/ogre_large.dck` | 94 | 74 | 66 | 23 | rebuild (keep the idea) |
| Trolls & Giants | Forest Giant | `decks/standard/foresttitan.dck` | 60 | 30 | 30 | 14 | rebuild (keep the idea) |
| Trolls & Giants | Wandering Giant | `decks/standard/wanderinggiant.dck` | 60 | 36 | 26 | 10 | reuse, moderate swaps |
| Trolls & Giants | Cloud Giant | `decks/standard/cloudgiant.dck` | 85 | 61 | 57 | 21 | rebuild (keep the idea) |
| Trolls & Giants | Fire Giant | `decks/standard/firegiant.dck` | 60 | 34 | 28 | 11 | rebuild (keep the idea) |
| Trolls & Giants | Cyclops | `decks/standard/cyclops.dck` | 60 | 35 | 35 | 20 | rebuild (keep the idea) |
| Trolls & Giants | Giant Cyclops | `decks/standard/luckyminotaur.dck` | 177 | 123 | 107 | 33 | rebuild (keep the idea) |
| Trolls & Giants | Yeti | `decks/standard/yeti.dck` | 60 | 44 | 43 | 23 | rebuild (keep the idea) |
| Undead & Wraiths | Skeleton | `decks/standard/skeleton.dck` | 60 | 36 | 22 | 8 | reuse, moderate swaps |
| Undead & Wraiths | Skeleton Soldier | `decks/standard/skeleton_2.dck` | 60 | 40 | 36 | 32 | rebuild (keep the idea) |
| Undead & Wraiths | Skeleton Champion | `decks/standard/skeleton_champion.dck` | 60 | 44 | 31 | 13 | rebuild (keep the idea) |
| Undead & Wraiths | Bloodied Skeleton | `decks/standard/bloodiedskeleton.dck` | 60 | 36 | 24 | 8 | reuse, moderate swaps |
| Undead & Wraiths | Reassembling Skeleton | `decks/standard/reassemblingskeleton.dck` | 60 | 40 | 28 | 8 | rebuild (keep the idea) |
| Undead & Wraiths | Zombie | `decks/standard/zombie_black_easy.dck` | 60 | 38 | 36 | 20 | rebuild (keep the idea) |
| Undead & Wraiths | Zombie Lord | `decks/standard/zombie_black_medium.dck` | 60 | 39 | 35 | 18 | rebuild (keep the idea) |
| Undead & Wraiths | Zombie Master | `decks/standard/zombie_black_hard.dck` | 60 | 39 | 37 | 13 | rebuild (keep the idea) |
| Undead & Wraiths | Big Zombie | `decks/standard/zombie_bad.json` | - | - | - | - | generated from the pool (`.json`) |
| Undead & Wraiths | Greater Zombie | `decks/standard/zombie_greater.dck` | 67 | 47 | 35 | 12 | rebuild (keep the idea) |
| Undead & Wraiths | Ghoul | `decks/standard/ghoul.dck` | 60 | 37 | 37 | 21 | rebuild (keep the idea) |
| Undead & Wraiths | Ghost | `decks/standard/ghost.json` | - | - | - | - | generated from the pool (`.json`) |
| Undead & Wraiths | Geist | `decks/standard/ghost_blue.dck` | 60 | 36 | 36 | 24 | rebuild (keep the idea) |
| Undead & Wraiths | Wraith | `decks/standard/wraithswampwalk.dck` | 60 | 36 | 28 | 8 | rebuild (keep the idea) |
| Undead & Wraiths | Shade | `decks/standard/shade.dck` | 60 | 33 | 33 | 11 | rebuild (keep the idea) |
| Undead & Wraiths | Lich | `decks/standard/lich.dck` | 60 | 36 | 29 | 26 | rebuild (keep the idea) |
| Undead & Wraiths | Necromancer | `decks/standard/reanimator.dck` | 60 | 48 | 41 | 24 | rebuild (keep the idea) |
| Undead & Wraiths | Death Knight | `decks/standard/death_knight.dck` | 60 | 33 | 30 | 24 | rebuild (keep the idea) |
| Undead & Wraiths | Dark Knight | `decks/standard/death_knight.dck` | 60 | 33 | 30 | 24 | rebuild (keep the idea) |
| Undead & Wraiths | Dark Spirit | `decks/standard/dark_spirit.dck` | 66 | 43 | 39 | 18 | rebuild (keep the idea) |
| Undead & Wraiths | Undead Roamer | `decks/standard/undeadroamer.dck` | 85 | 56 | 52 | 21 | rebuild (keep the idea) |
| Undead & Wraiths | Undead Shuffler | `decks/standard/undeadshuffler.dck` | 60 | 49 | 43 | 19 | rebuild (keep the idea) |
| Undead & Wraiths | Tomb Guardian | `decks/standard/tombguardian.dck` | 60 | 47 | 45 | 27 | rebuild (keep the idea) |
| Undead & Wraiths | Spirit | `decks/standard/spirit.dck` | 66 | 48 | 48 | 17 | rebuild (keep the idea) |
| Undead & Wraiths | Unholy Knight | `decks/standard/unholyknight.dck` | 102 | 67 | 51 | 26 | rebuild (keep the idea) |
| Undead & Wraiths | Graven Revenant | `decks/standard/giralfgisa.dck` | 82 | 65 | 62 | 32 | rebuild (keep the idea) |
| Bandits & Men | Bandit | `decks/standard/bandit.dck` | 60 | 40 | 33 | 30 | rebuild (keep the idea) |
| Bandits & Men | Bandit Archer | `decks/standard/banditarcher_blink.dck` | 60 | 39 | 34 | 22 | rebuild (keep the idea) |
| Bandits & Men | Bandit Archer | `decks/standard/banditarcher_damage.dck` | 60 | 45 | 45 | 23 | rebuild (keep the idea) |
| Bandits & Men | Bandit Leader | `decks/standard/banditleader_bolas.dck` | 80 | 50 | 50 | 30 | rebuild (keep the idea) |
| Bandits & Men | Bandit Leader | `decks/standard/banditleader_hellbent.dck` | 60 | 53 | 53 | 22 | rebuild (keep the idea) |
| Bandits & Men | Bandit Leader | `decks/standard/banditleader_rakdos.dck` | 60 | 40 | 39 | 22 | rebuild (keep the idea) |
| Bandits & Men | Bandit Scoundrel | `decks/standard/bandit_monor.dck` | 60 | 37 | 35 | 22 | rebuild (keep the idea) |
| Bandits & Men | Bandit Scoundrel | `decks/standard/bandit_mutate.dck` | 60 | 46 | 39 | 19 | rebuild (keep the idea) |
| Bandits & Men | Bandit Scoundrel | `decks/standard/bandit_zoo.dck` | 60 | 52 | 50 | 31 | rebuild (keep the idea) |
| Bandits & Men | Bandit Slingshot | `decks/standard/banditslingshot_flyers.dck` | 60 | 36 | 34 | 15 | rebuild (keep the idea) |
| Bandits & Men | Bandit Trapmaster | `decks/standard/bandittrapper_hazezon.dck` | 60 | 54 | 54 | 25 | rebuild (keep the idea) |
| Bandits & Men | Rogue | `decks/standard/rogue.json` | - | - | - | - | generated from the pool (`.json`) |
| Bandits & Men | Human | `decks/standard/human_bad.json` | - | - | - | - | generated from the pool (`.json`) |
| Bandits & Men | Human guard | `decks/standard/human_mid.json` | - | - | - | - | generated from the pool (`.json`) |
| Bandits & Men | Human elite | `decks/standard/human_good.json` | - | - | - | - | generated from the pool (`.json`) |
| Bandits & Men | Human Captain | `decks/standard/humanelite.dck` | 60 | 32 | 32 | 10 | rebuild (keep the idea) |
| Bandits & Men | Human Mercenary | `decks/standard/humanmercenary.dck` | 64 | 48 | 42 | 17 | rebuild (keep the idea) |
| Bandits & Men | Human Bard | `decks/standard/humanbard.dck` | 70 | 49 | 49 | 25 | rebuild (keep the idea) |
| Bandits & Men | Archer | `decks/standard/human_archer.dck` | 60 | 39 | 36 | 28 | rebuild (keep the idea) |
| Bandits & Men | Archer Elite | `decks/standard/archerelite.dck` | 60 | 40 | 36 | 15 | rebuild (keep the idea) |
| Bandits & Men | Farmer | `decks/standard/farmer.dck` | 60 | 36 | 32 | 10 | rebuild (keep the idea) |
| Bandits & Men | Warrior | `decks/standard/human_warrior_amonkhet.dck` | 69 | 53 | 52 | 22 | rebuild (keep the idea) |
| Bandits & Men | Barbarian | `decks/standard/barbarian.dck` | 60 | 36 | 28 | 11 | rebuild (keep the idea) |
| Bandits & Men | Berserker | `decks/standard/berserker.json` | - | - | - | - | generated from the pool (`.json`) |
| Bandits & Men | Adventurer | `decks/standard/adventurer.dck` | 60 | 37 | 37 | 28 | rebuild (keep the idea) |
| Bandits & Men | Torturer | `decks/miniboss/torturer.dck` | 60 | 32 | 26 | 9 | reuse, moderate swaps |
| Knights & Riders | Knight | `decks/standard/knight.json` | - | - | - | - | generated from the pool (`.json`) |
| Knights & Riders | Armored Knight | `decks/standard/armored_knight.dck` | 60 | 41 | 41 | 29 | rebuild (keep the idea) |
| Knights & Riders | Knight Mercenary | `decks/standard/knightmercenary.dck` | 60 | 37 | 35 | 21 | rebuild (keep the idea) |
| Knights & Riders | Royal King | `decks/standard/whiteking.dck` | 102 | 51 | 45 | 16 | rebuild (keep the idea) |
| Knights & Riders | Royal Sentinel | `decks/standard/trap.dck` | 60 | 38 | 34 | 19 | rebuild (keep the idea) |
| Knights & Riders | Horseman | `decks/standard/horsemanship.dck` | 60 | 35 | 35 | 21 | rebuild (keep the idea) |
| Knights & Riders | Horse | `decks/standard/horse.dck` | 60 | 42 | 30 | 8 | rebuild (keep the idea) |
| Knights & Riders | Holy Paladin | `decks/standard/holypaladin.dck` | 100 | 71 | 67 | 25 | rebuild (keep the idea) |
| Knights & Riders | Cathar | `decks/standard/cathar.dck` | 60 | 37 | 35 | 21 | rebuild (keep the idea) |
| Corsairs | Pirate | `decks/standard/pirate.dck` | 60 | 43 | 35 | 14 | rebuild (keep the idea) |
| Corsairs | Pirate 2 | `decks/standard/pirate2.dck` | 75 | 61 | 50 | 20 | rebuild (keep the idea) |
| Corsairs | Pirate 3 | `decks/standard/pirate3.dck` | 75 | 59 | 41 | 13 | rebuild (keep the idea) |
| Corsairs | Pirate Captain | `decks/standard/pirate_captain.dck` | 94 | 82 | 68 | 32 | rebuild (keep the idea) |
| Corsairs | Pirate Captain 2 | `decks/standard/pirate_captain_2.dck` | 65 | 44 | 27 | 14 | rebuild (keep the idea) |
| Elves | Elf | `decks/standard/elf_bad.json` | - | - | - | - | generated from the pool (`.json`) |
| Elves | Elf warrior | `decks/standard/elf_mid.json` | - | - | - | - | generated from the pool (`.json`) |
| Elves | High Elf | `decks/standard/elf_good.json` | - | - | - | - | generated from the pool (`.json`) |
| Elves | Elf Vagabond | `decks/standard/elfvagabond.dck` | 60 | 31 | 31 | 12 | rebuild (keep the idea) |
| Dwarves | Dwarf | `decks/standard/dwarf.json` | - | - | - | - | generated from the pool (`.json`) |
| Dwarves | Dwarf Mercenary | `decks/standard/dwarfmercenary.dck` | 60 | 53 | 39 | 13 | rebuild (keep the idea) |
| Dwarves | Dwarf Warlord | `decks/standard/dwarf_whitecastle.dck` | 60 | 45 | 41 | 15 | rebuild (keep the idea) |
| Dwarves | Axgard Dwarf | `decks/standard/axgard_dwarf.dck` | 60 | 42 | 28 | 17 | rebuild (keep the idea) |
| Dwarves | White Dwarf | `decks/standard/white_dwarf.dck` | 60 | 43 | 40 | 32 | rebuild (keep the idea) |
| Dwarves | Dwarf Demolisher | `decks/standard/dwarfdemolisher.dck` | 60 | 42 | 13 | 6 | reuse, small swaps |
| Ents & Huorns | Treefolk | `decks/standard/treefolk.json` | - | - | - | - | generated from the pool (`.json`) |
| Ents & Huorns | Treefolk Elite | `decks/standard/treefolkelite.dck` | 60 | 34 | 30 | 19 | rebuild (keep the idea) |
| Ents & Huorns | Treefolk Guardian | `decks/standard/treefolk.dck` | 60 | 35 | 30 | 28 | rebuild (keep the idea) |
| Ents & Huorns | Wandering Treefolk | `decks/standard/wanderingtreefolk.dck` | 60 | 28 | 10 | 5 | reuse, small swaps |
| Dragons & Beasts | Dragon | `decks/standard/dragon.dck` | 60 | 35 | 30 | 27 | rebuild (keep the idea) |
| Dragons & Beasts | Fire Dragon | `decks/standard/firedragon.dck` | 60 | 36 | 32 | 13 | rebuild (keep the idea) |
| Dragons & Beasts | Volcano Dragon | `decks/standard/junddragon.dck` | 135 | 86 | 78 | 34 | rebuild (keep the idea) |
| Dragons & Beasts | Drake | `decks/standard/drake_ur_beginner.dck` | 60 | 44 | 38 | 13 | rebuild (keep the idea) |
| Dragons & Beasts | Drake | `decks/standard/drake_ur_mid.dck` | 60 | 40 | 40 | 11 | rebuild (keep the idea) |
| Dragons & Beasts | Drake | `decks/standard/drake_ur_unfair.dck` | 58 | 46 | 46 | 12 | rebuild (keep the idea) |
| Dragons & Beasts | Wurm | `decks/standard/wurm.json` | - | - | - | - | generated from the pool (`.json`) |
| Dragons & Beasts | Sea Monster | `decks/standard/sea_monster.dck` | 60 | 35 | 35 | 29 | rebuild (keep the idea) |
| Dragons & Beasts | Nightmare | `decks/standard/nightmare.dck` | 60 | 38 | 29 | 11 | rebuild (keep the idea) |
| Dragons & Beasts | Bat | `decks/standard/bat.dck` | 60 | 36 | 20 | 5 | reuse, moderate swaps |
| Dragons & Beasts | Demon | `decks/standard/demon.json` | - | - | - | - | generated from the pool (`.json`) |
| Dragons & Beasts | Demon | `decks/standard/demonshard.dck` | 76 | 62 | 56 | 24 | rebuild (keep the idea) |
| Dragons & Beasts | Demon | `decks/standard/demonblackred.dck` | 60 | 36 | 36 | 11 | rebuild (keep the idea) |
| Dragons & Beasts | Demon | `decks/standard/demonmonoblack.dck` | 60 | 36 | 32 | 9 | rebuild (keep the idea) |
| Dragons & Beasts | Ancient Demon | `decks/standard/demon5.dck` | 62 | 44 | 23 | 10 | reuse, moderate swaps |
| Wizards & Witches | Apprentice White Wizard | `decks/standard/whitewizard_easy_ajani.dck` | 60 | 47 | 47 | 27 | rebuild (keep the idea) |
| Wizards & Witches | Apprentice White Wizard | `decks/standard/whitewizard_easy_wanderer.dck` | 60 | 46 | 46 | 22 | rebuild (keep the idea) |
| Wizards & Witches | Apprentice White Wizard | `decks/standard/whitewizard_easy_angelhuman.dck` | 60 | 30 | 23 | 15 | rebuild (keep the idea) |
| Wizards & Witches | Apprentice Blue Wizard | `decks/standard/bluewizard_apprentice_1.dck` | 60 | 36 | 35 | 26 | rebuild (keep the idea) |
| Wizards & Witches | Apprentice Blue Wizard | `decks/standard/bluewizard_apprentice_2.dck` | 60 | 32 | 32 | 17 | rebuild (keep the idea) |
| Wizards & Witches | Apprentice Blue Wizard | `decks/standard/bluewizard_apprentice_3.dck` | 60 | 37 | 37 | 20 | rebuild (keep the idea) |
| Wizards & Witches | Apprentice Blue Wizard | `decks/standard/bluewizard_easy.json` | - | - | - | - | generated from the pool (`.json`) |
| Wizards & Witches | Apprentice Black Wizard | `decks/standard/blackwizard_easy_bog.dck` | 60 | 33 | 25 | 13 | rebuild (keep the idea) |
| Wizards & Witches | Apprentice Black Wizard | `decks/standard/blackwizard_easy_humans.dck` | 60 | 36 | 32 | 10 | rebuild (keep the idea) |
| Wizards & Witches | Apprentice Black Wizard | `decks/standard/blackwizard_easy_pile.dck` | 60 | 34 | 32 | 21 | rebuild (keep the idea) |
| Wizards & Witches | Apprentice Black Wizard | `decks/standard/blackwizard_easy_thrull.dck` | 60 | 42 | 38 | 12 | rebuild (keep the idea) |
| Wizards & Witches | Apprentice Black Wizard | `decks/standard/blackwizard_easy.json` | - | - | - | - | generated from the pool (`.json`) |
| Wizards & Witches | Apprentice Red Wizard | `decks/standard/redwizard_easy.json` | - | - | - | - | generated from the pool (`.json`) |
| Wizards & Witches | Apprentice Red Wizard | `decks/standard/redwizard_easy_1.dck` | 60 | 35 | 35 | 14 | rebuild (keep the idea) |
| Wizards & Witches | Apprentice Red Wizard | `decks/standard/redwizard_easy_2.dck` | 60 | 35 | 35 | 13 | rebuild (keep the idea) |
| Wizards & Witches | Apprentice Red Wizard | `decks/standard/redwizard_apprentice_1.dck` | 60 | 40 | 40 | 11 | rebuild (keep the idea) |
| Wizards & Witches | Apprentice Red Wizard | `decks/standard/redwizard_easy_3.dck` | 60 | 40 | 40 | 11 | rebuild (keep the idea) |
| Wizards & Witches | Apprentice Green Wizard | `decks/standard/greenwizard_easy_elf.dck` | 60 | 39 | 39 | 13 | rebuild (keep the idea) |
| Wizards & Witches | Apprentice Green Wizard | `decks/standard/greenwizard_easy_enchantment.dck` | 60 | 46 | 46 | 26 | rebuild (keep the idea) |
| Wizards & Witches | Apprentice Green Wizard | `decks/standard/greenwizard_easy_vanilla.dck` | 60 | 37 | 32 | 15 | rebuild (keep the idea) |
| Wizards & Witches | Apprentice Green Wizard | `decks/standard/greenwizard_easy.json` | - | - | - | - | generated from the pool (`.json`) |
| Wizards & Witches | Adept White Wizard | `decks/standard/whitewizard_medium_aggro.dck` | 60 | 40 | 36 | 21 | rebuild (keep the idea) |
| Wizards & Witches | Adept White Wizard | `decks/standard/whitewizard_medium_basri.dck` | 60 | 40 | 40 | 23 | rebuild (keep the idea) |
| Wizards & Witches | Adept White Wizard | `decks/standard/whitewizard_medium_control.dck` | 60 | 35 | 32 | 25 | rebuild (keep the idea) |
| Wizards & Witches | Adept White Wizard | `decks/standard/whitewizard_medium_knight.dck` | 60 | 36 | 34 | 17 | rebuild (keep the idea) |
| Wizards & Witches | Adept Blue Wizard | `decks/standard/bluewizard_medium_donate.dck` | 60 | 38 | 23 | 8 | reuse, moderate swaps |
| Wizards & Witches | Adept Blue Wizard | `decks/standard/bluewizard_medium_dragons.dck` | 60 | 47 | 42 | 23 | rebuild (keep the idea) |
| Wizards & Witches | Adept Blue Wizard | `decks/standard/bluewizard_easy_artifact.dck` | 60 | 48 | 48 | 26 | rebuild (keep the idea) |
| Wizards & Witches | Adept Blue Wizard | `decks/standard/bluewizard_easy_control.dck` | 60 | 38 | 35 | 20 | rebuild (keep the idea) |
| Wizards & Witches | Adept Blue Wizard | `decks/standard/bluewizard_easy_thassa.dck` | 60 | 46 | 46 | 24 | rebuild (keep the idea) |
| Wizards & Witches | Adept Blue Wizard | `decks/standard/bluewizard_medium_midrange.dck` | 60 | 40 | 40 | 15 | rebuild (keep the idea) |
| Wizards & Witches | Adept Blue Wizard | `decks/standard/bluewizard_medium_mill.dck` | 60 | 36 | 36 | 28 | rebuild (keep the idea) |
| Wizards & Witches | Adept Black Wizard | `decks/standard/blackwizard_medium_demonicpact.dck` | 60 | 42 | 30 | 16 | rebuild (keep the idea) |
| Wizards & Witches | Adept Black Wizard | `decks/standard/blackwizard_medium_fear.dck` | 60 | 37 | 37 | 20 | rebuild (keep the idea) |
| Wizards & Witches | Adept Black Wizard | `decks/standard/blackwizard_medium_goblins.dck` | 60 | 37 | 33 | 10 | rebuild (keep the idea) |
| Wizards & Witches | Adept Black Wizard | `decks/standard/blackwizard_easy_artifact.dck` | 60 | 46 | 43 | 24 | rebuild (keep the idea) |
| Wizards & Witches | Adept Black Wizard | `decks/standard/blackwizard_easy_badmoon.dck` | 60 | 38 | 35 | 28 | rebuild (keep the idea) |
| Wizards & Witches | Adept Black Wizard | `decks/standard/blackwizard_easy_mill.dck` | 60 | 36 | 36 | 15 | rebuild (keep the idea) |
| Wizards & Witches | Adept Red Wizard | `decks/standard/redwizard_medium_artifact.dck` | 60 | 48 | 48 | 38 | rebuild (keep the idea) |
| Wizards & Witches | Adept Red Wizard | `decks/standard/redwizard_medium_giant.dck` | 60 | 39 | 38 | 13 | rebuild (keep the idea) |
| Wizards & Witches | Adept Red Wizard | `decks/standard/redwizard_medium_circleofflame.dck` | 60 | 37 | 35 | 22 | rebuild (keep the idea) |
| Wizards & Witches | Adept Red Wizard | `decks/standard/redwizard_medium_ralburn.dck` | 60 | 39 | 31 | 12 | rebuild (keep the idea) |
| Wizards & Witches | Adept Red Wizard | `decks/standard/redwizard_medium_hasteburn.dck` | 60 | 36 | 29 | 24 | rebuild (keep the idea) |
| Wizards & Witches | Adept Red Wizard | `decks/standard/redwizard_easy_burn.dck` | 60 | 44 | 39 | 21 | rebuild (keep the idea) |
| Wizards & Witches | Adept Red Wizard | `decks/standard/redwizard_easy_dragons.dck` | 60 | 45 | 41 | 22 | rebuild (keep the idea) |
| Wizards & Witches | Adept Red Wizard | `decks/standard/redwizard_easy_gauntletofmight.dck` | 60 | 34 | 24 | 7 | reuse, moderate swaps |
| Wizards & Witches | Adept Green Wizard | `decks/standard/greenwizard_medium_cheatramp.dck` | 60 | 38 | 38 | 15 | rebuild (keep the idea) |
| Wizards & Witches | Adept Green Wizard | `decks/standard/greenwizard_medium_nissa.dck` | 60 | 37 | 33 | 22 | rebuild (keep the idea) |
| Wizards & Witches | Adept Green Wizard | `decks/standard/greenwizard_medium_snakes.dck` | 60 | 39 | 39 | 23 | rebuild (keep the idea) |
| Wizards & Witches | Adept Green Wizard | `decks/standard/greenwizard_medium_trample.dck` | 60 | 36 | 36 | 26 | rebuild (keep the idea) |
| Wizards & Witches | Master White Wizard | `decks/standard/whitewizard_hard_humility.dck` | 60 | 32 | 28 | 9 | rebuild (keep the idea) |
| Wizards & Witches | Master White Wizard | `decks/standard/whitewizard_hard_hatebears.dck` | 60 | 31 | 29 | 24 | rebuild (keep the idea) |
| Wizards & Witches | Master White Wizard | `decks/standard/whitewizard_hard_humansoldier.dck` | 60 | 33 | 29 | 24 | rebuild (keep the idea) |
| Wizards & Witches | Master White Wizard | `decks/standard/whitewizard_hard_armaggedon.dck` | 60 | 45 | 43 | 24 | rebuild (keep the idea) |
| Wizards & Witches | Master White Wizard | `decks/standard/whitewizard_hard_enchantments.dck` | 60 | 38 | 31 | 12 | rebuild (keep the idea) |
| Wizards & Witches | Master Blue Wizard | `decks/standard/bluewizard_hard_control.dck` | 60 | 39 | 33 | 21 | rebuild (keep the idea) |
| Wizards & Witches | Master Blue Wizard | `decks/standard/bluewizard_hard_tempo.dck` | 60 | 33 | 27 | 12 | rebuild (keep the idea) |
| Wizards & Witches | Master Blue Wizard | `decks/standard/bluewizard_hard_artifact.dck` | 60 | 42 | 42 | 20 | rebuild (keep the idea) |
| Wizards & Witches | Master Blue Wizard | `decks/standard/bluewizard_hard_bounce.dck` | 60 | 40 | 37 | 15 | rebuild (keep the idea) |
| Wizards & Witches | Master Black Wizard | `decks/standard/blackwizard_hard_gravepact.dck` | 60 | 37 | 31 | 16 | rebuild (keep the idea) |
| Wizards & Witches | Master Black Wizard | `decks/standard/blackwizard_hard_knight.dck` | 80 | 59 | 53 | 33 | rebuild (keep the idea) |
| Wizards & Witches | Master Black Wizard | `decks/standard/blackwizard_hard_liliana.dck` | 60 | 36 | 33 | 29 | rebuild (keep the idea) |
| Wizards & Witches | Master Black Wizard | `decks/standard/blackwizard_hard_sacrifice.dck` | 60 | 35 | 30 | 15 | rebuild (keep the idea) |
| Wizards & Witches | Master Red Wizard | `decks/standard/redwizard_hard_burn.dck` | 80 | 59 | 49 | 21 | rebuild (keep the idea) |
| Wizards & Witches | Master Red Wizard | `decks/standard/redwizard_hard_dragons.dck` | 80 | 51 | 49 | 23 | rebuild (keep the idea) |
| Wizards & Witches | Master Red Wizard | `decks/standard/redwizard_hard_fight.dck` | 60 | 39 | 36 | 18 | rebuild (keep the idea) |
| Wizards & Witches | Master Red Wizard | `decks/standard/redwizard_hard_lavaaxe.dck` | 60 | 36 | 36 | 14 | rebuild (keep the idea) |
| Wizards & Witches | Master Green Wizard | `decks/standard/greenwizard_hard_ramp.dck` | 60 | 31 | 30 | 18 | rebuild (keep the idea) |
| Wizards & Witches | Master Green Wizard | `decks/standard/greenwizard_hard_rampbis.dck` | 60 | 41 | 41 | 13 | rebuild (keep the idea) |
| Wizards & Witches | Master Green Wizard | `decks/standard/greenwizard_hard_sapproling.dck` | 80 | 51 | 51 | 28 | rebuild (keep the idea) |
| Wizards & Witches | Witch | `decks/standard/witch.dck` | 60 | 40 | 36 | 14 | rebuild (keep the idea) |
| Wizards & Witches | Bog Witch | `decks/standard/witch.dck` | 60 | 40 | 36 | 14 | rebuild (keep the idea) |
| Wizards & Witches | Conjurer | `decks/miniboss/conjure.dck` | 60 | 46 | 46 | 23 | rebuild (keep the idea) |
| Wizards & Witches | Illusionist | `decks/standard/illusion.dck` | 60 | 37 | 31 | 14 | rebuild (keep the idea) |
| Wizards & Witches | Shaman | `decks/standard/shaman.json` | - | - | - | - | generated from the pool (`.json`) |
| Wizards & Witches | Archmage | `decks/standard/archmage.dck` | 60 | 36 | 32 | 12 | rebuild (keep the idea) |
| Wizards & Witches | Dark Enchanter | `decks/miniboss/darkenchanter.dck` | 60 | 49 | 45 | 12 | rebuild (keep the idea) |
| Wizards & Witches | High Cultist | `decks/standard/cultist.dck` | 75 | 53 | 43 | 18 | rebuild (keep the idea) |
| Animals | Bear | `decks/standard/bear.json` | - | - | - | - | generated from the pool (`.json`) |
| Animals | Boar | `decks/standard/boar.json` | - | - | - | - | generated from the pool (`.json`) |
| Animals | Boar | `decks/standard/boar_gruul.dck` | 60 | 44 | 44 | 11 | rebuild (keep the idea) |
| Animals | Boar | `decks/standard/boar_monogreenlifegain.dck` | 60 | 36 | 36 | 10 | rebuild (keep the idea) |
| Animals | Raven | `decks/standard/raven.dck` | 60 | 44 | 32 | 9 | rebuild (keep the idea) |
| Animals | Bird | `decks/standard/bird_blue.json` | - | - | - | - | generated from the pool (`.json`) |
| Animals | Falcon | `decks/standard/falcon_w.dck` | 60 | 44 | 44 | 11 | rebuild (keep the idea) |
| Animals | Owl | `decks/standard/owl.dck` | 60 | 46 | 46 | 13 | rebuild (keep the idea) |
| Animals | Elk | `decks/standard/elk.dck` | 60 | 35 | 35 | 24 | rebuild (keep the idea) |
| Animals | Fox | `decks/standard/fox.json` | - | - | - | - | generated from the pool (`.json`) |
| Animals | Fox | `decks/standard/fox_greenwhite.dck` | 60 | 36 | 36 | 10 | rebuild (keep the idea) |
| Animals | Wild Rat | `decks/standard/infectrat.dck` | 60 | 44 | 44 | 11 | rebuild (keep the idea) |
| Animals | Wild Rat | `decks/standard/rat.json` | - | - | - | - | generated from the pool (`.json`) |
| Animals | Wild Rat | `decks/standard/ratswarm.dck` | 60 | 44 | 44 | 11 | rebuild (keep the idea) |
| Animals | Vulture | `decks/standard/vulture.dck` | 60 | 36 | 28 | 7 | rebuild (keep the idea) |
| Animals | Snake | `decks/standard/snake.json` | - | - | - | - | generated from the pool (`.json`) |
| Animals | Viper | `decks/standard/snake.dck` | 60 | 51 | 48 | 32 | rebuild (keep the idea) |
| Animals | Poisonous Snake | `decks/standard/poisonoussnake.dck` | 60 | 40 | 36 | 12 | rebuild (keep the idea) |

## 5. `check.sh` before and after

Before (base commit `443d2a882`, prebuilt jar):

```
   allowedEditions: [LTR, LTC, PLTC, HOB, HOC]
      206 enemy decks contain off-pool cards
== Card resolution respects allowedEditions
      unique card pool size: 844
      printings per edition: {HOB=321, HOC=158, LTC=558, LTR=836, PLTC=4}
      checked 268 reprints for printing replacement
== Result: 0 error(s), 556 warning(s)
```

After (this branch, rebuilt jar):

```
   allowedEditions: [LTR, LTC, PLTC, HOB, HOC, MEAR]
      206 enemy decks contain off-pool cards
== Card resolution respects allowedEditions
      unique card pool size: 1067
      printings per edition: {HOB=321, HOC=158, LTC=558, LTR=836, MEAR=223, PLTC=4}
      checked 491 reprints for printing replacement
== Reprint editions (art sources)
      MEAR: 223 cards {C=120, U=70, R=28, M=5}, 223 art sources resolved
      MEAR: tokens without a printing in the allowed editions (generic image): [b_2_2_pirate_menace, b_2_4_spider_reach, g_1_2_spider_reach, r_1_1_dwarf, r_2_1_dwarf_berserker, w_1_1_soldier, w_3_4_bird_flying, w_5_5_horse]
== Result: 0 error(s), 556 warning(s)
```

The warning count is the same, because every deck that spawns in the plane still has *some* off-pool card. Off-pool copies across the spawned enemy decks drop from 8,426 to 7,956 (of 12,664), and across the starter decks from 885 to 871. The new section 5 of `PlaneCheck` doesn't depend on the engine change, so it also runs with an older jar.

## 6. Open questions for the user

1. **Size:** 223 cards is a bit above the 120–200 target, because I added about 25 cards to make specific upstream decks reusable (orcs, skeletons, treefolk, dwarves, bats). Trim, or keep?
2. **Power and economy:** should the reserved-list original duals (Badlands, Underground Sea, Bayou, …) or the shocklands be in the pool at R? Upstream enemy decks use them a lot, but they would also show up in rewards and shops.
3. **Named-plane names:** Seven Dwarves, Witch's Cottage, Mardu Outrider, the "Dire Fleet" pirates and Haunted Fengraf carry non-Tolkien names. Is that OK, given that the art fits?
4. **Art review:** I chose all art from memory of the printings, without seeing the images (the sandbox can't reach Scryfall). A pass in the GUI with network access (deck editor, filter set `MEAR`) is worth doing. Each swap is a one-line `artSet`/`artNum` edit.
5. **Tokens:** is it worth extending the art-source idea to tokens (8 tokens currently use the generic image)?
6. **Bulk download:** should the desktop "download all pictures" also understand `artSet`/`artNum`? This isn't needed for adventure.
