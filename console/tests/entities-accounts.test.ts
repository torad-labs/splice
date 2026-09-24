// WALLS for the accounts and settings data layer. These are the numbers and sentences that decide
// whether an operator keeps working or hunts a browser login for an account with room, so the two
// that carry the most risk are pinned directly rather than eyeballed on a page:
//
//   - `a missing window is not an empty one`. A provider that reports no usage for a window has
//     said nothing; reading that as 0 puts the account nobody can trust at the top of a headroom
//     sort and hides the one that is nearly spent.
//   - `the next-target mark is the daemon's own`. The pin, then primary, then the session's sticky
//     account, then lowest seven-day used (AccountPool.kt:179-186). A mark computed from a
//     plausible-looking sort instead lands on a strip the daemon will not actually take, which is
//     worse than no mark at all: it is a confident wrong answer about what happens next. So the
//     daemon's `next_target` flag picks the strip and the console only names the rule (M4-08).
//
// The pending-route cases are asserted through the REAL fetch path with a stubbed transport, not
// through the predicate alone: a predicate that returns the right value while the store writes
// something else is exactly the bug this is here to catch.
import { afterEach, describe, expect, test, vi } from 'vitest';
import type { ConfigPayload } from '../src/shared/api';
import { pendingOf } from '../src/shared/api';
import {
  NOT_REPORTED,
  SELECTOR_ORDER_TEXT,
  accountsFromWire,
  accountsStore,
  fetchAccounts,
  nearestOverall,
  nearestWindow,
  nextRuleOf,
  sevenDayUsed,
  windowLengthText,
  windowUsedText,
} from '../src/entities/account';
import type { AccountRow, AccountWindow, AccountWire } from '../src/entities/account';
import { knobDispositions, provenanceOf } from '../src/entities/config';
import { validateTopology } from '../src/entities/topology';

const HOUR_5 = 18000;
const DAY_7 = 604800;
const DAY_30 = 2592000;

function account(over: Partial<AccountRow> = {}): AccountRow {
  return {
    kind: 'chatgpt-oauth',
    label: 'acct-a',
    single_login: false,
    credential_path: null,
    primary: false,
    selected: false,
    available: true,
    pinned: false,
    next_target: false,
    credential_present: true,
    windows: [],
    heads: ['claudex'],
    ...over,
  };
}

function window5h(used: number | null): AccountWindow {
  return { seconds: HOUR_5, used_percent: used, reset_epoch_seconds: 1_800_000_000 };
}

function window7d(used: number | null): AccountWindow {
  return { seconds: DAY_7, used_percent: used, reset_epoch_seconds: null };
}

describe('nearest window', () => {
  test('picks the highest reported used percent', () => {
    const a = account({ windows: [window5h(12), window7d(74)] });
    expect(nearestWindow(a)?.used_percent).toBe(74);
  });

  test('a window the provider does not report is NOT a candidate, never a zero', () => {
    const a = account({ windows: [window5h(null), window7d(74)] });
    expect(nearestWindow(a)?.used_percent).toBe(74);
    expect(windowUsedText(window5h(null))).toBe(NOT_REPORTED);
    expect(windowUsedText(window5h(null))).not.toBe('0%');
  });

  test('an account reporting nothing has no nearest window at all', () => {
    const a = account({ windows: [window5h(null)] });
    expect(nearestWindow(a)).toBeNull();
    expect(nearestOverall([a])).toBeNull();
  });

  test('a tie goes to the shorter window, which resets first', () => {
    const a = account({ windows: [window7d(50), window5h(50)] });
    expect(nearestWindow(a)?.seconds).toBe(HOUR_5);
  });

  test('overall takes the nearest across accounts, ignoring the ones that report nothing', () => {
    const quiet = account({ label: 'quiet', windows: [window5h(null)] });
    const spent = account({ label: 'spent', windows: [window5h(91)] });
    const mild = account({ label: 'mild', windows: [window5h(20)] });
    expect(nearestOverall([quiet, mild, spent])?.account.label).toBe('spent');
  });
});

describe('window labels come from the reported length', () => {
  test('5h, 7d and Grok 30d', () => {
    expect(windowLengthText(HOUR_5)).toBe('5h');
    expect(windowLengthText(DAY_7)).toBe('7d');
    expect(windowLengthText(DAY_30)).toBe('30d');
  });

  test('nothing is ever labelled weekly by position', () => {
    // Grok reports a 30-day period; a console that called the second window "weekly" would be
    // naming a length no provider sent (FEATURES 2.6, GrokQuotaProbe.kt:41-52).
    expect(windowLengthText(DAY_30)).not.toContain('w');
  });
});

