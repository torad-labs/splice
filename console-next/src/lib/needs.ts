// NEEDS YOU (V4-219): the console's first screen, one ranked list of everything that needs the
// operator, beside near-limit readings with no act when a pool cannot switch. Marlin's rule is the page's contract:
//   - every item comes from a measured signal, read through the definition its own page prints:
//     headAttention for a head, nearestLimit for a plan window, stateOf for a
//     session, wantsAttention for a doctor check;
//   - an input nobody could read is an unknown, listed with why, never silence;
//   - "Nothing needs you" only when every input was read, as of the oldest of those reads.
// Only live things need the operator: a session needs a person only when it waits for an answer or its
// live turn has gone quiet (lib/sessions.ts needsPerson), and a deliberately stopped local runtime is a
// Fleet state, never an item.
// Pure: no React, no store and no clock (the caller passes `now`), so each rule is held in a test
// against plain payloads. Ported from the old console's pages/needs-you/model.ts.
import type { AccountRow } from '../types/accounts';
import type { PendingRoute } from '../types/budget';
import type { AuthPayload, HeadStatus, UsagePayload } from '../types/core';
import type { DoctorCheck, FixKind } from '../types/doctor';
import { INPUTS } from '../types/needs';
import type {
  Fix, InputName, Need, NeedInputs, NeedKind, NeedsList, Read, ReadState, Reading, Severity, Source,
} from '../types/needs';
import type { SessionRow } from '../types/sessions';
import { UNKNOWN_HEAD } from '../types/sessions';
import type { TeamRow } from '../types/teams';
import { exclusionText, isExcluded, isServable, poolOf, refusalText } from './accounts';
import { checkFinding, collapseChecks, fixMasked, logsHrefOf, wantsAttention } from './doctor';
import { ABSENT } from './format';
import { headAttention, localInstantText, quotaRefusedUntil } from './heads';
import { nearestLimit } from './nearest-limit';
import { waitingQuestion } from './session-says';
import { activityText, needsPerson, repoName, sessionKey, sessionLabel, stateOf, timingOf } from './sessions';
import type { TurnOf } from './sessions';
import { H, K, S, U } from './words-needs';

export { INPUTS };
export type { Fix, InputName, Need, NeedInputs, NeedKind, NeedsList, Read, ReadState, Reading, Severity, Source };

const pendingOf = (data: unknown): string | null =>
  typeof data === 'object' && data !== null && 'pending' in data ? String((data as { pending: unknown }).pending) : null;

export function readingOf(input: InputName, read: Read<unknown>): Reading {
  if (read.error !== null) return { input, state: 'failed', at: read.lastUpdated, reason: read.error };
  if (read.data === null) return { input, state: 'reading', at: null, reason: null };
  const pending = pendingOf(read.data);
  if (pending !== null) return { input, state: 'unserved', at: read.lastUpdated, reason: pending };
  return { input, state: 'read', at: read.lastUpdated, reason: null };
}

// ---- where an item opens ---------------------------------------------------------------------

/** The hash address of the page an item belongs to, and of its own detail where the page opens one: a
 *  head's card on Fleet (`/fleet/<key>`), a session's own page. The daemon is no page's. */
export function hrefOf(source: 'daemon'): null;
export function hrefOf(source: Exclude<Source, 'daemon'> | 'turns', id?: string): string;
export function hrefOf(source: Source | 'turns', id?: string): string | null;
export function hrefOf(source: Source | 'turns', id?: string): string | null {
  switch (source) {
    case 'heads':
    case 'accounts':
      return id === undefined ? '#/fleet' : `#/fleet/${encodeURIComponent(id)}`;
    case 'plans':
      return '#/usage';
    case 'turns':
      return '#/turns';
    case 'sessions':
      return id === undefined ? '#/sessions' : `#/sessions/${encodeURIComponent(id)}`;
    case 'teams':
      return '#/sessions?group=team';
    case 'doctor':
      return '#/settings/health';
    case 'daemon':
      return null;
  }
}

