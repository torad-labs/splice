#!/usr/bin/env node
// scan — run this campaign's two structural instruments over a path, and refuse to
// report clean for a path they did not actually read.
//
// WHY. Both tools pass vacuously on a target that is not there:
//
//   $ ast-grep scan -c sgconfig.yml webui/src/does/not/exist
//   ERROR: webui/src/does/not/exist: No such file or directory (os error 2)
//   $ echo $?
//   0
//
//   $ node ~/.claude/skills/impeccable/scripts/detect.mjs webui/src/does/not/exist
//   Warning: cannot access webui/src/does/not/exist
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
//   node .dev/web-console/scan.mjs <path...>     # exits 2 if a path is missing or empty
//   node .dev/web-console/scan.mjs --selftest

import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const DETECT = path.join(process.env.HOME, '.claude/skills/impeccable/scripts/detect.mjs');
// What either tool can actually read. A directory of nothing but PNGs is as unscanned as
// a directory that is not there, and must report the same way.
const SCANNABLE = new Set(['.ts', '.tsx', '.js', '.jsx', '.mjs', '.cjs', '.css', '.html', '.vue', '.svelte']);

function sh(cmd, args) {
  try {
    return { code: 0, out: execFileSync(cmd, args, { cwd: ROOT, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], maxBuffer: 64 << 20 }) };
  } catch (e) {
    return { code: e.status ?? 1, out: `${e.stdout ?? ''}${e.stderr ?? ''}${e.message ?? ''}` };
  }
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
  const vacuous = /No such file or directory|cannot access/.test(r.out);
  if (r.code !== 0 || vacuous) {
    failed++;
    console.error(`${label}: ${vacuous ? 'read nothing' : `exit ${r.code}`}\n${r.out.trim()}`);
  } else if (r.out.trim()) {
    console.log(r.out.trim());
  }
}
console.log(`scan: ${paths.length} path(s), ${paths.map(p => scannableCount(p)).reduce((a, b) => a + b, 0)} file(s) read`);
process.exit(failed ? 1 : 0);
