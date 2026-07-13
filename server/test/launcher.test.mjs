#!/usr/bin/env node
// Launcher units: section-aware TOML (the sed-replacement), context-window
// resolution, env assembly (the autocompact un-gate), claude-arg policy, and
// the pure handshake/kill-stale decisions.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import {
  AUTO_COMPACT_WINDOW_FLOOR,
  assembleClaudexEnv,
  assembleClaudithosEnv,
  buildClaudeArgs,
  contextWindowFromModelsCache,
  readTomlKey,
  resolveContextWindow,
} from '../launcher/assemble-env.mjs';
import { decideAction, filterSelf } from '../launcher/ensure-proxy.mjs';

const dir = mkdtempSync(join(tmpdir(), 'mythos-launcher-test-'));

// ── TOML: the sed bug this replaces ──────────────────────────────────────────

const TOML = `
# codex config
model = "gpt-5.6-sol"
model_reasoning_effort = "high"

[profiles.fast]
model_reasoning_effort = "low"
model_context_window = 128000

[mcp_servers.thing]
command = "x"
`;

test('readTomlKey: root keys only — never leaks values from [sections] (sed bug)', () => {
  assert.equal(readTomlKey(TOML, 'model_reasoning_effort'), 'high');
  assert.equal(readTomlKey(TOML, 'model_context_window'), '', 'root has no window; sed would have grabbed 128000 from [profiles.fast]');
  assert.equal(readTomlKey(TOML, 'model_reasoning_effort', 'profiles.fast'), 'low', 'sections readable when asked explicitly');
});

test('readTomlKey: quoted values, bare values, trailing comments, missing file', () => {
  assert.equal(readTomlKey('a = "x y" # c', 'a'), 'x y');
  assert.equal(readTomlKey('a = 42 # answer', 'a'), '42');
  assert.equal(readTomlKey("a = 'single'", 'a'), 'single');
  assert.equal(readTomlKey('', 'a'), '');
});

// ── models_cache + window resolution ─────────────────────────────────────────

const cachePath = join(dir, 'models_cache.json');
writeFileSync(cachePath, JSON.stringify({
  models: [
    { slug: 'gpt-5.6-sol', context_window: 272000 },
    { slug: 'gpt-5.3-codex-spark', context_window: 128000 },
  ],
}));
const tomlPath = join(dir, 'config.toml');
writeFileSync(tomlPath, 'model_context_window = 200000\n');

test('contextWindowFromModelsCache: exact slug, then substring, else null', () => {
  assert.equal(contextWindowFromModelsCache('gpt-5.6-sol', cachePath), 272000);
  assert.equal(contextWindowFromModelsCache('spark', cachePath), 128000, 'substring fallback');
  assert.equal(contextWindowFromModelsCache('nope', cachePath), null);
});

test('resolveContextWindow precedence: env → models_cache → toml → default; ceiling caps', () => {
  assert.equal(resolveContextWindow({ model: 'gpt-5.6-sol', env: { CLAUDEX_CONTEXT_WINDOW: '250000' }, cachePath, tomlPath }), 250000);
  assert.equal(resolveContextWindow({ model: 'gpt-5.6-sol', env: {}, cachePath, tomlPath }), 272000, 'models_cache wins over toml');
  assert.equal(resolveContextWindow({ model: 'unknown-model', env: {}, cachePath, tomlPath }), 200000, 'toml fallback');
  assert.equal(resolveContextWindow({ model: 'unknown-model', env: {}, cachePath: join(dir, 'missing.json'), tomlPath: join(dir, 'missing.toml') }), 272000, 'default');
  assert.equal(
    resolveContextWindow({ model: 'x', env: { CLAUDEX_CONTEXT_WINDOW: '1000000' }, cachePath, tomlPath }),
    272000,
    'ceiling caps at 272k unless CLAUDEX_CONTEXT_CEILING raises it',
  );
  assert.equal(
    resolveContextWindow({ model: 'x', env: { CLAUDEX_CONTEXT_WINDOW: '1000000', CLAUDEX_CONTEXT_CEILING: '1000000' }, cachePath, tomlPath }),
    1000000,
  );
});

// ── env assembly: the autocompact un-gate (fix #1) ───────────────────────────

