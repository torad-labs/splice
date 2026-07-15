import { logsStore } from './model/store';

export { fetchLogs, startLogsPolling, setLogTail, currentLogTail, setLogHead, currentLogHead } from './api';
export const useLogs = logsStore.use;
