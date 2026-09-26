// The MCP entity's HTTP segment: ONE read. There is no restart call to write: /mcp/{name} is the
// JSON-RPC transport, and a hosted server that exits respawns on its next call (HostedServer.kt).
import { request } from '@shared/api';
import { poll } from '@shared/lib';
import { mcpStore } from '../model/store';
import type { McpPayload } from '../model/types';

export async function fetchMcp(): Promise<void> {
  mcpStore.startLoading();
  try {
    mcpStore.setData(await request<McpPayload>('/api/mcp'));
  } catch (err) {
    mcpStore.setError(err instanceof Error ? err.message : String(err));
  }
}

export function startMcpPolling(intervalMs = 10000): () => void {
  return poll(fetchMcp, intervalMs);
}
