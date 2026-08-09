#!/usr/bin/env node
/**
 * dev/campaigns/proxy-hardening/oracle/replay.mjs — GRADE THE KOTLIN GATEWAY AGAINST THE ORACLE.
 *
 * WHAT  Boots the REAL Kotlin daemon (fat jar) against the SAME vendored mock upstream the
 *       oracle was captured from, drives every frozen fixture's client_request at the codex
 *       head, and byte-compares BOTH wire directions against the recording:
 *
 *           fixture.client_request ──▶ [ Kotlin gateway ] ──▶ observed upstream requests
 *                                          │                        vs expected_upstream_requests
 *                                          ▼
 *                                  observed client SSE  vs  expected_client_sse (canonicalized)
 *
 * DISCIPLINE (mirrors capture.mjs, deliberately):
 *   - the mock is extracted BYTE-IDENTICAL from server/test/codex-proxy.test.mjs and its sha256
 *     MUST match _manifest.json — a moved or edited mock invalidates the oracle, fail closed;
 *   - canonicalization is the ONE manifest-declared rule (msg_<digits> -> msg_CANON), applied to
 *     the observed stream exactly as capture applied it to the recording;
 *   - a divergence is never "close enough": it is a byte diff, and it must be classified in
 *     expectations.toml (kotlin-wrong | sanctioned-with-authority), never suppressed here.
 *     [[divergence]] rows in expectations.toml are honored FIELD-WISE for upstream requests:
 *     a mismatch confined to a sanctioned field whose observed value equals the row's
 *     expected_without_session_header still passes (the runner sends NO session header, so the
 *     frozen fallback bytes are what a faithful gateway must reproduce — see the row's note).
 *
 * RUN   node dev/campaigns/proxy-hardening/oracle/replay.mjs [--scenario NAME] [--keep] [--json OUT]
 * EXIT  0 = every replayed scenario byte-matches (or is sanctioned-field-only); 1 = divergence;
 *       2 = harness failure (mock drift, boot failure) — NOT a verdict about the gateway.
 */

import http from 'node:http';
import { spawn, execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { once } from 'node:events';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '../../../..');
const SOURCE = join(ROOT, 'server/test/codex-proxy.test.mjs');
const FIXTURES = join(HERE, 'fixtures');
const EXPECTATIONS = join(HERE, 'expectations.toml');
const JAR = join(ROOT, 'gateway/app/build/libs/app-all.jar');

const START = "import http from 'node:http';";
const END = "mock.listen(0, '127.0.0.1');";

const HEAD_PORT = 39490;   // CI-hermetic fixed scratch ports (OSS-M pattern)
const CONTROL_PORT = 39491;

const CANON_RULES = [{ re: /msg_\d+/g, to: 'msg_CANON' }];
const canonicalize = (t) => CANON_RULES.reduce((x, r) => x.replace(r.re, r.to), t);

const args = process.argv.slice(2);
const flag = (n) => args.includes(n);
const opt = (n) => { const i = args.indexOf(n); return i >= 0 ? args[i + 1] : null; };

// ── the vendored mock: same extraction, same integrity gate as capture ──────
function extractMock() {
  const src = readFileSync(SOURCE, 'utf8');
  const a = src.indexOf(START); const b = src.indexOf(END);
  if (a < 0 || b < 0 || b <= a) throw Object.assign(new Error(`mock markers not found in ${SOURCE}`), { harness: true });
  const region = src.slice(a, b);
  return { region, sha256: createHash('sha256').update(region).digest('hex') };
}

// ── minimal field-wise deep diff for upstream request objects ────────────────
function jsonDiff(exp, obs, path = '', out = []) {
  if (typeof exp !== typeof obs || (exp === null) !== (obs === null)) { out.push({ path, exp, obs }); return out; }
  if (Array.isArray(exp)) {
    if (!Array.isArray(obs) || exp.length !== obs.length) { out.push({ path: `${path}.length`, exp: exp.length, obs: Array.isArray(obs) ? obs.length : typeof obs }); return out; }
    exp.forEach((v, i) => jsonDiff(v, obs[i], `${path}[${i}]`, out));
    return out;
  }
  if (exp && typeof exp === 'object') {
    for (const k of new Set([...Object.keys(exp), ...Object.keys(obs || {})])) {
      if (!(k in exp)) out.push({ path: `${path}.${k}`, exp: '<absent>', obs: obs[k] });
      else if (!(k in obs)) out.push({ path: `${path}.${k}`, exp: exp[k], obs: '<absent>' });
      else jsonDiff(exp[k], obs[k], `${path}.${k}`, out);
    }
    return out;
  }
  if (exp !== obs) out.push({ path, exp, obs });
  return out;
}

