# WP2b mearfix: report (written by the coordinator; the agent hit the usage limit before reporting)

Branch `worktree-agent-a5c2f4f82e6a57be7`. It implements decision D10.

## What changed

- **Tolkien flavor names:** 66 MEAR cards carry `"flavorName"` in their `${...}` block, next to `artSet`/`artNum`. This uses Forge's existing per-printing flavor-name support (as in the Avatar Eternal set).
  - The rules identity is unchanged: decks, rewards and `Card|MEAR` lines still use the real name.
  - Examples: Mardu Outrider → *Warg Rider of Gundabad*, Briarpack Alpha → *Grey Warg Chieftain*, Wingmate Roc → *Great Eagle of the Misty Mountains*, Redwood Treefolk → *Huorn of the Old Forest*, Haunted Fengraf → *Barrow-downs Crypt*, Uthden Troll → *Ettenmoors Troll*.
  - The full list is in the edition file: grep `flavorName`.
- **Dual lands:** the 10 original duals were added at rare (MEAR 224–233), each with a Tolkien name: Badlands → *Plains of Gorgoroth*, Bayou → *Midgewater Marshes*, Plateau → *Dunharrow*, Savannah → *Wold of Rohan*, Scrubland → *The Brown Lands*, Taiga → *Misty Mountain Pines*, Tropical Island → *Vales of Anduin*, Tundra → *Ice Bay of Forochel*, Underground Sea → *Pool of the Watcher*, Volcanic Island → *Shores of Nurnen*. No shock lands were added.
- **Token art:** the MEAR edition has a `[tokens]` section. Its 8 token lines carry `${"artSet": "<set>", "artNum": "<token number in that set>"}`, and a small engine change (below) makes `ImageFetcher` download the image from that set's token. As with cards, the image is cached under the MEAR key.
- **Set size:** 233 cards (C 120, U 70, R 38, M 5).

## Engine change

- `CardEdition`: `[tokens]` lines accept a trailing `${...}` parameter block. It's parsed like card lines (`parseTokenParams`) and stored on the token's `EditionEntry`.
- `ImageFetcher.getTokenArtSource(edition, tokenName, collectorNumber)`: if the token entry has an art source and the source set lists the same token script under that number, the Scryfall token URL is built from it.

## Tests (coordinator)

- `check.sh`: 0 errors. "Reprint editions": 233 art sources resolved, 66 flavor names unique and resolving (real name, flavor name, image key, art source), 8 token art sources resolved, 0 tokens without art.
- GUI: `give card "Warg Rider of Gundabad"` adds the MEAR printing, and the deck editor shows the flavor name with the real type line (Orc Warrior 5/5). The console's `give card` doesn't apply the plane's edition filter (it gave Badlands from 30A); rewards and shops do.
- Real image downloads couldn't be tested (the sandbox blocks Scryfall).
