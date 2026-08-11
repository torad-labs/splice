#!/usr/bin/env node
/**
 * dev/campaigns/proxy-hardening/oracle/capture.mjs — FREEZE THE MIGRATION ORACLE.
 *
 * INOPERABLE SINCE 2026-08-10, BY DESIGN. This script drives `server/`, and P8-CUT deleted that
 * tree. It is KEPT, not removed, because it is the PROVENANCE of the 11 fixtures: it records how
 * they were produced from a known-good implementation that no longer exists, which is the only
 * reason `replay.mjs` can claim they were "not written to flatter the Kotlin port". Running it
 * now exits 2 with that explanation rather than a confusing module-not-found. The fixtures are
 * frozen; re-capturing would require restoring server/ from git history first.
 *
 * WHAT  Drives the LEGACY Node stack (server/) through every scenario its own mock upstream
 *       defines, and records BOTH wire directions byte-exactly into dev/campaigns/proxy-hardening/oracle/fixtures/:
 *
 *           client request ──▶ [ server/ proxy ] ──▶ upstream request   (recorded)
 *           canned upstream SSE ──▶ [ server/ proxy ] ──▶ client SSE    (recorded)
 *
 *       Those two recordings ARE the oracle. They say what a KNOWN-GOOD implementation of this
 *       protocol does, and they were not written to flatter the Kotlin port.
 *
 * WHY NOW (the whole point)  Bun can vendor Node's test suite forever — Node is an independent,
 *       living project. splice's reference implementation is OURS and is marked legacy
 *       (README: "server/ ... kept runnable during cutover, no longer the documented entry
 *       point"). One `git rm` deletes this oracle permanently. Today it is GREEN:
 *       `cd server && node --test` => 104/104 pass in ~3s. Capture it while that is still true.
 *
 * THE BUN DISCIPLINE, kept literally:
 *   - the mock upstream is VENDORED BYTE-IDENTICAL out of server/test/codex-proxy.test.mjs
 *     (extracted between stable markers, never retyped), and its sha256 is recorded in the
 *     manifest so drift or tampering is mechanically detectable — Bun verifies vendored files
 *     with `cmp`; this is the same control.
 *   - fixtures are recorded, never authored. An expectation nobody observed is not an oracle.
 *   - a scenario that is captured but not yet replayed against the Kotlin gateway "wasn't counted
 *     and wasn't protected against regression" (bun#34441) — dev/campaigns/proxy-hardening/oracle/expectations.toml is
 *     the honest list of what is not yet proven.
 *
 * RUN   node dev/campaigns/proxy-hardening/oracle/capture.mjs            (writes fixtures + manifest)
 *       node dev/campaigns/proxy-hardening/oracle/capture.mjs --check     (re-capture to temp, diff vs committed;
 *                                                                         nonzero on ANY drift — see compareAgainstCommitted)
 *
 * NOT A TEST. This produces the oracle; dev/campaigns/proxy-hardening/oracle/replay is what grades against it.
 */

import http from 'node:http';
import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync, rmSync } from 'node:fs';
import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { once } from 'node:events';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '../../../..');
const SOURCE = join(ROOT, 'server/test/codex-proxy.test.mjs');

// The Node tree this script drives was deleted on 2026-08-10 (P8-CUT). Fail with the reason.
if (!existsSync(join(ROOT, 'server/src/codex-proxy.mjs'))) {
  console.error(
    'oracle:capture is INOPERABLE — server/ was deleted on 2026-08-10 (P8-CUT).\n' +
    'The 11 fixtures it produced are frozen and still verified by `npm run oracle:replay`.\n' +
    'This file is kept as their provenance. To re-capture, restore server/ from git history first.',
  );
  process.exit(2);
}
const FIXTURES = join(HERE, 'fixtures');

// The mock region, delimited by markers that have been stable across the port.
const START = "import http from 'node:http';";
const END = "mock.listen(0, '127.0.0.1');";

/** Every scenario the vendored mock branches on, plus the implicit default. */
const SCENARIOS = [
  'basic', 'multipart', 'toolcall', 'nonstream_tool', 'truncated', 'overflow_sse',
  'prefill', 'replaystream', 'failed', 'bigout', 'compactish',
];
// Deliberately excluded from the frozen corpus, with reasons (never silently dropped):
const EXCLUDED = {
  idle: 'timing-dependent (CLAUDEX_STREAM_IDLE_MS) — wall-clock races make a byte-exact fixture unstable',
  drip: 'timing-dependent chunk pacing — same reason as idle',
  refresh: 'mutates auth state across runs (single-flight 401 refresh); needs its own stateful fixture shape',
};

function extractMock() {
  const src = readFileSync(SOURCE, 'utf8');
  const a = src.indexOf(START);
  const b = src.indexOf(END);
  if (a < 0 || b < 0 || b <= a) {
    throw new Error(`capture: mock markers not found in ${SOURCE} — the oracle source moved; fix the markers, do not guess`);
  }
  const region = src.slice(a, b);
  return { region, sha256: createHash('sha256').update(region).digest('hex') };
}

