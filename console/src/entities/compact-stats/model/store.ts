import { createResource } from '@shared/lib';
import type { CompactPayload } from '@shared/api';
import type { InstructionsState } from './types';

export const compactStore = createResource<CompactPayload>();

/** The compaction rules in effect across the fleet (GET /api/compaction/instructions, per head). */
export const instructionsStore = createResource<InstructionsState>();
