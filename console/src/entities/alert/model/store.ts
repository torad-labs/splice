import { createResource } from '@shared/lib';
import type { AlertsSlice } from './types';

/** The alert settings. A union with PendingRoute because GET /api/alerts does not exist yet
 *  (V4-133). */
export const alertsStore = createResource<AlertsSlice>();
