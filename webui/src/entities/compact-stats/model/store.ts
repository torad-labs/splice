import { createResource } from '@shared/lib';
import type { CompactPayload } from '@shared/api';

export const compactStore = createResource<CompactPayload>();
