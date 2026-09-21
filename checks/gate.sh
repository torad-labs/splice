#!/usr/bin/env bash
# checks/gate.sh — the ONE local gate. Runs the SAME checks CI runs and prints a single, unmissable
# GATE: PASS / GATE: FAIL derived from the REAL exit codes.
#
# Never trust a filtered `gradle | grep | tail` exit — a wrapped pipeline's status masked BUILD
# FAILED twice this session (the monitor grepping raw output caught the truth both times). This
# script captures each command's own exit code and refuses to report PASS unless every one is 0.
#
# Run: `npm run gate`  or  `bash checks/gate.sh`
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT" || exit 1

# Splice requires JDK 21 (module law + toolchain). Resolve JAVA_HOME from the running VM's own
# java.home property rather than OS/package-manager-specific paths. This works for Linux and macOS
# launchers, including /usr/bin/java, without non-portable readlink flags.

java_major() {
  "$1" -version 2>&1 | awk -F'"' '/ version "/ { print $2; exit }' | cut -d. -f1
}

path_java_major() { # major version reported by `java` on PATH, or empty if there is none
  command -v java >/dev/null 2>&1 || return 0
  java_major "$(command -v java)"
}

path_java_home() {
  java -XshowSettings:properties -version 2>&1 |
    awk -F' = ' '/^[[:space:]]*java.home = / { print $2; exit }'
}

