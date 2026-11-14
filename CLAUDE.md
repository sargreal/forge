# CLAUDE.md

This is a fork of [Card-Forge/forge](https://github.com/Card-Forge/forge), extended with a **Middle-earth adventure plane** (`forge-gui/res/adventure/MiddleEarth/`).

Before working on the plane, read these:

- `docs/MiddleEarth/PLAN.md`: the phase plan, decisions, current status and change log. **Update it with every change.**
- `docs/MiddleEarth/DEVELOPMENT.md`: repo orientation, how to build, how to test (headless checker and GUI smoke test), and plane data how-tos.

Quick commands:

```bash
tools/middleearth/game.sh build    # build the adventure client (forge-gui-mobile-dev)
tools/middleearth/check.sh         # headless plane data check, ~10 s, must report 0 errors
tools/middleearth/game.sh smoke    # GUI smoke test under Xvfb; screenshots and world map go to .me-test/
tools/middleearth/tests/run_all.sh # check.sh + GUI regression tests (intro walkthrough) via the dev API
tools/middleearth/gamectl.py state # while a game runs (game.sh start): state as text; also choose/walk/interact/console…
```

Drive the running game with `gamectl.py` (dev API, DEVELOPMENT.md §3.5) instead of screenshots and key presses.

Rules:

- The plane stays in this fork. Don't open PRs to upstream with plane content.
- Keep engine (Java) changes minimal and record each one in PLAN.md → "Engine changes".
- Upstream format docs for plane data are in `docs/Adventure/`.
