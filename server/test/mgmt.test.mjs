#!/usr/bin/env node
// Management API: bearer auth, status, hot-config round-trip, models/compact shapes.
import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { mkdtempSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { once } from 'node:events';

const root = mkdtempSync(join(tmpdir(), 'splice-mgmt-test-'));
process.env.CODEX_PROXY_TEST = '1';
process.env.CLAUDEX_STATE_DIR = join(root, 'state');
process.env.CODEX_AUTH_PATH = join(root, 'auth.json'); // absent — mgmt must still work

const proxy = await import('../src/codex-proxy.mjs');
const { statePaths, resetRuntimeConfigForTests } = await import('../src/config.mjs');
const { ensureMgmtKey } = await import('../src/mgmt/api.mjs');

const server = proxy.createServer();
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
        res.on('end', () => resolve({ status: res.statusCode, json: t ? JSON.parse(t) : null }));
      },
    );
    req.on('error', reject);
    req.end(body ? JSON.stringify(body) : undefined);
  });
}

test('mgmt key is generated once into the state dir (0600)', () => {
  const onDisk = readFileSync(statePaths.mgmtKey(), 'utf8').trim();
  assert.equal(onDisk, KEY);
  assert.equal(ensureMgmtKey(), KEY, 'stable across calls');
});

test('bearer required: no key → 401, wrong key → 401', async () => {
  assert.equal((await call('GET', '/mgmt/status', { key: null })).status, 401);
  assert.equal((await call('GET', '/mgmt/status', { key: 'wrong' })).status, 401);
});

test('GET /mgmt/status: version, uptime, gate snapshot', async () => {
  const { status, json } = await call('GET', '/mgmt/status');
  assert.equal(status, 200);
  assert.equal(json.proxy, 'codex-proxy');
  assert.equal(json.version, proxy.PROXY_VERSION);
  assert.ok(json.uptime_s >= 0);
  assert.ok(json.gate && typeof json.gate.inflight === 'number');
  assert.ok('live' in json.gate);
});

test('hot-config round-trip: PATCH applies without restart and persists', async () => {
  const before = await call('GET', '/mgmt/config');
  assert.equal(before.status, 200);
  assert.ok(before.json.layers && before.json.effective, 'layered view exposed');

  const patch = await call('PATCH', '/mgmt/config', { body: { effort: 'low', port: 4242 } });
  assert.equal(patch.status, 200);
  assert.equal(patch.json.applied.effort, 'low');
  assert.deepEqual(patch.json.restart_required, ['port'], 'port flagged restart-required');
  assert.equal(patch.json.effective.effort, 'low', 'hot-applied');

  const onDisk = JSON.parse(readFileSync(statePaths.config(), 'utf8'));
  assert.equal(onDisk.effort, 'low', 'persisted');
  await call('PATCH', '/mgmt/config', { body: { effort: null, port: null } }); // cleanup
});

test('PATCH rejects unknown keys with 400 when nothing applies', async () => {
  const r = await call('PATCH', '/mgmt/config', { body: { bogus: 1 } });
  assert.equal(r.status, 400);
  assert.equal(r.json.rejected.bogus, 'unknown key');
});

test('GET /mgmt/models: catalog, windows, pinned, discovery', async () => {
  const { status, json } = await call('GET', '/mgmt/models');
  assert.equal(status, 200);
  assert.ok(Array.isArray(json.catalog) && json.catalog.length > 0);
  assert.ok(json.context_windows['gpt-5.6-sol'] === 272000);
  assert.equal(json.pinned, 'gpt-5.6-sol');
  assert.ok(json.discovery.every((id) => id.startsWith('claude-codex--')));
});

test('GET /mgmt/compact: stats + shadow tail shapes', async () => {
  const { status, json } = await call('GET', '/mgmt/compact');
  assert.equal(status, 200);
  assert.ok('total' in json.stats && 'by_outcome' in json.stats && Array.isArray(json.stats.tail));
  assert.ok(Array.isArray(json.shadow));
});

test('GET /mgmt/usage and /mgmt/auth respond with introspection shapes', async () => {
  const usage = await call('GET', '/mgmt/usage');
  assert.equal(usage.status, 200);
  assert.equal(usage.json.window_hours, 5);
  const auth = await call('GET', '/mgmt/auth');
  assert.equal(auth.status, 200);
  assert.equal(auth.json.present, false, 'no auth.json in this fixture');
  assert.ok(!JSON.stringify(auth.json).includes('access_token'), 'no token material exposed');
});

test('GET /mgmt/logs: tolerates missing log file', async () => {
  const { status, json } = await call('GET', '/mgmt/logs?tail=10');
  assert.equal(status, 200);
  assert.ok(Array.isArray(json.lines));
});

test('unknown mgmt route → 404', async () => {
  assert.equal((await call('GET', '/mgmt/nope')).status, 404);
});
