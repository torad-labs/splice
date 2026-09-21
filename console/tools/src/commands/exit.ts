#!/usr/bin/env bun
// exit-gate — the milestone boundary, as a script instead of a remembered checklist.
//
// WHY IT LOOKS LIKE THIS. Campaign law 23 says every instrument must distinguish three
// outcomes and never two — PASSED, FAILED, and DID NOT RUN — and that the third is a
// failure. This milestone produced three instances of the two-outcome kind in one day: a
// rendered detector handed a source directory so sixty rules never ran; a detector that
// writes its failure to stderr and EXITS 0 with an empty finding list when its browser
// engine is missing; and a capture whose fixture name did not exist, so the page rendered
// LIVE data into frames that looked exactly like working captures. Every one of them
// reported green.
//
// So a leg here does not pass because its command exited 0. It passes because we could
// first PROVE the tool is there and runnable (`probe`), and then the check itself came
// back clean. A missing tool, a missing dev server, a silent no-op: DID NOT RUN, which
// exits non-zero with the remedy printed. `--selftest` mutation-proves that claim.
//
// Legs that drive a browser need the dev server the campaign's capture recipe uses:
//   npm run dev -w webui -- --port 5173 --strictPort
// Host must be localhost — vite binds ::1 only and 127.0.0.1 is refused.
//
// M1-76 DISPOSITION — the two ways a check can be decorative, answered for this file.
//   SHAPE ONE, does every FAIL reach the exit code? NOW YES, AND IT DID NOT — in the runner that
//     judges every other leg. runLeg() tested `leg.proof` BEFORE it read `r.code`, so a leg that
//     ran, found a real defect and exited 1 was reported DID NOT RUN whenever its output happened
//     not to satisfy a regex written here. Measured on the comp-check leg: `--only comp-check`
//     returned `DID NOT RUN ... exited 1 but produced no evidence it ran` while comp-check itself
//     printed its summary at BOTH frames and exited 1 on 51 real findings. The exit code decides
//     now; the proof is an additional assertion on a leg that already passed on it, and keeps the
//     one case it was built for — a leg that exits ZERO having done nothing. The verdict then comes
//     through failIf where a leg has one, so the gate names WHICH constant is off instead of
//     reporting that something exited 1. `bad` still drives `process.exit(bad.length ? 1 : 0)`.
//   SHAPE TWO, if every leg threw, what would it print? IT REFUSES, and it did before this row:
//     `legs.length === 0` exits 2 rather than printing `0/0 passed` (a typo in --only must not read
//     as green), a leg whose command cannot start gets `r.status ?? 127` from sh() rather than a
//     null read as 0, and a leg that dies mid-run has a non-zero code which — as of this row — is
//     what its verdict is built on. Clean, and it was clean.
//   AND THE SELFTEST'S OWN CONTROL ARM, which is the finding under the finding: all six runner
//     cases carried a proof that MATCHES whenever the leg exits non-zero, so not one of them could
//     ever enter the branch where the two disagree. A selftest whose control arm cannot fail is
//     why this shipped. Four cases now drive the disagreement deliberately, in both directions.
//
//   bun console/tools exit [--only <name,...>] [--json]
//   bun console/tools exit --selftest

import { run } from '../lib/proc.ts';
import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..', '..', '..');
const CONSOLE = path.join(ROOT, 'console');
/** The console's own CLI (this file's siblings), by verb — the one spelling every leg below uses. */
const tool = (verb, ...args) => sh('bun', [path.join(ROOT, 'console/tools'), verb, ...args], ROOT);
// --port exists so the browser legs' DID NOT RUN path can be exercised deliberately against a
// dead port (M1-23's check) without stopping the dev server every other seat is capturing from.
const PORT = Number(process.argv.includes('--port') ? process.argv[process.argv.indexOf('--port') + 1] : 5173);

const ARGS = process.argv.slice(2);
const has = (f) => ARGS.includes(`--${f}`);
const flag = (f, d) => { const i = ARGS.indexOf(`--${f}`); return i === -1 ? d : ARGS[i + 1]; };

