import { headsStore } from './model/store';

export { fetchHeads, startHeadsPolling, startHead, stopHead, restartHead } from './api';
export const useHeads = headsStore.use;
