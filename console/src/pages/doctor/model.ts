// The doctor page's pure half: how a check reads, how the report is gated on redaction, and the
// playground's state machine.
//
// The playground is a reducer rather than a handful of booleans for one reason that is a promise
// rather than a style: a body sent through it is NEVER stored. The daemon does not record it
// (V4-133: "one prompt through one head, raw request and response back, never recorded"), and the
// console must not either. A pure machine that returns the response inside its own state is a
// thing a test can prove holds nothing anywhere else; a component that quietly wrote to the store
// or to localStorage could not be.
import type { DoctorCheck, DoctorPayload, DoctorStatus, Leak, UpgradePayload } from '@entities/doctor';
import { checkSection, leaksIn, wantsAttention } from '@entities/doctor';
import { ABSENT, fmtInt } from '@shared/lib';
import type { BarPart, Mark, Tone } from '@shared/ui';
import { H, S } from './strings';

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

/** A status's badge tone and chart mark. `info` is neutral rather than green: it is a statement,
 *  not a pass, and a report that painted it green would make "3 checks passed" out of "2 passed, 1
 *  had something to say". */
export const TONE: Record<DoctorStatus, Tone> = { ok: 'ok', info: 'neutral', warn: 'warn', fail: 'danger' };
export const MARK: Record<DoctorStatus, Mark> = { ok: 'ok', info: 'series-2', warn: 'warn', fail: 'danger' };

/** The order statuses are drawn and counted in, so the colours never swap places. */
const STATUSES: readonly DoctorStatus[] = ['ok', 'info', 'warn', 'fail'];

/** A row's tint: only a check that wants the operator. */
export function rowTone(status: DoctorStatus): Tone | null {
  return status === 'fail' ? 'danger' : status === 'warn' ? 'warn' : null;
}

/** The checks by status as bar parts: the report's state at a glance, and each section's. */
export function statusParts(checks: readonly DoctorCheck[]): BarPart[] {
  const counts: Record<DoctorStatus, number> = { ok: 0, info: 0, warn: 0, fail: 0 };
  for (const check of checks) counts[check.status] += 1;
  return STATUSES.map((status) => ({ key: status, label: S.statusName[status], value: counts[status], mark: MARK[status] }));
}

/** Claude Code's version as its tile prints it. The daemon reports `claude --version` verbatim,
 *  `2.1.282 (Claude Code)`, and the tile's label already names the product. When the probe read no
 *  version the same field carries the daemon's sentence (`probe timed out`, `present (version probe
 *  failed: …)`, DoctorInstallProbes.capturedVersion), which as the figure ran past the tile and was
 *  cut (CI run 36184525303, no `claude` on the runner): the figure says the version is unknown and
 *  the sentence reads whole under it. */
export function claudeVersion(version: string): { figure: string; note: string | null } {
  const bare = version.replace(/\s*\(Claude Code\)$/, '');
  return /^\d+(\.\d+)+/.test(bare) ? { figure: bare, note: null } : { figure: S.unknownVersion, note: version };
}

/** The newest release as the page prints it: the version, `None` when the check looked and found
 *  nothing newer, and the absence when it never looked (never "nothing newer" by default). */
export function latestText(upgrade: UpgradePayload | null): string {
  if (upgrade === null || upgrade.latest_basis !== 'measured') return ABSENT;
  return upgrade.latest ?? S.none;
}

/** The release to roll back to, by the same rule: measured and null is none on disk. */
export function rollbackText(upgrade: UpgradePayload | null): string {
  if (upgrade === null || upgrade.rollback_basis !== 'measured') return ABSENT;
  return upgrade.rollback_target ?? S.none;
}

export interface CheckGroup {
  key: string;
  checks: DoctorCheck[];
}

/**
 * The checks in the order the active view reads them.
 *
 * `attention first` is ONE list, worst status first and then by id: every check that wants the
 * operator comes before any that does not. It used to sort inside each section and the sections by
 * their worst check, so under a warn-led `configuration` section its ok rows sat above `runtime`'s
 * warnings, and the sections were not labelled on screen to explain the order (walkthrough S14).
 * The section is still the id's prefix, printed in every row's first cell.
 *
 * `by section` groups by that prefix ("daemon/port" is a `daemon` check), alphabetically, which is
 * what an operator who knows the check they are looking for wants.
 */
export function groupChecks(checks: readonly DoctorCheck[], view: { sort: { field: string } | null }): CheckGroup[] {
  const rank: Record<DoctorStatus, number> = { fail: 3, warn: 2, info: 1, ok: 0 };
  if (view.sort?.field === 'status') {
    return [{ key: 'attention', checks: [...checks].sort((l, r) => rank[r.status] - rank[l.status] || l.id.localeCompare(r.id)) }];
  }
  const groups = new Map<string, DoctorCheck[]>();
  for (const check of checks) {
    const section = checkSection(check);
    const bucket = groups.get(section);
    if (bucket === undefined) groups.set(section, [check]);
    else bucket.push(check);
  }
  return [...groups.entries()]
    .map(([key, rows]) => ({ key, checks: [...rows].sort((l, r) => l.id.localeCompare(r.id)) }))
    .sort((left, right) => left.key.localeCompare(right.key));
}

/** The part of an id after its colon, which names the head or project a check is about. */
export function subjectOf(check: DoctorCheck): string {
  const colon = check.id.indexOf(':');
  return colon === -1 ? check.id : check.id.slice(colon + 1);
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

/** What the attention count is made of, worst first (`6 Fail · 2 Warn`): a bare number said how
 *  many and not what (splice-lead, 2026-09-25). Null when nothing wants the operator. */
export function attentionParts(checks: readonly DoctorCheck[]): string | null {
  const parts = [...STATUSES].reverse().filter(wantsAttention).flatMap((status) => {
    const count = checks.filter((check) => check.status === status).length;
    return count === 0 ? [] : [`${fmtInt(count)} ${S.statusName[status]}`];
  });
  return parts.length === 0 ? null : parts.join(' · ');
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
  noReport: { text: S.unavailable, source: H.noReport },
  noChecks: { text: S.noChecks, source: H.noChecks },
} as const;
