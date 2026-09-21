#!/usr/bin/env bash
# checks/schema-keys-consumed-selftest.sh — red-green proof for the V4-91 config-key wall.
#
# HERMETIC. Every arm below, the CONTROL included, runs over a temp-tree FIXTURE written by this
# script: a synthetic parser file (Topology/DaemonConfig/HeadConfig/ProviderConfig/QuirksConfig and
# a Knob enum), a synthetic consumer, a synthetic echo surface and a synthetic accessor facade.
# Nothing here reads gateway/ except the one LIVE SMOKE arm at the end, and that arm asserts only
# SHAPE — that the checker runs, exits 0 or 1, reports a denominator, and names any finding it has.
#
# WHY HERMETIC, dated 2026-09-17 and the reason this file was rewritten. The first revision pinned
# the LIVE tree's red COUNT in its control ("expected EXACTLY 2 findings"). That control went red
# within the hour — not because anything broke, but because V4-109 retired `state_dir`, which is
# the work the finding EXISTS to cause. A wall whose selftest fails when its own fix row succeeds
# teaches exactly one lesson, and it is to delete the selftest. The live tree is the wall's
# SUBJECT, never its fixture: a count that a fix row is supposed to change cannot be an assertion.
#
# WHAT THE ARMS ADD OVER `schema-keys-consumed.ts --selftest`, which also uses fixtures: that one
# swaps NON_CONSUMPTION and ALLOWLIST through module globals, in-process. These arms mutate the
# CHECKER FILE AS SHIPPED — its exclusion list, its allowlist, its source glob, and in two INVERSE
# arms the mechanisms themselves — so the thing proven is the artifact the gate leg executes.
#
# THE TWO INVERSE ARMS ARE THE POINT. They mutate a mechanism OUT and require the finding to
# DISAPPEAR, which is the only way to show the mechanism is what found it rather than decoration:
#   · drop the echo-surface exclusion  -> the dead key reads as consumed. A key whose only read is
#     `put("<key>", ...)` in a doctor shape is what this wall exists to name; if that file counted,
#     the schema plane would be green over a dead key.
#   · drop the receiver qualification  -> the dead key reads as consumed. The fixture declares the
#     SAME property name on a second class, exactly as the live tree does (DaemonConfig.stateDir
#     and StatePaths.stateDir), so a name-only rule matches the wrong owner and calls it wired.
#
# THE CONTROL COMES FIRST: each arm claims "this mutation turns green into red", which is worth
# nothing unless the unmutated fixture is green.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

fail=0
err() { echo "  x schema-keys-consumed-selftest: $1"; fail=1; }
note() { printf '  %s\n' "$1"; }

CHECK="$tmp/checks/schema-keys-consumed.ts"
FIXTURE_PKG="gateway/zzfix/src/main/kotlin/splice/zzfix"
FIXTURE="$tmp/$FIXTURE_PKG"

mkdir -p "$tmp/checks"

# NOTHING MAY LAND IN THE TREE (the 2026-09-17 scar: an earlier public-surface harness `ln -s`-ed
# THROUGH an existing symlink and created gateway/build-logic/build-logic in the working tree).
# This harness never symlinks at all, and the guard is re-checked at exit.
tree_state="$(cd "$ROOT/gateway" && ls -1A)"

