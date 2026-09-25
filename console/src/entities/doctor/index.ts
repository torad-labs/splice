import { doctorStore, upgradeStore } from './model/store';

export { fetchDoctor, fetchUpgrade, readUpgradeRun, runDoctorFix, startDoctorPolling, startUpgrade, PENDING_DOCTOR } from './api';
export type { DoctorFixAnswer, UpgradeRunRead } from './api';
export { isRedacted, leaksIn, leaksInText } from './model/redact';
export type { Leak, LeakKind } from './model/redact';
export { checkFinding, checkFix, checkSection, collapseChecks, fixMasked, wantsAttention } from './model/types';
export type { CheckRow, DoctorCheck, DoctorPayload, DoctorSlice, DoctorStatus } from './model/types';
export { PENDING_UPGRADE, UPGRADE_RUN_STATES, upgradeVerdict } from './model/upgrade';
export type { UpgradeAsk, UpgradePayload, UpgradeRun, UpgradeRunState, UpgradeSlice, UpgradeVerdict } from './model/upgrade';
export const useDoctor = doctorStore.use;
export const useUpgrade = upgradeStore.use;