// The two row shapes GET /api/accounts sends (AccountsRoute.write), as a live daemon sent them on
// 2026-09-22: a pooled account whose provider reported both windows, and a single-login head whose
// label, flags and windows are all null. The page crashed on the second (`e.windows is not
// iterable`) for as long as the console typed the wire it wished for.
function wireRow(over: Partial<AccountWire> = {}): AccountWire {
  return {
    credential_path: '/home/op/.codex/auth.json',
    kind: 'chatgpt-oauth',
    label: 'work',
    primary: false,
    single_login: false,
    plan: 'plus',
    five_hour_used_percent: 42,
    five_hour_reset_epoch_seconds: 1_800_003_600,
    five_hour_window_seconds: HOUR_5,
    seven_day_used_percent: 7,
    seven_day_reset_epoch_seconds: 1_800_086_400,
    seven_day_window_seconds: DAY_7,
    available: true,
    credential_present: true,
    auth_excluded_until_epoch_millis: null,
    auth_exclusion_reason: null,
    selected: false,
    pinned: false,
    next_target: true,
    heads: ['e2e-codex'],
    ...over,
  };
}

const SINGLE_LOGIN = wireRow({
  label: null,
  primary: true,
  single_login: true,
  plan: null,
  five_hour_used_percent: null,
  five_hour_reset_epoch_seconds: null,
  five_hour_window_seconds: null,
  seven_day_used_percent: null,
  seven_day_reset_epoch_seconds: null,
  seven_day_window_seconds: null,
  available: null,
  selected: null,
  pinned: null,
  next_target: null,
});

describe('the accounts wire becomes the page model', () => {
  test('each reported slot becomes a window at the length the provider reported', () => {
    const [row] = accountsFromWire({ accounts: [wireRow({ seven_day_window_seconds: DAY_30 })] }).accounts;
    expect(row?.windows).toEqual([
      { seconds: HOUR_5, used_percent: 42, reset_epoch_seconds: 1_800_003_600 },
      { seconds: DAY_30, used_percent: 7, reset_epoch_seconds: 1_800_086_400 },
    ]);
  });

  test('a slot the provider reported nothing for is no window, never a zero', () => {
    const [row] = accountsFromWire({ accounts: [SINGLE_LOGIN] }).accounts;
    expect(row?.windows).toEqual([]);
    expect(row === undefined ? null : windowUsedTextOf(row)).toBe(NOT_REPORTED);
  });

  test('a single-login head keeps its nulls and is never a selector candidate', () => {
    const rows = accountsFromWire({ accounts: [SINGLE_LOGIN] }).accounts;
    expect(rows[0]).toMatchObject({ label: null, single_login: true, available: null, selected: null });
    expect(rows.map((row) => nextRuleOf(row, rows))).toEqual([null]);
  });
});

function windowUsedTextOf(row: AccountRow): string {
  const nearest = nearestWindow(row);
  return nearest === null ? NOT_REPORTED : windowUsedText(nearest);
}

