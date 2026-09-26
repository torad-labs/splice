import { createKeyed } from '@shared/lib';
import type { TranscriptSlice } from './types';

/**
 * The loaded transcript of ONE session at a time, plus its cursor, by session id: the panel opened
 * next asks for its own, never the conversation read before it (V4-304). A union with PendingRoute
 * because the route does not exist yet (V4-130).
 *
 * There is no polling function in this slice and nothing fetches on import: FEATURES.md 4.4 reads
 * the conversation "from disk on demand", so a page has to ask for the first page and ask again for
 * each one after it. The store holds only what was asked for.
 */
export const transcriptStore = createKeyed<string, TranscriptSlice>();
