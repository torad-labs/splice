import { accountsStore } from './model/store';

export { accountsStore } from './model/store';
export { fetchAccounts, startAccountsPolling } from './api';
export {
  nearestOverall,
  nearestWindow,
  nextTarget,
  sevenDayUsed,
  windowLengthText,
  windowUsedText,
  NOT_REPORTED,
  SELECTOR_ORDER_TEXT,
  SELECTOR_RULES,
} from './model/derive';
export type { NearestOverall, NextTarget, SelectorRule } from './model/derive';
export { PENDING_ACCOUNTS } from './model/types';
export type {
  AccountRow,
  AccountsPayload,
  AccountsState,
  AccountSwitch,
  AccountWindow,
} from './model/types';
export const useAccounts = accountsStore.use;
