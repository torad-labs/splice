#!/usr/bin/env node
// A ROW THAT READS `done` CLAIMS A LANDING. NOTHING CHECKED THAT THE LANDING HAPPENED.
//
// Found 2026-09-18 07:00 by the orchestrator, against the orchestrator: .dev/web-console/wire-check.mjs
// — M1-37's whole deliverable, the instrument that found the eight unserved routes — had read `done`
// since the previous night, carried a valid receipt, passed its verify, and existed nowhere but one
// worktree as an `??` line in `git status`. One `git clean` and the row was gone with no blob to
// recover it from. It surfaced only because `stage` refused for an unrelated reason.
//
// The gap is structural, not an oversight, and that is why it needs a check rather than a resolution.
// A receipt attests that THE BUILDER RAN THE VERIFY. Builders never commit, so no receipt can attest
// that the result was committed, and the orchestrator — the one seat whose signature could cover it —
// was the seat that dropped it. There was no signature on "this is in the history".
//
// WHAT IT ASKS, and the framing took two tries. The first version compared the receipted blob to the
// INDEX and reported 257 problems, nearly all of them noise: a receipt records the bytes at receipt
// time, and every later row that legitimately edits the same file moves the index away from it. That
// check would have been abandoned within a day for crying wolf, which is how a wall dies.
//
// The honest question is REACHABILITY, not equality: were the receipted bytes ever committed at all?
// `git rev-list --objects HEAD` answers it in one pass for the whole history, and it covers both
// halves of the failure with one question — the file that was never committed (tonight's: the blob
// does not exist), and the file that WAS committed at different bytes than the ones verified (the
// blob the receipt names is absent even though the path is tracked). code-reviewer's correction:
// tracked-ness alone goes green on the second half, which is the more insidious one, because git is
// happy and the thing that shipped is not the thing that was proven.
//
// IGNORED FILES ARE REPORTED, NEVER SILENT. webui/.impeccable/.gitignore deliberately keeps the
// regenerable capture bytes out of history, and 13 receipted PNGs across M1-08 and M1-09 land there.
// Those are not lost work. But "it is gitignored" is exactly the sentence that would hide a real
// artifact, so they are counted and named on every run: a growing count is visible, a silent
// exemption is not.
//
// Usage: node .dev/web-console/landed.mjs [ledger.toml]
//        node .dev/web-console/landed.mjs --selftest
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const ARGS = process.argv.slice(2);
const LEDGER = ARGS.find((a) => !a.startsWith('--')) ?? '.dev/campaigns/web-console.toml';

const LANDED_STATUS = new Set(['done', 'verified']);

// The pure core, so the selftest can drive it without a repository. `reachable` is a Set of object
// ids; `ignored` answers whether a path is deliberately kept out of history.
export function audit(text, reachable, ignored = () => false) {
  const out = { rows: 0, files: 0, unlanded: [], ignored: [], noReceipt: [] };
  for (const b of text.split(/^\[\[items\]\]\s*$/m).slice(1)) {
    const id = /^id = "([^"]+)"/m.exec(b)?.[1];
    const status = /^status = "([^"]+)"/m.exec(b)?.[1];
    if (!id || !LANDED_STATUS.has(status)) continue;
    const receipts = [...b.matchAll(/RECEIPT files=(\S+) blobs=(\S+)/g)];
    if (receipts.length === 0) { out.noReceipt.push(id); continue; }
    out.rows += 1;
    // The LAST receipt is the row's live claim: a re-receipt supersedes, it does not accumulate.
    const [, files, blobs] = receipts[receipts.length - 1];
    const fs = files.split(','), hs = blobs.split(',');
    for (let i = 0; i < fs.length; i += 1) {
      out.files += 1;
      if (reachable.has(hs[i])) continue;
      (ignored(fs[i]) ? out.ignored : out.unlanded).push({ id, file: fs[i], blob: hs[i] });
    }
  }
  return out;
}

function reachableFromHead() {
  const out = execFileSync('git', ['rev-list', '--objects', 'HEAD'], { cwd: ROOT, maxBuffer: 1 << 28, encoding: 'utf8' });
  const set = new Set();
  for (const line of out.split('\n')) { const id = line.slice(0, 40); if (id) set.add(id); }
  return set;
}

function isIgnored(file) {
  try {
    execFileSync('git', ['check-ignore', '-q', '--', file], { cwd: ROOT, stdio: 'ignore' });
    return true;
  } catch { return false; }
}