const open = (href: string, label: string): Fix => ({ kind: 'open', href, label });
const SIGN_IN = open(hrefOf('accounts'), S.signIn);
const OAUTH_KINDS = new Set(['chatgpt-oauth', 'grok-oauth', 'kimi-oauth', 'muse-oauth']);
const loginFix = (kind: string, head: string, label?: string): Fix => OAUTH_KINDS.has(kind)
  ? { kind: 'login', head, ...(label === undefined ? {} : { label }) } : SIGN_IN;

/** A read's data, or null when it holds none or holds a route this daemon does not serve. */
const answered = <T>(read: Read<T | PendingRoute>): T | null =>
  read.data === null || pendingOf(read.data) !== null ? null : (read.data as T);

// ---- each source's items ---------------------------------------------------------------------

/** The item of a head whose provider refuses turns until an instant still ahead. An OAuth head that
 *  rides a pool of more than one login can switch account; any other waits for the reset or changes plan. */
function quotaNeed(head: HeadStatus, until: number, accounts: readonly AccountRow[]): Need {
  const pooled = OAUTH_KINDS.has(head.authKind) && poolOf(accounts, head.key).length > 1;
  return {
    key: `heads:${head.key}`, severity: 'warn', source: 'heads', kind: K.quota, head: head.key, subject: head.label,
    finding: H.outOfQuota(localInstantText(until)),
    fix: open(hrefOf('heads', head.key), pooled ? S.switchAccount : S.seePlan),
    at: hrefOf('heads', head.key),
  };
}

/**
 * What each head needs, by headAttention, the definition Fleet prints. Two of its causes are said
 * once elsewhere rather than once per head: an excluded account is its account's item, and a
 * changed config file is the daemon's. A head whose runtime is not answering is a Fleet state, no item.
 */
function headNeeds(heads: readonly HeadStatus[], auth: AuthPayload | null, accounts: readonly AccountRow[], now: number): Need[] {
  return heads.flatMap((head): Need[] => {
    const card = auth?.[head.key];
    const attention = headAttention(head, {
      credentialPresent: card?.present ?? null,
      refreshLatched: card?.refresh_latched ?? null,
      accountExcluded: false,
      topologyStale: false,
    }, now);
    const need = (severity: Severity, kind: NeedKind, finding: string, fix: Fix): Need[] => [
      { key: `heads:${head.key}`, severity, source: 'heads', kind, head: head.key, subject: head.label, finding, fix, at: hrefOf('heads', head.key) },
    ];
    switch (attention.cause) {
      case 'down':
        return need('danger', K.failing, H.down, { kind: 'start', head: head.key });
      case 'unhealthy':
        return need('danger', K.failing, H.unhealthy, { kind: 'restart', head: head.key });
      case 'version mismatch':
        return need('warn', K.version, `${U.runs} ${head.version ?? ABSENT}, ${U.wants} ${head.wantVersion}`, { kind: 'restart', head: head.key });
      case 'signed out':
        return need('warn', K.signedOut, H.signedOut, loginFix(head.authKind, head.key));
      case 'key missing': {
        // `splice key set` writes the key store and the next request reads it: no restart (Fleet).
        const variable = card?.env_var;
        return variable === undefined
          ? need('warn', K.keyMissing, H.keyMissingBare, open(hrefOf('heads'), S.openFleet))
          
          : need('warn', K.keyMissing, `${variable} ${U.notSet}`, { kind: 'copy', command: `splice key set ${variable}` });
      }
      case 'login expired':
        return need('warn', K.signedOut, H.loginExpired, loginFix(head.authKind, head.key));
      case 'queue full':
        return need('warn', K.queue, H.queueFull, open(hrefOf('turns'), S.openTurns));
      case 'out of quota': {
        const until = quotaRefusedUntil(head, now);
        return until === null ? [] : [quotaNeed(head, until, accounts)];
      }
      // Fleet's own states, or said once elsewhere: never a head item.
      case 'runtime not answering':
      case 'account excluded':
      case 'restart needed':
      case 'ok':
        return [];
    }
  });
}

/** The daemon's one item: the config file changed since it started, or settings this console saved
 *  wait for it. Both are cleared by the same restart, so they are one item. */
