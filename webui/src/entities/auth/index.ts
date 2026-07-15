import { authStore } from './model/store';

export { fetchAuth, refreshAuth, loginAuth, startAuthPolling } from './api';
export const useAuth = authStore.use;
