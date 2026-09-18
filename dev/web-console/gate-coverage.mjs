#!/usr/bin/env node
// gate-coverage — every checker in dev/web-console, and what the exit gate does with it (M1-49).
//
// WHY THIS EXISTS. `grep wire-check checks/` returns nothing. wire-check.mjs was written, reviewed,
// receipted, committed and named `done`, and no gate ran it — while the ruling written inside that
// same file says a Level 3b census entry becomes a defect the day its route lands AND THE MILESTONE
// RE-RUN IS WHAT CATCHES IT. That re-run did not exist. The file contained a justification for not
// gating that depended on a gate.
//
// AND SOME ABSENCES ARE CORRECT, WHICH IS THE ACTUAL PROBLEM. snapshot.mjs is a library look.mjs
// calls. scale.mjs reports numbers and has no verdict to give. Those two belong outside the gate and
// wire-check.mjs did not, and NOTHING IN THE TREE RECORDED THE DIFFERENCE — so from outside, a
// deliberate exclusion and a forgotten checker are the same absence. That is law 24's denominator
// problem pointed at the gate itself: exit-gate.mjs's leg list is a hand-authored allowlist checked
// against nothing, so a checker can be written, reviewed, committed and never run by anything, and
// the gate stays green while saying nothing about it.
//
// THE DENOMINATOR COMES FROM THE DIRECTORY, RECURSIVELY, AND NEVER FROM A LIST IN THIS FILE. That
// sentence is the whole check, and it is also a correction to the row that cut it: the row counted
// 13 checkers from `dev/web-console/*.mjs`, and the directory holds 18. A top-level glob cannot see
// lib/cdp.mjs, lib/png.mjs, lib/fixtures.mjs, census/tonal-census.mjs or census/build-punch-list.mjs
// — five files, three of which every rendered leg in the gate depends on. A denominator derived from
// a glob is an allowlist with a wildcard in it, which is the same defect one directory down, so this
// walks the tree.
//
// EVERY CHECKER EARNS ONE OF FOUR DISPOSITIONS, AND EACH ONE IS A CLAIM THIS FILE THEN VERIFIES —
// a disposition nothing checks is a comment, and comments do not gate:
//
//   GATE LEG           exit-gate.mjs runs it. VERIFIED by parsing exit-gate.mjs's own LEGS array:
//                      if no leg names the file, the claim is false and fails by name.
//   LIBRARY OF <file>  a disposed checker imports or shells out to it. VERIFIED: the named file must
//                      exist, be in this denominator, and actually reference this one.
//   TOOL, NEVER GATES  it produces evidence or numbers rather than a verdict, with the reason
//                      written out. VERIFIED in the one direction that can be: no leg may name it —
//                      a file the gate actually runs is not a tool that never gates.
//   THE GATE ITSELF    exit-gate.mjs. VERIFIED: it is the file the leg list was parsed out of.
//   PENDING <row>      a checker still being built, whose fate belongs to the row building it.
//                      VERIFIED AGAINST THE LEDGER, and this is the disposition that EXPIRES: the
//                      row must exist and must not yet read done or verified. The day it lands, this
//                      entry fails by name and someone has to say which of the other four it is.
//
// THE FIFTH ONE WAS NOT PLANNED AND THE TREE ASKED FOR IT. The first real run of this check found
// `type-ladder.mjs`, which did not exist when the denominator was enumerated ten minutes earlier: it
// is M1-50's deliverable, in_flight, owned by another seat. Calling it a GATE LEG would wire another
// row's unfinished work into the gate; calling it TOOL, NEVER GATES would decide a question that is
// not mine to answer. Both would have been a lie told to keep a check green, which is the failure
// this file exists to catch, committed by this file. A disposition that names the row and expires
// with it is the honest third answer -- and it is §24's own vocabulary, the same `pending` that
// coverage.ts uses for a route whose row has not landed.
//
// A FILE IN NONE OF THE FOUR FAILS BY NAME. Absence is not a disposition — the same sentence
// wire-check.mjs enforces one plane down, and the reason this file exists rather than a paragraph in
// a review document.
//
// WHY THERE ARE FOUR AND NOT THE THREE THE ROW ASKED FOR: exit-gate.mjs is genuinely none of the
// three. It is not a leg (it cannot run itself), not a library of a leg, and not a tool that never
// gates — it IS the gate. Forcing it into one of the three would have made the table lie about its
// most important entry to keep a count, so it got a fourth disposition with a verification of its
// own. A fourth that is checked beats a third that is wrong.
//
// LAW 23. A missing directory, a directory with no .mjs in it, an exit-gate.mjs that cannot be read
// or whose LEGS array cannot be found: each is DID NOT RUN, each exits non-zero, none is ever clean.
// The last one matters most — if the leg list stops parsing, every GATE LEG claim in the table
// becomes unverifiable, and reporting that as a pass is exactly the shape this file was built to end.
//
// Usage: node dev/web-console/gate-coverage.mjs
//        node dev/web-console/gate-coverage.mjs --selftest
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import { dirname, join, posix, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import process from 'node:process';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '..', '..');
const DIR = join(ROOT, 'dev/web-console');
const EXIT_GATE = 'exit-gate.mjs';

