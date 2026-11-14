# Custom artwork for MEAR cards and tokens

Why: the MEAR reprints (D5, D10) borrow another printing's image, so a card with a Tolkien flavor name (for example "Huorn of the Old Forest") still shows "Redwood Treefolk" on its picture. This page says how Forge finds card images, where a custom picture goes today, and what small engine change would let the repo ship such pictures. Nothing here changes the engine; section 5 is a sketch only.

Short answer:

- **Today (no code change):** put `<Flavor Name>.full.jpg` in the user's picture cache under `pics/cards/MEAR/`. Forge finds it first and never downloads over it. That is per user and does not ship with the repo.
- **Repo-shipped:** not supported yet. `ImageKeys.getImageFile` only searches the cache dirs (and the adventure `custom_card_pics` dir for `a:` keys, which only covers new-rules cards from `common/custom_cards`). Section 5 describes a ~25-line engine change for a plane-local directory. Until it exists, a repo-side folder plus a copy or symlink step is the stopgap (section 4).
- **Full-card images** (with the new name in the frame) are what the adventure client needs. Art crops are only used by the desktop client's optional "Crop" mode and for sleeves.

## 1. How Forge resolves a card image

1. A `PaperCard` computes its image key in `ImageUtil.getImageRelativePath(card, face, includeSet=true, isDownloadUrl=false)` (`forge-core/src/main/java/forge/util/ImageUtil.java`):

   ```
   <set folder>/<name to use><art index>.full
   ```

   - `set folder` is `ImageKeys.getSetFolder(edition)`, the edition's `Code2` (MEAR: `MEAR`).
   - `name to use` comes from `ImageUtil.getNameToUse`. **If the printing has a flavor name it is the flavor (display) name, not the rules name**: `else if (cp.hasFlavorName()) return cp.getDisplayName();`. Then `toMWSFilename` strips accents and the characters `" / : ?`.
   - `art index` is only appended when the card has more than one art in that edition (never for MEAR).
   - `PaperCard.getImageKey` returns a `c:<name>|<set>|<art>` key; the client normalises it to the path above (`PaperCard.getCardImageKey`) before `getImageFile` looks for the file.
2. `ImageKeys.getImageFile(key)` (`forge-core/src/main/java/forge/ImageKeys.java`) maps the key to a file under the card picture cache dir (`ForgeConstants.CACHE_CARD_PICS_DIR`, from `ForgeProfileProperties.cardPicsDir`, default `<cache dir>/pics/cards/`; on Linux `~/.cache/forge/pics/cards/`, and `forge.profile.properties` can override `cacheDir` / `cardPicsDir`). In `findFile` it tries the extensions `.jpg`, then `.png`. If nothing matches it tries, in order: `.full` replaced by `.fullborder`, the same without the art index, the set aliases (`setlookup/`, edition alias), and finally the same file name without the set folder (`pics/cards/<name>.full.jpg`). A miss is remembered in `ImageKeys.missingCards` (libGDX client) until restart.
3. The libGDX client (`forge-gui-mobile`, `ImageCache.getImage`, `CachedCardImage.fetch`, `RewardActor`) loads that file as a texture. It calls the fetcher only if `imageKeyFileExists(key)` is false. While the file is missing it draws a placeholder card frame with the display name.
4. `ImageFetcher.fetchImage` (`forge-gui/src/main/java/forge/util/ImageFetcher.java`) downloads only when the file is missing: `destFile = CACHE_CARD_PICS_DIR + <key>.jpg`; `if (destFile.exists()) return;`. URLs are built from the **art source printing** (`getArtSourcePrinting`, the `artSet`/`artNum` of the MEAR entry), so a flavor name does not break the download, but the file is saved under the flavor-name key.

So **precedence is: a local file at the key always wins and no download starts.** An already-downloaded borrowed image sits at the same path; replace it (same file name) or delete it.

Note: `notes/wp2-reprint-set.md` says MEAR images are cached as `MEAR/<Card Name>.full.jpg`. That holds for the 167 MEAR cards without a flavor name; for the 66 with one, the file name is the flavor name.

## 2. Where to put a picture today, with a worked example

