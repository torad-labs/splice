// WALLS for the fleet page. The load-bearing one is `one printed cause, and the worst one wins`:
// ARRIVE on this page is "which head needs me", and a strip that printed a healthy edge while a
// head was actually unhealthy would send the operator past the only thing they opened the page for.
//
// The second is `a stopped head is struck, never merely amber`. A down head is not a warning; it is
// a head that cannot take work, and the world's grammar makes that the strike.
//
// A `.ts` test cannot hold JSX (TS1161), so elements are built with React.createElement and
// asserted against renderToStaticMarkup's string.
import * as React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, test } from 'vitest';
import { NOT_REPORTED } from '../src/entities/account';
import type { AccountRow } from '../src/entities/account';
import {
  ATTENTION_CAUSES,
  headAttention,
  inflightText,
  liveTurnText,
  providerFamily,
  queueAtMax,
  NO_SIGNALS,
  FAMILY_NAME,
} from '../src/entities/heads';
import type { HeadSignals } from '../src/entities/heads';
import { headWindow, headsReportingNone, nearestWindow } from '../src/entities/usage';
import { HeadStrip, providerText } from '../src/widgets/head-strip';
import { EMPTIES, arrangeHeads, columnsOf, dialectOf, poolEmpty, poolNext, poolOf, selectedExcluded } from '../src/pages/fleet/model';
import { dispositions } from '../src/pages/fleet/coverage';
import { Empty } from '../src/shared/ui';
import type { AuthPayload, GateSnapshot, HeadStatus, UsagePayload } from '../src/shared/api';
import type { View } from '../src/features/views';

const h = React.createElement;
const render = (el: React.ReactElement): string => renderToStaticMarkup(el);

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
    ...over,
  };
}

function signals(over: Partial<HeadSignals> = {}): HeadSignals {
  return { ...NO_SIGNALS, ...over };
}

