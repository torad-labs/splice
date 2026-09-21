#!/usr/bin/env bash
# checks/role-registry-selftest.sh — red-green proof for the role registry (V4-89), against BOTH
# fixtures and the REAL tree.
#
# WHY A SECOND SELFTEST when role-registry.ts already has --selftest. The checker selftest proves the
# LOGIC on hand-built temp trees: it can say "a third name joining a dispositioned group is red"
# without ever reading a line of splice. That is necessary and not sufficient, because every claim
# it makes is about a tree it wrote itself. The claim this file adds is about REALITY: that the
# shipped disposition file accounts for the live tree exactly, that the only names it does NOT
# account for are the three duplicates the row was opened for, and that the checker's own regex
# census agrees with an INDEPENDENT one taken from ast-grep's Kotlin AST. A hand-authored list
# checked against another hand-authored list is not a check against reality (§24) — the
# denominator arm below is what keeps that from being what this wall is.
#
# WHAT IT ENCODES — a control that MUST be green, then fixtures that must each be red FOR THEIR
# STATED REASON. A fixture that goes red for the wrong reason has stopped testing anything:
#
#   A  control  the checker fixture selftest passes
#   B  control  the real tree with a FULLY-dispositioned config is GREEN  <- the green path, proven
#              against reality rather than against a fixture
#   1  the real tree with the SHIPPED config is red with EXACTLY the three duplicate names
#   2  GROWTH   a synthetic `fun interface` joining a dispositioned group -> red BY NAME
#   3  UNREASONED a shipped entry's reason blanked -> red, and its names stop being accounted for
#   4  ABSENCE  a shipped entry deleted -> every name under it red BY NAME
#   5  STALENESS a name added to `names` that no interface carries -> red BY NAME
#   6  DENOMINATOR the checker census equals ast-grep independent AST census, and is non-zero
#
# EVERYTHING RUNS OUT OF TREE, on the concentration-selftest.sh pattern: a mktemp -d holding a COPY
# of the checker and of the disposition file, plus one SYMLINK per gateway module. The checker
# derives its ROOT from its own module URL, so the copy under $tmp/checks measures $tmp — it reads the
# real sources through the symlinks while every mutation lands on a throwaway. Nothing is ever
# written into gateway/ or checks/, and the working tree is not touched at all.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

fail=0
err() { echo "  ✗ role-registry-selftest: $1"; fail=1; }
note() { printf '  %s\n' "$1"; }

CHECKER="$tmp/checks/role-registry.ts"
CONFIG="$tmp/checks/config/role-registry.toml"
SYNTH_DIR="$tmp/gateway/zz-selftest-role/src/main/kotlin/splice/selftest"