Card: Redwood Treefolk, flavor name "Huorn of the Old Forest" (MEAR #37, art source POR 183).

```
~/.cache/forge/pics/cards/MEAR/Huorn of the Old Forest.full.jpg
```

- Folder: `MEAR` (the edition's `Code2`).
- File name: the flavor name exactly as in the edition line, case and spaces included, accents removed, no `" / : ?`, then `.full.jpg` (`.png` also works, `.jpg` is tried first). An accented name such as "Sméagol" is saved as `Smeagol`.
- Check the key before drawing 66 pictures: the `check.sh` section "Reprint editions" lists, for every flavor name, the real name, flavor name, image key and art source.
- Start the game; the card now shows the new picture, and a download is never attempted for that key.

Without a flavor name (for example Giant Spider) the file is `MEAR/Giant Spider.full.jpg`.

Double-faced cards (the MEAR set has Ambitious Farmhand): the back face is looked up under the other face's name (`ImageUtil.getNameToUse(..., "back")`), so it needs its own `MEAR/<back face name>.full.jpg`. Untested here.

## 3. Size, format and what Forge renders

- **Full card, portrait, 5:7** (63 x 88 mm). Scryfall's `normal` size is 488 x 680 px JPEG; that is the practical minimum. Up to 745 x 1040 (`large`) loads fine; the libGDX client scales textures, so more pixels cost memory, not correctness. JPEG for card art (smaller), PNG also accepted.
- Include the card's **black border** on all four sides and leave the corners square (full bleed). The client rounds the corners itself (radius 22 px for modern sets) and samples the border colour from the image. A `.full` file is drawn as is.
- If you only have a borderless render, name it `...fullborder.jpg` (for example `Huorn of the Old Forest.fullborder.jpg`): the client then crops 4 % off every side (`ImageCache.croppedBorderImage`) before drawing. `getImageFile` finds `.fullborder` when no `.full` file exists.
- **Full-card vs art crop.** The adventure client and the in-game card view always draw the whole picture, so the new name must be in the frame of the image you supply. Forge never overlays a name on a full-card file. An art crop (`<name>.artcrop.jpg`, roughly 626 x 457) is used only by the desktop (Swing) client when Settings > "Card art format" is "Crop" (`ImageCache` in `forge-gui-desktop`, `ImageFetcher` replaces `.full` with `.artcrop`), where Forge draws its own frame and prints the display name; and by sleeves (`SleeveArt`). The adventure client has no art-crop mode, so a crop alone does not fix the flavor names in this plane.

Tools people use to make full-card renders: card-frame generators and card-image editors such as Magic Set Editor, Card Conjurer, or any image editor with a frame template. Use original or properly licensed art and frames; do not copy Wizards of the Coast or other copyrighted artwork or frame assets into the repo unless you have the right to. A quick option that stays inside what Forge already does is to take the borrowed image, repaint the name bar and the type line, and keep the original art credit.

## 4. Tokens

Token pictures use `ImageKeys.TOKEN_PREFIX` keys: `t:<token script name>|<EDITION>|<collector number>`, and live in `ForgeConstants.CACHE_TOKEN_PICS_DIR` (`~/.cache/forge/pics/tokens/`). For MEAR (`[tokens]` section, collector numbers 1 to 8) the lookup order in `ImageKeys.getImageFile` is:

1. `tokens/<script>_MEAR_<n>.jpg|png` (flat name, for example `b_2_2_pirate_menace_MEAR_1.jpg`),
2. `tokens/MEAR/<n>_<script>.jpg|png` (**the path the fetcher itself writes**, for example `tokens/MEAR/1_b_2_2_pirate_menace.jpg`),
3. `tokens/MEAR/<script>.jpg`, then `tokens/<script>.jpg`.

Use form 2. Token images are drawn as is, with a 4 % crop (the client treats paths containing `tokens` like `.fullborder`), so supply the full token card render, about 488 x 680 for a Scryfall-style token, JPG or PNG. Token lines in the edition currently set `artSet`/`artNum` to borrow another set's token; a local file takes precedence and prevents the download exactly as for cards. A token's name is the script's in-game name, so a Tolkien token name needs a new token script (see `common/custom_cards` for the new-rules path) rather than a flavor name.

## 5. Shipping pictures in the repo: engine change needed

**Not supported today.** The only repo-shipped picture directory is `forge-gui/res/adventure/common/custom_card_pics/`. It is set as `ImageKeys.ADVENTURE_CARD_PICS_DIR` in `forge-gui-mobile/.../adventure/util/Config.java` (`loadResources`) and consulted only for keys with the `a:` prefix (`ImageKeys.ADVENTURECARD_PREFIX`), which are built for the new-rules cards read from `common/custom_cards` (a card that is not a reprint, edition `UNKNOWN`). MEAR cards have `c:` keys, so that folder is never consulted for them, and it is `common/`, not plane-local.

What to change (small, keep the CLAUDE.md rule: record it in PLAN.md "Engine changes"):

1. `forge-core/src/main/java/forge/ImageKeys.java`
   - Add `public static String PLANE_CARD_PICS_DIR, PLANE_TOKEN_PICS_DIR;` next to `ADVENTURE_CARD_PICS_DIR` (null by default, so desktop Forge is unaffected).
   - In `getImageFile`, in the `else` branch right after `cachedCards.get(filename)` misses and before `findFile(dir, filename)`: for card keys and token keys try the plane dir first, with the same filename and the `.full` / `.fullborder` swap, using the existing `findFile` loop over `.jpg`, `.png`:

     ```java
     File planeDir = dir.equals(CACHE_CARD_PICS_DIR) ? PLANE_CARD_PICS_DIR
                    : dir.equals(CACHE_TOKEN_PICS_DIR) ? PLANE_TOKEN_PICS_DIR : null;
     if (planeDir != null) {
         File f = findFile(planeDir, filename);                 // MEAR/Huorn of the Old Forest.full(.jpg|.png)
         if (f == null && filename.contains(".full"))
             f = findFile(planeDir, TextUtil.fastReplace(filename, ".full", ".fullborder"));
         if (f != null) { cachedCards.put(filename, f); return f; }
     }
     ```

     Putting it before the cache lookup makes the repo's picture beat a stale download of the borrowed art. `findFile` takes a `dir` argument and treats non-cache dirs with the "old method" branch, so it works for the plane dir unchanged.
   - `ImageKeys.hasImage(PaperCard)` (the set-folder listing shortcut) should also return true when the plane dir has the file; otherwise code that filters on `PaperCard.hasImage()` still thinks the picture is missing. Add a check of `new File(PLANE_CARD_PICS_DIR, <key>+ext)`.
2. `forge-gui-mobile/src/forge/adventure/util/Config.java`, `loadResources()` (line ~675, beside `ImageKeys.ADVENTURE_CARD_PICS_DIR = ...`):

   ```java
   ImageKeys.PLANE_CARD_PICS_DIR  = getFilePath("card_pics") + "/";   // forge-gui/res/adventure/MiddleEarth/card_pics/
   ImageKeys.PLANE_TOKEN_PICS_DIR = getFilePath("token_pics") + "/";
   ```

   `getFilePath` is the plane prefix (`res/adventure/<plane>/`), so each plane gets its own folder and switching planes switches the pictures. Reset both to null for planes without the folder if desired (a missing directory is harmless: `File.exists` returns false).
3. `forge-gui/src/main/java/forge/util/ImageFetcher.java` needs no change: the libGDX paths already skip `fetchImage` when `getImageFile` finds the file (`CachedCardImage.fetch`, `RewardActor`). Optionally add an early `if (ImageKeys.getImageFile(imageKey) != null) return;` at the top of the card branch to cover other callers.
4. Layout once done: `forge-gui/res/adventure/MiddleEarth/card_pics/MEAR/Huorn of the Old Forest.full.jpg` and `.../token_pics/MEAR/1_b_2_2_pirate_menace.jpg`. Add a row to `tools/middleearth/check.sh`/`PlaneCheck.java` "Reprint editions" that reports flavor-named cards without a plane-local picture.

Alternatives with no engine change:

- **Symlink or copy:** keep the pictures in the repo (for example `docs/` or `forge-gui/res/adventure/MiddleEarth/card_pics/`) and link the folder into the cache: `ln -s <repo>/forge-gui/res/adventure/MiddleEarth/card_pics/MEAR ~/.cache/forge/pics/cards/MEAR`. If MEAR images were already downloaded, move them aside first, because they are in the same folder. A small script such as `tools/middleearth/install_card_art.sh` can do this per checkout. This ships the art with the repo but still needs a step per user and does not help the release builds.
- `cardPicsSubDirs` in `forge.profile.properties` can redirect a set's folder, but `ImageKeys.findFile` joins it onto `CACHE_CARD_PICS_DIR`, so it is not a clean way to point at the repo.
- `res/setlookup/MEAR.txt` makes MEAR fall back to other local folders by file name; it can't hold new files.
