// What splice keeps: a UTC day as a date, and a census as sentences that claim nothing the store did not say.
import { describe, expect, test } from 'vitest';
import { ageOutText, entriesOf, keptLines, plansKept, plansKeptLine, plansKeptValue } from '../src/lib/kept';
import type { KeptInventory, TraceInventory } from '../src/types/kept';

const store = (over: Partial<KeptInventory> = {}): KeptInventory => ({ store: 'edges', state: 'on', days: 10, rows: 9227, oldest: '2026-09-19', ages_out: '2026-12-29', ...over });
const trace = (over: Partial<TraceInventory> = {}): TraceInventory => ({ head: 'claudex', state: 'kept', days: 3, records: 3780, bytes: 4_294_967_296, oldest: null, ages_out: null, ...over });

describe('the census', () => {
  test('a UTC day reads as a date in UTC, and a bad one reads as nothing', () => {
    expect(ageOutText('2026-12-29')).toBe('Dec 29');
    expect(ageOutText(null)).toBeNull();
    expect(ageOutText('soon')).toBeNull();
  });
  test('a store with days says how many, and when the last one ages out', () => {
    expect(keptLines(store())).toEqual(['10 days, 9,227 entries kept.', 'The last kept day ages out on Dec 29.']);
  });
  test('large inventories keep their exact count with thousands separators', () => {
    expect(keptLines(store({ rows: 710932, ages_out: null }))).toEqual(['10 days, 710,932 entries kept.']);
    expect(entriesOf(store({ rows: 710932 }))).toBe(710932);
  });
  test('an empty store says nothing is kept and names no ageing', () => {
    expect(keptLines(store({ days: 0, rows: 0 }))).toEqual(['Nothing is kept.']);
  });
  test('a daemon reason rides along, and one day reads as singular', () => {
    expect(keptLines(store({ days: 1, rows: 1, ages_out: null, reason: 'Recording is off.' }))).toEqual(['1 day, 1 entry kept.', 'Recording is off.']);
  });
  test('a trace directory counts records and bytes', () => {
    expect(keptLines(trace())).toEqual(['3,780 records, 4.0 GiB kept.']);
    expect(entriesOf(trace())).toBe(3780);
    expect(entriesOf(store())).toBe(9227);
  });
});

describe('the plans that keep activity labels', () => {
  const keys = ['claudex', 'claude-grok', 'bonsai'];
  test('the knob reads as a set of plans: * is all of them, empty is none, a list is those it names', () => {
    expect([...plansKept('*', keys)]).toEqual(keys);
    expect([...plansKept('', keys)]).toEqual([]);
    expect([...plansKept(' claudex , bonsai ', keys)]).toEqual(['claudex', 'bonsai']);
    expect([...plansKept('claudex,gone', keys)]).toEqual(['claudex']);
  });
  test('the set writes back as the knob: every plan is *, none is empty, else a list in plan order', () => {
    expect(plansKeptValue(new Set(keys), keys)).toBe('*');
    expect(plansKeptValue(new Set(), keys)).toBe('');
    expect(plansKeptValue(new Set(['bonsai', 'claudex']), keys)).toBe('claudex,bonsai');
  });
  test('the summary counts plans in words', () => {
    expect(plansKeptLine(new Set(keys), keys)).toBe('Every command');
    expect(plansKeptLine(new Set(), keys)).toBe('No command');
    expect(plansKeptLine(new Set(['claudex']), keys)).toBe('1 of 3 commands');
    expect(plansKeptLine(new Set(['claudex', 'bonsai']), keys)).toBe('2 of 3 commands');
  });
});
