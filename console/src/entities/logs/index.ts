import { logsStore } from './model/store';

// The payload type lives in the shared client (it is the client's contract), re-exported here so a
// page or widget that reads this slice does not have to reach past it for the type.
export type { LogsPayload } from '@shared/api';

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
  timeOf,
  dateOf,
  LEVELS,
  NO_FILTER,
} from './model/derive';
export type { LogFilter, LogLevel, LogTail, TailAdvance } from './model/derive';
export const useLogs = logsStore.use;
