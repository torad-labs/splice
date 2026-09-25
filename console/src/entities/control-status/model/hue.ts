// A HEAD'S COLOUR COMES FROM ITS FAMILY (operator ruling 4, item 6, 2026-09-25). Registry order
// coloured the operator's ten heads by where each sat in splice.toml, so a DeepSeek head could wear
// Anthropic's orange and three local bonsai heads wore three unrelated colours. Now the hue is the
// provider's family, one table below, and heads of one family step in lightness within that hue in
// registry order: the first at the base tone, the next lighter, then darker. The name always
// travels beside the mark, so siblings are never told apart by tone alone.
import type { RegistryEntry } from '@shared/api';

/** Each family's colour slot: the hue the operator ruled for it (tokens.css --head-N). */
const FAMILY_SLOT: ReadonlyMap<string, number> = new Map([
  ['xai', 1], //        blue
  ['anthropic', 2], //  orange
  ['openai', 3], //     teal
  ['moonshot', 4], //   pink
  ['local', 5], //      lime
  ['meta', 6], //       violet
  ['deepseek', 7], //   sky
  ['openrouter', 8], // gold
]);

/** A sibling's tone within its family's hue, in registry order: the base, one lighter, one darker. */
const TONES = ['', '-up', '-down'] as const;

/** A head's colour: `0` for no head, else a slot 1..8 and its tone (`3`, `3-up`, `3-down`). The
 *  `myx-hue-<hue>` class binds it (hueClass). */
export type Hue = `${number}` | `${number}-up` | `${number}-down`;

function toned(slot: number, nth: number): Hue {
  const tone = TONES[nth % TONES.length];
  return tone === '' ? `${slot}` : `${slot}${tone}`;
}

/**
 * Every listed head's colour.
 *
 * A head of a known family takes that family's slot, toned by how many of its family came before it
 * in the registry, so adding a head of another family anywhere never moves it. A head whose family
 * the console does not map (an unrecognised provider, or a daemon too old to name one) wears the
 * no-head neutral: on this console a colour names a provider, and every slot is some provider's, so
 * any slot it took would name the wrong one (Hitstop and Marlin, 2026-09-25). Its name, always beside
 * its mark, tells it apart.
 */
export function huesOf(registry: readonly RegistryEntry[]): ReadonlyMap<string, Hue> {
  const hues = new Map<string, Hue>();
  const siblings = new Map<number, number>();
  for (const entry of registry) {
    const slot = entry.family == null ? undefined : FAMILY_SLOT.get(entry.family);
    if (slot === undefined) {
      hues.set(entry.key, '0');
      continue;
    }
    const nth = siblings.get(slot) ?? 0;
    siblings.set(slot, nth + 1);
    hues.set(entry.key, toned(slot, nth));
  }
  return hues;
}
