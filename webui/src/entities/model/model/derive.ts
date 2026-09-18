// Pure derivations over a head's catalog. No rendering, no store.
import { MODEL_SLOTS } from './types';
import type { CatalogModel, HeadCatalog, SlotTier } from './types';

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