describe('one printed cause, and the worst one wins', () => {
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
      headAttention(head(), signals({ refreshLatched: 'refresh failed' })).cause,
      headAttention(head(), signals({ accountExcluded: true })).cause,
      headAttention(head({ gate: gate({ queued: 4, max: 4 }) }), signals()).cause,
      headAttention(head(), signals({ topologyStale: true })).cause,
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

  test('a warning is amber and cocked, never red', () => {
    const state = headAttention(head({ versionMatch: false }), signals());
    expect(state.edge).toBe('amber');
    expect(state.cocked).toBe(true);
    expect(state.struck).toBe(false);
    expect(state.label).toBe('version mismatch');
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

  // NAMED FOR WHAT IT ASSERTS (M2-29). This read `prints not reported by provider`, a sentence
  // no code path produces: windowText returns NOT_REPORTED and NOT_REPORTED is the single word
  // `unknown`. The assertion was always right and the name described the phrase this widget was
  // moved off; a test whose name and assertion disagree is read by the next person as the name.
  test('a head with no window prints the unknown glyph, never 0%', () => {
    expect(headWindow(usage, 'silent')).toEqual({ pct: null, level: 'none', reset: null });
    expect(headWindow(usage, 'claudex').pct).toBe(12);
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
    expect(providerText('api-key')).toBe('api key');
    expect(providerText('chatgpt-oauth')).toBe('chatgpt');
  });
});

describe('what one strip prints', () => {
  const usage: UsagePayload = {
    window_hours: 5,
    warn_pct: 80,
    warn_tokens_5h: 0,
    heads: [{ key: 'claudex', label: 'claudex', usage: { output_tokens_5h: 1, entries: 2, ratelimit: null, warn: { level: 'warn', pct: 84, source: 'headers', reset: null } } }],
  };

  function strip(over: Partial<HeadStatus> = {}, signalsOver: Partial<HeadSignals> = {}): string {
    const one = head(over);
    return render(h(HeadStrip, {
      head: one,
      attention: headAttention(one, signals(signalsOver)),
      window: headWindow(usage, one.key),
      account: 'acct-a',
      dialect: null,
      model: null,
      columns: [],
    }));
  }

  test('every field label reaches the markup', () => {
    const out = strip();
    for (const label of ['provider', 'head', 'port', 'dialect', 'model', 'account', 'in flight', 'window', 'last turn']) {
      expect(out).toContain(`>${label}<`);
    }
  });

  test('the provider family prints as its name, with no monogram before it', () => {
    const out = strip();
    expect(out).toContain('>chatgpt<');
    expect(out).not.toContain('cg chatgpt');
  });

  test('the window prints its percentage, and not reported when there is none', () => {
    expect(strip()).toContain('84%');
    expect(strip({ key: 'other' })).toContain(NOT_REPORTED);
  });

  test('a field no source answered prints none rather than inventing a value or a stale row', () => {
    const out = strip();
    expect(out).toContain('>none<');
    expect(out).not.toContain('not built');
  });

  test('a struck head renders aria-disabled and its printed cause', () => {
    const out = strip({ running: false });
    expect(out).toContain('aria-disabled="true"');
    expect(out).toContain('>down<');
  });

  test('a cocked head prints its cause beside the edge', () => {
    const out = strip({ versionMatch: false });
    expect(out).toContain('version mismatch');
    expect(out).toContain('myx-edge-amber');
  });

  test('in-flight prints against the ceiling, and alone when there is none', () => {
    expect(strip()).toContain('1/4');
    expect(strip({ gate: gate({ max: 'unlimited' }) })).toContain('>1<');
  });

  test('a live turn prints its phase and age; an idle head prints the honest empty', () => {
    const busy = strip({ gate: gate({ live: [{ label: 'x', compact: false, phase: 'streaming', age_ms: 1500, idle_ms: 10 }] }) });
    expect(busy).toContain('streaming');
    expect(liveTurnText(head())).toBeNull();
  });

  test('inflightText with no gate prints nothing rather than a zero', () => {
    expect(inflightText(head({ gate: null }))).toBe('');
  });
});

describe('the saved views', () => {
  const byHead: View = { id: 'h', name: 'by head', layout: 'bay', filter: {}, sort: null, group: null, fields: [] };
  const byProvider: View = { id: 'p', name: 'by provider', layout: 'bay', filter: {}, sort: null, group: 'provider', fields: [] };
  const attention: View = { id: 'a', name: 'attention first', layout: 'bay', filter: {}, sort: { field: 'attention', dir: 'desc' }, group: null, fields: [] };
  const noSignals = () => NO_SIGNALS;

  test('by head is one bay of every head, ordered by key', () => {
    const groups = arrangeHeads([head({ key: 'z' }), head({ key: 'a' })], byHead, noSignals);
    expect(groups).toHaveLength(1);
    expect(groups[0]?.heads.map((row) => row.key)).toEqual(['a', 'z']);
  });

  test('by provider makes one bay per family', () => {
    const groups = arrangeHeads(
      [head({ key: 'a', authKind: 'chatgpt-oauth' }), head({ key: 'b', authKind: 'grok-oauth' })],
      byProvider,
      noSignals,
    );
    expect(groups.map((group) => group.key)).toEqual(['chatgpt', 'grok']);
  });

  test('attention first puts the head that needs the operator at the top', () => {
    const groups = arrangeHeads(
      [head({ key: 'fine' }), head({ key: 'down', running: false }), head({ key: 'amber', versionMatch: false })],
      attention,
      noSignals,
    );
    expect(groups[0]?.heads.map((row) => row.key)).toEqual(['down', 'amber', 'fine']);
  });

  test('a view that names no fields shows every column rather than none', () => {
    expect(columnsOf(byHead, ['provider', 'head'])).toEqual(['provider', 'head']);
    expect(columnsOf({ ...byHead, fields: ['port'] }, ['provider', 'head'])).toEqual(['port']);
  });
});

describe('reading the pending topology', () => {
  const topology = {
    providers: { codex: { dialect: 'openai-responses' } },
    heads: { claudex: { provider: 'codex', port: 3099 }, loner: { port: 3100 } },
  };

  test('a dialect is two hops: head to provider, provider to dialect', () => {
    expect(dialectOf(topology, 'claudex')).toBe('openai-responses');
  });

  test('a head with no provider names no dialect rather than a guess', () => {
    expect(dialectOf(topology, 'loner')).toBeNull();
    expect(dialectOf(topology, 'missing')).toBeNull();
  });

  test('a pending route gives nothing to read, which is null and not a crash', () => {
    expect(dialectOf(null, 'claudex')).toBeNull();
    expect(dialectOf({}, 'claudex')).toBeNull();
  });
});

describe('a route this daemon does not serve says so in words, not a row id', () => {
  test('the field empty names what is missing and why', () => {
    const out = render(h(Empty, EMPTIES.fields));
    expect(out).toContain('dialect and model unavailable');
    expect(out).toContain('does not serve');
    expect(out).not.toMatch(/V4-\d+|\/api\//);
  });

  test('the pool empty, for the one state that still reaches it: a 404 on the route', () => {
    expect(render(h(Empty, EMPTIES.pool))).toContain('does not serve accounts');
  });

  test('no fleet empty prints an api route or a campaign row', () => {
    const all = [...Object.values(EMPTIES), ...['client', 'api-key', 'local-llama', 'grok-oauth'].map(poolEmpty)];
    expect(all.filter((empty) => /V4-\d+|\/api\/|GET |row /.test(`${empty.text} ${empty.source}`))).toEqual([]);
  });

  // M4-02: POST /api/daemon/restart is served (ControlServer.kt:368), so the empty that said it was
  // not built is gone rather than left printing beside the control that replaced it.
  test('the daemon restart is a control now, not an empty', () => {
    expect(Object.keys(EMPTIES)).not.toContain('daemonRestart');
  });
});

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

const NOW_MS = 1_790_000_000_000;

/** A seven-day window at `used` percent, the one the daemon's third rule sorts by. */
function sevenDay(used: number | null) {
  return { seconds: 604800, used_percent: used, reset_epoch_seconds: null };
}

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

  test('an api-key head has no pool and says why', () => {
    expect(poolEmpty('api-key')).toEqual(EMPTIES.apiKey);
    expect(EMPTIES.apiKey.source).toContain('one key');
  });

  test('a local head needs no login', () => {
    expect(poolEmpty('something-local')).toEqual(EMPTIES.local);
  });

  test('a claude head uses one login and never pools', () => {
    expect(poolEmpty('client').text).toBe('one login, no pool');
  });

  test('an oauth head with no row says where to sign one in', () => {
    for (const kind of ['chatgpt-oauth', 'grok-oauth', 'kimi-oauth', 'muse-oauth']) {
      expect(poolEmpty(kind)).toEqual({ text: 'no accounts signed in', source: 'sign one in on the accounts page' });
    }
  });
});

describe('the next target is the daemon\'s own answer', () => {
  // AccountsRoute writes next_target = (label == the pool's nextTargetLabel), and the pool walks the
  // pin, then primary, then the caller's previous account, then lowest seven-day used with ties by
  // label (AccountPool.kt:163-186). The mark is the daemon's flag; the rule only explains it.
  test('a pinned target is marked pinned, even over an available primary', () => {
    const pool = [
      account({ label: 'main', primary: true }),
      account({ label: 'work', pinned: true, next_target: true }),
    ];
    expect(poolNext(pool)).toEqual({ label: 'work', rule: 'pinned' });
  });

  test('an unpinned primary target is explained by primary', () => {
    expect(poolNext([account({ label: 'main', primary: true, next_target: true }), account({ label: 'work' })]))
      .toEqual({ label: 'main', rule: 'primary' });
  });

  test('the lowest seven-day account is explained by that rule, ties broken by label as the daemon sorts', () => {
    const pool = [
      account({ label: 'main', primary: true, available: false }),
      account({ label: 'zeta', windows: [sevenDay(0)] }),
      account({ label: 'beta', windows: [], next_target: true }),
    ];
    expect(poolNext(pool)).toEqual({ label: 'beta', rule: 'most weekly room' });
  });

  test('a target that is neither pinned, primary nor lowest was the sticky account', () => {
    const pool = [
      account({ label: 'main', primary: true, available: false }),
      account({ label: 'low', windows: [sevenDay(5)] }),
      account({ label: 'held', windows: [sevenDay(60)], next_target: true }),
    ];
    expect(poolNext(pool)).toEqual({ label: 'held', rule: 'last used' });
  });

  test('no flagged row has no next target, and neither does a single login', () => {
    expect(poolNext([account({ label: 'work' })])).toBeNull();
    expect(poolNext([account({ label: null, single_login: true, next_target: null, selected: null, available: null })]))
      .toBeNull();
  });
});

describe('account excluded on the rack', () => {
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

describe('the coverage manifest', () => {
  test('takes over exactly the eight routes the baseline held for this row', () => {
    expect(dispositions.map((entry) => entry.name).sort()).toEqual([
      '/api/daemon/restart',
      '/api/heads',
      '/api/heads/{head}/restart',
      '/api/heads/{head}/start',
      '/api/heads/{head}/stop',
      '/api/status',
      '/api/usage',
      '/health',
    ]);
  });

  // Was `the only pending entry is the one route that is still a row`, pinning /api/daemon/restart as
  // pending V4-74. The route is served (ControlServer.kt:368, DaemonRoutes.restartJson) and the head
  // detail writes through it (M4-02), so what stays pinned is the property: a pending entry names
  // the row that lands it.
  test('every pending entry names the row that will land it', () => {
    for (const entry of dispositions.filter((e) => e.disposition === 'pending')) {
      expect(entry.where, `${entry.name} is pending and names no row`).toMatch(/^V4-\d+$/);
    }
  });

  test('the three lifecycle actions and the daemon restart are editable, and the reads are not', () => {
    const byName = new Map(dispositions.map((entry) => [entry.name, entry.disposition]));
    expect(byName.get('/api/heads/{head}/start')).toBe('editable');
    expect(byName.get('/api/heads/{head}/stop')).toBe('editable');
    expect(byName.get('/api/heads/{head}/restart')).toBe('editable');
    expect(byName.get('/api/daemon/restart')).toBe('editable');
    expect(byName.get('/api/heads')).toBe('read-only');
  });
});
