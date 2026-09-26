import { accountsStore } from './model/store';

export { accountsStore } from './model/store';
export { fetchAccounts, startAccountsPolling } from './api';
export {
  accountState,
  exclusionText,
  isExcluded,
  isStale,
  nearestOverall,
  nearestWindow,
  nextRuleOf,
  poolOf,
  readAgeText,
  resetText,
  selectedExcluded,
  sevenDayUsed,
  slotWindows,
  windowLengthText,
  windowUsedText,
  COCK_AT_PERCENT,
  EXCLUDED_REASON,
  EXHAUSTED_AT_PERCENT,
  NOT_REPORTED,
  NOT_REREAD,
  SELECTOR_ORDER_TEXT,
  SELECTOR_RULES,
} from './model/derive';
export type {
  AccountState,
  NearestOverall,
  SelectorRule,
} from './model/derive';
export { PENDING_ACCOUNTS } from './model/types';
export { accountsFromWire } from './model/wire';
export type {
  AccountRow,
  AccountsPayload,
  AccountsState,
  AccountSwitch,
  AccountWindow,
  AccountWire,
  AccountsWire,
} from './model/types';
export { LIVE_KINDS } from './model/live';
export const useAccounts = accountsStore.use;
