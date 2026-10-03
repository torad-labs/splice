// `splice doctor --json`, typed from the code that builds it (app/.../cli/DoctorReport.kt
// build(), schema version 1) and from the shape pass that renders each check
// (DoctorReportShape.checks); and `GET /api/upgrade`, typed from ConsoleUpgradeStatus.kt.
//
// PENDING V4-127: GET /api/doctor does not exist. When it lands it serves this same report, so the
// type is taken from the report builder rather than invented.
import type { PendingRoute } from './budget';

/** The doctor's own vocabulary (DoctorConfigChecks.kt:22), lowercased on the wire. */
export type DoctorStatus = 'ok' | 'info' | 'warn' | 'fail';

export interface DoctorCheck {
  /** "<section>/<name>", e.g. "daemon/port". The page groups by the part before the slash. */
  id: string;
  status: DoctorStatus;
  /** The check's sentence: what it found. Its remedy, when it has one, is [fix]. */
  detail: string;
  /** Supporting counts, disclosed separately from the headline when the daemon supplies them. */
  details?: string | null;
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
  /**
   * What [fix] is (DoctorCheckTypes.kt FixKind): `command` is one shell line safe to paste as it stands, `advice` is a
   * sentence about what to change. Null with no fix; absent from a daemon older than the field, which the console reads
   * as advice, so nothing is offered to paste on a guess.
   */
  fix_kind?: FixKind | null;
  /** V4-371: the declared trace value differs from what this daemon boot runs. */
  pending_restart?: boolean;
}

/** `fix_kind` on the wire. */
export type FixKind = 'command' | 'advice';

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
   * page and its own entity, and FEATURES.md 4.12 asks this page for checks and version drift. They
   * ride along as opaque values so the redaction check can still walk them, and a page that wants
   * one has to narrow it first rather than read fields off a shape nobody typed.
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

/** V4-371's current wire requires the flag on every check; the view accepts older reports too. */
export interface DoctorWireCheck extends DoctorCheck {
  pending_restart: boolean;
}

export interface DoctorWirePayload extends DoctorPayload {
  checks: DoctorWireCheck[];
}

export type DoctorSlice = DoctorPayload | PendingRoute;

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
  /** The first member's `fix_kind`: whether [fix] is a line to paste or advice to read. */
  fixKind: FixKind | null;
  members: DoctorCheck[];
}

/** The credential and identifier shapes the CLI masks. */
export type LeakKind = 'jwt' | 'bearer' | 'key-value' | 'provider-key' | 'email' | 'uuid' | 'opaque';

/** Where a shape survived, named by kind and JSON path. The matched text is deliberately absent. */
export interface Leak {
  kind: LeakKind;
  where: string;
}

/** What a fact rests on: `measured` (looked, and this is the answer, which may be null) or
 *  `unavailable` (could not look, and the `_unavailable_reason` says why). */
export type UpgradeBasis = 'measured' | 'unavailable';

/** GET /api/upgrade exactly as ConsoleUpgradeStatus.json writes it: a VALUE PLUS A BASIS for each fact
 *  that can be absent, never a bare boolean that would answer "up to date" when nothing checked. */
export interface UpgradePayload {
  /** The jar the daemon is running. Always measured. */
  installed: string;
  /** The newest release, or null. Null with a measured basis is "nothing newer"; with an unavailable
   *  basis it is "never checked", and the console must say the second rather than imply the first. */
  latest: string | null;
  latest_basis: UpgradeBasis;
  latest_unavailable_reason?: string | null;
  /** The previous release still on disk to roll back to; null with a measured basis means there is
   *  none. */
  rollback_target: string | null;
  rollback_basis: UpgradeBasis;
  rollback_unavailable_reason: string | null;
  /** When a check last SUCCEEDED, absolute; null when none ever has (never zero, never the epoch). */
  checked_at_epoch_millis: number | null;
}

export type UpgradeSlice = UpgradePayload | PendingRoute;

/** The v0.4.0 item that will serve GET /api/upgrade. */
export const PENDING_UPGRADE = 'V4-127';

/** Whether an upgrade is offered, as three states rather than a boolean: "unknown" is a real answer
 *  and a page must be able to say it instead of implying "current". */
export type UpgradeVerdict = 'unknown' | 'current' | 'behind';

/** Where a console-started run stands, in UpgradeRunState's wire words (UpgradeRuns.kt). `lost`: the
 *  shell that ran it is gone and left no exit code (killed, or the machine went down). */
export const UPGRADE_RUN_STATES = ['running', 'succeeded', 'failed', 'lost'] as const;
export type UpgradeRunState = (typeof UPGRADE_RUN_STATES)[number];

/** One `splice upgrade` the console started, as UpgradeRunRoutes.view writes it (V4-220 item 4). The
 *  run is out of process and restarts the daemon, so its record lives on disk and whichever daemon is
 *  up answers for it. */
export interface UpgradeRun {
  id: string;
  /** The CLI's own arguments: `upgrade`, then `--to vX` or `--rollback` when asked. */
  args: string[];
  state: UpgradeRunState;
  started_at_epoch_millis: number;
  /** Null until the run ends, and on a lost run. */
  exit_code: number | null;
  /** The run's last 200 lines, colour codes stripped. */
  output: string[];
}

/** What POST /api/upgrade asks for: neither field is the latest release, `to` one release, and
 *  `rollback` the previous release, which takes no version. */
export interface UpgradeAsk {
  to?: string;
  rollback?: true;
}
