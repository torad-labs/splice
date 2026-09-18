#!/usr/bin/env node
// law-check — laws 25 and 27, mechanically, over the WHOLE ledger.
//
// WHY. code-reviewer's M1-33 asked the only question worth asking about a gate: name the properties
// this campaign cares about that NO leg observes. Three came back and two are fixed. The third was
// this one — no leg opened the ledger, so laws 25 and 27 were enforced by the orchestrator
// remembering them, which is the same class of guarantee as a check that cannot fail. It was
// demonstrated the same night by four verify lines shipping with two broken legs each.
//
// LAW 25: a row whose fence can touch CSS carries a build leg, and the leg is `npx vite build`
//   specifically — not `npm run build -w webui`, whose script is `tsc --noEmit && vite build`, so a
//   peer's in-flight type error would mask a CSS defect. An orphaned declaration reached the tree
//   and broke the build for every seat because ZERO done rows carried one.
//
// LAW 27: a verify leg asserts what must be PRESENT. An absence is what every broken instrument
//   produces, so a leg that passes on absence cannot tell a fixed defect from a dead tool. The three
//   shapes have all shipped here and each one is caught by name:
//     if <cmd> | grep ...; then exit 1; fi   M1-08/09/10 — GREEN when world.test.ts is deleted,
//                                            because a missing file makes grep match nothing
//     ! grep ...                             a bare negation: the pass is the empty output
//     test -z "$(grep ...)"                  emptiness as the pass, spelled out
//
// THE DENOMINATOR IS THE LEDGER FILE, NOT THE ROWS THAT LOOK INTERESTING. Every row gets a
// disposition and a row in none of them fails BY NAME; a run that parses zero rows is an ERROR, not
// a clean report, because a law check that reads an empty ledger and reports clean is the joke
// version of itself.
//
// READ THROUGH THE CLI, not by parsing TOML here: `manifest.py list` enumerates and
// `manifest.py get <id>` returns files/status/verify, which is the ledger's own reader. (The
// ledger-only-via-CLI law is about writes and the flock — a reader cannot corrupt it — but using
// the CLI costs nothing and keeps one reader.)
//
//   node dev/web-console/law-check.mjs            check, exit 1 on any violation
//   node dev/web-console/law-check.mjs --report   check, print, ALWAYS exit 0 (the exit gate's
//                                                 wiring: whether this blocks is the orchestrator's
//                                                 call, so the gate reports and does not gate yet)
//   node dev/web-console/law-check.mjs --selftest mutation-proof both laws both ways
//   node dev/web-console/law-check.mjs --json
import { execFileSync } from 'node:child_process';
import { readdirSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(import.meta.dirname, '../..');
/** The campaign's ledger, or one named on the command line (the selftest points it at a fixture). */
function ledgerPath() {
  const at = process.argv.indexOf('--ledger');
  return at === -1 ? 'dev/campaigns/web-console.toml' : process.argv[at + 1];
}

const manifest = (args) => execFileSync('python3', ['dev/campaigns/manifest.py', ledgerPath(), ...args], { cwd: ROOT, encoding: 'utf8' });

/** Every row, with its fence, status and verify line, read through the CLI. */
export function readLedger() {
  const listed = manifest(['list']);
  const ids = listed.split('\n').map((line) => (line.trim().split(/\s+/)[0] ?? '')).filter((id) => /^[A-Z]+\d+-\d+$/.test(id));
  return ids.map((id) => {
    // THE LEDGER IS LIVE and the orchestrator writes to it while legs read it: a `get` hit a
    // half-rewritten item during this row's own first run and killed the check with
    // "item 'M1-07' not found". One retry, and a row still unreadable is NAMED as unreadable rather
    // than dropped — a row quietly missing from the denominator is the failure this file is about.
    let text = null;
    for (let attempt = 0; attempt < 2 && text === null; attempt += 1) {
      try { text = manifest(['get', id]); }
      catch { if (attempt === 0) execFileSync('sleep', ['1']); }
    }
    if (text === null) return { id, status: null, verify: null, files: [], unreadable: true };
    const files = (text.match(/^files = \[(.*)\]$/m) ?? [, ''])[1];
    const one = (key) => {
      const match = text.match(new RegExp(`^${key} = "(.*)"$`, 'm'));
      return match === null ? null : match[1];
    };
    return {
      id,
      status: one('status'),
      verify: one('verify'),
      files: [...files.matchAll(/"([^"]+)"/g)].map((m) => m[1]),
    };
  });
}

// --------------------------------------------------------------------- law 25

/** Does a path on disk hold a .css file? The `could touch CSS` question, answered by the tree. */
function holdsCss(prefix) {
  const root = prefix.endsWith('/**') ? prefix.slice(0, -3) : prefix;
  const walk = (dir, depth) => {
    if (depth > 6) return false;
    let entries;
    try { entries = readdirSync(dir); } catch { return false; }
    for (const entry of entries) {
      const path = join(dir, entry);
      if (entry.endsWith('.css')) return true;
      try { if (statSync(path).isDirectory() && walk(path, depth + 1)) return true; } catch { /* unreadable */ }
    }
    return false;
  };
  try { return statSync(root).isDirectory() ? walk(root, 0) : root.endsWith('.css'); } catch { return false; }
}

/** The fence entries that can touch CSS: an exact .css file, or a directory/glob holding one. */
export function cssFences(files) {
  return files.filter((entry) => entry.endsWith('.css') || holdsCss(entry));
}

// --------------------------------------------------------------------- law 27

/** The three absence-asserting shapes, as the regexes that match the real shipped lines. */
export const ABSENCE_SHAPES = [
  {
    name: 'if-pipe-grep-then-exit',
    // `if npx vitest run tests/world.test.ts 2>&1 | grep -E -q 'src/pages/'; then exit 1; fi`
    // (M1-08/09/10). A deleted test file makes grep match nothing, so the leg passes.
    re: /\bif\s+[^;]*\bgrep\b[^;]*;\s*then\s+exit\s+[1-9]/,
  },
  { name: 'bang-grep', re: /(?:^|[\s(])!\s*[^|;&]*\bgrep\b/ },
  { name: 'empty-is-the-pass', re: /(?:\btest\s+|\[\s*)-[zn]\s+["']?\$\([^)]*\bgrep\b/ },
];

/** The law-27 shapes a verify line carries, by name. */
export function absenceShapes(verify) {
  if (verify === null) return ['no verify line'];
  return ABSENCE_SHAPES.filter((shape) => shape.re.test(verify)).map((shape) => shape.name);
}

// ------------------------------------------------------------------- the check

/**
 * Both laws over a set of rows. Every row gets a disposition: `ok`, or the violations it carries.
 * A leg can carry both laws at once and is reported for both.
 */
export function check(rows, { cssFencesOf = cssFences } = {}) {
  const findings = [];
  const dispositions = { ok: 0, 'law-25': 0, 'law-27': 0, both: 0, unreadable: 0 };
  for (const row of rows) {
    if (row.unreadable === true) {
      findings.push({ id: row.id, law: 0, detail: 'the ledger listed this row and get refused it (the ledger was being written while this leg read it) — it is unreadable, not absent' });
      dispositions.unreadable += 1;
      continue;
    }
    const css = cssFencesOf(row.files);
    const buildLeg = row.verify !== null && /npx\s+vite\s+build/.test(row.verify);
    const missingBuild = css.length > 0 && !buildLeg;
    const shapes = absenceShapes(row.verify);
    const absent = shapes.length > 0 && shapes[0] !== 'no verify line';
    if (missingBuild && absent) dispositions.both += 1;
    else if (missingBuild) dispositions['law-25'] += 1;
    else if (absent) dispositions['law-27'] += 1;
    else dispositions.ok += 1;
    if (missingBuild) {
      findings.push({ id: row.id, law: 25, detail: `fence can touch CSS (${css.slice(0, 3).join(', ')}${css.length > 3 ? ', …' : ''}) and the verify carries no \`npx vite build\`` });
    }
    if (absent) {
      findings.push({ id: row.id, law: 27, detail: `${shapes.join(' + ')} — the pass is an absence: ${shapes.map((s) => ABSENCE_SHAPES.find((x) => x.name === s).re).join(' ')}` });
    }
  }
  return { findings, dispositions, rows: rows.length };
}

function report(rows) {
  const result = check(rows);
  console.log(`law-check: ${result.rows} row(s) read from the ledger (${ledgerPath()})`);
  if (result.rows === 0) {
    console.error('DID NOT RUN: the ledger parsed to zero rows. A law check that reads nothing and reports clean is the joke version of itself.');
    process.exit(2);
  }
  const d = result.dispositions;
  console.log(`  dispositions: ${d.ok} ok, ${d['law-25']} law-25 only, ${d['law-27']} law-27 only, ${d.both} both, ${d.unreadable} unreadable`);
  // BOTH READINGS OF LAW 25, printed, because they differ by 4x and the difference is a ruling the
  // orchestrator owns: the strict reading is "any fence that could hold CSS" (the row's words), and
  // the narrow one is "a fence that names a .css file itself". Measured on the ledger as it stands.
  const explicit = rows.filter((row) => row.files.some((f) => f.endsWith('.css')));
  const explicitNoBuild = explicit.filter((row) => !/npx\s+vite\s+build/.test(row.verify ?? ''));
  console.log(`  law 25, narrow reading (fence names a .css file): ${explicitNoBuild.length} of ${explicit.length} rows lack it${explicitNoBuild.length === 0 ? '' : ` — ${explicitNoBuild.map((r) => r.id).join(' ')}`}`);
  const done = rows.filter((row) => row.status === 'done');
  console.log(`  done rows: ${done.length}, of which ${done.filter((row) => /npx\s+vite\s+build/.test(row.verify ?? '')).length} carry \`npx vite build\``);
  const byLaw = (law) => result.findings.filter((f) => f.law === law);
  for (const law of [25, 27]) {
    const list = byLaw(law);
    console.log(`  law ${law}: ${list.length} violation(s)${list.length === 0 ? '' : ' — by name:'}`);
    for (const finding of list) console.log(`    ${finding.id}  law ${finding.law}  ${finding.detail}`);
  }
  return result;
}

// ---------------------------------------------------------------- mutation proof

/** Both laws, both ways, on synthetic rows. Nothing here touches the ledger. */
function selftest() {
  const cases = [
    { label: 'a CSS-fenced row with no build leg is RED', want: ['law-25'],
      rows: [{ id: 'X-1', status: 'done', files: ['webui/src/widgets/rule/rule.css'], verify: 'npx tsc --noEmit' }] },
    { label: 'the same row WITH npx vite build is GREEN', want: [],
      rows: [{ id: 'X-1', status: 'done', files: ['webui/src/widgets/rule/rule.css'], verify: 'npx tsc --noEmit && npx vite build' }] },
    { label: 'npm run build is NOT a build leg (tsc masks the CSS defect)', want: ['law-25'],
      rows: [{ id: 'X-1', status: 'done', files: ['webui/src/widgets/rule/rule.css'], verify: 'npm run build -w webui' }] },
    { label: 'a CSS-holding directory counts, not just a .css path', want: ['law-25'],
      rows: [{ id: 'X-1', status: 'done', files: ['webui/src/widgets/rule/**'], verify: 'npx tsc --noEmit' }] },
    { label: 'a fence with no CSS anywhere is not a law-25 row', want: [],
      rows: [{ id: 'X-1', status: 'done', files: ['dev/web-console/law-check.mjs'], verify: 'npx tsc --noEmit' }] },
    { label: 'the M1-08 shape is RED', want: ['law-27'],
      rows: [{ id: 'X-1', status: 'done', files: ['dev/web-console/law-check.mjs'],
        verify: "if npx vitest run tests/world.test.ts 2>&1 | grep -E -q 'src/pages/'; then exit 1; fi" }] },
    { label: 'a bare ! grep is RED', want: ['law-27'],
      rows: [{ id: 'X-1', status: 'done', files: ['dev/web-console/law-check.mjs'], verify: '! grep -q TODO file.txt' }] },
    { label: 'emptiness as the pass is RED', want: ['law-27'],
      rows: [{ id: 'X-1', status: 'done', files: ['dev/web-console/law-check.mjs'], verify: 'test -z "$(grep -v ok file.txt)"' }] },
    { label: 'a presence assertion of the compliant shape is GREEN', want: [],
      rows: [{ id: 'X-1', status: 'done', files: ['dev/web-console/law-check.mjs'],
        verify: 'node dev/web-console/scan.mjs webui/src && grep -q OWNER dev/web-console/census/m1-punch-list.md' }] },
    { label: 'both laws on one row are both reported', want: ['law-25', 'law-27'],
      rows: [{ id: 'X-1', status: 'done', files: ['webui/src/widgets/rule/rule.css'],
        verify: "npx tsc --noEmit && if grep -q x y; then exit 1; fi" }] },
  ];
  let bad = 0;
  for (const c of cases) {
    const result = check(c.rows, { cssFencesOf: cssFences });
    const got = [...new Set(result.findings.map((f) => `law-${f.law}`))].sort();
    const ok = JSON.stringify(got) === JSON.stringify([...c.want].sort());
    if (!ok) bad += 1;
    console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${c.label} — wanted [${c.want.join(', ')}], got [${got.join(', ')}]`);
  }
  // The empty-ledger case, run as a real subprocess against a fixture ledger with no rows, so the
  // exit code is the evidence rather than an assertion about it.
  {
    const fixture = join(tmpdir(), `law-check-empty-${process.pid}.toml`);
    writeFileSync(fixture, '# a ledger with no rows\n');
    let status = 0;
    try { execFileSync(process.execPath, [fileURLToPath(import.meta.url), '--ledger', fixture], { encoding: 'utf8', stdio: 'pipe' }); }
    catch (error) { status = error.status; }
    rmSync(fixture, { force: true });
    const ok = status === 2;
    if (!ok) bad += 1;
    console.log(`  ${ok ? 'PASS' : 'FAIL'}  an empty ledger is DID NOT RUN, not a clean report — wanted exit 2, got ${status}`);
  }
  const total = cases.length + 1;
  console.log(bad === 0 ? `\nselftest ${total}/${total} PASS` : `\nselftest ${total - bad}/${total}, ${bad} wrong`);
  process.exit(bad === 0 ? 0 : 1);
}

// ------------------------------------------------------------------------ CLI

/** The CLI runs only when this file is the program: an unguarded body made `import` run a real
 *  ledger check, which the selftest's own empty-ledger case caught by getting exit 1 instead of 2. */
if (import.meta.url === `file://${process.argv[1]}`) {
  const args = process.argv.slice(2);
  if (args.includes('--selftest')) selftest();
  const result = report(readLedger());
  if (args.includes('--json')) console.log(JSON.stringify(result, null, 1));
  if (args.includes('--report')) process.exit(0);
  process.exit(result.findings.length === 0 ? 0 : 1);
}