test('claudex child env carries CLAUDE_CODE_AUTO_COMPACT_WINDOW (the un-gate), floored at 100k', () => {
  const p = assembleClaudexEnv({ env: { CLAUDEX_MODEL: 'gpt-5.6-sol' }, cachePath, tomlPath });
  assert.equal(p.childEnv.CLAUDE_CODE_AUTO_COMPACT_WINDOW, '272000');
  assert.equal(p.childEnv.CLAUDE_CODE_MAX_CONTEXT_TOKENS, '272000', 'kept for unwrapped gpt-* names');
  assert.equal(p.childEnv.CLAUDE_AUTOCOMPACT_PCT_OVERRIDE, '85');
  assert.equal(p.childEnv.CLAUDE_CODE_ENABLE_GATEWAY_MODEL_DISCOVERY, '1');
  assert.equal(p.childEnv.ANTHROPIC_AUTH_TOKEN, 'codex-local');
  assert.ok(p.childUnset.includes('ANTHROPIC_API_KEY'));

  const small = assembleClaudexEnv({ env: { CLAUDEX_MODEL: 'x', CLAUDEX_CONTEXT_WINDOW: '50000' }, cachePath, tomlPath });
  assert.equal(small.childEnv.CLAUDE_CODE_AUTO_COMPACT_WINDOW, String(AUTO_COMPACT_WINDOW_FLOOR), 'floor 100k');
  assert.equal(small.childEnv.CLAUDE_CODE_MAX_CONTEXT_TOKENS, '50000', 'max-context reports the real resolved window');
});

test('claudex proxy env pins the model and mirrors the reasoning knobs', () => {
  const p = assembleClaudexEnv({ env: { CLAUDEX_MODEL: 'gpt-5.6-luna', CLAUDEX_REASONING_EFFORT: 'xhigh' }, cachePath, tomlPath });
  assert.equal(p.proxyEnv.CLAUDEX_PINNED_MODEL, 'gpt-5.6-luna');
  assert.equal(p.proxyEnv.CLAUDEX_REASONING_EFFORT, 'xhigh');
  assert.equal(p.proxyEnv.CODEX_PROXY_PORT, '3099');
});

test('claudithos env: arm validated, defaults mirror, no model overrides', () => {
  const p = assembleClaudithosEnv({ env: { CLAUDITHOS_MODE: 'weird' } });
  assert.equal(p.mode, 'mirror');
  const a = assembleClaudithosEnv({ env: { CLAUDITHOS_MODE: 'amnesia' } });
  assert.equal(a.proxyEnv.CLAUDITHOS_MODE, 'amnesia');
  assert.equal(a.childEnv.ANTHROPIC_AUTH_TOKEN, 'mythos-local');
  assert.ok(!('ANTHROPIC_MODEL' in a.childEnv), 'normal default model applies from shared settings');
});

// ── claude arg policy ────────────────────────────────────────────────────────

test('buildClaudeArgs: skip-permissions default, CLAUDEX_SAFE opt-out, model injection', () => {
  assert.deepEqual(
    buildClaudeArgs(['-c'], { defaultModel: 'gpt-5.6-sol', env: {} }),
    ['--model', 'gpt-5.6-sol', '--dangerously-skip-permissions', '-c'],
  );
  assert.deepEqual(
    buildClaudeArgs([], { defaultModel: 'gpt-5.6-sol', env: { CLAUDEX_SAFE: '1' } }),
    ['--model', 'gpt-5.6-sol'],
  );
  assert.deepEqual(
    buildClaudeArgs(['--model', 'gpt-5.4'], { defaultModel: 'gpt-5.6-sol', env: { CLAUDEX_SAFE: '1' } }),
    ['--model', 'gpt-5.4'],
    'user model wins',
  );
  assert.deepEqual(
    buildClaudeArgs(['--dangerously-skip-permissions'], { env: {} }),
    ['--dangerously-skip-permissions'],
    'no duplicate skip flag',
  );
});

// ── handshake + kill-stale decisions (pure) ──────────────────────────────────

test('decideAction: start/restart/patch-mode/ok', () => {
  assert.equal(decideAction({ health: null, wantVersion: '30' }), 'start');
  assert.equal(decideAction({ health: { ok: true, version: '29' }, wantVersion: '30' }), 'restart');
  assert.equal(decideAction({ health: { ok: true, version: '3', mode: 'mirror' }, wantVersion: '3', wantMode: 'amnesia' }), 'patch-mode');
  assert.equal(decideAction({ health: { ok: true, version: '3', mode: 'amnesia' }, wantVersion: '3', wantMode: 'amnesia' }), 'ok');
  assert.equal(decideAction({ health: { ok: true, version: '30' }, wantVersion: '30' }), 'ok');
});

test('filterSelf: never targets our own pid/ppid, pid 1, or garbage', () => {
  assert.deepEqual(filterSelf([100, 200, 300, 1, NaN], { pid: 200, ppid: 300 }), [100]);
});
