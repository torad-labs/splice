import { createResource } from '@shared/lib';
import type { ModelsPayload, PendingRoute } from './types';

/** The catalog per head. A union with PendingRoute because GET /api/models does not exist yet
 *  (V4-127): the store holds the honest empty, never a mocked catalog. */
export const modelsStore = createResource<ModelsPayload | PendingRoute>();
