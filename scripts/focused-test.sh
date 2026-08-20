#!/usr/bin/env bash
# Run only the darpan tests that a change could plausibly break.
#
#   scripts/focused-test.sh                  # vs origin/main
#   scripts/focused-test.sh HEAD~1           # vs an explicit ref
#   scripts/focused-test.sh origin/main -n   # print the selection, run nothing
#
# Selection comes from scripts/select_tests.py, which errs toward selecting more and
# escalates to the full suite for anything it cannot map. This is a faster inner loop,
# NOT a replacement for `./gradlew :runtime:component:darpan:test` before you push.
set -euo pipefail

COMPONENT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BACKEND_DIR="$(cd "$COMPONENT_DIR/../../.." && pwd)"
GRADLE_PATH=":runtime:component:darpan"

BASE="${1:-origin/main}"
DRY_RUN=""
[[ "${2:-}" == "-n" || "${2:-}" == "--dry-run" ]] && DRY_RUN=1

cd "$COMPONENT_DIR"
SELECTION_JSON="$(python3 scripts/select_tests.py --base "$BASE" --format json --explain)"

read -r RUN_ALL N_UNIT N_SMOKE <<<"$(
  python3 -c 'import json,sys; d=json.load(sys.stdin); print(int(d["all"]), len(d["unit"]), len(d["smoke"]))' \
    <<<"$SELECTION_JSON"
)"

# macOS ships bash 3.2, so no mapfile — read the FQCNs one per line into an array.
collect_args() {  # $1 = json key set to emit
  ARGS=()
  while IFS= read -r fqcn; do
    [[ -n "$fqcn" ]] && ARGS+=(--tests "$fqcn")
  done < <(python3 -c 'import json,sys
d = json.load(sys.stdin)
keys = sys.argv[1].split(",")
for k in keys:
    for f in d[k]:
        print(f)' "$1" <<<"$SELECTION_JSON")
}

if [[ "$RUN_ALL" == "1" ]]; then
  echo "Change set has no useful narrowing — running the full suite."
  CMD=("$BACKEND_DIR/gradlew" "$GRADLE_PATH:testAll")
elif (( N_UNIT == 0 && N_SMOKE == 0 )); then
  echo "No test-relevant changes vs $BASE — nothing to run."
  exit 0
else
  # Route each half to its own pool rather than putting everything through `test`. Gradle binds
  # a --tests option to the task name preceding it, so the two filters stay independent, and the
  # unit half avoids the per-class fork it would pay under `test`.
  CMD=("$BACKEND_DIR/gradlew")
  if (( N_UNIT > 0 )); then
    collect_args unit
    CMD+=("$GRADLE_PATH:unitTest" "${ARGS[@]}")
  fi
  if (( N_SMOKE > 0 )); then
    collect_args smoke
    CMD+=("$GRADLE_PATH:smokeTest" "${ARGS[@]}")
  fi
fi

echo
echo "+ ${CMD[*]}"
[[ -n "$DRY_RUN" ]] && exit 0

cd "$BACKEND_DIR"
exec "${CMD[@]}"
