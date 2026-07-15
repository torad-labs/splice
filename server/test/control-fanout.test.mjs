#!/usr/bin/env node
// Control server config FAN-OUT to a RUNNING head. The other control tests use
// down heads (the file-persist path); this one stands up a mock head on the
// codex port so the PATCH-to-/mgmt/config fan-out path (runtime layer wins over
// env) is actually exercised, plus GET /api/config reading a live head's effective.
import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { mkdtempSync, mkdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { once } from 'node:events';

const { CODEX_PROXY_VERSION } = await import('../src/versions.mjs');

// ── a mock codex head: /health (matching version) + /mgmt/config GET+PATCH ──────
let lastPatchBody = null;
const mockHead = http.createServer((req, res) => {
  const send = (obj) => { res.writeHead(200, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(obj)); };
  if (req.method === 'GET' && req.url.startsWith('/health')) {
    return send({ ok: true, mode: 'codex-proxy', port: 0, version: CODEX_PROXY_VERSION, gate: { inflight: 0 } });
  }
  if (req.url.startsWith('/mgmt/config')) {
    if (req.method === 'PATCH') {
      let body = '';
      req.on('data', (c) => { body += c; });
      req.on('end', () => {
        lastPatchBody = JSON.parse(body || '{}');
        send({ applied: lastPatchBody, rejected: {}, restart_required: [], effective: { ...lastPatchBody } });
      });
      return;
    }
    return send({ effective: { effort: 'medium', pinnedModel: 'gpt-5.6-sol' }, layers: { defaults: {}, file: {}, env: {}, runtime: { effort: 'medium' } }, restart_required_keys: [] });
  }
  res.writeHead(404); res.end();
});
mockHead.listen(0, '127.0.0.1');
await once(mockHead, 'listening');
const mockPort = mockHead.address().port;

const root = mkdtempSync(join(tmpdir(), 'splice-fanout-test-'));
mkdirSync(join(root, 'state'), { recursive: true });
process.env.CONTROL_SERVER_TEST = '1';
process.env.CLAUDEX_STATE_DIR = join(root, 'state');
process.env.CODEX_PROXY_PORT = String(mockPort); // codex head = the mock

const control = await import('../src/control-server.mjs');
const { resetRuntimeConfigForTests } = await import('../src/config.mjs');
const { ensureMgmtKey } = await import('../src/mgmt/api.mjs');

const server = control.createServer();
server.listen(0, '127.0.0.1');
await once(server, 'listening');
const port = server.address().port;
after(() => { server.close(); mockHead.close(); resetRuntimeConfigForTests(); });

const KEY = ensureMgmtKey();
function call(method, path, body) {
  return new Promise((resolve, reject) => {
    const req = http.request({ host: '127.0.0.1', port, path, method, headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${KEY}` } }, (res) => {
      let t = '';
      res.on('data', (c) => { t += c; });
      res.on('end', () => resolve({ status: res.statusCode, json: t ? JSON.parse(t) : null }));
    });
    req.on('error', reject);
    req.end(body ? JSON.stringify(body) : undefined);
  });
}

test('GET /api/heads: the mock reads as a running, version-matched head', async () => {
  const { json } = await call('GET', '/api/heads');
  const codex = json.heads.find((h) => h.key === 'codex');
  assert.equal(codex.running, true);
  assert.equal(codex.healthy, true);
  assert.equal(codex.versionMatch, true);
  assert.equal(codex.port, mockPort);
});

test('PATCH /api/config fans out to the running head (runtime, not just file)', async () => {
  const { status, json } = await call('PATCH', '/api/config', { effort: 'medium' });
  assert.equal(status, 200);
  assert.equal(json.persisted, 'runtime+file', 'a running head was targeted, not the file fallback');
  assert.deepEqual(json.targets, [{ key: 'codex', ok: true }], 'codex head got the PATCH');
  assert.equal(json.applied.effort, 'medium');
  assert.deepEqual(lastPatchBody, { effort: 'medium' }, 'the control server forwarded the exact partial to /mgmt/config');
});

test('GET /api/config reads the running head effective (source=codex)', async () => {
  const { json } = await call('GET', '/api/config');
  assert.equal(json.source, 'codex', 'effective came from the live head, not control defaults');
  assert.equal(json.effective.effort, 'medium');
});