function daemonNeeds(topologyStale: boolean | null, pending: readonly string[], checks: readonly DoctorCheck[]): Need[] {
  const parts = [
    topologyStale === true ? H.configChanged : null,
    pending.length === 0 ? null : `${pending.length} ${pending.length === 1 ? U.oneWaiting : U.waiting}`,
    ...checks.map((check) => check.id.startsWith('configuration/trace:')
      ? H.tracePending(check.id.slice('configuration/trace:'.length)) : H.checkPending),
  ].filter((part) => part !== null);
  if (parts.length === 0) return [];
  return [{
    key: 'daemon', severity: 'warn', source: 'daemon', kind: K.restart, head: null, subject: S.daemon,
    finding: parts.join(' '), fix: { kind: 'restart-daemon' }, at: null,
  }];
}

/** The nearest limit, the one definition the status strip, Fleet and Usage print, when the daemon's
 *  own lines put it past warn, unless it is the head whose out-of-quota item already says so. */
function planNeeds(
  accounts: readonly AccountRow[], usage: UsagePayload | null, auth: AuthPayload | null, now: number, refused: ReadonlySet<string>,
): Need[] {
  const nearest = nearestLimit({ accounts, usage, auth }, now);
  if (nearest === null || nearest.level === 'ok') return [];
  if (nearest.head !== null && refused.has(nearest.head)) return [];
  const reset = nearest.reset === null ? '' : `, ${U.resets} ${nearest.reset}`;
  const pool = nearest.head === null ? [] : poolOf(accounts, nearest.head);
  const canSwitch = pool.some((account) => account.label !== nearest.account && account.selected !== true && isServable(account) && !isExcluded(account, now));
  return [{
    key: 'plans',
    severity: nearest.level === 'critical' ? 'danger' : 'warn',
    source: 'plans',
    kind: K.plan,
    head: nearest.head,
    subject: nearest.account ?? S.nearest,
    finding: `${nearest.window === null ? '' : `${nearest.window} `}${U.at} ${nearest.pct}%${reset}`,
    fix: canSwitch && nearest.head !== null ? open(hrefOf('accounts', nearest.head), S.switchAccount) : null,
    // The nearest limit is the fleet's, read across every account: its page, not one row.
    at: hrefOf('plans'),
  }];
}

/**
 * What an account needs that the nearest limit cannot say: a refused credential, a pooled login gone,
 * and an exclusion, decided in that order (a refusal is not renewed by signing in; a login with no
 * credential serves nothing, so it comes before any exclusion the pool made while it could not
 * authenticate). A single login's missing credential is its head's `signed out`, said once there.
 */
function accountNeeds(accounts: readonly AccountRow[], now: number): Need[] {
  return accounts.flatMap((account): Need[] => {
    const head = account.heads[0];
    const need = (finding: string, fix: Fix): Need[] => [{
      key: `accounts:${account.heads.join(',')}:${account.label ?? ''}`,
      severity: 'warn',
      source: 'accounts',
      kind: K.account,
      head: head ?? null,
      subject: account.label ?? S.singleLogin,
      finding,
      fix,
      at: hrefOf('accounts', head),
    }];
    // A credential the daemon refuses to load says why and opens Fleet: signing in again is not its fix.
    const refusal = refusalText(account);
    if (refusal !== null) return need(refusal, open(hrefOf('accounts'), S.openFleet));
    if (!account.credential_present) {
      return account.label === null ? [] : need(H.accountSignedOut, head === undefined ? SIGN_IN : loginFix(account.kind, head, account.label));
    }
    return isExcluded(account, now) ? need(exclusionText(account), open(hrefOf('accounts'), S.openFleet)) : [];
  });
}

/** The daemon cuts a message at 160 characters without saying so: one that stops mid-sentence ends in an ellipsis, so the
 *  sentence after it does not run into it. */

/** Every session that needs a person by lib/sessions.ts: one waiting for an answer, or a busy one whose
 *  live turn reports itself quiet past STUCK_IDLE_MS. Never a session merely called stale by the daemon:
 *  a busy session with no live turn is running a tool, and an idle one is waiting for its next message. */
