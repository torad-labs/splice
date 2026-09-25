// THE HEAD MARK (docs/design/DESIGN.md section 5): the console's one bold place. Every head owns
// one of eight colours, and the colour follows the head to every page it appears on, so orange on
// the sessions board and orange in the usage chart are the same head. The name always travels
// beside the colour, so the mark is never the only signal.
//
// The colour is the head's place in the daemon's registry (GET /api/status), which keeps topology
// order: adding a head appends, so no existing head changes colour. A head the registry does not
// list (a session splice did not start, or a registry not read yet) takes the neutral grey.
import type { ReactNode } from 'react';
import { cx } from '@shared/lib';
import { controlStatusStore } from '../model/store';
import './head-mark.css';

/** How many head colours tokens.css declares (--head-1..8). */
export const HEAD_HUES = 8;

/** A head's colour slot, 1..8, from its index in the registry's keys; 0 for a head it does not list. */
export function hueOf(head: string, keys: readonly string[]): number {
  const at = keys.indexOf(head);
  return at === -1 ? 0 : (at % HEAD_HUES) + 1;
}

/** The class that binds `--hue` to a head's colour for everything inside it (a chart series, a
 *  share bar, a group title). */
export function hueClass(hue: number): string {
  return `myx-hue-${hue}`;
}

/** The head's colour slot, read from the registry the status strip keeps polled. */
export function useHue(head: string): number {
  const registry = controlStatusStore.use((state) => state.data?.registry ?? null);
  return hueOf(head, registry === null ? [] : registry.map((entry) => entry.key));
}

/** Every head's colour slot at once, for a page that colours many heads (a chart). */
export function useHues(): (head: string) => number {
  const registry = controlStatusStore.use((state) => state.data?.registry ?? null);
  const keys = registry === null ? [] : registry.map((entry) => entry.key);
  return (head: string) => hueOf(head, keys);
}

/** A head's colour mark and its name: the registry's label (the command the operator types, like
 *  `claude-bonsai`) wherever the registry lists the head, so one head reads with one colour and one
 *  name on every page, and the key itself where it does not. `hue` overrides the registry, for a
 *  caller (or a test) that already knows it; `children` replaces the printed name (a head with no
 *  key prints why). */
export function HeadMark({ head, hue, children }: { head: string; hue?: number; children?: ReactNode }) {
  const registry = controlStatusStore.use((state) => state.data?.registry ?? null);
  const keys = registry === null ? [] : registry.map((entry) => entry.key);
  const slot = hue ?? hueOf(head, keys);
  const label = registry?.find((entry) => entry.key === head)?.label ?? head;
  return (
    <span className={cx('myx-hm', hueClass(slot))} data-hue={slot}>
      <span className="myx-hm-mark" aria-hidden="true" />
      <span className="myx-hm-name">{children ?? label}</span>
    </span>
  );
}