# ── the fixture ───────────────────────────────────────────────────────────────────────────────
# write_fixture <dead_key|-> <dead_knob|-> <rename|-> <orphan|->
#   dead_key   a schema key whose ONLY read is the echo surface (state_dir's exact shape)
#   dead_knob  a knob whose ONLY reader is a facade accessor nobody calls (debug's exact shape)
#   rename     renames QuirksConfig, so the class search cannot find it
#   orphan     puts a @SerialName inside the constructor that belongs to no parameter
write_fixture() {
  rm -rf "$tmp/gateway"
  mkdir -p "$FIXTURE"
  bun -e '
const fs = require("fs");
const path = require("path");
const [out, ...flags] = process.argv.slice(1);
const [deadKey, deadKnob, rename, orphan] = flags.map((arg) => arg !== "-");

const quirks = rename ? "QuirksRenamed" : "QuirksConfig";
const deadLine = deadKey ? "    @SerialName(\"zz_dead_dir\") val zzDeadDir: String? = null,\n" : "";
const orphanLine = orphan ? "    @SerialName(\"zz_orphan\")\n" : "";

// The synthetic PARSER file: the five classes the wall denominator is taken from. Every key is
// wired by Wiring.kt below EXCEPT the optional dead one, and three keys are wired in the ways the
// live tree wires them and a naive rule would call dead:
//   extraWindows  — read ONLY inside this file, projected into a domain type (Catalog)
//   compactEffort — read ONLY by a require() in its own init, which REJECTS a retired key
//   contextWindow — an AMBIGUOUS name (Catalog declares one too), read receiver-qualified elsewhere
fs.writeFileSync(path.join(out, "Schema.kt"),
  "package splice.zzfix\n\n" +
  "import kotlinx.serialization.SerialName\n" +
  "import kotlinx.serialization.Serializable\n\n" +
  "@Serializable\n" +
  "public data class Topology(\n" +
  "    val daemon: DaemonConfig = DaemonConfig(),\n" +
  "    val providers: Map<String, ProviderConfig> = emptyMap(),\n" +
  "    val heads: Map<String, HeadConfig> = emptyMap(),\n" +
  ")\n\n" +
  "@Serializable\n" +
  "public data class DaemonConfig(\n" +
  "    @SerialName(\"control_port\") val controlPort: Int? = null,\n" +
  "    @SerialName(\"state_dir\") val stateDir: String? = null,\n" +
  deadLine + orphanLine + ")\n\n" +
  "@Serializable\n" +
  "public data class HeadConfig(\n" +
  "    val port: Int,\n" +
  "    /** A KDoc between parameters, with a comma and a ) in it. */\n" +
  "    @SerialName(\"context_window\") val contextWindow: Long? = null,\n" +
  ")\n\n" +
  "@Serializable\n" +
  "public data class ProviderConfig(\n" +
  "    @SerialName(\"base_url\") val baseUrl: String,\n" +
  `    val quirks: ${quirks} = ${quirks}(),\n` +
  "    @SerialName(\"extra_windows\") val extraWindows: List<String> = emptyList(),\n" +
  ") {\n" +
  "    public fun toCatalog(): Catalog = Catalog(extraWindows = extraWindows)\n" +
  "}\n\n" +
  "@Serializable\n" +
  `public data class ${quirks}(\n` +
  "    val store: Boolean = false,\n" +
  "    @SerialName(\"compact_effort\") val compactEffort: String? = null,\n" +
  ") {\n" +
  "    init {\n" +
  "        require(compactEffort == null) { \"compact_effort is retired\" }\n" +
  "    }\n" +
  "}\n");

fs.writeFileSync(path.join(out, "Catalog.kt"),
  "package splice.zzfix\n\n" +
  "public class Catalog(\n" +
  "    public val extraWindows: List<String> = emptyList(),\n" +
  "    public val contextWindow: Long = 0,\n" +
  ") {\n" +
  "    public fun widest(): String? = extraWindows.maxOrNull()\n" +
  "}\n");

// The synthetic CONSUMER. Reads every key that is supposed to be wired, and nothing else.
fs.writeFileSync(path.join(out, "Wiring.kt"),
  "package splice.zzfix\n\n" +
  `internal class Wiring(private val topology: Topology, private val quirks: ${quirks}) {\n` +
  "    fun bind(): Int = topology.daemon.controlPort ?: 0\n" +
  "    fun providerKeys(): Set<String> = topology.providers.keys\n" +
  "    fun headKeys(): Set<String> = topology.heads.keys\n" +
  "    fun window(head: HeadConfig): Long = head.contextWindow ?: 0\n" +
  "    fun port(head: HeadConfig): Int = head.port\n" +
  "    fun base(provider: ProviderConfig): String = provider.baseUrl\n" +
  `    fun quirksOf(provider: ProviderConfig): ${quirks} = provider.quirks\n` +
  "    fun store(): Boolean = quirks.store\n" +
  "    fun dir(): String? = topology.daemon.stateDir\n" +
  "}\n");

// The synthetic ECHO SURFACE: it puts the dead key back out under its own key name and does
// nothing else with it. This is the only file in the fixture that touches DaemonConfig.zzDeadDir,
// and it is excluded, which is what makes the dead key red.
const echoLines = deadKey ? "        \"zz_dead_dir\" to t.daemon.zzDeadDir,\n" : "";
fs.writeFileSync(path.join(out, "Doctor.kt"),
  "package splice.zzfix\n\n" +
  "internal class DoctorShape {\n" +
  "    fun shape(t: Topology): Map<String, Any?> = mapOf(\n" +
  echoLines +
  "        \"port\" to t.daemon.controlPort,\n" +
  "    )\n" +
  "}\n");

// The AMBIGUITY TWIN, in a file that is NOT excluded: a second class declaring the SAME property
// name, read through a receiver that is not a spelling of DaemonConfig. StatePaths.stateDir in the
// live tree, exactly. Receiver-qualified, this read belongs to LocalPaths and the schema key stays
// red; name-only, it makes the dead schema key look wired. Arm 4 is that difference.
fs.writeFileSync(path.join(out, "Paths.kt"),
  "package splice.zzfix\n\n" +
  "internal class LocalPaths {\n" +
  "    val zzDeadDir: String = \"/var/lib/zzfix\"\n" +
  "}\n\n" +
  "internal class PathUser(private val paths: LocalPaths) {\n" +
  "    fun dir(): String = paths.zzDeadDir\n" +
  "}\n");

const knobDead = deadKnob ? "    ZZ_DEAD_KNOB(\"zzDeadKnob\", false),\n" : "";
fs.writeFileSync(path.join(out, "Knob.kt"),
  "package splice.zzfix\n\n" +
  "public enum class Knob(\n" +
  "    public val key: String,\n" +
  "    public val default: Any?,\n" +
  ") {\n" +
  "    PORT(\"port\", 3099L),\n" +
  "    WIRED_DIRECT(\"wiredDirect\", \"x\"),\n" +
  "    WIRED_VIA_ACCESSOR(\"wiredViaAccessor\", true),\n" +
  knobDead +
  "}\n");

const facadeDead = deadKnob ? "    public val zzDeadKnob: Boolean get() = m[Knob.ZZ_DEAD_KNOB.key] == true\n" : "";
fs.writeFileSync(path.join(out, "SpliceConfig.kt"),
  "package splice.zzfix\n\n" +
  "public class SpliceConfig internal constructor(private val m: Map<String, Any?>) {\n" +
  "    public val port: Int get() = (m[Knob.PORT.key] as? Int) ?: 0\n" +
  "    public val wiredViaAccessor: Boolean get() = m[Knob.WIRED_VIA_ACCESSOR.key] == true\n" +
  facadeDead +
  "}\n");

// The synthetic KNOB CONSUMER: one knob read directly, one read through the facade accessor (the
// single hop the wall follows). The dead knob accessor is deliberately never called.
fs.writeFileSync(path.join(out, "KnobWiring.kt"),
  "package splice.zzfix\n\n" +
  "internal class KnobWiring(private val cfg: SpliceConfig) {\n" +
  "    fun direct(): String = Knob.WIRED_DIRECT.key\n" +
  "    fun viaAccessor(): Boolean = cfg.wiredViaAccessor\n" +
  "    fun bind(): Int = cfg.port\n" +
  "}\n");
' "$FIXTURE" "$1" "$2" "$3" "$4"
}

