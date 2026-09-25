// NEEDS YOU (V4-219): the console's first screen, one ranked list of everything that needs the
// operator, each item with its one fix. Marlin's rule is the page's contract:
//   - every item comes from a measured signal, read through the definition its own page prints:
//     headAttention for a head, nearestLimit for a plan window, isStalled for a turn, the daemon's
//     availability for a session, wantsAttention for a doctor check;
//   - an input nobody could read is an unknown, listed with why, never silence;
//   - "Nothing needs you" only when every input was read, as of the oldest of those reads.
// Pure: no React, no store and no clock (the caller passes `now`), so each rule is held in a test
// against plain payloads.
import { exclusionText } from '@entities/account';
import type { AccountRow, AccountsState } from '@entities/account';
import type { DoctorCheck, DoctorSlice } from '@entities/doctor';
import { checkFinding, collapseChecks, fixMasked, wantsAttention } from '@entities/doctor';
import { headAttention } from '@entities/heads';
import { inflightFrom, isStalled } from '@entities/perf';
import { sessionLabel, UNKNOWN_HEAD } from '@entities/session';
import type { SessionRow, SessionsPayload } from '@entities/session';
import type { TeamRow, TeamsState } from '@entities/team';
import { nearestLimit } from '@features/nearest-limit';
import { accountName, stateOf as accountStateOf } from '@widgets/account-table';
import type { AuthPayload, HeadStatus, UsagePayload } from '@shared/api';
import { ABSENT, fmtMs, timeAgo } from '@shared/lib';
import { H, S, U } from './strings';

/** One read the list rests on, as its store holds it: data kept across a failed poll, the error of
 *  the newest poll, and when the newest good answer landed. */
export interface Read<T> {
  data: T | null;
  error: string | null;
  lastUpdated: number | null;
}

export const INPUTS = ['heads', 'auth', 'accounts', 'usage', 'sessions', 'teams', 'doctor', 'topology'] as const;
export type InputName = (typeof INPUTS)[number];

export interface NeedInputs {
  heads: Read<HeadStatus[]>;
  auth: Read<AuthPayload>;
  accounts: Read<AccountsState>;
  usage: Read<UsagePayload>;
  sessions: Read<SessionsPayload>;
  teams: Read<TeamsState>;
  doctor: Read<DoctorSlice>;
  /** /health's topologyStale: the config file changed since the daemon started. */
  topology: Read<boolean>;
  /** Settings this console saved that wait for a restart: its own record of its own writes. */
  restartPending: readonly string[];
}

/** An input's state: answered, still out, failed on its newest poll, or a route this daemon does
 *  not serve (it answered 404). Only `read` counts toward "Nothing needs you". */
export type ReadState = 'read' | 'reading' | 'failed' | 'unserved';

export interface Reading {
  input: InputName;
  state: ReadState;
  /** Epoch ms of the newest good answer, or null when there was none. */
  at: number | null;
  /** Why it is not read: the error, or the item that will serve the route. */
  reason: string | null;
}

const pendingOf = (data: unknown): string | null =>
  typeof data === 'object' && data !== null && 'pending' in data ? String((data as { pending: unknown }).pending) : null;

export function readingOf(input: InputName, read: Read<unknown>): Reading {
  if (read.error !== null) return { input, state: 'failed', at: read.lastUpdated, reason: read.error };
  if (read.data === null) return { input, state: 'reading', at: null, reason: null };
  const pending = pendingOf(read.data);
  if (pending !== null) return { input, state: 'unserved', at: read.lastUpdated, reason: pending };
  return { input, state: 'read', at: read.lastUpdated, reason: null };
}

export type Severity = 'danger' | 'warn';

/** The page an item belongs to, which is also where its detail lives. */
export type Source = keyof typeof S.sources;

/** The one fix an item offers: a write the console makes here, a command to copy, or the page
 *  where the fix is. */
export type Fix =
  | { kind: 'start'; head: string }
  | { kind: 'restart'; head: string }
  | { kind: 'restart-daemon' }
  | { kind: 'copy'; command: string }
  /** A remedy the report's redaction reached: printed with why, never offered to copy. */
  | { kind: 'masked'; command: string }
  /** A fix the daemon runs itself (V4-220 item 4): run from here, its command beside it to copy
   *  (or printed with why, when masked). */
  | { kind: 'doctor-fix'; id: string; command: string; masked: boolean }
  | { kind: 'open'; href: string; label: string };

export interface Need {
  key: string;
  severity: Severity;
  source: Source;
  /** The head the item is about, for its mark; null when it is about no one head. */
  head: string | null;
  subject: string;
  finding: string;
  fix: Fix;
}

export interface NeedsList {
  needs: Need[];
  readings: Reading[];
  /** The oldest read, once every input was read: the time "Nothing needs you" is true as of. */
  readAt: number | null;
}

const open = (href: string, label: string): Fix => ({ kind: 'open', href, label });
const ACCOUNTS = open('#/accounts', S.openAccounts);
const SIGN_IN = open('#/accounts', S.signIn);

