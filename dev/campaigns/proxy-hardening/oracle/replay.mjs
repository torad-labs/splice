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
import net from 'node:net';
import { spawn, execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { closeSync, existsSync, mkdirSync, mkdtempSync, openSync, readFileSync, readdirSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { once } from 'node:events';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '../../../..');
const SOURCE = join(ROOT, 'server/test/codex-proxy.test.mjs');
const VENDORED = join(HERE, 'mock-upstream.vendored.mjs');
const FIXTURES = join(HERE, 'fixtures');
const EXPECTATIONS = join(HERE, 'expectations.toml');
const JAR = join(ROOT, 'gateway/app/build/libs/app-all.jar');

const START = "import http from 'node:http';";
const END = "mock.listen(0, '127.0.0.1');";

const EMPTY_LOCK_STALE_MS = 30_000; // >> the create->write window; only a crashed pre-write lock survives it
const REQUEST_TIMEOUT_MS = 60_000; // a replayed fixture answers in ms; 60s means WEDGED, not slow
const HEAD_PORT = 39490;   // CI-hermetic fixed scratch ports (OSS-M pattern)
const CONTROL_PORT = 39491;

const CANON_RULES = [{ re: /msg_\d+(?:_\d+)?/g, to: 'msg_CANON' }];
const canonicalize = (t) => CANON_RULES.reduce((x, r) => x.replace(r.re, r.to), t);

const args = process.argv.slice(2);
const flag = (n) => args.includes(n);
const opt = (n) => { const i = args.indexOf(n); return i >= 0 ? args[i + 1] : null; };

// ── the vendored mock: same extraction, same integrity gate as capture ──────
function regionFrom(src, where) {
  const a = src.indexOf(START); const b = src.indexOf(END);
  if (a < 0 || b < 0 || b <= a) throw Object.assign(new Error(`mock markers not found in ${where}`), { harness: true });
  return src.slice(a, b);
}

// The mock is VENDORED (mock-upstream.vendored.mjs) rather than read out of server/ at runtime:
// P8-CUT deletes server/ wholesale, and reading it here would have taken the 11 byte-exact
// fixtures down with the Node tree. The sha256 gate against _manifest.json is unchanged, so the
// bytes are still pinned. While server/ SURVIVES we additionally re-extract from it and fail on
// divergence — so vendoring drops the dependency without dropping the drift alarm. Once server/ is
// gone that cross-check silently stops applying, which is correct: there is nothing left to drift.
function extractMock() {
  if (!existsSync(VENDORED)) {
    throw Object.assign(new Error(`vendored mock missing: ${VENDORED}`), { harness: true });
  }
  const region = regionFrom(readFileSync(VENDORED, 'utf8'), VENDORED);
  if (existsSync(SOURCE)) {
    const live = regionFrom(readFileSync(SOURCE, 'utf8'), SOURCE);
    if (live !== region) {
      throw Object.assign(
        new Error(`vendored mock diverges from ${SOURCE} — re-vendor deliberately or revert the edit`),
        { harness: true },
      );
    }
  }
  return { region, sha256: createHash('sha256').update(region).digest('hex') };
}

// ── minimal field-wise deep diff for upstream request objects ────────────────
/** True when something is already listening — used to fail closed on a leaked daemon. */
function portInUse(port) {
  return new Promise((resolve) => {
    const sock = new net.Socket();
    sock.setTimeout(400);
    sock.once('connect', () => { sock.destroy(); resolve(true); });
    sock.once('timeout', () => { sock.destroy(); resolve(false); });
    sock.once('error', () => resolve(false));
    sock.connect(port, '127.0.0.1');
  });
}

/** Atomic exclusive run lock via O_EXCL (portable; node has no flock). Returns true on acquire.
 *  A lock naming a DEAD pid is reclaimed — an interrupted run that never ran its exit handler must
 *  not wedge every future run. A lock held by a live pid, or one too young to rule out a competing
 *  run mid-create, → refuse (F6). */
function acquireRunLock(lockPath) {
  try {
    const fd = openSync(lockPath, 'wx'); // wx = O_CREAT|O_EXCL: fails if the file exists
    writeFileSync(fd, String(process.pid));
    closeSync(fd);
    return true;
  } catch {
    // FAIL CLOSED on an unreadable owner. openSync+writeFileSync are two syscalls, so a competing
    // run can read this file in the microseconds after it is CREATED and before the pid lands.
    // Treating that empty read as "no owner => dead" let the second run delete a LIVE run's lock
    // and proceed, putting both into the preflight where one SIGKILLs the other's daemon as
    // "leaked" — reinstating the mutual assassination this lock was written to stop.
    // Only a lock naming a pid that is genuinely gone is reclaimable; an empty lock is reclaimed
    // solely once it is old enough that no in-flight create could still be mid-write.
    const raw = runCatchRead(lockPath).trim();
    const owner = Number(raw);
    const reclaimable = raw === ''
      ? ageMs(lockPath) > EMPTY_LOCK_STALE_MS
      : Number.isInteger(owner) && owner > 0 && !pidAlive(owner);
    if (!reclaimable) return false;
    try { rmSync(lockPath, { force: true }); } catch { /* raced */ }
    try { const fd = openSync(lockPath, 'wx'); writeFileSync(fd, String(process.pid)); closeSync(fd); return true; } catch { return false; }
  }
}

/** Age of [p] in ms; Infinity when it cannot be stat'd (a vanished lock is maximally stale). */
function ageMs(p) {
  try { return Date.now() - statSync(p).mtimeMs; } catch { return Infinity; }
}
function runCatchRead(p) { try { return readFileSync(p, 'utf8'); } catch { return ''; } }
function pidAlive(pid) { try { process.kill(pid, 0); return true; } catch (e) { return e.code === 'EPERM'; } }

/** SIGKILL a leaked ORACLE daemon holding [port]; true if one was killed. Scoped by cmdline to
 *  the build-tree jar (app-all.jar) — the production daemon runs ~/.local/share/splice/splice.jar,
 *  so the real gateway on :3099/:3096 is unreachable from here by construction. Only reached while
 *  we hold the run lock, so a squatter here is a prior interrupted run, never a live concurrent one. */
function killLeakedOracleDaemon(port) {
  try {
    const out = execFileSync('ss', ['-ltnpH', `( sport = :${port} )`], { encoding: 'utf8' });
    let killed = false;
    for (const pid of new Set([...out.matchAll(/pid=(\d+)/g)].map((m) => m[1]))) {
      const cmd = readFileSync(`/proc/${pid}/cmdline`, 'utf8').replace(/\0/g, ' ');
      if (cmd.includes('app-all.jar') && cmd.includes('daemon')) {
        console.error(`[preflight] killing oracle daemon pid ${pid} leaked by an earlier run (held :${port})`);
        process.kill(Number(pid), 'SIGKILL');
        killed = true;
      }
    }
    return killed;
  } catch { return false; }
}

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
    if (name && tomlStr(block, 'status') === 'sanctioned') {
      rows[name] = {
        pinnedSha: tomlStr(block, 'pinned_sha256'),
        pinUpstream: tomlStr(block, 'pin_upstream'),
        pinnedUpstreamSha: tomlStr(block, 'pinned_upstream_sha256'),
      };
    }
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
    // A sanction with NO runner-readable pin sanctioned EVERY observed value at that leaf, at any
    // depth — the wildcard the comment above says cannot exist. It passed the wall too, because the
    // wall checks `pinned_sha256` while the runner reads `pinned_value` / the without-header value:
    // two checkers, different fields, both green. Fail closed here; the wall now enforces that the
    // sha256 is the hash OF the runner-readable pin, so the two can no longer drift apart.
    if (s.without === undefined) return false;
    return entry.obs === s.without;
  });
}

