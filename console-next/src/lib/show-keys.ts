// Whether Settings prints every setting's key under its row, remembered in the browser. Off, a row's own `<>` reveals its key.
import { useSyncExternalStore } from 'react';
import { readText, writeText } from './storage';

const KEY = 'splice-show-keys';
const listeners = new Set<() => void>();
let on = readText(KEY) === 'true';

export function setShowKeys(next: boolean): void {
  on = next;
  writeText(KEY, String(next));
  listeners.forEach((fn) => fn());
}

export const showKeysNow = (): boolean => on;

export function useShowKeys(): boolean {
  return useSyncExternalStore(
    (fn) => {
      listeners.add(fn);
      return () => {
        listeners.delete(fn);
      };
    },
    showKeysNow,
  );
}
