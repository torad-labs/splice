#!/usr/bin/env node
// Launcher units: section-aware TOML (the sed-replacement), context-window
// resolution, env assembly (the autocompact un-gate), claude-arg policy, and
// the pure handshake/kill-stale decisions.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { lstatSync, mkdirSync, mkdtempSync, readFileSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import {
  AUTO_COMPACT_WINDOW_FLOOR,
  assembleClaudexEnv,
  buildClaudeArgs,
  contextWindowFromModelsCache,
  readTomlKey,
  resolveContextWindow,
} from '../launcher/assemble-env.mjs';
import { prepareClaudexConfig } from '../launcher/prepare-config.mjs';
import { availableModelIds, DEFAULT_CODEX_MODEL } from '../src/models/codex-models.mjs';
import { decideAction, filterSelf, stalePattern } from '../launcher/ensure-proxy.mjs';

const dir = mkdtempSync(join(tmpdir(), 'splice-launcher-test-'));

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

test('claudex env: alias slots are distinct, named, allowlisted picker rows; UNWRAPPED for the 272k window', () => {
  const p = assembleClaudexEnv({ env: { CLAUDEX_MODEL: 'gpt-5.6-sol' }, cachePath, tomlPath });
  // Unwrapped active model — a claude-* id makes Claude Code ignore the context
  // window and compact early (~140k), the bug this reverts.
  assert.equal(p.model, 'gpt-5.6-sol', 'returned model (for --model) is unwrapped');
  assert.equal(p.childEnv.ANTHROPIC_MODEL, 'gpt-5.6-sol');
  assert.ok(!/^claude/.test(p.childEnv.ANTHROPIC_MODEL), 'never claude-prefixed');
  // Distinct targets → no duplicate row; each carries a clean label + subtitle.
  const slots = {
    OPUS: ['gpt-5.6-sol', 'Codex 5.6 Sol'],
    SONNET: ['gpt-5.6-terra', 'Codex 5.6 Terra'],
    HAIKU: ['gpt-5.4-mini', 'Codex 5.4 Mini'],
    FABLE: ['gpt-5.6-luna', 'Codex 5.6 Luna'],
  };
  for (const [slot, [id, name]] of Object.entries(slots)) {
    assert.equal(p.childEnv[`ANTHROPIC_DEFAULT_${slot}_MODEL`], id);
    assert.equal(p.childEnv[`ANTHROPIC_DEFAULT_${slot}_MODEL_NAME`], name, 'clean label, not the raw wrapped id');
    assert.ok(p.childEnv[`ANTHROPIC_DEFAULT_${slot}_MODEL_DESCRIPTION`], 'has a description, not the "Custom X model" fallback');
  }
  const targets = Object.values(slots).map(([id]) => id);
  assert.equal(new Set(targets).size, targets.length, 'all four alias targets distinct — no duplicate picker rows');
  assert.ok(!('ANTHROPIC_CUSTOM_MODEL_OPTION' in p.childEnv), 'availableModels + discovery own the picker now');
  assert.equal(p.proxyEnv.CLAUDEX_PINNED_MODEL, 'gpt-5.6-sol', 'proxy still pinned to the UNWRAPPED id');
});

test('prepareClaudexConfig: writes a claudex-only availableModels allowlist, NEVER touching global settings', () => {
  const home = mkdtempSync(join(tmpdir(), 'splice-home-'));
  const globalDir = join(home, '.claude');
  mkdirSync(globalDir, { recursive: true });
  const globalSettings = join(globalDir, 'settings.json');
  writeFileSync(globalSettings, JSON.stringify({ model: 'opus[1m]', permissions: { allow: ['Bash'] }, hooks: { x: 1 } }));
  writeFileSync(join(globalDir, 'CLAUDE.md'), '# global');

  const configDir = join(home, '.claude-codex');
  mkdirSync(configDir, { recursive: true });
  // Simulate the pre-change state: settings.json symlinked to global.
  symlinkSync(globalSettings, join(configDir, 'settings.json'));

  prepareClaudexConfig({ home });

  const dst = join(configDir, 'settings.json');
  assert.ok(!lstatSync(dst).isSymbolicLink(), 'settings.json is a real file now, not the symlink');
  const written = JSON.parse(readFileSync(dst, 'utf8'));
  assert.deepEqual(written.availableModels, availableModelIds(), 'availableModels = the codex allowlist');
  assert.ok(written.availableModels.every((m) => !/^claude/.test(m)), 'unwrapped ids — else the active model mis-sizes the window');
  assert.equal(written.enforceAvailableModels, true, 'enforced so even the Default row is bounded');
  assert.equal(written.model, DEFAULT_CODEX_MODEL, 'default is unwrapped Sol (keeps the 272k window)');
  assert.deepEqual(written.permissions, { allow: ['Bash'] }, 'global permissions preserved');
  assert.deepEqual(written.hooks, { x: 1 }, 'global hooks preserved');

  // The safety invariant: plain `claude` (global settings) is untouched.
  const globalAfter = JSON.parse(readFileSync(globalSettings, 'utf8'));
  assert.equal(globalAfter.model, 'opus[1m]', 'global model unchanged');
  assert.ok(!('availableModels' in globalAfter), 'global never receives availableModels');
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

test('stalePattern: matches only OUR tagged instance on OUR port', () => {
  const re = new RegExp(stalePattern('codex-proxy.mjs', 3097));
  assert.ok(re.test('node /repo/server/src/codex-proxy.mjs --instance=3097'), 'own side-port instance matches');
  assert.ok(!re.test('node /repo/server/src/codex-proxy.mjs --instance=3099'), 'production new-stack instance does NOT name-match');
  assert.ok(!re.test('node /old/fork/scripts/codex-proxy.mjs'), 'old v29 fork (untagged) does NOT name-match — port-kill only');
  assert.ok(!re.test('node /repo/server/src/codex-proxy.mjs --instance=30971'), 'port prefix collision guarded');
});
