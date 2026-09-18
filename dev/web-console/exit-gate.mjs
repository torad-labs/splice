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
//   node dev/web-console/exit-gate.mjs [--only <name,...>] [--json]
//   node dev/web-console/exit-gate.mjs --selftest

import { execFileSync } from 'node:child_process';
import net from 'node:net';
import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const WEBUI = path.join(ROOT, 'webui');
const PORT = 5173;

const ARGS = process.argv.slice(2);
const has = (f) => ARGS.includes(`--${f}`);
const flag = (f, d) => { const i = ARGS.indexOf(`--${f}`); return i === -1 ? d : ARGS[i + 1]; };

const PASSED = 'PASSED', FAILED = 'FAILED', DID_NOT_RUN = 'DID NOT RUN';

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
    proof: /Tests\s+\d+ passed/,
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
    name: 'fixture-leak',
    why: 'a fixture byte in dist ships sample data to an operator',
    probe: () => ({ code: fs.existsSync(path.join(ROOT, 'dev/web-console/fixture-leak.mjs')) ? 0 : 1, out: 'fixture-leak.mjs' }),
    probeProof: /fixture-leak/,
    run: () => sh('node', ['dev/web-console/fixture-leak.mjs'], ROOT),
    proof: /fixture/i,
  },
  {
    name: 'scan',
    why: 'the structural walls, through the wrapper that refuses a path they did not read',
    // NOT `ast-grep scan` directly: it prints "ERROR: no such file" and exits 0, and
    // detect.mjs prints "cannot access" and exits 0. scan.mjs is that sentence's fix, and
    // its own file count is the proof the walls saw something.
    probe: () => sh('node', ['dev/web-console/scan.mjs', '--selftest'], ROOT),
    probeProof: /selftest \d+\/\d+ PASS/,
    run: () => sh('node', ['dev/web-console/scan.mjs', 'webui/src'], ROOT),
    proof: /scan: \d+ path\(s\), [1-9]\d* file\(s\) read/,
  },
  {
    name: 'comp-check',
    why: "the comp's constants measured on live pages; needs the dev server",
    needsServer: true,
    probe: () => sh('node', ['dev/web-console/comp-check.mjs', '--list'], ROOT),
    probeProof: /rail/,
    run: () => sh('node', ['dev/web-console/comp-check.mjs'], ROOT),
  },
  {
    name: 'look',
    why: 'the rendered rule pass and the look gate; needs the dev server',
    needsServer: true,
    probe: () => sh('node', ['dev/web-console/look.mjs', '--help'], ROOT),
    probeProof: /./,
    run: () => sh('node', ['dev/web-console/look.mjs'], ROOT),
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
