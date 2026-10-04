// Ported from the old console's fleet.test.ts and fleet-out-of-quota.test.ts: the blocks that
// exercise only the pure derivations (headAttention, quotaRefusedUntil's callers, providerFamily,
// headWindow, nearestWindow, nextRuleOf, ...) with plain data. Rendering blocks are not ported here.
import { describe, expect, test } from 'vitest';
import { nextRuleOf, poolOf, selectedExcluded } from '../src/lib/accounts';
import {
  ATTENTION_CAUSES,
  EDGE_WORDS,
  FAMILY_NAME,
  NO_SIGNALS,
  familyName,
  headAttention,
  inflightText,
  localInstantText,
  providerFamily,
  queueAtMax,
} from '../src/lib/heads';
import type { HeadSignals } from '../src/lib/heads';
import { headWindow, headsReportingNone, nearestWindow } from '../src/lib/usage';
import type { AccountRow } from '../src/types/accounts';
import type { AuthPayload, GateSnapshot, HeadStatus, UsagePayload } from '../src/types/core';

/** A gate snapshot, so a test can vary one field without a non-null assertion on the parent. */
function gate(over: Partial<GateSnapshot> = {}): GateSnapshot {
  return {
    inflight: 1, queued: 0, max: 4, acquired: 9, released: 8, waited: 1, avg_wait_ms: 20,
    live: [], stream_idle_ms: 30000,
    ...over,
  };
}

function head(over: Partial<HeadStatus> = {}): HeadStatus {
  return {
    key: 'claudex',
    label: 'claudex',
    name: 'claudex',
    port: 3099,
    authKind: 'chatgpt-oauth',
    wantVersion: '0.4.0',
    running: true,
    healthy: true,
    version: '0.4.0',
    versionMatch: true,
    mode: null,
    gate: gate(),
    maxInflight: 4,
    health: { localOriginErrors: 0, providerErrors: 0 },
    pids: [1],
    last_provider_answer: { status: 200, observed_at_epoch_ms: 1, accepted: true },
    ...over,
  };
}

function signals(over: Partial<HeadSignals> = {}): HeadSignals {
  return { ...NO_SIGNALS, ...over };
}

/** One account row, as accountsFromWire builds it from GET /api/accounts. */
function account(over: Partial<AccountRow> = {}): AccountRow {
  return {
    kind: 'chatgpt-oauth',
    label: 'a',
    single_login: false,
    credential_path: null,
    plan: null,
    primary: false,
    selected: false,
    available: true,
    pinned: false,
    next_target: false,
    credential_present: true,
    auth_excluded_until_epoch_millis: null,
    auth_exclusion_reason: null,
    windows: [],
    heads: ['claudex'],
    ...over,
  };
}

