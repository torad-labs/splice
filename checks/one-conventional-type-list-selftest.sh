#!/usr/bin/env bash
# checks/one-conventional-type-list-selftest.sh — mutation-proves
# checks/config/one-conventional-type-list.ts (V4-30).
#
# The live wall walks the tree. This canary proves the wall can fail: a second
# copy of the conventional-type list reds, including a copy that happens to be
# correct, because a correct second copy is still the divergence mechanism.
# Fixtures are synthetic and hermetic.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

fail=0
err() { echo "  ✗ one-conventional-type-list-selftest: $1"; fail=1; }

mkdir -p "$tmp/checks/config"
cp "$ROOT/checks/config/one-conventional-type-list.ts" "$tmp/checks/config/" || {
  echo "  ✗ one-conventional-type-list-selftest: the checker is missing — the gate leg cannot be trusted"
  exit 1
}

# The fixture tree must carry the SOURCE file so the checker parses TYPES from
# it rather than from a list this selftest types.
SOURCE=tools/gate/src/lib/conventional.ts
mkdir -p "$tmp/$(dirname "$SOURCE")"
grep -E '^export const TYPES = ' "$ROOT/$SOURCE" > "$tmp/$SOURCE" || {
  echo "  ✗ one-conventional-type-list-selftest: $SOURCE has no TYPES export"
  exit 1
}

spaces_from_source() {
  bun -e "$(cat <<'JS'
const text = await Bun.file(process.argv[1]).text();
const match = /^export const TYPES = "([^"]+)";$/m.exec(text);
console.log(match[1].split("|").join(" "));
JS
)" "$1"
}

two_from_source() {
  bun -e "$(cat <<'JS'
const text = await Bun.file(process.argv[1]).text();
const match = /^export const TYPES = "([^"]+)";$/m.exec(text);
const parts = match[1].split("|");
console.log(parts[0], parts[1]);
JS
)" "$1"
}

CHECKER=(bun checks/config/one-conventional-type-list.ts check .)
TYPES_SPACES="$(spaces_from_source "$tmp/$SOURCE")"
TWO="$(two_from_source "$tmp/$SOURCE")"

arm() {
  local label="$1" want="$2"
  shift 2
  ( cd "$tmp" && "${CHECKER[@]}" >/dev/null 2>&1 )
  local rc=$?
  [ "$rc" = "$want" ] || err "$label: expected rc=$want, got rc=$rc"
}

arm_names() {
  local label="$1" needle="$2"
  local out
  out=$( cd "$tmp" && "${CHECKER[@]}" 2>&1 )
  local rc=$?
  [ "$rc" != 0 ] || err "$label: expected a red, got rc=0"
  echo "$out" | grep -q "$needle" || err "$label: expected to name $needle, got: $(echo "$out" | head -2)"
}

# 1 — source file alone is green.
arm "source file alone is green" 0

# 2 — THE BORING CASE: a second copy that is perfectly correct still reds.
printf '%s\n' "$TYPES_SPACES" > "$tmp/docs.md"
arm "correct second copy still reds" 1
arm_names "correct second copy is named" "docs.md"
rm -f "$tmp/docs.md"

# 3 — a second copy that also names types the org gate rejects reds.
printf '%s extra-one extra-two\n' "$TYPES_SPACES" > "$tmp/CONTRIBUTING.md"
arm "stale extra types still red" 1
arm_names "stale extra types named" "CONTRIBUTING.md"
rm -f "$tmp/CONTRIBUTING.md"

# 4 — two types in a row is not a restated vocabulary.
printf '%s\n' "$TWO" > "$tmp/note.md"
arm "two types are green" 0
rm -f "$tmp/note.md"

# 5 — missing source refuses to pass vacuously.
mv "$tmp/$SOURCE" "$tmp/$SOURCE.bak"
( cd "$tmp" && "${CHECKER[@]}" >/dev/null 2>&1 )
rc=$?
[ "$rc" != 0 ] || err "missing source: expected non-zero, got rc=0"
mv "$tmp/$SOURCE.bak" "$tmp/$SOURCE"

# 6 — empty tree of text files besides the source still green (scanned count > 0
#     because the walk sees the checker itself, which must not restate the list).
arm "checker file is not itself a second copy" 0

# 7 — untracked scratch is not in the git denominator; a tracked copy still reds.
git -C "$tmp" init -q
git -C "$tmp" add "$SOURCE" checks/config/one-conventional-type-list.ts
printf '%s\n' "$TYPES_SPACES" > "$tmp/scratch.md"
arm "untracked second copy is green under git ls-files" 0
git -C "$tmp" add scratch.md
arm "tracked second copy reds under git ls-files" 1
arm_names "tracked second copy is named" "scratch.md"
git -C "$tmp" rm -f --cached scratch.md >/dev/null
rm -f "$tmp/scratch.md"

if [ "$fail" -ne 0 ]; then
  echo "ONE CONVENTIONAL TYPE LIST SELFTEST FAIL"
  exit 1
fi
echo "  ✓ one-conventional-type-list selftest: correct copies red, two-type phrases green, missing source refuses"
exit 0
