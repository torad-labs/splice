#!/usr/bin/env node
// Model catalog + context-window resolution — replaces the v29 source-regex
// test (CODEX_MODEL_CONTEXT_WINDOWS is a real exported module now).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  CODEX_MODEL_CONTEXT_WINDOWS,
  CODEX_MODEL_OPTIONS,
  DISCOVERY_PREFIX,
  availableModelIds,
  discoveryModels,
  getContextWindowForModel,
  stripModelSuffixes,
  unwrapCodexModel,
  wrapCodexModel,
} from '../src/models/codex-models.mjs';

test('gpt-5.5 and gpt-5.4 registered with flagship-scale windows', () => {
  for (const id of ['gpt-5.5', 'gpt-5.4']) {
    const v = CODEX_MODEL_CONTEXT_WINDOWS[id];
    assert.ok(Number.isFinite(v) && v >= 100_000, `${id} window must be >=100k, got ${v}`);
  }
});

test('exact match beats prefix rules; spark resolves 128k', () => {
  assert.equal(getContextWindowForModel('gpt-5.3-codex-spark'), 128_000);
  assert.equal(getContextWindowForModel('gpt-5.6-sol'), 272_000);
  assert.equal(getContextWindowForModel('gpt-5.5-1m'), 1_000_000, '1m id exact entry wins over gpt-5.5 prefix');
});

test('explicit prefix rules cover unlisted family members; no substring fuzz', () => {
  assert.equal(getContextWindowForModel('gpt-5.6-nova'), 272_000, 'unlisted 5.6 variant via prefix rule');
  assert.equal(getContextWindowForModel('experimental-gpt-5.6'), 272_000, 'no substring matching: falls to default (which happens to equal 272k)');
  assert.equal(getContextWindowForModel('totally-unknown', 50_000), 50_000, 'unknown id → provided default');
});

test('discovery wrap/unwrap + [1m] suffix strip', () => {
  assert.equal(unwrapCodexModel(`${DISCOVERY_PREFIX}gpt-5.6-luna`), 'gpt-5.6-luna');
  assert.equal(stripModelSuffixes(`${DISCOVERY_PREFIX}gpt-5.5[1m]`), 'gpt-5.5');
  assert.equal(stripModelSuffixes('gpt-5.4'), 'gpt-5.4');
});

test('discoveryModels: every model wrapped with a label, none excluded', () => {
  const list = discoveryModels();
  assert.equal(list.length, CODEX_MODEL_OPTIONS.length, 'all catalog models surfaced (pinned no longer excluded)');
  assert.ok(list.every((m) => m.id.startsWith(DISCOVERY_PREFIX)), 'ids are claude-codex-- wrapped');
  assert.ok(list.some((m) => m.id === `${DISCOVERY_PREFIX}gpt-5.6-sol`), 'pinned Sol now included (it needs a discovery row for its label)');
  assert.ok(list.every((m) => m.display_name), 'every row carries a display_name label');
});

test('availableModelIds: UNWRAPPED catalog ids for the settings allowlist, no Claude aliases', () => {
  const ids = availableModelIds();
  assert.deepEqual(ids, CODEX_MODEL_OPTIONS.map((m) => m.value), 'plain gpt-* ids');
  // Unwrapped is load-bearing: a claude-* wrapped active model makes Claude Code
  // ignore CLAUDE_CODE_MAX_CONTEXT_TOKENS and compact early.
  assert.ok(ids.every((id) => !id.startsWith(DISCOVERY_PREFIX)), 'never wrapped');
  assert.equal(wrapCodexModel('gpt-5.6-sol'), `${DISCOVERY_PREFIX}gpt-5.6-sol`, 'the wrap helper still exists for /v1/models discovery');
  // No opus/sonnet/haiku/fable in the allowlist — omission is what hides them.
  assert.ok(!ids.some((id) => /opus|sonnet|haiku|fable/i.test(id)));
});

test('speculative models dropped from the catalog (they would 404)', () => {
  const values = CODEX_MODEL_OPTIONS.map((m) => m.value);
  assert.ok(!values.includes('gpt-5.6'), 'base gpt-5.6 removed');
  assert.ok(!values.includes('gpt-5.3-mini'), 'gpt-5.3-mini removed');
  assert.deepEqual(values, ['gpt-5.6-sol', 'gpt-5.6-terra', 'gpt-5.6-luna', 'gpt-5.5', 'gpt-5.4', 'gpt-5.4-mini', 'gpt-5.3-codex-spark']);
});
