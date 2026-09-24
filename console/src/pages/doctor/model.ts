// The doctor page's pure half: how a check reads, how the report is gated on redaction, and the
// playground's state machine.
//
// The playground is a reducer rather than a handful of booleans for one reason that is a promise
// rather than a style: a body sent through it is NEVER stored. The daemon does not record it
// (V4-133: "one prompt through one head, raw request and response back, never recorded"), and the
// console must not either. A pure machine that returns the response inside its own state is a
// thing a test can prove holds nothing anywhere else; a component that quietly wrote to the store
// or to localStorage could not be.
import type { DoctorCheck, DoctorPayload, DoctorStatus, Leak } from '@entities/doctor';
import { checkFix, checkSection, leaksIn } from '@entities/doctor';
import type { Edge } from '@shared/ui';

/** What the board may print of a report, and why it may print nothing. */
export interface GatedReport {
  /** The report, only when it carries no credential shape; null when none was served or it was
   *  refused. The ONE value a surface on the board may read the report through. */
  shown: DoctorPayload | null;
  /** Where each shape survived, by kind and path; empty unless the report was refused. */
  leaks: Leak[];
}

/**
 * THE REDACTION GATE, as one value rather than a flag beside the payload (M4-07).
 *
 * It was a boolean the rack checked, while the fix list and the opened check's detail read the
 * payload itself, so a secret the gate caught still printed in the aside. A gate that hands out the
 * report only when it passed cannot be walked around by a surface that forgets to ask: a surface
 * that reads `shown` gets null from a refused report, and there is nothing else to read.
 */
export function gateReport(report: DoctorPayload | null): GatedReport {
  if (report === null) return { shown: null, leaks: [] };
  const leaks = leaksIn(report);
  return { shown: leaks.length === 0 ? report : null, leaks };
}

/** The holder edge for a check's status. `info` is grey rather than green: it is a statement, not a
 *  pass, and a report that painted it green would make "3 checks passed" out of "2 passed, 1 had
 *  something to say". */
export function statusEdge(status: DoctorStatus): Edge {
  if (status === 'ok') return 'green';
  if (status === 'warn') return 'amber';
  if (status === 'fail') return 'red';
  return 'grey';
}

/** Whether a check wants the operator: warn and fail cock the strip, ok and info do not. */
export function wantsAttention(status: DoctorStatus): boolean {
  return status === 'fail' || status === 'warn';
}

export interface CheckGroup {
  key: string;
  checks: DoctorCheck[];
}

/**
 * The checks grouped by their id's section, which is what the id's prefix is for
 * ("daemon/port" is a `daemon` check).
 *
 * The `attention first` view orders both the sections and the checks inside them by worst status,
 * so the thing that needs the operator is at the top of its rack and not buried under a wall of
 * green. `by section` keeps the alphabetical order instead, which is what an operator who knows
 * the check they are looking for wants.
 */
export function groupChecks(checks: readonly DoctorCheck[], view: { sort: { field: string } | null }): CheckGroup[] {
  const rank: Record<DoctorStatus, number> = { fail: 3, warn: 2, info: 1, ok: 0 };
  const groups = new Map<string, DoctorCheck[]>();
  for (const check of checks) {
    const section = checkSection(check);
    const bucket = groups.get(section);
    if (bucket === undefined) groups.set(section, [check]);
    else bucket.push(check);
  }
  const byAttention = view.sort?.field === 'status';
  return [...groups.entries()]
    .map(([key, rows]) => ({
      key,
      checks: byAttention
        ? [...rows].sort((l, r) => rank[r.status] - rank[l.status] || l.id.localeCompare(r.id))
        : [...rows].sort((l, r) => l.id.localeCompare(r.id)),
    }))
    .sort((left, right) => {
      if (!byAttention) return left.key.localeCompare(right.key);
      const worst = (group: CheckGroup) => Math.max(...group.checks.map((check) => rank[check.status]));
      return worst(right) - worst(left) || left.key.localeCompare(right.key);
    });
}

/** One row of the checks rack: a check, or several that say the same thing about different heads. */
export interface CheckRow {
  /** The first member's id: stable across polls, and what the page opens by. */
  key: string;
  status: DoctorStatus;
  /** The id for one check; for several, the id up to the colon with the member count. */
  label: string;
  fix: string | null;
  members: DoctorCheck[];
}

