// The doctor page's pure half: how a check reads, how the report is gated on redaction, and the
// playground's state machine.
//
// The playground is a reducer rather than a handful of booleans for one reason that is a promise
// rather than a style: a body sent through it is NEVER stored. The daemon does not record it
// (V4-133: "one prompt through one head, raw request and response back, never recorded"), and the
// console must not either. A pure machine that returns the response inside its own state is a
// thing a test can prove holds nothing anywhere else; a component that quietly wrote to the store
// or to localStorage could not be.
import type { DoctorCheck, DoctorPayload, DoctorStatus } from '@entities/doctor';
import { checkSection } from '@entities/doctor';
import type { Edge } from '@shared/ui';

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
  noReport: { text: 'doctor report not built', source: 'row V4-127' },
  upgrade: { text: 'upgrade status not built', source: 'row V4-127' },
  capture: { text: 'body capture not built', source: 'row V4-133' },
  noChecks: { text: 'no checks reported', source: 'GET /api/doctor' },
} as const;
