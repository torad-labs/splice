#!/usr/bin/env bash
# checks/campaign-cli-selftest-canary.sh — the canary for checks/campaign-cli-selftest.sh.
#
# gate.sh's own words, a few lines above where that leg is wired: "the leg guards the tree, this
# canary guards the LEG. It shipped without one, and that is precisely why a routing guard defeated
# by a single `#` survived into the branch." A leg that cannot fail is a leg that reports PASS
# forever, which is the failure DR-184 exists to end — so it ships with its own red-proofs.
#
# Method: fixture ROOTs under mktemp -d, each with a checks/ and a .dev/campaigns/, and a STUB
# manifest.ts whose behaviour is chosen by the ledger's filename. The subject under test is the
# LEG's logic — run the suite once, then validate and round-trip a note through every ledger on a
# copy, judge the diff, assert the real ledgers byte-identical — never the CLI, which has its own
# suite (that is the whole point of the leg). Nothing here touches the real repo: the arms read
# $ROOT only to copy the leg out of it, and the real ledgers' sha256s are compared before and after.
#
# V4-143 (2026-09-18): the leg moved to the bun CLI and gained a round-trip half, so the stub moved
# to bun and the canary gained the reds that half can be fooled by — a note that lands twice, a
# note that lands nowhere, a writer that rewrites instead of appending, and a ledger with no row.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LEG="$ROOT/checks/campaign-cli-selftest.sh"

before_real="$(sha256sum "$ROOT"/.dev/campaigns/*.toml | sha256sum | cut -d' ' -f1)"

fixtures=""
cleanup() {
  for dir in $fixtures; do rm -rf "$dir"; done
}
trap cleanup EXIT

# A fixture root: checks/<the real leg> + .dev/campaigns/<stub manifest.ts> + the named ledgers.
make_root() {
  local root
  root="$(mktemp -d -t campaign-cli-canary-XXXXXX)"
  fixtures="$fixtures $root"
  mkdir -p "$root/checks" "$root/.dev/campaigns"
  cp "$LEG" "$root/checks/campaign-cli-selftest.sh"
  cat >"$root/.dev/campaigns/manifest.ts" <<'STUB'
// Stub: the canary is testing the LEG, not the CLI. Behaviour is keyed on the ledger's name so a
// fixture tree can spell each way the CLI could fool the leg.
import { appendFileSync, readFileSync, writeFileSync } from "node:fs";
import { basename, join } from "node:path";
const [ledger = "", verb = "", ...rest] = Bun.argv.slice(2);
const name = basename(ledger);
const is = (tag: string): boolean => name.includes(tag);
if (verb === "selftest") {
  if (is("suite-broken")) { console.error("stub: pretending the CLI suite failed"); process.exit(1); }
  console.log("stub: selftest OK");
  process.exit(0);
}
if (verb === "validate") {
  if (is("invalid")) { console.error("stub: pretending the ledger does not validate"); process.exit(1); }
  console.log(`${ledger}: valid`);
  process.exit(0);
}
if (verb === "list") {
  if (!is("norows")) console.log("X1     p  todo      a stub row");
  process.exit(0);
}
if (verb === "note") {
  const text = rest[1] ?? "";
  // The CLI writing the REAL ledger instead of the copy it was handed: the leg runs from ROOT.
  if (is("mutates")) appendFileSync(join(process.cwd(), ".dev/campaigns", name), "# the suite wrote the real ledger\n");
  if (is("noop")) process.exit(0);
  if (is("rewrites")) {
    // A serialiser-shaped writer: the note lands, and a line it had no business touching goes.
    const lines = readFileSync(ledger, "utf8").split("\n").slice(1);
    writeFileSync(ledger, `${lines.join("\n")}# ${text}\n`);
    process.exit(0);
  }
  appendFileSync(ledger, `# ${text}\n`);
  if (is("twolines")) appendFileSync(ledger, `# ${text}, twice\n`);
  process.exit(0);
}
console.error(`stub: unexpected verb ${verb}`);
process.exit(2);
STUB
  for name in "$@"; do
    printf '[campaign]\nname = "%s"\n' "$name" >"$root/.dev/campaigns/$name.toml"
  done
  echo "$root"
}

