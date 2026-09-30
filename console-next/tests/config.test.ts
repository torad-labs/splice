// Walls for the config entity's pure logic (src/lib/config.ts), ported from the old console's
// entities-accounts.test.ts ("knob provenance and the restart verdict"), config-entity.test.ts
// (headOptions) and knob-copy.test.ts ("a head's own values": the three tests that need no page).
// Skipped, being rendering, page models or the fetching stores: the KnobForm/KnobRack render tests,
// "an override is written as the string splice.toml holds" (pages/settings withHeadOverride),
// settings.test.ts, config-patch.test.ts, v4372-pending-knob.test.ts, keyed-reads.test.ts, and every
// test of v4379-restart-pending.test.ts (they drive the zustand restart store through fetch).
//
// The last block is NEW: the restart store's transitions were extracted as pure reducers, and the
// semantics v4379-restart-pending.test.ts pins through the store are pinned here on the reducers.
import { describe, expect, test } from 'vitest';
import {
  NO_RESTART_PENDING,
  clearRestartPending,
  diffPatch,
  globalValueOf,
  headOptions,
  knobDispositions,
  markRestartPending,
  observeDaemonBoot,
  parseConfigInput,
  provenanceOf,
  shadowOfOverride,
} from '../src/lib/config';
import type { ConfigPayload } from '../src/types/core';

describe('knob provenance and the restart verdict', () => {
  const payload: ConfigPayload = {
    effective: { maxInflight: 4, effort: 'high', mirrorReasoning: false },
    layers: {
      defaults: { maxInflight: 2, effort: 'high', mirrorReasoning: false },
      toml: { effort: 'high' },
      perHead: { claudex: { maxInflight: 4 } },
      file: {},
      env: { mirrorReasoning: false },
      runtime: {},
    },
    restart_required_keys: ['effort'],
    source: 'test',
  };

  function disposition(key: string) {
    const found = knobDispositions(payload).find((knob) => knob.key === key);
    if (found === undefined) throw new Error(`no disposition for ${key}`);
    return found;
  }

  test('the strongest layer that carries the key wins', () => {
    expect(provenanceOf('effort', payload)).toBe('defaults table'); // a layer above the default
    expect(provenanceOf('mirrorReasoning', payload)).toBe('env');
  });

  test('a key no layer carries has no provenance, rather than a confident default', () => {
    expect(provenanceOf('wibble', payload)).toBeNull();
  });

  test('a head override is only reachable when the view was fetched for that head', () => {
    // The same knob and the same payload. With no head, or with another head, the per-head layer
    // is not this view's, so the value on screen cannot have come from there; the global default
    // is what it is. Only asking for the overriding head reaches the strongest layer.
    expect(provenanceOf('maxInflight', payload)).toBe('default');
    expect(provenanceOf('maxInflight', payload, 'someone-else')).toBe('default');
    expect(provenanceOf('maxInflight', payload, 'claudex')).toBe('head override');
  });

  test('hot comes from restart_required_keys, never from a hand list', () => {
    expect(disposition('maxInflight').hot).toBe(true);
    expect(disposition('effort').hot).toBe(false);
  });

  test('every effective knob is dispositioned, not just the ones a page happens to list', () => {
    expect(knobDispositions(payload).map((knob) => knob.key)).toEqual([
      'effort', 'maxInflight', 'mirrorReasoning',
    ]);
  });
});

describe('headOptions (JW-06)', () => {
  test('is global-only when no head overrides anything', () => {
    expect(headOptions(undefined)).toEqual(['global']);
    expect(headOptions({})).toEqual(['global']);
  });

  test('lists override-carrying heads after global, sorted', () => {
    expect(headOptions({ kimi: { maxInflight: 8 }, claudex: { effort: 'high' } })).toEqual([
      'global',
      'claudex',
      'kimi',
    ]);
  });
});