if [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/java" ] &&
  [ "$(java_major "${JAVA_HOME}/bin/java")" = "21" ]; then
  : # caller-provided JAVA_HOME wins
elif [ "$(path_java_major)" = "21" ]; then
  JAVA_HOME="$(path_java_home)"
else
  echo "GATE: FAIL — JDK 21 required, none found (checked \$JAVA_HOME and PATH java)." >&2
  echo "  Install a JDK 21 and re-run, e.g.:" >&2
  echo "    macOS:  brew install openjdk@21" >&2
  echo "    Debian/Ubuntu: sudo apt install openjdk-21-jdk" >&2
  echo "    or download from https://adoptium.net/temurin/releases/?version=21" >&2
  exit 1
fi
export JAVA_HOME

if [ "${1:-}" = "--java-home-only" ]; then
  printf '%s\n' "$JAVA_HOME"
  exit 0
fi

fail=0
run() { # run <label> <cmd...> — runs the command, records its REAL exit, never masks it
  echo "── $1 ──"
  if "${@:2}"; then
    echo "  ✓ $1"
  else
    echo "  ✗ $1 (exit $?)"
    fail=1
  fi
}

echo "══ splice gate ══  (JAVA_HOME=$JAVA_HOME)"
# Dependabot edits the catalog but cannot regenerate verification metadata, so gradle bumps used
# to arrive red six minutes into the gradle leg (#91). State the same fact statically, first and
# in under a second, with the regeneration remedy attached. Selftest guards the checker itself.
run "catalog metadata sync" bun checks/catalog-metadata-sync.ts
run "catalog metadata selftest" bash checks/catalog-metadata-selftest.sh
# --no-build-cache is CORRECTNESS here, not paranoia. Kotlin's compile-avoidance ABI snapshot does
# not track `internal` members, so changing an `internal fun interface`'s method signature does not
# invalidate the dependent test-compile task. Measured 2026-09-16: after renaming PulseScheduler's
# parameter type, `clean check` restored BOTH :app:compileKotlin and :app:compileTestKotlin
# FROM-CACHE from different source states and SpinnerTest died with AbstractMethodError against a
# signature the test had never been compiled for. The same hole can hand back a false GREEN. A gate
# of record must never measure a mixture of two source states; the cache is the one input that can
# make it do so, so the gate of record does not use it.
# THROUGH THE SLOT, because the gate of record was the last caller that was not (2026-09-18). Every
# seat routes gradle through the slot (`bun tools/gate slot`) since the 2026-09-17 fix wave, and this line — the one
# run whose verdict is the one that counts — still went straight to ./gradlew. Two seats' gradles in
# one project dir clobber build/ outputs and hand each other false reds, and a gate that can be
# false-red by a neighbour's timing is not a gate of record. Measured here the same day: a direct
# invocation died with NoClassDefFoundError against four live daemons, and the identical tasks
# through the lock came back clean. The label is what a waiting seat reads out of the holder file.
run "gradle clean check" bun tools/gate run
run "ast-grep walls" npm run --silent gate:rules
# The walls leg above proves the routed rules pass; it cannot prove they are ALL routed. .rules/kotlin
# sat in the tree unreferenced for a month reporting zero findings, because ast-grep never errors on a
# rule directory nobody named. This leg is that missing half — completeness, not conformance.
run "rule routing"   bash checks/rule-routing.sh
# DR-132: and the canary over that leg. rule-routing.sh shipped without a selftest, which is how its
# forward direction ran fail-OPEN on flow-style rules — a dormant pack reported PASS, reproducing
# the scar the leg above exists to prevent, inside the leg itself. Same pairing as config-guard.
run "rule-routing selftest" bash checks/rule-routing-selftest.sh
# checks/concentration.py — the decomposition campaign's own oracle — was itself the thing the leg
# above exists to catch: referenced by nothing but its ledger, so this gate printed PASS while
# saying nothing about concentration and every ratio in the campaign was advisory. Wired 2026-08-18
# as a RATCHET, not as `--max-ratio 1.8`: the tree-wide gate is red on 42 files, and a leg that
# lands red is a leg that gets weakened within a day. The counts may not rise; the debt prints.
run "concentration"  npm run --silent gate:concentration
# Same defence-in-depth idiom as the catalog and secret-scan selftests above and below: the leg
# guards the tree, this canary guards the LEG. It shipped without one, and that is precisely why a
# routing guard defeated by a single `#` survived into the branch — its red-proofs were hand-run
# transcripts in a ledger note, so nothing re-ran them. Both of those bypasses are fixtures now.
run "concentration selftest" bash checks/concentration-selftest.sh
run "hook tests"     npm run --silent test:hooks
# Campaign enforcement, BLOCKING half only (C1/C2/C3/C5/C6/C7/C9): a wall that lies, a status that
# lies, a fence collision, or a broken law are never acceptable. The ADVISORY half (C4 unwalled /
# C8 unlawed) is the standing worklist and is reported without failing — see
# `npm run gate:campaign:strict`. Split 2026-07-26: lumping them together kept the whole check out
# of this ladder, which meant nothing ran it at all.
run "campaign walls"  npm run --silent gate:campaign
run "campaign selftest" npm run --silent gate:campaign:selftest
# DR-181: the walls above guard the SOURCE TREE a campaign describes; nothing guarded the campaign
# MEMORY itself. On 2026-09-01 drift-repair.toml lost 164 rows and 2604 lines, was committed and
# pushed, and this ladder passed 13 of 13 — because no leg read the file. A green gate said nothing
# about the one artifact whose entire purpose is to outlive the session that wrote it.
run "campaign ledger floor" bun checks/campaign-ledger-floor.ts --check
run "campaign ledger floor selftest" bash checks/campaign-ledger-floor-selftest.sh
# DR-184: and the CLI's own suite, which nothing ran. The leg two lines up named "campaign
# selftest" is campaign_wall_gate's, not the ledger CLI's — so every arm guarding the instrument
# that owns campaign memory fired only when a session remembered to type it.
run "campaign CLI selftest" bash checks/campaign-cli-selftest.sh
run "campaign CLI selftest canary" bash checks/campaign-cli-selftest-canary.sh
# Eleven frozen request/response scenarios grade the built Kotlin gateway byte-for-byte against the
# captured Node oracle. Keeping the replay as a package script without a gate leg left the parity
# claim entirely opt-in: every other check could pass while none of these fixtures ran.
run "oracle replay" npm run --silent oracle:replay
# Local, no quota: skip / fake-token / FATAL-mgmt-key against a loopback control+head.
# The live `e2e:heads` lane is billed and stays out of this ladder.
run "heads-e2e selftest" bash checks/e2e/heads-e2e-selftest.sh
# Budget/oracle canaries are local; the explicit code-mode A/B lane consumes subscription quota.
run "code-mode probe selftest" bun checks/e2e/code_mode_probe.ts --selftest
run "code-mode startup receipt selftest" bun checks/e2e/code_mode_compare_test.ts
run "code-mode guidance selftest" bun checks/e2e/code_mode_guidance.ts --selftest
run "code-mode mock selftest" bun checks/e2e/code_mode_mock.ts --selftest
# Exercise the fat JAR built by clean check, with synthetic auth and loopback-only tools.
run "code-mode packaged mock" bun checks/e2e/code_mode_mock.ts \
  --artifact app/build/libs/app-all.jar \
  --receipt "checks/e2e/receipts/code-mode-mock-$(date -u +%Y%m%dT%H%M%S)-$$.json"
run "config guard"   bash checks/config-guard.sh
run "config-guard selftest" bash checks/config-guard-selftest.sh
# DR-140: the DR-65 wall. Every throwable rendered into text from a source that touches files or
# names credential/state types is routed through SafeFailureText.render or carries a dated,
# reasoned exemption; an undispositioned sink fails BY NAME. DR-73 swept this class by hand and
# its denominator was files rather than sinks, so UsageRingFile's write half kept a raw render for
# another eight days (DR-139) — a hand sweep closes the instance, a checker closes the class.
run "safe-failure-render" bun checks/config/safe-failure-render.ts check .
run "safe-failure-render selftest" bash checks/safe-failure-render-selftest.sh
# V4-29: a non-null default on a SHARED dialect's quirks is inherited by every provider that does
# not override it, so a vendor fact parked there rides to other vendors' endpoints. Two defects
# shipped in v0.3.2 that way: codex's internal lite marker went to any api-key responses head
# pinned to a gpt-6 id (V4-28), and a bare `mini` effort clamp caught google/gemini-2.5-pro by
# substring (V4-29). Fields are enumerated by parsing every shared *Quirks primary constructor on
# disk, so a field added tomorrow is in scope without editing the checker; there is no allowlist.
run "shared-quirks no vendor defaults" bun checks/config/shared-quirks-no-vendor-defaults.ts check .
run "shared-quirks selftest" bun checks/config/shared-quirks-no-vendor-defaults.ts --selftest
# V4-44: the OTHER half of the same surface. The check above keeps a VENDOR FACT out of a shared
# dialect default; this one keeps a quirk KEY from drifting away from its documentation. The
# denominator is parsed from QuirksConfig.kt — the keys an operator may write under
# [providers.X.quirks], nested tool_surface included — so a key added tomorrow is in scope with no
# edit to the checker, and two guards refuse a vacuous pass (a parse yielding no keys, and a parsed
# @SerialName count that disagrees with the file). Every key must be documented in
# config/splice.example.toml or the `splice add PROFILE` emitter, or retired with a written reason;
# absence is not a disposition and fails BY NAME. The selftest proves it red on a synthetic key
# appended to a temp copy of the source, on a retirement carrying no reason, and on a key named
# only in a runtime doctor map — the map that would otherwise make this wall green for free.
run "quirks keys documented" bun checks/config/quirks-keys-documented.ts check .
run "quirks keys selftest" bun checks/config/quirks-keys-documented.ts --selftest
# ARCH-AUDIT 2026-09-17 (#924, walls V4-87..V4-98): each violation class the architecture audit
# found became an instrument BEFORE its instances were fixed, and every instrument is red-green
# proven by a selftest that runs beside it. The `check` legs are red BY NAME until the sibling fix
# rows (V4-99..V4-114) land; a red here is the inventory, not a flake. Ratchet legs (--ratchet)
# carry a checked-in baseline and fail on GROWTH and on a stale entry, so they are green today and
# stay green only while nobody adds an offender. The ast-grep walls of the same wave need no leg
# here — sgconfig.yml routes them into `gate:rules` above and into the PreToolUse hook from one
# source (same-checker-twice).
run "knob keys documented" bun checks/config/knob-keys-documented.ts check .
run "knob keys selftest" bash checks/knob-keys-documented-selftest.sh
run "env vars documented" bun checks/config/env-vars-documented.ts check .
run "env vars selftest" bash checks/env-vars-documented-selftest.sh
run "const single source" bun checks/const-single-source.ts --ratchet
run "const single source selftest" bash checks/const-single-source-selftest.sh
run "silent constants" bun checks/silent-constants.ts --ratchet
run "silent constants selftest" bash checks/silent-constants-selftest.sh
run "role registry" bun checks/role-registry.ts check .
run "role registry selftest" bash checks/role-registry-selftest.sh
run "model catalogs single source" bun checks/model-catalogs-single-source.ts check .
run "model catalogs selftest" bash checks/model-catalogs-single-source-selftest.sh
run "autocloseable closed" bun checks/autocloseable-closed.ts check
run "autocloseable closed selftest" bash checks/autocloseable-closed-selftest.sh
# Operator rule 2026-09-18: this repo has no Python — tooling is bun/TypeScript. The rule drifted
# every time it was only prose, because nothing failed when a session added another .py. It is a
# wall now; tools/gate/config/python-burndown.json is the dated debt and may only shrink.
run "no python" bun tools/gate no-python
# SAME CHECKER, TWICE. The leg above is the commit-time half and it fails LATE: on 2026-09-18 a
# webui commit added a fresh python3 subprocess and the branch stayed green for hours, until a
# builder converting an unrelated checker happened to run the wall. The PreToolUse guard
# (.claude/settings.json, Write|Edit|MultiEdit) is `bun tools/gate no-python --guard`, the same
# library as the leg above rather than a reimplementation. The test arms below prove both halves —
# every census RED on its mutation, and the guard still refusing a synthetic event — because a write
# guard that silently stopped refusing would announce itself only as Python reappearing.
run "no python selftest" bun test tools/gate/test/no-python.test.ts
run "public surface" bun checks/public-surface.ts --ratchet
run "public surface selftest" bash checks/public-surface-selftest.sh
run "constructor width" bun checks/constructor-width.ts --ratchet
run "constructor width selftest" bash checks/constructor-width-selftest.sh
run "schema keys consumed" bun checks/schema-keys-consumed.ts
run "schema keys consumed selftest" bash checks/schema-keys-consumed-selftest.sh
# V4-68: a @Test method JUnit never DISCOVERED is a green suite with a hole in it — no failure,
# no skip, no warning, and the XML just looks one short. Found 2026-09-16: HeadServerCapacityTest
# declared four @Test methods and its XML reported three, because the fourth's body ended in
# held.await() inside `= runBlocking { }`, so the method returned a String. The denominator is
# parsed from the SOURCE (@Test/@ParameterizedTest per class, member depth only) and the
# observation is the JUnit XML — never the SHAPE of the source, because a syntax-based check
# accuses all four of those methods and kotlinx's TestResult is a Unit typealias, so it is wrong
# in both directions. Two guards refuse a vacuous pass: a parse that finds no test class, and an
# empty results tree.
#
# POSITION IS LOAD-BEARING: these two legs MUST stay after the `gradle clean check` leg above,
# because that leg PRODUCES the XML they read, and they must read it in the same pass. The results
# directory is a shared, mutually-destructive observation — any scoped run of one test class in a
# module wipes every other class's XML in that module, which is measured, not hypothetical (a
# scoped :daemon-head:test run by another seat during this row's own development deleted 62 of the 63
# XML files mid-run). Moved above the gradle leg this wall would compare fresh source against
# absent or stale results and red the whole tree for the wrong reason.
run "tests are discovered" bun checks/config/tests-are-discovered.ts check .
run "tests are discovered selftest" bun checks/config/tests-are-discovered.ts --selftest
# CW-9: stty and raw-mode entry exist only inside TerminalMode.raw. A widget that
# invokes stty on its own can leave the tty raw after SIGINT. Selftest is in-process
# (temp tree, RED then GREEN) so a green live wall cannot hide a dead selftest.
run "terminal-restore bracketed" bun checks/config/terminal-restore-bracketed.ts check .
run "terminal-restore selftest" bun checks/config/terminal-restore-bracketed.ts --selftest
# V4-30: the conventional-type vocabulary lives once, in tools/gate/src/lib/conventional.ts, which
# `gate title` (the leg after next) enforces.
# CONTRIBUTING.md and AGENTS.md had both restated it and both had drifted to include `release` and
# `codex`, types the org gate rejects — the exact divergence conventional.ts's own header describes as
# fixed history. Denominator is `git ls-files`, so an untracked sidecar or editor backup cannot red
# the gate; a second copy must be tracked to reach main.
run "one conventional type list" bun checks/config/one-conventional-type-list.ts check .
run "one conventional type list selftest" bash checks/one-conventional-type-list-selftest.sh
run "pr title"       bun tools/gate title
# Two layers, deliberately. The generator makes the hazards inexpressible (#924); the canary
# selftest is defence in depth over its OUTPUT, so a bug in the generator itself still gets caught.
run "secret-scan allowlist generated" bun checks/gen-secret-scan-allow.ts --check
run "secret-scan allowlist" bash checks/secret-scan-allow-selftest.sh
run "console lint"   npm run lint -w console
run "console tests"  npm test -w console
# PR 4: the "webui build" and "webui dist" legs are RETIRED with the committed bundle. The bundle is
# :console:bundle's output, built inside the gradle tier above (`clean check` reaches :console:check),
# and :app's shadowJar packages that output through the task's provider — producer wiring, not a
# byte comparison against a file in the tree.
run "OSS readiness"  bash checks/oss/run.sh

echo
if [ "$fail" -eq 0 ]; then
  echo "GATE: PASS"
else
  echo "GATE: FAIL"
fi
exit "$fail"
