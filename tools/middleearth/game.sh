#!/usr/bin/env bash
# Drive the Forge Adventure (mobile-dev) client headlessly for testing the
# MiddleEarth plane. Uses Xvfb + xdotool + ImageMagick `import`.
#
#   tools/middleearth/game.sh build             # mvn build of forge-gui-mobile-dev (+deps)
#   tools/middleearth/game.sh start [plane]     # start Xvfb + game (default plane: MiddleEarth) with the dev API
#   tools/middleearth/game.sh api               # print the dev API port (8700 + DISPLAY_NUM); drive it with gamectl.py
#   tools/middleearth/game.sh wait-menu         # block until the adventure title menu is shown
#   tools/middleearth/game.sh smoke [plane]     # new game via the dev API, intro skipped, screenshots + world map into $OUT_DIR
#   tools/middleearth/game.sh click X Y         # left click at screen coords (1280x720)
#   tools/middleearth/game.sh key KEY...        # send xdotool key names (e.g. Escape, Return, w)
#   tools/middleearth/game.sh hold KEY SECONDS  # hold a key (walk on the world map)
#   tools/middleearth/game.sh console CMD...    # run in-game console commands (F9), e.g. leave, "give gold 500"
#   tools/middleearth/game.sh skip-dialog [N]   # press Return N times (default 12) to click through dialogs
#   tools/middleearth/game.sh shot [file.png]   # screenshot (default: $OUT_DIR/shot-<time>.png)
#   tools/middleearth/game.sh errors            # show exceptions from the game log
#   tools/middleearth/game.sh log               # tail the game log
#   tools/middleearth/game.sh stop              # kill the game (and Xvfb)
#
# Environment overrides:
#   DISPLAY_NUM (99)        use a different number per parallel agent/worktree
#   OUT_DIR (./.me-test)    screenshots, log, and the isolated game home ($OUT_DIR/home = user.home of the game)
#   SCREEN (1280x720)
#   NO_DEVAPI=1             start without the dev API (normal game: mode chooser, no port, win/lose screen)
#   FORGE_JAR               use a prebuilt fat jar (e.g. from the main checkout) instead of this tree's build;
#                           game data is always read from THIS tree's forge-gui/res
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DISPLAY_NUM="${DISPLAY_NUM:-99}"
SCREEN="${SCREEN:-1280x720}"
OUT_DIR="${OUT_DIR:-$ROOT/.me-test}"
LOG="$OUT_DIR/game.log"
JAR_GLOB="$ROOT/forge-gui-mobile-dev/target/forge-gui-mobile-dev-*-jar-with-dependencies.jar"
GAME_HOME="$OUT_DIR/home"
PIDFILE="$OUT_DIR/game.pid"
API_PORT="${FORGE_DEVAPI_PORT:-$((8700 + DISPLAY_NUM))}"
export DISPLAY=":$DISPLAY_NUM" DISPLAY_NUM FORGE_DEVAPI_PORT="$API_PORT"
GAMECTL="python3 $ROOT/tools/middleearth/gamectl.py"
mkdir -p "$OUT_DIR"

need() { command -v "$1" >/dev/null 2>&1 || { echo "missing tool: $1 (apt-get install -y $2)" >&2; exit 1; }; }

cmd="${1:-help}"; shift || true
case "$cmd" in
  build)
    cd "$ROOT"
    # wagon transport: the default native HTTP transport drops jar downloads behind the cloud proxy.
    for i in 1 2 3; do
      mvn -B -q -Dmaven.resolver.transport=wagon -Dmaven.wagon.http.retryHandler.count=5 \
          install -DskipTests -Dcheckstyle.skip -pl forge-gui-mobile-dev -am && exit 0
      echo "build attempt $i failed, retrying" >&2
      find ~/.m2 -name '*.lastUpdated' -delete 2>/dev/null || true
    done
    exit 1 ;;
  start)
    need Xvfb xvfb; need xdotool xdotool; need import imagemagick
    plane="${1:-MiddleEarth}"
    pgrep -f "Xvfb :$DISPLAY_NUM" >/dev/null || { Xvfb ":$DISPLAY_NUM" -screen 0 "${SCREEN}x24" >/dev/null 2>&1 & sleep 1; }
    [ -f "$PIDFILE" ] && kill "$(cat "$PIDFILE")" 2>/dev/null || true
    # Isolated game home (saves, settings) per OUT_DIR so parallel runs don't collide.
    settings="$GAME_HOME/.forge/adventure/settings.json"; mkdir -p "$(dirname "$settings")"
    api=1; [ -z "${NO_DEVAPI:-}" ] || api=0
    python3 - "$settings" "$plane" "${SCREEN%x*}" "${SCREEN#*x}" "$api" "$GAME_HOME/.forge/preferences/forge.preferences" <<'EOF'
import json, sys, os
p, plane, w, h, api, prefs = sys.argv[1], sys.argv[2], int(sys.argv[3]), int(sys.argv[4]), sys.argv[5] == "1", sys.argv[6]
d = {}
if os.path.exists(p):
    try: d = json.load(open(p))
    except Exception: d = {}
