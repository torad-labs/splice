// The Claude head entity's HTTP segment: read the mode, install or remove the wrap shim, no
// rendering. The client helper carries the key, the 401 lockout and the error envelope, and
// `pendingOf` carries the "route not built yet" mapping (CONTRACTS.md 8), so nothing here
// re-implements either.
import { request } from '@shared/api';
import { poll } from '@shared/lib';
import { claudeHeadStore } from '../model/store';
import type { ClaudeHeadActionResult, ClaudeHeadPayload } from '../model/types';

function messageOf(err: unknown): string {
  return err instanceof Error ? err.message : String(err);
}

/** No `pendingOf` here since V4-175: the route is served (V4-129), so a failure reading it is a
 *  failure, and mapping a 404 to "not built yet" would hide a daemon that stopped answering behind
 *  a row id that closed. */
export async function fetchClaudeHead(): Promise<void> {
  claudeHeadStore.startLoading();
  try {
    claudeHeadStore.setData(await request<ClaudeHeadPayload>('/api/claude-head'));
  } catch (err) {
    claudeHeadStore.setError(messageOf(err));
  }
}

/**
 * Install the wrap shim over the vanilla config dir. This is one action, not a mode toggle: the
 * two routes are separate verbs because wrap and unwrap have different side effects (one writes
 * two files and shadows a command, the other restores them) and a single PATCH would have to
 * guess which of the two the operator meant. Both re-read the card afterwards, since the mode on
 * screen must be the daemon's, not the one the click hoped for.
 *
 * A REFUSAL throws, and that is the point: the daemon answers 409 with the reason in words
 * ("claude is not currently wrapped", "the 'claude-splice' head is not configured — wrap needs its
 * catalog to materialize"), and the sentence is the whole content of the answer. There is no
 * honest empty for an action, so the page must be told, and must print what it was told.
 */
export async function wrapClaudeHead(): Promise<ClaudeHeadActionResult> {
  const result = await request<ClaudeHeadActionResult>('/api/claude-head/wrap', { method: 'POST' });
  await fetchClaudeHead();
  return result;
}

export async function unwrapClaudeHead(): Promise<ClaudeHeadActionResult> {
  const result = await request<ClaudeHeadActionResult>('/api/claude-head/unwrap', { method: 'POST' });
  await fetchClaudeHead();
  return result;
}

export function startClaudeHeadPolling(intervalMs = 30000): () => void {
  return poll(fetchClaudeHead, intervalMs);
}
