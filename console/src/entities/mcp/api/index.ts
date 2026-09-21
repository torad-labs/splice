// The MCP entity's HTTP segment: ONE read. There is no restart call to write, and the export that
// says so lives in model/types.ts (MCP_RESTART) so a page can render the honest empty without a
// function that looks like it might fire.
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
