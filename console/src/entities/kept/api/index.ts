import { request } from '@shared/api';
import type { KeptInventory, KeptRemoval, KeptStore, TraceInventory } from '../model/types';

function path(store: KeptStore): string {
  return `/api/kept/${store}`;
}

/** The daemon's current physical census, including days written before a switch went off. */
export function fetchKept(store: KeptStore): Promise<KeptInventory> {
  if (store === 'turns') return request<KeptInventory>('/api/kept/turns');
  return request<KeptInventory>(path(store));
}

/** Delete only the named splice-owned store; callers refresh the physical census afterward. */
export function deleteKept(store: KeptStore): Promise<KeptInventory> {
  return request<KeptInventory>(path(store), { method: 'DELETE' });
}

function failureOf(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

/** DELETE responses differ across stores; always refresh physical truth, even after failure. */
async function removeAndRead<T>(remove: () => Promise<T>, read: () => Promise<T>): Promise<KeptRemoval<T>> {
  let deleteError: string | null = null;
  try {
    await remove();
  } catch (error) {
    deleteError = failureOf(error);
  }
  try {
    return { inventory: await read(), deleted: deleteError === null, deleteError, readError: null };
  } catch (error) {
    return { inventory: null, deleted: deleteError === null, deleteError, readError: failureOf(error) };
  }
}

export function removeKept(store: KeptStore): Promise<KeptRemoval> {
  return removeAndRead(() => deleteKept(store), () => fetchKept(store));
}

function tracePath(head: string): string {
  return `/api/heads/${encodeURIComponent(head)}/trace/kept`;
}

export function fetchTraceKept(head: string): Promise<TraceInventory> {
  return request<TraceInventory>(tracePath(head));
}

export function removeTraceKept(head: string): Promise<KeptRemoval<TraceInventory>> {
  return removeAndRead(
    () => request<TraceInventory>(tracePath(head), { method: 'DELETE' }),
    () => fetchTraceKept(head),
  );
}