describe("a head's own values", () => {
  const payload: ConfigPayload = {
    effective: { maxInflight: 100, upstreamRetries: 4, debug: true },
    layers: {
      defaults: { maxInflight: 12, upstreamRetries: 4, debug: false },
      toml: { upstreamRetries: 6 },
      perHead: { 'claude-grok': { maxInflight: 100 } },
      file: { upstreamRetries: 4 },
      env: { debug: true },
      runtime: {},
    },
    restart_required_keys: ['upstreamRetries', 'debug'],
    source: 'test',
  };

  test('the global value leaves the per-head layer out', () => {
    expect(globalValueOf('maxInflight', payload)).toBe(12);
    expect(globalValueOf('upstreamRetries', payload)).toBe(4);
  });

  test('a console or environment value is named as what outranks an override', () => {
    expect(shadowOfOverride('upstreamRetries', payload)).toBe('console');
    expect(shadowOfOverride('debug', payload)).toBe('environment');
    expect(shadowOfOverride('maxInflight', payload)).toBeNull();
  });

  test('every declared head is selectable, not only those with overrides', () => {
    expect(headOptions(payload.layers.perHead, ['claudex', 'claude-grok'])).toEqual(['global', 'claude-grok', 'claudex']);
  });
});

// NEW: no old test drove these two directly.
describe('the patch diff and the edited-string parse', () => {
  test('a patch lists only what changes, and marks the keys that wait for a restart', () => {
    expect(diffPatch({ maxInflight: 4, effort: 'high' }, { maxInflight: 4, effort: 'max' }, ['effort']))
      .toEqual([{ key: 'effort', from: 'high', to: 'max', restartRequired: true }]);
    expect(diffPatch({}, { maxInflight: 8 }, [])).toEqual([{ key: 'maxInflight', from: undefined, to: 8, restartRequired: false }]);
  });

  test('an edited string parses back into the value space of the knob it edits', () => {
    expect(parseConfigInput('', 'x')).toBeNull();
    expect(parseConfigInput('null', 4)).toBeNull();
    expect(parseConfigInput('true', 'x')).toBe(true);
    expect(parseConfigInput('no', false)).toBe(false);
    expect(parseConfigInput('12', 'x')).toBe(12);
    expect(parseConfigInput('high', 'x')).toBe('high');
  });
});

// NEW: the restart store's transitions as pure reducers (v4379-restart-pending.test.ts pins the same
// semantics through the store and fetch).
describe('a pending setting belongs to one daemon boot', () => {
  test('marking records the keys sorted and deduped, under the boot they reached', () => {
    const marked = markRestartPending(markRestartPending(NO_RESTART_PENDING, ['trace', 'debug'], 1_000), ['trace'], 1_000);
    expect(marked.pending).toEqual(['debug', 'trace']);
    expect(marked.pendingAtEpochMillis).toBe(1_000);
  });

  test('marking no keys changes nothing', () => {
    expect(markRestartPending(NO_RESTART_PENDING, [], 1_000)).toBe(NO_RESTART_PENDING);
  });

  test('a later boot clears the list; the same boot preserves it', () => {
    const marked = markRestartPending(observeDaemonBoot(NO_RESTART_PENDING, 1_000), ['trace']);
    expect(marked.pendingAtEpochMillis).toBe(1_000);
    expect(observeDaemonBoot(marked, 1_000).pending).toEqual(['trace']);
    const later = observeDaemonBoot(marked, 2_000);
    expect(later.pending).toEqual([]);
    expect(later.pendingAtEpochMillis).toBeNull();
    expect(later.bootedAtEpochMillis).toBe(2_000);
  });

  test('a write that reached a newer boot drops what an older boot was holding', () => {
    const older = markRestartPending(NO_RESTART_PENDING, ['debug'], 7_000);
    const newer = markRestartPending(older, ['trace'], 8_000);
    expect(newer.pending).toEqual(['trace']);
    expect(newer.pendingAtEpochMillis).toBe(8_000);
  });

  test('an unread boot never clears a pending key', () => {
    const marked = markRestartPending(NO_RESTART_PENDING, ['trace'], null);
    expect(marked.pending).toEqual(['trace']);
    expect(marked.pendingAtEpochMillis).toBeNull();
    expect(observeDaemonBoot(marked, 1_000).pending).toEqual(['trace']);
  });

  test('clearing drops the list and the boot it was pending under', () => {
    const cleared = clearRestartPending(markRestartPending(NO_RESTART_PENDING, ['trace'], 1_000));
    expect(cleared.pending).toEqual([]);
    expect(cleared.pendingAtEpochMillis).toBeNull();
  });
});