const LEG = 'GATE LEG';
const LIBRARY = 'LIBRARY OF';
const TOOL = 'TOOL, NEVER GATES';
const GATE = 'THE GATE ITSELF';
const PENDING = 'PENDING';

/**
 * THE CLAIMS. Hand-authored, unavoidably — a disposition is a judgement and judgements are written
 * by people. What is NOT hand-authored is the denominator they are checked against, and every claim
 * here is verified against the tree below. The table may be wrong; it may not be silently wrong.
 */
const DISPOSITIONS = {
  // ---- the gate
  'exit-gate.mjs': {
    kind: GATE,
    why: 'the milestone boundary itself; it runs the legs and cannot be one',
  },

  // ---- legs
  'gate-coverage.mjs': {
    kind: LEG,
    why: 'this file. It gates on its own denominator, which is the only way the list below can be ' +
      'trusted: a table nothing runs is a document, and this campaign has enough of those',
  },
  'wire-check.mjs': {
    kind: LEG,
    why: 'the console declares against what the daemon emits. THE FINDING THIS ROW CAME FROM: it was ' +
      'in no gate at all, while its own exit rule cites the milestone re-run as what catches a ' +
      'census entry the day its route lands',
  },
  'landed.mjs': {
    kind: LEG,
    why: 'every done row\'s receipted bytes reachable from HEAD. Catches the row that claims a ' +
      'landing and exists in one worktree only — which is how wire-check.mjs itself spent a night',
  },
  'scan.mjs': { kind: LEG, why: 'the structural walls, through the wrapper that refuses a path they did not read' },
  'law-check.mjs': { kind: LEG, why: 'laws 25 and 27 over the ledger; reports rather than gates, by the orchestrator\'s ruling' },
  'fixture-leak.mjs': { kind: LEG, why: 'a fixture byte in dist ships sample data to an operator' },
  'comp-check.mjs': { kind: LEG, why: 'the comp\'s constants measured on live pages, at both frames' },
  'look.mjs': { kind: LEG, why: 'the rendered rule pass over three frame-and-theme cells' },

  // ---- libraries of a disposed checker
  'look-gate.mjs': {
    kind: LIBRARY, of: 'look.mjs',
    why: 'the static-and-capture half of the look pass. look.mjs runs it (look.mjs:56) and the gate ' +
      'reads its verdict through the look leg\'s failIf, so it is already gating — through its caller',
  },
  'snapshot.mjs': {
    kind: LIBRARY, of: 'look.mjs',
    why: 'look.mjs imports snapshot/LOOK_DIR/nameFor from it; it freezes the page the rules then read',
  },
  'lib/cdp.mjs': {
    kind: LIBRARY, of: 'look.mjs',
    why: 'the Chrome DevTools transport under every rendered checker — look, comp-check, snapshot, ' +
      'capture, gate and scale all import it. Named against look.mjs because that is the LEG whose ' +
      'failure would follow from breaking it',
  },
  'lib/png.mjs': { kind: LIBRARY, of: 'look.mjs', why: 'decodePng and the colour-fraction maths the rendered legs measure with' },
  'lib/fixtures.mjs': { kind: LIBRARY, of: 'comp-check.mjs', why: 'the one address-to-fixture mapping; comp-check imports urlFor from it' },
  'capture.mjs': {
    kind: LIBRARY, of: 'gate.mjs',
    why: 'gate.mjs imports capturePage from it. NOTE THIS ONE REACHES NO LEG: its caller is a tool, ' +
      'so nothing in the exit gate exercises it. Its own blank-frame predicate is a real check and ' +
      'the --sweep mode that runs it is invoked by rows, not by the gate. If a capture ever needs ' +
      'gating, this is the entry that says it currently is not',
  },

  // ---- a row landed while this file was being written, and the PENDING disposition expired
  // exactly as designed: M1-50 went `done` mid-row and the check said so by name on its next run.
  'type-ladder.mjs': {
    kind: TOOL,
    why: 'M1-50\'s type and spacing ladder. THIS RECORDS WHAT IT IS TODAY, not a judgement that it ' +
      'should never gate: it prints `rungs off by more than RATIO_LIMIT: N` and exits 0 whatever N ' +
      'is, so there is no verdict for a leg to read. It is the strongest leg candidate in this ' +
      'column and the thing standing between it and a leg is a DECISION nobody has made — which ' +
      'ratio delta fails a build. Give it that threshold and this entry becomes GATE LEG',
  },

  'theme.mjs': {
    kind: LIBRARY, of: 'webui/.impeccable/review/coverage/coverage.mjs',
    why: 'M1-55\'s shared theme-seeding capability, which that row cut precisely so a fourth seat ' +
      'would not rebuild it privately. ITS ONLY CONSUMER IS A ROW\'S REVIEW SCRIPT, and the finding ' +
      'is that snapshot.mjs — edited in M1-55\'s own receipt — still seeds the theme INLINE at ' +
      'snapshot.mjs:94 rather than importing from here. The file that exists to end duplicated ' +
      'theme seeding is duplicated by its own row\'s other file; no leg reaches it either way',
  },

  // ---- tools that never gate
  'gate.mjs': {
    kind: TOOL,
    why: 'milestone contact sheets: exhaustive captures of every address in both themes at two ' +
      'frames, for a human to sign off on. It produces evidence for a reviewer, not a verdict, and ' +
      'the gate\'s rendered judgement is the look leg',
  },
  'scale.mjs': {
    kind: TOOL,
    why: 'measures what the console looks like at the operator\'s 3840 frame — bay width, strip ' +
      'height, label size, text share. It REPORTS numbers and has no pass/fail to give. Its finding ' +
      '(layout in vw, type in px) is now carried by the legs that do judge: FRAMES puts 3840x2160 ' +
      'into comp-check and look',
  },
  'census/tonal-census.mjs': {
    kind: TOOL,
    why: 'the mid-tone census over captured frames, comp measured by the same code. A distribution ' +
      'is not a verdict — there is no threshold anyone has agreed to fail a build on',
  },
  'census/build-punch-list.mjs': {
    kind: TOOL,
    why: 'assembles the punch list from artefacts other runs produced. It orders findings for people; ' +
      'the findings themselves are gated where they are measured',
  },
};

