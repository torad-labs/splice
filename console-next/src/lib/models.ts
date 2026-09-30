// Pure derivations over a head's catalog. No rendering, no store.
import { MODEL_SLOTS } from '../types/models';
import type { CatalogModel, HeadCatalog, ModelRates, SlotTier } from '../types/models';
import type { TopologyState } from '../types/topology';
import { fmtUsd } from './format';
import { M } from './words-models';

/**
 * The head's four tiers, in the daemon's slot order, each with the model that fills it or null.
 *
 * FEATURES.md 4.8 asks for "the tiers Claude Code will and will not get on this head, and what
 * degrades when a tier is undeclared" - so the undeclared tier is a ROW with a null model, not an
 * omission from the list. A page that rendered only the declared slots could not tell "this head
 * never declares haiku" apart from "the catalog did not load".
 *
 * A head that declares one id under two slots keeps both rows: the daemon requires slot names to be
 * distinct but says nothing about ids, and two slots over one model is a real configuration.
 */
export function slotTiers(head: HeadCatalog): SlotTier[] {
  const bySlot = new Map<string, CatalogModel>();
  for (const model of head.models) {
    if (model.slot !== null) bySlot.set(model.slot, model);
  }
  return MODEL_SLOTS.map((slot) => ({ slot, model: bySlot.get(slot) ?? null }));
}

/** Where a row's context window came from, in words. The daemon sends a LABEL it never expects the
 *  console to parse (ModelsRoute.kt WINDOW_FROM_*), and the page printed it verbatim, so the
 *  operator read `extra-window` and `default`. A value this map does not know prints as the daemon
 *  sent it, dashes spaced, so a new daemon label still reads. */
const WINDOW_SOURCE_WORDS: Record<string, string> = {
  model: 'model catalog',
  head: 'head setting',
  rule: 'prefix rule',
  'extra-window': 'extra window',
  default: 'provider default',
  unknown: 'unknown',
};

export function windowSourceText(source: string): string {
  return WINDOW_SOURCE_WORDS[source] ?? source.replaceAll('-', ' ');
}

/** A model's price as a person reads it, or the plain fact that none is declared: an absent rate is not a rate of zero. */
export function rateText(rates: ModelRates | null | undefined): string {
  if (rates === null || rates === undefined) return M.noRate;
  return M.rate(fmtUsd(rates.input), fmtUsd(rates.output));
}

/** What splice.toml declares for a plan's windows, apart from the catalogue's own: the plan's forced window, its provider's default,
 *  and how many extra windows and prefix rules the provider carries. Null while the topology is not here or does not name the plan. */
export interface HeadWindows {
  plan: number | null;
  provider: number | null;
  extra: number;
  rules: number;
}

const tableOf = (value: unknown): Record<string, unknown> | null =>
  typeof value === 'object' && value !== null && !Array.isArray(value) ? (value as Record<string, unknown>) : null;
const tokensOf = (value: unknown): number | null => (typeof value === 'number' && value > 0 ? value : null);
const countOf = (value: unknown): number => (Array.isArray(value) ? value.length : 0);

export function headWindows(topology: TopologyState | undefined, head: string, provider: string): HeadWindows | null {
  if (topology === undefined || 'pending' in topology) return null;
  const headTable = tableOf(tableOf(topology.topology.heads)?.[head]);
  const providerTable = tableOf(tableOf(topology.topology.providers)?.[provider]);
  if (headTable === null || providerTable === null) return null;
  return {
    plan: tokensOf(headTable.context_window),
    provider: tokensOf(providerTable.default_context_window),
    extra: countOf(providerTable.extra_windows),
    rules: countOf(providerTable.window_rules),
  };
}
