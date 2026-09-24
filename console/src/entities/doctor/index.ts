import { doctorStore, upgradeStore } from './model/store';

export { fetchDoctor, fetchUpgrade, startDoctorPolling, PENDING_DOCTOR } from './api';
export { isRedacted, leaksIn, leaksInText } from './model/redact';
export type { Leak, LeakKind } from './model/redact';
export { checkFinding, checkFix, checkSection } from './model/types';
export type { DoctorCheck, DoctorPayload, DoctorSlice, DoctorStatus } from './model/types';
export { PENDING_UPGRADE, upgradeVerdict } from './model/upgrade';
export type { UpgradePayload, UpgradeSlice, UpgradeVerdict } from './model/upgrade';
export const useDoctor = doctorStore.use;
export const useUpgrade = upgradeStore.use;
