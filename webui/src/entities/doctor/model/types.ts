// `splice doctor --json`, typed from the code that builds it (gateway/app/.../cli/DoctorReport.kt
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
  /**
   * The check's sentence. A check with a remedy carries it INSIDE this string, appended by the
   * shape pass (DoctorReportShape.kt:59); there is no separate fix field to read, so a page that
   * offers "copy the command" has to take the tail after the separator, and a check without one has
   * no remedy to offer.
   */
  detail: string;
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

/**
 * The separator the daemon builds between a check's detail and its fix: a space, U+2014 EM DASH,
 * then " fix: " (DoctorReportShape.kt:59). Assembled from its code point rather than typed, so this
 * parser cannot be confused with the copy gate that bans em-dashes from UI text, and so the byte
 * that must match the daemon's is named once.
 */
const FIX_SEPARATOR = ` ${String.fromCharCode(0x2014)} fix: `;

/** The check id's section, or the whole id when it carries no slash. */
export function checkSection(check: DoctorCheck): string {
  const slash = check.id.indexOf('/');
  return slash === -1 ? check.id : check.id.slice(0, slash);
}

/** The remedy command carried inside a check's detail, or null when the check offers none. Null,
 *  not an empty string: "no remedy" and "a remedy that is empty" are different sentences. */
export function checkFix(check: DoctorCheck): string | null {
  const at = check.detail.indexOf(FIX_SEPARATOR);
  return at === -1 ? null : check.detail.slice(at + FIX_SEPARATOR.length);
}
