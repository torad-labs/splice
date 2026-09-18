#!/usr/bin/env node
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
//   node .dev/web-console/exit-gate.mjs [--only <name,...>] [--json]
//   node .dev/web-console/exit-gate.mjs --selftest

import { execFileSync } from 'node:child_process';
import net from 'node:net';
import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const WEBUI = path.join(ROOT, 'webui');
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

/** Run a command; never throw. Returns {code, out} with stdout and stderr merged. */
function sh(cmd, args, cwd) {
  try {
    const out = execFileSync(cmd, args, { cwd, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], maxBuffer: 64 << 20 });
    return { code: 0, out };
  } catch (e) {
    return { code: e.status ?? 1, out: `${e.stdout ?? ''}${e.stderr ?? ''}${e.message ?? ''}` };
  }
}

function serverUp(port) {
  // A synchronous TCP probe with no dependency: the gate must not need the thing it checks.
  const r = sh('bash', ['-c', `exec 3<>/dev/tcp/localhost/${port}`], ROOT);
  return r.code === 0;
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
    probe: () => sh('npx', ['tsc', '--version'], WEBUI),
    probeProof: /^Version \d+/m,
    run: () => sh('npx', ['tsc', '--noEmit'], WEBUI),
  },
  {
    name: 'lint',
    why: 'FSD boundaries live here, not in tsc',
    probe: () => sh('npx', ['eslint', '--version'], WEBUI),
    probeProof: /^v?\d+\./m,
    run: () => sh('npx', ['eslint', 'src', 'tests'], WEBUI),
  },
  {
    name: 'suite',
    why: 'the world wall is one of these files; a zero-test run is DID NOT RUN, not a pass',
    probe: () => sh('npx', ['vitest', '--version'], WEBUI),
    probeProof: /\d+\./,
    run: () => sh('npx', ['vitest', 'run'], WEBUI),
    // `Tests  12 passed` and `Tests  5 failed | 22 passed` are both evidence the runner ran;
    // requiring the word `passed` immediately after the count made a suite WITH FAILURES read
    // as DID NOT RUN instead of FAILED (M1-23: the runner did its job and the gate mis-filed it)
    proof: /Tests\s+\d+ (?:failed|passed)/,
  },
  {
    name: 'build',
    why: 'dist is what ships; the proof is that index.html is newer than this gate',
    probe: () => ({ code: fs.existsSync(path.join(WEBUI, 'package.json')) ? 0 : 1, out: 'webui/package.json' }),
    probeProof: /package\.json/,
    run: () => sh('npm', ['run', 'build', '-w', 'webui'], ROOT),
    after: (startedAt) => {
      const dist = path.join(WEBUI, 'dist', 'index.html');
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
    // leg is `npx vite build` and NOT `npm run build -w webui`, because that script is
    // tsc-then-vite and a peer's live type error would mask exactly this defect: M1-17's orphan
    // made lightningcss die with "Invalid token in pseudo element" while the npm script never
    // reached vite, and every verify line in the campaign stayed green.
    name: 'bundle',
    why: 'the bundler is the only CSS parser here; a row that touches CSS needs it',
    probe: () => sh('npx', ['vite', '--version'], WEBUI),
    probeProof: /vite\/v?\d+/i,
    run: () => sh('npx', ['vite', 'build'], WEBUI),
    proof: /built in \d+\s*ms|modules transformed/i,
  },
  {
    name: 'fixture-leak',
    why: 'a fixture byte in dist ships sample data to an operator',
    probe: () => ({ code: fs.existsSync(path.join(ROOT, '.dev/web-console/fixture-leak.mjs')) ? 0 : 1, out: 'fixture-leak.mjs' }),
    probeProof: /fixture-leak/,
    run: () => sh('node', ['.dev/web-console/fixture-leak.mjs'], ROOT),
    proof: /fixture/i,
  },
  {
    name: 'scan',
    why: 'the structural walls, through the wrapper that refuses a path they did not read',
    // NOT `ast-grep scan` directly: it prints "ERROR: no such file" and exits 0, and
    // detect.mjs prints "cannot access" and exits 0. scan.mjs is that sentence's fix, and
    // its own file count is the proof the walls saw something.
    probe: () => sh('node', ['.dev/web-console/scan.mjs', '--selftest'], ROOT),
    probeProof: /selftest \d+\/\d+ PASS/,
    run: () => sh('node', ['.dev/web-console/scan.mjs', 'webui/src'], ROOT),
    proof: /scan: \d+ path\(s\), [1-9]\d* file\(s\) read/,
  },
  {
    name: 'comp-check',
    why: "the comp's constants measured on live pages; needs the dev server",
    needsServer: true,
    probe: () => sh('node', ['.dev/web-console/comp-check.mjs', '--list'], ROOT),
    probeProof: /rail/,
    // BOTH FRAMES, and the second one is the whole point. Until 2026-09-18 this leg ran
    // comp-check's hardcoded `FRAMES = [[1536, 1024]]` and look.mjs's identical default, so
    // NOTHING in this gate rendered at a second size — M1-33's coverage finding. The operator's
    // monitors are 3840x2160; every defect the 3840 blind pass found passed all nine legs.
    run: () => {
      const runs = FRAMES.map((f) => sh('node', ['.dev/web-console/comp-check.mjs', '--frame', f], ROOT));
      return { code: runs.some((r) => r.code !== 0) ? 1 : 0, out: runs.map((r) => r.out).join('\n') };
    },
    // The proof is the summary line with a NON-ZERO compared count, once per frame. It used to be
    // /^(?:PASS|FAIL) /m — and comp-check prints no PASS line at all, so that regex could only ever
    // match a FAILURE. The gate would have gone unproven at the exact moment the build went green,
    // which is the one direction none of tonight's other holes pointed in.
    proof: new RegExp(FRAMES.map((f) => `at ${f}[^\\n]*?, [1-9]\\d* compared against the comp`).join('[\\s\\S]*'), 'm'),
    failIf: /^FAIL /m,
    failHint: 'comp-check found a comp constant off on a live page; those belong on the punch list, not in a weakened proof',
  },
  {
    name: 'look',
    why: 'the rendered rule pass and the look gate; needs the dev server',
    needsServer: true,
    probe: () => sh('node', ['.dev/web-console/look.mjs', '--help'], ROOT),
    // was /./ , which matches the usage text and therefore any output at all: a probe that cannot
    // fail. The usage line is the proof that the file runs and still takes a url.
    probeProof: /usage: node dev\/web-console\/look\.mjs '<url>'/,
    // it takes a URL: called with none it prints usage and exits 2, which the old wiring reported
    // as a FAILED look pass rather than as a missing argument
    // Both frames, same reason as comp-check: look.mjs takes --width/--height and defaulted to
    // 1536x1024, so the rendered rule pass had never seen the size the console is used at. The
    // rendered rules that care most about size — clipped-overflow-container, text-occlusion,
    // cramped-padding, line-length — are exactly the ones a single frame cannot exercise.
    run: () => {
      const runs = FRAMES.map((f) => {
        const [w, h] = f.split('x');
        return sh('node', ['.dev/web-console/look.mjs', LOOK_URL, '--width', w, '--height', h], ROOT);
      });
      return { code: runs.some((r) => r.code !== 0) ? 1 : 0, out: runs.map((r, i) => `--- ${FRAMES[i]}\n${r.out}`).join('\n') };
    },
    proof: /look — \S+/,
    failIf: /LOOK: BLOCKED|look-gate\s+FAIL/,
    failHint: 'the look gate found blocking findings on this page; fix them or put them on the punch list',
  },
];

