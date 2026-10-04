// The arithmetic and words of the Usage page: the window choices the daemon's retention allows, one row per plan
// against its limit, and the totals. Pure over /api/economics, /api/usage and /api/heads; the page only draws what this returns.
import { burn, costOf, hitRate, hourly, sum, within } from './economics';
import type { Totals } from './economics';
import { ABSENT, fmtShare, fmtTokens, fmtUsd } from './format';
import { nearestWindow, planWindows, rateLimitAge } from './usage';
import type { PlanWindow } from './usage';
import { U, spanWords } from './words-usage';
import type { ModelColour } from './model';
import type { HeadEconomics } from '../types/economics';
import type { HeadStatus, UsagePayload } from '../types/core';
import { localZonedInstantText, quotaRefusedUntil } from './heads';

/** The windows the daemon's own retention can answer: a day always, a week and a month once it keeps them. */
export function windowChoices(retentionHours: number): readonly (readonly [string, string])[] {
  const choices: (readonly [string, string])[] = [['24', '24 hours']];
  if (retentionHours >= 168) choices.push(['168', '7 days']);
  if (retentionHours >= 720) choices.push(['720', '30 days']);
  return choices;
}

/** How long the fullest window lasts at the recent pace, said the way a person would. Null when nothing can be projected
 *  (no ceiling, or no burn) rather than a guess. */
export function paceText(hoursLeft: number | null): string | null {
  if (hoursLeft === null || !Number.isFinite(hoursLeft)) return null;
  if (hoursLeft < 1) return U.paceLess;
  if (hoursLeft < 48) return U.paceHours(Math.round(hoursLeft));
  return U.paceDays(Math.round(hoursLeft / 24));
}

export interface PlanUsage {
  key: string;
  label: string;
  colour: ModelColour;
  /** The fullest live window's used percentage, null when the plan reports none (never 0). */
  pct: number | null;
  /** Only a refusal the head still holds, never the percentage of a reading. */
  full: boolean;
  reset: string | null;
  limitWindow: string | null;
  /** The age of any retained rate-limit headers, not a claim about a current limit. */
  reading: string | null;
  /** Each quota window's retained observation, independently of page-open and probe times. */
  observations?: readonly PlanWindow[];
  pace: string | null;
  /** Null while request counts are pending or unavailable. */
  turns: number | null;
  requestState?: 'loading' | 'unavailable' | 'ready';
  requestReason?: string;
  inTokens: number | null;
  partial?: boolean;
  costPartial?: boolean;
  models?: readonly string[];
  subscription?: string | undefined;
  cache: number | null;
  /** Dollars of the priced turns, null when none was priced. */
  cost: number | null;
  spark: number[];
  /** Dollars of the last 24 hours, the figure a daily budget is measured against. */
  spentToday: number | null;
}

export function planUsage(head: HeadEconomics, label: string, colour: ModelColour, usage: UsagePayload | null, hours: number, now: number, status?: HeadStatus): PlanUsage {
  const totals = sum(within(head.buckets, hours, now));
  const window = nearestWindow(usage === null ? null : { ...usage, heads: usage.heads.filter(row => row.key === head.key) }, null, now);
  const day = sum(within(head.buckets, 24, now));
  const age = rateLimitAge(usage?.heads.find((row) => row.key === head.key)?.usage ?? null, now);
  return {
    key: head.key,
    label,
    colour,
    pct: window?.pct ?? null,
    full: status !== undefined && quotaRefusedUntil(status, now) !== null,
    reset: window?.resetsAt == null ? null : localZonedInstantText(window.resetsAt),
    limitWindow: window?.window ?? null,
    reading: age === null ? null : U.rateLimitReading(age),
    observations: planWindows(usage?.heads.find(row => row.key === head.key)?.usage ?? null, now),
    pace: window === null ? null : paceText(burn(head, now).hoursToExhaustion),
    turns: totals.turns,
    inTokens: totals.inTokens,
    cache: hitRate(totals),
    cost: totals.turns === 0 ? null : costOf(totals),
    spark: hourly(head.buckets, 24, now),
    spentToday: costOf(day),
  };
}

/** Fullest window first, plans that report none after, then by tokens read. */
export function orderPlans(plans: readonly PlanUsage[]): PlanUsage[] {
  return [...plans].sort((a, b) => (b.pct ?? -1) - (a.pct ?? -1) || (b.inTokens ?? -1) - (a.inTokens ?? -1) || a.label.localeCompare(b.label));
}

/** Plans that ran a turn in the window, and the ones that did not: an idle plan is one sentence, never a row of zeros. */
export function splitIdle(plans: readonly PlanUsage[]): { active: PlanUsage[]; idle: PlanUsage[] } {
  const active = plans.filter((plan) => plan.turns === null || plan.turns > 0 || plan.pct !== null || plan.full || plan.reading !== null);
  return { active, idle: plans.filter((plan) => plan.turns === 0 && !active.includes(plan)) };
}

export function totalsOf(heads: readonly HeadEconomics[], hours: number, now: number): Totals {
  return sum(heads.flatMap((head) => within(head.buckets, hours, now)));
}

export const tokensText = (n: number | null): string => n === null ? ABSENT : fmtTokens(n);

/** The sentence under the title: what it cost, and the plan closest to its limit when one is near. */
export function usageLede(totals: Totals, plans: readonly PlanUsage[], hours: number, count: number | null = totals.turns, cost: number | null = totals.turns === 0 ? null : costOf(totals), incompleteCost = false, incompleteCount = false): string {
  const spent = count === null
    ? U.countNotReported
    : count === 0 && !incompleteCount ? `No requests in ${spanWords(hours)}.`
    : cost === null ? `${incompleteCount ? 'At least ' : ''}${count.toLocaleString('en-US')} requests in ${spanWords(hours)}. API cost is not reported.` : `${incompleteCost ? 'At least' : 'About'} ${fmtUsd(cost)} of recorded API cost in ${spanWords(hours)}.`;
  const refused = plans.find((plan) => plan.full);
  if (refused !== undefined) return `${spent} ${refused.label} is out of quota.`;
  const nearest = plans.find((plan) => plan.pct !== null);
  if (nearest === undefined || nearest.pct === null || nearest.pct < 80) return spent;
  return `${spent} ${nearest.label} is at ${U.ofLimit(Math.round(nearest.pct), nearest.limitWindow)}.`;
}

export const cacheLine = (ratio: number | null): string => (ratio === null ? U.noCache : U.cached(fmtShare(ratio)));
export const costLine = (usd: number | null): string => (usd === null ? ABSENT : fmtUsd(usd));
