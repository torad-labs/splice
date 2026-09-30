// What one head's card on Fleet says: a title, one state, the one window that matters, one quiet line of facts and at
// most one act. Pure: the clock and every read are passed in. Every fact is measured by the derivation its own page
// prints (headAttention, planWindows, poolOf), so Fleet and Needs you cannot disagree about a head.
import type { AccountRow } from '../types/accounts';
import type { AuthPayload, HeadStatus, UsagePayload } from '../types/core';
import { familyName, headAttention, localInstantText, providerFamily, quotaRefusedUntil } from './heads';
import type { HeadSignals } from './heads';
import { colourOfHead } from './model';
import type { ModelColour } from './model';
import { poolOf, selectedExcluded } from './accounts';
import { planLevel, planWindows } from './usage';
import { noun } from './format';
import { FL } from './words-fleet';

export type FleetTone = 'work' | 'wait' | 'stuck' | 'idle' | 'quota';

/** The one act a card offers. `copy-start` is a copy, never a button that starts: the runtime is rig's, not splice's. */
export type FleetFix = 'switch' | 'sign-in' | 'start' | 'restart' | 'copy-start';

export type FleetLine =
  /** The tightest plan window, drawn as a bar. `full` is a window that has refused turns. */
  | { kind: 'gauge'; name: string; pct: number; note: string; full: boolean }
  /** A head with no window to draw says its one sentence instead. */
  | { kind: 'note'; text: string };

export interface FleetCard {
  key: string;
  title: string;
  colour: ModelColour;
  tone: FleetTone;
  state: string;
  attention: boolean;
  /** Null for a healthy head with nothing to draw: a card says nothing rather than fill the space. */
  line: FleetLine | null;
  /** Plan family, account, sessions: read once, never chips. */
  meta: string[];
  fix: FleetFix | null;
}

export interface FleetInputs {
  usage: UsagePayload | null;
  auth: AuthPayload | null;
  accounts: readonly AccountRow[];
  /** Live sessions riding each head, by head key. */
  sessions: ReadonlyMap<string, number>;
  topologyStale: boolean;
  now: number;
}

const WINDOW_NAME = { '5h': '5 hours', '7d': 'Week' } as const;

const OAUTH_KINDS = new Set(['chatgpt-oauth', 'grok-oauth', 'kimi-oauth', 'muse-oauth']);

/** The start command a stopped local runtime is copied as. */
export const startCommandOf = (head: HeadStatus): string => `rig up ${head.key}`;

function accountLine(head: HeadStatus, pool: readonly AccountRow[], auth: AuthPayload | null): string {
  if (providerFamily(head.authKind) === 'local') return FL.local;
  if (pool.length > 1) return `Pool · ${pool.length} accounts`;
  const only = pool[0];
  if (only?.label != null && only.label !== '') return only.label;
  const masked = auth?.[head.key]?.account_id_masked;
  return masked ?? (head.authKind === 'api-key' ? 'API key' : '');
}

function tightest(head: HeadStatus, usage: UsagePayload | null, now: number): Extract<FleetLine, { kind: 'gauge' }> | null {
  const entry = usage?.heads.find((row) => row.key === head.key)?.usage ?? null;
  const live = planWindows(entry, now).filter((window) => !window.stale);
  const best = live.reduce<(typeof live)[number] | null>((a, b) => (a === null || b.pct >= a.pct ? b : a), null);
  if (best === null) return null;
  const pct = Math.round(best.pct);
  return {
    kind: 'gauge',
    name: WINDOW_NAME[best.window],
    pct,
    note: best.resetsAt === null ? '' : `resets ${localInstantText(best.resetsAt)}`,
    full: pct >= 100,
  };
}

export function fleetCard(head: HeadStatus, inputs: FleetInputs): FleetCard {
  const { usage, auth, accounts, sessions, topologyStale, now } = inputs;
  const pool = poolOf(accounts, head.key);
  const card = auth?.[head.key];
  const signals: HeadSignals = {
    credentialPresent: card?.present ?? null,
    refreshLatched: card?.refresh_latched ?? null,
    accountExcluded: selectedExcluded(pool, now),
    topologyStale,
  };
  const attention = headAttention(head, signals, now);
  const until = quotaRefusedUntil(head, now);
  const gauge = tightest(head, usage, now);
  const count = sessions.get(head.key) ?? 0;
  const said = new Set<string>();
  const meta = [familyName(head.authKind), accountLine(head, pool, auth), `${count === 0 ? 'no' : count} ${noun(count, 'session', 'sessions')}`].filter((part) => {
    const key = part.toLowerCase();
    if (part === '' || said.has(key)) return false;
    said.add(key);
    return true;
  });
  const oauth = OAUTH_KINDS.has(head.authKind);
  const base = { key: head.key, title: head.label, colour: colourOfHead(head.authKind), meta };

  const note = (tone: FleetTone, state: string, text: string, fix: FleetFix | null, needsPerson: boolean): FleetCard => ({
    ...base, tone, state, attention: needsPerson, line: gauge === null || tone === 'idle' ? { kind: 'note', text } : gauge, fix,
  });

  switch (attention.cause) {
    case 'down':
      return { ...base, tone: 'idle', state: 'Stopped', attention: false, line: { kind: 'note', text: FL.stopped }, fix: 'start' };
    case 'runtime not answering':
      return {
        ...base, tone: 'idle', state: 'Runtime off', attention: false,
        line: { kind: 'note', text: `The runtime is not answering on ${head.runtimeNotAnswering ?? 'its port'}.` }, fix: 'copy-start',
      };
    case 'unhealthy':
      return note('stuck', 'Failing', FL.unhealthy, 'restart', true);
    case 'out of quota': {
      const when = until === null ? '' : ` until ${localInstantText(until)}`;
      const full: FleetLine = gauge === null ? { kind: 'note', text: `The provider refuses new turns${when}.` } : { ...gauge, full: true, note: until === null ? gauge.note : `out${when}` };
      return { ...base, tone: 'quota', state: `Out of quota${when}`, attention: true, line: full, fix: pool.length > 1 ? 'switch' : null };
    }
    case 'signed out':
    case 'key missing':
      return note('stuck', head.authKind === 'api-key' ? 'Key missing' : 'Signed out', FL.signedOut, oauth ? 'sign-in' : null, true);
    case 'login expired':
      return note('stuck', 'Sign-in expired', FL.loginExpired, oauth ? 'sign-in' : null, true);
    case 'version mismatch':
      return note('wait', 'Version mismatch', `It runs ${head.version ?? 'an unknown version'}; it wants ${head.wantVersion}.`, 'restart', true);
    case 'account excluded':
      return note('wait', 'Account excluded', FL.accountExcluded, pool.length > 1 ? 'switch' : null, true);
    case 'queue full':
      return note('wait', 'Queue full', FL.queueFull, null, true);
    case 'restart needed':
      return note('wait', 'Restart needed', FL.restartNeeded, 'restart', true);
    case 'ok': {
      const level = gauge === null || usage === null ? 'ok' : planLevel(gauge.pct, usage.warn_pct);
      if (level !== 'ok') return note('quota', 'Near its limit', '', null, false);
      return { ...base, tone: 'work', state: 'Ready', attention: false, line: gauge, fix: null };
    }
  }
}