// expectations.toml rows, parsed leniently (comments carry the prose; we only need the machine
// fields). A full TOML parser is deliberately NOT vendored; the wall re-parses with tomllib.
function tomlStr(block, key) {
  return new RegExp(`^${key}\\s*=\\s*"([^"]+)"`, 'm').exec(block)?.[1]
    ?? new RegExp(`^${key}\\s*=\\s*'([^']+)'`, 'm').exec(block)?.[1];
}
function sanctionedFields() {
  const text = existsSync(EXPECTATIONS) ? readFileSync(EXPECTATIONS, 'utf8') : '';
  const rows = [];
  for (const block of text.split(/^\[\[divergence\]\]$/m).slice(1)) {
    const field = tomlStr(block, 'field');
    if (field && tomlStr(block, 'status') === 'sanctioned') {
      rows.push({ field, without: tomlStr(block, 'expected_without_session_header'), pinnedValue: tomlStr(block, 'pinned_value') });
    }
  }
  return rows;
}
function sanctionedScenarios() {
  const text = existsSync(EXPECTATIONS) ? readFileSync(EXPECTATIONS, 'utf8') : '';
  const rows = {};
  for (const block of text.split(/^\[\[scenario\]\]$/m).slice(1)) {
    const name = tomlStr(block, 'name');
    if (name && tomlStr(block, 'status') === 'sanctioned') rows[name] = { pinnedSha: tomlStr(block, 'pinned_sha256') };
  }
  return rows;
}

// a diff entry is sanctioned iff its path matches a sanctioned field pattern
// (pattern like "expected_upstream_requests[].prompt_cache_key" -> path suffix ".prompt_cache_key")
// AND the observed value equals the row's pin — a sanction is still a pin, never a wildcard.
function isSanctioned(entry, sanctioned) {
  return sanctioned.some((s) => {
    const leaf = s.field.split('.').pop();
    if (!entry.path.endsWith(`.${leaf}`)) return false;
    if (s.pinnedValue !== undefined) return JSON.stringify(entry.obs) === s.pinnedValue;
    return s.without === undefined || entry.obs === s.without;
  });
}

