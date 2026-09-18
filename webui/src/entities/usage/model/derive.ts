// The usage entity's pure derivations. The one the rule bar needs is `nearestWindow`: the plan
// window closest to exhaustion across the fleet, which is the single number the operator wants on
// screen at all times.
//
// The semantics are the ones the rule bar already implements in its own copy, kept identical so
// adopting this is a drop-in rather than a behaviour change: a head whose warn source is `none`
// reports no window at all, and an absence cannot be close to exhaustion, so such a head is never
// the nearest one.
import type { AuthPayload, UsagePayload } from '@shared/api';

/** The plan window nearest exhaustion, at its reported length. */
export interface NearestWindow {
  head: string;
  /** The account's masked id or login, where the head reports one. Null when the head is on a
   *  credential the auth card does not name. */
  account: string | null;
  /** The window's reported length, printed as the daemon reports it (`5h`). */
  window: string;
  pct: number;
  reset: string | null;
}

/**
 * The nearest window across every head, or null when no head reports one.
 *
 * `usage` drives it because /api/usage is the route that carries a percentage; `auth` is only
 * asked for the account NAME behind the winning head, so a missing auth card costs the account
 * label and never the window.
 */
export function nearestWindow(
  usage: UsagePayload | null,
  auth: AuthPayload | null,
): NearestWindow | null {
  if (usage === null) return null;
  let best: NearestWindow | null = null;
  for (const entry of usage.heads) {
    const head = entry.usage;
    if (head === null || head.warn.source === 'none') continue;
    if (best !== null && head.warn.pct <= best.pct) continue;
    const card = auth?.[entry.key];
    best = {
      head: entry.key,
      account: card?.account_id_masked ?? card?.login ?? null,
      window: `${usage.window_hours}h`,
      pct: head.warn.pct,
      reset: head.warn.reset,
    };
  }
  return best;
}

/** How many heads report no window at all. Null until the route answers, so a caller can tell
 *  "not loaded yet" from "everyone reports one". */
export function headsReportingNone(usage: UsagePayload | null): number | null {
  if (usage === null) return null;
  return usage.heads.filter((entry) => entry.usage === null || entry.usage.warn.source === 'none')
    .length;
}

/** One head's window state as a strip prints it. */
export interface HeadWindow {
  /** The used percentage, or null when the head reports no window. */
  pct: number | null;
  level: 'ok' | 'warn' | 'critical' | 'none';
  reset: string | null;
}

/**
 * A head's window, from /api/usage.
 *
 * `pct: null` is the whole point: a head with no configured cap and no rate-limit headers has no
 * percentage, and printing 0 would read as "barely used" when the truth is "nobody measured". The
 * page prints the honest empty for that.
 */
export function headWindow(usage: UsagePayload | null, key: string): HeadWindow {
  const entry = usage?.heads.find((row) => row.key === key)?.usage ?? null;
  if (entry === null || entry.warn.source === 'none') {
    return { pct: null, level: 'none', reset: null };
  }
  return { pct: entry.warn.pct, level: entry.warn.level, reset: entry.warn.reset };
}
