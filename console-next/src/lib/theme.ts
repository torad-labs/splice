// Day wall or night wall: one geometry, two materials. The choice is the operator's, kept in the browser;
// the first visit follows the system.
import { useSyncExternalStore } from 'react';
import { readText, writeText } from './storage';

export type Theme = 'day' | 'night';
const KEY = 'splice-theme';
const listeners = new Set<() => void>();

const systemTheme = (): Theme =>
  typeof matchMedia === 'function' && matchMedia('(prefers-color-scheme: dark)').matches ? 'night' : 'day';

let current: Theme = ((): Theme => {
  const kept = readText(KEY);
  return kept === 'day' || kept === 'night' ? kept : systemTheme();
})();

export function applyTheme(theme: Theme): void {
  current = theme;
  document.documentElement.dataset.theme = theme;
  listeners.forEach((fn) => fn());
}

/** Put the kept (or the system's) wall on the page before the first paint. */
export function bootTheme(): void {
  applyTheme(current);
}

export function setTheme(theme: Theme): void {
  writeText(KEY, theme);
  applyTheme(theme);
}

export function useTheme(): Theme {
  return useSyncExternalStore(
    (fn) => {
      listeners.add(fn);
      return () => {
        listeners.delete(fn);
      };
    },
    () => current,
  );
}
