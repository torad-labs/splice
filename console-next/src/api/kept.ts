// What splice keeps: the census of each store the daemon owns, and the delete of one. A delete answers a different
// shape per store, so it is followed by a fresh read of the census and the answer itself is discarded.
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { request } from './client';
import { read } from './queries';
import type { KeptInventory, KeptStore, TraceInventory } from '../types/kept';

export const keptKey = ['kept'] as const;
export const traceKeptKey = ['trace-kept'] as const;

export const keptPath = (store: KeptStore): string => `/api/kept/${store}`;
export const traceKeptPath = (head: string): string => `/api/heads/${encodeURIComponent(head)}/trace/kept`;

/** The census of one store. Read when the page opens and after a delete; nothing moves it in between but the sweep. */
export const useKept = (store: KeptStore) => useQuery(read<KeptInventory>([...keptKey, store], keptPath(store), { refetchInterval: false }));

export const useTraceKept = (head: string) => useQuery(read<TraceInventory>([...traceKeptKey, head], traceKeptPath(head), { refetchInterval: false }));

/** Delete a store's physical days, then read the census again. A refusal rejects with the daemon's sentence. */
export function useKeptDelete() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (target: { store: KeptStore } | { head: string }) =>
      request<unknown>('store' in target ? keptPath(target.store) : traceKeptPath(target.head), { method: 'DELETE' }),
    onSettled: () => Promise.all([keptKey, traceKeptKey].map((key) => client.invalidateQueries({ queryKey: [...key] }))),
  });
}
