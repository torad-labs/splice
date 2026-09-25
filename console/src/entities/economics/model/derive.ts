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
  /** The cache-WRITE half of inTokens, disjoint from cachedTokens. Summed on its own because it
   * bills at the vendor's cache_write rate and not at the input rate. */
  cacheWriteTokens: number;
  outTokens: number;
  reqBytes: number;
  upstreamBytes: number;
  toolsEager: number;
  toolsDeferred: number;
  deferralTurns: number;
  rateLimited: number;
  /** The dollars of the priced turns, as the daemon priced each at its own model's card. */
  costUsd: number;
  /** Turns whose dollars are not in costUsd: those whose model had no card, and every turn of an
   *  hour recorded before the daemon priced turns. */
  unpricedTurns: number;
}

const ZERO: Totals = {
  turns: 0, inTokens: 0, cachedTokens: 0, cacheWriteTokens: 0, outTokens: 0, reqBytes: 0,
  upstreamBytes: 0, toolsEager: 0, toolsDeferred: 0, deferralTurns: 0, rateLimited: 0,
  costUsd: 0, unpricedTurns: 0,
};

export function sum(buckets: readonly EconomicsBucket[]): Totals {
  return buckets.reduce<Totals>((a, b) => ({
    turns: a.turns + b.turns,
    inTokens: a.inTokens + b.in_tokens,
    cachedTokens: a.cachedTokens + b.cached_tokens,
    cacheWriteTokens: a.cacheWriteTokens + b.cache_write_tokens,
    outTokens: a.outTokens + b.out_tokens,
    reqBytes: a.reqBytes + b.req_bytes,
    upstreamBytes: a.upstreamBytes + b.upstream_req_bytes,
    toolsEager: a.toolsEager + b.tools_eager,
    toolsDeferred: a.toolsDeferred + b.tools_deferred,
    deferralTurns: a.deferralTurns + b.deferral_turns,
    rateLimited: a.rateLimited + b.rate_limited,
    costUsd: a.costUsd + (b.cost_usd ?? 0),
    // An hour not priced then, or served by a daemon that prices none, is every one of its turns.
    unpricedTurns: a.unpricedTurns + (b.cost_usd == null ? b.turns : b.unpriced_turns ?? 0),
  }), ZERO);
}

/** Buckets within the last [hours], relative to [now]. */
export function within(buckets: readonly EconomicsBucket[], hours: number, now: number): EconomicsBucket[] {
  const cutoff = now - hours * HOUR_MS;
  return buckets.filter((b) => b.hour >= cutoff);
}

/** Cache hit rate. Diagnostic ONLY — deliberately NOT an input to the burn gauge, because a
 * 90%-cached prompt bills in full. Kept on screen precisely so a high hit rate can stop being
 * mistaken for safety. */
export function hitRate(t: Totals): number | null {
  return t.inTokens > 0 ? t.cachedTokens / t.inTokens : null;
}

/** Share of the metered input that was WRITTEN into the cache, computed exactly as [hitRate] is
 * computed for the read half: the bucket over total input, null when there is no input to divide.
 *
 * It sits beside the hit rate rather than replacing it because the two answer different questions
 * about the same total: a high hit rate is a warm prefix being re-read, a high write share is that
 * prefix being re-BUILT, and a vendor charges more per token for the second than for either the
 * first or a plain miss. On this page both are diagnostics, never inputs to the burn gauge — the
 * plan meters total input and every one of these tokens bills in full. */
export function writeRate(t: Totals): number | null {
  return t.inTokens > 0 ? t.cacheWriteTokens / t.inTokens : null;
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

/**
 * The dollars of [totals], or null when it has turns and none of them was priced.
 *
 * The daemon prices each turn at record time at the card of the model that turn ran (V4-221), so
 * the console multiplies nothing: an hour mixes models (a haiku subagent, a compaction model), and
 * pricing its token sums at one card mispriced every turn on another. A window with some unpriced
 * turns reads its priced dollars, and the page counts the rest beside them. No turns at all is
 * $0, a reading.
 */
export function costOf(totals: Totals): number | null {
  return totals.turns > 0 && totals.unpricedTurns >= totals.turns ? null : totals.costUsd;
}

/** Per-hour input tokens over the last [hours], oldest first, with missing hours as 0 so the
 * sparkline shows idle gaps as gaps instead of silently closing them up. */
export function hourly(buckets: EconomicsBucket[], hours: number, now: number): number[] {
  const end = Math.floor(now / HOUR_MS) * HOUR_MS;
  const byHour = new Map(buckets.map((b) => [b.hour, b.in_tokens]));
  return Array.from({ length: hours }, (_, i) => byHour.get(end - (hours - 1 - i) * HOUR_MS) ?? 0);
}
