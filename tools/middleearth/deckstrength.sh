#!/usr/bin/env bash
# AI-vs-AI deck strength harness for the Middle-earth plane (see docs/MiddleEarth/DEVELOPMENT.md,
# "Deck strength testing").
#
#   tools/middleearth/deckstrength.sh build                  # build the engine jar (forge-gui-desktop, ~3-5 min cold)
#   tools/middleearth/deckstrength.sh [options] DECK...      # rate decks against the LTR Starter Kit gauntlet
#   tools/middleearth/deckstrength.sh -h                     # all options
#
# DECK: a .dck file, an adventure .json deck (plane path such as decks/starter/white_e.json, or a file
# under forge-gui/res/adventure/), enemy:<Name> (all decks of that enemy), or a directory of .dck files.
# Results: .me-test/deckstrength/<timestamp>/summary.md (+ summary.csv, games.csv).
#
# Environment: FORGE_DESKTOP_JAR (engine jar, default: this tree's, then the main checkout's),
#              FORGE_JAR (adventure jar for .json / enemy: inputs), DS_GAMES, DS_JOBS.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

if [ "${1:-}" = "build" ]; then
  cd "$ROOT"
  # wagon transport: the default native HTTP transport drops jar downloads behind the cloud proxy.
  for i in 1 2 3 4; do
    mvn -B -q -Dmaven.resolver.transport=wagon -Dmaven.wagon.http.retryHandler.count=5 \
        package -DskipTests -Dcheckstyle.skip -pl forge-gui-desktop -am && exit 0
    echo "build attempt $i failed, retrying" >&2
    find ~/.m2 -name '*.lastUpdated' -delete 2>/dev/null || true
  done
  exit 1
fi

exec python3 "$ROOT/tools/middleearth/deckstrength/deckstrength.py" "$@"
