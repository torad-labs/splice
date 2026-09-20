import { createResource } from '@shared/lib';
import type { ClaudeHeadPayload } from './types';

/** The Claude head's mode and what `claude` resolves to (GET /api/claude-head). No PendingRoute
 *  union: V4-129 shipped the route, and a store that still carried the honest empty for a route
 *  that answers would be printing "not built yet" over a real reading. `null` while the first poll
 *  is in flight is a different fact and `createResource` already carries it. */
export const claudeHeadStore = createResource<ClaudeHeadPayload>();
