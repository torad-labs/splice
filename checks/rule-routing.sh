#!/usr/bin/env bash
# checks/rule-routing.sh — the wall against a wall that is PRESENT but wired to nothing (HD-11).
#
# On 2026-07-16 this repo installed a Kotlin rule pack at .rules/kotlin/ (no-loose-function,
# no-companion-objects, a konsist ArchitectureTest) and the same day disabled the hook that ran it,
# "superseded by sgconfig route". The sgconfig route was wired to a DIFFERENT directory
# (.rules/kotlin-splice). ast-grep does not error on a rule directory nobody references — it simply
# never reads it — so a whole rule set sat in the tree producing zero findings for a month while
# 336 top-level functions and 58 companion objects accumulated under a green gate.
#
# A green wall proves nothing unless something reads it. This leg checks BOTH directions:
#   forward — every .rules/ directory holding ast-grep rule files is REFERENCED by sgconfig.yml
#             (ruleDirs: or testConfigs.testDir:) or carries a dated entry in UNROUTED_ALLOWLIST
#   inverse — every ruleDirs: entry EXISTS and holds at least one rule file (a typo'd or emptied
#             ruleDir is the same fail-open bug seen from the other side)
#
# testConfigs.testDir counts as a reference because it IS one: `ast-grep test` reads those files
# every gate run. A typo'd testDir does not slip through — it un-references .rules/rule-tests, and
# the forward direction fails on it.
#
# Run: `bash checks/rule-routing.sh`, and as a leg of `npm run gate`.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
fail=0
err() { echo "  ✗ $1"; fail=1; }

SGCONFIG=sgconfig.yml

# Directories deliberately NOT routed. Format: <path>|<YYYY-MM-DD>: <reason>. An entry covers the
# path and everything beneath it. The date is REQUIRED and mechanically checked — an undated
# exemption is how the next dormant pack hides. An entry whose path no longer exists is a hard
# failure, not a no-op: a stale line is exactly the "referenced by nothing" state, one level up.
UNROUTED_ALLOWLIST=(
  ".rules/kotlin|2026-08-17: the torad-toolkit Android/Compose/XR pack, vendored 2026-07-16 for reference only. Its rules (Compose, ViewModel, no-mutable-var, no-runblocking) encode a different project type and contradict this Ktor gateway's mandated idioms, so routing it here would turn the gate red on correct code. Splice's own Kotlin walls live in .rules/kotlin-splice. UNSCANNED IS NOT UNTESTED (HD-21): the pack now carries its own sgconfig testConfigs, and npm run gate:rules runs 'ast-grep test --config .rules/kotlin/ast-grep/sgconfig.yml' so a dormant matcher can be red/green pinned BEFORE it graduates — a rule-test dropped into the root .rules/rule-tests for a rule outside the root ruleDirs is silently skipped, not reported."
)

# A "rule file" is a .yml/.yaml carrying an `id:` key — the SAME test checks/config-guard.sh uses to
# decide what is a rule definition, so the two legs cannot disagree about what a rule is.
rule_file_count() { # rule_file_count <dir> <maxdepth>
  local n=0 f
  while IFS= read -r f; do
    grep -qE '^[[:space:]]*id:' "$f" && n=$((n + 1))
  done < <(find "$1" -maxdepth "$2" -type f \( -name '*.yml' -o -name '*.yaml' \) 2>/dev/null)
  printf '%s\n' "$n"
}

normalize_path() { # strip surrounding quotes and any trailing slash
  local p="$1"
  p="${p%\"}" p="${p#\"}"
  p="${p%\'}" p="${p#\'}"
  printf '%s\n' "${p%/}"
}