// ---------------------------------------------------------------- law 23

function fail(message) {
  console.error(`gate-coverage: DID NOT RUN — ${message}`);
  process.exit(1);
}

/**
 * Every `.mjs` under a directory, recursively, as posix-relative paths. THE DENOMINATOR. It walks
 * because a `*.mjs` glob stops at the top level and this tree has two subdirectories of checkers.
 */
export function enumerate(dir, prefix = '') {
  if (!existsSync(dir)) return null;
  const out = [];
  for (const name of readdirSync(dir).sort()) {
    const full = join(dir, name);
    const rel = prefix === '' ? name : posix.join(prefix, name);
    if (statSync(full).isDirectory()) out.push(...(enumerate(full, rel) ?? []));
    else if (name.endsWith('.mjs')) out.push(rel);
  }
  return out;
}

/**
 * The legs exit-gate.mjs actually runs, parsed out of its LEGS array: leg name -> the
 * dev/web-console files that leg names. Returns null when the array cannot be found, which is
 * DID NOT RUN and never an empty answer — an unparseable leg list makes every GATE LEG claim in the
 * table unverifiable, and reporting that as a pass is the defect this file exists to end.
 */
export function legsOf(text) {
  const start = text.indexOf('const LEGS = [');
  if (start === -1) return null;
  const end = text.indexOf('\n];', start);
  if (end === -1) return null;
  const block = text.slice(start, end);
  const byLeg = new Map();
  let current = null;
  // one scan, two alternatives: a leg's name, or a path it names. A path belongs to the nearest
  // preceding name, which is what the array's own shape means.
  // the lookahead is not decoration: unanchored, `dev/web-console/wire-check.mjs.disabled` matches
  // its own longest `.mjs` prefix, so a path that had been renamed OUT of the gate would still read
  // as named by it. Found by a mutation that came back green and turned out to be the mutation's
  // fault rather than the check's — which is the same two-outcome confusion law 23 is about, one
  // level up, in the instrument that tests the instrument.
  for (const m of block.matchAll(/name:\s*'([^']+)'|dev\/web-console\/([A-Za-z0-9._/-]+\.mjs)(?![A-Za-z0-9._/-])/g)) {
    if (m[1] !== undefined) { current = m[1]; if (!byLeg.has(current)) byLeg.set(current, []); }
    else if (current !== null) byLeg.get(current).push(m[2]);
  }
  return byLeg.size === 0 ? null : byLeg;
}

