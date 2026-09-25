import { authActionStore, authStore, keysStore } from './model/store';

export {
  fetchAuth,
  fetchKeys,
  fetchLoginStatus,
  refreshAuth,
  relabelAccount,
  removeAccount,
  removeKey,
  startAuthPolling,
  startKeysPolling,
  startLogin,
  storeKey,
  switchAccount,
  unpinAccount,
} from './api';
export { PENDING_AUTH_WRITES } from './model/types';
export type {
  AccountMutationPayload,
  AuthActionOutcome,
  AuthActionState,
  KeyReader,
  KeyState,
  KeysPayload,
  LoginStatusPayload,
  SwitchPayload,
} from './model/types';
export type { LoginState, LoginView } from '@shared/api';
export { LIVE_KINDS } from './model/live';
export { isClientLogin, signInOf } from './model/sign-in';
export type { SignIn, SignInState } from './model/sign-in';
export const useAuth = authStore.use;
export const useAuthAction = authActionStore.use;
export const useKeys = keysStore.use;