async function bootOracle(tmp) {
  const { region, sha256 } = extractMock();
  const modPath = join(tmp, 'vendored_mock.mjs');
  writeFileSync(modPath, region + `
mock.listen(0, '127.0.0.1');
export { mock, upstreamAuths, upstreamBodies, abortedScenarios, AUTH_PATH, stateRoot };
`);
  const m = await import(pathToFileURL(modPath).href);
  await once(m.mock, 'listening');
  const mockPort = m.mock.address().port;

  // Env BEFORE importing the proxy — mirrors server/test/codex-proxy.test.mjs exactly.
  process.env.CODEX_PROXY_TEST = '1';
  process.env.CODEX_AUTH_PATH = m.AUTH_PATH;
  process.env.CLAUDEX_STATE_DIR = join(m.stateRoot, 'state');
  process.env.CHATGPT_API_BASE = `http://127.0.0.1:${mockPort}`;
  process.env.CODEX_OAUTH_TOKEN_URL = `http://127.0.0.1:${mockPort}/oauth/token`;
  process.env.CLAUDEX_STREAM_IDLE_MS = '700';
  process.env.CLAUDEX_UPSTREAM_RETRIES = '2';
  process.env.CLAUDEX_SHOW_REASONING = 'text';
  process.env.CLAUDEX_REASONING_EFFORT = 'high';
  process.env.CLAUDEX_REASONING_SUMMARY = 'detailed';
  process.env.CLAUDEX_REPLAY_REASONING = '1';
  delete process.env.CLAUDEX_MAX_INFLIGHT;

  const proxy = await import(pathToFileURL(join(ROOT, 'server/src/codex-proxy.mjs')).href);
  const server = proxy.createServer();
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  return { mockMod: m, server, proxyPort: server.address().port, mockSha: sha256 };
}

/**
 * CANONICALIZATION — deliberately ONE rule, declared in the manifest.
 *
 * Measured 2026-07-26 by diffing two independent captures of all 11 scenarios: the ONLY byte that
 * moves between runs is the Anthropic message id, minted as `msg_<epoch_ms>`. Everything else —
 * event ordering, block indices, usage numbers, mirror text — is already byte-stable.
 *
 * The rule stays narrow ON PURPOSE. A broad normalizer (strip all digits, sort keys, ignore
 * whitespace) would make the oracle pass on drift it should catch. Anything added here must be
 * justified by an observed diff, never by anticipation.
 */
const CANON_RULES = [
  { name: 'message-id', pattern: 'msg_<digits> -> msg_CANON', re: /msg_\d+/g, to: 'msg_CANON' },
];

function canonicalize(text) {
  return CANON_RULES.reduce((t, r) => t.replace(r.re, r.to), text);
}

function clientRequest(scenario) {
  return {
    model: 'gpt-5-codex',
    max_tokens: 1024,
    stream: true,
    system: `SCENARIO:${scenario}`,
    messages: [{ role: 'user', content: 'Say the answer.' }],
  };
}

function post(port, body) {
  return new Promise((res, rej) => {
    const req = http.request(
      { host: '127.0.0.1', port, path: '/v1/messages', method: 'POST', headers: { 'Content-Type': 'application/json' } },
      (r) => { let t = ''; r.on('data', (c) => { t += c; }); r.on('end', () => res({ status: r.statusCode, sse: t })); },
    );
    req.on('error', rej);
    req.end(JSON.stringify(body));
  });
}

/**
 * THE DRIFT TRIPWIRE — what makes `--check` a gate instead of a re-run.
 *
 * Review 2026-07-27: `--check` used to capture into a temp dir, delete it, and exit on scenario
 * success alone. Nothing ever read the committed fixtures, so `oracle:check` (package.json) and
 * ledger item INF-03's verify command were both vacuous: change behavior in server/ and it still
 * exited 0. A proof that cannot fail is not a proof.
 *
 * Compares three things, because each fails independently:
 *   1. every scenario fixture, BYTE-for-byte (the oracle itself);
 *   2. the vendored mock's sha256 (the mock can drift under a byte-identical fixture set only
 *      until it doesn't — and mockSha is recomputed live on every run, so nothing else sees it);
 *   3. the scenario ROSTER both ways — a fixture the fresh run no longer produces is drift just
 *      as much as one it newly produces.
 * Returns the drift lines; empty means clean.
 */
