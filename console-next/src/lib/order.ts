// The operator's order, kept in the browser (the daemon has no field for it). One list of keys per place:
// `sessions` holds session keys, `fleet` head keys. A place with no kept order is in the order it was given.
import { useSyncExternalStore } from 'react';
import { readJson, writeJson } from './storage';

export type Place = 'sessions' | 'fleet';
const storageKey = (place: Place): string => `splice-order-${place}`;

const listeners = new Set<() => void>();
const held = new Map<Place, readonly string[]>();

function load(place: Place): readonly string[] {
  const cached = held.get(place);
  if (cached !== undefined) return cached;
  const raw = readJson<unknown>(storageKey(place), []);
  const list = Array.isArray(raw) ? raw.filter((key): key is string => typeof key === 'string') : [];
  held.set(place, list);
  return list;
}

export function setOrder(place: Place, order: readonly string[]): void {
  held.set(place, order);
  writeJson(storageKey(place), order);
  listeners.forEach((fn) => fn());
}

export function useOrder(place: Place): readonly string[] {
  return useSyncExternalStore(
    (fn) => {
      listeners.add(fn);
      return () => {
        listeners.delete(fn);
      };
    },
    () => load(place),
  );
}

/** Sort [items] by the position of their key in [order]; a key the order has not seen keeps its place after
 *  the known ones, in the order given. */
export function sortByOrder<T>(items: readonly T[], keyOf: (item: T) => string, order: readonly string[]): T[] {
  const at = new Map(order.map((key, index) => [key, index] as const));
  const rank = (item: T): number => at.get(keyOf(item)) ?? order.length;
  return items.map((item, index) => ({ item, index })).sort((a, b) => rank(a.item) - rank(b.item) || a.index - b.index).map((entry) => entry.item);
}

/** [order] with [key] moved to sit where [over] sits. Keys the order did not hold yet are added first, in the
 *  sequence [all] gives, so a first drag fixes the whole order. */
export function moveKey(order: readonly string[], all: readonly string[], key: string, over: string): string[] {
  const full = [...order, ...all.filter((candidate) => !order.includes(candidate))];
  const from = full.indexOf(key);
  const to = full.indexOf(over);
  if (from === -1 || to === -1 || from === to) return full;
  const next = full.filter((candidate) => candidate !== key);
  next.splice(to, 0, key);
  return next;
}
