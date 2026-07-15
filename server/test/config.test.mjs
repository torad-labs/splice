#!/usr/bin/env node
// Layered config: defaults ← file ← env ← runtime PATCH; hot-apply + persistence.
import { test, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const root = mkdtempSync(join(tmpdir(), 'splice-config-test-'));
process.env.CLAUDEX_STATE_DIR = join(root, 'state');
mkdirSync(process.env.CLAUDEX_STATE_DIR, { recursive: true });
process.env.CODEX_PROXY_TEST = '1';

const { DEFAULTS, getConfig, patchConfig, statePaths, resetRuntimeConfigForTests, RESTART_REQUIRED_KEYS } = await import('../src/config.mjs');

beforeEach(() => {
  resetRuntimeConfigForTests();
  writeFileSync(statePaths.config(), '{}\n');
  delete process.env.CLAUDEX_REASONING_EFFORT;
  delete process.env.CLAUDEX_MAX_INFLIGHT;
});

test('defaults apply when no layer overrides', () => {
  const cfg = getConfig();
  assert.equal(cfg.port, DEFAULTS.port);
  assert.equal(cfg.showReasoning, 'text');
  assert.equal(cfg.maxInflight, 0);
});

test('file layer overrides defaults; env overrides file; patch overrides env', () => {
  writeFileSync(statePaths.config(), JSON.stringify({ effort: 'medium', maxInflight: 4 }));
  let cfg = getConfig();
  assert.equal(cfg.effort, 'medium', 'file beats default');
  assert.equal(cfg.maxInflight, 4);

  process.env.CLAUDEX_REASONING_EFFORT = 'xhigh';
  cfg = getConfig();
  assert.equal(cfg.effort, 'xhigh', 'env beats file (launcher is the boot authority)');

  const { applied, restartRequired } = patchConfig({ effort: 'low' });
  assert.deepEqual(applied, { effort: 'low' });
  assert.deepEqual(restartRequired, []);
  assert.equal(getConfig().effort, 'low', 'runtime PATCH beats env (hot-apply)');
});

test('patch persists to the file layer and flags restart-required keys', () => {
  const { restartRequired } = patchConfig({ port: 4099, streamIdleMs: 60000 });
  assert.deepEqual(restartRequired, ['port'], 'port needs a restart; streamIdleMs is hot');
  const onDisk = JSON.parse(readFileSync(statePaths.config(), 'utf8'));
  assert.equal(onDisk.port, 4099, 'persisted for next boot');
  assert.equal(onDisk.streamIdleMs, 60000);
  assert.ok(RESTART_REQUIRED_KEYS.includes('port'));
});

test('unknown keys and invalid values are rejected, valid ones still apply', () => {
  const { applied, rejected } = patchConfig({ nonsense: 1, maxInflight: 'not-a-number', debug: 'true' });
  assert.equal(rejected.nonsense, 'unknown key');
  assert.equal(rejected.maxInflight, 'invalid value');
  assert.deepEqual(applied, { debug: true });
});

test('maxInflight accepts unlimited/off aliases as 0', () => {
  process.env.CLAUDEX_MAX_INFLIGHT = 'unlimited';
  assert.equal(getConfig().maxInflight, 0);
});

test('normalization: floors, showReasoning aliases', () => {
  patchConfig({ streamIdleMs: 1, authCacheMs: 1, upstreamRetries: 0, showReasoning: 'full' });
  const cfg = getConfig();
  assert.equal(cfg.streamIdleMs, 250, 'test-mode idle floor');
  assert.equal(cfg.authCacheMs, 5000);
  assert.equal(cfg.upstreamRetries, 2, '0 is unset → default (v29: || 2); an explicit 1 is honored');
  assert.equal(cfg.showReasoning, 'text', '"full" aliases to text');
  patchConfig({ streamIdleMs: null, authCacheMs: null, upstreamRetries: null, showReasoning: null });
});