describe('the selector order', () => {
  test('is printed as the sentence the daemon implements, the pin first', () => {
    expect(SELECTOR_ORDER_TEXT).toBe('pinned, then primary, then last used, then most weekly room');
  });

  /** Every row's rule in one pool, in order: null for each strip the daemon did not flag. */
  const rules = (pool: AccountRow[]) => pool.map((row) => nextRuleOf(row, pool));

  test('the flag picks the strip: an available primary the daemon did not flag is not marked', () => {
    const primary = account({ label: 'primary', primary: true, windows: [window7d(5)] });
    const pinned = account({ label: 'pinned', pinned: true, next_target: true, windows: [window7d(90)] });
    expect(rules([primary, pinned])).toEqual([null, 'pinned']);
  });

  test('a flagged primary is named primary', () => {
    const primary = account({ label: 'primary', primary: true, next_target: true, windows: [window7d(90)] });
    const roomy = account({ label: 'roomy', windows: [window7d(5)] });
    expect(rules([primary, roomy])).toEqual(['primary', null]);
  });

  test('a flagged account that is the pool\'s lowest seven-day used is named for that rule', () => {
    const primary = account({ label: 'primary', primary: true, available: false });
    const heavy = account({ label: 'heavy', windows: [window7d(80)] });
    const roomy = account({ label: 'roomy', next_target: true, windows: [window7d(5)] });
    expect(rules([primary, heavy, roomy])).toEqual([null, null, 'most weekly room']);
  });

  test('a flagged account that is neither can only be the previous one, the sticky rule', () => {
    const primary = account({ label: 'primary', primary: true, available: false });
    const sticky = account({ label: 'sticky', next_target: true, windows: [window7d(80)] });
    const roomy = account({ label: 'roomy', windows: [window7d(5)] });
    expect(rules([primary, sticky, roomy])).toEqual([null, 'last used', null]);
  });

  test('the lowest is found inside the flagged account\'s own pool, never across pools', () => {
    // `other` rides another head with more room; the flagged account is still its own pool's lowest.
    const flagged = account({ label: 'mine', next_target: true, heads: ['codex-a'], windows: [window7d(40)] });
    const other = account({ label: 'other', heads: ['codex-b'], windows: [window7d(1)] });
    expect(nextRuleOf(flagged, [flagged, other])).toBe('most weekly room');
  });

  test('an account with no seven-day snapshot sorts as zero used, as the daemon does', () => {
    const fresh = account({ label: 'fresh', next_target: true, windows: [] });
    expect(sevenDayUsed(fresh)).toBe(0);
    const used = account({ label: 'used', windows: [window7d(40)] });
    expect(rules([used, fresh])).toEqual([null, 'most weekly room']);
  });

  test('a window present but unreported also sorts as zero, never as unavailable', () => {
    expect(sevenDayUsed(account({ windows: [window7d(null)] }))).toBe(0);
  });

  test("the seven-day SLOT is any window past six hours, as the daemon files it: Grok's 30 days counts", () => {
    const grok = account({ windows: [{ seconds: 5 * 3600, used_percent: 90, reset_epoch_seconds: null }, { seconds: 30 * 86400, used_percent: 55, reset_epoch_seconds: null }] });
    expect(sevenDayUsed(grok)).toBe(55);
  });

  test('a pool the daemon flagged nothing in has no next target, and says so with null', () => {
    expect(rules([account({ available: false }), account({ label: 'b' })])).toEqual([null, null]);
  });
});

describe('pending routes resolve in the store, not in a mock', () => {
  afterEach(() => vi.unstubAllGlobals());

  test('a 404 on /api/accounts lands as { pending: V4-132 }', async () => {
    vi.stubGlobal('fetch', async () => ({
      ok: false, status: 404, json: async () => ({ error: { message: 'unknown route' } }),
    }));
    await fetchAccounts();
    expect(accountsStore.get().data).toEqual({ pending: 'V4-132' });
    expect(accountsStore.get().error).toBeNull();
  });

  test('a named unknown route resolves the same way', async () => {
    vi.stubGlobal('fetch', async () => ({
      ok: false, status: 400, json: async () => ({ error: { message: 'no such route /api/accounts' } }),
    }));
    await fetchAccounts();
    expect(accountsStore.get().data).toEqual({ pending: 'V4-132' });
  });

  test('a 500 is a real error and is NOT dressed up as pending', async () => {
    // Establish a known-good state first, so this asserts the store's real behaviour rather than
    // whatever the previous test happened to leave behind.
    vi.stubGlobal('fetch', async () => ({
      ok: true, status: 200, json: async () => ({ accounts: [wireRow({ label: 'real' })] }),
    }));
    await fetchAccounts();

    vi.stubGlobal('fetch', async () => ({
      ok: false, status: 500, json: async () => ({ error: { message: 'boom' } }),
    }));
    await fetchAccounts();

    expect(accountsStore.get().error).toBe('boom');
    // The last good data stays visible (the store's documented no-skeleton-flash rule), and it is
    // NOT a pending marker: a broken daemon must never read as "this route was never built".
    expect(accountsStore.get().data).toEqual(accountsFromWire({ accounts: [wireRow({ label: 'real' })] }));
  });

  test('a plain Error is not a transport failure and never reads as pending', () => {
    expect(pendingOf(new Error('unknown route'), 'V4-132')).toBeNull();
  });

  test('a successful read leaves no pending marker behind', async () => {
    vi.stubGlobal('fetch', async () => ({
      ok: true, status: 200, json: async () => ({ accounts: [wireRow({ label: 'real' })] }),
    }));
    await fetchAccounts();
    expect(accountsStore.get().data).toEqual(accountsFromWire({ accounts: [wireRow({ label: 'real' })] }));
  });
});

