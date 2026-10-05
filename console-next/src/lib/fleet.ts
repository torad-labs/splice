// What one head's card on Fleet says: a title, one state, the one window that matters, one quiet line of facts and at
// most one act. Pure: the clock and every read are passed in. Every fact is measured by the derivation its own page
// prints (headAttention, planWindows, poolOf), so Fleet and Needs you cannot disagree about a head.
import type { AccountRow } from '../types/accounts';
import type { AuthPayload, HeadStatus, UsagePayload } from '../types/core';
import type { KeysPayload } from '../types/login';
import { familyName, headAttention, isKeyHead, localZonedInstantText, providerAnswerText, providerFamily, quotaRefusedUntil } from './heads';
import type { HeadSignals } from './heads';
import { colourOf } from './model';
import type { ModelColour } from './model';
import { accountEmail, accountName, poolOf, selectedExcluded } from './accounts';
import { planLevel, planWindows, rateLimitAge } from './usage';
import { countWord, noun, timeAgo } from './format';
import { FL } from './words-fleet';
import { Q } from './words-quota';

/** Where a plan stands, in the few ways the page's one sentence counts them. */
export type FleetStanding = 'ready' | 'quota' | 'signed-out' | 'off' | 'other';

export type FleetTone = 'work' | 'wait' | 'stuck' | 'idle' | 'quota';

/** The one act a card offers. `copy-start` is a copy, never a button that starts: the runtime is rig's, not splice's. */
export type FleetFix = 'switch' | 'sign-in' | 'start' | 'restart' | 'copy-start' | 'copy-key' | 'copy-launch';

export type FleetLine =
  /** The tightest plan window, drawn as a bar. `full` is a window that has refused turns. */
  | { kind: 'gauge'; name: string; pct: number; note: string; full: boolean; observedAt?: number | null }
  /** A head with no window to draw says its one sentence instead. */
  | { kind: 'note'; text: string };

export interface FleetCard {
  key: string;
  title: string;
  colour: ModelColour;
  tone: FleetTone;
  standing: FleetStanding;
  state: string;
  attention: boolean;
  /** Null for a healthy head with nothing to draw: a card says nothing rather than fill the space. */
  line: FleetLine | null;
  /** Plan family, account, sessions: read once, never chips. */
  meta: string[];
  fix: FleetFix | null;
  /** The command `copy-key` copies: the key store's setter for this head's variable. Null when the daemon names no variable. */
  keyCommand?: string | null;
  /** What a card with no line says instead, only when it is true of this head: a key pays per token, a reading may be missing or old. Null says nothing. */
  none: string | null;
  /** The provider's actual answer and observation age, separate from quota observation time. */
  providerAnswer?: string | null;
}

export interface FleetInputs {
  usage: UsagePayload | null;
  auth: AuthPayload | null;
  accounts: readonly AccountRow[];
  /** Live sessions riding each head, by head key. */
  sessions: ReadonlyMap<string, number>;
  topologyStale: boolean;
  /** The daemon's declared provider family, not a guess from whether a key was stored. Null while status has not answered. */
  family: string | null;
  keys: KeysPayload | null;
  now: number;
}

export const WINDOW_NAME = { '5h': '5 hours', '7d': 'Week' } as const;

const OAUTH_KINDS = new Set(['chatgpt-oauth', 'grok-oauth', 'kimi-oauth', 'muse-oauth']);

/** The start command a stopped local runtime is copied as. */
export const startCommandOf = (head: HeadStatus): string => `rig up ${head.key}`;

/** The daemon's provider family is authoritative: a loopback runtime can have a stored API key, and a remote provider can have none. */
export function kindOf(head: HeadStatus, family: string | null | undefined): string {
  if (family === 'local') return 'local';
  return providerFamily(head.authKind) === 'local' ? 'api-key' : head.authKind;
}

function accountLine(pool: readonly AccountRow[], kind: string): string {
  if (providerFamily(kind) === 'local') return '';
  if (pool.length > 1) return `Pool · ${pool.length} accounts`;
  const only = pool[0];
  return only === undefined ? kind === 'api-key' ? 'API key' : '' : accountName(only);
}