describe('one printed cause, and the worst one wins', () => {
  test.each([403, 429, 503])('a real provider refusal %s never reads healthy just because its daemon is running', status => {
    const state = headAttention(head({ last_provider_answer: { status, observed_at_epoch_ms: 1, accepted: false } }), signals());
    expect(state.cause).toBe('provider refused');
    expect(state.edge).not.toBe('green');
  });

  test.each([null, undefined])('an unobserved provider %s is unknown, never accepted', last_provider_answer => {
    const unobserved = head({ last_provider_answer: null });
    if (last_provider_answer === undefined) delete unobserved.last_provider_answer;
    const state = headAttention(unobserved, signals());
    expect(state.cause).toBe('provider unobserved');
    expect(state.cocked).toBe(false);
    expect(state.edge).not.toBe('green');
  });

  test('a healthy head is green and prints ok', () => {
    const state = headAttention(head(), signals());
    expect(state.edge).toBe('green');
    expect(state.cocked).toBe(false);
    expect(state.label).toBe('ok');
  });

  test('every cause in the vocabulary has a case that reaches it', () => {
    const reached = new Set<string>([
      headAttention(head({ healthy: false }), signals()).cause,
      headAttention(head({ versionMatch: false }), signals()).cause,
      headAttention(head(), signals({ credentialPresent: false })).cause,
      headAttention(head({ authKind: 'api-key' }), signals({ credentialPresent: false })).cause,
      headAttention(head(), signals({ refreshLatched: 'refresh failed' })).cause,
      headAttention(head(), signals({ accountExcluded: true })).cause,
      headAttention(head({ gate: gate({ queued: 4, max: 4 }) }), signals()).cause,
      headAttention(head(), signals({ topologyStale: true })).cause,
      headAttention(head({ last_provider_answer: { status: 403, observed_at_epoch_ms: 1, accepted: false } }), signals()).cause,
    ]);
    for (const cause of ATTENTION_CAUSES) expect(reached.has(cause)).toBe(true);
    expect(reached.size).toBe(ATTENTION_CAUSES.length); // and nothing fires that is not in it
  });

  test('unhealthy outranks every warning it is bundled with', () => {
    const state = headAttention(
      head({ healthy: false, versionMatch: false }),
      signals({ credentialPresent: false, topologyStale: true }),
    );
    expect(state.cause).toBe('unhealthy');
    expect(state.edge).toBe('red');
  });

  test('a missing credential outranks the weaker causes below it', () => {
    expect(headAttention(head(), signals({ credentialPresent: false, topologyStale: true })).cause)
      .toBe('signed out');
  });

  test('the provider-reported API-key kind makes a custom head key-missing, not signed-out', () => {
    expect(headAttention(head({ authKind: 'bearer' }), signals({ credentialKind: 'api-key', credentialPresent: false })).cause).toBe('key missing');
  });

  test('a warning is amber and cocked, never red', () => {
    const state = headAttention(head({ versionMatch: false }), signals());
    expect(state.edge).toBe('amber');
    expect(state.cocked).toBe(true);
    expect(state.struck).toBe(false);
    expect(state.label).toBe('mismatch');
    expect(state.cause).toBe('version mismatch');
  });

  test('every edge word fits the 8ch edge whole', () => {
    // Walkthrough S1: `account excluded` printed as `account…` and `signed out` as `signed o…`.
    for (const word of Object.values(EDGE_WORDS)) expect(word.length).toBeLessThanOrEqual(8);
  });

  test('an unhealthy head is the only red', () => {
    for (const cause of ['version mismatch', 'queue full', 'restart needed'] as const) {
      const state = cause === 'version mismatch'
        ? headAttention(head({ versionMatch: false }), signals())
        : cause === 'queue full'
          ? headAttention(head({ gate: gate({ queued: 4, max: 4 }) }), signals())
          : headAttention(head(), signals({ topologyStale: true }));
      expect(state.edge).not.toBe('red');
    }
  });
});

describe('a stopped head is struck, never merely amber', () => {
  test('not running is the strike, and it outranks every attention cause', () => {
    const state = headAttention(head({ running: false, healthy: false, versionMatch: false }), signals({ topologyStale: true }));
    expect(state.struck).toBe(true);
    expect(state.cocked).toBe(false);
    expect(state.edge).toBe('grey');
    expect(state.label).toBe('down');
  });
});

describe('the queue ceiling', () => {
  test('a full queue is at max', () => {
    expect(queueAtMax(head({ gate: gate({ queued: 4, max: 4 }) }))).toBe(true);
  });

  test('an unlimited gate is never at max', () => {
    expect(queueAtMax(head({ gate: { ...gate(), queued: 99, max: 'unlimited' } }))).toBe(false);
  });

  test('a head with no gate has no queue', () => {
    expect(queueAtMax(head({ gate: null }))).toBe(false);
  });
});