const PASSED = 'PASSED', FAILED = 'FAILED', DID_NOT_RUN = 'DID NOT RUN';

/** The page the look leg reads: the campaign's own comp-of-record surface. */
const LOOK_URL = 'http://localhost:5173/#/teams?fixture=hero';

/**
 * The frames every rendered leg runs at. The comp's own, and the operator's.
 *
 * Added 2026-09-18 after M1-33 measured what was missing: `comp-check.mjs` carried a hardcoded
 * `FRAMES = [[1536, 1024]]` of which only `[0]` was ever read, and `look.mjs` defaulted to the
 * same size, so NOTHING in this gate had ever rendered at a second viewport. The operator uses
 * 3840x2160 monitors. Every finding of the 3840 blind pass — 13.7% mean paper coverage, 9px of
 * ink on knob names and column headers, 801px gaps in the rule bar — passed all nine legs green.
 *
 * This is the gate's own instance of the campaign's oldest defect: not a check that lied, a check
 * whose DENOMINATOR came from itself. One frame, measured perfectly, forever.
 */
const FRAMES = ['1536x1024', '3840x2160'];
/** comp-check's per-frame summary, with a non-zero compared count so a run that read nothing
 *  cannot pass as a run that read everything. One frame's worth, so the leg can say WHICH. */
const frameSummary = (f) => new RegExp(`at ${f}[^\\n]*?, [1-9]\\d* compared against the comp`);

/** The rendered-rule pass's cells: [frame, theme]. See the `look` leg for why this is a diagonal. */
const LOOKS = [['1536x1024', 'dark'], ['3840x2160', 'dark'], ['3840x2160', 'light']];

/**
 * Run a command; never throw. Returns {code, out} with stdout and stderr merged.
 *
 * spawnSync AND NOT execFileSync, and the difference was a hole rather than a style choice (M1-49).
 * execFileSync RETURNS STDOUT ONLY on success — stderr reached this function exclusively through the
 * catch, from `e.stderr`. So every leg that PASSED was judged on half its output: its `proof` regex
 * could not match anything the tool wrote to stderr, and a `note` hook could not report it. Measured
 * on the wire-check leg the moment it was wired: the leg passed and its note printed the three
 * disposition counts and NOTHING of Level 3a or 3b, which wire-check writes to stderr and which
 * M1-45's ruling promises will be printed loudly on every run. The gate was quietly keeping the half
 * of the output that the tools use for findings, and only when the tool had already failed.
 */
function sh(cmd, args, cwd) {
  // lib/proc.ts carries the rule: both streams on every outcome, and a binary that never started
  // is a named non-zero code rather than a null read as a clean run
  return run([cmd, ...args], cwd);
}

async function serverUp(port) {
  // A TCP probe with no dependency and no shell: the gate must not need the thing it checks.
  try {
    const socket = await Bun.connect({ hostname: '127.0.0.1', port, socket: { data() {}, open(s) { s.end(); } } });
    socket.end();
    return true;
  } catch {
    return false;
  }
}

/**
 * A leg. `probe` proves the tool can run at all and is what separates DID NOT RUN from
 * FAILED — without it, a missing binary and a real defect are the same exit code.
 * `proof` (optional) is a regex the RUN output must match for the run to count as having
 * happened, for tools that can exit 0 while doing nothing.
 * `failIf` (optional) is the third shape, found by M1-23 reading look.mjs: a tool that exits
 * 0 while its own output says FAIL. look.mjs prints `LOOK: BLOCKED` and exits 0, so a
 * code-only leg calls a blocked page a pass; `failIf` reads the verdict the tool printed
 * instead of the one its exit code did not carry.
 */
