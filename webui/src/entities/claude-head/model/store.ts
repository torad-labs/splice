import { createResource } from '@shared/lib';
import type { ClaudeHeadState } from './types';

/** The Claude head's mode and what `claude` resolves to (GET /api/claude-head). A union with
 *  PendingRoute because the route does not exist yet (V4-129): the store holds the honest empty,
 *  never a guessed mode. */
export const claudeHeadStore = createResource<ClaudeHeadState>();