/** Why a head draws no window. A key has none by nature; a plan that draws none has either never been read or been read before its window reset. */
function noWindowText(kind: string, keys: KeysPayload | null, usage: UsagePayload | null, head: HeadStatus, now: number): string | null {
  if (kind === 'api-key') return keys === null ? null : FL.payPerToken;
  if (kind === 'local') return null;
  const entry = usage?.heads.find((row) => row.key === head.key)?.usage ?? null;
  const windows = planWindows(entry, now);
  const last = windows.reduce<(typeof windows)[number] | null>((a, b) => (a === null || (b.observedAt ?? 0) >= (a.observedAt ?? 0) ? b : a), null);
  if (last === null) {
    const age = rateLimitAge(entry, now);
    if (age !== null) return FL.rateLimitReading(age);
    return entry?.ratelimit == null ? FL.noReading : null;
  }
  const when = last.observedAt === null ? 'before its reset' : timeAgo(last.observedAt * 1000, now);
  return FL.lastReading(when, Math.round(last.pct), WINDOW_NAME[last.window], last.resetsAt !== null && last.resetsAt * 1000 <= now);
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
    note: `${pct}%${best.resetsAt === null ? '' : ` · resets ${localZonedInstantText(best.resetsAt)}`}`,
    full: false,
    ...(best.observedAt === null ? {} : { observedAt: best.observedAt }),
  };
}

/** The command that stores a key under its variable, which the card offers to copy. */
export const keyCommandOf = (variable: string): string => `splice key set ${variable}`;

