// THE HEAD MARK (docs/design/DESIGN.md section 5): the console's one bold place. Every head owns
// one colour, and the colour follows the head to every page it appears on, so orange on the
// sessions board and orange in the usage chart are the same head. The name always travels beside
// the colour, so the mark is never the only signal.
//
// The colour is the head's provider family, read from the daemon's registry (GET /api/status), and
// siblings of one family step in lightness in registry order (model/hue.ts). A head the registry
// does not list (a session splice did not start, or a registry not read yet) takes the neutral grey.
import { useMemo, type ReactNode } from 'react';
import { cx } from '@shared/lib';
import type { RegistryEntry } from '@shared/api';
import { InfoTip } from '@shared/ui';
import { huesOf, type Hue } from '../model/hue';
import { controlStatusStore } from '../model/store';
import { H, S } from './strings';
import './head-mark.css';

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

/** The name and the reason every page gives a head the registry does not list. */
export const NO_SPLICE_HEAD = S.noHead;
export const NO_SPLICE_HEAD_WHY = H.noHead;

/** Whether the registry lists [head]: true or false once it has been read, null before. A head it
 *  does not list is one splice does not run, so splice never sees its turns: its figures are
 *  unknown, never zero (Marlin, 2026-09-25: a plain `claude` member read $0.000). */
export function registryLists(spliceHeads: ReadonlySet<string> | null, head: string): boolean | null {
  return spliceHeads === null ? null : spliceHeads.has(head);
}

/** The keys the registry lists, or null while it has not been read: an unread registry must not
 *  turn every head into one splice does not run. */
export function useSpliceHeads(): ReadonlySet<string> | null {
  const registry = controlStatusStore.use((state) => state.data?.registry ?? null);
  return useMemo(() => (registry === null ? null : new Set(registry.map((entry) => entry.key))), [registry]);
}

/** A head the registry does not list, as every page prints it: the grey mark, the shared name, and
 *  why in its tip. `why` is the caller's more exact reason when it has one (Sessions counts them). */
export function HeadlessMark({ why = H.noHead }: { why?: string }) {
  return (
    <span className="myx-hm-headless">
      <HeadMark head="" hue={0}>{S.noHead}</HeadMark>
      <InfoTip text={why} label={S.noHead} side="bottom" />
    </span>
  );
}