function selftest() {
  const row = (id, status, files, blobs) =>
    `[[items]]\nid = "${id}"\nstatus = "${status}"\n# [d] RECEIPT files=${files} blobs=${blobs}\n`;
  const cases = [
    { want: 'clean', why: 'a landed row whose bytes are in the history',
      text: row('A', 'done', 'a.ts', 'aaa'), reach: ['aaa'], ignored: () => false,
      check: (r) => r.rows === 1 && r.files === 1 && r.unlanded.length === 0 },
    // THE BORING CASE, and the one that actually happened. The file exists, reads correctly, passes
    // every other leg, and git has never seen it. A check that only notices MISSING files waves it through.
    { want: 'unlanded', why: 'a landed row whose bytes were never committed',
      text: row('B', 'done', 'b.ts', 'bbb'), reach: ['zzz'], ignored: () => false,
      check: (r) => r.unlanded.length === 1 && r.unlanded[0].file === 'b.ts' },
    // The second half: the PATH is committed, at bytes that are not the ones verified. Tracked-ness
    // alone calls this green.
    { want: 'unlanded', why: 'committed at bytes other than the receipted ones',
      text: row('C', 'done', 'c.ts', 'ccc'), reach: ['ccc-other', 'tree'], ignored: () => false,
      check: (r) => r.unlanded.length === 1 && r.unlanded[0].blob === 'ccc' },
    { want: 'ignored', why: 'a receipted file the campaign deliberately keeps out of history',
      text: row('D', 'done', 'x.png', 'ddd'), reach: [], ignored: (f) => f.endsWith('.png'),
      check: (r) => r.unlanded.length === 0 && r.ignored.length === 1 },
    { want: 'skipped', why: 'an in_flight row claims nothing yet and is not checked',
      text: row('E', 'in_flight', 'e.ts', 'eee'), reach: [], ignored: () => false,
      check: (r) => r.rows === 0 && r.unlanded.length === 0 },
    { want: 'noReceipt', why: 'a landed row with no receipt at all',
      text: '[[items]]\nid = "F"\nstatus = "done"\n', reach: [], ignored: () => false,
      check: (r) => r.noReceipt.length === 1 && r.rows === 0 },
    // An empty ledger must not read as a pass. The caller enforces it via the row count, so the
    // count has to be honest here: zero rows is zero rows, never an implicit all-clear.
    { want: 'empty', why: 'an empty ledger reads as zero rows, not as a clean bill',
      text: '', reach: [], ignored: () => false,
      check: (r) => r.rows === 0 && r.files === 0 && r.unlanded.length === 0 },
    // A re-receipt supersedes: M1-37 was re-receipted onto one file after its first named two.
    { want: 'supersede', why: 'the last receipt is the live claim, not the union of all of them',
      text: row('G', 'done', 'g.ts,stale.txt', 'ggg,sss') + '# [d] RECEIPT files=g.ts blobs=ggg\n',
      reach: ['ggg'], ignored: () => false,
      check: (r) => r.files === 1 && r.unlanded.length === 0 },
  ];
  let bad = 0;
  for (const c of cases) {
    const r = audit(c.text, new Set(c.reach), c.ignored);
    const ok = c.check(r);
    if (!ok) bad += 1;
    console.log(`${ok ? 'ok  ' : 'WRONG'} ${c.want.padEnd(10)} ${c.why}`);
  }
  console.log(bad === 0 ? `\nselftest ${cases.length}/${cases.length} PASS` : `\nselftest ${cases.length - bad}/${cases.length}, ${bad} wrong`);
  process.exit(bad === 0 ? 0 : 1);
}

if (ARGS.includes('--selftest')) selftest();

const text = fs.readFileSync(path.resolve(ROOT, LEDGER), 'utf8');
const reachable = reachableFromHead();
const r = audit(text, reachable, isIgnored);

console.log(`landed: ${r.rows} row(s) read from ${LEDGER}, ${r.files} receipted file(s) checked against ${reachable.size} object(s) reachable from HEAD`);
for (const e of r.ignored) console.log(`  ignored-by-design  ${e.id.padEnd(8)} ${e.file}`);
if (r.ignored.length) console.log(`  ${r.ignored.length} receipted file(s) are gitignored — regenerable bytes, named here so the exemption cannot grow in silence`);
for (const id of r.noReceipt) console.log(`  NO RECEIPT         ${id} — status claims a landing and nothing proves what landed`);
for (const e of r.unlanded) console.log(`  NOT IN HISTORY     ${e.id.padEnd(8)} ${e.file} (receipted ${e.blob.slice(0, 8)})`);

if (r.rows === 0) {
  console.error('landed: no landed rows read — an empty row set is not a pass');
  process.exit(1);
}
if (r.unlanded.length || r.noReceipt.length) {
  console.error(`\nlanded: ${r.unlanded.length + r.noReceipt.length} row-file(s) claim a landing that is not in the history.`);
  console.error('REMEDY: the builder re-receipts the file at its current bytes, the orchestrator stages and commits the row.');
  process.exit(1);
}
console.log('landed: every landed row is in the history');
