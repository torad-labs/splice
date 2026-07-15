#!/usr/bin/env node
// Control server (spliced): bearer auth, aggregated /api/*, config fan-out (the
// file path when no head runs), usage soft-warn, auth introspection, and
// lifecycle routing. Head ports point at unused ports so nothing real is touched.
import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { mkdtempSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { once } from 'node:events';

const root = mkdtempSync(join(tmpdir(), 'splice-control-test-'));
const stateDir = join(root, 'state');
mkdirSync(stateDir, { recursive: true });
process.env.CONTROL_SERVER_TEST = '1'; // don't auto-start; we listen manually
process.env.CLAUDEX_STATE_DIR = stateDir;
process.env.CODEX_PROXY_PORT = '3991'; // isolated + unused → heads report not-running
process.env.CODEX_AUTH_PATH = join(root, 'codex-auth.json'); // absent

const control = await import('../src/control-server.mjs');
const { statePaths, resetRuntimeConfigForTests } = await import('../src/config.mjs');
const { ensureMgmtKey } = await import('../src/mgmt/api.mjs');
const { CODEX_PROXY_VERSION } = await import('../src/versions.mjs');

// Seed a rate-limit snapshot at 90% used so the soft-warn has something to fire on.
writeFileSync(statePaths.ratelimit(), JSON.stringify({ limit_tokens: 1000, remaining_tokens: 100, reset_tokens: '2h' }));
writeFileSync(statePaths.usage(), JSON.stringify([]));

const server = control.createServer();
server.listen(0, '127.0.0.1');
await once(server, 'listening');
const port = server.address().port;
after(() => { server.close(); resetRuntimeConfigForTests(); });

const KEY = ensureMgmtKey();

function call(method, path, { body, key } = {}) {
  return new Promise((resolve, reject) => {
    const req = http.request(
      {
        host: '127.0.0.1',
        port,
        path,
        method,
        headers: {
          'Content-Type': 'application/json',
          ...(key === null ? {} : { Authorization: `Bearer ${key ?? KEY}` }),
        },
      },
      (res) => {
        let t = '';
        res.on('data', (c) => { t += c; });
        res.on('end', () => {
          try { resolve({ status: res.statusCode, json: t ? JSON.parse(t) : null }); }
          catch { resolve({ status: res.statusCode, json: null, text: t }); }
        });
      },
    );
    req.on('error', reject);
    req.end(body ? JSON.stringify(body) : undefined);
  });
}

test('control mints the mgmt key at boot (shared with the proxies)', () => {
  const onDisk = readFileSync(statePaths.mgmtKey(), 'utf8').trim();
  assert.equal(onDisk, KEY);
});

test('bearer required: no key → 401, wrong key → 401', async () => {
  assert.equal((await call('GET', '/api/status', { key: null })).status, 401);
  assert.equal((await call('GET', '/api/status', { key: 'wrong' })).status, 401);
});

test('GET /api/status: server meta + head registry', async () => {
  const { status, json } = await call('GET', '/api/status');
  assert.equal(status, 200);
  assert.equal(json.server, 'control');
  assert.equal(json.version, control.PROXY_VERSION);
  assert.deepEqual(json.heads, ['codex']);
  assert.ok(json.registry.some((h) => h.key === 'codex' && h.authKind === 'codex'));
});

test('GET /api/heads: every head reported; not running on isolated ports', async () => {
  const { status, json } = await call('GET', '/api/heads');
  assert.equal(status, 200);
  const codex = json.heads.find((h) => h.key === 'codex');
  assert.equal(codex.running, false);
  assert.equal(codex.healthy, false);
  assert.equal(codex.port, 3991);
  assert.equal(codex.wantVersion, CODEX_PROXY_VERSION);
});

test('GET /api/auth: codex introspected, present=false in fixture, no token material', async () => {
  const { status, json } = await call('GET', '/api/auth');
  assert.equal(status, 200);
  assert.equal(json.codex.present, false);
  assert.equal(json.codex.login, 'automated');
  assert.equal(json.claude, undefined, 'claude auth removed');
  assert.ok(!JSON.stringify(json).includes('access_token'), 'never exposes token material');
});

test('GET /api/usage: aggregated + soft-warn fires from the seeded 90% ratelimit', async () => {
  const { status, json } = await call('GET', '/api/usage');
  assert.equal(status, 200);
  assert.equal(json.window_hours, 5);
  const codex = json.heads.find((h) => h.key === 'codex');
  assert.equal(codex.usage.warn.level, 'warn', '90% used ≥ 80% default → warn (never blocks)');
  assert.equal(codex.usage.warn.pct, 90);
  assert.equal(codex.usage.warn.source, 'ratelimit');
});

test('PATCH /api/config with no head up → persists to file, reflected by GET', async () => {
  const patch = await call('PATCH', '/api/config', { body: { effort: 'low' } });
  assert.equal(patch.status, 200);
  assert.equal(patch.json.applied.effort, 'low');
  assert.equal(patch.json.persisted, 'file');
  assert.deepEqual(patch.json.targets, [], 'no running head to fan out to');
  assert.equal(JSON.parse(readFileSync(statePaths.config(), 'utf8')).effort, 'low', 'persisted to config.json');
  const get = await call('GET', '/api/config');
  assert.equal(get.json.source, 'control', 'no running head → control computes effective');
  assert.equal(get.json.effective.effort, 'low');
  await call('PATCH', '/api/config', { body: { effort: null } }); // cleanup
});

test('PATCH /api/config rejects unknown keys with 400', async () => {
  const r = await call('PATCH', '/api/config', { body: { bogus: 1 } });
  assert.equal(r.status, 400);
  assert.equal(r.json.rejected.bogus, 'unknown key');
});

test('lifecycle routing: stop on an isolated head is a safe no-op; unknown head → 404', async () => {
  const stop = await call('POST', '/api/heads/codex/stop');
  assert.equal(stop.status, 200);
  assert.equal(stop.json.stopped, true);
  assert.equal(stop.json.running, false, 'nothing was running on the isolated port');
  assert.equal((await call('POST', '/api/heads/bogus/start')).status, 404);
});

test('GET / serves the dashboard (html when built)', async () => {
  const { status } = await call('GET', '/');
  assert.ok(status === 200 || status === 503, 'committed dist → 200, otherwise 503');
});

test('unknown /api route → 404', async () => {
  assert.equal((await call('GET', '/api/nope')).status, 404);
});
