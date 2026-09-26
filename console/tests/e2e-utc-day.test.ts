// The e2e journeys that read a day's facts back (a team's chat and timeline, a project's turns
// today) make their own facts first, and all of it must fall on one day: a project's day turns at
// 00:00 UTC, the Teams board's at the viewer's midnight (V4-249), and a gate run that straddled one
// read an empty chat (train 23, run 36202675360). utcDayWait and localDayWait are the guards; this
// holds their arithmetic, since no journey can move the clock.
import { afterEach, describe, expect, test } from 'vitest';
import { localDayWait, utcDayWait } from '../e2e/stack';

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

describe('the Teams journey waits past LOCAL midnight instead (V4-249: the board reads the viewer\'s day)', () => {
  const zone = process.env.TZ;
  afterEach(() => {
    if (zone === undefined) delete process.env.TZ;
    else process.env.TZ = zone;
  });

  test('in Chicago the wait is for 05:00 UTC in September, and 00:00 UTC is no boundary', () => {
    process.env.TZ = 'America/Chicago';
    const local = Date.UTC(2026, 8, 26, 5);
    expect(localDayWait(120_000, local - 1)).toBe(1_001);
    expect(localDayWait(120_000, local - 10 * 60_000)).toBe(0);
    expect(localDayWait(120_000, MIDNIGHT - 1)).toBe(0);
  });
});
