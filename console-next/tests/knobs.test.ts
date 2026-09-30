// KNOB COPY WALL, ported from the old console's knob-copy.test.ts. Settings answers "what is this knob"
// from three tables: the label and the group names (lib/words-knobs.ts), the one line of help (also
// words-knobs.ts), and the group, unit and closed values (lib/knobs.ts). All are keyed by the knob's
// key, and the key list is PARSED FROM Knob.kt here, not typed: a knob the daemon grows without words
// fails by name, and so do words left behind for a knob the daemon dropped. The old test's rack and row
// blocks are the widget's (index.tsx), not ported with it; the head-override and layer tests were
// ported with lib/config.ts (config.test.ts).
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, test } from 'vitest';
import { KNOB_SOURCE } from '../src/coverage/denominator';
import { KNOB_META, plural, readableBytes, readableMs, unitText } from '../src/lib/knobs';
import { GROUP_LABELS, KNOB_HELP, KNOB_LABELS } from '../src/lib/words-knobs';

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const knobSource = readFileSync(path.join(repoRoot, KNOB_SOURCE), 'utf8');

/** Each Knob entry as the source spells it: `PORT("port", ...)` or `PORT(\n    "port", ...)`. */
const ENTRY = /^ {4}([A-Z][A-Z0-9_]*)\(\s*"([^"]+)"/gm;

/** The `"key"` each Knob entry opens with. */
function parseKnobKeys(source: string): string[] {
  return [...source.matchAll(ENTRY)].map((match) => match[2] ?? '').sort();
}

/** The keys whose entry declares `headOnly = true`: an entry runs to the next one, or to the enum's end. */
function parseHeadOnlyKeys(source: string): string[] {
  const entries = [...source.matchAll(ENTRY)];
  return entries.flatMap((entry, at) => {
    const part = source.slice(entry.index, entries[at + 1]?.index ?? source.lastIndexOf('}'));
    return /headOnly\s*=\s*true/.test(part) ? [entry[2] ?? ''] : [];
  }).sort();
}

/** What a table is missing and what it has left over, against the daemon's keys. */
function drift(keys: readonly string[], table: Record<string, unknown>): { missing: string[]; extra: string[] } {
  const have = Object.keys(table);
  return {
    missing: keys.filter((key) => !have.includes(key)),
    extra: have.filter((key) => !keys.includes(key)).sort(),
  };
}

const knobKeys = parseKnobKeys(knobSource);

const headOnlyIn = (table: Record<string, { headOnly?: true }>, keys: readonly string[] = Object.keys(table)): string[] =>
  keys.filter((key) => table[key]?.headOnly === true).sort();

describe('every knob Knob.kt declares has words', () => {
  test('head-only knobs in the table match the daemon source, both ways', () => {
    const restricted = parseHeadOnlyKeys(knobSource);
    expect([...knobSource.matchAll(ENTRY)].length).toBe(knobKeys.length);
    expect(headOnlyIn(KNOB_META)).toEqual(restricted);
    expect(restricted).toEqual(['trace', 'wireTap']);
    // A head-only knob added to the daemon and missing from the table is caught by name.
    const withNew = knobSource.replace('    TRACE_RETENTION_DAYS(', '    HEAD_NEW("headNew", KnobKind.BOOL, listOf(), false, headOnly = true),\n    TRACE_RETENTION_DAYS(');
    expect(withNew).not.toBe(knobSource);
    expect(parseHeadOnlyKeys(withNew).filter((key) => KNOB_META[key]?.headOnly !== true)).toEqual(['headNew']);
  });

  test('the parse found the catalogue', () => {
    // A zero means the regex broke, not that the daemon has no knobs.
    expect(knobKeys.length).toBeGreaterThan(40);
    expect(knobKeys).toContain('maxInflight');
    expect(knobKeys).toContain('perfArchiveRetentionDays');
  });

  test('each has a label, and no label is left over', () => {
    expect(drift(knobKeys, KNOB_LABELS)).toEqual({ missing: [], extra: [] });
  });

  test('each has a line of help, and no line is left over', () => {
    expect(drift(knobKeys, KNOB_HELP)).toEqual({ missing: [], extra: [] });
  });

  test('each has a group, and no entry is left over', () => {
    expect(drift(knobKeys, KNOB_META)).toEqual({ missing: [], extra: [] });
  });

  test('the check fails on a knob with no words, and on words with no knob', () => {
    const short = Object.fromEntries(Object.entries(KNOB_HELP).filter(([key]) => key !== 'maxInflight'));
    expect(drift(knobKeys, short).missing).toEqual(['maxInflight']);
    const retired = { ...KNOB_META, retiredKnob: { group: 'records' } };
    expect(drift(knobKeys, retired).extra).toEqual(['retiredKnob']);
  });

  test("every line ends with a stop (its length and case are the copy wall's)", () => {
    expect(Object.entries(KNOB_HELP).filter(([, help]) => !help.endsWith('.')).map(([key]) => key)).toEqual([]);
  });

  test('every group a knob names is a group the rack prints', () => {
    const groups = new Set(Object.keys(GROUP_LABELS));
    expect(Object.entries(KNOB_META).filter(([, meta]) => !groups.has(meta.group)).map(([key]) => key)).toEqual([]);
  });

  test('a picker never lists a value twice', () => {
    const repeated = Object.entries(KNOB_META).filter(([, meta]) => meta.choices !== undefined && new Set(meta.choices).size !== meta.choices.length);
    expect(repeated.map(([key]) => key)).toEqual([]);
  });
});