function runLeg(leg) {
  if (leg.needsServer && !serverUp(PORT)) {
    return { status: DID_NOT_RUN, detail: `no dev server on localhost:${PORT} — npm run dev -w webui -- --port ${PORT} --strictPort` };
  }
  const p = leg.probe();
  if (p.code !== 0 || (leg.probeProof && !leg.probeProof.test(p.out))) {
    return { status: DID_NOT_RUN, detail: `probe failed: ${p.out.trim().split('\n').slice(-2).join(' ').slice(0, 200)}` };
  }
  const startedAt = Date.now();
  const r = leg.run();
  if (leg.proof && !leg.proof.test(r.out)) {
    return { status: DID_NOT_RUN, detail: `exited ${r.code} but produced no evidence it ran (expected ${leg.proof})` };
  }
  if (leg.failIf) {
    const m = leg.failIf.exec(r.out);
    if (m) return { status: FAILED, detail: `exited ${r.code} but its own output reports a failure: ${m[0].trim()}${leg.failHint ? ` — ${leg.failHint}` : ''}` };
  }
  if (r.code !== 0) {
    return { status: FAILED, detail: r.out.trim().split('\n').slice(-12).join('\n') };
  }
  const late = leg.after?.(startedAt);
  if (late) return { status: DID_NOT_RUN, detail: late };
  return { status: PASSED, detail: '' };
}

// --selftest: the gate's own mutation proof. A leg that exits 0 while doing nothing must
// come back DID NOT RUN, and an honest one must come back PASSED. If either verdict is
// wrong the runner cannot be trusted to report the other legs, so it fails closed.
function selftest() {
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
  ];
  let bad = 0;
  for (const c of cases) {
    const got = runLeg(c.leg).status;
    const ok = got === c.want;
    if (!ok) bad++;
    console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${c.label} — wanted ${c.want}, got ${got}`);
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
  const total = cases.length + 1;
  console.log(bad === 0 ? `\nselftest ${total}/${total} PASS` : `\nselftest ${total - bad}/${total}, ${bad} wrong`);
  process.exit(bad === 0 ? 0 : 1);
}

if (has('selftest')) selftest();

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
  results.push({ name: leg.name, why: leg.why, ...runLeg(leg) });
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
