// V4-429: Fleet read claudex OK while splice said it was out of quota until Oct 5 (`splice status`, /health's
// quotaResetAtEpochSeconds, usage's provider_reset). The daemon now carries that instant on the heads route
// (`quotaResetAtEpochSeconds` on the head), and the console reads it: the State cell and the panel say
// `Out of quota until <reset>` in the machine's zone, and the Plans card counts the head outside OK. A head
// at 99% carries nothing and reads OK; a reset already passed is over and reads OK; a runtime that does not
// answer still reads Down first.
import * as React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, test } from 'vitest';
import { NO_SIGNALS, headAttention, localInstantText } from '../src/entities/heads';
import { FleetBoard } from '../src/pages/fleet';
import type { FleetSources } from '../src/pages/fleet';
import { causeHelp, healthOf, healthParts, stateText } from '../src/pages/fleet/model';
import { H, S, U } from '../src/pages/fleet/strings';
import type { HeadStatus } from '../src/shared/api';
import { tableOf } from './lib/markup';

const h = React.createElement;
const NOW_MS = Date.UTC(2026, 9, 1, 18, 0, 0);
const RESET_S = Date.UTC(2026, 9, 5, 19, 13, 0) / 1000;

function head(over: Partial<HeadStatus> = {}): HeadStatus {
  return {
    key: 'claudex', label: 'claudex', name: 'claudex', port: 3099, authKind: 'chatgpt-oauth', wantVersion: '0.4.0',
    running: true, healthy: true, version: '0.4.0', versionMatch: true, mode: null,
    gate: { inflight: 1, queued: 0, max: 4, acquired: 9, released: 8, waited: 1, avg_wait_ms: 20, live: [], stream_idle_ms: 30000 },
    maxInflight: 4, health: { localOriginErrors: 0, providerErrors: 0 }, pids: [1],
    ...over,
  };
}

const refused = (over: Partial<HeadStatus> = {}): HeadStatus => head({ quotaResetAtEpochSeconds: RESET_S, ...over });
const localText = localInstantText(RESET_S);
const OUT_OF_QUOTA_UNTIL = `${S.stateName['out of quota']} ${U.until} ${localText}`;

/** Every source unread: what the board sees before its first polls land. */
const NO_SOURCES: FleetSources = {
  auth: null, usage: null, accounts: null, topology: null, catalogs: null, fieldsPending: false,
  topologyStale: false, landed: [], lastTs: new Map(), overrides: [],
};

function board(heads: readonly HeadStatus[], openKey: string | null = null): string {
  return renderToStaticMarkup(h(FleetBoard, { heads, sources: NO_SOURCES, openKey, onOpen: () => undefined, nowMs: NOW_MS }));
}

describe('a running head whose provider refuses turns until a known instant', () => {
  test('reads out of quota, outside OK, and is not struck: the head runs, the provider says wait', () => {
    const state = headAttention(refused(), NO_SIGNALS, NOW_MS);
    expect(state).toMatchObject({ cause: 'out of quota', struck: false });
    expect(healthOf(state.cause)).not.toBe('ok');
    expect(healthOf(state.cause)).not.toBe('down');
  });

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

  test('the State cell and the opened panel read `Out of quota until <reset>`', () => {
    const state = headAttention(refused(), NO_SIGNALS, NOW_MS);
    expect(stateText(state, refused())).toBe(OUT_OF_QUOTA_UNTIL);
    expect(board([refused()])).toContain(OUT_OF_QUOTA_UNTIL);
    const opened = board([refused()], 'claudex');
    expect(opened.split(OUT_OF_QUOTA_UNTIL).length - 1).toBeGreaterThanOrEqual(2);
    expect(causeHelp(refused(), 'out of quota', undefined)?.text).toContain(H.outOfQuota);
  });

  test('the Plans card counts it outside OK', () => {
    const heads = [refused(), head({ key: 'grok', label: 'grok', name: 'grok' })];
    const parts = healthParts(heads.map((each) => headAttention(each, NO_SIGNALS, NOW_MS).cause));
    expect(parts.find((part) => part.key === 'ok')?.value).toBe(1);
    expect(parts.filter((part) => part.key !== 'ok').reduce((sum, part) => sum + part.value, 0)).toBe(1);
    const rows = tableOf(board(heads), S.heads).rows;
    expect(rows.filter((row) => row.includes(OUT_OF_QUOTA_UNTIL))).toHaveLength(1);
  });
});
