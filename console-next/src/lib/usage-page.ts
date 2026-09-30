// The arithmetic and words of the Usage page: the window choices the daemon's retention allows, one row per plan
// against its limit, and the totals. Pure over /api/economics and /api/usage; the page only draws what this returns.
import { burn, costOf, hitRate, hourly, sum, within } from './economics';
import type { Totals } from './economics';
import { ABSENT, fmtShare, fmtTokens, fmtUsd } from './format';
import { headWindow } from './usage';
import type { HeadWindow } from './usage';
import { U, spanWords } from './words-usage';
import type { ModelColour } from './model';
import type { HeadEconomics } from '../types/economics';
import type { UsagePayload } from '../types/core';

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
  full: boolean;
  reset: string | null;
  pace: string | null;
  turns: number;
  inTokens: number;
  cache: number | null;
  /** Dollars of the priced turns, null when none was priced. */
  cost: number | null;
  spark: number[];
  /** Dollars of the last 24 hours, the figure a daily budget is measured against. */
  spentToday: number | null;
}

export function planUsage(head: HeadEconomics, label: string, colour: ModelColour, usage: UsagePayload | null, hours: number, now: number): PlanUsage {
  const totals = sum(within(head.buckets, hours, now));
  const window: HeadWindow = headWindow(usage, head.key, now);
  const day = sum(within(head.buckets, 24, now));
  return {
    key: head.key,
    label,
    colour,
    pct: window.pct,
    full: window.pct !== null && window.pct >= 100,
    reset: window.reset,
    pace: window.pct === null ? null : paceText(burn(head, now).hoursToExhaustion),
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
  return [...plans].sort((a, b) => (b.pct ?? -1) - (a.pct ?? -1) || b.inTokens - a.inTokens || a.label.localeCompare(b.label));
}

/** Plans that ran a turn in the window, and the ones that did not: an idle plan is one sentence, never a row of zeros. */
export function splitIdle(plans: readonly PlanUsage[]): { active: PlanUsage[]; idle: PlanUsage[] } {
  return { active: plans.filter((plan) => plan.turns > 0 || plan.pct !== null), idle: plans.filter((plan) => plan.turns === 0 && plan.pct === null) };
}

export function totalsOf(heads: readonly HeadEconomics[], hours: number, now: number): Totals {
  return sum(heads.flatMap((head) => within(head.buckets, hours, now)));
}

export const tokensText = (n: number): string => fmtTokens(n);

/** The sentence under the title: what it cost, and the plan closest to its limit when one is near. */
export function usageLede(totals: Totals, plans: readonly PlanUsage[], hours: number): string {
  const cost = costOf(totals);
  const spent = totals.turns === 0
    ? `No turns in ${spanWords(hours)}.`
    : cost === null ? `${totals.turns.toLocaleString('en-US')} turns in ${spanWords(hours)}, none priced.` : `About ${fmtUsd(cost)} of API cost in ${spanWords(hours)}.`;
  const nearest = plans.find((plan) => plan.pct !== null);
  if (nearest === undefined || nearest.pct === null || nearest.pct < 80) return spent;
  return nearest.full ? `${spent} ${nearest.label} is out of quota.` : `${spent} ${nearest.label} is at ${Math.round(nearest.pct)}% of its limit.`;
}

export const cacheLine = (ratio: number | null): string => (ratio === null ? U.noCache : U.cached(fmtShare(ratio)));
export const costLine = (usd: number | null): string => (usd === null ? ABSENT : fmtUsd(usd));