function post(port, body, bearer, path = '/v1/messages') {
  // Bearer = the daemon's mgmt key: HeadServer.authorize gates local clients on it (launched
  // wrappers receive it as ANTHROPIC_AUTH_TOKEN). NO x-claude-code-session-id on purpose — the
  // frozen fixtures carry no headers, so the cache-key fallback must reproduce (divergence note).
  return new Promise((res, rej) => {
    const req = http.request(
      { host: '127.0.0.1', port, path, method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${bearer}` } },
      (r) => { let t = ''; r.on('data', (c) => { t += c; }); r.on('end', () => res({ status: r.statusCode, sse: t })); },
    );
    req.on('error', rej);
    req.end(JSON.stringify(body));
  });
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
async function waitHttp(port, path, tries = 120) {
  for (let i = 0; i < tries; i++) {
    try { await new Promise((res, rej) => { const q = http.get({ host: '127.0.0.1', port, path, timeout: 1000 }, (r) => { r.resume(); res(); }); q.on('error', rej); q.on('timeout', () => { q.destroy(); rej(new Error('t/o')); }); }); return; }
    catch { await sleep(250); }
  }
  throw Object.assign(new Error(`nothing answering on :${port}${path} after ${tries / 4}s`), { harness: true });
}

async function main() {
  const manifest = JSON.parse(readFileSync(join(FIXTURES, '_manifest.json'), 'utf8'));
  const { region, sha256 } = extractMock();
  if (sha256 !== manifest.mock_region_sha256) {
    console.error(`FATAL mock drift: live region ${sha256.slice(0, 16)}… vs manifest ${String(manifest.mock_region_sha256).slice(0, 16)}…`);
    console.error('the vendored mock no longer matches what the oracle was captured from — re-examine before trusting any replay');
    return 2;
  }

  // fixture integrity — same NO-SAVED-TRUTH rule the wall enforces
  for (const [name, meta] of Object.entries(manifest.scenarios || {})) {
    const f = join(FIXTURES, `${name}.json`);
    const digest = createHash('sha256').update(readFileSync(f)).digest('hex');
    if (digest !== meta.sha256) { console.error(`FATAL fixture tampered: ${name}.json sha256 mismatch vs _manifest.json`); return 2; }
  }

  if (!existsSync(JAR)) {
    console.error('fat jar missing — building (:app:shadowJar)…');
    execFileSync('./gradlew', ['-q', ':app:shadowJar'], { cwd: join(ROOT, 'gateway'), stdio: 'inherit' });
  }

  const tmp = mkdtempSync(join(tmpdir(), 'splice-replay-'));
  const mockPath = join(tmp, 'vendored_mock.mjs');
  writeFileSync(mockPath, region + `\nmock.listen(0, '127.0.0.1');\nexport { mock, upstreamAuths, upstreamBodies, abortedScenarios, AUTH_PATH, stateRoot };\n`);
  const m = await import(pathToFileURL(mockPath).href);
  await once(m.mock, 'listening');
  const mockPort = m.mock.address().port;

  mkdirSync(join(tmp, 'state'), { recursive: true });
  writeFileSync(join(tmp, 'splice.toml'), `# hermetic replay topology — generated by replay.mjs, mirrors capture env exactly
[daemon]
control_port = ${CONTROL_PORT}
show_reasoning = "text"
summary = "detailed"
effort = "high"
replay_reasoning = true

[providers.codex]
dialect = "openai-responses"
base_url = "http://127.0.0.1:${mockPort}"
auth = { kind = "chatgpt-oauth", file = "${m.AUTH_PATH}" }
quirks = { store = false, account_id_header = true, cache_key = "first-message-hash", effort_ceiling = "max", summary_field = true }

[[providers.codex.models]]
id = "gpt-5-codex"
label = "Codex (oracle)"
# 272000 = the legacy Node resolveContextWindow DEFAULT (server/test/launcher.test.mjs:74) —
# the capture-time reference had no roster entry for gpt-5-codex, so the oracle's message_start
# usage rides that default and a faithful replay must too.
context_window = 272000

[heads.claudex]
provider = "codex"
port = ${HEAD_PORT}
discovery_prefix = "claude-codex--"
pinned_model = "gpt-5-codex"
[heads.claudex.claude]
command = "claudex"
`);

  const env = Object.fromEntries(Object.entries(process.env).filter(([k]) => !/^(CLAUDEX_|CODEX_|SPLICE_|CHATGPT_)/.test(k)));
  Object.assign(env, {
    SPLICE_CONFIG: join(tmp, 'splice.toml'),
    CLAUDEX_STATE_DIR: join(tmp, 'state'),
    CLAUDEX_STREAM_IDLE_MS: '700',
    CLAUDEX_UPSTREAM_RETRIES: '2',
    CLAUDEX_SHOW_REASONING: 'text',
    CLAUDEX_REASONING_EFFORT: 'high',
    CLAUDEX_REASONING_SUMMARY: 'detailed',
    CLAUDEX_REPLAY_REASONING: '1',
    CODEX_OAUTH_TOKEN_URL: `http://127.0.0.1:${mockPort}/oauth/token`,
  });

  const logFd = join(tmp, 'daemon.stdout.log');
  const daemon = spawn('java', ['-Xmx1024m', '-jar', JAR, 'daemon'], { env, stdio: ['ignore', 'pipe', 'pipe'] });
  let dlog = '';
  daemon.stdout.on('data', (c) => { dlog += c; });
  daemon.stderr.on('data', (c) => { dlog += c; });
  const dead = new Promise((r) => daemon.once('exit', (code) => r(code)));

  const verdicts = {};
  let exit = 0;
  try {
    await Promise.race([
      (async () => { await waitHttp(CONTROL_PORT, '/health'); await waitHttp(HEAD_PORT, '/health', 40).catch(() => waitHttp(HEAD_PORT, '/', 40)); })(),
      dead.then((code) => { throw Object.assign(new Error(`daemon exited (${code}) before healthy`), { harness: true }); }),
    ]);

    const only = opt('--scenario');
    const bearer = readFileSync(join(tmp, 'state', 'mgmt-key'), 'utf8').trim();
    const sanctioned = sanctionedFields();
    const roster = readdirSync(FIXTURES).filter((f) => f.endsWith('.json') && f !== '_manifest.json').map((f) => f.replace(/\.json$/, '')).sort();

    const sanctionedRows = sanctionedScenarios();
    for (const name of roster) {
      if (only && name !== only) continue;
      const fx = JSON.parse(readFileSync(join(FIXTURES, `${name}.json`), 'utf8'));
      const before = m.upstreamBodies.length;
      const out = await post(HEAD_PORT, fx.client_request, bearer);
      const observedUpstream = m.upstreamBodies.slice(before).map((x) => x.body);
      const problems = [];

      // a sanctioned scenario is graded against ITS pin, not the frozen reference bytes: the
      // gateway diverges on purpose (cited in expectations.toml) and the pinned sha is the
      // regression net. The upstream shape is deterministically coupled to those client bytes
      // (e.g. truncated's 3 re-anchor attempts), so the pin is the whole check.
      const sanction = sanctionedRows[name];
      if (sanction?.pinnedSha) {
        const gotSse = canonicalize(out.sse);
        const sha = createHash('sha256').update(gotSse).digest('hex');
        if (out.status !== fx.expected_client_status) problems.push(`client status: expected ${fx.expected_client_status}, got ${out.status}`);
        if (sha !== sanction.pinnedSha) {
          problems.push(`sanctioned bytes drifted: pinned ${sanction.pinnedSha.slice(0, 16)}…, observed ${sha.slice(0, 16)}… — the divergence is no longer the one that was authorised`);
        }
        verdicts[name] = { pass: problems.length === 0, sanctioned: true, problems, observed_status: out.status, observed_sse: gotSse, observed_upstream: observedUpstream };
        console.log(problems.length === 0 ? `  ✓ ${name} (sanctioned — pinned bytes hold)` : `  ✗ ${name}`);
        for (const p of problems) console.log(`      ${p}`);
        if (problems.length) exit = 1;
        continue;
      }

      if (out.status !== fx.expected_client_status) problems.push(`client status: expected ${fx.expected_client_status}, got ${out.status}`);

      const gotSse = canonicalize(out.sse);
      if (gotSse !== fx.expected_client_sse) {
        const a = fx.expected_client_sse; const b = gotSse;
        let i = 0; while (i < Math.min(a.length, b.length) && a[i] === b[i]) i++;
        problems.push(`client SSE diverges at byte ${i} (expected ${a.length}B, got ${b.length}B)`);
        problems.push(`  expected …${JSON.stringify(a.slice(Math.max(0, i - 60), i + 100))}`);
        problems.push(`  observed …${JSON.stringify(b.slice(Math.max(0, i - 60), i + 100))}`);
      }

      if (observedUpstream.length !== fx.expected_upstream_requests.length) {
        problems.push(`upstream request count: expected ${fx.expected_upstream_requests.length}, got ${observedUpstream.length}`);
      } else {
        fx.expected_upstream_requests.forEach((expReq, i) => {
          for (const d of jsonDiff(expReq, observedUpstream[i], `upstream[${i}]`)) {
            if (isSanctioned(d, sanctioned)) continue;
            problems.push(`${d.path}: expected ${JSON.stringify(d.exp)?.slice(0, 120)}, got ${JSON.stringify(d.obs)?.slice(0, 120)}`);
          }
        });
      }

      verdicts[name] = { pass: problems.length === 0, problems, observed_status: out.status, observed_sse: gotSse, observed_upstream: observedUpstream };
      console.log(problems.length === 0 ? `  ✓ ${name}` : `  ✗ ${name}`);
      for (const p of problems) console.log(`      ${p}`);
      if (problems.length) exit = 1;
    }
  } catch (e) {
    console.error(`HARNESS FAILURE: ${e.message}`);
    exit = 2;
  } finally {
    daemon.kill('SIGTERM');
    await Promise.race([dead, sleep(5000).then(() => daemon.kill('SIGKILL'))]);
    m.mock.close();
    writeFileSync(logFd, dlog);
  }

  const jsonOut = opt('--json');
  if (jsonOut) writeFileSync(jsonOut, JSON.stringify({ replayed_at: new Date().toISOString(), verdicts }, null, 2) + '\n');

  const total = Object.keys(verdicts).length;
  const passed = Object.values(verdicts).filter((v) => v.pass).length;
  console.log(`\nreplay: ${passed}/${total} scenarios byte-match the oracle${exit === 2 ? ' (HARNESS FAILURE — not a gateway verdict)' : ''}`);
  if (exit !== 0 || flag('--keep')) console.log(`scratch kept for post-mortem: ${tmp} (daemon log: ${logFd})`);
  else rmSync(tmp, { recursive: true, force: true });
  return exit;
}

main().then((c) => process.exit(c), (e) => { console.error(e); process.exit(2); });
