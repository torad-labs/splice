// Resource store cell: the loading/data/error state machine views subscribe to.
import { describe, expect, test } from 'vitest';
import { createResource, fmtMs, fmtTokens, timeAgo } from '../src/shared/lib';

describe('createResource', () => {
  test('loading only flags before first data; data clears error', () => {
    const cell = createResource<{ n: number }>();
    expect(cell.get()).toEqual({ data: null, error: null, loading: false, lastUpdated: null });

    cell.startLoading();
    expect(cell.get().loading).toBe(true);

    cell.setData({ n: 1 });
    expect(cell.get().data).toEqual({ n: 1 });
    expect(cell.get().loading).toBe(false);
    expect(cell.get().lastUpdated).toBeTypeOf('number');

    cell.setError('boom');
    expect(cell.get().error).toBe('boom');
    expect(cell.get().data).toEqual({ n: 1 }, );

    cell.startLoading();
    expect(cell.get().loading).toBe(false); // stale data stays visible, no skeleton flash

    cell.setData({ n: 2 });
    expect(cell.get().error).toBeNull();
  });
});

describe('formatters', () => {
  test('fmtTokens scales', () => {
    expect(fmtTokens(950)).toBe('950');
    expect(fmtTokens(1500)).toBe('1.5k');
    expect(fmtTokens(231000)).toBe('231k');
    expect(fmtTokens(1_000_000)).toBe('1.00M');
  });
  test('fmtMs scales', () => {
    expect(fmtMs(250)).toBe('250ms');
    expect(fmtMs(1500)).toBe('1.5s');
    expect(fmtMs(90_000)).toBe('1m 30s');
  });
  test('timeAgo buckets', () => {
    const now = 1_000_000_000;
    expect(timeAgo(now - 1000, now)).toBe('now');
    expect(timeAgo(now - 30_000, now)).toBe('30s ago');
    expect(timeAgo(now - 120_000, now)).toBe('2m ago');
  });
});
