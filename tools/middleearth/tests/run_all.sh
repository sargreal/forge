#!/usr/bin/env bash
# Run all Middle-earth plane checks: the headless data check, then the GUI tests through the dev API.
# Needs a built client (tools/middleearth/game.sh build, or FORGE_JAR). Use your own DISPLAY_NUM.
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
fail=0
echo "== check.sh"
"$HERE/../check.sh" || fail=1
echo "== FlagConditionCheck (headless unit checks)"
ROOT="$(cd "$HERE/../../.." && pwd)"
JAR="${FORGE_JAR:-$(ls "$ROOT"/forge-gui-mobile-dev/target/forge-gui-mobile-dev-*-jar-with-dependencies.jar 2>/dev/null | head -1)}"
mkdir -p "$ROOT/.me-test/check-home"
(cd "$ROOT/forge-gui" && java -Duser.home="$ROOT/.me-test/check-home" -Djava.awt.headless=true -cp "$JAR" \
  "$ROOT/tools/middleearth/FlagConditionCheck.java" 2>/dev/null | grep -E "^(OK|FAIL)") || fail=1
for t in "$HERE"/intro_walkthrough.py "$HERE"/origin_start.py "$HERE"/flags_and_world_state.py \
         "$HERE"/corruption.py "$HERE"/boss_loss.py "$HERE"/locked_item.py; do
  echo "== $(basename "$t")"
  python3 "$t" || fail=1
done
[ $fail -eq 0 ] && echo "ALL PASSED" || echo "SOME TESTS FAILED"
exit $fail