describe('numbers read the way a person says them', () => {
  test('a count agrees with its noun: one is singular, zero and the rest are plural', () => {
    expect(plural(0, 'byte')).toBe('0 bytes');
    expect(plural(1, 'byte')).toBe('1 byte');
    expect(plural(2, 'byte')).toBe('2 bytes');
  });

  test('milliseconds', () => {
    expect(readableMs(200)).toBe('200 ms');
    expect(readableMs(20_000)).toBe('20 s');
    expect(readableMs(1_500)).toBe('1.5 s');
    expect(readableMs(90_000)).toBe('1 min 30 s');
    expect(readableMs(300_000)).toBe('5 min');
    expect(readableMs(1_800_000)).toBe('30 min');
    expect(readableMs(5_400_000)).toBe('1 h 30 min');
  });

  test('milliseconds at zero and at each unit boundary', () => {
    expect(readableMs(0)).toBe('0 ms');
    expect(readableMs(999)).toBe('999 ms');
    expect(readableMs(1_000)).toBe('1 s');
    expect(readableMs(1_999)).toBe('2 s');
    expect(readableMs(59_900)).toBe('59.9 s');
    expect(readableMs(59_949)).toBe('59.9 s');
    // rounds up to the minute rather than printing 60 s
    expect(readableMs(59_950)).toBe('1 min');
    expect(readableMs(60_000)).toBe('1 min');
    expect(readableMs(60_499)).toBe('1 min');
    expect(readableMs(61_000)).toBe('1 min 1 s');
    expect(readableMs(119_600)).toBe('2 min');
    expect(readableMs(3_540_000)).toBe('59 min');
    expect(readableMs(3_599_600)).toBe('1 h');
    expect(readableMs(3_600_000)).toBe('1 h');
    expect(readableMs(3_660_000)).toBe('1 h 1 min');
  });

  test('bytes, in binary units', () => {
    expect(readableBytes(512)).toBe('512 bytes');
    expect(readableBytes(8 * 1024 * 1024)).toBe('8 MiB');
    expect(readableBytes(1536)).toBe('1.5 KiB');
  });

  test('bytes at zero, singular and each unit boundary', () => {
    expect(readableBytes(0)).toBe('0 bytes');
    expect(readableBytes(1)).toBe('1 byte');
    expect(readableBytes(1_023)).toBe('1023 bytes');
    expect(readableBytes(1_024)).toBe('1 KiB');
    expect(readableBytes(1_048_575)).toBe('1 MiB');
    expect(readableBytes(1_048_576)).toBe('1 MiB');
    expect(readableBytes(1_073_741_824)).toBe('1 GiB');
    // the largest unit is GiB: past it the figure grows instead of changing unit
    expect(readableBytes(2_048 * 1_073_741_824)).toBe('2048 GiB');
  });

  test('a readable form is printed only where the raw number needs one', () => {
    expect(unitText('ms', 200)).toEqual({ suffix: 'ms', readable: null });
    expect(unitText('ms', 1_800_000)).toEqual({ suffix: 'ms', readable: '30 min' });
    expect(unitText('chars', 4_194_304)).toEqual({ suffix: 'characters', readable: '4.2 million' });
    expect(unitText('count', 12)).toEqual({ suffix: null, readable: null });
    expect(unitText(undefined, 12)).toEqual({ suffix: null, readable: null });
  });

  test('unit text: the suffix per unit, and the readable form starting at its boundary', () => {
    expect(unitText('ms', 999)).toEqual({ suffix: 'ms', readable: null });
    expect(unitText('ms', 1_000)).toEqual({ suffix: 'ms', readable: '1 s' });
    expect(unitText('ms', 0)).toEqual({ suffix: 'ms', readable: null });
    expect(unitText('bytes', 1_023)).toEqual({ suffix: 'bytes', readable: null });
    expect(unitText('bytes', 1_024)).toEqual({ suffix: 'bytes', readable: '1 KiB' });
    expect(unitText('chars', 999_999)).toEqual({ suffix: 'characters', readable: null });
    expect(unitText('chars', 1_000_000)).toEqual({ suffix: 'characters', readable: '1.0 million' });
    expect(unitText('days', 30)).toEqual({ suffix: 'days', readable: null });
    expect(unitText('percent', 80)).toEqual({ suffix: '%', readable: null });
    expect(unitText('tokens', 1_000_000)).toEqual({ suffix: 'tokens', readable: null });
    expect(unitText('port', 3096)).toEqual({ suffix: null, readable: null });
  });

  test('a value that is not there prints no unit at all', () => {
    expect(unitText('ms', null)).toEqual({ suffix: null, readable: null });
    expect(unitText(undefined, null)).toEqual({ suffix: null, readable: null });
  });
});