function compareAgainstCommitted(freshDir, manifest) {
  if (!existsSync(FIXTURES)) return [`no committed fixtures at ${FIXTURES} — run without --check first`];
  const drift = [];

  let committedManifest = null;
  try {
    committedManifest = JSON.parse(readFileSync(join(FIXTURES, '_manifest.json'), 'utf8'));
  } catch (e) {
    drift.push(`_manifest.json unreadable in the committed corpus: ${String(e).slice(0, 120)}`);
  }
  if (committedManifest && committedManifest.mock_region_sha256 !== manifest.mock_region_sha256) {
    drift.push(
      `mock region sha256: committed ${String(committedManifest.mock_region_sha256).slice(0, 16)}… ` +
      `vs live ${manifest.mock_region_sha256.slice(0, 16)}… (server/test/codex-proxy.test.mjs moved)`,
    );
  }

  const fresh = new Set(readdirSync(freshDir).filter((f) => f.endsWith('.json') && f !== '_manifest.json'));
  const committed = new Set(readdirSync(FIXTURES).filter((f) => f.endsWith('.json') && f !== '_manifest.json'));
  for (const f of committed) if (!fresh.has(f)) drift.push(`${f}: committed but the fresh capture did not produce it`);
  for (const f of fresh) if (!committed.has(f)) drift.push(`${f}: captured but not committed`);

  for (const f of [...fresh].filter((x) => committed.has(x)).sort()) {
    const a = readFileSync(join(FIXTURES, f));
    const b = readFileSync(join(freshDir, f));
    if (!a.equals(b)) drift.push(`${f}: BYTES DIFFER (committed ${a.length}B vs fresh ${b.length}B)`);
  }
  return drift;
}

async function main() {
  const check = process.argv.includes('--check');
  const tmp = mkdtempSync(join(tmpdir(), 'splice-oracle-'));
  const { mockMod, server, proxyPort, mockSha } = await bootOracle(tmp);

  const outDir = check ? join(tmp, 'fixtures') : FIXTURES;
  mkdirSync(outDir, { recursive: true });

  const manifest = {
    captured_from: 'server/ (legacy Node reference implementation)',
    source_test: 'server/test/codex-proxy.test.mjs',
    mock_region_sha256: mockSha,
    canonicalization: CANON_RULES.map((r) => r.name + ': ' + r.pattern),
    node_suite_state_at_capture: '104/104 pass (node --test)',
    excluded: EXCLUDED,
    scenarios: {},
  };

  for (const scenario of SCENARIOS) {
    const before = mockMod.upstreamBodies.length;
    const req = clientRequest(scenario);
    let out;
    try {
      out = await post(proxyPort, req);
    } catch (e) {
      manifest.scenarios[scenario] = { error: String(e).slice(0, 200) };
      continue;
    }
    const sent = mockMod.upstreamBodies.slice(before).map((x) => x.body);
    const fixture = {
      scenario,
      client_request: req,
      expected_upstream_requests: sent,
      expected_client_status: out.status,
      expected_client_sse: canonicalize(out.sse),
    };
    const text = JSON.stringify(fixture, null, 2) + '\n';
    writeFileSync(join(outDir, `${scenario}.json`), text);
    manifest.scenarios[scenario] = {
      sha256: createHash('sha256').update(text).digest('hex'),
      upstream_requests: sent.length,
      client_sse_bytes: out.sse.length,
      client_status: out.status,
    };
  }

  writeFileSync(join(outDir, '_manifest.json'), JSON.stringify(manifest, null, 2) + '\n');
  server.close(); mockMod.mock.close();

  const rows = Object.entries(manifest.scenarios);
  const ok = rows.filter(([, v]) => !v.error);
  console.log(`oracle captured from server/ — ${ok.length}/${SCENARIOS.length} scenarios`);
  for (const [k, v] of rows) {
    console.log(v.error
      ? `  ✗ ${k.padEnd(16)} ${v.error}`
      : `  ✓ ${k.padEnd(16)} upstream_reqs=${v.upstream_requests}  client_sse=${v.client_sse_bytes}B  status=${v.client_status}`);
  }
  console.log(`  excluded (recorded, not dropped): ${Object.keys(EXCLUDED).join(', ')}`);
  console.log(`  mock region sha256: ${mockSha.slice(0, 16)}…`);

  let drift = [];
  if (check) {
    drift = compareAgainstCommitted(outDir, manifest);
    console.log(drift.length ? `\noracle DRIFT — ${drift.length} mismatch(es) vs the committed corpus:` : '\noracle clean — fresh capture matches the committed corpus byte-for-byte');
    for (const line of drift) console.log(`  ✗ ${line}`);
    if (drift.length) {
      console.log('\nThe legacy stack changed, or the fixtures did. Re-read the diff before re-capturing:');
      console.log('  npm run oracle:capture   # only after deciding the new behavior is the CORRECT oracle');
    }
  } else {
    console.log(`\nwrote ${outDir}`);
  }
  rmSync(tmp, { recursive: true, force: true });
  process.exit(ok.length === SCENARIOS.length && drift.length === 0 ? 0 : 1);
}

main().catch((e) => { console.error(e); process.exit(1); });
