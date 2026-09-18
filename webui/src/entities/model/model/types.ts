// The payload contract of the model entity: GET /api/models, PENDING V4-127. Typed from
// FEATURES.md 6 ("catalog per head with slots, windows, sources, rates, pinned") and 4.8, and from
// the daemon values that will answer it:
//   splice/core/model/ModelCatalog.kt  ModelEntry, ExtraWindow, WindowRule, ModelCatalog
//   splice/core/model/TokenCost.kt     ModelRates
//   splice/core/topology/Topology.kt   HeadModel (id + slot), HeadConfig.pinnedModel/contextWindow
// The route does not exist, so this is the contract the console builds against; the honest empty a
// page renders is the pending state, never a mocked catalog.

/** The four Claude Code tiers a head can declare, in the daemon's own vocabulary
 *  (Topology.kt headModelSlots). */
export const MODEL_SLOTS = ['opus', 'sonnet', 'haiku', 'fable'] as const;

export type ModelSlot = (typeof MODEL_SLOTS)[number];

/** USD per million tokens. Absent on a model whose provider declares none, which reads as "no
 *  dollar figure", never as a rate of zero (TokenCost.ratesFor returns null, not a zero card). */
export interface ModelRates {
  input: number;
  cache_read: number;
  output: number;
  cache_write?: number;
}

/** A window-only id the picker does not list (ModelCatalog.extraWindows). */
export interface ExtraWindow {
  id: string;
  context_window: number;
}

/** An ordered prefix rule for ids the catalog does not name exactly, first match wins. */
export interface WindowRule {
  prefix: string;
  context_window: number;
}

export interface CatalogModel {
  id: string;
  label: string;
  description: string;
  /** The Claude Code tier this row fills, or null for a row the operator did not slot. */
  slot: ModelSlot | null;
  context_window: number;
  /** Where that number came from, in the daemon's own words (an exact row, an extra window, a
   *  prefix rule, the provider default). Free text on purpose: naming it lets the page print the
   *  provenance without inventing an enum the daemon may resolve differently. */
  context_window_source: string;
  rates: ModelRates | null;
  /** True for the head's pinned model, the one every launch plants as the client's window. */
  pinned: boolean;
}

export interface HeadCatalog {
  key: string;
  label: string;
  discovery_prefix: string;
  /** The head's pinned model (ANTHROPIC_MODEL at launch), or "" when the head pins none. */
  pinned_model: string;
  /** The forced head-wide window from [heads.<key>].context_window, or null when the head
   *  declares none and window resolution falls through to prefix rules and the provider default. */
  context_window: number | null;
  default_context_window: number;
  models: CatalogModel[];
  extra_windows: ExtraWindow[];
  window_rules: WindowRule[];
}

export interface ModelsPayload {
  heads: HeadCatalog[];
}

/**
 * A route the daemon has not built yet; the store resolves to this instead of a catalog, and the
 * page prints the v0.4.0 item that will serve it (CONTRACTS.md 8). Declared here rather than shared
 * because a slice may not import a sibling slice and `src/shared/**` is not this row's fence; the
 * same shape is declared in entities/perf, and the finish row can hoist both into one place.
 */
export interface PendingRoute {
  pending: string;
}

/** One tier of a head: the model that fills it, or null when the head declares none. A null row is
 *  what FEATURES.md 4.8 asks the page to show ("which tiers Claude Code will and will not get"), so
 *  the empty tier is a value in this list, never a missing entry. */
export interface SlotTier {
  slot: ModelSlot;
  model: CatalogModel | null;
}
