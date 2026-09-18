import { doctorStore } from './model/store';

export { fetchDoctor, startDoctorPolling, PENDING_DOCTOR } from './api';
export { isRedacted, leaksIn, leaksInText } from './model/redact';
export type { Leak, LeakKind } from './model/redact';
export { checkFix, checkSection } from './model/types';
export type { DoctorCheck, DoctorPayload, DoctorSlice, DoctorStatus } from './model/types';
export const useDoctor = doctorStore.use;