mkdir -p "$tmp/checks/config" "$tmp/gateway"
for main in "$ROOT"/gateway/*/src/main; do
  [ -d "$main" ] || continue
  mod="${main#"$ROOT"/gateway/}"
  mod="${mod%%/*}"
  ln -s "$ROOT/gateway/$mod" "$tmp/gateway/$mod"
done
# restructure PR 3: :client is the first module to live outside gateway/, so the loop above
# cannot reach it. A harness that measures a tree with one module missing hands its control a
# red that reads exactly like a real regression (or, worse, a green over a smaller tree).
[ -e "$tmp/client" ] || ln -s "$ROOT/client" "$tmp/client"
[ -e "$tmp/gateway/core" ] || { echo "  ✗ role-registry-selftest: no gateway modules found under $ROOT"; exit 1; }
[ -e "$tmp/client/src/main" ] || { echo "  ✗ role-registry-selftest: :client is not linked — the harness lost a module home"; exit 1; }

reset() {
  cp "$ROOT/checks/role-registry.ts" "$CHECKER"
  cp "$ROOT/checks/config/role-registry.toml" "$CONFIG"
  rm -rf "$tmp/gateway/zz-selftest-role"
}
reset

rc=0
run_check() { bun "$CHECKER" "$tmp" >"$tmp/out" 2>&1; rc=$?; }

must_fail() { # must_fail <label> <substring the failure must name>
  if [ "$rc" -eq 0 ]; then
    err "$1 — MUST exit non-zero, exited 0. The arm it is supposed to prove is not enforcing."
  elif ! grep -qF -- "$2" "$tmp/out"; then
    err "$1 — exited $rc, but not for the stated reason (expected '$2'): $(head -3 "$tmp/out" | tr '\n' ' ')"
  else
    note "✓ $1 (exit $rc)"
  fi
}

# ── A. control: the fixture selftest ─────────────────────────────────────────────────────────────
if ! bun "$ROOT/checks/role-registry.ts" --selftest >"$tmp/out" 2>&1; then
  err "CONTROL A: --selftest must pass: $(tail -6 "$tmp/out" | tr '\n' ' ')"
else
  note "✓ CONTROL A: the fixture selftest passes"
fi

# ── B. control: the real tree with the REAL config is GREEN ──────────────────────────────────────
# This used to APPEND three duplicate names to the entries disposing their signatures, because those
# three were the fix row's work list and the row had not run yet. V4-122 reconciled all three — onto
# ElapsedClock, WallClock and QuotaHeaderRead — so the names no longer exist and appending them made
# the control CONSTRUCT the staleness it then reported: a config listing a name the tree no longer
# declares is red by this wall's own rule, correctly. The real config IS the fully-dispositioned one
# now, so the control runs it unchanged. Its purpose is unchanged and still the load-bearing half:
# if this is not green, the wall's green path does not work against the live tree and every red arm
# below is meaningless.
run_check
if [ "$rc" -ne 0 ]; then
  err "CONTROL B: the real tree with a fully-dispositioned config must be GREEN (exit $rc): $(grep -c 'NO DISPOSITION\|STALE' "$tmp/out") finding(s): $(head -4 "$tmp/out" | tr '\n' ' ')"
else
  note "✓ CONTROL B: $(tail -1 "$tmp/out")"
fi
reset

if [ "$fail" -ne 0 ]; then
  echo "  ✗ role-registry-selftest: a control failed — the fixtures below are UNPROVEN, not passing"
  exit 1
fi

# ── 1. the shipped config accounts for EVERY shared signature ────────────────────────────────────
# This arm used to require the tree to be RED on exactly three duplicates, naming ElapsedNow,
# AccountNow and HeaderLookup — the row's own work list. V4-122 reconciled all three onto
# ElapsedClock, WallClock and QuotaHeaderRead, so a red here would now mean the row FAILED rather
# than that the wall worked; a control pinned to red stops being a control the moment the red is
# fixed. What it asserts instead is the state the row was supposed to reach.
#
# NOT VACUOUS, and arm 2 is why: that arm plants a seam the config does NOT list and requires the
# red BY NAME, so the NO-DISPOSITION path is still proven with a synthetic violation rather than
# with the shipped tree's own backlog.
run_check
if [ "$rc" -ne 0 ]; then
  err "1. the shipped config leaves signatures unaccounted for (exit $rc): $(grep -c 'NO DISPOSITION\|STALE' "$tmp/out") finding(s): $(head -3 "$tmp/out" | cut -c1-90 | tr '\n' ' ')"
else
  note "✓ 1. every shared signature is accounted for — $(tail -1 "$tmp/out")"
fi

# ── 2. GROWTH: a synthetic seam joining a dispositioned group ────────────────────────────────────
# `() -> Boolean` is the group whose 10 names ARE dispositioned, so a red here can only be the new
# name. A brand-new signature would prove nothing — the wall only grades SHARED signatures.
mkdir -p "$SYNTH_DIR"
cat > "$SYNTH_DIR/SelftestSeam.kt" <<'KT'
package splice.selftest

/** A synthetic seam sharing () -> Boolean with the ten dispositioned names. */
public fun interface SelftestClientVanished {
    public operator fun invoke(): Boolean
}
KT
run_check
must_fail "2. GROWTH — a new name joining a dispositioned group" "NO DISPOSITION: SelftestClientVanished"
grep -q "()->Boolean" "$tmp/out" ||
  err "2. GROWTH — the finding must name the shared signature so the fix is obvious"
if [ "$(grep -c 'NO DISPOSITION' "$tmp/out")" -ne 1 ]; then
  err "2. GROWTH — expected EXACTLY the synthetic name (V4-122 reconciled the three standing duplicates, so the count is 1, not 4), got: $(grep -c 'NO DISPOSITION' "$tmp/out")"
fi
reset

