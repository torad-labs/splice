// KNOB COPY WALL. Settings answers "what is this knob" from two tables in widgets/knob-form: the
// label (strings.ts, which the label wall already reads) and the sentence, group and unit
// (copy.ts). Both are keyed by the knob's key, and the key list is PARSED FROM Knob.kt here, not
// typed: a knob the daemon grows without a sentence fails by name, and so does a sentence left
// behind for a knob the daemon dropped. The last describe block covers what the rack does with
// the copy and what a head's view writes.
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createElement as h } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, test } from 'vitest';

import type { KnobDisposition } from '../src/entities/config';
import { globalValueOf, headOptions, shadowOfOverride } from '../src/entities/config/model/store';
import { withHeadOverride } from '../src/pages/settings/model';
import type { ConfigPayload } from '../src/shared/api';
import { KNOB_SOURCE } from '../src/shared/coverage/denominator';
import { HEAD_WORDING, KnobForm, KnobRack, knobMatches } from '../src/widgets/knob-form';
import { KNOB_COPY, readableBytes, readableMs, unitText } from '../src/widgets/knob-form/copy';
import { GROUP_LABELS, KNOB_LABELS } from '../src/widgets/knob-form/strings';

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');

/** The `"key"` each Knob entry opens with: `PORT("port", ...)` or `PORT(\n    "port", ...)`. */
function parseKnobKeys(source: string): string[] {
  return [...source.matchAll(/^\s{4}[A-Z][A-Z0-9_]*\(\s*"([^"]+)"/gm)].map((match) => match[1]).sort();
}

/** What a table is missing and what it has left over, against the daemon's keys. */
function drift(keys: readonly string[], table: Record<string, unknown>): { missing: string[]; extra: string[] } {
  const have = Object.keys(table);
  return {
    missing: keys.filter((key) => !have.includes(key)),
    extra: have.filter((key) => !keys.includes(key)).sort(),
  };
}

const knobKeys = parseKnobKeys(readFileSync(path.join(repoRoot, KNOB_SOURCE), 'utf8'));

describe('every knob Knob.kt declares has words', () => {
  test('the parse found the catalogue', () => {
    // A zero means the regex broke, not that the daemon has no knobs.
    expect(knobKeys.length).toBeGreaterThan(40);
    expect(knobKeys).toContain('maxInflight');
    expect(knobKeys).toContain('perfArchiveRetentionDays');
  });

  test('each has a label, and no label is left over', () => {
    expect(drift(knobKeys, KNOB_LABELS)).toEqual({ missing: [], extra: [] });
  });

  test('each has a sentence, a group and no leftover', () => {
    expect(drift(knobKeys, KNOB_COPY)).toEqual({ missing: [], extra: [] });
  });

  test('the check fails on a knob with no words, and on words with no knob', () => {
    const short = Object.fromEntries(Object.entries(KNOB_COPY).filter(([key]) => key !== 'maxInflight'));
    expect(drift(knobKeys, short).missing).toEqual(['maxInflight']);
    expect(drift(knobKeys, { ...KNOB_COPY, retiredKnob: KNOB_COPY.debug }).extra).toEqual(['retiredKnob']);
  });

  test('every sentence is one: capitalised or a key, ends with a stop, no em-dash', () => {
    const bad = Object.entries(KNOB_COPY).filter(([, copy]) =>
      copy.summary.trim() === '' || !copy.summary.endsWith('.') || copy.summary.includes('—') || copy.summary.length > 200);
    expect(bad.map(([key]) => key)).toEqual([]);
  });

  test('every group a knob names is a group the rack prints', () => {
    const groups = new Set(Object.keys(GROUP_LABELS));
    expect(Object.entries(KNOB_COPY).filter(([, copy]) => !groups.has(copy.group)).map(([key]) => key)).toEqual([]);
  });
});

describe('numbers read the way a person says them', () => {
  test('milliseconds', () => {
    expect(readableMs(200)).toBe('200 ms');
    expect(readableMs(20_000)).toBe('20 s');
    expect(readableMs(1_500)).toBe('1.5 s');
    expect(readableMs(90_000)).toBe('1 min 30 s');
    expect(readableMs(300_000)).toBe('5 min');
    expect(readableMs(1_800_000)).toBe('30 min');
    expect(readableMs(5_400_000)).toBe('1 h 30 min');
  });

  test('bytes, in binary units', () => {
    expect(readableBytes(512)).toBe('512 bytes');
    expect(readableBytes(8 * 1024 * 1024)).toBe('8 MiB');
    expect(readableBytes(1536)).toBe('1.5 KiB');
  });

  test('a readable form is printed only where the raw number needs one', () => {
    expect(unitText('ms', 200)).toEqual({ suffix: 'ms', readable: null });
    expect(unitText('ms', 1_800_000)).toEqual({ suffix: 'ms', readable: '30 min' });
    expect(unitText('chars', 4_194_304)).toEqual({ suffix: 'characters', readable: '4.2 million' });
    expect(unitText('count', 12)).toEqual({ suffix: null, readable: null });
    expect(unitText(undefined, 12)).toEqual({ suffix: null, readable: null });
  });
});

function knob(over: Partial<KnobDisposition> & { key: string }): KnobDisposition {
  return { value: null, provenance: 'default', hot: false, defaultValue: null, overriddenBy: [], ...over };
}

const render = (element: Parameters<typeof renderToStaticMarkup>[0]) => renderToStaticMarkup(element);

describe('the knob row', () => {
  test('leads with the name and the sentence, and keeps the key', () => {
    const html = render(h(KnobForm, { disposition: knob({ key: 'maxInflight', value: 12, defaultValue: 12 }), pending: false, onSave: () => undefined }));
    expect(html).toContain('>concurrent turns<');
    expect(html).toContain('>maxInflight<');
    expect(html).toContain(KNOB_COPY.maxInflight.summary);
    expect(html).not.toContain('>changed<');
  });

  test('a millisecond knob prints its unit and its readable form', () => {
    const html = render(h(KnobForm, { disposition: knob({ key: 'mcpIdleTimeoutMs', value: 1_800_000, defaultValue: 1_800_000 }), pending: false, onSave: () => undefined }));
    expect(html).toContain('ms');
    expect(html).toContain('>30 min<');
  });

  test('a true/false knob is a switch, not a text box', () => {
    const html = render(h(KnobForm, { disposition: knob({ key: 'debug', value: false, defaultValue: false }), pending: false, onSave: () => undefined }));
    expect(html).toContain('role="switch"');
    expect(html).toContain('aria-checked="false"');
    expect(html).not.toContain('<input');
  });

  test('a value off its default says so and offers the way back', () => {
    const html = render(h(KnobForm, { disposition: knob({ key: 'maxInflight', value: 40, defaultValue: 12 }), pending: false, onSave: () => undefined }));
    expect(html).toContain('>changed<');
    expect(html).toContain('>reset to default<');
  });

  test("a head's view names the difference against the global value", () => {
    const html = render(h(KnobForm, { disposition: knob({ key: 'maxInflight', value: 40, defaultValue: 12 }), pending: false, onSave: () => undefined, wording: HEAD_WORDING }));
    expect(html).toContain('>own value<');
    expect(html).toContain('>use global value<');
  });

  test('the rack prints groups in order and the scope note under its row', () => {
    const html = render(h(KnobRack, {
      dispositions: [knob({ key: 'debug', value: false, defaultValue: false }), knob({ key: 'maxInflight', value: 12, defaultValue: 12, overriddenBy: ['claude-grok'] })],
      pending: [],
      busyKey: null,
      onSave: () => undefined,
      scopeNote: (row) => (row.overriddenBy.length > 0 ? 'claude-grok sets its own value.' : null),
    }));
    expect(html.indexOf(GROUP_LABELS.limits)).toBeLessThan(html.indexOf(GROUP_LABELS.records));
    expect(html).toContain('claude-grok sets its own value.');
  });

  test('the finder matches the name, the key and the sentence', () => {
    expect(knobMatches('maxInflight', 'concurrent')).toBe(true);
    expect(knobMatches('maxInflight', 'MAXINFL')).toBe(true);
    expect(knobMatches('maxInflight', 'upstream at the same')).toBe(true);
    expect(knobMatches('maxInflight', 'retry')).toBe(false);
    expect(knobMatches('maxInflight', '  ')).toBe(true);
  });
});

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

describe("a head's own values", () => {
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

  test('an override is written as the string splice.toml holds, and removed cleanly', () => {
    const topology = { heads: { claudex: { port: 3099, overrides: { wireTap: '4' } } } };
    const set = withHeadOverride(topology, 'claudex', 'maxInflight', 40);
    expect(set).toEqual({ heads: { claudex: { port: 3099, overrides: { wireTap: '4', maxInflight: '40' } } } });
    expect(topology.heads.claudex.overrides).toEqual({ wireTap: '4' });

    const removed = withHeadOverride(set, 'claudex', 'wireTap', null);
    expect(removed).toEqual({ heads: { claudex: { port: 3099, overrides: { maxInflight: '40' } } } });

    const empty = withHeadOverride(removed, 'claudex', 'maxInflight', null);
    expect(empty).toEqual({ heads: { claudex: { port: 3099 } } });
  });
});
