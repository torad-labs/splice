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
  esac

  # DR-188: this rule used to be the glob '^'*'$' — "starts with ^, ends with $" — and it called
  # that FULLY ANCHORED. It is not. ERE gives `|` the lowest precedence, so
  # `^[0-9]+:[[:space:]]*a|b[[:space:]]*$` starts with ^ and ends with $ while parsing as
  # `(^…a)` OR `(b…$)`: only the outermost alternatives carry an anchor and every branch between
  # them floats free. A credential appended to the first branch, or prepended to the second, was
  # silently exempted while this rule reported the line as anchored. The generator now brackets the
  # pattern, which makes the whole emitted shape checkable — so check the shape, not two characters
  # of it, and a hand-edit that drops the group fails here.
  if ! printf '%s\n' "$line" | grep -qE '^\^\[0-9\]\+:\[\[:space:\]\]\*\(.*\)\[\[:space:\]\]\*\$$'; then
    bad "line $lineno is not a bracketed, fully anchored exemption (reach unknown): [$line]"
  fi
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

# ── generator contracts ──────────────────────────────────────────────────────
# DR-188: nothing anywhere exercised the GENERATOR. This script read the .txt and the gate ran
# `--check` against the real tree, so every rejection path in gen-secret-scan-allow.py — anchors,
# missing reason, invalid ERE — was unexercised: a wall nobody has watched fail. The arms below run
# it against fixture inputs in a throwaway tree (the generator resolves its paths from __file__, so
# copying it is enough to relocate its whole world).
GEN="$ROOT/checks/gen-secret-scan-allow.py"
if [ ! -f "$GEN" ]; then
  bad "the generator is missing — the allowlist's source of truth cannot be verified"
else
  gtmp="$(mktemp -d)"
  trap 'rm -rf "$gtmp"' EXIT
  mkdir -p "$gtmp/checks" "$gtmp/.github"
  cp "$GEN" "$gtmp/checks/"

  # $1 label · $2 expected rc (0 generated / 1 refused) · $3 TOML body
  gen_arm() {
    printf '%s\n' "$3" > "$gtmp/.github/secret-scan-allow.toml"
    ( cd "$gtmp" && python3 checks/gen-secret-scan-allow.py >/dev/null 2>&1 )
    local rc=$?
    [ "$rc" = "$2" ] || bad "generator: $1 — expected rc=$2, got rc=$rc"
  }
  # A pattern is a TOML literal string, so it may not contain a single quote; none below does.
  gen_pattern() { printf "[[exemption]]\npattern = '%s'\nreason = 'fixture arm'\n" "$1"; }

  # G1 — an alternation is ACCEPTED, and the emitted line must BIND it. This is DR-188's whole
  #      subject: before bracketing the same input produced a line that silently exempted anything
  #      appended to the first branch or prepended to the second.
  gen_arm "an alternation is accepted" 0 "$(gen_pattern 'val fixtureA = "aaa"|val fixtureB = "bbb"')"
  emitted="$gtmp/.github/secret-scan-allow.txt"
  for probe in \
    '99:  AWS_SECRET_ACCESS_KEY = "AKIAIOSFODNN7EXAMPLE"; val fixtureB = "bbb"' \
    '98:  val fixtureA = "aaa" ; token = "ghp_canary0000000000000000000000000000"'
  do
    printf '%s\n' "$probe" | grep -vEf "$emitted" >/dev/null 2>&1 \
      || bad "generator: an alternation exemption still swallows a whole hit line: $probe"
  done
  # ...and the branches themselves must still be exempted, or the fix broke what it protects.
  printf '%s\n' '10:  val fixtureB = "bbb"' | grep -vEf "$emitted" >/dev/null 2>&1 \
    && bad "generator: bracketing broke a legitimate alternation branch"

  # G2 — the breakout. `a)|(b` would close the group the generator adds and float free again; it is
  #      refused because the BARE pattern is validated as an ERE, not because of any paren logic.
  #      The generated line `(a)|(b)` is perfectly valid, so this arm is what pins that the bare
  #      half of the ERE check stays — delete it and the breakout reopens with everything green.
  gen_arm "a breakout pattern is refused" 1 "$(gen_pattern 'val a = "x")|(val b = "y"')"

  # G3..G5 — contracts the generator has always claimed and nothing has ever exercised.
  gen_arm "a pattern bringing its own anchor is refused" 1 "$(gen_pattern '^val a = "x"')"
  gen_arm "an invalid ERE is refused" 1 "$(gen_pattern 'val a = "x"(')"
  gen_arm "an exemption with no reason is refused" 1 "$(printf "[[exemption]]\npattern = 'val a = \"x\"'\n")"
  gen_arm "an empty allowlist is refused" 1 "# no exemptions at all"
fi

if [ "$fail" -eq 0 ]; then
  note "secret-scan allowlist: ${#must_report[@]} canaries still reported, ${#must_exempt[@]} exemption intact, generator contracts held"
fi
exit "$fail"