describe('the topology validator', () => {
  // The documented shape (FEATURES.md 2.3), one of every construct it has to walk: a plain table,
  // a table of typed keys, a table keyed by free names, an array of tables, a nested sub-table and
  // a bag whose child keys are deliberately not schema.
  const EXAMPLE = {
    daemon: { control_port: 3096, show_reasoning: 'text', mcp_hosting: true, mcp_hosting_exclude: ['x'] },
    claude: { share: { settings: true, mcps: false }, isolate: { projects: true }, config_dir: '~/.config/splice/claude' },
    compaction: {
      instructions: 'Keep every file path verbatim.',
      model: [{ model: 'gpt-6-astra', file: '~/compaction.md' }],
      project: [{ path: '/home/me/app', model: 'gpt-6-astra', instructions: 'Summarize the plan first.' }],
    },
    defaults: { maxInflight: '4', effort: 'high', statuslineGitRoots: 'a,b' },
    providers: {
      codex: {
        dialect: 'openai-responses',
        base_url: 'https://chatgpt.com/backend-api/codex',
        auth: { kind: 'chatgpt-oauth' },
        quirks: {
          store: false,
          account_id_header: true,
          tool_surface: { enabled: true, defer: ['LSP'], search_limit: 8 },
        },
        extra_headers: { 'x-anything': 'a bag, not a schema' },
        models: [{ id: 'gpt-6-astra', label: 'Astra', context_window: 400000 }],
        rates: { 'gpt-6-astra': { input: 1.25, cache_read: 0.125, output: 10 } },
      },
    },
    heads: {
      claudex: {
        provider: 'codex',
        port: 3100,
        discovery_prefix: 'claudex-',
        models: [{ id: 'gpt-6-astra', slot: 'opus' }],
        overrides: { effort: 'max' },
        claude: { command: 'claude', share: { settings: true } },
        system_prompt: 'you are',
        system_prompt_mode: 'append',
        rates: { 'gpt-6-astra': { input: 1.25, output: 10 } },
      },
    },
  };

  test('accepts the documented example', () => {
    expect(validateTopology(EXAMPLE)).toEqual([]);
  });

  test('rejects an unknown key and names its path', () => {
    expect(validateTopology({ daemon: { control_port: 3096, wibble: true } }))
      .toEqual([{ path: 'daemon.wibble', message: 'unknown key' }]);
  });

  test('reaches into quirks, a nested sub-table', () => {
    expect(validateTopology({ providers: { codex: { quirks: { nope: 1 } } } }))
      .toEqual([{ path: 'providers.codex.quirks.nope', message: 'unknown key' }]);
  });

  test('reaches into an array of tables, which is where much of a real topology lives', () => {
    expect(validateTopology({ providers: { codex: { models: [{ id: 'x', typo: 1 }] } } }))
      .toEqual([{ path: 'providers.codex.models[0].typo', message: 'unknown key' }]);
  });

  test('rejects a misspelled share key, the one failure the daemon reports as silence', () => {
    expect(validateTopology({ claude: { share: { skils: true } } }))
      .toEqual([{ path: 'claude.share.skils', message: 'unknown key' }]);
  });

  test('rejects an unknown top-level table', () => {
    expect(validateTopology({ daemons: {} }))
      .toEqual([{ path: 'daemons', message: 'unknown key' }]);
  });

  test('an unknown knob under [defaults] is still an unknown key', () => {
    expect(validateTopology({ defaults: { maxInflight: '4', wibble: true } }))
      .toEqual([{ path: 'defaults.wibble', message: 'unknown key' }]);
  });

  test('a bag is not key-checked, so a legal extra header stays legal', () => {
    expect(validateTopology({ providers: { codex: { extra_headers: { 'x-anything': '1' } } } })).toEqual([]);
  });

  test('never throws on a shape it does not expect', () => {
    expect(validateTopology(null)).toEqual([]);
    expect(validateTopology('not a topology')).toEqual([]);
    expect(validateTopology({ providers: 'a string where a table belongs' })).toEqual([]);
  });
});

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
    // is not this view's, so the value on screen cannot have come from there — the global default
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
