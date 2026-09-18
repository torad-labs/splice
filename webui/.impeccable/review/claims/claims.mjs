/**
 * M1-120: EVERY COMMENT THAT ASSERTS A BEHAVIOUR, AND WHETHER THE CODE CARRIES IT.
 *
 * WHY THIS EXISTS. A comment that describes the RIGHT rule while the code implements a different
 * one has now cost this campaign three separate investigations, and the third is why the row was
 * cut: `look-gate.mjs:299` says in words that the field-grid leg "fails when two strips IN ONE BAY
 * disagree", and no step in `checkFieldGrid` or `judgeGrid` groups rows by bay at all. That one
 * sentence sent three seats at anchors, bands and bay-count correlations for an evening, because it
 * was the only statement of intent available and everyone reasoned from it. The other two are on
 * the record: M1-108 found `checkFieldGrid` printing DID NOT RUN in its detail while returning
 * `ok=true`, and M1-64 carried a comment about what the boards rule ink carries that had outlived
 * the rule it described. `fixture-leak.mjs` names the class in its own header, which means the
 * codebase already knows about it and has never swept for it.
 *
 * THE DELIVERABLE IS A LIST, NOT A FIX. This instrument changes nothing and routes everything.
 *
 * WHAT IS IN SCOPE, AND WHAT IS DELIBERATELY NOT. In scope: a comment making a CHECKABLE ASSERTION
 * - one naming a condition under which something fails, passes, is excluded, grouped, skipped or
 * counted. Out of scope: explanatory prose, however long, about why something exists. A census that
 * flags every old comment is one nobody reads, so a comment with no condition word is not a
 * candidate at all and its absence from this list is not a claim that it is fine.
 *
 * THE VERDICTS COME FROM NAMED CHECKS, NEVER FROM INFERENCE, and this is the part worth reading
 * before trusting a line of the output. A verdict reached by reading a comment twice is not a
 * verdict. So each verdict is produced by a CHECK with a stated mechanism, the check is named on
 * the row it produced, and a candidate no check applies to is `undetermined` - kept as its own
 * bucket and expected to be large, because most assertions about behaviour can only be settled by
 * reading the code, and this instrument does not read code. It counts, greps and reports.
 *
 * Usage: bun webui/.impeccable/review/claims/claims.mjs [out.json]
 *        bun webui/.impeccable/review/claims/claims.mjs --selftest
 */
import fs from 'node:fs';
import { execFileSync } from 'node:child_process';
import path from 'node:path';

const R = '/home/user/Documents/dev/projects/atlas/repo/.claude/worktrees/v0.4.0';
const TREES = ['.dev/web-console', 'webui/.impeccable'];

/** A candidate must name a CONDITION. Without one it is prose, and prose is out of scope. */
const CONDITION = /\b(fails?|failed|refuses?|refused|must|never|always|only|cannot|requires?|required|excludes?|excluded|groups?|grouped|skips?|skipped|counts?|counted|passes?|asserts?|rejects?|ignores?|treats?|accepts?)\b/i;

/** Comment shapes that are plainly prose ABOUT the file rather than an assertion about behaviour. */
const PROSE_ONLY = /^\s*(the |this |why |a |an |it |we |note[: ]|usage:|e\.g\.)/i;
/** Modality that makes a comment an assertion whatever word it opens with. `PROSE_ONLY` alone can
 *  only EXCLUDE when none of these is present: "this must never be written before the room has
 *  taken" opens like prose and is an assertion, and the first cut of this guard dropped it. */
const MODAL = /\b(must|never|always|cannot|fails?|refused?|refuses?|only when|requires?|excludes?|groups?|skips?|counts?|passes?|asserts?)\b/i;

/**
 * Strip comments so a grep can be about CODE. Returns the text with every comment replaced by
 * spaces of the same length, so line numbers survive - the same trick sweep-d7.mjs uses.
 */