export function fleetCard(head: HeadStatus, inputs: FleetInputs): FleetCard {
  const { usage, auth, accounts, sessions, topologyStale, now } = inputs;
  const pool = poolOf(accounts, head.key);
  const card = auth?.[head.key];
  const signals: HeadSignals = {
    credentialPresent: card?.present ?? null,
    credentialKind: card?.kind ?? null,
    refreshLatched: card?.refresh_latched ?? null,
    accountExcluded: selectedExcluded(pool, now),
    topologyStale,
  };
  const attention = headAttention(head, signals, now);
  const until = quotaRefusedUntil(head, now);
  const gauge = tightest(head, usage, now);
  const count = sessions.get(head.key) ?? 0;
  const said = new Set<string>();
  const kind = kindOf(head, inputs.family);
  const entry = usage?.heads.find((row) => row.key === head.key)?.usage ?? null;
  const otherWindows = planWindows(entry, now).filter(window => WINDOW_NAME[window.window] !== gauge?.name).map(window =>
    `${Q.window(WINDOW_NAME[window.window], window.pct, window.resetsAt === null ? null : localZonedInstantText(window.resetsAt), !window.stale)} · ${Q.observed(window.observedAt === null ? null : localZonedInstantText(window.observedAt))}`);
  const email = pool.length === 1 && pool[0] !== undefined ? accountEmail(pool[0]) : null;
  const meta = [familyName(kind), accountLine(pool, kind), ...(email === null ? [] : [email]), ...otherWindows, `${count === 0 ? 'no' : count} ${noun(count, 'session', 'sessions')}`].filter((part) => {
    const key = part.toLowerCase();
    if (part === '' || said.has(key)) return false;
    said.add(key);
    return true;
  });
  const keyless = isKeyHead(head, card?.kind);
  const oauth = !keyless && OAUTH_KINDS.has(head.authKind);
  const base = { key: head.key, title: head.label, colour: colourOf(inputs.family), meta, none: head.last_provider_answer?.accepted === false ? null : noWindowText(kind, inputs.keys, inputs.usage, head, now), providerAnswer: providerAnswerText(head, now) };

  const note = (tone: FleetTone, standing: FleetStanding, state: string, text: string, fix: FleetFix | null, needsPerson: boolean): FleetCard => ({
    ...base, tone, standing, state, attention: needsPerson, line: gauge === null || tone === 'idle' ? { kind: 'note', text } : gauge, fix,
  });

  switch (attention.cause) {
    case 'down':
      return { ...base, tone: 'idle', standing: 'off', state: 'Stopped', attention: false, line: { kind: 'note', text: FL.stopped }, fix: 'start' };
    case 'runtime not answering':
      return {
        ...base, tone: 'idle', standing: 'off', state: 'Runtime off', attention: false,
        line: { kind: 'note', text: `The runtime is not answering on ${head.runtimeNotAnswering ?? 'its port'}.` }, fix: 'copy-start',
      };
    case 'unhealthy':
      return note('stuck', 'other', 'Failing', FL.unhealthy, 'restart', true);
    case 'out of quota': {
      const when = until === null ? '' : ` until ${localZonedInstantText(until)}`;
      const full: FleetLine = gauge === null ? { kind: 'note', text: `The provider refuses new turns${when}.` } : { ...gauge, full: true, note: until === null ? gauge.note : `out${when}` };
      return { ...base, tone: 'quota', standing: 'quota', state: `Out of quota${when}`, attention: true, line: full, fix: pool.length > 1 ? 'switch' : null };
    }
    case 'signed out':
    case 'key missing': {
      const variable = card?.env_var;
      const fix = oauth ? 'sign-in' : keyless && variable !== undefined ? 'copy-key' : null;
      return { ...note('stuck', 'signed-out', keyless ? 'Key missing' : 'Signed out', keyless ? FL.keyMissing : FL.signedOut, fix, true), keyCommand: variable === undefined ? null : keyCommandOf(variable) };
    }
    case 'login expired':
      return note('stuck', 'signed-out', 'Sign-in expired', FL.loginExpired, oauth ? 'sign-in' : null, true);
    case 'version mismatch':
      return note('wait', 'other', 'Version mismatch', `It runs ${head.version ?? 'an unknown version'}; it wants ${head.wantVersion}.`, 'restart', true);
    case 'account excluded':
      return note('wait', 'other', 'Account excluded', FL.accountExcluded, pool.length > 1 ? 'switch' : null, true);
    case 'queue full':
      return note('wait', 'other', 'Queue full', FL.queueFull, null, true);
    case 'restart needed':
      return note('wait', 'other', 'Restart needed', FL.restartNeeded, 'restart', true);
    case 'provider unobserved':
      return { ...base, tone: 'wait', standing: 'other', state: 'Readiness unknown', attention: false, line: gauge ?? { kind: 'note', text: FL.readinessUnknown }, fix: 'copy-launch' };
    case 'provider refused': {
      const status = head.last_provider_answer?.status;
      const access = status === 401 || status === 403;
      const limited = status === 429;
      return { ...base, none: null, tone: limited ? 'quota' : 'stuck', standing: 'other', state: access ? 'Access refused' : limited ? 'Rate limited' : 'Provider refused', attention: true,
        line: { kind: 'note', text: access ? FL.accessRefused : limited ? FL.rateRefused : FL.providerRefused }, fix: null };
    }
    case 'ok': {
      const level = gauge === null || usage === null ? 'ok' : planLevel(gauge.pct, usage.warn_pct);
      const line = level !== 'ok' && gauge !== null ? { ...gauge, note: `near its limit · ${gauge.note.replace(' · resets ', ', resets ')}` } : gauge;
      return { ...base, tone: 'work', standing: 'ready', state: 'Ready', attention: false, line, fix: 'copy-launch' };
    }
  }
}

const STANDING_ORDER: readonly FleetStanding[] = ['ready', 'quota', 'signed-out', 'off', 'other'];

/** The page's one sentence: how many plans, and how many stand each way, then how to arrange them. */
export function fleetLede(cards: readonly FleetCard[]): string {
  const groups = STANDING_ORDER.flatMap((standing) => {
    const count = cards.filter((card) => card.standing === standing).length;
    return count === 0 ? [] : [`${countWord(count).toLowerCase()} ${FL.standing[standing]}`];
  });
  const total = cards.length === 1 ? FL.onePlan : `${countWord(cards.length)} ${FL.manyPlans}`;
  return `${FL.plans(total, groups.join(', '))} ${FL.drag}`;
}