sg_rule_dirs() { # the `- entry` items under the top-level ruleDirs: block
  awk '
    /^ruleDirs:[[:space:]]*$/ { blk = 1; next }
    /^[^[:space:]#]/          { blk = 0 }
    blk && /^[[:space:]]*-[[:space:]]+/ {
      line = $0
      sub(/^[[:space:]]*-[[:space:]]+/, "", line)
      sub(/[[:space:]]*#.*$/, "", line)
      sub(/[[:space:]]+$/, "", line)
      if (line != "") print line
    }
  ' "$SGCONFIG"
}

sg_test_dirs() { # every testConfigs entry's testDir: value
  grep -E '^[[:space:]]*-?[[:space:]]*testDir:' "$SGCONFIG" |
    sed -e 's/.*testDir:[[:space:]]*//' -e 's/[[:space:]]*#.*$//' -e 's/[[:space:]]*$//'
}

# sg_rule_dirs reads the BLOCK-list form only. A flow-style declaration (`ruleDirs: [a, b]`) never
# sets blk, so RULE_DIRS comes back empty — fail-closed via the emptiness check below, but under a
# diagnostic that names the wrong cause ("declares no ruleDirs") and sends the reader hunting for a
# key that is sitting right there, while every real rule directory is simultaneously reported as
# unreferenced. Refusing the shape the parser cannot read keeps the fail-closed property and names
# the actual cause; this stays deliberately short of implementing YAML (review 2026-08-28, PR 99).
grep -qE '^ruleDirs:[[:space:]]*[^[:space:]#]' "$SGCONFIG" &&
  err "$SGCONFIG declares ruleDirs in flow style; this gate parses only the block form (\`ruleDirs:\` then \`  - dir\`)."

RULE_DIRS=()
while IFS= read -r d; do [ -n "$d" ] && RULE_DIRS+=("$(normalize_path "$d")"); done < <(sg_rule_dirs)
REFERENCED=("${RULE_DIRS[@]}")
while IFS= read -r d; do [ -n "$d" ] && REFERENCED+=("$(normalize_path "$d")"); done < <(sg_test_dirs)

[ "${#RULE_DIRS[@]}" -gt 0 ] ||
  err "$SGCONFIG declares no ruleDirs — every ast-grep wall in this repo is dormant."

# Prefix, not exact match — MEASURED, not assumed (2026-08-16, ast-grep 0.45.0): a rule file in a
# SUBdirectory of a routed ruleDir is read. Probe was a deliberately malformed rule (`rule:
# {thisIsNotAValidRuleKey: 1}`); at .rules/rules/probe.yml `ast-grep scan` exits 8 "Cannot parse
# rule", and at .rules/rules/__nested/probe.yml it exits 8 on the same file. If a future ast-grep
# stops recursing, this must become an exact match or the leg fails open on nested layouts.
covered_by() { # covered_by <dir> <prefix...> — true if dir IS a prefix or lives beneath one
  local dir="$1" p
  shift
  for p in "$@"; do
    [ -n "$p" ] || continue
    [ "$dir" = "$p" ] && return 0
    case "$dir/" in "$p"/*) return 0 ;; esac
  done
  return 1
}

# --- forward: nothing under .rules/ may be present-but-unreferenced ---------------------------
ALLOWED_PATHS=()
for entry in "${UNROUTED_ALLOWLIST[@]}"; do ALLOWED_PATHS+=("${entry%%|*}"); done

while IFS= read -r dir; do
  count="$(rule_file_count "$dir" 1)"
  [ "$count" -gt 0 ] || continue
  covered_by "$dir" "${REFERENCED[@]}" && continue
  covered_by "$dir" "${ALLOWED_PATHS[@]}" && continue
  err "$dir holds $count ast-grep rule file(s) but nothing references it — add it to ruleDirs: in $SGCONFIG, or give it a dated entry in UNROUTED_ALLOWLIST in this script. ast-grep never errors on an unreferenced rule directory; it silently scans none of those rules, which is exactly how .rules/kotlin ran dormant for a month."
done < <(find .rules -type d | sort)

# --- inverse: every routed directory must exist and actually carry rules -----------------------
for dir in "${RULE_DIRS[@]}"; do
  if [ ! -d "$dir" ]; then
    err "$SGCONFIG ruleDirs lists '$dir', which does not exist — ast-grep reads nothing there and still exits 0. Fix the path or drop the entry."
    continue
  fi
  count="$(rule_file_count "$dir" 99)"
  [ "$count" -gt 0 ] ||
    err "$SGCONFIG ruleDirs lists '$dir' but it holds 0 ast-grep rule files — an emptied ruleDir is a wall that passes by having nothing to say."
done

# --- the allowlist itself is checked: dated, and never stale -----------------------------------
for entry in "${UNROUTED_ALLOWLIST[@]}"; do
  path="${entry%%|*}"
  reason="${entry#*|}"
  [ -d "$path" ] ||
    err "UNROUTED_ALLOWLIST names '$path', which no longer exists — delete the entry (a stale exemption is an unread rule directory one level up)."
  printf '%s' "$reason" | grep -qE '^[0-9]{4}-[0-9]{2}-[0-9]{2}: .' ||
    err "UNROUTED_ALLOWLIST entry for '$path' has no dated reason — every exemption starts 'YYYY-MM-DD: <why>'."
done

if [ "$fail" -eq 0 ]; then echo "rule-routing: PASS"; else echo "rule-routing: FAIL"; fi
exit "$fail"
