import { logsStore } from './model/store';

export { fetchLogs, startLogsPolling, setLogTail, currentLogTail } from './api';
export const useLogs = logsStore.use;