pass=0
fail=0
arm() { # arm <name> <expect: RED|GREEN> <root> [diagnosis substring]
  local name="$1" expect="$2" root="$3" want="${4:-}" out status
  out="$(bash "$root/checks/campaign-cli-selftest.sh" 2>&1)"
  status=$?
  local got; got=$([ $status -eq 0 ] && echo GREEN || echo RED)
  if [ "$got" != "$expect" ]; then
    echo "FAIL  $name — expected $expect, got $got"
    printf '%s\n' "$out" | sed 's/^/      /'
    fail=$((fail + 1))
    return
  fi
  if [ -n "$want" ] && ! printf '%s' "$out" | grep -qF "$want"; then
    echo "FAIL  $name — $expect, but undiagnosed: wanted \"$want\""
    printf '%s\n' "$out" | sed 's/^/      /'
    fail=$((fail + 1))
    return
  fi
  echo "ok    $name ($expect)"
  pass=$((pass + 1))
}

# RED 1 — an empty denominator. A leg that skips when there is nothing to measure passes forever
# the day the ledgers move; this is the boring case that gets waved through.
empty_root="$(make_root)"
arm "no ledger on disk is a hard failure" RED "$empty_root" "no ledger under .dev/campaigns/"

# RED 2 — the CLI's own suite fails. Half one of the leg.
suite_root="$(make_root aaa-suite-broken zzz-healthy)"
arm "a failing CLI suite fails the leg" RED "$suite_root" "the CLI's own selftest FAILED"

# RED 3 — a ledger that does not validate, and the leg must NAME it. Placed LAST alphabetically on
# purpose: a leg that checks only the first ledger would report green here, so this arm is also the
# proof that the sweep's denominator is every ledger, not ledgers[0].
tail_fail_root="$(make_root aaa-healthy zzz-invalid)"
arm "an invalid NON-first ledger fails the leg" RED "$tail_fail_root" "zzz-invalid.toml"

# RED 4 — the same failure at the head of the list, so neither end is a blind spot.
head_fail_root="$(make_root aaa-invalid zzz-healthy)"
arm "an invalid first ledger fails the leg" RED "$head_fail_root" "aaa-invalid.toml"

# RED 5 — a CLI that writes the REAL ledger instead of the copy. The one regression that would be
# worse than no leg at all, and invisible to an exit-code-only check: the stub exits 0.
mutate_root="$(make_root aaa-healthy zzz-mutates)"
arm "a CLI that writes the real ledger fails the leg" RED "$mutate_root" "MUTATED"

# RED 6 — a note that lands twice. Exit 0, the note is there, and the ledger has grown by two.
twice_root="$(make_root aaa-healthy zzz-twolines)"
arm "a note that adds two lines fails the leg" RED "$twice_root" "EXACTLY ONE"

# RED 7 — a note that lands nowhere. Exit 0 and nothing written: the silent-success shape.
noop_root="$(make_root aaa-healthy zzz-noop)"
arm "a note that adds nothing fails the leg" RED "$noop_root" "EXACTLY ONE"

# RED 8 — a writer that rewrites instead of appending. The note lands, and the line count even
# balances, but a line was removed: the serialiser shape that would still parse.
rewrite_root="$(make_root aaa-healthy zzz-rewrites)"
arm "a writer that rewrites the file fails the leg" RED "$rewrite_root" "EXACTLY ONE"

# RED 9 — a ledger with no row to round-trip through. Skipping it would shrink the denominator.
norows_root="$(make_root aaa-healthy zzz-norows)"
arm "a ledger with no row fails the leg by name" RED "$norows_root" "zzz-norows.toml has no row"

# GREEN — the control the nine reds are measured against.
healthy_root="$(make_root aaa-healthy mmm-healthy zzz-healthy)"
arm "a healthy tree passes" GREEN "$healthy_root" "3 ledger(s) validated, each round-tripped one line, all byte-identical"

after_real="$(sha256sum "$ROOT"/.dev/campaigns/*.toml | sha256sum | cut -d' ' -f1)"
if [ "$before_real" != "$after_real" ]; then
  echo "FAIL  the canary itself touched the REAL ledgers"
  fail=$((fail + 1))
else
  echo "ok    real ledgers byte-identical ($before_real)"
  pass=$((pass + 1))
fi

echo "campaign-cli-selftest-canary: $pass passed, $fail failed"
[ "$fail" -eq 0 ]