/** The refusals, as a pure function so the selftest can prove each one without a tree to break. */
export function guard({ files, legs, ledger = null, needsLedger = false }) {
  if (files === null) return 'no dev/web-console directory — this is not the worktree root';
  if (files.length === 0) return 'dev/web-console holds no .mjs files, so the denominator is empty and nothing could be checked';
  if (!files.includes(EXIT_GATE)) return `dev/web-console holds no ${EXIT_GATE} — there is no gate to have coverage of`;
  if (legs === null) return `${EXIT_GATE} has no parseable LEGS array, so every GATE LEG claim below is unverifiable`;
  // a PENDING disposition is a claim about a ledger row; with no ledger to read it cannot be
  // checked, and an unverifiable disposition is the thing this file refuses to report as a pass
  if (needsLedger && (ledger === null || ledger === '')) {
    return 'a PENDING disposition names a ledger row and the ledger could not be read, so that claim is unverifiable';
  }
  return null;
}

// ---------------------------------------------------------------- the comparison

const OK = 'OK';
const UNDISPOSITIONED = 'UNDISPOSITIONED';
const STALE = 'STALE';
const MISMATCHED = 'MISMATCHED';

/**
 * Every enumerated file against its claim, and every claim against the tree. `sources` is
 * file -> text, so a LIBRARY OF claim is checked by reading the CALLER and not by trusting the label.
 */
