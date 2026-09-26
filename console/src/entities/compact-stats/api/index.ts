import { control, request } from '@shared/api';
import type { HeadsPayload } from '@shared/api';
import { poll } from '@shared/lib';
import { mergeInstructions } from '../model/instructions';
import { compactStore, instructionsStore } from '../model/store';
import type { InstructionsWire } from '../model/types';

function messageOf(err: unknown): string {
  return err instanceof Error ? err.message : String(err);
}

export async function fetchCompact(): Promise<void> {
  compactStore.startLoading();
  try {
    compactStore.setData(await control.compact());
  } catch (err) {
    compactStore.setError(err instanceof Error ? err.message : String(err));
  }
}

export function startCompactPolling(intervalMs = 5000): () => void {
  return poll(fetchCompact, intervalMs);
}

/** One head's answer, or the failure that kept it from being read. */
type HeadRead = { ok: true; head: string; wire: InstructionsWire } | { ok: false; head: string; err: unknown };

async function readHead(head: string): Promise<HeadRead> {
  try {
    return { ok: true, head, wire: await request<InstructionsWire>(`/api/compaction/instructions?head=${encodeURIComponent(head)}`) };
  } catch (err) {
    return { ok: false, head, err };
  }
}

/**
 * The compaction rules in effect across the fleet: GET /api/compaction/instructions for every
 * configured head, read in parallel off one heads read and merged (model/instructions.ts). The
 * heads come from GET /api/heads here rather than from the heads entity because a slice may not
 * import a sibling slice, the same trade the perf slice makes for its turns.
 *
 * One head failing does not blank the others: it is named in `unread` with the daemon's reason, and
 * only a read where EVERY head failed is an error.
 */
export async function fetchInstructions(): Promise<void> {
  instructionsStore.startLoading();
  try {
    const heads = await request<HeadsPayload>('/api/heads');
    const reads = await Promise.all(heads.heads.map((status) => readHead(status.key)));
    const answered = reads.flatMap((read) => (read.ok ? [{ head: read.head, wire: read.wire }] : []));
    const failed = reads.flatMap((read) => (read.ok ? [] : [read]));
    const first = failed[0];
    if (first !== undefined && failed.length === reads.length) throw first.err;
    instructionsStore.setData({
      rules: mergeInstructions(answered),
      unread: failed.map((read) => ({ head: read.head, reason: messageOf(read.err) })),
    });
  } catch (err) {
    instructionsStore.setError(messageOf(err));
  }
}

/** The rules' lengths are live (a file edit shows without a restart), so they are polled, at a
 *  slower cadence than the outcome feed because nothing but an edit moves them. */
export function startInstructionsPolling(intervalMs = 15000): () => void {
  return poll(fetchInstructions, intervalMs);
}
