// The payload contract of the model entity: GET /api/models, served by V4-127 (ModelsRoute.kt) and
// typed as that route writes it — FEATURES.md 6 names each row's columns (id, label, description,
// slot, context window and its source, rates, pinned) plus the head's provider, and nothing at the
// head level beyond the pinned model. The head's own windows (its forced window, the provider
// default, extra windows and prefix rules) are TOPOLOGY, read from GET /api/topology by the page
// that shows them. Until 2026-09-22 this file declared them here, on a route that never sent them,
// and the fleet's model column and the usage rates join both keyed on a `key` the daemon spells
// `head` (V4-140's defect class: a declaration standing in for a measurement).

import type { PendingRoute } from '@shared/api';

export type { PendingRoute };

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

export interface CatalogModel {
  id: string;
  label: string;
  description: string;
  /** The Claude Code tier this row fills, or null for a row the operator did not slot. */
  slot: ModelSlot | null;
  /** Null only on an unresolved row whose declared id no catalog entry or extra window names. */
  context_window: number | null;
  /** Where that number came from, in the daemon's own words (an exact row, an extra window, a
   *  prefix rule, the provider default). Free text on purpose: naming it lets the page print the
   *  provenance without inventing an enum the daemon may resolve differently. */
  context_window_source: string;
  /** OPTIONAL, not merely nullable, and the difference is the whole of M1-41. ModelsRoute.kt:130
   *  is `entry.rates?.let { put("rates", ratesJson(it)) }` — the daemon OMITS the key when a model
   *  declares no rates, it does not send null. `rates: ModelRates | null` claimed the key is ALWAYS
   *  PRESENT and may hold null, so `rates === null` was false for a missing key and four call sites
   *  took the has-rates branch on `undefined`. A type that cannot express doubt about the wire
   *  cannot be checked by anyone (M1-37's second column). */
  rates?: ModelRates | null;
  /** True for the head's pinned model, the one every launch plants as the client's window. */
  pinned: boolean;
  /** False for a DECLARED slot that resolved to no catalog model: its own row, never dropped, because
   *  "the tiers Claude Code will not get on this head" is what the page exists to show. */
  resolved: boolean;
  /** Why an unresolved row did not resolve, in the daemon's words; absent on a resolved row. */
  reason?: string;
}

export interface HeadCatalog {
  /** The head's topology key. */
  head: string;
  /** The registry's provider key for the head (FEATURES.md 6, decided with the daemon lead for
   *  V4-127: the models page groups by provider). */
  provider: string;
  /** The head's pinned model (ANTHROPIC_MODEL at launch), or "" when the head pins none. */
  pinned_model: string;
  models: CatalogModel[];
}

export interface ModelsPayload {
  heads: HeadCatalog[];
}

// PendingRoute moved to @shared/api (CONTRACTS.md 8): the shape and its `pendingOf` mapping are now
// one definition every slice imports, instead of a copy per data row.

/** One tier of a head: the model that fills it, or null when the head declares none. A null row is
 *  what FEATURES.md 4.8 asks the page to show ("which tiers Claude Code will and will not get"), so
 *  the empty tier is a value in this list, never a missing entry. */
export interface SlotTier {
  slot: ModelSlot;
  model: CatalogModel | null;
}
