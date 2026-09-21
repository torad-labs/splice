#!/usr/bin/env bun
// scan — run this campaign's two structural instruments over a path, and refuse to
// report clean for a path they did not actually read.
//
// WHY. Both tools pass vacuously on a target that is not there:
//
//   $ ast-grep scan -c sgconfig.yml console/src/does/not/exist
//   ERROR: console/src/does/not/exist: No such file or directory (os error 2)
//   $ echo $?
//   0
//
//   $ node ~/.claude/skills/impeccable/scripts/detect.mjs console/src/does/not/exist
//   Warning: cannot access console/src/does/not/exist
//   $ echo $?
//   0
//
// ast-grep prints the word ERROR and exits 0. An empty directory gets the same silent
// zero from both. Every look-bearing verify line in this campaign ends with those two
// commands, so a renamed directory, a deleted one, or a typo turns a row's last two legs
// into no-ops that read as green — and the builder records a receipt saying they passed.
//
// An audit of the ledger on 2026-09-18 found 238 of 244 scan paths live and the other six
// belonging to a row that has not been built yet, so this had not bitten. It was going to.
// Campaign law 23: a check must be able to say it did not run, and DID NOT RUN is a
// failure. This wrapper is that sentence for these two tools.
//
// Found by shape rather than by search: the splice campaign audited thirteen of its own
// instruments for "does it refuse an empty TREE" and found none missing, then this
// campaign's exit gate turned out to pass on an empty FILTER, and that shape — the same
// failure with a different denominator — sent each of us back to the other's question.
//
// M1-76 DISPOSITION — the two ways a check can be decorative, answered for this file.
//   SHAPE ONE, does every FAIL reach the exit code? YES, AND IT DID BEFORE. Both branches that
//     print a failure increment `failed`, and `process.exit(failed ? 1 : 0)` is the only exit. The
//     refusals go out through their own `process.exit(2)`. Nothing here prints red and returns 0.
//   SHAPE TWO, if every tool read nothing, what would it print? IT PRINTED `0 file(s) read` AND
//     EXITED 0, and the guard written to stop that could not fire. refuse() covers the case the
//     tree can see beforehand and always did; the REAR guard — the tool's own words, for a path
//     that goes away mid-run — tested `r.out` for a message this file's own header shows arriving
//     on STDERR, and sh() used execFileSync, which returns stdout only. Fixed: spawnSync, and the
//     predicate named (readNothing) so the selftest can fire it. Proven 2026-09-18 by disabling
//     refuse() and running both versions at a path that does not exist: spawnSync exits 1 with
//     `ast-grep: read nothing` and `detect: read nothing`; execFileSync exits 0 printing
//     `scan: 1 path(s), 0 file(s) read`.
//   NOT FIXED HERE, and named rather than left silent: this file has no isMain guard at all, so
//     importing it would run a real scan. Nothing imports it today (it is a leg, run as a program).
//     An absent guard is a different defect from the broken one law-check.mjs carried, and this row
//     is not the place to add one on speculation.
//
//   bun console/tools scan <path...>     # exits 2 if a path is missing or empty
//   bun console/tools scan --selftest

import { run } from '../lib/proc.ts';
import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..', '..', '..');
const DETECT = path.join(process.env.HOME, '.claude/skills/impeccable/scripts/detect.mjs');
// What either tool can actually read. A directory of nothing but PNGs is as unscanned as
// a directory that is not there, and must report the same way.
const SCANNABLE = new Set(['.ts', '.tsx', '.js', '.jsx', '.mjs', '.cjs', '.css', '.html', '.vue', '.svelte']);

/**
 * Both streams, on every outcome. spawnSync AND NOT execFileSync (M1-76).
 *
 * execFileSync RETURNS STDOUT ONLY. stderr reaches the caller solely through the throw, and the
 * throw only happens on a non-zero exit — so on a PASSING command `out` held stdout and nothing
 * else. Everything below this line that reads `r.out` was therefore blind on exactly the runs it
 * was written for: the `vacuous` guard tests r.out for "No such file or directory", the header of
 * this file says in its own words that ast-grep "prints the word ERROR and exits 0", and the third
 * line of that transcript shows the message going to STDERR. The guard was written against a
 * variable that could not contain the string it looks for.
 *
 * Measured 2026-09-18 before the change: `sh('bash', ['-c', 'echo "No such file or directory" >&2;
 * exit 0'])` returned `{ code: 0, out: '' }` and the regex did not fire; the same command through
 * spawnSync returns that text. Third file this campaign has caught with the same defect
 * (wire-check's selftest runner, exit-gate's sh) — it is an idiom, not an accident.
 */
function sh(cmd, args) {
  return run([cmd, ...args], ROOT);
}

/** How many files at this path either tool could read. 0 and "absent" are one answer. */
function scannableCount(p) {
  const abs = path.isAbsolute(p) ? p : path.join(ROOT, p);
  if (!fs.existsSync(abs)) return null;                       // null = not there at all
  const st = fs.statSync(abs);
  if (st.isFile()) return SCANNABLE.has(path.extname(abs)) ? 1 : 0;
  let n = 0;
  const walk = (d) => {
    for (const e of fs.readdirSync(d, { withFileTypes: true })) {
      if (e.name === 'node_modules' || e.name.startsWith('.')) continue;
      const f = path.join(d, e.name);
      if (e.isDirectory()) walk(f);
      else if (SCANNABLE.has(path.extname(e.name))) n++;
    }
  };
  walk(abs);
  return n;
}

