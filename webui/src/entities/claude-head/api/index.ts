// The Claude head entity's HTTP segment: read the mode, install or remove the wrap shim, no
// rendering. The client helper carries the key, the 401 lockout and the error envelope, and
// `pendingOf` carries the "route not built yet" mapping (CONTRACTS.md 8), so nothing here
// re-implements either.
import { pendingOf, request } from '@shared/api';
import { poll } from '@shared/lib';
import { claudeHeadStore } from '../model/store';
import { PENDING_CLAUDE_HEAD } from '../model/types';
import type { ClaudeHeadActionResult, ClaudeHeadPayload } from '../model/types';

function messageOf(err: unknown): string {
  return err instanceof Error ? err.message : String(err);
}

export async function fetchClaudeHead(): Promise<void> {
  claudeHeadStore.startLoading();
  try {
    claudeHeadStore.setData(await request<ClaudeHeadPayload>('/api/claude-head'));
  } catch (err) {
    const pending = pendingOf(err, PENDING_CLAUDE_HEAD);
    if (pending !== null) {
      claudeHeadStore.setData(pending);
      return;
    }
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
 * A pending route throws: there is no honest empty for an ACTION. The page shows the exact CLI
 * command that stands in today, which it can only do if it is told the route is absent.
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
