import { bindUnauthorized, getStoredKey, storeKey } from '@shared/api';
import { sessionStore } from '../model/store';

/** Wire the 401 signal from the mgmt client into session state (app mount). */
export function initSession(): void {
  bindUnauthorized(() => sessionStore.setState({ locked: true }));
  sessionStore.setState({ hasKey: Boolean(getStoredKey()), locked: !getStoredKey() });
}

/** Store the pasted management key and unlock; pollers retry on their next tick. */
export function unlock(key: string): void {
  storeKey(key);
  sessionStore.setState({ locked: false, hasKey: Boolean(key.trim()) });
}
