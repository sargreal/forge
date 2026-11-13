#!/usr/bin/env bash
# Headless consistency check of an adventure plane (default MiddleEarth).
#   tools/middleearth/check.sh [-v] [plane]
# -v lists every off-pool card per deck. Needs the fat jar: tools/middleearth/game.sh build
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
VERBOSE=""; [ "${1:-}" = "-v" ] && { VERBOSE="-v"; shift; }
PLANE="${1:-MiddleEarth}"
JAR="${FORGE_JAR:-$(ls "$ROOT"/forge-gui-mobile-dev/target/forge-gui-mobile-dev-*-jar-with-dependencies.jar 2>/dev/null | head -1)}"
[ -n "$JAR" ] || { echo "no jar found, run: tools/middleearth/game.sh build" >&2; exit 1; }

# Isolated user dir so the check never touches real saves/settings.
OUT_DIR="${OUT_DIR:-$ROOT/.me-test}"
HOME_DIR="$OUT_DIR/check-home"
mkdir -p "$HOME_DIR/.forge/adventure"
echo "{\"plane\":\"$PLANE\"}" > "$HOME_DIR/.forge/adventure/settings.json"

cd "$ROOT/forge-gui"   # Config resolves ./res/adventure/<plane>
java -Xmx3g -Duser.home="$HOME_DIR" -Djava.awt.headless=true -cp "$JAR" \
  "$ROOT/tools/middleearth/PlaneCheck.java" $VERBOSE 2>"$OUT_DIR/check-stderr.log" \
  | grep -v -E "was not assigned to any set|dated in the future|^Read cards:|^Looking for resource"
