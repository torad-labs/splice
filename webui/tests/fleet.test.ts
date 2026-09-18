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
import {
  ATTENTION_CAUSES,
  headAttention,
  inflightText,
  liveTurnText,
  providerFamily,
  queueAtMax,
  NO_SIGNALS,
  PROVIDER_MARK,
} from '../src/entities/heads';
import type { HeadSignals } from '../src/entities/heads';
import { headWindow, headsReportingNone, nearestWindow } from '../src/entities/usage';
import { HeadStrip } from '../src/widgets/head-strip';
import { EMPTIES, arrangeHeads, columnsOf, dialectOf } from '../src/pages/fleet/model';
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
      .toBe('token missing');
  });

  test('a warning is amber and cocked, never red', () => {
    const state = headAttention(head({ versionMatch: false }), signals());
    expect(state.edge).toBe('amber');
    expect(state.cocked).toBe(true);
    expect(state.struck).toBe(false);
    expect(state.label).toBe('version mismatch');
  });

  test('an unhealthy head is the only red', () => {
    for (const cause of ['version mismatch', 'queue at max', 'topology stale'] as const) {
      const state = cause === 'version mismatch'
        ? headAttention(head({ versionMatch: false }), signals())
        : cause === 'queue at max'
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

  test('a head with no window prints not reported by provider, never 0%', () => {
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

  test('every family has a monochrome mark, and they are distinct', () => {
    const marks = Object.values(PROVIDER_MARK);
    expect(new Set(marks).size).toBe(marks.length);
    for (const mark of marks) expect(mark).toMatch(/^[a-z]{2}$/);
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

  test('the provider family prints as a monogram and as its name', () => {
    const out = strip();
    expect(out).toContain('cg chatgpt');
  });

  test('the window prints its percentage, and not reported when there is none', () => {
    expect(strip()).toContain('84%');
    expect(strip({ key: 'other' })).toContain('not reported by provider');
  });

  test('a pending field source says not built rather than inventing a value', () => {
    const out = strip();
    expect(out).toContain('not built');
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

describe('pending routes render an empty naming their row', () => {
  test('the field empty names both rows it is waiting on', () => {
    const out = render(h(Empty, EMPTIES.fields));
    expect(out).toContain('dialect and model not built');
    expect(out).toContain('V4-127');
    expect(out).toContain('V4-128');
  });

  test('the daemon restart empty names its own row, which is not a head restart', () => {
    const out = render(h(Empty, EMPTIES.daemonRestart));
    expect(out).toContain('V4-74');
  });

  test('the pool empty names V4-132', () => {
    expect(render(h(Empty, EMPTIES.pool))).toContain('V4-132');
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

  test('the only pending entry is the one route that is still a row', () => {
    const pending = dispositions.filter((entry) => entry.disposition === 'pending');
    expect(pending.map((entry) => entry.name)).toEqual(['/api/daemon/restart']);
    expect(pending[0]?.where).toBe('V4-74');
  });

  test('the three lifecycle actions are editable and the reads are not', () => {
    const byName = new Map(dispositions.map((entry) => [entry.name, entry.disposition]));
    expect(byName.get('/api/heads/{head}/start')).toBe('editable');
    expect(byName.get('/api/heads/{head}/stop')).toBe('editable');
    expect(byName.get('/api/heads/{head}/restart')).toBe('editable');
    expect(byName.get('/api/heads')).toBe('read-only');
  });
});
