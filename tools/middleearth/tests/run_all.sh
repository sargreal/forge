#!/usr/bin/env bash
# Run all Middle-earth plane checks: the headless data check, then the GUI tests through the dev API.
# Needs a built client (tools/middleearth/game.sh build, or FORGE_JAR). Use your own DISPLAY_NUM.
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
fail=0
echo "== check.sh"
"$HERE/../check.sh" || fail=1
for t in "$HERE"/intro_walkthrough.py; do
  echo "== $(basename "$t")"
  python3 "$t" || fail=1
done
[ $fail -eq 0 ] && echo "ALL PASSED" || echo "SOME TESTS FAILED"
exit $fail
