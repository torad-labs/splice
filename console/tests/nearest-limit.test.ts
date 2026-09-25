// The nearest limit is ONE number wherever it is printed (review of #264). The status strip and the
// fleet printed 38% while the accounts page printed 64% for one fleet: the first two read /api/usage,
// which reports only each head's SELECTED account, and the third read every pooled account. Here all
// three are fed the same sources and each must print the number the one derivation names.
//
// A `.ts` test cannot hold JSX (TS1161), so elements are built with React.createElement and read back
// from renderToStaticMarkup's string.
import * as React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, test } from 'vitest';
import type { AccountRow, AccountWindow } from '../src/entities/account';
import { nearestWindow } from '../src/entities/usage';
import { limitText, nearestLimit } from '../src/features/nearest-limit';
import type { LimitSources } from '../src/features/nearest-limit';
import { AccountsBoard } from '../src/pages/accounts';
import { S as ACCOUNTS } from '../src/pages/accounts/strings';
import { FleetBoard } from '../src/pages/fleet';
import type { FleetSources } from '../src/pages/fleet';
import { S as FLEET } from '../src/pages/fleet/strings';
import type { AuthPayload, HeadStatus, HeadUsageEntry, UsagePayload } from '../src/shared/api';
import { WindowCell } from '../src/widgets/rule';
import { cellText, statOf } from './lib/markup';

const h = React.createElement;
const render = (el: React.ReactElement): string => renderToStaticMarkup(el);

// The strip reads its own clock, so the fixture's resets are placed ahead of the real one.
const NOW = Math.floor(Date.now() / 1000) * 1000;
const at = (seconds: number) => NOW / 1000 + seconds;
const HOUR_5 = 18_000;
const DAY_7 = 604_800;

function win(seconds: number, used: number, resetIn: number): AccountWindow {
  return { seconds, used_percent: used, reset_epoch_seconds: at(resetIn) };
}

function account(label: string, windows: AccountWindow[], over: Partial<AccountRow> = {}): AccountRow {
  return {
    kind: 'chatgpt-oauth', label, single_login: false, credential_path: null, primary: false, selected: false,
    available: true, pinned: false, next_target: false, credential_present: true, windows, heads: ['claudex'], ...over,
  };
}

/** One head's /api/usage entry reporting its plan windows, as the daemon folds them. */
function planHead(key: string, fiveHour: number, sevenDay: number): HeadUsageEntry {
  return {
    key, label: key,
    usage: {
      output_tokens_5h: 0, entries: 0, ratelimit: null,
      warn: { level: 'ok', pct: fiveHour, source: 'quota_5h', reset: null },
      quota: { five_hour: { used_pct: fiveHour, resets_at: at(2 * 3600) }, seven_day: { used_pct: sevenDay, resets_at: at(6 * 86_400) } },
    },
  };
}

function head(key: string, authKind: string): HeadStatus {
  return {
    key, label: key, name: key, port: 3099, authKind, wantVersion: '0.4.0', running: true, healthy: true, version: '0.4.0',
    versionMatch: true, mode: null, gate: null, maxInflight: null, health: { localOriginErrors: 0, providerErrors: 0 }, pids: [1],
  };
}

const auth: AuthPayload = {
  claudex: { kind: 'chatgpt-oauth', login: 'browser', present: true },
  claude: { kind: 'client', login: 'browser', present: true, account_id_masked: 'acct-c' },
};

/** The review's fleet: claudex rides a pool of two, and /api/usage reports only the selected one. */
const pool = [
  account('primary', [win(HOUR_5, 38, 2 * 3600 + 4 * 60), win(DAY_7, 21, 6 * 86_400)], { selected: true }),
  account('work', [win(HOUR_5, 12, 41 * 60 + 24), win(DAY_7, 64, 3 * 86_400 + 10 * 3600)]),
];

function usageOf(claude: number): UsagePayload {
  return { window_hours: 5, warn_pct: 80, warn_tokens_5h: 0, heads: [planHead('claudex', 38, 21), planHead('claude', claude, 10)] };
}

