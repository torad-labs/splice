// The topology entity's HTTP segment: read and write of splice.toml, no rendering. The client
// helper carries the key, the 401 lockout and the error envelope, and `pendingOf` carries the
// "route not built yet" mapping (CONTRACTS.md 8), so nothing here re-implements either.
import { pendingOf, request } from '@shared/api';
import { poll } from '@shared/lib';
import { topologyStore } from '../model/store';
import { PENDING_TOPOLOGY } from '../model/types';
import type { TopologyPayload, TopologyWriteResult } from '../model/types';

function messageOf(err: unknown): string {
  return err instanceof Error ? err.message : String(err);
}

export async function fetchTopology(): Promise<void> {
  topologyStore.startLoading();
  try {
    topologyStore.setData(await request<TopologyPayload>('/api/topology'));
  } catch (err) {
    const pending = pendingOf(err, PENDING_TOPOLOGY);
    if (pending !== null) {
      topologyStore.setData(pending);
      return;
    }
    topologyStore.setError(messageOf(err));
  }
}

/**
 * PUT the whole topology. The daemon backs the file up first and writes through its structured
 * writer, so a rejected key comes back as `findings` on a successful response rather than as a
 * thrown error — the caller renders them beside the editor.
 *
 * A pending route throws instead of writing anything: there is no honest empty for a WRITE. The
 * console must show the exact CLI command that answers today (FEATURES 4.7), and it can only do
 * that if it is told the route is absent, which is what the throw carries.
 */
export async function saveTopology(topology: Record<string, unknown>): Promise<TopologyWriteResult> {
  return request<TopologyWriteResult>('/api/topology', {
    method: 'PUT',
    body: JSON.stringify({ topology }),
  });
}

export function startTopologyPolling(intervalMs = 30000): () => void {
  return poll(fetchTopology, intervalMs);
}
