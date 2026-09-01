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
run "catalog metadata sync" python3 checks/catalog-metadata-sync.py
run "catalog metadata selftest" bash checks/catalog-metadata-selftest.sh
run "gradle clean check" bash -c 'cd gateway && ./gradlew clean check'
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
run "campaign ledger floor" python3 checks/campaign-ledger-floor.py --check
run "campaign ledger floor selftest" bash checks/campaign-ledger-floor-selftest.sh
# Eleven frozen request/response scenarios grade the built Kotlin gateway byte-for-byte against the
# captured Node oracle. Keeping the replay as a package script without a gate leg left the parity
# claim entirely opt-in: every other check could pass while none of these fixtures ran.
run "oracle replay" npm run --silent oracle:replay
# Local, no quota: skip / fake-token / FATAL-mgmt-key against a loopback control+head.
# The live `e2e:heads` lane is billed and stays out of this ladder.
run "heads-e2e selftest" bash checks/e2e/heads-e2e-selftest.sh
run "config guard"   bash checks/config-guard.sh
run "config-guard selftest" bash checks/config-guard-selftest.sh
# DR-140: the DR-65 wall. Every throwable rendered into text from a source that touches files or
# names credential/state types is routed through SafeFailureText.render or carries a dated,
# reasoned exemption; an undispositioned sink fails BY NAME. DR-73 swept this class by hand and
# its denominator was files rather than sinks, so UsageRingFile's write half kept a raw render for
# another eight days (DR-139) — a hand sweep closes the instance, a checker closes the class.
run "safe-failure-render" python3 checks/config/safe-failure-render.py check .
run "safe-failure-render selftest" bash checks/safe-failure-render-selftest.sh
run "pr title"       bash checks/pr-title.sh
# Two layers, deliberately. The generator makes the hazards inexpressible (#924); the canary
# selftest is defence in depth over its OUTPUT, so a bug in the generator itself still gets caught.
run "secret-scan allowlist generated" python3 checks/gen-secret-scan-allow.py --check
run "secret-scan allowlist" bash checks/secret-scan-allow-selftest.sh
run "webui lint"     npm run lint -w webui
run "webui tests"    npm test -w webui
webui_dist_before="$(mktemp)"
if ! cp webui/dist/index.html "$webui_dist_before"; then
  echo "  ✗ webui dist snapshot (committed bundle missing)"
  fail=1
fi
run "webui build"    npm run build -w webui
run "webui dist"     cmp -s "$webui_dist_before" webui/dist/index.html
rm -f "$webui_dist_before"
run "OSS readiness"  bash checks/oss/run.sh

echo
if [ "$fail" -eq 0 ]; then
  echo "GATE: PASS"
else
  echo "GATE: FAIL"
fi
exit "$fail"