describe('nearest window text', () => {
  const usage: UsagePayload = {
    window_hours: 5,
    warn_pct: 80,
    warn_tokens_5h: 0,
    heads: [
      { key: 'claudex', label: 'claudex', usage: { output_tokens_5h: 1, entries: 2, ratelimit: null, warn: { level: 'ok', pct: 12, source: 'headers', reset: null } } },
      { key: 'grok', label: 'grok', usage: { output_tokens_5h: 1, entries: 2, ratelimit: null, warn: { level: 'critical', pct: 96, source: 'headers', reset: '16:40' } } },
      { key: 'silent', label: 'silent', usage: { output_tokens_5h: 1, entries: 1, ratelimit: null, warn: { level: 'ok', pct: 0, source: 'none', reset: null } } },
      { key: 'dark', label: 'dark', usage: null },
    ],
  };
  const auth: AuthPayload = { grok: { kind: 'grok-oauth', login: 'x', present: true, account_id_masked: 'acct-a' } };

  test('takes the highest reported percentage and names its account', () => {
    const nearest = nearestWindow(usage, auth);
    expect(nearest?.head).toBe('grok');
    expect(nearest?.pct).toBe(96);
    expect(nearest?.window).toBe('5h');
    expect(nearest?.account).toBe('acct-a');
    expect(nearest?.reset).toBe('16:40');
  });

  test('a head whose source is none is not a candidate: an absence cannot be nearest', () => {
    const onlySilent: UsagePayload = { ...usage, heads: usage.heads.filter((row) => row.key === 'silent') };
    expect(nearestWindow(onlySilent, auth)).toBeNull();
  });

  test('a head with no usage at all is counted as reporting none', () => {
    expect(headsReportingNone(usage)).toBe(2); // 'silent' (source none) and 'dark' (null usage)
  });

  test('not loaded yet is null, which is not the same as zero heads reporting none', () => {
    expect(headsReportingNone(null)).toBeNull();
  });

  test('a head with no window prints the unknown glyph, never 0%', () => {
    expect(headWindow(usage, 'silent')).toEqual({ pct: null, level: 'none', reset: null });
    expect(headWindow(usage, 'claudex').pct).toBe(12);
  });
});

describe('plan windows count as limits', () => {
  const NOW_MS = 1_790_000_000_000;
  const nowS = NOW_MS / 1000;
  const quiet = { level: 'ok' as const, pct: 0, source: 'none', reset: null };
  const usage: UsagePayload = {
    window_hours: 5,
    warn_pct: 80,
    warn_tokens_5h: 0,
    heads: [
      { key: 'splice', label: 'splice', usage: { output_tokens_5h: 0, entries: 0, ratelimit: null, warn: quiet,
        quota: { five_hour: { used_pct: 65, resets_at: nowS + 3600 }, seven_day: { used_pct: 65, resets_at: nowS + 86400 } } } },
      { key: 'muse', label: 'muse', usage: { output_tokens_5h: 0, entries: 0, ratelimit: null, warn: quiet,
        quota: { seven_day: { used_pct: 99, resets_at: nowS - 60 } } } },
      { key: 'grok', label: 'grok', usage: { output_tokens_5h: 0, entries: 0, ratelimit: null, warn: { level: 'ok', pct: 40, source: 'ratelimit', reset: '6m0s' } } },
      { key: 'dark', label: 'dark', usage: null },
    ],
  };

  test('the rule bar names a plan window when warn reports none, and ties go to the sooner reset', () => {
    const nearest = nearestWindow(usage, null, NOW_MS);
    expect(nearest).toEqual({ head: 'splice', account: null, window: '5h', pct: 65, reset: 'in 1h 0m', resetsAt: nowS + 3600 });
  });

  test('a window whose reset passed is not a candidate: its 99% is from before the reset', () => {
    const onlyMuse: UsagePayload = { ...usage, heads: usage.heads.filter((row) => row.key === 'muse') };
    expect(nearestWindow(onlyMuse, null, NOW_MS)).toBeNull();
    expect(headWindow(onlyMuse, 'muse', NOW_MS)).toEqual({ pct: null, level: 'none', reset: null });
  });

  test('a warn reading that copies a plan window is not offered twice, nor as a 5h window', () => {
    // The warn fold: the daemon reports the 7d plan window as warn with source quota_7d and an ISO
    // reset. The plan window already carries it with its real length and a readable reset.
    const folded: UsagePayload = { ...usage, heads: [{ key: 'claudex', label: 'claudex', usage: {
      output_tokens_5h: 0, entries: 0, ratelimit: null,
      warn: { level: 'warn', pct: 85, source: 'quota_7d', reset: '2026-09-25T10:00:00Z' },
      quota: { seven_day: { used_pct: 85, resets_at: nowS + 86400 } } } }] };
    expect(nearestWindow(folded, null, NOW_MS)).toEqual({ head: 'claudex', account: null, window: '7d', pct: 85, reset: 'in 24h 0m', resetsAt: nowS + 86400 });
    expect(headWindow(folded, 'claudex', NOW_MS)).toEqual({ pct: 85, level: 'warn', reset: 'in 24h 0m' });
  });

  test('a head tracking plan windows is not counted among heads without limits', () => {
    expect(headsReportingNone(usage)).toBe(1); // only 'dark'
  });

  test('the fleet cell takes the fullest of warn and the live plan windows, at the daemon levels', () => {
    expect(headWindow(usage, 'splice', NOW_MS)).toEqual({ pct: 65, level: 'ok', reset: 'in 1h 0m' });
    expect(headWindow(usage, 'grok', NOW_MS)).toEqual({ pct: 40, level: 'ok', reset: '6m0s' });
    const hot: UsagePayload = { ...usage, heads: [{ key: 'hot', label: 'hot', usage: { output_tokens_5h: 0, entries: 0, ratelimit: null, warn: quiet,
      quota: { seven_day: { used_pct: 98, resets_at: nowS + 60 } } } }] };
    expect(headWindow(hot, 'hot', NOW_MS).level).toBe('critical');
  });
});

