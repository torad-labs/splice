#!/usr/bin/env bash
# Canary self-test for .github/secret-scan-allow.txt.
#
# WHY THIS EXISTS. The allowlist is applied by the org scan as `grep -vEf`, so EVERY line in it is
# a live regex — the prose included, because `grep -f` has no comment syntax. A single careless
# line silently disables secret scanning while CI stays green, which is the worst possible failure
# shape: invisible, and it removes a control rather than breaking a build.
#
# It has already happened three times in one PR (#81):
#   - the exemption was UNANCHORED, so appending a real credential to the allowed declaration
#     bypassed the scan;
#   - two bare `#` separators were UNANCHORED regexes matching any hit CONTAINING a `#`, which
#     exempted every credential sitting in a shell/Python/YAML/TOML comment, repo-wide;
#   - an unbalanced `(` in a prose line made the file an INVALID pattern set, so `grep -vEf`
#     errors out instead of filtering at all.
# None was caught by review. All three are caught by a planted canary in under a second, so the
# verification is mechanical from here on and never done by eye again.
#
# The scan feeds `grep -nIE` output, so a hit always arrives as `<line>:<content>` and therefore
# begins with a DIGIT. Every canary below is written in that shape.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# Path is overridable so the hazards below can be exercised against a throwaway copy — a wall
# nobody has watched fail is not known to work.
ALLOW="${1:-$ROOT/.github/secret-scan-allow.txt}"
fail=0

note() { printf '  %s\n' "$1"; }
bad() { printf '  ✗ %s\n' "$1"; fail=1; }

[ -f "$ALLOW" ] || { echo "✗ missing $ALLOW"; exit 1; }

# ── structural rules ─────────────────────────────────────────────────────────
# A blank line is an empty regex and matches EVERY hit.
if grep -qE '^[[:space:]]*$' "$ALLOW"; then
  bad "blank line present — an empty regex matches every hit and disables the scan"
fi

# Every line must be either an anchored prose line (^#…, inert against digit-prefixed hits) or a
# deliberate pattern anchored at both ends. Anything else is an unanchored regex of unknown reach.
lineno=0
while IFS= read -r line; do
  lineno=$((lineno + 1))
  # An invalid ERE anywhere makes grep reject the WHOLE file — the allowlist stops filtering and
  # the scan step errors rather than passing. Validate each line in isolation so the message names
  # the offender instead of just the file.
  err="$(printf 'x\n' | grep -E -- "$line" 2>&1 >/dev/null)"
  [ -n "$err" ] && bad "line $lineno is not a valid ERE (breaks the entire file): $(printf '%s' "$err" | head -1)"

  case "$line" in
    '^#'*) continue ;;                                  # inert prose: a hit starts with a digit
    '^'*'$') continue ;;                                # fully anchored exemption
    *) bad "line $lineno unanchored (matches more than the one line it names): [$line]" ;;
  esac
done < "$ALLOW"

# ── canaries ─────────────────────────────────────────────────────────────────
# ASSEMBLED AT RUN TIME, never written as literals. A credential-shaped assignment sitting in this
# file trips the very supplementary scan this script exists to verify — which is exactly what
# happened on the first push of #81, and is the scan behaving correctly. Splitting each keyword
# from its `=` keeps the SOURCE clean while the assembled strings stay byte-identical to what the
# scan sees in real code, so the canaries lose no fidelity.
K='_KEY'
S='_SECRET'
EQ=' = '

# MUST stay reported (i.e. must NOT be exempted by any line in the allowlist).
must_report=(
  "43:    const val CUSTOM_API${K}_RESPONSES${EQ}\"customApiKeyResponses\"; val OPENROUTER_API${K}${EQ}\"sk-or-v1-canary000000000\""
  "44:    const val CLIENT${S}${EQ}\"canary-client-secret-000\""
  "77:  # leftover from debugging: api${K}${EQ}\"sk-live-canary00000000000\""
  "78:  AWS${S}_ACCESS${K}${EQ}\"canary00000000000000000000000000000000000\" # rotate me"
  "79:export GITHUB_TOKEN=\"ghp_canary0000000000000000000000000000\""
)
# MUST be exempted — the one declaration this allowlist exists for.
must_exempt=(
  "42:    const val CUSTOM_API${K}_RESPONSES${EQ}\"customApiKeyResponses\""
)

for hit in "${must_report[@]}"; do
  if ! printf '%s\n' "$hit" | grep -vEf "$ALLOW" >/dev/null 2>&1; then
    bad "SILENTLY EXEMPTED (scan blinded): $hit"
  fi
done

for hit in "${must_exempt[@]}"; do
  if printf '%s\n' "$hit" | grep -vEf "$ALLOW" >/dev/null 2>&1; then
    bad "intended exemption no longer applies: $hit"
  fi
done

if [ "$fail" -eq 0 ]; then
  note "secret-scan allowlist: ${#must_report[@]} canaries still reported, ${#must_exempt[@]} exemption intact"
fi
exit "$fail"
