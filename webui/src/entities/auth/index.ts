import { authStore } from './model/store';

export { fetchAuth, refreshAuth, startAuthPolling } from './api';
export const useAuth = authStore.use;
