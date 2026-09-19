import { createResource } from '@shared/lib';
import type { DoctorSlice } from './types';
import type { UpgradeSlice } from './upgrade';

/** The doctor report. A union with PendingRoute because GET /api/doctor does not exist yet
 *  (V4-127); the page renders the pending empty and never a fabricated clean bill of health. */
export const doctorStore = createResource<DoctorSlice>();

/** GET /api/upgrade: installed, latest, rollback available. Same pending rule. */
export const upgradeStore = createResource<UpgradeSlice>();
