import { logsStore } from './model/store';

export { fetchLogs, startLogsPolling, setLogTail, currentLogTail, setLogHead, currentLogHead } from './api';
export {
  advance,
  applyFilter,
  headOf,
  headsPresent,
  levelOf,
  levelsPresent,
  matches,
  overlap,
  tailOf,
  LEVELS,
  NO_FILTER,
} from './model/derive';
export type { LogFilter, LogLevel, LogTail, TailAdvance } from './model/derive';
export const useLogs = logsStore.use;