/** The part of an id after its colon, which names the head or project a check is about. */
export function subjectOf(check: DoctorCheck): string {
  const colon = check.id.indexOf(':');
  return colon === -1 ? check.id : check.id.slice(colon + 1);
}

/**
 * THE SAME FINDING ONCE, NOT ONCE PER HEAD (console review, 2026-09-24). The live report carried
 * eleven `configuration/system-prompt:<head>` warnings with one identical fix, and they filled the
 * first screen of the rack. Checks collapse when they share a status, the id up to its colon (the
 * whole id when it has none), and the fix word for word; a check whose fix names its head
 * (`splice logs --head claudex`) stays its own row, because its remedy differs. Order is the first
 * member's. The row's key is that grouping, so two rows never share one.
 */
export function collapseChecks(checks: readonly DoctorCheck[]): CheckRow[] {
  // The family is the id up to its colon, or the whole id when it has none. A colon-less id is a
  // family too: the daemon sends one `installation/wrapper` per launcher (eleven live), and keying
  // those by id alone overwrote ten of them, so the rack printed 1 row for 11 checks and the
  // attention count disagreed with the rows under it (splice-lead's walkthrough, B1).
  const rows = new Map<string, CheckRow & { family: string }>();
  for (const check of checks) {
    const colon = check.id.indexOf(':');
    const fix = checkFix(check);
    const family = colon === -1 ? check.id : check.id.slice(0, colon);
    const key = `${check.status}|${family}|${fix ?? ''}`;
    const row = rows.get(key);
    if (row === undefined) rows.set(key, { key, family, status: check.status, label: check.id, fix, members: [check] });
    else row.members.push(check);
  }
  return [...rows.values()].map(({ family, ...row }) => row.members.length === 1
    ? row
    : { ...row, label: `${family} (${row.members.length})` });
}

/** The head a `splice logs --head <head>` remedy names, so the page can open that log itself. */
export function logsHeadOf(fix: string | null): string | null {
  const match = fix === null ? null : /^splice logs --head (\S+)/.exec(fix);
  return match === null ? null : match[1];
}

/** How many checks are not `ok`. The report's one number, and the one the page leads with. */
export function attentionCount(checks: readonly DoctorCheck[]): number {
  return checks.filter((check) => wantsAttention(check.status)).length;
}

/**
 * THE REPORT'S OWN FACTS, AS SERVED (M2-22).
 *
 * The page was served twenty-six typed fields and printed nine, and the seventeen it did not print
 * were not pending anything: `generated_at`, `schema_version`, `os.*` and `jvm.*` arrive in the
 * same payload every check does. This is that surface, printed.
 *
 * THE FIELD NAME IS THE LABEL, and that is a decision rather than a shortcut. A hand-written
 * caption per row would be seven chances to invent a meaning the payload does not carry -- whether
 * `os.version` is a kernel, a distribution release or a build number is a fact about the daemon's
 * host and not about this page, and a caption that guessed would be read as authoritative. The
 * payload's own key cannot be wrong about what it names.
 *
 * THE EXCLUSIONS, because a list that does not say what it left out reads as complete:
 *   - `splice.version` and `claude_code.version` are PRINTED, in the detail column's version strip
 *     and its note; printing them again would be the page saying one fact twice (M1-34, B10).
 *   - `topology`, `accounts` and `perf` are DELIBERATELY UNTYPED on `DoctorPayload`: the entity
 *     carries them as opaque values so the redaction pass can walk them, and says in its own words
 *     that a page wanting one "has to narrow it first rather than read fields off a shape nobody
 *     typed". Narrowing one means typing a daemon shape with no route behind it, which is the
 *     invented member this row refuses.
 *   - `checks` is the rack below, and `logs`, `logs_dropped_in_tail` and `logs_error` arrive only
 *     with `--with-logs`, which the console's doctor route does not ask for; the log tail has its
 *     own page.
 */