const LEGS = [
  {
    name: 'typecheck',
    why: 'tree-wide; tsc is silent on success, so the probe is the whole did-it-run proof',
    probe: () => sh('bunx', ['tsc', '--version'], CONSOLE),
    probeProof: /^Version \d+/m,
    run: () => sh('bunx', ['tsc', '--noEmit'], CONSOLE),
  },
  {
    name: 'lint',
    why: 'FSD boundaries live here, not in tsc',
    probe: () => sh('bunx', ['eslint', '--version'], CONSOLE),
    probeProof: /^v?\d+\./m,
    run: () => sh('bunx', ['eslint', 'src', 'tests'], CONSOLE),
  },
  {
    name: 'suite',
    why: 'the world wall is one of these files; a zero-test run is DID NOT RUN, not a pass',
    probe: () => sh('bunx', ['vitest', '--version'], CONSOLE),
    probeProof: /\d+\./,
    run: () => sh('bunx', ['vitest', 'run'], CONSOLE),
    // `Tests  12 passed` and `Tests  5 failed | 22 passed` are both evidence the runner ran;
    // requiring the word `passed` immediately after the count made a suite WITH FAILURES read
    // as DID NOT RUN instead of FAILED (M1-23: the runner did its job and the gate mis-filed it)
    proof: /Tests\s+\d+ (?:failed|passed)/,
  },
  {
    name: 'build',
    why: 'dist is what ships; the proof is that index.html is newer than this gate',
    probe: () => ({ code: fs.existsSync(path.join(CONSOLE, 'package.json')) ? 0 : 1, out: 'console/package.json' }),
    probeProof: /package\.json/,
    run: () => sh('bun', ['run', 'build'], CONSOLE),
    after: (startedAt) => {
      const dist = path.join(CONSOLE, 'dist', 'index.html');
      if (!fs.existsSync(dist)) return 'no dist/index.html after a build that exited 0';
      if (fs.statSync(dist).mtimeMs < startedAt) return 'dist/index.html is older than this run: the build did not rewrite it';
      return null;
    },
  },
  {
    // LAW 25 (M1-23, from M1-17 shipping an orphaned declaration): A ROW THAT TOUCHES CSS RUNS A
    // BUILD LEG. The other legs cannot see CSS at all -- eslint does not lint it here, tsc never
    // reads it, vitest renders to a string with no stylesheet, ast-grep's rules are TS, and
    // detect reads it as text -- so the bundler is the only real CSS parser in this repo. This
    // leg is `bunx --bun vite build` and NOT the package's build script, because that script is
    // tsc-then-vite and a peer's live type error would mask exactly this defect: M1-17's orphan
    // made lightningcss die with "Invalid token in pseudo element" while the npm script never
    // reached vite, and every verify line in the campaign stayed green.
    name: 'bundle',
    why: 'the bundler is the only CSS parser here; a row that touches CSS needs it',
    probe: () => sh('bunx', ['--bun', 'vite', '--version'], CONSOLE),
    probeProof: /vite\/v?\d+/i,
    run: () => sh('bunx', ['--bun', 'vite', 'build'], CONSOLE),
    proof: /built in \d+\s*ms|modules transformed/i,
  },
  {
    name: 'fixture-leak',
    why: 'a fixture byte in dist ships sample data to an operator',
    probe: () => ({ code: fs.existsSync(path.join(ROOT, 'console/tools/src/commands/leak.ts')) ? 0 : 1, out: 'commands/leak.ts' }),
    probeProof: /leak/,
    run: () => tool('leak'),
    proof: /fixture/i,
  },
  {
    name: 'law-check',
    why: 'laws 25 and 27 over the whole ledger — the one property no other leg observes',
    // REPORTS, DOES NOT GATE. law-check.mjs exits 1 on the violations that are history (M1-08/09/10
    // carry the negation shape and are done; the CSS rows predate law 25), and whether that blocks
    // the milestone is the orchestrator's call — so this leg runs --report, which always exits 0 and
    // prints the violations and their row ids. The proof requires the row count, so a run that read
    // no ledger is DID NOT RUN rather than a clean report.
    probe: () => sh('node', ['.dev/web-console/law-check.mjs', '--selftest'], ROOT),
    probeProof: /selftest \d+\/\d+ PASS/,
    run: () => sh('node', ['.dev/web-console/law-check.mjs', '--report'], ROOT),
    proof: /law-check: [1-9]\d* row\(s\) read from the ledger/,
    // The leg passes and still says the number, because the number is the deliverable.
    note: (out) => out.split('\n').filter((line) => /dispositions|narrow reading|done rows|law 2[57]:/.test(line)).map((l) => l.trim()).join('\n'),
  },
  {
    name: 'scan',
    why: 'the structural walls, through the wrapper that refuses a path they did not read',
    // NOT `ast-grep scan` directly: it prints "ERROR: no such file" and exits 0, and
    // detect.mjs prints "cannot access" and exits 0. scan.mjs is that sentence's fix, and
    // its own file count is the proof the walls saw something.
    probe: () => tool('scan', '--selftest'),
    probeProof: /selftest \d+\/\d+ PASS/,
    run: () => tool('scan', 'console/src'),
    proof: /scan: \d+ path\(s\), [1-9]\d* file\(s\) read/,
  },
  {
    // M1-49, and the finding that cut the row: this file was in NO gate. Its own exit rule cites
    // "the milestone re-run" as what catches a Level 3b census entry the day its route lands, and
    // that re-run did not exist — a justification for not gating that depended on a gate.
    name: 'wire-check',
    why: 'what the console declares against what the daemon emits; the milestone re-run its own exit rule names',
    probe: () => sh('node', ['.dev/web-console/wire-check.mjs', '--selftest'], ROOT),
    probeProof: /selftest \d+\/\d+ PASS/,
    run: () => sh('node', ['.dev/web-console/wire-check.mjs'], ROOT),
    // THE PROOF IS THE HEADER, NOT A FINDING. M1-37's own verify leg greped for the route-failure
    // line — it asserted the presence of a DEFECT, so it went false the moment M1-41 fixed the eight
    // routes, and the leg has been exit 1 ever since against a check that was working perfectly. A
    // leg has to survive its own success, so this matches the line the check prints on every run
    // whatever it finds, with both counts non-zero so a run that read nothing is DID NOT RUN.
    proof: /wire-check: [1-9]\d* entity types file\(s\), [1-9]\d* declared field\(s\)/,
    // the census does not gate (M1-45) — so the leg SAYS it, every run, passing or not
    note: (out) => out.split('\n').filter((l) => /LEVEL 3[ab] —|PRINTED, NOT GATED|^  (MATCHES|MISMATCHED|UNDISPOSITIONED)/.test(l)).map((l) => l.trim()).join('\n'),
  },
  {
    // M1-49. The gate's own denominator: every .mjs under .dev/web-console carries a disposition, and
    // each disposition is verified against the tree. Without this leg the table is a document, and a
    // document is what let wire-check.mjs sit outside the gate for a night.
    name: 'gate-coverage',
    why: 'every checker under console/tools/src is a leg, a library, a tool, or a named pending row — and nothing else',
    probe: () => tool('coverage', '--selftest'),
    probeProof: /selftest \d+\/\d+ PASS/,
    run: () => tool('coverage'),
    proof: /gate-coverage: [1-9]\d* checker\(s\) under console\/tools\/src, [1-9]\d* leg\(s\)/,
  },
  {
    name: 'comp-check',
    why: "the comp's constants measured on live pages; needs the dev server",
    needsServer: true,
    probe: () => tool('comp', '--list'),
    probeProof: /rail/,
    // BOTH FRAMES, and the second one is the whole point. Until 2026-09-18 this leg ran
    // comp-check's hardcoded `FRAMES = [[1536, 1024]]` and look.mjs's identical default, so
    // NOTHING in this gate rendered at a second size — M1-33's coverage finding. The operator's
    // monitors are 3840x2160; every defect the 3840 blind pass found passed all nine legs.
    run: () => {
      const runs = FRAMES.map((f) => [f, tool('comp', '--frame', f)]);
      // NAME THE FRAME THAT WENT SILENT. The proof below spans BOTH frames, so one missing summary
      // read as "produced no evidence it ran" for the whole leg with no way to tell which frame was
      // quiet — the leg failing in the exact way it exists to catch. Measured 2026-09-18 12:20: both
      // frames summarise correctly when run by hand and the concatenation matches the proof, so a
      // gate run that reported no evidence had one silent frame and could not say so.
      const silent = runs.filter(([f, r]) => !frameSummary(f).test(r.out));
      const audit = silent.map(([f, r]) =>
        `comp-check: ${f} produced NO summary line (exit ${r.code}) — last line: ${r.out.trim().split('\n').pop() || '(no output at all)'}`);
      return { code: runs.some(([, r]) => r.code !== 0) || silent.length > 0 ? 1 : 0, out: [...runs.map(([, r]) => r.out), ...audit].join('\n') };
    },
    // The proof is the summary line with a NON-ZERO compared count, once per frame. It used to be
    // /^(?:PASS|FAIL) /m — and comp-check prints no PASS line at all, so that regex could only ever
    // match a FAILURE. The gate would have gone unproven at the exact moment the build went green,
    // which is the one direction none of tonight's other holes pointed in.
    proof: new RegExp(FRAMES.map((f) => frameSummary(f).source).join('[\\s\\S]*'), 'm'),
    // A silent frame is now a FAILURE that names itself, not an unattributable DID NOT RUN.
    failIf: /^FAIL |produced NO summary line/m,
    failHint: 'comp-check found a comp constant off on a live page; those belong on the punch list, not in a weakened proof',
  },
  {
    name: 'look',
    why: 'the rendered rule pass and the look gate; needs the dev server',
    needsServer: true,
    probe: () => tool('look', '--help'),
    // was /./ , which matches the usage text and therefore any output at all: a probe that cannot
    // fail. The usage line is the proof that the file runs and still takes a url.
    probeProof: /usage: bun console\/tools look '<url>'/,
    // it takes a URL: called with none it prints usage and exits 2, which the old wiring reported
    // as a FAILED look pass rather than as a missing argument
    // THREE CELLS, and the shape is deliberate: a diagonal, not a full cross.
    //
    //   dark  1536x1024   the historical baseline, so a regression against every past run shows
    //   dark  3840x2160   the size the console is actually used at
    //   light 3840x2160   the room no automatic check in this campaign has ever rendered
    //
    // Two axes were missing, both found by M1-33. look.mjs defaulted to 1536x1024 with no way to
    // ask for another size, and NOTHING here seeded a theme — so the rendered rules that care
    // about size (clipped-overflow-container, text-occlusion, cramped-padding, line-length) only
    // ever saw one frame, and the rules that care about tone (low-contrast, design-system-color)
    // only ever saw one room. Light is the room with 9.2 L of headroom above its paper against
    // dark's 216.7, where three of twelve materials clip and the ghost that recedes on dark
    // advances on light.
    //
    // light-at-1536 is the omitted cell. The light defects measured so far are tonal rather than
    // size-dependent, so the 3840 light run reaches them; if one ever turns out to need the comp
    // frame specifically, this is the cell to add and this comment is the reason it was not here.
    run: () => {
      const runs = LOOKS.map(([frame, theme]) => {
        const [w, h] = frame.split('x');
        return tool('look', LOOK_URL, '--width', w, '--height', h, '--theme', theme);
      });
      return { code: runs.some((r) => r.code !== 0) ? 1 : 0, out: runs.map((r, i) => `--- ${LOOKS[i].join(' ')}\n${r.out}`).join('\n') };
    },
    // One headline per cell, each naming its own frame and room. A single /look — \S+/ would have
    // been satisfied by one run out of three, which is how a gate reports three passes over one.
    proof: new RegExp(LOOKS.map(([f, t]) => `look — [^\\n]*${f} ${t}`).join('[\\s\\S]*'), 'm'),
    failIf: /LOOK: BLOCKED|look-gate\s+FAIL/,
    failHint: 'the look gate found blocking findings on this page; fix them or put them on the punch list',
  },
  {
    // LAST ON PURPOSE, AND THE POSITION IS THE POINT (M1-49). This leg asks whether every row that
    // reads `done` has its receipted bytes reachable from HEAD. That question only has one meaning
    // at the MILESTONE BOUNDARY, after the orchestrator has committed the milestone's rows: a red
    // here says a row claims a landing it does not have. Run mid-milestone it says something else
    // entirely — that the orchestrator has not committed yet, which is not a defect but the normal
    // state of a campaign in flight. Placing it after every other leg keeps the gate's own ordering
    // honest about that: everything above judges the TREE, this one judges the HISTORY, and history
    // is the last thing to be true.
    name: 'landed',
    why: 'every done row\'s receipted bytes reachable from HEAD — the row that claims a landing it does not have',
    probe: () => sh('node', ['.dev/web-console/landed.mjs', '--selftest'], ROOT),
    probeProof: /selftest \d+\/\d+ PASS/,
    run: () => sh('node', ['.dev/web-console/landed.mjs'], ROOT),
    // rows read and objects walked both non-zero: a run against an empty ledger or an unborn
    // repository would otherwise report a clean history it never looked at
    proof: /landed: [1-9]\d* row\(s\) read from .+, \d+ receipted file\(s\) checked against [1-9]\d* object\(s\)/,
  },
];