/**
 * Did the tool say, in words, that it read nothing? The other half of law 23 for these two: refuse()
 * asks the QUESTION BEFOREHAND from the tree, this reads the tool's own ANSWER afterwards. Both are
 * needed — a path can vanish between the two, and a tool can decide it cannot read something the
 * filesystem says is there. Named and exported so the selftest can fire it, because as an inline
 * regex over a variable that never held stderr it was three lines of decoration (M1-76).
 */
export function readNothing(out) {
  return /No such file or directory|cannot access/.test(out);
}

/** Returns null when every path is readable, or the refusal text when one is not. */
function refuse(paths) {
  if (paths.length === 0) return 'REFUSED: no path given. A scan of nothing is not a clean scan.';
  for (const p of paths) {
    const n = scannableCount(p);
    if (n === null) return `REFUSED: ${p} does not exist. ast-grep and detect.mjs both exit 0 on a missing path, so this would have read as clean.`;
    if (n === 0) return `REFUSED: ${p} holds no file either tool can read. An empty scan is DID NOT RUN, not a pass.`;
  }
  return null;
}

function selftest() {
  const tmp = fs.mkdtempSync(path.join(ROOT, '.scan-selftest-'));
  const cases = [];
  try {
    fs.mkdirSync(path.join(tmp, 'empty'), { recursive: true });
    fs.mkdirSync(path.join(tmp, 'pngs'), { recursive: true });
    fs.writeFileSync(path.join(tmp, 'pngs', 'a.png'), 'not code');
    fs.mkdirSync(path.join(tmp, 'real'), { recursive: true });
    fs.writeFileSync(path.join(tmp, 'real', 'a.ts'), 'export const a = 1;\n');
    const rel = (s) => path.relative(ROOT, path.join(tmp, s));

    cases.push(['a missing path is refused', !!refuse([rel('nope')])]);
    cases.push(['an empty directory is refused', !!refuse([rel('empty')])]);
    cases.push(['a directory of unreadable files is refused', !!refuse([rel('pngs')])]);
    cases.push(['no path at all is refused', !!refuse([])]);
    cases.push(['a real directory is accepted', refuse([rel('real')]) === null]);
    // The compliant form must survive: a refusal that fires on everything is not a check.
    cases.push(['one good path plus one missing is refused', !!refuse([rel('real'), rel('nope')])]);
    // And the tools really do pass vacuously — the premise this file rests on, verified
    // rather than quoted, so nobody deletes the wrapper when a tool's behaviour changes.
    cases.push(['ast-grep still exits 0 on a missing path (the reason this exists)',
      sh('ast-grep', ['scan', '-c', 'sgconfig.yml', rel('nope')]).code === 0]);
    // ---- AND THE WIRE BETWEEN THE TOOL AND THE GUARD (M1-76). The `vacuous` check reads r.out,
    // and until this row r.out could not hold stderr on a command that exited 0 — the only shape
    // the guard exists for. Two cases, because it is two claims: the runner carries stderr through
    // a ZERO exit, and the predicate fires on what it carries. Either one alone is the half that
    // was never in doubt.
    const quiet = sh('bash', ['-c', 'echo "ERROR: x: No such file or directory (os error 2)" >&2; exit 0']);
    cases.push([`sh() carries stderr on a PASSING command (was '' under execFileSync) — code ${quiet.code}, ${quiet.out.length} bytes`,
      quiet.code === 0 && quiet.out.includes('No such file or directory')]);
    cases.push(['readNothing() fires on what the runner actually delivers', readNothing(quiet.out)]);
    cases.push(['readNothing() does NOT fire on an ordinary clean run', !readNothing(sh('bash', ['-c', 'echo scanned 12 files']).out)]);
  } finally {
    fs.rmSync(tmp, { recursive: true, force: true });
  }
  let bad = 0;
  for (const [label, ok] of cases) { if (!ok) bad++; console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${label}`); }
  console.log(bad === 0 ? `\nselftest ${cases.length}/${cases.length} PASS` : `\nselftest ${cases.length - bad}/${cases.length}, ${bad} wrong`);
  process.exit(bad === 0 ? 0 : 1);
}

const args = process.argv.slice(2);
if (args.includes('--selftest')) selftest();

const paths = args.filter(a => !a.startsWith('--'));
const no = refuse(paths);
if (no) { console.error(no); process.exit(2); }

let failed = 0;
for (const [label, r] of [
  ['ast-grep', sh('ast-grep', ['scan', '-c', 'sgconfig.yml', ...paths])],
  ['detect', sh('node', [DETECT, ...paths])],
]) {
  // ast-grep reports a missing path on stderr with exit 0; refuse() has already made that
  // unreachable, but a path that vanishes mid-run would still slip through the exit code.
  const vacuous = readNothing(r.out);
  if (r.code !== 0 || vacuous) {
    failed++;
    console.error(`${label}: ${vacuous ? 'read nothing' : `exit ${r.code}`}\n${r.out.trim()}`);
  } else if (r.out.trim()) {
    console.log(r.out.trim());
  }
}
console.log(`scan: ${paths.length} path(s), ${paths.map(p => scannableCount(p)).reduce((a, b) => a + b, 0)} file(s) read`);
process.exit(failed ? 1 : 0);