export function codeOnly(text) {
  let out = '';
  let i = 0;
  while (i < text.length) {
    const ch = text[i];
    const next = text[i + 1];
    if (ch === '/' && next === '/') { while (i < text.length && text[i] !== '\n') { out += ' '; i += 1; } continue; }
    if (ch === '/' && next === '*') {
      while (i < text.length && !(text[i] === '*' && text[i + 1] === '/')) { out += text[i] === '\n' ? '\n' : ' '; i += 1; }
      out += '  '; i += 2; continue;
    }
    out += ch; i += 1;
  }
  return out;
}

/** Every comment in a file, with the line it starts on.
 *
 *  A CHARACTER SCANNER, NOT A LINE SEARCH, and the first cut of this was a line search. It read
 *  `//` and `/*` with indexOf, so a marker INSIDE a string literal opened a comment that did not
 *  exist: it reported an assertion at law-check.mjs:255 whose "comment" was a page of code sitting
 *  inside a test fixture's string. That is the same defect fixture-leak.mjs fixed in its own
 *  scanner and names in its header - quotes paired by regex, an apostrophe in a comment opening a
 *  bogus literal - so this one tracks the six states that matter and emits only from code.
 */
export function commentsOf(text) {
  const found = [];
  let i = 0;
  let line = 1;
  let state = 'code';
  let start = 0;
  let buf = [];
  while (i < text.length) {
    const ch = text[i];
    const next = text[i + 1];
    if (ch === '\n') line += 1;
    if (state === 'code') {
      if (ch === '/' && next === '/') { state = 'line'; start = line; buf = []; i += 2; continue; }
      if (ch === '/' && next === '*') { state = 'block'; start = line; buf = []; i += 2; continue; }
      if (ch === "'") { state = 'single'; i += 1; continue; }
      if (ch === '"') { state = 'double'; i += 1; continue; }
      if (ch === '`') { state = 'template'; i += 1; continue; }
      i += 1; continue;
    }
    if (state === 'line') { if (ch === '\n') { found.push({ line: start, text: buf.join('') }); state = 'code'; } else buf.push(ch); i += 1; continue; }
    if (state === 'block') {
      if (ch === '*' && next === '/') { found.push({ line: start, text: buf.join('') }); state = 'code'; i += 2; continue; }
      buf.push(ch); i += 1; continue;
    }
    // inside a string: escapes skip, and the matching quote (or a template's backtick) returns to code
    if (ch === '\\') { i += 2; continue; }
    if ((state === 'single' && ch === "'") || (state === 'double' && ch === '"') || (state === 'template' && ch === '`')) state = 'code';
    i += 1; continue;
  }
  if (state === 'line') found.push({ line: start, text: buf.join('') });
  return found;
}

/** Is this comment a checkable assertion about behaviour, or is it prose? */
export function isCheckable(text) {
  const t = text.replace(/^\s*[*\s]+/gm, ' ').trim();
  if (t.length < 30) return false;
  if (!CONDITION.test(t)) return false;
  // A comment that is only a heading or a short label is not an assertion about behaviour - and the
  // exclusion needs BOTH tests, prose-opening AND no modality, or it eats real assertions.
  if (PROSE_ONLY.test(t) && !MODAL.test(t)) return false;
  return true;
}