d.update(plane=plane, width=w, height=h, fullScreen=False)
# dev API runs: duels return to the map without the win/lose screen (no click needed)
d["disableWinLose"] = api
json.dump(d, open(p, "w"), indent=1)
# dev API runs open Adventure mode directly (no Classic/Adventure chooser); without it, the chooser as before
os.makedirs(os.path.dirname(prefs), exist_ok=True)
lines = open(prefs).read().splitlines() if os.path.exists(prefs) else []
lines = [l for l in lines if not l.startswith("UI_SELECTOR_MODE=")] + ["UI_SELECTOR_MODE=" + ("Adventure" if api else "Default")]
open(prefs, "w").write("\n".join(lines) + "\n")
EOF
    jar="${FORGE_JAR:-$(ls $JAR_GLOB 2>/dev/null | head -1)}"
    [ -n "$jar" ] || { echo "no jar found, run: $0 build" >&2; exit 1; }
    cd "$ROOT/forge-gui-mobile-dev"   # assets resolve to ../forge-gui/res from here
    apiflag=""; [ -n "${NO_DEVAPI:-}" ] || apiflag="-Dforge.devapi.port=$API_PORT"
    LIBGL_ALWAYS_SOFTWARE=1 FORGE_DEVAPI_PORT= nohup java -Xmx3g -Duser.home="$GAME_HOME" $apiflag \
      --add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.util=ALL-UNNAMED \
      -jar "$jar" "width=${SCREEN%x*}" "height=${SCREEN#*x}" > "$LOG" 2>&1 &
    echo $! > "$PIDFILE"
    echo "started (pid $!), plane=$plane, log=$LOG"
    if [ -n "${NO_DEVAPI:-}" ]; then echo "dev API off (NO_DEVAPI)"; else echo "dev API: 127.0.0.1:$API_PORT  (tools/middleearth/gamectl.py state)"; fi ;;
  api) echo "$API_PORT" ;;
  wait-menu)
    if [ -z "${NO_DEVAPI:-}" ]; then
      # dev API run: the game opens Adventure mode directly; wait for its title scene.
      for _ in $(seq 1 90); do $GAMECTL wait scene=StartScene --timeout 2000 >/dev/null 2>&1 && break; sleep 2; done
      $GAMECTL wait scene=StartScene --timeout 5000 >/dev/null && echo "adventure menu ready" && exit 0
      echo "adventure menu not reached" >&2; exit 1
    fi
    # Splash -> "Classic/Adventure" chooser -> click Adventure -> wait for adventure title scene.
    for _ in $(seq 1 90); do grep -q 'ui/title_bg.png' "$LOG" 2>/dev/null && break; sleep 2; done
    sleep 5
    xdotool mousemove 640 620 click 1
    for _ in $(seq 1 60); do grep -q 'minimap_player' "$LOG" 2>/dev/null && break; sleep 2; done
    sleep 3; echo "adventure menu ready" ;;
  console)
    xdotool key F9; sleep 1
    for c in "$@"; do xdotool type --delay 40 "$c"; xdotool key Return; sleep 1; done
    xdotool key F9; sleep 1 ;;
  skip-dialog) for _ in $(seq 1 "${1:-12}"); do xdotool key Return; sleep 1.2; done ;;
  smoke)
    # Full flow via the dev API: start -> new game -> close the intro dialog -> leave -> overworld;
    # exports screenshots + world map. (tests/intro_walkthrough.py covers the real intro path.)
    unset NO_DEVAPI
    "$0" start "${1:-MiddleEarth}"
    "$0" wait-menu
    $GAMECTL newgame
    if $GAMECTL wait dialogOpen --timeout 15000 >/dev/null; then $GAMECTL close >/dev/null; fi
    $GAMECTL console leave
    $GAMECTL wait onOverworld --timeout 20000 >/dev/null
    if $GAMECTL wait dialogOpen --timeout 3000 >/dev/null; then $GAMECTL close >/dev/null; fi
    sleep 2
    $GAMECTL state | head -4
    $GAMECTL screenshot "$OUT_DIR/smoke-overworld.png" >/dev/null
    $GAMECTL console "exportMap $OUT_DIR/smoke-worldmap.png"
    $GAMECTL console "exportWorldTmx $OUT_DIR/smoke-world.tmx"
    echo "screenshots: $OUT_DIR/smoke-overworld.png $OUT_DIR/smoke-worldmap.png"
    echo "POI placement retries: $(grep -c 'Can not place POI' "$LOG" || true)"
    "$0" errors ;;
  click) xdotool mousemove "$1" "$2" click 1 ;;
  key) xdotool key --delay 150 "$@" ;;
  hold) xdotool keydown "$1"; sleep "$2"; xdotool keyup "$1" ;;
  shot)
    f="${1:-$OUT_DIR/shot-$(date +%H%M%S).png}"
    import -window root "$f"; echo "$f" ;;
  errors) grep -n -E 'Exception|Caused by|SEVERE|Can not place POI' "$LOG" | grep -v -E 'releases.atom|RSSReader' || echo "no exceptions" ;;
  log) tail -n "${1:-40}" "$LOG" ;;
  stop) [ -f "$PIDFILE" ] && kill "$(cat "$PIDFILE")" 2>/dev/null; rm -f "$PIDFILE"; pkill -f "Xvfb :$DISPLAY_NUM" || true ;;
  *) sed -n 2,28p "$0" ;;
esac
