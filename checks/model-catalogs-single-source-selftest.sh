#!/usr/bin/env bash
# checks/model-catalogs-single-source-selftest.sh — red-green proof for the model-roster wall,
# run by the gate beside the checker itself.
#
# WHY THIS EXISTS, ON TOP OF --selftest. The checker's own --selftest proves its arms against
# SYNTHETIC fixtures it authored. That is necessary and not sufficient: a fixture tree the checker
# wrote can drift away from the shape of the real files (a raw-string spelling, a trailing comment,
# an alias the fixtures never exercise) and every arm would stay green while the wall stopped
# reading the tree. So this script mutates COPIES OF THE REAL FILES and asserts the wall goes red
# by name on each one. The three checks below are the three mutations the row asks for, applied to
# production bytes rather than to fixtures:
#
#   1  a context window edited in the real AddProfileCatalog.kt      -> window arm, real Kotlin
#   2  a context window edited in the real TopologyLoader DEFAULT_TOML -> window arm, real raw string
#   3  a model row deleted from the real config/splice.example.toml  -> the SOURCE shrinking makes
#      every emitter row it carried go red, which is the arm that proves the example really is the
#      denominator and not decoration
#   4  the checker's own --selftest                                  -> the fixture arms
#
# THE CONTROL IS INVERTED HERE, and deliberately. Every other selftest in this repo asserts the
# unmutated tree is GREEN first. This wall's unmutated tree is RED today: `splice add openrouter`
# and the first-run starter both declare eight OpenRouter ids config/splice.example.toml never
# mentions (recorded in the V4-98 ledger note; V4-99's fix row owns the repair). So the control
# asserts the KNOWN red — exactly 8 distinct drifted ids across exactly 2 emitters, openrouter and
# nothing else — which is a stronger control than "green": it pins both that the wall fires and
# that it fires on the one provider that has actually drifted. When the fix row lands, this control
# flips to expecting green; until then a control that went GREEN would mean the wall stopped
# reading, and a control that named a NEW provider would mean something else regressed.
#
# EVERYTHING RUNS OUT OF TREE: a mktemp -d holding copies of the three real files under their real
# relative paths, plus a copy of the checker. Nothing is written into the working tree.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

CHECKER="$ROOT/checks/model-catalogs-single-source.py"
EXAMPLE="config/splice.example.toml"
CATALOG="gateway/app/src/main/kotlin/splice/app/cli/AddProfileCatalog.kt"
STARTER="gateway/app/src/main/kotlin/splice/app/TopologyLoader.kt"

fail=0
err() { echo "  ✗ model-catalogs-single-source-selftest: $1"; fail=1; }
note() { printf '  %s\n' "$1"; }

reset_tree() {
  rm -rf "$tmp/tree"
  for rel in "$EXAMPLE" "$CATALOG" "$STARTER"; do
    mkdir -p "$tmp/tree/$(dirname "$rel")"
    cp "$ROOT/$rel" "$tmp/tree/$rel"
  done
}

rc=0
wall() { python3 "$CHECKER" check "$tmp/tree" >"$tmp/out" 2>&1; rc=$?; }

must_fail() { # must_fail <label> <substring the failure must name>
  if [ "$rc" -eq 0 ]; then
    err "$1 — MUST exit non-zero, exited 0. The arm it proves is not enforcing."
  elif ! grep -qF -- "$2" "$tmp/out"; then
    err "$1 — exited $rc, but not for the stated reason (expected '$2'): $(head -3 "$tmp/out" | tr '\n' ' ')"
  else
    note "✓ $1 (exit $rc)"
  fi
}

# ── control: the KNOWN red, pinned by shape ───────────────────────────────────────────────────
reset_tree
wall
if [ "$rc" -eq 0 ]; then
  err "CONTROL: the real tree is expected RED today (openrouter drift). A clean pass means the wall stopped reading the tree — or the fix row landed, in which case flip this control to expect green."
else
  drifted="$(grep -oE '\[openrouter\]: [^ ]+' "$tmp/out" | awk '{print $2}' | sort -u | wc -l | tr -d ' ')"
  emitters="$(grep -oE '^  [^ ]+\.(kt|toml)(:DEFAULT_TOML)? \[' "$tmp/out" | sort -u | wc -l | tr -d ' ')"
  others="$(grep -cE '^  [^ ]+ \[(codex|grok|kimi|muse|deepseek|claude|api-key|xai|anthropic|fireworks)\]' "$tmp/out")"
  if [ "$drifted" != "8" ]; then
    err "CONTROL: expected 8 distinct drifted openrouter ids, counted $drifted: $(grep -c . "$tmp/out") lines"
  else
    note "✓ CONTROL: 8 distinct drifted openrouter ids, as recorded in the V4-98 ledger note"
  fi
  if [ "$emitters" != "2" ]; then
    err "CONTROL: expected the drift in exactly 2 emitters (catalog + DEFAULT_TOML), counted $emitters"
  else
    note "✓ CONTROL: the drift is in exactly 2 emitters"
  fi
  if [ "$others" != "0" ]; then
    err "CONTROL: expected openrouter to be the ONLY drifted provider, but $others line(s) name another"
  else
    note "✓ CONTROL: openrouter is the only drifted provider"
  fi