describe('provider family', () => {
  test('maps each auth kind to its family', () => {
    expect(providerFamily('chatgpt-oauth')).toBe('chatgpt');
    expect(providerFamily('grok-oauth')).toBe('grok');
    expect(providerFamily('kimi-oauth')).toBe('kimi');
    expect(providerFamily('muse-oauth')).toBe('muse');
    expect(providerFamily('client')).toBe('anthropic');
    expect(providerFamily('api-key')).toBe('key');
  });

  test('an unknown kind is local rather than a crash', () => {
    expect(providerFamily('something-new')).toBe('local');
  });

  test('every family prints a distinct name a person would say, and the key family reads api key', () => {
    const names = Object.values(FAMILY_NAME);
    expect(new Set(names).size).toBe(names.length);
    expect(FAMILY_NAME.key).toBe('api key');
    expect(familyName('api-key')).toBe('api key');
    expect(familyName('chatgpt-oauth')).toBe('chatgpt');
  });
});

describe('what one head row prints', () => {
  test('inflightText with no gate prints nothing rather than a zero', () => {
    expect(inflightText(head({ gate: null }))).toBe('');
  });
});

describe('the opened head\'s account pool', () => {
  test('is every row whose heads name the head, and a login shared by two heads rides both', () => {
    const shared = account({ label: 'shared', heads: ['claudex', 'codex'] });
    const own = account({ label: 'own', heads: ['claudex'] });
    const other = account({ label: 'other', heads: ['codex'] });
    expect(poolOf([shared, own, other], 'claudex')).toEqual([shared, own]);
    expect(poolOf([shared, own, other], 'codex')).toEqual([shared, other]);
  });

  test('a head no row names has an empty pool, never another head\'s', () => {
    expect(poolOf([account({ heads: ['codex'] })], 'openrouter')).toEqual([]);
  });
});

describe('the next target is the daemon\'s own answer', () => {
  // AccountsRoute writes next_target = (label == the pool's nextTargetLabel), and the pool walks the
  // pin, then primary, then the caller's previous account, then lowest seven-day used with ties by
  // label (AccountPool.kt:163-186). The mark is the daemon's flag; the rule only explains it.
  /** The flagged row's rule, or null when no row is flagged. */
  const ruleOf = (pool: readonly AccountRow[]) => {
    const target = pool.find((one) => one.next_target === true);
    return target === undefined ? null : nextRuleOf(target, pool);
  };

  /** A seven-day window at `used` percent, the one the daemon's third rule sorts by. */
  const sevenDay = (used: number | null) => ({ seconds: 604800, used_percent: used, reset_epoch_seconds: null });

  test('a pinned target is marked pinned, even over an available primary', () => {
    const pool = [
      account({ label: 'main', primary: true }),
      account({ label: 'work', pinned: true, next_target: true }),
    ];
    expect(ruleOf(pool)).toBe('pinned');
  });

  test('an unpinned primary target is explained by primary', () => {
    expect(ruleOf([account({ label: 'main', primary: true, next_target: true }), account({ label: 'work' })])).toBe('primary');
  });

  test('the lowest seven-day account is explained by that rule, ties broken by label as the daemon sorts', () => {
    const pool = [
      account({ label: 'main', primary: true, available: false }),
      account({ label: 'zeta', windows: [sevenDay(0)] }),
      account({ label: 'beta', windows: [], next_target: true }),
    ];
    expect(ruleOf(pool)).toBe('most weekly room');
  });

  test('a target that is neither pinned, primary nor lowest was the sticky account', () => {
    const pool = [
      account({ label: 'main', primary: true, available: false }),
      account({ label: 'low', windows: [sevenDay(5)] }),
      account({ label: 'held', windows: [sevenDay(60)], next_target: true }),
    ];
    expect(ruleOf(pool)).toBe('last used');
  });
});

