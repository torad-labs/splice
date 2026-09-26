// `splice doctor --json`, typed from the code that builds it (app/.../cli/DoctorReport.kt
// build(), schema version 1) and from the shape pass that renders each check
// (DoctorReportShape.checks).
//
// PENDING V4-127: GET /api/doctor does not exist. When it lands it serves this same report, so the
// type is taken from the report builder rather than invented.
import type { PendingRoute } from '@shared/api';

/** The doctor's own vocabulary (DoctorConfigChecks.kt:22), lowercased on the wire. */
export type DoctorStatus = 'ok' | 'info' | 'warn' | 'fail';

export interface DoctorCheck {
  /** "<section>/<name>", e.g. "daemon/port". The page groups by the part before the slash. */
  id: string;
  status: DoctorStatus;
  /** The check's sentence: what it found. Its remedy, when it has one, is [fix]. */
  detail: string;
  /**
   * The command or step that clears the check (DoctorReportShape.checks), null when it offers none.
   * V4-253: it rode inside [detail] behind an em-dash separator until then, so the JSON users paste
   * into issues carried U+2014 on every check with a remedy.
   */
  fix?: string | null;
  /**
   * The fix the daemon can run itself for this check (V4-220 item 4, DoctorReportShape.kt:84):
   * POST /api/doctor/fix/{fix_id} runs it and answers with doctor re-run. Null, or absent from a
   * daemon older than the field, when the remedy is the operator's to make.
   */
  fix_id?: string | null;
}

export interface DoctorPayload {
  schema_version: number;
  /** ISO-8601 instant, the daemon's clock (`Instant.now().toString()`). */
  generated_at: string;
  splice: { version: string };
  claude_code: { version: string };
  os: { name: string; version: string; arch: string };
  jvm: { version: string; vendor: string };
  /**
   * The report's topology, accounts and perf blocks are deliberately NOT modelled: each has its own
   * page and its own entity (@entities/config, @entities/auth, and the perf entity in this slice's
   * own terms), and FEATURES.md 4.12 asks this page for checks and version drift. They ride along as
   * opaque values so the redaction check can still walk them, and a page that wants one has to
   * narrow it first rather than read fields off a shape nobody typed.
   */
  topology: unknown;
  checks: DoctorCheck[];
  accounts: unknown;
  perf: unknown;
  /** Only with `--with-logs`; the report omits the key entirely otherwise. */
  logs?: string[];
  logs_dropped_in_tail?: number;
  logs_error?: string;
}

export type DoctorSlice = DoctorPayload | PendingRoute;

/** The check id's section, or the whole id when it carries no slash. */
export function checkSection(check: DoctorCheck): string {
  const slash = check.id.indexOf('/');
  return slash === -1 ? check.id : check.id.slice(0, slash);
}

/** The check's remedy, or null when it offers none. Null, not an empty string: "no remedy" and "a
 *  remedy that is empty" are different sentences. */
export function checkFix(check: DoctorCheck): string | null {
  return check.fix ?? null;
}

/** Whether a check wants the operator: warn and fail do, ok and info do not. Doctor and Needs you
 *  both read it. */
export function wantsAttention(status: DoctorStatus): boolean {
  return status === 'fail' || status === 'warn';
}

/**
 * Whether the report's redaction reached a fix: the daemon masks a value (`<redacted>`) or a path it
 * does not recognise (`<redacted:path>`), and a line with a mask in it runs nothing as pasted
 * (Marlin, 2026-09-25: `export PATH="<redacted:path>"` sat beside a Copy button). Such a fix is
 * printed, never offered to copy.
 */
export function fixMasked(fix: string): boolean {
  return /<redacted(?::[a-z-]+)?>/.test(fix);
}

/** What the check found, which a page prints on its own line apart from the remedy. */
export function checkFinding(check: DoctorCheck): string {
  return check.detail;
}

/** One row of the checks rack: a check, or several that say the same thing about different heads. */
export interface CheckRow {
  /** `status|family|fix`: unique on the rack, but it moves when a check's status does, so the page
   *  opens a row by its first member's id instead (DoctorBoard resolves either). */
  key: string;
  status: DoctorStatus;
  /** The id for one check; for several, the id up to the colon with the member count. */
  label: string;
  fix: string | null;
  /** The fix the daemon runs itself (the first member's `fix_id`), null when the remedy is text. */
  fixId: string | null;
  members: DoctorCheck[];
}

/**
 * THE SAME FINDING ONCE, NOT ONCE PER HEAD (console review, 2026-09-24): Doctor's rack and Needs you both read it. The live report carried
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
    if (row === undefined) rows.set(key, { key, family, status: check.status, label: check.id, fix, fixId: check.fix_id ?? null, members: [check] });
    else row.members.push(check);
  }
  return [...rows.values()].map(({ family, ...row }) => row.members.length === 1
    ? row
    : { ...row, label: `${family} (${row.members.length})` });
}
