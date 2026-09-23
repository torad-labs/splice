// The `upgrade` status, typed from FEATURES.md 4.12 ("installed, latest, rollback available") and
// section 6 (GET /api/upgrade). It lives beside the doctor read because the two are one question
// asked twice: the doctor checks the machine, the upgrade status checks the jar.
//
// Served by V4-127 (UpgradeRoute, body ConsoleUpgradeStatus.kt). The orchestrator's note of
// 2026-09-18 put its call here rather than in a slice of its own, because the doctor page is the
// only surface that shows it.
import type { PendingRoute } from '@shared/api';

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

export function upgradeVerdict(payload: UpgradePayload): UpgradeVerdict {
  if (payload.latest_basis !== 'measured') return 'unknown';
  // Measured and null: the check looked and found nothing newer.
  if (payload.latest === null) return 'current';
  return payload.latest === payload.installed ? 'current' : 'behind';
}