async function runLeg(leg) {
  if (leg.needsServer && !(await serverUp(PORT))) {
    return { status: DID_NOT_RUN, detail: `no dev server on localhost:${PORT} — bun run --cwd console dev -- --port ${PORT} --strictPort` };
  }
  const p = leg.probe();
  if (p.code !== 0 || (leg.probeProof && !leg.probeProof.test(p.out))) {
    return { status: DID_NOT_RUN, detail: `probe failed: ${p.out.trim().split('\n').slice(-2).join(' ').slice(0, 200)}` };
  }
  const startedAt = Date.now();
  const r = leg.run();
  // THE EXIT CODE DECIDES; THE PROOF IS AN ADDITIONAL ASSERTION ON A LEG THAT ALREADY PASSED ON IT.
  //
  // This tested `leg.proof` FIRST and returned DID NOT RUN before it ever read `r.code`, so a leg
  // that ran, found a real defect and exited 1 was filed as a leg that never ran — whenever its
  // output happened not to satisfy a regex written here. Measured 2026-09-18 on the comp-check leg:
  // `exit-gate.mjs --only comp-check` returned `DID NOT RUN comp-check / exited 1 but produced no
  // evidence it ran`, while comp-check itself printed its summary at BOTH frames and exited 1 on 51
  // real findings. The gate could not tell "the check found nothing" from "the check found
  // something" — the mirror image of the mislabel the probe was added in M1-59 to prevent, produced
  // by the probe itself. This row's own subject, in the runner that judges every other leg: a check
  // whose verdict is decorative, because the evidence that outranked it was a regex over prose.
  //
  // A NON-ZERO EXIT IS THE STRONGEST EVIDENCE A LEG RAN. A process that dies before printing its
  // summary still has an exit code, so an exit code and an output that disagree are settled by the
  // exit code (claude-splice-main's ladder classifies every leg this way and has never carried this
  // hole — `if "${@:2}"; then ✓ else ✗`, no output parsing anywhere). The proof keeps the one job
  // it is the right tool for and the one it was built for: catching a leg that exits ZERO having
  // done nothing. An unproven FAILURE is still a failure, and it says so — the proof miss rides
  // along as detail on the FAILED verdict instead of replacing it.
  const unproven = leg.proof !== undefined && !leg.proof.test(r.out);
  const alsoUnproven = unproven ? `\n(and it produced no evidence it ran: expected ${leg.proof})` : '';
  if (leg.failIf) {
    const m = leg.failIf.exec(r.out);
    if (m) return { status: FAILED, detail: `exited ${r.code} but its own output reports a failure: ${m[0].trim()}${leg.failHint ? ` — ${leg.failHint}` : ''}${alsoUnproven}` };
  }
  if (r.code !== 0) {
    return { status: FAILED, detail: `${r.out.trim().split('\n').slice(-12).join('\n')}${alsoUnproven}` };
  }
  if (unproven) {
    return { status: DID_NOT_RUN, detail: `exited 0 and produced no evidence it ran (expected ${leg.proof})` };
  }
  const late = leg.after?.(startedAt);
  if (late) return { status: DID_NOT_RUN, detail: late };
  // A REPORTING LEG has to be able to say something while passing. Until this hook existed a leg
  // could only speak by failing or by going DID NOT RUN, so a leg whose whole job is to surface a
  // count for the orchestrator (law-check, M1-42) printed nothing at all: it was green and mute.
  return { status: PASSED, detail: leg.note ? leg.note(r.out) : '' };
}