describe('account excluded on the rack', () => {
  const NOW_MS = 1_790_000_000_000;

  // HeadSignals.accountExcluded's own contract: the head rides a pool whose SELECTED account is
  // excluded. It was left false while GET /api/accounts was V4-132; the route is served now.
  test('a pool whose selected account is excluded cocks the head with that cause', () => {
    const pool = [account({ label: 'main', selected: true, available: false }), account({ label: 'work' })];
    expect(selectedExcluded(pool, NOW_MS)).toBe(true);
    expect(headAttention(head(), signals({ accountExcluded: selectedExcluded(pool, NOW_MS) })).cause).toBe('account excluded');
  });

  test('an excluded account the pool has not selected does not', () => {
    const pool = [account({ label: 'main', selected: true }), account({ label: 'work', available: false })];
    expect(selectedExcluded(pool, NOW_MS)).toBe(false);
  });

  test('an exclusion that has not lapsed yet counts even while the flag still says available', () => {
    const until = NOW_MS + 60_000;
    expect(selectedExcluded([account({ selected: true, auth_excluded_until_epoch_millis: until })], NOW_MS)).toBe(true);
    expect(selectedExcluded([account({ selected: true, auth_excluded_until_epoch_millis: NOW_MS - 1 })], NOW_MS)).toBe(false);
  });

  test('a single login is judged by no pool, so it never excludes', () => {
    expect(selectedExcluded([account({ label: null, single_login: true, selected: null, available: null })], NOW_MS)).toBe(false);
  });
});

describe('a running local head whose runtime is silent reads Down', () => {
  const local = (key: string, port: number, over: Partial<HeadStatus> = {}): HeadStatus =>
    head({ key, label: key, name: key, authKind: 'api-key', port: 3100 + port, ...over });
  const silent = (key: string, port: number): HeadStatus => local(key, port, { runtimeNotAnswering: `:${port}` });

  test('a stopped head is down first, and an unhealthy one that is also silent reads by the runtime', () => {
    expect(headAttention({ ...silent('bonsai', 8099), running: false }, signals())).toMatchObject({ cause: 'down', struck: true });
    expect(headAttention({ ...silent('bonsai', 8099), healthy: false }, signals()).cause).toBe('runtime not answering');
  });
});

// The provider refuses turns until a known instant: the daemon carries it on the head as
// `quotaResetAtEpochSeconds`. A head at 99% carries nothing and reads OK; a reset already passed is
// over and reads OK; a runtime that does not answer still reads Down first.
describe('a running head whose provider refuses turns until a known instant', () => {
  const NOW_MS = Date.UTC(2026, 9, 1, 18, 0, 0);
  const RESET_S = Date.UTC(2026, 9, 5, 19, 13, 0) / 1000;
  const refused = (over: Partial<HeadStatus> = {}): HeadStatus => head({ quotaResetAtEpochSeconds: RESET_S, ...over });
  const localText = localInstantText(RESET_S);

  test('a head at 99% carries no instant and reads OK; a reset already passed reads OK', () => {
    expect(headAttention(head(), NO_SIGNALS, NOW_MS).cause).toBe('ok');
    expect(headAttention(refused({ quotaResetAtEpochSeconds: NOW_MS / 1000 - 1 }), NO_SIGNALS, NOW_MS).cause).toBe('ok');
  });

  test('a runtime that does not answer, and a stopped head, still read Down first', () => {
    expect(headAttention(refused({ runtimeNotAnswering: ':8099' }), NO_SIGNALS, NOW_MS).cause).toBe('runtime not answering');
    expect(headAttention(refused({ running: false }), NO_SIGNALS, NOW_MS).cause).toBe('down');
  });

  test('the instant prints in the machine zone, in words and no ISO form', () => {
    expect(localInstantText(RESET_S, 'America/Chicago')).toBe('Oct 5, 2:13 PM');
    expect(localText).toBe(localInstantText(RESET_S, Intl.DateTimeFormat().resolvedOptions().timeZone));
    expect(localText).not.toMatch(/\d{4}-\d{2}-\d{2}T/);
  });
});