# The checker copy, with its two non-consumption surfaces retargeted at the fixture's own.
reset_check() {
  cp "$ROOT/checks/schema-keys-consumed.ts" "$CHECK"
  # The harness mutates with bun rather than an inline heredoc for the retired interpreter, so this
# file stops being an invoker at all. `bun -e '<script>' ARG...` puts ARGs at process.argv[1..].
bun -e '
const fs = require("fs");
const [target, pkg] = process.argv.slice(1);
const text = fs.readFileSync(target, "utf8");
const start = text.indexOf("let NON_CONSUMPTION");
const end = text.indexOf("\n];\n", start) + 4;
if (start < 0 || end < 4) throw new Error("the NON_CONSUMPTION declaration moved");
const replacement =
  "let NON_CONSUMPTION: [string, string][] = [\n" +
  `  ["${pkg}/Doctor.kt", "2026-09-17: the fixture echo surface"],\n` +
  `  ["${pkg}/SpliceConfig.kt", "2026-09-17: the fixture accessor facade"],\n` +
  "];\n";
fs.writeFileSync(target, text.slice(0, start) + replacement + text.slice(end));
' "$CHECK" "$FIXTURE_PKG"
}

rc=0
check() { bun "$CHECK" "$tmp" >"$tmp/out" 2>&1; rc=$?; }