function sessionNeeds(rows: readonly SessionRow[], now: number, turnOf: TurnOf): Need[] {
  return rows.flatMap((row): Need[] => {
    const turn = turnOf(row);
    const state = stateOf(row, turn);
    if (!needsPerson(state)) return [];
    const stuck = state === 'stuck';
    const head = row.head === UNKNOWN_HEAD || row.head === '' ? null : row.head;
    const at = hrefOf('sessions', sessionKey(row));
    return [{
      key: `sessions:${row.session_id ?? row.pid ?? sessionLabel(row)}`,
      severity: 'warn',
      source: 'sessions',
      kind: stuck ? K.stuck : K.waiting,
      state: stuck ? 'stuck' : 'waiting',
      head,
      subject: sessionLabel(row),
      finding: activityText(state, timingOf(row, state, turn, now).since),
      // Only a question the session itself asked is quoted: a system notice or a tool's output is not one.
      session: { id: row.session_id, said: waitingQuestion(row.last), repo: repoName(row) },
      fix: stuck && head !== null && row.session_id !== null
        ? { kind: 'stop-turn', head, session: row.session_id }
        : open(at, S.openSession),
      at,
    }];
  });
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
      kind: K.seat,
      head: slot.head,
      subject: `${team.name} ${slot.role} ${U.seat}`,
      finding: row === undefined ? H.seatUnlisted : H.seatEnded,
      fix: open(hrefOf('teams'), S.openTeams),
      at: hrefOf('teams'),
    }];
  }));
}

/**
 * Every doctor check that wants the operator and is about no head with an item here, with its own
 * remedy to copy where it carries one, and the same finding once, as Doctor's rack collapses it
 * (four unlinked launchers are one item with one `splice install --all`).
 */
function doctorNeeds(checks: readonly DoctorCheck[]): Need[] {
  return collapseChecks(checks).map((row) => ({
    key: `doctor:${row.key}`,
    severity: row.status === 'fail' ? 'danger' : 'warn',
    source: 'doctor',
    kind: K.doctor,
    head: null,
    subject: row.label,
    finding: [...new Set(row.members.map(checkFinding))].join('; '),
    fix: doctorFixOf(row.fix, row.fixId, row.fixKind),
    at: hrefOf('doctor'),
  }));
}

/**
 * The heads with an item here that a doctor check is about (V4-333): a head's sign-in
 * (`auth/<head>`) and its port (`daemon/head <head>`, DoctorHeadChecks) are its own, and the
 * daemon's count of heads still coming up (`daemon/heads` at warn) is about every head that is
 * down. That count at fail carries the daemon's own remedy, which Start is not: it stays its own item.
 */
function headsOfCheck(check: DoctorCheck, said: ReadonlySet<string>, down: readonly string[]): string[] {
  const [section, name = ''] = check.id.split('/');
  const own = section === 'auth' ? name : section === 'daemon' && name.startsWith('head ') ? name.slice('head '.length) : null;
  if (own !== null) return said.has(own) ? [own] : [];
  return check.id === 'daemon/heads' && check.status === 'warn' ? [...down] : [];
}

/** A head's item, and what Doctor found about that head after it: one stopped head is one item. */
function withDoctor(need: Need, checks: readonly DoctorCheck[]): Need {
  // What Doctor adds is what the head's own finding has not already said: "X not set" and "X is not set" are one fact.
  const words = (text: string): string => text.toLowerCase().replace(/\b(is|are)\b/g, '').replace(/[^a-z0-9_ ]/g, '').replace(/\s+/g, ' ').trim();
  const added = [...new Set(checks.map(checkFinding))].filter((finding) => words(finding) !== words(need.finding));
  if (added.length === 0) return need;
  // The head's finding closes as a sentence first: "not set Doctor:" ran together in V4-331's render.
  const said = /[.!?]$/.test(need.finding) ? need.finding : `${need.finding}.`;
  return { ...need, finding: `${said} ${U.doctor} ${added.join('; ')}` };
}