fi

# ── 1. a context window edited in the real AddProfileCatalog.kt ────────────────────────────────
# WINDOW_200K is the constant the claude/openrouter rows share. Moving the CONSTANT (not a row)
# also proves the constant resolution is live: if the parser had hardcoded windows, this is the
# mutation that would sail through.
reset_tree
python3 - "$tmp/tree/$CATALOG" <<'PY'
import pathlib, sys
p = pathlib.Path(sys.argv[1])
s = p.read_text(encoding="utf-8")
before = s
s = s.replace("WINDOW_200K = 200_000L", "WINDOW_200K = 199_999L", 1)
if s == before:
    sys.exit("MUTATION-NOT-APPLIED: WINDOW_200K constant not found")
p.write_text(s, encoding="utf-8")
PY
if [ $? -ne 0 ]; then err "1 — mutation could not be applied to the real catalog copy"; else
  wall
  must_fail "1 catalog window drift (WINDOW_200K 200000 -> 199999)" "declares context_window 199999"
fi

# ── 2. a context window edited in the real TopologyLoader DEFAULT_TOML ─────────────────────────
reset_tree
python3 - "$tmp/tree/$STARTER" <<'PY'
import pathlib, sys
p = pathlib.Path(sys.argv[1])
s = p.read_text(encoding="utf-8")
before = s
s = s.replace('id = "meta-llama/llama-4-maverick"\nlabel = "Llama 4 Maverick"\ncontext_window = 1048576',
              'id = "meta-llama/llama-4-maverick"\nlabel = "Llama 4 Maverick"\ncontext_window = 131072', 1)
if s == before:
    sys.exit("MUTATION-NOT-APPLIED: the llama-4-maverick starter row not found")
p.write_text(s, encoding="utf-8")
PY
if [ $? -ne 0 ]; then err "2 — mutation could not be applied to the real starter copy"; else
  wall
  must_fail "2 DEFAULT_TOML window drift (llama-4-maverick 1048576 -> 131072)" "declares context_window 131072"
fi

# ── 3. a model row deleted from the real example: the SOURCE is the denominator ─────────────────
# z-ai/glm-5.3 is one of the two OpenRouter rows the example does declare and both emitters carry.
# Deleting it from the source must make BOTH emitters go red on that id — the arm that proves the
# example is the denominator rather than a third opinion.
reset_tree
python3 - "$tmp/tree/$EXAMPLE" <<'PY'
import pathlib, sys
p = pathlib.Path(sys.argv[1])
s = p.read_text(encoding="utf-8")
lines = s.split("\n")
out, i, dropped = [], 0, False
while i < len(lines):
    if lines[i].strip() == "[[providers.openrouter.models]]" and i + 1 < len(lines) \
            and lines[i + 1].startswith('id = "z-ai/glm-5.3"'):
        i += 1
        while i < len(lines) and not lines[i].startswith("["):
            i += 1
        dropped = True
        continue
    out.append(lines[i])
    i += 1
if not dropped:
    sys.exit("MUTATION-NOT-APPLIED: the z-ai/glm-5.3 example row not found")
p.write_text("\n".join(out), encoding="utf-8")
PY
if [ $? -ne 0 ]; then err "3 — mutation could not be applied to the real example copy"; else
  wall
  must_fail "3 example row deleted (z-ai/glm-5.3)" "z-ai/glm-5.3 (context_window 1310720) is absent from"
  hits="$(grep -cF 'z-ai/glm-5.3 (context_window 1310720) is absent' "$tmp/out")"
  if [ "$hits" != "2" ]; then
    err "3 — deleting one SOURCE row must red BOTH emitters that carry it, got $hits"
  else
    note "✓ 3 the deleted source row reds both emitters"
  fi
fi

# ── 4. the checker's own fixture arms ──────────────────────────────────────────────────────────
if python3 "$CHECKER" --selftest >"$tmp/out" 2>&1; then
  note "✓ 4 checker --selftest OK"
else
  err "4 — the checker's own --selftest FAILED: $(tail -5 "$tmp/out" | tr '\n' ' ')"
fi

if [ "$fail" -ne 0 ]; then
  echo "  ✗ model-catalogs-single-source-selftest: FAILED"
  exit 1
fi
echo "  ✓ model-catalogs-single-source-selftest: the wall fires on the recorded openrouter drift and"
echo "    on a window edited in either emitter, on a row deleted from the source, and its own"
echo "    fixture arms are green"
