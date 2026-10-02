// The shapes of the Needs-you list: what it reads (one Read per input), and what it says (one Need per
// finding, with a fix only when the person has an act). The derivation is lib/needs.ts.
import type { AccountsPayload } from './accounts';
import type { PendingRoute } from './budget';
import type { AuthPayload, HeadStatus, UsagePayload } from './core';
import type { DoctorSlice } from './doctor';
import type { SessionsPayload } from './sessions';
import type { TeamsPayload } from './teams';

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
  accounts: Read<AccountsPayload | PendingRoute>;
  usage: Read<UsagePayload>;
  sessions: Read<SessionsPayload>;
  teams: Read<TeamsPayload | PendingRoute>;
  doctor: Read<DoctorSlice>;
  /** /health's topologyStale: the config file changed since the daemon started. */
  topology: Read<boolean>;
  /** Settings this console saved that wait for a restart: its own record of its own writes. */
  restartPending: readonly string[];
}

/** An input's state: answered, still out, failed on its newest poll, or a route this daemon does
 *  not serve (it answered `pending`). Only `read` counts toward "Nothing needs you". */
export type ReadState = 'read' | 'reading' | 'failed' | 'unserved';

export interface Reading {
  input: InputName;
  state: ReadState;
  /** Epoch ms of the newest good answer, or null when there was none. */
  at: number | null;
  /** Why it is not read: the error, or the item that will serve the route. */
  reason: string | null;
}

export type Severity = 'danger' | 'warn';

/** The page an item belongs to, which is also where its detail lives. */
export type Source = 'heads' | 'daemon' | 'plans' | 'accounts' | 'sessions' | 'teams' | 'doctor';

/** The word a card prints as its state. */
export type NeedKind =
  | 'Waiting on you' | 'Out of quota' | 'Signed out' | 'Key missing' | 'Failing' | 'Version mismatch' | 'Queue full'
  | 'Restart needed' | 'Command near its limit' | 'Account' | 'Team seat' | 'Doctor';

/** The one fix an item offers: a write the console makes here, a command to copy, or the page
 *  where the fix is. */
export type Fix =
  | { kind: 'start'; head: string }
  | { kind: 'restart'; head: string }
  | { kind: 'restart-daemon' }
  | { kind: 'login'; head: string; label?: string }
  | { kind: 'copy'; command: string }
  /** A remedy the report's redaction reached: printed with why, never offered to copy. */
  | { kind: 'masked'; command: string }
  /** A fix the daemon runs itself (V4-220 item 4), without a terminal command. */
  | { kind: 'doctor-fix'; id: string; fallback?: string }
  | { kind: 'open'; href: string; label: string; fallback?: string };

export interface Need {
  key: string;
  severity: Severity;
  source: Source;
  /** What the card prints as its state. */
  kind: NeedKind;
  /** A session item's own state; absent on every other source. */
  state?: 'waiting';
  /** The head the item is about, for its mark; null when it is about no one head. */
  head: string | null;
  subject: string;
  finding: string;
  /** A session item's own words: the session id it joins to /api/sessions by, the newest message it left (a waiting
   *  session's question), and the repo it works in. Absent on every other source. */
  session?: { id: string | null; said: string | null; repo: string | null };
  /** Null for a near-limit reading with no account the person can switch to. */
  fix: Fix | null;
  /** Where the item itself opens: its detail on its page where the page has one, else the page;
   *  null for the daemon, which no page opens. */
  at: string | null;
}

export interface NeedsList {
  needs: Need[];
  readings: Reading[];
  /** The oldest read, once every input was read: the time "Nothing needs you" is true as of. */
  readAt: number | null;
}