/** A read's data, or null when it holds none or holds a route this daemon does not serve. */
const answered = <T>(read: Read<T | { pending: string }>): T | null =>
  read.data === null || pendingOf(read.data) !== null ? null : (read.data as T);

// ---- each source's items ---------------------------------------------------------------------

/**
 * What each head needs, by headAttention, the definition Fleet prints. Two of its causes are said
 * once elsewhere rather than once per head: an excluded account is its account's item, and a
 * changed config file is the daemon's.
 */
function headNeeds(heads: readonly HeadStatus[], auth: AuthPayload | null): Need[] {
  return heads.flatMap((head): Need[] => {
    const card = auth?.[head.key];
    const attention = headAttention(head, {
      credentialPresent: card?.present ?? null,
      refreshLatched: card?.refresh_latched ?? null,
      accountExcluded: false,
      topologyStale: false,
    });
    const need = (severity: Severity, finding: string, fix: Fix): Need[] => [
      { key: `heads:${head.key}`, severity, source: 'heads', head: head.key, subject: head.label, finding, fix },
    ];
    switch (attention.cause) {
      case 'down':
        return need('danger', H.down, { kind: 'start', head: head.key });
      case 'unhealthy':
        return need('danger', H.unhealthy, { kind: 'restart', head: head.key });
      case 'version mismatch':
        return need('warn', `${U.runs} ${head.version ?? ABSENT}, ${U.wants} ${head.wantVersion}`, { kind: 'restart', head: head.key });
      case 'signed out':
        return need('warn', H.signedOut, SIGN_IN);
      case 'key missing': {
        // `splice key set` writes the key store and the next request reads it: no restart (Fleet).
        const variable = card?.env_var;
        return variable === undefined
          ? need('warn', H.keyMissingBare, open('#/fleet', S.openFleet))
          : need('warn', `${variable} ${U.notSet}`, { kind: 'copy', command: `splice key set ${variable}` });
      }
      case 'login expired':
        return need('warn', H.loginExpired, SIGN_IN);
      case 'queue full':
        return need('warn', H.queueFull, open('#/turns', S.openTurns));
      case 'account excluded':
      case 'restart needed':
      case 'ok':
        return [];
    }
  });
}

/** The daemon's one item: the config file changed since it started, or settings this console saved
 *  wait for it. Both are cleared by the same restart, so they are one item. */
function daemonNeeds(topologyStale: boolean | null, pending: readonly string[]): Need[] {
  const parts = [
    topologyStale === true ? H.configChanged : null,
    pending.length === 0 ? null : `${pending.length} ${U.waiting}`,
  ].filter((part) => part !== null);
  if (parts.length === 0) return [];
  return [{ key: 'daemon', severity: 'warn', source: 'daemon', head: null, subject: S.daemon, finding: parts.join(' '), fix: { kind: 'restart-daemon' } }];
}

/** The nearest limit, the one definition the status strip, Fleet and Accounts print, when the
 *  daemon's own lines put it past warn. */
function planNeeds(accounts: readonly AccountRow[], usage: UsagePayload | null, auth: AuthPayload | null, now: number): Need[] {
  const nearest = nearestLimit({ accounts, usage, auth }, now);
  if (nearest === null || nearest.level === 'ok') return [];
  const reset = nearest.reset === null ? '' : `, ${U.resets} ${nearest.reset}`;
  return [{
    key: 'plans',
    severity: nearest.level === 'critical' ? 'danger' : 'warn',
    source: 'plans',
    head: nearest.head,
    subject: nearest.account ?? S.nearest,
    finding: `${nearest.window} ${U.at} ${nearest.pct}%${reset}`,
    fix: ACCOUNTS,
  }];
}

/**
 * What an account needs that the nearest limit cannot say: an exclusion, and a pooled login gone.
 * A single login's missing credential is its head's `signed out`, said once there.
 */
function accountNeeds(accounts: readonly AccountRow[], now: number): Need[] {
  return accounts.flatMap((account): Need[] => {
    const state = accountStateOf(account, now);
    const need = (finding: string, fix: Fix): Need[] => [{
      key: `accounts:${account.heads.join(',')}:${account.label ?? ''}`,
      severity: 'warn',
      source: 'accounts',
      head: account.heads[0] ?? null,
      subject: accountName(account),
      finding,
      fix,
    }];
    if (state === 'signedOut' && account.label !== null) return need(H.accountSignedOut, SIGN_IN);
    if (state === 'excluded') return need(exclusionText(account), ACCOUNTS);
    return [];
  });
}

/** Every live turn idle past its head's own stream idle limit, by isStalled, the definition Turns
 *  prints. */
function turnNeeds(heads: readonly HeadStatus[]): Need[] {
  return inflightFrom(heads).filter(isStalled).map((turn, at) => ({
    key: `turns:${turn.head}:${turn.label}:${at}`,
    severity: 'warn',
    source: 'turns',
    head: turn.head,
    subject: turn.label,
    finding: `${U.idle} ${fmtMs(turn.idleMs)}, ${U.limit} ${fmtMs(turn.streamIdleMs)}`,
    fix: open('#/turns', S.openTurns),
  }));
}

