// The e2e journeys that read a day's facts back (a team's chat and timeline, a project's turns
// today) make their own facts first, and all of it must fall on one UTC day: the daemon's day turns
// at 00:00 UTC, and a gate run that straddled it read an empty chat (train 23, run 36202675360).
// utcDayWait is the guard; this holds its arithmetic, since no journey can move the clock.
import { describe, expect, test } from 'vitest';
import { utcDayWait } from '../e2e/stack';

const MIDNIGHT = Date.UTC(2026, 8, 26);

describe('a journey waits past 00:00 UTC only when its budget would cross it', () => {
  test('far from midnight, no wait', () => {
    expect(utcDayWait(120_000, MIDNIGHT - 10 * 60_000)).toBe(0);
    expect(utcDayWait(120_000, MIDNIGHT + 1)).toBe(0);
  });

  test('inside the budget of midnight, until just past it', () => {
    expect(utcDayWait(120_000, MIDNIGHT - 119_000)).toBe(120_000);
    expect(utcDayWait(120_000, MIDNIGHT - 1)).toBe(1_001);
    // exactly the budget away still crosses it at the budget's last millisecond
    expect(utcDayWait(120_000, MIDNIGHT - 120_000)).toBe(121_000);
  });
});
