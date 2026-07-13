import { compactStore } from './model/store';

export { fetchCompact, startCompactPolling } from './api';
export const useCompact = compactStore.use;