/** The strip's figure and the two pages' Nearest limit stats, from one set of sources. */
function printed(sources: LimitSources): { strip: string; fleet: ReturnType<typeof statOf>; accounts: ReturnType<typeof statOf> } {
  const strip = render(h(WindowCell, { accounts: sources.accounts, usage: sources.usage, auth: sources.auth }));
  const fleetSources: FleetSources = {
    auth: sources.auth, usage: sources.usage, accounts: { accounts: [...sources.accounts] }, topology: null, catalogs: null,
    fieldsPending: false, topologyStale: false, landed: [], lastTs: new Map(), overrides: [],
  };
  const fleet = render(h(FleetBoard, {
    heads: [head('claudex', 'chatgpt-oauth'), head('claude', 'client')], sources: fleetSources, openKey: null, onOpen: () => undefined, nowMs: NOW,
  }));
  const accounts = render(h(AccountsBoard, { payload: { accounts: [...sources.accounts] }, usage: sources.usage, auth: sources.auth, nowMs: NOW }));
  const figure = /<span class="myx-rule-figure[^"]*">([\s\S]*?)<\/span>/.exec(strip)?.[1];
  return { strip: figure === undefined ? '' : cellText(figure), fleet: statOf(fleet, FLEET.nearestLimit), accounts: statOf(accounts, ACCOUNTS.nearestLimit) };
}

describe('the nearest limit is one number on the strip, the fleet and the accounts page', () => {
  test('a pooled account the head has not selected counts: work at 64% weekly, not the selected 38%', () => {
    const sources = { accounts: pool, usage: usageOf(20), auth };
    // what the strip and the fleet used to read: the selected account alone
    expect(nearestWindow(sources.usage, auth, NOW)?.pct).toBe(38);
    const limit = nearestLimit(sources, NOW);
    if (limit === null) throw new Error('the review fleet reports windows, so it has a nearest limit');
    expect(limit).toMatchObject({ head: 'claudex', account: 'work', window: '7d', pct: 64 });
    const out = printed(sources);
    expect(out.strip).toBe('64%');
    expect(out.fleet).toEqual({ value: '64%', sub: limitText(limit) });
    expect(out.accounts).toEqual({ value: '64%', sub: limitText(limit) });
  });

  test('a head no account row names counts too: the Claude head at 70% is the limit on all three', () => {
    const sources = { accounts: pool, usage: usageOf(70), auth };
    expect(nearestLimit(sources, NOW)).toMatchObject({ head: 'claude', account: 'acct-c', window: '5h', pct: 70 });
    const out = printed(sources);
    expect([out.strip, out.fleet?.value, out.accounts?.value]).toEqual(['70%', '70%', '70%']);
    expect(out.fleet?.sub).toBe(out.accounts?.sub);
  });

  test('an account that cannot serve ranks after every one that can, and is named once none can', () => {
    const spent = account('spent', [win(HOUR_5, 100, 3600)]);
    const refused = account('refused', [win(HOUR_5, 97, 3600)], { available: false });
    const room = account('room', [win(HOUR_5, 30, 3600)]);
    const noKey = account('nokey', [win(HOUR_5, 90, 3600)], { credential_present: false });
    const quiet = { window_hours: 5, warn_pct: 80, warn_tokens_5h: 0, heads: [] };
    expect(nearestLimit({ accounts: [spent, refused, noKey, room], usage: quiet, auth: null }, NOW)?.account).toBe('room');
    expect(nearestLimit({ accounts: [refused, spent], usage: quiet, auth: null }, NOW)).toMatchObject({ account: 'spent', pct: 100, level: 'critical' });
    const out = printed({ accounts: [spent, room], usage: quiet, auth: null });
    expect([out.strip, out.fleet?.value, out.accounts?.value]).toEqual(['30%', '30%', '30%']);
  });

  test('nothing reported anywhere is no limit, never a zero', () => {
    expect(nearestLimit({ accounts: [account('quiet', [])], usage: null, auth: null }, NOW)).toBeNull();
  });
});