function gradeUpstream(expected, observed, sanctioned, problems) {
  if (observed.length !== expected.length) {
    problems.push(`upstream request count: expected ${expected.length}, got ${observed.length}`);
    return;
  }
  expected.forEach((expReq, i) => {
    for (const d of jsonDiff(expReq, observed[i], `upstream[${i}]`)) {
      if (!isSanctioned(d, sanctioned)) problems.push(`${d.path}: expected ${JSON.stringify(d.exp)?.slice(0, 120)}, got ${JSON.stringify(d.obs)?.slice(0, 120)}`);
    }
  });
}

function post(port, body, bearer, path = '/v1/messages') {
  // Bearer = the daemon's mgmt key: HeadServer.authorize gates local clients on it (launched
  // wrappers receive it as ANTHROPIC_AUTH_TOKEN). NO x-claude-code-session-id on purpose — the
  // frozen fixtures carry no headers, so the cache-key fallback must reproduce (divergence note).
  return new Promise((res, rej) => {
    const req = http.request(
      {
        host: '127.0.0.1', port, path, method: 'POST',
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${bearer}` },
        // Without this the harness HANGS FOREVER on exactly the failure it exists to detect:
        // the 91h wedge accepted every connection and answered none, so an untimed read waits
        // out the heat death rather than reporting the wedge. Generous enough that a slow-but-
        // working replay never trips it.
        timeout: REQUEST_TIMEOUT_MS,
      },
      (r) => { let t = ''; r.on('data', (c) => { t += c; }); r.on('end', () => res({ status: r.statusCode, sse: t })); },
    );
    req.on('timeout', () => {
      req.destroy(Object.assign(
        new Error(`no response from :${port}${path} in ${REQUEST_TIMEOUT_MS}ms — accepted but never answered (the wedge signature)`),
        { harness: true },
      ));
    });
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
  // The daemon's quota poller (QuotaPoller, 2026-09-02) asks this origin for
  // GET /backend-api/wham/usage; the captured mock predates it and JSON-parses every request body,
  // so an empty GET body crashed the mock process. Answer 404 BEFORE the vendored handler: the
  // replay keeps its captured shape (no quota snapshot, no unified headers on head responses, no
  // extra upstream request recorded) and the pinned mock region is untouched.
  const vendoredHandler = m.mock.listeners('request')[0];
  m.mock.removeAllListeners('request');
  m.mock.on('request', (req, res) => {
    if (req.method === 'GET' && req.url === '/backend-api/wham/usage') {
      res.writeHead(404);
      res.end();
      return;
    }
    vendoredHandler(req, res);
  });
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

  // EXCLUSIVE RUN LOCK (F6, 2026-08-12). The ports are FIXED and shared, so two concurrent runs
  // cannot coexist. Before this lock the second run's preflight SIGKILLed the FIRST run's LIVE
  // daemon (mislabelled "leaked"), so two CI jobs on one runner mutually assassinated at ~50%.
  // An exclusive, non-blocking lock makes concurrent = fail-fast-and-refuse; only after we hold it
  // does the preflight treat a port-squatter as a genuine leak from an interrupted PRIOR run.
  const lockPath = join(tmpdir(), 'splice-oracle-replay.lock');
  const heldLock = acquireRunLock(lockPath);
  if (!heldLock) {
    throw Object.assign(
      new Error('another oracle replay holds the run lock — concurrent runs share fixed ports; retry when it finishes'),
      { harness: true },
    );
  }
  process.on('exit', () => { try { rmSync(lockPath, { force: true }); } catch { /* already gone */ } });

  // PREFLIGHT (2026-08-11). Now that we hold the lock, any daemon still on these ports is a leak
  // from an interrupted PRIOR run, never a live concurrent one. If the squatter is OUR OWN oracle
  // daemon (app-all.jar cmdline — production runs splice.jar, so this can never touch the real
  // gateway), kill it and continue. Anything else on the port: refuse loudly.
  for (const port of [HEAD_PORT, CONTROL_PORT]) {
    if (!(await portInUse(port))) continue;
    const killed = killLeakedOracleDaemon(port);
    if (killed) {
      for (let i = 0; i < 30 && await portInUse(port); i++) await sleep(100);
    }
    if (await portInUse(port)) {
      throw Object.assign(
        new Error(`port ${port} is held by a process that is not a leaked oracle daemon — refusing to validate against another process's gateway`),
        { harness: true },
      );
    }
  }

  const logFd = join(tmp, 'daemon.stdout.log');
  const daemon = spawn('java', ['-Xmx1024m', '-jar', JAR, 'daemon'], { env, stdio: ['ignore', 'pipe', 'pipe'] });
  let dlog = '';
  daemon.stdout.on('data', (c) => { dlog += c; });
  daemon.stderr.on('data', (c) => { dlog += c; });
  const dead = new Promise((r) => daemon.once('exit', (code) => r(code)));
  // LAST LINE OF DEFENSE against a leaked daemon. process.on('exit') covers a normal return and
  // an uncaught throw (the zstd-crash shape), but NOT a signal — node's default SIGINT/SIGTERM
  // termination skips 'exit' handlers (verified 2026-08-12). So Ctrl-C mid-run — the exact
  // "earlier interrupted run" the preflight self-heal exists for — needs its own handlers. With
  // both, an interrupted run kills its own daemon; the preflight is the backstop, not the only net.
  const killDaemon = () => { try { daemon.kill('SIGKILL'); } catch { /* already gone */ } };
  process.on('exit', killDaemon);
  for (const sig of ['SIGINT', 'SIGTERM', 'SIGHUP']) {
    process.on(sig, () => { killDaemon(); process.exit(130); });
  }
  // A rejecting twin of `dead` for the boot race ONLY. It must be marked handled immediately:
  // if health wins the race, the daemon's LATER exit (including our own SIGTERM in cleanup)
  // still triggers this rejection, and an unhandled rejection is fatal in modern node — the
  // process died mid-cleanup with a bare "Node.js v24.16.0" tail, a 0-byte daemon.stdout.log,
  // and a leaked daemon holding the fixed ports. That was the entire leak chain.
  const deadBeforeHealthy = dead.then((code) => {
    throw Object.assign(new Error(`daemon exited (${code}) before healthy`), { harness: true });
  });
  deadBeforeHealthy.catch(() => {}); // mark handled; the race keeps its own rejecting reference

  const verdicts = {};
  let exit = 0;
  try {
    await Promise.race([
      // Head waits carry the same 30s budget as control: right after gate.sh's `clean check` +
      // inline shadowJar the JVM boots under full gradle-daemon load, and the head listener
      // (which binds AFTER control) blew a 10s ceiling — a slow boot is not a dead daemon.
      (async () => { await waitHttp(CONTROL_PORT, '/health'); await waitHttp(HEAD_PORT, '/health').catch(() => waitHttp(HEAD_PORT, '/')); })(),
      deadBeforeHealthy,
    ]);

    const only = opt('--scenario');
    const bearer = readFileSync(join(tmp, 'state', 'mgmt-key'), 'utf8').trim();
    const sanctioned = sanctionedFields();
    const roster = readdirSync(FIXTURES).filter((f) => f.endsWith('.json') && f !== '_manifest.json').map((f) => f.replace(/\.json$/, '')).sort();

    const sanctionedRows = sanctionedScenarios();
    // A typo'd --scenario used to skip every iteration and exit 0 on "0/0 byte-match": a
    // verification gate whose vacuous case is GREEN. Name it before grading anything.
    if (only && !roster.includes(only)) {
      throw Object.assign(
        new Error(`--scenario ${only} is not in the roster (${roster.join(', ')})`),
        { harness: true },
      );
    }
    for (const name of roster) {
      if (only && name !== only) continue;
      const fx = JSON.parse(readFileSync(join(FIXTURES, `${name}.json`), 'utf8'));
      const before = m.upstreamBodies.length;
      const out = await post(HEAD_PORT, fx.client_request, bearer);
      const observedUpstream = m.upstreamBodies.slice(before).map((x) => x.body);
      const problems = [];

      // A sanctioned scenario pins BOTH directions. Rows whose upstream request remains reference-
      // compatible set pin_upstream="fixture" and keep the ordinary deep diff; rows such as truncated
      // deliberately changed request count/shape and pin the authorized upstream array by hash.
      const sanction = sanctionedRows[name];
      if (sanction?.pinnedSha) {
        const gotSse = canonicalize(out.sse);
        const sha = createHash('sha256').update(gotSse).digest('hex');
        if (out.status !== fx.expected_client_status) problems.push(`client status: expected ${fx.expected_client_status}, got ${out.status}`);
        if (sha !== sanction.pinnedSha) {
          problems.push(`sanctioned bytes drifted: pinned ${sanction.pinnedSha.slice(0, 16)}…, observed ${sha.slice(0, 16)}… — the divergence is no longer the one that was authorised`);
        }
        if (sanction.pinUpstream === 'fixture') {
          gradeUpstream(fx.expected_upstream_requests, observedUpstream, sanctioned, problems);
        } else if (sanction.pinnedUpstreamSha) {
          const upstreamSha = createHash('sha256').update(JSON.stringify(observedUpstream)).digest('hex');
          if (upstreamSha !== sanction.pinnedUpstreamSha) {
            problems.push(`sanctioned upstream bytes drifted: pinned ${sanction.pinnedUpstreamSha.slice(0, 16)}…, observed ${upstreamSha.slice(0, 16)}…`);
          }
        } else {
          problems.push('sanctioned scenario has no upstream pin — refusing a one-direction-only verdict');
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

      gradeUpstream(fx.expected_upstream_requests, observedUpstream, sanctioned, problems);

      verdicts[name] = { pass: problems.length === 0, problems, observed_status: out.status, observed_sse: gotSse, observed_upstream: observedUpstream };
      console.log(problems.length === 0 ? `  ✓ ${name}` : `  ✗ ${name}`);
      for (const p of problems) console.log(`      ${p}`);
      if (problems.length) exit = 1;
    }
  } catch (e) {
    console.error(`HARNESS FAILURE: ${e.message}`);
    exit = 2;
  } finally {
    // SIGTERM, bounded wait, then SIGKILL — and only trust the PORTS, not kill()'s return.
    daemon.kill('SIGTERM');
    await Promise.race([dead, sleep(5000)]);
    if (daemon.exitCode === null) daemon.kill('SIGKILL');
    await Promise.race([dead, sleep(2000)]);
    for (let i = 0; i < 20 && (await portInUse(HEAD_PORT) || await portInUse(CONTROL_PORT)); i++) await sleep(100);
    if (await portInUse(HEAD_PORT)) {
      console.error(`WARNING: :${HEAD_PORT} still held after cleanup — the next replay will self-heal it.`);
    }
    m.mock.close();
    writeFileSync(logFd, dlog);
  }

  const jsonOut = opt('--json');
  if (jsonOut) writeFileSync(jsonOut, JSON.stringify({ replayed_at: new Date().toISOString(), verdicts }, null, 2) + '\n');

  const total = Object.keys(verdicts).length;
  const passed = Object.values(verdicts).filter((v) => v.pass).length;
  // Grading NOTHING is not passing. An emptied/renamed fixtures dir, or any future filter that
  // matches no scenario, otherwise prints "0/0 byte-match" and exits 0 — the gate certifying a
  // run in which it verified nothing at all.
  if (total === 0) {
    console.error('HARNESS FAILURE: no scenario was graded — the oracle verified nothing');
    console.log('\nreplay: 0/0 scenarios byte-match the oracle (HARNESS FAILURE — not a gateway verdict)');
    if (flag('--keep')) console.log(`scratch kept for post-mortem: ${tmp} (daemon log: ${logFd})`);
    return 2;
  }
  console.log(`\nreplay: ${passed}/${total} scenarios byte-match the oracle${exit === 2 ? ' (HARNESS FAILURE — not a gateway verdict)' : ''}`);
  if (exit !== 0 || flag('--keep')) console.log(`scratch kept for post-mortem: ${tmp} (daemon log: ${logFd})`);
  else rmSync(tmp, { recursive: true, force: true });
  return exit;
}

main().then((c) => process.exit(c), (e) => {
  // A harness-tagged throw is a HARNESS problem, not a gateway verdict — report it the same way
  // the in-run failures are reported, so a leaked-port refusal reads as infrastructure and never
  // as "the gateway diverged".
  if (e && e.harness) {
    console.error(`HARNESS FAILURE: ${e.message}`);
    console.error('\nreplay: 0/0 scenarios byte-match the oracle (HARNESS FAILURE — not a gateway verdict)');
  } else {
    console.error(e);
  }
  process.exit(2);
});