export function audit({ files, dispositions, legs, sources, ledger = '' }) {
  // DEDUPED: a leg names its file twice, once in `probe` and once in `run`, and reporting
  // "run by the scan, scan leg" reads like two legs where there is one.
  const legByFile = new Map();
  for (const [leg, paths] of legs) for (const p of new Set(paths)) {
    if (!legByFile.has(p)) legByFile.set(p, []);
    legByFile.get(p).push(leg);
  }
  const rows = [];

  for (const file of files) {
    const d = dispositions[file];
    // THE BORING CASE, and the one this whole file is for: a checker nobody wrote a line about.
    if (d === undefined) {
      rows.push({ file, kind: null, verdict: UNDISPOSITIONED,
        detail: 'in dev/web-console and in none of the four dispositions — absence is not one of them' });
      continue;
    }
    const ranBy = legByFile.get(file) ?? [];
    let verdict = OK;
    let detail = '';
    if (d.kind === LEG) {
      if (ranBy.length === 0) {
        verdict = MISMATCHED;
        detail = `disposed ${LEG}, and no leg in ${EXIT_GATE} names it`;
      } else detail = `run by the ${ranBy.join(', ')} leg`;
    } else if (d.kind === LIBRARY) {
      // THE CALLER MAY LIVE OUTSIDE THIS DENOMINATOR, and theme.mjs is why. Its only consumer is
      // webui/.impeccable/review/coverage/coverage.mjs — a row's own review script, not a toolbox
      // checker — so requiring the caller to be one of the 21 files here would have reported a
      // library with a real, readable, verifiable caller as having none. The verification does not
      // weaken: the named file must exist and must actually reference this one. What it gives up is
      // the transitive answer, which this check reports as a sentence rather than as a verdict.
      const caller = d.of;
      if (!sources.has(caller)) {
        verdict = MISMATCHED;
        detail = `disposed ${LIBRARY} ${caller}, and no such file could be read`;
      } else if (!(sources.get(caller) ?? '').includes(file.split('/').pop())) {
        verdict = MISMATCHED;
        detail = `disposed ${LIBRARY} ${caller}, and ${caller} does not reference it`;
      } else {
        const callerRunBy = legByFile.get(caller) ?? [];
        const outside = files.includes(caller) ? '' : ' (outside dev/web-console)';
        detail = callerRunBy.length > 0
          ? `used by ${caller}${outside}, which the ${callerRunBy.join(', ')} leg runs`
          : `used by ${caller}${outside}, WHICH NO LEG RUNS — so nothing in the gate exercises this file`;
      }
    } else if (d.kind === TOOL) {
      if (ranBy.length > 0) {
        verdict = MISMATCHED;
        detail = `disposed ${TOOL}, and the ${ranBy.join(', ')} leg runs it — one of the two is wrong`;
      } else if (!d.why) {
        verdict = MISMATCHED;
        detail = `disposed ${TOOL} with no reason written; a blank reason is an absence wearing a label`;
      } else detail = 'no leg names it';
    } else if (d.kind === PENDING) {
      // the only disposition with an expiry date, and the ledger is what reads the clock
      const block = ledger.split(/^\[\[items\]\]\s*$/m).slice(1).find((b) => new RegExp(`^id = "${d.row}"`, 'm').test(b));
      const status = block === undefined ? null : /^status = "([^"]+)"/m.exec(block)?.[1] ?? null;
      if (block === undefined) {
        verdict = MISMATCHED;
        detail = `disposed ${PENDING} ${d.row}, and the ledger has no such row`;
      } else if (status === 'done' || status === 'verified') {
        verdict = MISMATCHED;
        detail = `disposed ${PENDING} ${d.row}, and ${d.row} now reads '${status}' — the row landed, so this ` +
          'checker needs a real disposition rather than a promise';
      } else detail = `${d.row} is building it (${status}); this disposition expires when that row lands`;
    } else if (d.kind === GATE) {
      if (file !== EXIT_GATE) {
        verdict = MISMATCHED;
        detail = `disposed ${GATE}, and the leg list was not parsed out of it`;
      } else detail = `declares ${legs.size} leg(s)`;
    } else {
      verdict = MISMATCHED;
      detail = `unknown disposition '${d.kind}'`;
    }
    rows.push({ file, kind: d.kind, of: d.of ?? null, why: d.why ?? '', verdict, detail });
  }

  // A CLAIM ABOUT A FILE THAT IS GONE is the table rotting quietly, which is how an allowlist ends
  // up describing a tree that no longer exists.
  const stale = Object.keys(dispositions).filter((f) => !files.includes(f))
    .map((file) => ({ file, kind: dispositions[file].kind, verdict: STALE,
      detail: 'dispositioned here and not in dev/web-console any more' }));

  return [...rows, ...stale];
}

// ---------------------------------------------------------------- the selftest

