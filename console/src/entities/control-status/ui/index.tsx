// THE HEAD MARK (docs/design/DESIGN.md section 5): the console's one bold place. Every head owns
// one colour, and the colour follows the head to every page it appears on, so orange on the
// sessions board and orange in the usage chart are the same head. The name always travels beside
// the colour, so the mark is never the only signal.
//
// The colour is the head's provider family, read from the daemon's registry (GET /api/status), and
// siblings of one family step in lightness in registry order (model/hue.ts). A head the registry
// does not list (a session splice did not start, or a registry not read yet) takes the neutral grey.
import type { ReactNode } from 'react';
import { cx } from '@shared/lib';
import type { RegistryEntry } from '@shared/api';
import { huesOf, type Hue } from '../model/hue';
import { controlStatusStore } from '../model/store';
import './head-mark.css';

export { HEAD_HUES } from '../model/hue';
export type { Hue } from '../model/hue';

/** A head's colour from the registry; `0` for a head it does not list. */
export function hueOf(head: string, registry: readonly RegistryEntry[]): Hue {
  return huesOf(registry).get(head) ?? '0';
}

/** The class that binds `--hue` to a head's colour for everything inside it (a chart series, a
 *  share bar, a group title). A number is a base tone: `0` is the neutral grey. */
export function hueClass(hue: Hue | number): string {
  return `myx-hue-${hue}`;
}

/** The head's colour, read from the registry the status strip keeps polled. */
export function useHue(head: string): Hue {
  const registry = controlStatusStore.use((state) => state.data?.registry ?? null);
  return hueOf(head, registry ?? []);
}

/** Every head's colour at once, for a page that colours many heads (a chart). */
export function useHues(): (head: string) => Hue {
  const registry = controlStatusStore.use((state) => state.data?.registry ?? null);
  const hues = huesOf(registry ?? []);
  return (head: string) => hues.get(head) ?? '0';
}

/** A head's colour mark and its name: the registry's label (the command the operator types, like
 *  `claude-bonsai`) wherever the registry lists the head, so one head reads with one colour and one
 *  name on every page, and the key itself where it does not. `hue` overrides the registry, for a
 *  caller (or a test) that already knows it; `children` replaces the printed name (a head with no
 *  key prints why). */
export function HeadMark({ head, hue, children }: { head: string; hue?: Hue | number; children?: ReactNode }) {
  const registry = controlStatusStore.use((state) => state.data?.registry ?? null);
  const slot = hue ?? hueOf(head, registry ?? []);
  const label = registry?.find((entry) => entry.key === head)?.label ?? head;
  return (
    <span className={cx('myx-hm', hueClass(slot))} data-hue={slot}>
      <span className="myx-hm-mark" aria-hidden="true" />
      <span className="myx-hm-name">{children ?? label}</span>
    </span>
  );
}