// --selftest: the gate's own mutation proof. A leg that exits 0 while doing nothing must
// come back DID NOT RUN, and an honest one must come back PASSED. If either verdict is
// wrong the runner cannot be trusted to report the other legs, so it fails closed.
async function selftest() {
  const cases = [
    { label: 'a silent no-op is DID NOT RUN', want: DID_NOT_RUN,
      leg: { name: 't', probe: () => ({ code: 0, out: 'ok' }), probeProof: /ok/, run: () => ({ code: 0, out: '' }), proof: /ran/ } },
    { label: 'a missing tool is DID NOT RUN, never FAILED', want: DID_NOT_RUN,
      leg: { name: 't', probe: () => ({ code: 127, out: 'not found' }), probeProof: /\d/, run: () => ({ code: 0, out: 'ran' }) } },
    { label: 'a real defect is FAILED', want: FAILED,
      leg: { name: 't', probe: () => ({ code: 0, out: 'ok' }), probeProof: /ok/, run: () => ({ code: 1, out: 'ran: 3 errors' }), proof: /ran/ } },
    { label: 'a clean run is PASSED', want: PASSED,
      leg: { name: 't', probe: () => ({ code: 0, out: 'ok' }), probeProof: /ok/, run: () => ({ code: 0, out: 'ran' }), proof: /ran/ } },
    { label: 'a tool that exits 0 while its output reports failure is FAILED', want: FAILED,
      leg: { name: 't', probe: () => ({ code: 0, out: 'ok' }), probeProof: /ok/, run: () => ({ code: 0, out: 'ran\nLOOK: BLOCKED (1)' }), proof: /ran/, failIf: /LOOK: BLOCKED/ } },
    { label: 'a stale artifact is DID NOT RUN', want: DID_NOT_RUN,
      leg: { name: 't', probe: () => ({ code: 0, out: 'ok' }), probeProof: /ok/, run: () => ({ code: 0, out: 'ran' }), proof: /ran/, after: () => 'artifact older than the run' } },
    // ---- THE ORDERING (M1-76). Every case above this line has a proof that MATCHES whenever the
    // leg exits non-zero, so not one of them ever reached the branch where the two disagree — which
    // is why the runner shipped for a month filing real failures as did-not-runs. These four drive
    // the disagreement deliberately, in both directions.
    { label: 'a REAL DEFECT whose output misses the proof is FAILED, never DID NOT RUN', want: FAILED,
      detail: /produced no evidence it ran/,
      leg: { name: 't', probe: () => ({ code: 0, out: 'ok' }), probeProof: /ok/, run: () => ({ code: 1, out: 'died before its summary' }), proof: /ran/ } },
    { label: 'a leg that exits 0 with no evidence is STILL DID NOT RUN (the proof keeps its job)', want: DID_NOT_RUN,
      detail: /exited 0 and produced no evidence/,
      leg: { name: 't', probe: () => ({ code: 0, out: 'ok' }), probeProof: /ok/, run: () => ({ code: 0, out: 'quiet' }), proof: /ran/ } },
    { label: 'failIf still names the output on a leg that ALSO exits non-zero and misses the proof', want: FAILED,
      detail: /its own output reports a failure: LOOK: BLOCKED/,
      leg: { name: 't', probe: () => ({ code: 0, out: 'ok' }), probeProof: /ok/, run: () => ({ code: 1, out: 'LOOK: BLOCKED (1)' }), proof: /ran/, failIf: /LOOK: BLOCKED/ } },
    { label: 'a leg with NO proof at all still fails on its exit code', want: FAILED,
      leg: { name: 't', probe: () => ({ code: 0, out: 'ok' }), probeProof: /ok/, run: () => ({ code: 1, out: 'anything' }) } },
  ];
  let bad = 0;
  for (const c of cases) {
    const verdict = await runLeg(c.leg);
    // THE DETAIL IS PART OF THE VERDICT, not decoration. A FAILED that says nothing about WHY is
    // what sends the next seat to the wrong file, and the ordering fix above turns a proof miss
    // into detail rather than a verdict — so the detail is what proves it was not simply dropped.
    const detailOk = c.detail === undefined || c.detail.test(verdict.detail ?? '');
    const ok = verdict.status === c.want && detailOk;
    if (!ok) bad++;
    console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${c.label} — wanted ${c.want}, got ${verdict.status}`
      + (detailOk ? '' : ` (detail did not match ${c.detail}: ${JSON.stringify((verdict.detail ?? '').slice(0, 90))})`));
  }
  // THE LEGS ARRAY ITSELF, which nothing here had ever looked at. Every case above drives runLeg
  // with a HAND-WRITTEN leg, so a malformed entry in the real LEGS — one missing `run`, say —
  // survived a 7/7 green selftest and then threw `leg.run is not a function` on the first real
  // invocation. Measured 2026-09-18 while wiring M1-49's three legs: that is exactly what happened,
  // to the leg being added, and the row's own verify chain could not see it because --selftest never
  // touches this array. A selftest that only exercises synthetic inputs proves the runner and says
  // nothing about what it will be asked to run.
  for (const leg of LEGS) {
    const missing = ['name', 'why', 'probe', 'run'].filter((k) => leg[k] === undefined);
    const shaped = missing.length === 0 && typeof leg.probe === 'function' && typeof leg.run === 'function';
    if (!shaped) bad++;
    console.log(`  ${shaped ? 'PASS' : 'FAIL'}  leg ${leg.name ?? '(unnamed)'} is fully formed` +
      (shaped ? '' : ` — missing or not callable: ${missing.join(', ') || 'probe/run not functions'}`));
  }

  // The vacuous pass, proven rather than asserted: this gate DID exit 0 on "0/0 passed"
  // until 2026-09-18, found by reading a peer campaign's audit of its own thirteen
  // checkers. A gate with nothing to run is the one case it cannot report as green.
  {
    const r = sh(process.execPath, [fileURLToPath(import.meta.url), '--only', '__no_such_leg__'], ROOT);
    const ok = r.code !== 0;
    if (!ok) bad++;
    console.log(`  ${ok ? 'PASS' : 'FAIL'}  an empty leg set is not a pass — wanted a non-zero exit, got ${r.code}`);
  }
  const total = cases.length + 1 + LEGS.length;
  console.log(bad === 0 ? `\nselftest ${total}/${total} PASS` : `\nselftest ${total - bad}/${total}, ${bad} wrong`);
  process.exit(bad === 0 ? 0 : 1);
}

if (has('selftest')) await selftest();

const only = flag('only', null)?.split(',').map(s => s.trim());
const legs = only ? LEGS.filter(l => only.includes(l.name)) : LEGS;
// FAIL CLOSED ON AN EMPTY LEG SET. Without this the gate prints "0/0 passed" and exits 0,
// which is a vacuous pass — the same defect as a check whose denominator came from the list
// it is checking, wearing the gate's own uniform. A typo in --only must not read as green.
if (legs.length === 0) {
  console.error(only
    ? `REFUSED: --only ${only.join(',')} selected no legs. Known: ${LEGS.map(l => l.name).join(', ')}`
    : 'REFUSED: no legs are defined; a gate with nothing to run is not a passing gate');
  process.exit(2);
}
const results = [];
for (const leg of legs) {
  process.stderr.write(`  … ${leg.name}\n`);
  results.push({ name: leg.name, why: leg.why, ...(await runLeg(leg)) });
}

if (has('json')) {
  console.log(JSON.stringify(results, null, 2));
} else {
  console.log('');
  for (const r of results) {
    console.log(`${r.status.padEnd(11)} ${r.name.padEnd(13)} ${r.why}`);
    if (r.detail) console.log(r.detail.split('\n').map(l => `            ${l}`).join('\n'));
  }
}
const bad = results.filter(r => r.status !== PASSED);
console.log(`\nm1 exit gate: ${results.length - bad.length}/${results.length} passed` +
  (bad.length ? ` — ${bad.map(b => `${b.name} ${b.status}`).join(', ')}` : ''));
process.exit(bad.length ? 1 : 0);
