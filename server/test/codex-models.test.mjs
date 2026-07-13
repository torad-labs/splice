#!/usr/bin/env node
// Model catalog + context-window resolution — replaces the v29 source-regex
// test (CODEX_MODEL_CONTEXT_WINDOWS is a real exported module now).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  CODEX_MODEL_CONTEXT_WINDOWS,
  CODEX_MODEL_OPTIONS,
  DISCOVERY_PREFIX,
  discoveryModels,
  getContextWindowForModel,
  stripModelSuffixes,
  unwrapCodexModel,
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

test('discoveryModels: wrapped, pinned excluded, labels present', () => {
  const list = discoveryModels('gpt-5.6-sol');
  assert.ok(list.length === CODEX_MODEL_OPTIONS.length - 1);
  assert.ok(list.every((m) => m.id.startsWith(DISCOVERY_PREFIX)));
  assert.ok(!list.some((m) => m.id === `${DISCOVERY_PREFIX}gpt-5.6-sol`));
  assert.ok(list.every((m) => m.display_name));
});