async function selftest() {
  const cases = [];
  const ok = (name, got, want) => cases.push({ name, pass: JSON.stringify(got) === JSON.stringify(want), got, want });
  const verdicts = (rows) => Object.fromEntries(rows.map((r) => [r.file, r.verdict]));

  // --- the pure core. Each case plants ONE condition and asserts the verdict for it.
  const legs = new Map([['alpha', ['exit-gate.mjs', 'a.mjs']], ['beta', ['b.mjs']]]);
  const sources = new Map([['a.mjs', "import x from './lib.mjs'"], ['t.mjs', 'no imports here']]);
  const base = {
    'exit-gate.mjs': { kind: GATE, why: 'the gate' },
    'a.mjs': { kind: LEG, why: 'a leg' },
    'b.mjs': { kind: LEG, why: 'another leg' },
    'lib.mjs': { kind: LIBRARY, of: 'a.mjs', why: 'a library' },
    't.mjs': { kind: TOOL, why: 'a tool' },
  };
  const files = ['a.mjs', 'b.mjs', 'exit-gate.mjs', 'lib.mjs', 't.mjs'];
  const run = (dispositions, f = files) => verdicts(audit({ files: f, dispositions, legs, sources }));

  ok('a tree whose every checker is correctly disposed is all OK', run(base),
    { 'a.mjs': OK, 'b.mjs': OK, 'exit-gate.mjs': OK, 'lib.mjs': OK, 't.mjs': OK });
  // THE BORING CASE. A new file nobody has written a line about must fail BY NAME, and it is the
  // one that gets waved through: a check that only notices a missing disposition on a file it
  // already knows about is the same allowlist one level up.
  ok('a checker in the directory and in no disposition is UNDISPOSITIONED',
    run(base, [...files, 'new.mjs'])['new.mjs'], UNDISPOSITIONED);
  ok('and it does not drag the correctly disposed ones down with it',
    run(base, [...files, 'new.mjs'])['a.mjs'], OK);
  ok('a disposition for a file that is gone is STALE',
    run(base, files.filter((f) => f !== 't.mjs'))['t.mjs'], STALE);
  ok('GATE LEG that no leg runs is MISMATCHED',
    run({ ...base, 'lib.mjs': { kind: LEG, why: 'claims to be a leg' } })['lib.mjs'], MISMATCHED);
  ok('TOOL, NEVER GATES that a leg does run is MISMATCHED',
    run({ ...base, 'b.mjs': { kind: TOOL, why: 'claims to never gate' } })['b.mjs'], MISMATCHED);
  ok('TOOL, NEVER GATES with a blank reason is MISMATCHED',
    run({ ...base, 't.mjs': { kind: TOOL, why: '' } })['t.mjs'], MISMATCHED);
  ok('LIBRARY OF a file that does not reference it is MISMATCHED',
    run({ ...base, 'lib.mjs': { kind: LIBRARY, of: 't.mjs', why: 'wrong caller' } })['lib.mjs'], MISMATCHED);
  ok('LIBRARY OF a caller that could not be read at all is MISMATCHED',
    run({ ...base, 'lib.mjs': { kind: LIBRARY, of: 'nowhere.mjs', why: 'no such caller' } })['lib.mjs'], MISMATCHED);
  // the relaxation, proven: a caller OUTSIDE the denominator is fine when it really references the file
  ok('LIBRARY OF a caller outside the denominator is OK when that caller really references it',
    verdicts(audit({ files, dispositions: { ...base, 'lib.mjs': { kind: LIBRARY, of: 'far/away.mjs', why: 'outside' } },
      legs, sources: new Map([...sources, ['far/away.mjs', "import './lib.mjs'"]]) }))['lib.mjs'], OK);
  ok('and still MISMATCHED when that outside caller does NOT reference it',
    verdicts(audit({ files, dispositions: { ...base, 'lib.mjs': { kind: LIBRARY, of: 'far/away.mjs', why: 'outside' } },
      legs, sources: new Map([...sources, ['far/away.mjs', 'nothing in here']]) }))['lib.mjs'], MISMATCHED);
  ok('THE GATE ITSELF on a file the leg list did not come from is MISMATCHED',
    run({ ...base, 't.mjs': { kind: GATE, why: 'not the gate' } })['t.mjs'], MISMATCHED);
  // a library whose caller no leg runs is NOT a failure — capture.mjs is exactly this — but the
  // check must SAY so, because "nothing in the gate exercises this" is the sentence worth reading
  ok('a library of a tool passes, and says that no leg reaches it',
    audit({ files, dispositions: { ...base, 'lib.mjs': { kind: LIBRARY, of: 't.mjs', why: 'library of a tool' } },
      legs, sources: new Map([...sources, ['t.mjs', "import './lib.mjs'"]]) })
      .find((r) => r.file === 'lib.mjs').detail.includes('WHICH NO LEG RUNS'), true);

  // PENDING is the disposition with an expiry date, so all three of its states are proven
  const ledger = '[[items]]\nid = "M1-99"\nstatus = "in_flight"\n\n[[items]]\nid = "M1-98"\nstatus = "done"\n';
  const withPending = (row) => verdicts(audit({ files: [...files, 'p.mjs'],
    dispositions: { ...base, 'p.mjs': { kind: PENDING, row, why: 'being built' } }, legs, sources, ledger }));
  ok('PENDING on a row that is still in flight is OK', withPending('M1-99')['p.mjs'], OK);
  ok('PENDING on a row that has LANDED is MISMATCHED — the disposition expires', withPending('M1-98')['p.mjs'], MISMATCHED);
  ok('PENDING on a row the ledger does not have is MISMATCHED', withPending('M1-00')['p.mjs'], MISMATCHED);

  // --- law 23: every way this check could read nothing
  ok('a missing directory is DID NOT RUN', guard({ files: null, legs }) !== null, true);
  ok('a directory with no .mjs is DID NOT RUN', guard({ files: [], legs }) !== null, true);
  ok('a tree with no exit-gate.mjs is DID NOT RUN', guard({ files: ['a.mjs'], legs }) !== null, true);
  ok('an exit-gate.mjs whose LEGS array will not parse is DID NOT RUN',
    guard({ files: ['exit-gate.mjs'], legs: null }) !== null, true);
  ok('a healthy tree is NOT refused', guard({ files, legs }), null);
  ok('a PENDING disposition with no ledger to read is DID NOT RUN',
    guard({ files, legs, ledger: null, needsLedger: true }) !== null, true);
  ok('and with a ledger it is not refused', guard({ files, legs, ledger: 'x', needsLedger: true }), null);
  ok('an exit-gate with no LEGS array parses to null, never to an empty answer', legsOf('const X = 1;\n'), null);
  ok('and one with an empty LEGS array is null too, not a clean zero', legsOf('const LEGS = [\n];\n'), null);

  // --- the real exit-gate.mjs, because the parser is only useful against the file it will read
  const gateText = readFileSync(join(DIR, EXIT_GATE), 'utf8');
  const realLegs = legsOf(gateText);
  ok('the real exit-gate.mjs parses to at least one leg', realLegs !== null && realLegs.size > 0, true);
  // the lookahead, kept honest: a path renamed OUT of the gate must stop counting as named by it.
  // Without the lookahead this returns ['a.mjs'] and a leg that no longer runs a.mjs still claims to.
  // the leg still PARSES — it exists and names nothing — so the assertion is on the paths it
  // claims, not on legsOf returning null. Getting this wrong once is how an over-strict assertion
  // gets "fixed" by weakening the code it was pointed at.
  ok('a path with a suffix after .mjs is NOT read as naming that file',
    [...(legsOf("const LEGS = [\n{ name: 'x', run: 'dev/web-console/a.mjs.disabled' },\n];\n") ?? new Map()).values()].flat(), []);
  ok('and the unsuffixed form still is',
    [...(legsOf("const LEGS = [\n{ name: 'x', run: 'dev/web-console/a.mjs' },\n];\n") ?? new Map()).values()].flat(), ['a.mjs']);
  ok('and the scan leg is found to run scan.mjs', (realLegs?.get('scan') ?? []).includes('scan.mjs'), true);

  // --- END TO END, and this is the case that proves the denominator is the DIRECTORY. Everything
  // above runs on lists this function wrote; a plant on disk is the only thing that proves the real
  // run enumerates the tree rather than a list inside itself.
  const probe = join(DIR, '.gate-coverage-selftest-probe.mjs');
  const { writeFileSync, rmSync } = await import('node:fs');
  const { spawnSync } = await import('node:child_process');
  const self = fileURLToPath(import.meta.url);
  const live = () => {
    const r = spawnSync(process.execPath, [self], { cwd: ROOT, encoding: 'utf8' });
    return { status: r.status ?? 1, out: `${r.stdout ?? ''}${r.stderr ?? ''}` };
  };
  const clean = live();
  ok('the tree as it stands comes back clean — the positive control, without which every red above proves only that this check always fails', clean.status, 0);
  try {
    writeFileSync(probe, '// planted by the gate-coverage selftest\n');
    const planted = live();
    ok('a NEW .mjs dropped into the directory with no disposition turns it RED', planted.status !== 0, true);
    ok('and the red names the file rather than only counting it',
      planted.out.includes('.gate-coverage-selftest-probe.mjs'), true);
  } finally {
    rmSync(probe, { force: true });
  }
  ok('and removing it comes back clean again, so the red was the plant and not the harness', live().status, 0);

  for (const c of cases) {
    console.log(`  ${c.pass ? 'PASS' : 'FAIL'}  ${c.name}${c.pass ? '' : ` — wanted ${JSON.stringify(c.want)}, got ${JSON.stringify(c.got)}`}`);
  }
  const passed = cases.filter((c) => c.pass).length;
  console.log(`\nselftest ${passed}/${cases.length} ${passed === cases.length ? 'PASS' : 'FAIL'}`);
  process.exitCode = passed === cases.length ? 0 : 1;
}