# ── 3. UNREASONED: a shipped entry's reason blanked ──────────────────────────────────────────────
# The entry is still present, still lists every name, still dated. Only the words are gone, which
# is the "absence wearing a label" this wall is built to refuse.
bun -e '
const fs = require("fs");
const path = process.argv[1];
const text = fs.readFileSync(path, "utf8");
const at = text.indexOf("signature = \"(Thread)->Unit\"");
const start = text.indexOf("reason = \"\"\"", at);
const end = text.indexOf("\"\"\"", start + "reason = \"\"\"".length);
fs.writeFileSync(path, text.slice(0, start) + "reason = \"\"\"   \"\"\"" + text.slice(end + 3));
' "$CONFIG"
run_check
must_fail "3. UNREASONED — a reason blanked to whitespace" "carries no reason"
grep -q "(Thread)->Unit" "$tmp/out" ||
  err "3. UNREASONED — the finding must name the signature whose reason went blank"
reset

# ── 4. ABSENCE: a shipped entry deleted outright ─────────────────────────────────────────────────
bun -e '
const fs = require("fs");
const path = process.argv[1];
const text = fs.readFileSync(path, "utf8");
const at = text.indexOf("signature = \"(Thread)->Unit\"");
const start = text.lastIndexOf("[[groups]]", at);
const nxt = text.indexOf("[[groups]]", at);
const end = nxt < 0 ? text.length : nxt;
fs.writeFileSync(path, text.slice(0, start) + text.slice(end));
' "$CONFIG"
run_check
must_fail "4. ABSENCE — a dispositioned group's entry deleted" "NO DISPOSITION: ShutdownHookAdd"
grep -q "NO DISPOSITION: ShutdownHookRemove " "$tmp/out" ||
  err "4. ABSENCE — both names under the deleted entry must be red BY NAME, not just the first"
reset

# ── 5. STALENESS: a name in `names` that no interface carries ────────────────────────────────────
# This is the arm that detects the FIX row's success: the moment ElapsedNow is deleted from the
# tree, an entry still listing it must go red rather than quietly keep a dead exemption.
bun -e '
const fs = require("fs");
const path = process.argv[1];
const text = fs.readFileSync(path, "utf8");
const at = text.indexOf("signature = \"(Thread)->Unit\"");
const namesAt = text.indexOf("names = [", at);
const close = text.indexOf("]", namesAt);
fs.writeFileSync(path, text.slice(0, close) + "    \"SelftestVanishedRole\",\n" + text.slice(close));
' "$CONFIG"
run_check
must_fail "5. STALENESS — a disposition naming an interface that no longer exists" "STALE DISPOSITION"
grep -q "SelftestVanishedRole" "$tmp/out" ||
  err "5. STALENESS — the finding must NAME the dead entry, or it cannot be removed"
reset

# ── 6. DENOMINATOR: the regex census must agree with ast-grep's AST census ───────────────────────
# §24: the checker's own regex cannot cross-check itself. This arm asks the parser CI already
# depends on for gate:rules, and refuses a vacuous agreement at zero.
if ! command -v ast-grep >/dev/null 2>&1; then
  err "6. DENOMINATOR — ast-grep is unavailable, so the independent census cannot run"
else
  bun -e '
const mod = await import(process.argv[1]);
const root = process.argv[2];
const { roles, problems: collectProblems } = mod.collect(root);
const { count: external, detail } = mod.denominator(root);
const problems = [...collectProblems];
if (external < 0) problems.push(`the independent census could not be taken: ${detail}`);
else if (external === 0) problems.push("the independent census found ZERO fun interfaces — refusing a vacuous agreement");
else if (external !== roles.length) problems.push(`regex census ${roles.length} != AST census ${external} — one of the two is wrong, and a denominator nobody can reproduce is not a denominator`);
for (const problem of problems) console.log(problem);
console.log(`census: regex ${roles.length}, ${detail}`);
process.exit(problems.length ? 1 : 0);
' "$ROOT/checks/role-registry.ts" "$ROOT" >"$tmp/denominator" 2>&1
  if [ "$?" -ne 0 ]; then
    err "6. DENOMINATOR — $(tail -3 "$tmp/denominator" | tr '\n' ' ')"
  else
    note "✓ 6. DENOMINATOR — $(tail -1 "$tmp/denominator")"
  fi
fi

if [ "$fail" -eq 0 ]; then
  note "role-registry selftest: 2 controls green, 5 mutation fixtures red for their stated reasons, regex and AST denominators agree"
fi
exit "$fail"
