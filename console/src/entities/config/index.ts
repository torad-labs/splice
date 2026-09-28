import { configStore } from './model/store';
import { restartStore } from './model/restart';

export { fetchConfig, applyConfigPatch, fetchTopologyStale, markPendingAfterWrite, probeTopologyStale, startDaemonBootPolling } from './api';
export { clearRestartPending, restartStore } from './model/restart';
export {
  diffPatch,
  globalValueOf,
  headOptions,
  knobDispositions,
  parseConfigInput,
  provenanceOf,
  shadowOfOverride,
} from './model/store';
export type { DiffEntry } from './model/store';
export { PROVENANCE_LAYERS } from './model/types';
export type { KnobDisposition, Provenance } from './model/types';
export const useConfig = configStore.use;
export const useRestartPending = restartStore.use;
