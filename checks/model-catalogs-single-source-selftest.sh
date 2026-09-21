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
# THE CONTROL ASSERTS THE KNOWN GREEN, now that the drift is fixed. Until V4-109, the unmutated
# tree was RED: `splice add openrouter` and the first-run starter declared eight OpenRouter ids
# config/splice.example.toml never mentioned (recorded in the V4-98 ledger note). The control then
# asserted that KNOWN red by shape — exactly 8 distinct drifted ids across exactly 2 emitters,
# openrouter and nothing else — a stronger control than "green" because it pinned both that the
# wall fires and that it fires on the one provider that had actually drifted. V4-109 fixed the
# drift (2026-09-18), so the control now asserts the shipped tree is GREEN: a red here means the
# wall fires on the tree as shipped — a regression, or a NEW provider that drifted.
#
# EVERYTHING RUNS OUT OF TREE: a mktemp -d holding copies of the three real files under their real
# relative paths, plus a copy of the checker. Nothing is written into the working tree.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

CHECKER="$ROOT/checks/model-catalogs-single-source.ts"
EXAMPLE="config/splice.example.toml"
CATALOG="app/src/main/kotlin/splice/app/cli/add/AddProfileCatalog.kt"
STARTER="app/src/main/kotlin/splice/app/daemon/TopologyLoader.kt"

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
wall() { bun "$CHECKER" check "$tmp/tree" >"$tmp/out" 2>&1; rc=$?; }

must_fail() { # must_fail <label> <substring the failure must name>
  if [ "$rc" -eq 0 ]; then
    err "$1 — MUST exit non-zero, exited 0. The arm it proves is not enforcing."
  elif ! grep -qF -- "$2" "$tmp/out"; then
    err "$1 — exited $rc, but not for the stated reason (expected '$2'): $(head -3 "$tmp/out" | tr '\n' ' ')"
  else
    note "✓ $1 (exit $rc)"
  fi
}

# ── control: the KNOWN green, pinned since V4-109 ─────────────────────────────────────────────
reset_tree
wall
if [ "$rc" -ne 0 ]; then
  err "CONTROL: the shipped tree is expected GREEN (V4-109 fixed the openrouter drift, 2026-09-18). A red here means the wall fires on the tree as shipped — a regression, or a NEW provider drifted: $(head -3 "$tmp/out" | tr '\n' ' ')"
else
  note "✓ CONTROL: the shipped tree is green — the openrouter drift V4-109 fixed has not come back"
fi

# ── 1. a context window edited in the real AddProfileCatalog.kt ────────────────────────────────
# WINDOW_200K is the constant the claude/openrouter rows share. Moving the CONSTANT (not a row)
# also proves the constant resolution is live: if the parser had hardcoded windows, this is the
# mutation that would sail through.
reset_tree
# The harness mutates with bun rather than an inline heredoc for the retired interpreter, so this
# file stops being an invoker at all. `bun -e '<script>' ARG` puts ARG at process.argv[1].
bun -e '
const fs = require("fs");
const p = process.argv[1];
const before = fs.readFileSync(p, "utf8");
const after = before.replace("WINDOW_200K = 200_000L", "WINDOW_200K = 199_999L");
if (after === before) { console.error("MUTATION-NOT-APPLIED: WINDOW_200K constant not found"); process.exit(1); }
fs.writeFileSync(p, after);
' "$tmp/tree/$CATALOG"
if [ $? -ne 0 ]; then err "1 — mutation could not be applied to the real catalog copy"; else
  wall
  must_fail "1 catalog window drift (WINDOW_200K 200000 -> 199999)" "declares context_window 199999"
fi

# ── 2. a context window edited in the real TopologyLoader DEFAULT_TOML ─────────────────────────
reset_tree
bun -e '
const fs = require("fs");
const p = process.argv[1];
const before = fs.readFileSync(p, "utf8");
const after = before.replace(
  "id = \"meta-llama/llama-4-maverick\"\nlabel = \"Llama 4 Maverick\"\ncontext_window = 1048576",
  "id = \"meta-llama/llama-4-maverick\"\nlabel = \"Llama 4 Maverick\"\ncontext_window = 131072",
);
if (after === before) { console.error("MUTATION-NOT-APPLIED: the llama-4-maverick starter row not found"); process.exit(1); }
fs.writeFileSync(p, after);
' "$tmp/tree/$STARTER"
if [ $? -ne 0 ]; then err "2 — mutation could not be applied to the real starter copy"; else
  wall
  must_fail "2 DEFAULT_TOML window drift (llama-4-maverick 1048576 -> 131072)" "declares context_window 131072"
fi

# ── 3. a model row deleted from the real example: the SOURCE is the denominator ─────────────────
# z-ai/glm-5.3 is one of the two OpenRouter rows the example does declare and both emitters carry.
# Deleting it from the source must make BOTH emitters go red on that id — the arm that proves the
# example is the denominator rather than a third opinion.
reset_tree
bun -e '
const fs = require("fs");
const p = process.argv[1];
const lines = fs.readFileSync(p, "utf8").split("\n");
const out = [];
let i = 0;
let dropped = false;
while (i < lines.length) {
  if (lines[i].trim() === "[[providers.openrouter.models]]" && i + 1 < lines.length &&
      lines[i + 1].startsWith("id = \"z-ai/glm-5.3\"")) {
    i += 1;
    while (i < lines.length && !lines[i].startsWith("[")) i += 1;
    dropped = true;
    continue;
  }
  out.push(lines[i]);
  i += 1;
}
if (!dropped) { console.error("MUTATION-NOT-APPLIED: the z-ai/glm-5.3 example row not found"); process.exit(1); }
fs.writeFileSync(p, out.join("\n"));
' "$tmp/tree/$EXAMPLE"
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
if bun "$CHECKER" --selftest >"$tmp/out" 2>&1; then
  note "✓ 4 checker --selftest OK"
else
  err "4 — the checker's own --selftest FAILED: $(tail -5 "$tmp/out" | tr '\n' ' ')"
fi

if [ "$fail" -ne 0 ]; then
  echo "  ✗ model-catalogs-single-source-selftest: FAILED"
  exit 1
fi
echo "  ✓ model-catalogs-single-source-selftest: the shipped tree is green, and the wall fires on a"
echo "    window edited in either emitter, on a row deleted from the source, and its own"
echo "    fixture arms are green"