// ---------------------------------------------------------------------------------------------
// THE NAMED CHECKS. Each states its mechanism, and each returns null when it does not apply.
// ---------------------------------------------------------------------------------------------
const CHECKS = [
  {
    name: 'bay-grouping',
    // M1-115's finding, as a rule: a comment claiming the comparison is per bay must be backed by
    // a grouping construct in the code. This is the check the row exists for.
    applies: (t) => /\bin one bay\b|\bper bay\b|\bwithin (a|one) bay\b/i.test(t),
    run: (code) => {
      // NO \b AROUND THE COMPOUND: `groupByBay` has no word boundary between `groupBy` and `Bay`,
      // so the first cut of this regex could not match the very construct it was written to find
      // and reported a carrying file as not-carried. A word-boundary anchor is right for a WORD and
      // wrong for a camelCase COMPOUND, which is what these names are.
      const groups = /groupBy|byBay|bayOf|\bbay\b/i.test(code);
      return groups
        ? { verdict: 'carried', why: 'the comment asserts a per-bay rule and the code contains a grouping construct' }
        : { verdict: 'not-carried', why: 'the comment asserts a per-bay rule and the code contains NO grouping construct — nothing in this file groups by bay' };
    },
  },
  {
    name: 'skip-reports-why',
    // A comment saying something is SKIPPED must be backed by a skip that reports a reason: the
    // campaign's own law 23. The check is the presence of a skip path that carries detail.
    // THE TWO TOKENS MUST BE NEAR EACH OTHER, and the first cut only asked whether both appeared
    // SOMEWHERE. That fired on law-check.mjs:347, whose comment contains "skipped" while describing
    // a CLI guard that used to let an import run a real check - not a skip that reports why - and
    // produced a not-carried verdict I could see was wrong. A check loose enough to convict prose is
    // the same class as a comment loose enough to misdescribe code, one level up.
    applies: (t) => /\bskipped\b[^.]{0,80}\b(reason|why|detail|named|counted)\b|\b(reason|why|detail|named|counted)\b[^.]{0,80}\bskipped\b/i.test(t),
    run: (code) => {
      const skip = /skip/i.test(code);
      const says = /skipped|DID NOT RUN|refus/i.test(code);
      if (!skip) return { verdict: 'not-carried', why: 'the comment describes a skip that reports why, and the code contains no skip path' };
      if (says) return { verdict: 'carried', why: 'the code carries a skip path that also reports it in words' };
      return { verdict: 'undetermined', why: 'the code skips, but whether the skip reports WHY could not be settled without reading it' };
    },
  },
  {
    name: 'counts-every',
    // A comment saying something is COUNTED must be backed by a counting construct.
    applies: (t) => /\bcounts? every\b|\bcounts? all\b|\bevery .{0,20}is counted\b/i.test(t),
    run: (code) => {
      const counts = /\.length\b|\+\+|\bcount\b|\bsize\b/i.test(code);
      return counts
        ? { verdict: 'carried', why: 'the comment asserts a count and the code contains a counting construct' }
        : { verdict: 'not-carried', why: 'the comment asserts a count and the code contains no counting construct' };
    },
  },
];

/**
 * The verdict for one candidate. A check that applies decides it; otherwise it is undetermined, and
 * the reason says so in words rather than leaving a blank. A blank verdict is an absence wearing a
 * label, which is the thing this campaign keeps refusing.
 */
export function verdictFor(text, code) {
  for (const c of CHECKS) {
    if (!c.applies(text)) continue;
    const r = c.run(code);
    return { ...r, check: c.name };
  }
  return { verdict: 'undetermined', check: null, why: 'no named check applies to this shape, and this instrument does not read code — a verdict here would be inference, not evidence' };
}

// ---------------------------------------------------------------------------------------------
const SELFTEST = [
  { name: 'a per-bay claim with no grouping in the code is NOT CARRIED',
    text: 'fails when two strips in one bay disagree.', code: 'const spread = Math.max(...xs) - Math.min(...xs);',
    want: 'not-carried' },
  { name: 'the same claim WITH a grouping construct is carried',
    text: 'fails when two strips in one bay disagree.', code: 'for (const bay of bays) { const rows = groupByBay(bay); }',
    want: 'carried' },
  { name: 'a skip that reports why, with a skip path in the code, is carried',
    text: 'captures older than the source are skipped and counted, with the reason named.', code: 'if (mtime < bar) { skipped += 1; detail += `skipped: ${f}`; }',
    want: 'carried' },
  { name: 'a shape no check covers is UNDETERMINED, and says why',
    text: 'this must never be written before the room has taken.', code: 'await send("Emulation.setDeviceMetricsOverride");',
    want: 'undetermined' },
  { name: 'prose about why a file exists is NOT a candidate at all',
    text: 'why this module exists: the mapping lived in three copies and drifted twice.', code: '', want: null },
  { name: 'a comment with no condition word is not a candidate',
    text: 'the rack scrolls rather than reflowing.', code: '', want: null },
];

