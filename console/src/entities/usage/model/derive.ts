// The usage entity's pure derivations. The one the rule bar needs is `nearestWindow`: the plan
// window closest to exhaustion across the fleet, which is the single number the operator wants on
// screen at all times.
//
// A head reports a window two ways, and both count. `warn` is the daemon's reading of the
// rate-limit headers and the 5h token count; `quota` is the plan's own 5h and 7d windows, which
// warn never reads (UsageWarnPolicy.computeUsageWarn). Reading warn alone left the rule bar saying
// "no head reports a limit" while a head sat at 65% of its 5h plan window (live /api/usage,
// 2026-09-24). A head whose warn source is `none` and that tracks no plan window reports nothing,
// and an absence cannot be close to exhaustion.
import { fmtDurationS } from '@shared/lib';
import type { AuthPayload, HeadUsage, QuotaWindow, UsagePayload } from '@shared/api';

/** One plan window of a head, as the page prints it. */
export interface PlanWindow {
  /** The window's length as the daemon names the slot. */
  window: '5h' | '7d';
  pct: number;
  /** Epoch SECONDS, or null where the provider sent none. */
  resetsAt: number | null;
  /** When splice read the figure, epoch SECONDS; null where the daemon does not say. */
  observedAt: number | null;
  /** The window's reset time has passed since the reading, so the figure describes a window that
   *  no longer exists. It is not a candidate for "nearest"; the page says it reset instead. */
  stale: boolean;
}

/** The plan windows a head tracks, five-hour first. Empty when it tracks none. */
export function planWindows(usage: HeadUsage | null, nowMs: number): PlanWindow[] {
  const quota = usage?.quota;
  if (quota === undefined) return [];
  const out: PlanWindow[] = [];
  const add = (window: PlanWindow['window'], entry: QuotaWindow | undefined) => {
    if (entry === undefined) return;
    const resetsAt = entry.resets_at;
    out.push({
      window,
      pct: entry.used_pct,
      resetsAt,
      observedAt: entry.observed_at ?? null,
      stale: resetsAt !== null && resetsAt * 1000 <= nowMs,
    });
  };
  add('5h', quota.five_hour);
  add('7d', quota.seven_day);
  return out;
}

/** How long until a window resets, as the rule bar prints it after `resets`. */
export function resetsInText(resetsAt: number | null, nowMs: number): string | null {
  if (resetsAt === null) return null;
  const seconds = resetsAt - Math.floor(nowMs / 1000);
  if (seconds <= 0) return 'now';
  // Days past two, as timeAgo does: `in 101h 3m` made the reader do the division.
  if (seconds >= 172_800) return `in ${Math.floor(seconds / 86_400)}d ${Math.floor((seconds % 86_400) / 3600)}h`;
  return `in ${fmtDurationS(seconds)}`;
}

/** The plan window nearest exhaustion, at its reported length. */
export interface NearestWindow {
  head: string;
  /** The account's masked id, where the head reports one. Null when the auth card names no
   *  account: its `login` is HOW the head signed in (browser, device, manual), not who, and a
   *  login method printed where an account belongs read as one ("claude-grok browser 5h"). */
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
 * label and never the window. Ties go to the window that resets sooner, the one the operator is
 * actually waiting on.
 */
export function nearestWindow(
  usage: UsagePayload | null,
  auth: AuthPayload | null,
  nowMs = Date.now(),
): NearestWindow | null {
  if (usage === null) return null;
  const candidates: Array<NearestWindow & { order: number }> = [];
  for (const entry of usage.heads) {
    const head = entry.usage;
    if (head === null) continue;
    const account = auth?.[entry.key]?.account_id_masked ?? null;
    if (head.warn.source !== 'none') {
      candidates.push({ head: entry.key, account, window: `${usage.window_hours}h`, pct: head.warn.pct, reset: head.warn.reset, order: 0 });
    }
    for (const plan of planWindows(head, nowMs)) {
      if (plan.stale) continue;
      candidates.push({
        head: entry.key,
        account,
        window: plan.window,
        pct: plan.pct,
        reset: resetsInText(plan.resetsAt, nowMs),
        order: plan.window === '5h' ? 0 : 1,
      });
    }
  }
  const best = candidates.reduce<(typeof candidates)[number] | null>(
    (held, next) => (held === null || next.pct > held.pct || (next.pct === held.pct && next.order < held.order) ? next : held),
    null,
  );
  if (best === null) return null;
  return { head: best.head, account: best.account, window: best.window, pct: best.pct, reset: best.reset };
}

/** Whether a head reports any window: rate-limit headers, a token limit, or a plan window. */
function reportsWindow(head: HeadUsage | null): boolean {
  if (head === null) return false;
  return head.warn.source !== 'none' || head.quota?.five_hour !== undefined || head.quota?.seven_day !== undefined;
}

/** How many heads report no window at all. Null until the route answers, so a caller can tell
 *  "not loaded yet" from "everyone reports one". */
export function headsReportingNone(usage: UsagePayload | null): number | null {
  if (usage === null) return null;
  return usage.heads.filter((entry) => !reportsWindow(entry.usage)).length;
}

/** One head's window state as a strip prints it. */
export interface HeadWindow {
  /** The used percentage, or null when the head reports no window. */
  pct: number | null;
  level: 'ok' | 'warn' | 'critical' | 'none';
  reset: string | null;
}

/** The daemon's own levels for a plan window: critical from 98% (UsageWarn.kt CRITICAL_PCT),
 *  warn from the configured percentage. */
const CRITICAL_PCT = 98;

export function planLevel(pct: number, warnPct: number): 'ok' | 'warn' | 'critical' {
  if (pct >= CRITICAL_PCT) return 'critical';
  return warnPct > 0 && pct >= warnPct ? 'warn' : 'ok';
}

/**
 * A head's window, from /api/usage: the fullest of its warn reading and its live plan windows.
 *
 * `pct: null` is the whole point: a head with no configured cap, no rate-limit headers and no live
 * plan window has no percentage, and printing 0 would read as "barely used" when the truth is
 * "nobody measured". The page prints the honest empty for that.
 */
export function headWindow(usage: UsagePayload | null, key: string, nowMs = Date.now()): HeadWindow {
  const entry = usage?.heads.find((row) => row.key === key)?.usage ?? null;
  let best: HeadWindow = { pct: null, level: 'none', reset: null };
  if (entry === null || usage === null) return best;
  if (entry.warn.source !== 'none') {
    best = { pct: entry.warn.pct, level: entry.warn.level, reset: entry.warn.reset };
  }
  for (const plan of planWindows(entry, nowMs)) {
    if (plan.stale || (best.pct !== null && plan.pct <= best.pct)) continue;
    best = { pct: plan.pct, level: planLevel(plan.pct, usage.warn_pct), reset: resetsInText(plan.resetsAt, nowMs) };
  }
  return best;
}