must_fail() { # must_fail <label> <substring the failure must name>
  if [ "$rc" -eq 0 ]; then
    err "$1 — MUST exit non-zero, exited 0. The arm it is supposed to prove is not enforcing."
  elif ! grep -qF -- "$2" "$tmp/out"; then
    err "$1 — exited $rc, but not for the stated reason (expected '$2'): $(head -4 "$tmp/out" | tr '\n' ' ')"
  else
    note "ok $1 (exit $rc)"
  fi
}

must_pass() { # must_pass <label>
  if [ "$rc" -ne 0 ]; then
    err "$1 — must be GREEN, exited $rc: $(grep '^  x ' "$tmp/out" | cut -c1-200 | tr '\n' ' ')"
  else
    note "ok $1"
  fi
}

allow() { # allow <entry list body, in TypeScript array syntax>
  bun -e '
const fs = require("fs");
const [target, body] = process.argv.slice(1);
const text = fs.readFileSync(target, "utf8");
const needle = "let ALLOWLIST: [string, string][] = [];";
if (!text.includes(needle)) throw new Error("the ALLOWLIST declaration moved");
fs.writeFileSync(target, text.replace(needle, "let ALLOWLIST: [string, string][] = [" + body + "];"));
' "$CHECK" "$1"
}

# ── control: the fixture logic in-process, then the fully-wired fixture ───────────────────────
bun "$ROOT/checks/schema-keys-consumed.ts" --selftest >"$tmp/out" 2>&1 || {
  err "CONTROL: the in-process fixture selftest must be green: $(tail -6 "$tmp/out" | tr '\n' ' ')"
}

write_fixture - - - -
reset_check
check
keys="$(grep -oE '[0-9]+ config key\(s\) examined' "$tmp/out" | grep -oE '^[0-9]+')"
if [ "$rc" -ne 0 ]; then
  err "CONTROL: the fully-wired fixture must be GREEN (exit $rc): $(grep '^  x ' "$tmp/out" | cut -c1-200 | tr '\n' ' ')"
elif [ -z "${keys:-}" ] || [ "$keys" -lt 10 ]; then
  err "CONTROL: the fixture yielded ${keys:-no} keys — the fixture or the parser is broken, so every arm below is unproven"
else
  note "ok CONTROL: the fully-wired fixture is green over $keys synthetic keys"
fi
if [ "$fail" -ne 0 ]; then
  echo "  x schema-keys-consumed-selftest: control failed — the arms below are UNPROVEN, not passing"
  exit 1
fi

# ── 1. a schema key whose only read is the ECHO surface (state_dir's exact shape) ─────────────
write_fixture dead - - -
check
must_fail "1. a schema key read only by the echo surface is RED BY NAME" "zz_dead_dir"
grep -q "echo surface" "$tmp/out" ||
  err "1. the reason must NAME the echo surface rather than claim nothing reads it: $(grep zz_dead_dir "$tmp/out" | cut -c1-200)"

