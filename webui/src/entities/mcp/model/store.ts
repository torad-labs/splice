import { createResource } from '@shared/lib';
import type { McpPayload } from './types';

/** The MCP host state. Not a PendingRoute union: GET /api/mcp exists (ControlServer.kt:161). */
export const mcpStore = createResource<McpPayload>();