if (process.argv.includes('--selftest')) {
  let bad = 0;
  for (const t of SELFTEST) {
    const got = isCheckable(t.text) ? verdictFor(t.text, t.code).verdict : null;
    const ok = got === t.want;
    if (!ok) bad += 1;
    console.log(`  ${ok ? 'ok  ' : 'FAIL'} ${t.name} (want ${t.want}, got ${got})`);
  }
  // ALL THREE BUCKETS MUST BE REACHABLE, or one of them is a bucket nothing can ever land in.
  const seen = new Set(SELFTEST.filter((t) => t.want).map((t) => t.want));
  for (const v of ['carried', 'not-carried', 'undetermined']) {
    if (!seen.has(v)) { console.log(`  FAIL no selftest case produces '${v}'`); bad += 1; }
  }
  console.log(bad === 0 ? 'selftest: ok' : `selftest: ${bad} case(s) FAILED`);
  process.exit(bad === 0 ? 0 : 1);
}

// ---------------------------------------------------------------------------------------------
/**
 * THE FILE LIST COMES FROM GIT, NOT FROM A DIRECTORY WALK, and the first cut walked. `webui/
 * .impeccable/review/gate/` is GITIGNORED and holds a STALE COPY of look-gate.mjs, so the walk
 * counted every comment in it twice and reported the copy as `not-carried` for the bay-grouping
 * rule while the LIVE file reads `carried` - because M1-114 has since added the grouping. A census
 * whose denominator includes derived copies counts the same claim twice and convicts a file nobody
 * runs. `git ls-files` is the source: it is the tree's own answer to what is tracked.
 */
function trackedUnder(dir) {
  try {
    return execFileSync('git', ['ls-files', '--', dir], { cwd: R, encoding: 'utf8' })
      .split('\n').filter(Boolean).map((p) => path.join(R, p));
  } catch { return []; }
}

const files = [...new Set(TREES.flatMap((t) => trackedUnder(t)))]
  .filter((f) => /\.(mjs|ts|tsx)$/.test(f)).sort();
const claims = [];
let candidates = 0;
for (const f of files) {
  let text;
  try { text = fs.readFileSync(f, 'utf8'); } catch { continue; }
  const code = codeOnly(text);
  for (const c of commentsOf(text)) {
    if (!isCheckable(c.text)) continue;
    candidates += 1;
    const v = verdictFor(c.text, code);
    const one = c.text.replace(/\s+/g, ' ').trim().slice(0, 150);
    claims.push({ file: path.relative(R, f), line: c.line, verdict: v.verdict, check: v.check, why: v.why, claim: one });
  }
}

const out = process.argv[2] ?? path.join(import.meta.dirname, 'claims.json');
fs.writeFileSync(out, JSON.stringify({ trees: TREES, files: files.length, candidates, claims }, null, 1));

const counts = new Map();
for (const c of claims) counts.set(c.verdict, (counts.get(c.verdict) ?? 0) + 1);
console.log(`COMMENTS: ${files.length} source file(s) under ${TREES.join(' and ')}`);
console.log(`CANDIDATES: ${candidates} comment(s) naming a condition under which something fails, passes, is excluded, grouped, skipped or counted`);
for (const v of ['carried', 'not-carried', 'undetermined']) console.log(`  ${v.padEnd(14)} ${counts.get(v) ?? 0}`);
console.log('\nNOT CARRIED (each with the check that decided it):');
for (const c of claims.filter((x) => x.verdict === 'not-carried')) console.log(`  ${c.file}:${c.line}  [${c.check}]  ${c.claim}`);
console.log(`\nwrote ${out}`);

// A sweep that examined nothing is not a clean sweep (law 23).
if (files.length === 0 || candidates === 0) {
  console.error('claims: BLIND - no file or no candidate was found, so nothing was examined. This is not a clean result.');
  process.exit(1);
}
