// The `upgrade` status, typed from FEATURES.md 4.12 ("installed, latest, rollback available") and
// section 6 (GET /api/upgrade). It lives beside the doctor read because the two are one question
// asked twice: the doctor checks the machine, the upgrade status checks the jar.
//
// PENDING V4-127: the route does not exist. The orchestrator's note of 2026-09-18 put its call here
// rather than in a slice of its own, because the doctor page is the only surface that shows it.
import type { PendingRoute } from '@shared/api';

export interface UpgradePayload {
  /** The jar the daemon is running. */
  installed: string;
  /** The newest release it could see, or null when it could not reach one. Null is "unknown", never
   *  "up to date": a console that printed a tick for an unreachable registry would be guessing. */
  latest: string | null;
  /** Whether the previous jar is still on disk to roll back to. */
  rollback_available: boolean;
}

export type UpgradeSlice = UpgradePayload | PendingRoute;

/** The v0.4.0 item that will serve GET /api/upgrade. */
export const PENDING_UPGRADE = 'V4-127';

/** Whether an upgrade is offered, as three states rather than a boolean: "unknown" is a real answer
 *  and a page must be able to say it instead of implying "current". */
export type UpgradeVerdict = 'unknown' | 'current' | 'behind';

export function upgradeVerdict(payload: UpgradePayload): UpgradeVerdict {
  if (payload.latest === null) return 'unknown';
  return payload.latest === payload.installed ? 'current' : 'behind';
}
