import { describe, expect, test } from 'vitest';
import { clockTime, fmtBytes, fmtMs, fmtShare, fmtTokens, fmtUsd, noun, ratio, timeAgo } from '../src/lib/format';

describe('formatting', () => {
  test('sizes, tokens and money at the precision a figure that small needs', () => {
    expect(fmtBytes(512)).toBe('512 B');
    expect(fmtBytes(1536)).toBe('1.5 KiB');
    expect(fmtTokens(1_234_567)).toBe('1.23M');
    expect(fmtUsd(0.4234)).toBe('$0.423');
    expect(fmtUsd(12.3)).toBe('$12.30');
  });
  test('a share never reads a rare failure as none', () => {
    expect(fmtShare(0)).toBe('0%');
    expect(fmtShare(0.00004)).toBe('<0.1%');
    expect(fmtShare(0.024)).toBe('2.4%');
    expect(fmtShare(0.25)).toBe('25%');
  });
  test('durations and ages', () => {
    expect(fmtMs(250)).toBe('250ms');
    expect(fmtMs(2500)).toBe('2.5s');
    expect(fmtMs(125_000)).toBe('2m 5s');
    expect(timeAgo(0, 3_000)).toBe('now');
    expect(timeAgo(0, 7_200_000)).toBe('2h ago');
    expect(timeAgo(0, 3 * 86_400_000)).toBe('3d ago');
  });
  test('a noun agrees with its count, zero included, and a ratio of an empty column is 0', () => {
    expect(noun(1, 'message', 'messages')).toBe('message');
    expect(noun(0, 'message', 'messages')).toBe('messages');
    expect(ratio(3, 0)).toBe(0);
  });
});

describe('a clock time', () => {
  const at = new Date(2026, 8, 29, 15, 4, 59).getTime();
  test('today it prints hour and minute and no seconds', () => {
    expect(clockTime(at, new Date(2026, 8, 29, 23, 59).getTime())).toMatch(/^(0?3:04\s?PM|15:04)$/i);
  });
  test('a time from an earlier day carries its day, and from an earlier year its year', () => {
    const yesterday = clockTime(at, new Date(2026, 8, 30, 0, 1).getTime());
    expect(yesterday).toMatch(/^Sep 29, (0?3:04\s?PM|15:04)$/i);
    expect(clockTime(at, new Date(2027, 0, 2).getTime())).toMatch(/^Sep 29, 2026, (0?3:04\s?PM|15:04)$/i);
  });
});
