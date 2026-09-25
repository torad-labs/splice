// WALLS for the fleet page. The load-bearing one is `one printed cause, and the worst one wins`:
// ARRIVE on this page is "which head needs me", and a row that printed a healthy badge while a
// head was actually unhealthy would send the operator past the only thing they opened the page for.
//
// The second is `a stopped head is down, never merely amber`. A down head is not a warning; it is
// a head that cannot take work.
//
// A `.ts` test cannot hold JSX (TS1161), so elements are built with React.createElement and
// asserted against renderToStaticMarkup's string.
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import * as React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, test } from 'vitest';
import { nextRuleOf } from '../src/entities/account';
import type { AccountRow } from '../src/entities/account';
import {
  ATTENTION_CAUSES,
  EDGE_WORDS,
  familyName,
  headAttention,
  inflightText,
  liveTurnText,
  providerFamily,
  queueAtMax,
  NO_SIGNALS,
  FAMILY_NAME,
} from '../src/entities/heads';
import type { HeadSignals } from '../src/entities/heads';
import type { TurnRow } from '../src/entities/perf';
import { headWindow, headsReportingNone, nearestWindow } from '../src/entities/usage';
import {
  EMPTIES, arrangeHeads, causeHelp, columnsOf, dialectOf, firstBytes, healthOf, healthParts, inflightTotals,
  lastTurnOf, median, noneAvailable, poolEmpty, poolOf, rowTone, selectedExcluded, stateTone,
} from '../src/pages/fleet/model';
import { dispositions } from '../src/pages/fleet/coverage';
import { ADD_COMMAND, AddHead, CauseLine, FleetBoard } from '../src/pages/fleet';
import type { FleetSources } from '../src/pages/fleet';
import { H, S } from '../src/pages/fleet/strings';
import { ACCOUNT_WORDS } from '../src/widgets/account-table';
import { ABSENT } from '../src/shared/lib';
import { Empty } from '../src/shared/ui';
import type { AuthPayload, GateSnapshot, HeadStatus, UsagePayload } from '../src/shared/api';
import type { View } from '../src/features/views';
import { tableOf } from './lib/markup';

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
      headAttention(head({ authKind: 'api-key' }), signals({ credentialPresent: false })).cause,
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
    expect(state.label).toBe('mismatch');
    expect(state.cause).toBe('version mismatch');
  });

  test('every edge word fits the 8ch edge whole', () => {
    // Walkthrough S1: `account excluded` printed as `account…` and `signed out` as `signed o…`.
    for (const word of Object.values(EDGE_WORDS)) expect(word.length).toBeLessThanOrEqual(8);
  });

  test('an api-key head with no credential is missing its key, not signed out, and says how to set it', () => {
    const keyHead = head({ authKind: 'api-key' });
    const state = headAttention(keyHead, signals({ credentialPresent: false }));
    expect(state.cause).toBe('key missing');
    expect(state.label).toBe('no key');
    const help = causeHelp(keyHead, state.cause, { kind: 'api-key', login: 'manual', present: false, env_var: 'DEEPSEEK_API_KEY' });
    expect(help?.command).toBe('splice key set DEEPSEEK_API_KEY');
    expect(help?.text).toBe(H.keyMissing);
    // With no variable named there is no command to copy, and the line says which one to run.
    expect(causeHelp(keyHead, state.cause, undefined)).toEqual({ text: H.keyMissingBare });
    // A login head keeps `signed out`, and its step is the accounts page.
    const login = causeHelp(head(), headAttention(head(), signals({ credentialPresent: false })).cause, undefined);
    expect(login?.href).toBe('#/accounts');
  });

  test('the opened head prints the command to copy, and the page to open', () => {
    const keyed = renderToStaticMarkup(React.createElement(CauseLine, { help: { text: 'no api key in X', command: 'splice key set X' } }));
    expect(keyed).toContain('splice key set X');
    expect(keyed).toContain('>Copy<');
    const linked = renderToStaticMarkup(React.createElement(CauseLine, { help: { text: 'no login', href: '#/accounts', link: 'sign in on accounts' } }));
    expect(linked).toContain('href="#/accounts"');
    expect(renderToStaticMarkup(React.createElement(CauseLine, { help: null }))).toBe('');
  });

  test('every state but ok explains itself when the head is opened', () => {
    for (const cause of [...ATTENTION_CAUSES, 'down'] as const) expect(causeHelp(head(), cause, undefined)?.text).toBeTruthy();
    expect(causeHelp(head(), 'ok', undefined)).toBeNull();
    expect(causeHelp(head(), 'unhealthy', undefined)?.href).toBe(`#/logs?head=${head().key}`);
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
    expect(nearest).toEqual({ head: 'splice', account: null, window: '5h', pct: 65, reset: 'in 1h 0m' });
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
    expect(nearestWindow(folded, null, NOW_MS)).toEqual({ head: 'claudex', account: null, window: '7d', pct: 85, reset: 'in 24h 0m' });
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

const BOARD_NOW = Date.UTC(2026, 8, 24, 18, 0, 0);

/** Every source unread: what the board sees before its first polls land. */
const NO_SOURCES: FleetSources = {
  auth: null,
  usage: null,
  accounts: null,
  topology: null,
  catalogs: null,
  fieldsPending: false,
  topologyStale: false,
  landed: [],
  lastTs: new Map(),
  overrides: [],
};

function board(heads: readonly HeadStatus[] | null, over: Partial<FleetSources> = {}, openKey: string | null = null): string {
  return render(h(FleetBoard, { heads, sources: { ...NO_SOURCES, ...over }, openKey, onOpen: () => undefined, nowMs: BOARD_NOW }));
}

/** One landed turn of `head`, `firstByte` ms to its first byte (absent for a turn that failed first). */
function turn(key: string, ts: number, firstByte?: number): TurnRow {
  return { head: key, ts, model: null, outcome: 'ok', compact: false, ...(firstByte === undefined ? {} : { first_byte: firstByte }) };
}

describe('what one head row prints', () => {
  const usage: UsagePayload = {
    window_hours: 5,
    warn_pct: 80,
    warn_tokens_5h: 0,
    heads: [{ key: 'claudex', label: 'claudex', usage: { output_tokens_5h: 1, entries: 2, ratelimit: null, warn: { level: 'warn', pct: 84, source: 'headers', reset: null } } }],
  };

  function row(over: Partial<HeadStatus> = {}, sources: Partial<FleetSources> = {}): string {
    return tableOf(board([head(over)], { usage, ...sources }), S.heads).rows[0] ?? '';
  }

  /** One cell of the row, found by its column's name rather than its position. */
  function cell(column: string, over: Partial<HeadStatus> = {}, sources: Partial<FleetSources> = {}): string {
    const heads = tableOf(board([head(over)], { usage, ...sources }), S.heads);
    return (heads.rows[0] ?? '').split('<td').slice(1)[heads.names.indexOf(column)] ?? '';
  }

  test('names every column once, and every row has one cell per column', () => {
    const heads = tableOf(board([head(), head({ key: 'other', gate: null })], { usage }), S.heads);
    expect(heads.names).toEqual([S.head, S.provider, S.state, S.model, S.account, S.inflight, S.window, S.firstByte, S.lastTurn]);
    for (const one of heads.rows) expect((one.match(/<td/g) ?? []).length).toBe(heads.names.length);
  });

  test('the provider family prints as its name, with no monogram before it', () => {
    expect(row()).toContain('>chatgpt<');
    expect(row()).not.toContain('cg chatgpt');
  });

  test('the window is a meter with its percentage, and the absence when the head reports none, never 0%', () => {
    expect(row()).toContain('>84%<');
    expect(row()).toContain('role="meter"');
    const quiet = row({ key: 'other' });
    expect(quiet).not.toContain('>0%<');
    expect(quiet).toContain(`>${ABSENT}<`);
  });

  test('a model the catalog writes as empty prints the absence, and a pinned one prints its name', () => {
    const catalog = (pinned: string) => [{ head: 'claudex', provider: 'codex', pinned_model: pinned, models: [] }];
    expect(cell(S.model, {}, { catalogs: catalog('') })).toContain(`>${ABSENT}<`);
    expect(cell(S.model, {}, { catalogs: catalog('gpt-5.5') })).toContain('>gpt-5.5<');
  });

  test('a stopped head says down on a quiet row; a failing one is the only red', () => {
    const down = row({ running: false });
    expect(down).toContain(`>${S.stateName.down}<`);
    expect(down).not.toContain('myx-dt-tone');
    const failing = row({ healthy: false });
    expect(failing).toContain(`>${S.stateName.unhealthy}<`);
    expect(failing).toContain('myx-dt-tone-danger');
    expect(row({ versionMatch: false })).toContain('myx-dt-tone-warn');
  });

  test('in flight is a pip per slot, a meter past what pips can count, and the count alone with no ceiling', () => {
    expect(row()).toContain('>1/4<');
    expect(cell(S.inflight)).toContain(`aria-label="${S.inflight} claudex: 1 of 4"`);
    expect((cell(S.inflight).match(/class="myx-pip[ "]/g) ?? []).length).toBe(4);
    const wide = cell(S.inflight, { gate: gate({ inflight: 3, max: 32 }) });
    expect(wide).toContain('role="meter"');
    expect(wide).not.toContain('myx-pip');
    const open = row({ gate: gate({ max: 'unlimited' }) });
    expect(open).toContain('>1<');
    expect(open).not.toContain(`aria-label="${S.inflight} claudex"`);
  });

  test('first byte is the head\'s own series as a sparkline with its median beside it', () => {
    const landed = [turn('claudex', 1, 400), turn('other', 2, 9000), turn('claudex', 3), turn('claudex', 4, 600)];
    const out = row({}, { landed });
    expect(out).toContain('myx-spark');
    // the median of 400 and 600: the other head's turn and the turn that failed first are not in it
    expect(out).toContain('>500ms<');
    expect(row()).not.toContain('myx-spark');
  });

  test('an idle head prints when its last turn was, from the perf summary, not a bare none', () => {
    // gate.live is served empty, so this cell read `none` on every head, busy afternoon or not.
    expect(row({}, { lastTs: new Map([['claudex', BOARD_NOW - 3 * 3_600_000]]) })).toContain('>3h ago<');
    expect(row({}, { lastTs: new Map([['claudex', null]]) })).toContain(`>${S.none}<`);
    const live = row({ gate: gate({ live: [{ label: 'x', compact: false, phase: 'streaming', age_ms: 1500, idle_ms: 10 }] }) });
    // The column holds a word: the cell says Running, and the phase and age stand in its tip.
    expect(live).toContain(`>${S.running}<`);
    expect(live).toMatch(/role="tooltip"[^>]*>streaming 1\.5s</);
  });

  test('the last turn is live, ago, none or unknown, and these are four different facts', () => {
    const busy = head({ gate: gate({ live: [{ label: 'x', compact: false, phase: 'streaming', age_ms: 1500, idle_ms: 10 }] }) });
    expect(lastTurnOf(busy, 5)).toEqual({ kind: 'live', phase: 'streaming', ageMs: 1500 });
    expect(lastTurnOf(head(), 5)).toEqual({ kind: 'ago', ts: 5 });
    expect(lastTurnOf(head(), null)).toEqual({ kind: 'none' });
    expect(lastTurnOf(head(), undefined)).toEqual({ kind: 'unknown' });
    expect(liveTurnText(head())).toBeNull();
  });

  test('inflightText with no gate prints nothing rather than a zero', () => {
    expect(inflightText(head({ gate: null }))).toBe('');
  });
});

describe('the figures', () => {
  test('health is the state now, split four ways in a fixed order', () => {
    expect(['ok', 'down', 'unhealthy', 'queue full'].map((cause) => healthOf(cause as never))).toEqual(['ok', 'down', 'failing', 'attention']);
    const parts = healthParts(['ok', 'ok', 'unhealthy', 'down', 'restart needed']);
    expect(parts.map((part) => [part.key, part.value])).toEqual([['ok', 2], ['attention', 1], ['failing', 1], ['down', 1]]);
    expect(stateTone('down')).toBe('neutral');
    expect(rowTone('down')).toBeNull();
    expect(rowTone('ok')).toBeNull();
  });

  test('in flight sums against the ceiling, and an unlimited gate leaves the fleet with none', () => {
    expect(inflightTotals([head(), head({ gate: gate({ inflight: 3, max: 8 }) }), head({ gate: null })])).toEqual({ inflight: 4, max: 12 });
    expect(inflightTotals([head(), head({ gate: gate({ max: 'unlimited' }) })]).max).toBeNull();
  });

  test('first byte leaves out a turn that never got one, and the median of nothing is nothing', () => {
    expect(firstBytes([turn('a', 1, 100), turn('b', 2), turn('a', 3, 300)])).toEqual([100, 300]);
    expect(firstBytes([turn('a', 1, 100), turn('b', 2, 50)], 'b')).toEqual([50]);
    expect(median([])).toBeNull();
    expect(median([3, 1, 2])).toBe(2);
  });

  test('the board leads with the heads by health, what is in flight, first byte and the nearest limit', () => {
    const out = board([head(), head({ key: 'down', running: false })], { landed: [turn('claudex', 1, 250)] });
    for (const label of [S.heads, S.inflight, S.firstByte, S.nearestLimit]) expect(out).toContain(`>${label}<`);
    expect(out).toContain(`${S.heads}: ${S.healthName.ok} 1, ${S.healthName.attention} 0, ${S.healthName.failing} 0, ${S.healthName.down} 1`);
    expect(out).toContain('>250ms<');
  });
});

describe('the opened head', () => {
  const pool = (over: Partial<AccountRow>[]): FleetSources['accounts'] => ({ accounts: over.map((one) => account(one)) });

  test('is not mounted at rest: no detail landmark until a head is opened', () => {
    expect(board([head()])).not.toContain(`aria-label="${S.detail}"`);
    expect(board([head()], {}, 'claudex')).toContain(`aria-label="${S.detail}"`);
  });

  test('carries the facts the table leaves out, and the lifecycle keys for its state', () => {
    const out = board([head()], { topology: { providers: { codex: { dialect: 'openai-responses' } }, heads: { claudex: { provider: 'codex' } } } }, 'claudex');
    expect(out).toContain(`>${S.port}<`);
    expect(out).toContain('>3099<');
    expect(out).toContain('>openai-responses<');
    expect(out).toContain(`>${S.restart}<`);
    expect(out).toContain(`>${S.stop}<`);
    expect(out).not.toContain(`>${S.start}<`);
    const stopped = board([head({ running: false })], {}, 'claudex');
    expect(stopped).toContain(`>${S.start}<`);
    expect(stopped).toContain(`>${H.down}<`);
  });

  test('its pool is the accounts it rides, drawn as account rows with the daemon\'s next target', () => {
    const out = board([head()], { accounts: pool([
      { label: 'main', primary: true, next_target: true, windows: [{ seconds: 18000, used_percent: 30, reset_epoch_seconds: null }] },
      { label: 'work' },
      { label: 'elsewhere', heads: ['codex'] },
    ]) }, 'claudex');
    const accounts = tableOf(out, S.pool);
    expect(accounts.rows).toHaveLength(2);
    const main = accounts.rows.find((one) => one.includes('>main<')) ?? '';
    expect(main).toContain('>30%<');
    // the state rides the name cell in the narrow pool, so no row is a cell short
    expect(accounts.names).toEqual([ACCOUNT_WORDS.account, ACCOUNT_WORDS.short, ACCOUNT_WORDS.long]);
    expect(main).toContain(`>${ACCOUNT_WORDS.stateName.ok}<`);
    // the daemon's next target is one fact above the rows: the account, and the rule that chose it
    const next = new RegExp(`<dt>${S.next}</dt><dd>(.*?)</dd>`).exec(out)?.[1] ?? '';
    expect(next).toContain('main');
    expect(next).toContain(`>${ACCOUNT_WORDS.ruleName.primary}<`);
    expect(out).not.toContain('>elsewhere<');
  });

  test('a head no row names says why it has no pool, and an api-key head has none', () => {
    const keyed = board([head({ authKind: 'api-key' })], { accounts: pool([]) }, 'claudex');
    expect(keyed).toContain(`>${S.noPool}<`);
    expect(keyed).not.toContain(`aria-label="${S.pool}"`);
    expect(board([head()], { accounts: pool([]) }, 'claudex')).toContain('href="#/accounts"');
  });

  test('a pool with nothing available says so above the rows', () => {
    const out = board([head()], { accounts: pool([{ label: 'main', available: false }]) }, 'claudex');
    expect(out).toContain(`>${S.noneAvailable}<`);
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
    expect(out).toContain(`>${S.fieldsUnavailable}<`);
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
    expect(poolEmpty('client')).toEqual({ text: S.oneLogin, source: H.oneLogin });
  });

  test('an oauth head with no row says where to sign one in', () => {
    for (const kind of ['chatgpt-oauth', 'grok-oauth', 'kimi-oauth', 'muse-oauth']) {
      expect(poolEmpty(kind)).toEqual({ text: S.noAccounts, source: H.noAccounts });
    }
    expect(H.noAccounts).toContain('accounts page');
  });
});

describe('the next target is the daemon\'s own answer', () => {
  // AccountsRoute writes next_target = (label == the pool's nextTargetLabel), and the pool walks the
  // pin, then primary, then the caller's previous account, then lowest seven-day used with ties by
  // label (AccountPool.kt:163-186). The mark is the daemon's flag; the rule only explains it. The
  // pool's Next column prints this rule (widgets/account-table), the same one the accounts page does.
  /** The flagged row's rule, or null when no row is flagged. */
  const ruleOf = (pool: readonly AccountRow[]) => {
    const target = pool.find((one) => one.next_target === true);
    return target === undefined ? null : nextRuleOf(target, pool);
  };

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

  test('no flagged row in a labelled pool is none available, and a single login never is', () => {
    expect(noneAvailable([account({ label: 'work' })])).toBe(true);
    expect(noneAvailable([account({ label: 'work', next_target: true })])).toBe(false);
    expect(noneAvailable([account({ label: null, single_login: true, next_target: null, selected: null, available: null })])).toBe(false);
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
    // Routes only: the page also answers CLI verbs (V4-219), which the coverage wall counts.
    expect(dispositions.filter((entry) => entry.kind === 'route').map((entry) => entry.name).sort()).toEqual([
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

describe('how a head joins the fleet', () => {
  test('the page names the command that adds one, with a copy key, and no route', () => {
    const markup = renderToStaticMarkup(React.createElement(AddHead));
    expect(ADD_COMMAND).toBe('splice add');
    expect(markup).toContain(`>${ADD_COMMAND}<`);
    expect(markup).toContain('>Copy<');
    expect(markup).not.toContain('/api/');
  });

  test('the empty fleet does not send the operator to a topology editor that cannot add a head', () => {
    expect(EMPTIES.noHeads.source).not.toContain('topology');
    expect(EMPTIES.noHeads.source).toContain('splice add');
  });
});

describe('the first-byte cell', () => {
  // Hitstop, 2026-09-25: the sparkline's own minimum width outgrew its track in the 9.5u column, so
  // claude-grok's line ran over the 9 of 986ms.
  const sheet = readFileSync(fileURLToPath(new URL('../src/pages/fleet/fleet.css', import.meta.url)), 'utf8');
  test('the line shrinks to its track, and the figure keeps the width of its widest value', () => {
    expect(sheet).toMatch(/\.myx-fl-lat \.myx-spark \{ min-width: 0; \}/);
    expect(sheet).toMatch(/\.myx-fl-lat \.myx-fl-figure \{ min-width: 5ch; text-align: end; \}/);
    expect(sheet).toMatch(/\.myx-fl-figure \{[^}]*font-variant-numeric: tabular-nums;/);
  });
});
