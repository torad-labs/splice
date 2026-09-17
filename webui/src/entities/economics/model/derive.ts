// The arithmetic behind the burn page. Pure functions over the daemon's hourly SUMS, kept out of
// the view so each one is directly testable — these numbers decide whether an operator keeps
// working or stops, so "it looked right on screen" is not an acceptable standard of proof.
//
// THE ONE THING THIS FILE EXISTS TO GET RIGHT: the plan meters TOTAL input, cache hits included.
// Measured across two exhaustion windows (2026-08-01): 1.716B total input read 97% while 1.759B
// read 100%, yet the same windows' UNCACHED totals ordered the wrong way round (571M at 97%,
// 390M at 100%). So `spent` is in_tokens — never in_tokens - cached_tokens. A burn gauge built on
// uncached tokens would have read comfortable at the exact moment the quota ran out.
import type { EconomicsBucket, HeadEconomics } from '@shared/api';

const HOUR_MS = 3_600_000;
const WEEK_HOURS = 168;

export interface Totals {
  turns: number;
  inTokens: number;
  cachedTokens: number;
  outTokens: number;
  reqBytes: number;
  upstreamBytes: number;
  toolsEager: number;
  toolsDeferred: number;
  deferralTurns: number;
  rateLimited: number;
}

const ZERO: Totals = {
  turns: 0, inTokens: 0, cachedTokens: 0, outTokens: 0, reqBytes: 0,
  upstreamBytes: 0, toolsEager: 0, toolsDeferred: 0, deferralTurns: 0, rateLimited: 0,
};

export function sum(buckets: EconomicsBucket[]): Totals {
  return buckets.reduce<Totals>((a, b) => ({
    turns: a.turns + b.turns,
    inTokens: a.inTokens + b.in_tokens,
    cachedTokens: a.cachedTokens + b.cached_tokens,
    outTokens: a.outTokens + b.out_tokens,
    reqBytes: a.reqBytes + b.req_bytes,
    upstreamBytes: a.upstreamBytes + b.upstream_req_bytes,
    toolsEager: a.toolsEager + b.tools_eager,
    toolsDeferred: a.toolsDeferred + b.tools_deferred,
    deferralTurns: a.deferralTurns + b.deferral_turns,
    rateLimited: a.rateLimited + b.rate_limited,
  }), ZERO);
}

/** Buckets within the last [hours], relative to [now]. */
export function within(buckets: EconomicsBucket[], hours: number, now: number): EconomicsBucket[] {
  const cutoff = now - hours * HOUR_MS;
  return buckets.filter((b) => b.hour >= cutoff);
}

/** Cache hit rate. Diagnostic ONLY — deliberately NOT an input to the burn gauge, because a
 * 90%-cached prompt bills in full. Kept on screen precisely so a high hit rate can stop being
 * mistaken for safety. */
export function hitRate(t: Totals): number | null {
  return t.inTokens > 0 ? t.cachedTokens / t.inTokens : null;
}

/** Input tokens burned per output token produced — the read-amplification of an agentic tool
 * loop, and the reason turn COUNT dominates cost. */
export function amplification(t: Totals): number | null {
  return t.outTokens > 0 ? t.inTokens / t.outTokens : null;
}

export function perTurn(t: Totals): number | null {
  return t.turns > 0 ? t.inTokens / t.turns : null;
}

/** Mean eager/total tool surface, or null on a dialect that cannot defer (deferralTurns === 0).
 * Null is the honest answer there and must not collapse to 0 — "cannot defer" is a finding. */
export function toolSurface(t: Totals): { eager: number; total: number } | null {
  if (t.deferralTurns === 0) return null;
  return {
    eager: t.toolsEager / t.deferralTurns,
    total: (t.toolsEager + t.toolsDeferred) / t.deferralTurns,
  };
}

/** Mean bytes a turn gains (+) or loses (-) between the client request and the upstream one.
 * On a deferring head this is tool savings NETTED against injected reasoning envelopes, so a
 * positive number means the envelopes now outweigh everything deferral removed. */
export function wireDelta(t: Totals): number | null {
  return t.turns > 0 ? (t.upstreamBytes - t.reqBytes) / t.turns : null;
}

export interface Burn {
  /** Total input tokens over the window — what the plan actually meters. */
  spent: number;
  /** Provider-reported ceiling, or null when it sends none. */
  ceiling: number | null;
  /** spent/ceiling, or null without a ceiling. Never fabricated. */
  fraction: number | null;
  /** Mean tokens/hour over the trailing sample. */
  ratePerHour: number;
  /** Hours until `ceiling` is reached at `ratePerHour`; null without a ceiling, Infinity if idle. */
  hoursToExhaustion: number | null;
  /** Wall-clock ms of exhaustion, or null when not projectable. */
  exhaustsAt: number | null;
}

/**
 * Project exhaustion from the trailing [rateHours] of burn.
 *
 * The rate deliberately comes from a SHORT trailing window, not the week average: the failure
 * being instrumented was a workload that doubled turn count and drained a week in twelve hours.
 * A week-averaged rate would have lagged that ramp by days and projected safety throughout.
 * Empty trailing hours count as zero burn rather than being skipped, so going idle correctly
 * relaxes the projection instead of freezing it at the last busy hour.
 */
export function burn(
  head: HeadEconomics,
  now: number,
  rateHours = 6,
  weekHours = WEEK_HOURS,
): Burn {
  const week = sum(within(head.buckets, weekHours, now));
  const recent = sum(within(head.buckets, rateHours, now));
  const ratePerHour = recent.inTokens / rateHours;
  const ceiling = head.ceiling_tokens;
  const remaining = ceiling === null ? null : Math.max(0, ceiling - week.inTokens);
  const hours = remaining === null ? null : ratePerHour > 0 ? remaining / ratePerHour : Infinity;
  return {
    spent: week.inTokens,
    ceiling,
    fraction: ceiling !== null && ceiling > 0 ? week.inTokens / ceiling : null,
    ratePerHour,
    hoursToExhaustion: hours,
    exhaustsAt: hours !== null && Number.isFinite(hours) ? now + hours * HOUR_MS : null,
  };
}

/** Per-hour input tokens over the last [hours], oldest first, with missing hours as 0 so the
 * sparkline shows idle gaps as gaps instead of silently closing them up. */
export function hourly(buckets: EconomicsBucket[], hours: number, now: number): number[] {
  const end = Math.floor(now / HOUR_MS) * HOUR_MS;
  const byHour = new Map(buckets.map((b) => [b.hour, b.in_tokens]));
  return Array.from({ length: hours }, (_, i) => byHour.get(end - (hours - 1 - i) * HOUR_MS) ?? 0);
}
