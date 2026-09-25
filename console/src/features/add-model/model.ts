// The add-model form's rules, apart from the panel so a test reads them without a browser.
import type { AddModelOffer } from '@entities/add';

/** The head the form adds to: the one picked, else the file's first OpenRouter head. */
export function offerOf(offers: readonly AddModelOffer[], head: string | null): AddModelOffer | null {
  return offers.find((offer) => offer.head === head) ?? offers[0] ?? null;
}

/** The picked ids still on offer for [offer], in the catalogue's order: a pick the offer no longer
 *  carries is never sent. */
export function pickedIds(offer: AddModelOffer, picked: ReadonlySet<string>): string[] {
  return offer.models.filter((model) => picked.has(model.id)).map((model) => model.id);
}

/** [picked] with [id] in or out. */
export function toggled(picked: ReadonlySet<string>, id: string, on: boolean): ReadonlySet<string> {
  const next = new Set(picked);
  if (on) next.add(id);
  else next.delete(id);
  return next;
}
