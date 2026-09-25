// What the models page computes from GET /api/models and the topology, as shapes rather than
// sentences: one row per model in tier order, the column maxima its bars are drawn against, the
// tiers each head fills, the provider grouping, and the opened model's head windows.
import { slotTiers, windowSourceText } from '@entities/model';
import type { CatalogModel, HeadCatalog, ModelsPayload, PendingRoute, SlotTier } from '@entities/model';
import type { View } from '@features/views';
import { S, U } from './strings';

export const DEFAULT_VIEWS: readonly View[] = [
  { id: 'by-head', name: S.byHead, layout: 'bay', filter: {}, sort: null, group: null, fields: [] },
  { id: 'by-provider', name: S.byProvider, layout: 'bay', filter: {}, sort: null, group: 'provider', fields: [] },
];

/** One model as a table row, keyed by its head as well as its id: several heads can serve one
 *  model id, and each opens its own head's windows. */
export interface ModelEntry {
  key: string;
  head: HeadCatalog;
  model: CatalogModel;
}

/** A head's models in the order the client meets them: the tiers first, in the daemon's tier order,
 *  then every model no tier selects. */
export function entriesOf(head: HeadCatalog): ModelEntry[] {
  const slotted = slotTiers(head).flatMap((tier) => (tier.model === null ? [] : [tier.model]));
  const unslotted = head.models.filter((model) => model.slot === null);
  return [...slotted, ...unslotted].map((model) => ({ key: `${head.head}:${model.id}`, head, model }));
}

/** The largest of each drawn column across every head, so a bar on one head compares with the same
 *  column on another. A model with no rates adds nothing: its cells print the absence. */
export interface ColumnMax {
  window: number;
  input: number;
  output: number;
}

export function columnMax(entries: readonly ModelEntry[]): ColumnMax {
  const max = { window: 0, input: 0, output: 0 };
  for (const { model } of entries) {
    max.window = Math.max(max.window, model.context_window ?? 0);
    max.input = Math.max(max.input, model.rates?.input ?? 0);
    max.output = Math.max(max.output, model.rates?.output ?? 0);
  }
  return max;
}

/** How many of the heads' Claude Code tiers a model fills, of every tier every head could fill. */
export function tiersFilled(heads: readonly HeadCatalog[]): { filled: number; total: number } {
  const tiers = heads.flatMap((head) => slotTiers(head));
  return { filled: tiers.filter((tier) => tier.model !== null).length, total: tiers.length };
}

export function tierText(tier: SlotTier['slot']): string {
  return S.tierName[tier];
}

/** Where a window came from, in the page's words; a label the daemon adds later prints in the
 *  entity's own spelling rather than disappearing. */
export function windowFromText(source: string): string {
  return (S.windowSource as Readonly<Record<string, string>>)[source] ?? windowSourceText(source);
}

/** A price per million tokens in cents at least (`$0.40`, `$10.00`), and every further digit the
 *  daemon sent: `$0.014` stays exact rather than rounding a cheap model to `$0.01`. */
export function rateText(usd: number): string {
  const places = String(usd).split('.')[1]?.length ?? 0;
  return `${U.usd}${usd.toFixed(Math.max(2, places))}`;
}

/** A model with no rate card: the daemon omits the key rather than sending null (M1-41). */
export function hasRates(model: CatalogModel): model is CatalogModel & { rates: NonNullable<CatalogModel['rates']> } {
  return model.rates !== undefined && model.rates !== null;
}

export interface ProviderGroup {
  provider: string;
  heads: HeadCatalog[];
}

/** Heads grouped by the provider the payload reports, in stable order, with the un-reported ones
 *  collected under one honest label instead of being dropped or guessed at: the head KEY is not a
 *  provider, and printing it under a provider heading would be the page inventing a fact. */
export function byProvider(heads: readonly HeadCatalog[]): ProviderGroup[] {
  const groups = new Map<string, HeadCatalog[]>();
  for (const head of heads) {
    const key = head.provider === '' ? S.providerUnknown : head.provider;
    groups.set(key, [...(groups.get(key) ?? []), head]);
  }
  return [...groups.entries()]
    .map(([provider, entries]) => ({ provider, heads: entries }))
    .sort((left, right) => left.provider.localeCompare(right.provider));
}

/** An opened model: the head it was opened on and its id. The head is part of the key because
 *  several heads can serve one model id, and the detail reads the OPENED head's windows. */
export interface OpenedModel {
  head: string;
  id: string;
}

/** The opened model, with the head it was opened on. */
export function findModel(
  payload: ModelsPayload | PendingRoute,
  opened: OpenedModel | null,
): { model: CatalogModel; head: HeadCatalog } | null {
  if (opened === null || 'pending' in payload) return null;
  const head = payload.heads.find((entry) => entry.head === opened.head);
  const model = head?.models.find((entry) => entry.id === opened.id);
  return head === undefined || model === undefined ? null : { model, head };
}

/** A head's windows as its topology declares them (FEATURES 4.8 "Windows"): the forced head-wide
 *  window, its provider's default, and how many extra windows and prefix rules that provider
 *  carries. Read from GET /api/topology because GET /api/models reports each MODEL's window and its
 *  source, never the head's own (ModelsRoute.row); a number the topology does not set is null, and
 *  the page prints the absence rather than the daemon's internal zero. */
export interface HeadWindows {
  headWindow: number | null;
  defaultWindow: number | null;
  extraWindows: number;
  windowRules: number;
}

function asTable(value: unknown): Record<string, unknown> | null {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : null;
}

/** The head's windows, or null while the topology has not been read or does not name the head. */
export function headWindows(topology: Record<string, unknown> | null, head: HeadCatalog): HeadWindows | null {
  const headTable = asTable(asTable(topology?.heads)?.[head.head]);
  const providerTable = asTable(asTable(topology?.providers)?.[head.provider]);
  if (headTable === null || providerTable === null) return null;
  const tokens = (value: unknown): number | null => (typeof value === 'number' && value > 0 ? value : null);
  const count = (value: unknown): number => (Array.isArray(value) ? value.length : 0);
  return {
    headWindow: tokens(headTable.context_window),
    defaultWindow: tokens(providerTable.default_context_window),
    extraWindows: count(providerTable.extra_windows),
    windowRules: count(providerTable.window_rules),
  };
}