# ── 2. a knob whose only reader is an accessor nobody calls (debug's exact shape) ─────────────
write_fixture - dead - -
check
must_fail "2. a knob read only by an uncalled facade accessor is RED BY NAME" "zzDeadKnob"

# ── 3. INVERSE: without the ECHO-SURFACE exclusion, the wall finds nothing ────────────────────
write_fixture dead - - -
reset_check
bun -e '
const fs = require("fs");
const target = process.argv[1];
const text = fs.readFileSync(target, "utf8");
// Dropped where the exclusion is APPLIED, not where it is declared, so NON_CONSUMPTION dated-
// reason and staleness validation keeps running: this arm removes exactly one thing.
const needle = "const excluded = new Set(NON_CONSUMPTION.map(([rel]) => rel));";
if (!text.includes(needle)) throw new Error("the excluded-set construction moved — this fixture is stale");
fs.writeFileSync(target, text.replace(needle, "const excluded = new Set(NON_CONSUMPTION.map(([rel]) => rel).filter((rel) => !rel.endsWith(\"Doctor.kt\")));"));
' "$CHECK"
check
if grep -q "zz_dead_dir" "$tmp/out"; then
  err "3. INVERSE echo surface — the dead key is STILL red with the exclusion dropped, so the exclusion is not what finds it and the arm proves nothing"
else
  note "ok 3. INVERSE — dropping the echo-surface exclusion makes the dead key read as consumed; the exclusion is load-bearing"
fi

# ── 4. INVERSE: without RECEIVER QUALIFICATION, a name-only rule calls the dead key wired ─────
# The fixture declares `zzDeadDir` on DaemonConfig AND on LocalPaths, and Doctor.kt reads
# `paths.zzDeadDir` — the live tree's DaemonConfig.stateDir / StatePaths.stateDir shape exactly.
reset_check
bun -e '
const fs = require("fs");
const target = process.argv[1];
const text = fs.readFileSync(target, "utf8");
const needle = "const ambiguous = [...others].some((rel) => rel !== key.declaredIn);";
if (!text.includes(needle)) throw new Error("the ambiguity branch moved — this fixture is stale");
// The replacement KEEPS the const: forcing the branch false is the whole mutation, and appending
// a bare assignment after a const would not even load.
fs.writeFileSync(target, text.replace(needle, "const ambiguous = false;"));
' "$CHECK"
check
if grep -q "zz_dead_dir" "$tmp/out"; then
  err "4. INVERSE receiver qualification — the dead key is STILL red with qualification off, so a name-only rule would have found it too and the mechanism is not load-bearing"
else
  note "ok 4. INVERSE — with qualification off, LocalPaths.zzDeadDir makes the dead key read as consumed; the qualification is load-bearing"
fi

# ── 5. the allowlist is a disposition, and only with a dated reason ───────────────────────────
write_fixture dead dead - -
reset_check
allow '["zz_dead_dir", "2026-09-17: fixture — deliberately inert"], ["zzDeadKnob", "2026-09-17: fixture — deliberately inert"],'
check
must_pass "5a. two dated, reasoned allowlist entries dispose of both dead keys"

reset_check
allow '["zz_dead_dir", "  "], ["zzDeadKnob", "2026-09-17: fixture"],'
check
must_fail "5b. an allowlist entry with a blank reason is a hard error" "absence wearing a label"

reset_check
allow '["zz_dead_dir", "2026-09-17: fixture"], ["zzDeadKnob", "2026-09-17: fixture"], ["control_port", "2026-09-17: fixture — but control_port IS wired"],'
check
must_fail "5c. an allowlist entry naming a key that IS acted on fails as stale" "IS acted on"

# ── 6. a stale non-consumption entry is a hard error ─────────────────────────────────────────
write_fixture - - - -
reset_check
bun -e '
const fs = require("fs");
const target = process.argv[1];
const text = fs.readFileSync(target, "utf8");
const needle = "let NON_CONSUMPTION: [string, string][] = [\n";
if (!text.includes(needle)) throw new Error("the NON_CONSUMPTION declaration moved");
fs.writeFileSync(target, text.replace(needle, needle + "  [\"gateway/zzfix/src/main/kotlin/splice/zzfix/Gone.kt\", \"2026-09-17: fixture — names nothing\"],\n"));
' "$CHECK"
check
must_fail "6. a non-consumption entry naming a file that is gone is a hard error" "stale exclusion"