// ---------------------------------------------------------------- main

if (process.argv.includes('--selftest')) {
  await selftest();
} else {
  const files = enumerate(DIR);
  const gateText = files?.includes(EXIT_GATE) ? readFileSync(join(DIR, EXIT_GATE), 'utf8') : '';
  const legs = gateText === '' ? null : legsOf(gateText);
  const refused = guard({ files, legs });
  if (refused !== null) fail(refused);

  const LEDGER = join(ROOT, 'dev/campaigns/web-console.toml');
  const ledger = existsSync(LEDGER) ? readFileSync(LEDGER, 'utf8') : null;
  const needsLedger = Object.values(DISPOSITIONS).some((d) => d.kind === PENDING);
  const refusedLedger = guard({ files, legs, ledger, needsLedger });
  if (refusedLedger !== null) fail(refusedLedger);

  const sources = new Map(files.map((f) => [f, readFileSync(join(DIR, f.split('/').join(sep)), 'utf8')]));
  // a LIBRARY OF claim may name a caller outside dev/web-console; read it from the worktree root so
  // the claim is checked against the real file rather than waved through for being out of reach
  for (const d of Object.values(DISPOSITIONS)) {
    if (d.kind !== LIBRARY || sources.has(d.of)) continue;
    const at = join(ROOT, d.of.split('/').join(sep));
    if (existsSync(at)) sources.set(d.of, readFileSync(at, 'utf8'));
  }
  const rows = audit({ files, dispositions: DISPOSITIONS, legs, sources, ledger: ledger ?? '' });

  const by = (k) => rows.filter((r) => r.kind === k && r.verdict === OK).length;
  console.log(`gate-coverage: ${files.length} checker(s) under dev/web-console, ${legs.size} leg(s) in ${EXIT_GATE}`);
  // EVERY CATEGORY ON THE LINE, including the empty ones. A summary that lists four of five
  // dispositions is the same silence this file is about, one line long: the reader counts the
  // numbers, finds they do not reach the total, and has to go looking for the category that was
  // left out. So the counts are printed from the kinds themselves and they must add up.
  const counts = [LEG, LIBRARY, TOOL, PENDING, GATE].map((k) => `${by(k)} ${k.toLowerCase()}`);
  console.log(`  ${counts.join(' · ')} — ${rows.filter((r) => r.verdict === OK).length} of ${files.length} settled\n`);

  for (const r of [...rows].sort((a, b) => a.file.localeCompare(b.file))) {
    const label = r.kind === LIBRARY ? `${LIBRARY} ${r.of}` : (r.kind ?? '—');
    const line = `  ${r.verdict.padEnd(16)} ${r.file.padEnd(30)} ${label}`;
    if (r.verdict === OK) console.log(`${line}\n${' '.repeat(21)}${r.detail}`);
    else console.error(`${line}\n${' '.repeat(21)}^ ${r.detail}`);
  }

  const bad = rows.filter((r) => r.verdict !== OK);
  if (bad.length > 0) {
    console.error(`\ngate-coverage: ${bad.length} checker(s) the gate says nothing about — ` +
      bad.map((r) => `${r.file} (${r.verdict})`).join(', '));
    console.error('REMEDY: give each one a disposition in gate-coverage.mjs — GATE LEG, LIBRARY OF <file>, ' +
      'or TOOL, NEVER GATES with the reason — or wire it into exit-gate.mjs if it should have been a leg all along.');
  } else {
    console.log('\ngate-coverage: every checker in the directory carries a disposition, and every disposition holds against the tree');
  }
  process.exitCode = bad.length > 0 ? 1 : 0;
}
