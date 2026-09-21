import { accountsStore } from './model/store';

export { accountsStore } from './model/store';
export { fetchAccounts, startAccountsPolling } from './api';
export {
  accountState,
  exclusionText,
  isExcluded,
  nearestOverall,
  nearestWindow,
  nextTarget,
  resetText,
  sevenDayUsed,
  windowLengthText,
  windowUsedText,
  COCK_AT_PERCENT,
  EXCLUDED_REASON,
  EXHAUSTED_AT_PERCENT,
  NOT_REPORTED,
  SELECTOR_ORDER_TEXT,
  SELECTOR_RULES,
} from './model/derive';
export type {
  AccountState,
  NearestOverall,
  NextTarget,
  SelectorRule,
} from './model/derive';
export { PENDING_ACCOUNTS } from './model/types';
export type {
  AccountRow,
  AccountsPayload,
  AccountsState,
  AccountSwitch,
  AccountWindow,
} from './model/types';
export { LIVE_KINDS } from './model/live';
export const useAccounts = accountsStore.use;
