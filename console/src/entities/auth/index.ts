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
  unpinAccount,
} from './api';
export { PENDING_AUTH_WRITES } from './model/types';
export type {
  AccountMutationPayload,
  AuthActionOutcome,
  AuthActionState,
  LoginStatusPayload,
  SwitchPayload,
} from './model/types';
export type { LoginState, LoginView } from '@shared/api';
export { LIVE_KINDS } from './model/live';
export { isClientLogin, signInOf } from './model/sign-in';
export type { SignIn, SignInState } from './model/sign-in';
export const useAuth = authStore.use;
export const useAuthAction = authActionStore.use;