/** Every session the daemon calls stale: alive, and not heard from inside its stale window. The
 *  sessions board tints the same rows as the ones that need the operator. */
function sessionNeeds(rows: readonly SessionRow[], now: number): Need[] {
  return rows.filter((row) => row.availability === 'stale').map((row) => ({
    key: `sessions:${row.session_id ?? row.pid ?? sessionLabel(row)}`,
    severity: 'warn',
    source: 'sessions',
    head: row.head === UNKNOWN_HEAD || row.head === '' ? null : row.head,
    subject: sessionLabel(row),
    finding: row.updated_at === null ? H.quietSince : `${U.lastHeard} ${timeAgo(row.updated_at, now)}`,
    fix: open('#/sessions', S.openSessions),
  }));
}

/** Every seat of a live team whose bound session ended or left the registry: the team runs a seat
 *  short until another session is bound. Read only when the registry answered, or every seat would
 *  read as ended. */
function teamNeeds(teams: readonly TeamRow[], rows: readonly SessionRow[]): Need[] {
  return teams.filter((team) => !team.archived).flatMap((team) => team.slots.flatMap((slot): Need[] => {
    if (slot.session === null) return [];
    const row = rows.find((candidate) => candidate.session_id === slot.session);
    if (row !== undefined && row.availability !== 'gone') return [];
    return [{
      key: `teams:${team.id}:${slot.id}`,
      severity: 'warn',
      source: 'teams',
      head: slot.head,
      subject: `${team.name} ${slot.role} ${U.seat}`,
      finding: row === undefined ? H.seatUnlisted : H.seatEnded,
      fix: open('#/teams', S.openTeams),
    }];
  }));
}

/**
 * Every doctor check that wants the operator, with its own remedy to copy where it carries one, and
 * the same finding once, as Doctor's rack collapses it (four unlinked launchers are one item with one
 * `splice install --all`). A head's sign-in check (`auth/<head>`) is the same fact as its head's
 * item, said once there.
 */
function doctorNeeds(checks: readonly DoctorCheck[], saidFor: ReadonlySet<string>): Need[] {
  const wanted = checks.filter((check) => {
    const [section, name = ''] = check.id.split('/');
    return wantsAttention(check.status) && !(section === 'auth' && saidFor.has(name));
  });
  return collapseChecks(wanted).map((row) => ({
    key: `doctor:${row.key}`,
    severity: row.status === 'fail' ? 'danger' : 'warn',
    source: 'doctor',
    head: null,
    subject: row.label,
    finding: [...new Set(row.members.map(checkFinding))].join('; '),
    fix: doctorFix(row.fix, row.fixId),
  }));
}

/** A doctor row's one fix: the daemon runs it, or its command is copied (printed when masked), or,
 *  with no remedy in the row, Doctor is where to look. */
function doctorFix(command: string | null, id: string | null): Fix {
  if (command === null) return open('#/doctor', S.openDoctor);
  if (id !== null) return { kind: 'doctor-fix', id, command, masked: fixMasked(command) };
  return { kind: fixMasked(command) ? 'masked' : 'copy', command };
}

// ---- the list --------------------------------------------------------------------------------

/** The order sources rank in within one severity: the heads splice runs on first, the daemon's own
 *  checks last. */
const SOURCE_ORDER: readonly Source[] = ['heads', 'daemon', 'plans', 'accounts', 'turns', 'sessions', 'teams', 'doctor'];

export function needsOf(inputs: NeedInputs, now: number): NeedsList {
  const heads = answered(inputs.heads) ?? [];
  const auth = answered(inputs.auth);
  const accounts = answered(inputs.accounts)?.accounts ?? [];
  const usage = answered(inputs.usage);
  const registry = answered(inputs.sessions);
  const teams = answered(inputs.teams)?.teams ?? [];
  const doctor = answered(inputs.doctor);

  const fromHeads = headNeeds(heads, auth);
  const saidFor = new Set(fromHeads.flatMap((need) => (need.head === null ? [] : [need.head])));
  const needs = [
    ...fromHeads,
    ...daemonNeeds(answered(inputs.topology), inputs.restartPending),
    ...planNeeds(accounts, usage, auth, now),
    ...accountNeeds(accounts, now),
    ...turnNeeds(heads),
    ...(registry === null ? [] : sessionNeeds(registry.sessions, now)),
    ...(registry === null ? [] : teamNeeds(teams, registry.sessions)),
    ...(doctor === null ? [] : doctorNeeds(doctor.checks, saidFor)),
  ];
  const rank = (need: Need): number => (need.severity === 'danger' ? 0 : 1) * SOURCE_ORDER.length + SOURCE_ORDER.indexOf(need.source);
  needs.sort((left, right) => rank(left) - rank(right));

  const readings = INPUTS.map((input) => readingOf(input, inputs[input]));
  const all = readings.every((reading) => reading.state === 'read');
  const readAt = all ? Math.min(...readings.map((reading) => reading.at ?? now)) : null;
  return { needs, readings, readAt };
}
