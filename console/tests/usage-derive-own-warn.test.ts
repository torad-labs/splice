// V4-430: a head's OWN warn (not a plan window's copy) names its real window and reads its reset through
// the console's one time formatter. `provider_reset` (V4-398) is the provider saying "out until <ISO
// instant>": it names no window and its reset is an instant, yet the nearest limit printed it as
// `${window_hours}h` with the instant as-is ("claude-grok 5h ... 2026-10-02T14:00:00Z" for a limit three
// days out). Pinned here: the reset reads `in 3d 0h`, the window is the plan window the instant belongs
// to or no window word at all, and the header sources keep the 5h token window they always named.
import { describe, expect, test } from 'vitest';
import { limitText, nearestLimit } from '../src/features/nearest-limit';
import { headWindow, nearestWindow } from '../src/entities/usage';
import type { HeadUsage, UsagePayload, UsageWarn } from '../src/shared/api';

const NOW_MS = 1_790_000_000_000;
const NOW_S = NOW_MS / 1000;
const THREE_DAYS_MS = 3 * 86_400_000;

const usageOf = (head: Partial<HeadUsage> & { warn: UsageWarn }): UsagePayload => ({
  window_hours: 5,
  warn_pct: 80,
  warn_tokens_5h: 0,
  heads: [{ key: 'grok', label: 'grok', usage: { output_tokens_5h: 0, entries: 0, ratelimit: null, ...head } }],
});

const outFor3Days: UsageWarn = {
  level: 'critical',
  pct: 100,
  source: 'provider_reset',
  reset: new Date(NOW_MS + THREE_DAYS_MS).toISOString(),
};

describe('a head whose own warn is provider_reset', () => {
  test('reads no window and a reset through the one formatter, never 5h and never an instant', () => {
    const nearest = nearestWindow(usageOf({ warn: outFor3Days }), null, NOW_MS);
    expect(nearest).toEqual({ head: 'grok', account: null, window: null, pct: 100, reset: 'in 3d 0h' });
  });

  test('the per-head strip reads the same reset', () => {
    expect(headWindow(usageOf({ warn: outFor3Days }), 'grok', NOW_MS))
      .toEqual({ pct: 100, level: 'critical', reset: 'in 3d 0h' });
  });

  test('the nearest limit prints the head and the reset, with no window word and no gap', () => {
    const limit = nearestLimit({ accounts: [], usage: usageOf({ warn: outFor3Days }), auth: null }, NOW_MS);
    expect(limit === null ? null : limitText(limit)).toBe('grok in 3d 0h');
  });

  test('a reset that is one of the head\'s plan windows names that window', () => {
    const weekly = { seven_day: { used_pct: 100, resets_at: NOW_S + 3 * 86_400 } };
    const nearest = nearestWindow(usageOf({ warn: outFor3Days, quota: weekly }), null, NOW_MS);
    expect(nearest).toEqual({ head: 'grok', account: null, window: '7d', pct: 100, reset: 'in 3d 0h' });
  });
});

describe('the header sources keep the window they always named', () => {
  test('tokens5h is the 5h token window', () => {
    const warn: UsageWarn = { level: 'warn', pct: 90, source: 'tokens5h', reset: null };
    expect(nearestWindow(usageOf({ warn }), null, NOW_MS)).toEqual({ head: 'grok', account: null, window: '5h', pct: 90, reset: null });
  });

  test('a rate-limit header reset is a duration the daemon wrote, not an instant: it passes through', () => {
    const warn: UsageWarn = { level: 'warn', pct: 85, source: 'ratelimit', reset: '6m0s' };
    expect(nearestWindow(usageOf({ warn }), null, NOW_MS)).toEqual({ head: 'grok', account: null, window: '5h', pct: 85, reset: '6m0s' });
  });
});
