import { authActionStore, authStore } from './model/store';

export {
  fetchAuth,
  fetchLoginStatus,
  refreshAuth,
  relabelAccount,
  removeAccount,
  startAuthPolling,
  startLogin,
  switchAccount,
} from './api';
export { LOGIN_FLOWS, LOGIN_STATES, PENDING_AUTH_WRITES } from './model/types';
export type {
  AccountMutationPayload,
  AuthActionOutcome,
  AuthActionState,
  LoginFlow,
  LoginStartPayload,
  LoginState,
  LoginStatusPayload,
  SwitchPayload,
} from './model/types';
export { LIVE_KINDS } from './model/live';
export const useAuth = authStore.use;
export const useAuthAction = authActionStore.use;