/** A doctor row's one fix: the daemon runs it, or its command is copied (printed when masked), or,
 *  with no remedy in the row, Doctor is where to look. */
export function doctorFixOf(remedy: string | null, id: string | null, kind: FixKind | null): Fix {
  if (id !== null) return remedy === null ? { kind: 'doctor-fix', id } : { kind: 'doctor-fix', id, fallback: remedy };
  if (remedy === null) return open(hrefOf('doctor'), S.openDoctor);
  if (fixMasked(remedy)) return { kind: 'masked', command: remedy };
  // Two remedies the console serves itself, recognised whole: the daemon restart and a head's log tail.
  if (remedy.trim() === 'splice restart') return { kind: 'restart-daemon' };
  const logsHref = logsHrefOf(remedy);
  if (logsHref !== null) return { kind: 'open', href: logsHref, label: S.openLog, fallback: remedy };
  // Only what the daemon marked a command is offered to paste; advice is printed beside the link to Doctor.
  if (kind === 'command') return { kind: 'copy', command: remedy };
  return { kind: 'open', href: hrefOf('doctor'), label: S.openDoctor, fallback: remedy };
}

// ---- the list --------------------------------------------------------------------------------

/** The order sources rank in within one severity: the heads splice runs on first, the daemon's own
 *  checks last. */
export const SOURCE_ORDER: readonly Source[] = ['heads', 'daemon', 'plans', 'accounts', 'sessions', 'teams', 'doctor'];

const noTurns: TurnOf = () => undefined;

export function needsOf(inputs: NeedInputs, now: number): NeedsList {
  const heads = answered(inputs.heads) ?? [];
  const auth = answered(inputs.auth);
  const accounts = answered(inputs.accounts)?.accounts ?? [];
  const usage = answered(inputs.usage);
  const registry = answered(inputs.sessions);
  const teams = answered(inputs.teams)?.teams ?? [];
  const doctor = answered(inputs.doctor);
  const topologyStale = answered(inputs.topology);

  const fromHeads = headNeeds(heads, auth, accounts, now);
  const headOf = (need: Need): string[] => (need.head === null ? [] : [need.head]);
  const said = new Set(fromHeads.flatMap(headOf));
  // headNeeds says danger only for a head down or failing its health check: the heads not up.
  const down = fromHeads.filter((need) => need.severity === 'danger').flatMap(headOf);
  const refused = new Set(fromHeads.filter((need) => need.kind === K.quota).flatMap(headOf));
  const wanted = (doctor?.checks ?? []).filter((check) => wantsAttention(check.status));
  const about = new Map(wanted.map((check) => [check, headsOfCheck(check, said, down)] as const));
  const needs = [
    ...fromHeads.map((need) => withDoctor(need, wanted.filter((check) => need.head !== null && about.get(check)?.includes(need.head)))),
    ...daemonNeeds(topologyStale, inputs.restartPending, wanted.filter((check) => check.pending_restart === true)),
    ...planNeeds(accounts, usage, auth, now, refused),
    ...accountNeeds(accounts, now),
    ...(registry === null ? [] : sessionNeeds(registry.sessions, now, inputs.turnOf ?? noTurns)),
    ...(registry === null ? [] : teamNeeds(teams, registry.sessions)),
    ...(doctor === null ? [] : doctorNeeds(wanted.filter((check) =>
      about.get(check)?.length === 0 && check.pending_restart !== true &&
      !(topologyStale === true && check.id === 'daemon/topology' && check.status === 'warn'),
    ))),
  ];
  const rank = (need: Need): number => (need.severity === 'danger' ? 0 : 1) * SOURCE_ORDER.length + SOURCE_ORDER.indexOf(need.source);
  needs.sort((left, right) => rank(left) - rank(right));

  const readings = INPUTS.map((input) => readingOf(input, inputs[input]));
  const all = readings.every((reading) => reading.state === 'read');
  const readAt = all ? Math.min(...readings.map((reading) => reading.at ?? now)) : null;
  return { needs, readings, readAt };
}