# ── 7. a schema class the tree no longer declares is a hard error ─────────────────────────────
write_fixture - - rename -
reset_check
check
must_fail "7. a schema class the search cannot find is a hard error, not a shorter list" "part of the denominator"

# ── 8. a @SerialName the parser cannot attribute means the key list is untrustworthy ──────────
write_fixture - - - orphan
reset_check
check
must_fail "8. a @SerialName belonging to no parameter must REFUSE, not shorten the list" "the parser dropped a parameter"

# ── 9. the BORING case: a lost denominator must REFUSE, not report a clean tree (s24) ─────────
write_fixture - - - -
reset_check
bun -e '
const fs = require("fs");
const target = process.argv[1];
const text = fs.readFileSync(target, "utf8");
const patched = text.replace(/^const SRC_GLOBS = .*$/m, "const SRC_GLOBS = [\"gateway/*/src/nowhere\"];");
if (patched === text) throw new Error("SRC_GLOBS assignment not found");
fs.writeFileSync(target, patched);
' "$CHECK"
check
must_fail "9. a source glob that matches nothing must REFUSE, not pass vacuously" "vacuously"

# ── 10. LIVE SMOKE — SHAPE ONLY, never a count (see WHY HERMETIC at the top) ──────────────────
# The live tree is the wall's SUBJECT. What is asserted: the checker RUNS against it, exits 0 or 1
# (never 2, which is "you asked the instrument something it cannot answer"), reports a real
# denominator, and every finding it does have is attributed to a file:line and names its key. The
# NUMBER of findings is deliberately not asserted: it is exactly what V4-96 and V4-109 change.
# The SHIPPED checker, not the retargeted copy: the gate leg's own artifact against its own tree.
bun "$ROOT/checks/schema-keys-consumed.ts" "$ROOT" >"$tmp/live" 2>&1
live_rc=$?
live_keys="$(grep -oE '[0-9]+ config key\(s\) examined' "$tmp/live" | grep -oE '^[0-9]+')"
if [ "$live_rc" -ne 0 ] && [ "$live_rc" -ne 1 ]; then
  err "10. LIVE SMOKE — exit $live_rc means the checker could not answer at all (2 = broken instrument): $(head -4 "$tmp/live" | tr '\n' ' ')"
elif [ -z "${live_keys:-}" ] || [ "$live_keys" -lt 50 ]; then
  err "10. LIVE SMOKE — the tree yielded ${live_keys:-no} config keys; the checker is not pointed at the real schema"
elif grep -q '^  x ' "$tmp/live" &&
  ! grep -qE '^  x [^ ]+\.kt:[0-9]+: .*`[a-zA-Z_][a-zA-Z0-9_]*`' "$tmp/live"; then
  err "10. LIVE SMOKE — a finding is not attributed to a file:line and a named key: $(grep -m1 '^  x ' "$tmp/live" | cut -c1-200)"
else
  found="$(grep -c '^  x ' "$tmp/live")"
  note "ok 10. LIVE SMOKE: runs over $live_keys real keys, exit $live_rc, $found finding(s), each named by file:line and key (count NOT asserted)"
fi

if [ "$tree_state" != "$(cd "$ROOT/gateway" && ls -1A)" ]; then
  err "the harness changed gateway/ — everything here must land in mktemp"
fi

if [ "$fail" -eq 0 ]; then
  note "schema-keys-consumed selftest: hermetic control green over a synthetic fixture, 8 mutation arms red for their stated reasons, 2 inverse arms prove the echo surface and the receiver qualification are load-bearing, 1 allowlist arm green, live tree asserted by SHAPE only, gateway/ untouched"
fi
exit "$fail"