export function reportFacts(payload: DoctorPayload): { field: string; value: string }[] {
  return [
    { field: 'generated_at', value: payload.generated_at },
    { field: 'schema_version', value: String(payload.schema_version) },
    { field: 'os.name', value: payload.os.name },
    { field: 'os.version', value: payload.os.version },
    { field: 'os.arch', value: payload.os.arch },
    { field: 'jvm.version', value: payload.jvm.version },
    { field: 'jvm.vendor', value: payload.jvm.vendor },
  ];
}

// ── the playground ───────────────────────────────────────────────────────────

export type PlaygroundStep = 'idle' | 'sending' | 'answered' | 'failed';

/**
 * One playground run. The request and the response live HERE and nowhere else: no store, no
 * storage, no history. A second send replaces them, which is the point — the console is not a log.
 */
export interface PlaygroundState {
  step: PlaygroundStep;
  head: string;
  prompt: string;
  request: unknown | null;
  response: unknown | null;
  note: string | null;
  /** Which send the in-flight request belongs to. Every send takes the next number, and an answer
   *  lands only on the run that asked for it, while it is still waiting: the send is a real request
   *  (POST /api/playground), so its answer can arrive after a `clear` or a second send, and landing
   *  it then would put back on screen a body the operator had already dropped. */
  run: number;
}

export const IDLE_PLAYGROUND: PlaygroundState = {
  step: 'idle',
  head: '',
  prompt: '',
  request: null,
  response: null,
  note: null,
  run: 0,
};

export type PlaygroundEvent =
  | { kind: 'head'; value: string }
  | { kind: 'prompt'; value: string }
  | { kind: 'send' }
  | { kind: 'answered'; run: number; request: unknown; response: unknown }
  | { kind: 'failed'; run: number; note: string }
  | { kind: 'reset' };

/** Whether an answer belongs to the run on screen: the same run, still waiting for it. */
function awaited(state: PlaygroundState, run: number): boolean {
  return state.step === 'sending' && state.run === run;
}

/** Whether a run can start: it needs a head and something to say. */
export function canSend(state: PlaygroundState): boolean {
  return state.head.trim() !== '' && state.prompt.trim() !== '' && state.step !== 'sending';
}

export function playgroundNext(state: PlaygroundState, event: PlaygroundEvent): PlaygroundState {
  switch (event.kind) {
    case 'head':
      return { ...state, head: event.value };
    case 'prompt':
      return { ...state, prompt: event.value };
    case 'send':
      if (!canSend(state)) return state;
      // The previous run's bodies are dropped HERE, at the only moment a new one begins: a console
      // that kept them would be a body store the operator never asked for.
      return { ...state, step: 'sending', request: null, response: null, note: null, run: state.run + 1 };
    case 'answered':
      if (!awaited(state, event.run)) return state;
      return { ...state, step: 'answered', request: event.request, response: event.response, note: null };
    case 'failed':
      if (!awaited(state, event.run)) return state;
      return { ...state, step: 'failed', note: event.note, request: null, response: null };
    case 'reset':
      // The run number survives the reset and nothing else does: it is what makes the answer to a
      // request the operator walked away from arrive to a run that is no longer waiting for it.
      return { ...IDLE_PLAYGROUND, run: state.run };
    default:
      return state;
  }
}

/**
 * The capture fixture's name, or null.
 *
 * Gated on `dev` explicitly rather than reading `import.meta.env` here, so the rule is testable: a
 * fixture must never be reachable in a shipped artifact, and the caller passes the real
 * `import.meta.env.DEV`. CONTRACTS.md section 4 fixes the rest: the address carries
 * `?fixture=<name>`, and a rendered fixture is labelled `sample data`.
 */
export function fixtureName(search: string, dev: boolean): string | null {
  if (!dev) return null;
  const name = new URLSearchParams(search).get('fixture');
  if (name === null) return null;
  const trimmed = name.trim();
  return trimmed === '' ? null : trimmed;
}

/**
 * The page's honest empties, as data rather than inline JSX, so a test can assert each names its
 * source (CONTRACTS.md section 8).
 */
export const EMPTIES = {
  noReport: { text: 'doctor unavailable', source: 'this splice version does not serve the doctor report' },
  noChecks: { text: 'no checks reported', source: 'the daemon returned an empty report; run splice doctor in a terminal to compare' },
} as const;
