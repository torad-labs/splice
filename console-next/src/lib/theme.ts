// Day wall or night wall: one geometry, two materials. The choice is the operator's, kept in the browser;
// the first visit follows the system.
import { useSyncExternalStore } from 'react';
import { readText, writeText } from './storage';

export type Theme = 'day' | 'night';
/** What the operator chose: a wall, or to follow the computer. */
export type ThemeChoice = Theme | 'system';
const KEY = 'splice-theme';
const listeners = new Set<() => void>();

const systemTheme = (): Theme =>
  typeof matchMedia === 'function' && matchMedia('(prefers-color-scheme: dark)').matches ? 'night' : 'day';

let choice: ThemeChoice = ((): ThemeChoice => {
  const kept = readText(KEY);
  return kept === 'day' || kept === 'night' ? kept : 'system';
})();
const wallOf = (of: ThemeChoice): Theme => (of === 'system' ? systemTheme() : of);
let current: Theme = wallOf(choice);

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
  setThemeChoice(theme);
}

/** Keep the operator's choice; `system` follows the computer, now and when it changes. */
export function setThemeChoice(next: ThemeChoice): void {
  choice = next;
  writeText(KEY, next);
  applyTheme(wallOf(next));
}

if (typeof matchMedia === 'function') {
  matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => {
    if (choice === 'system') applyTheme(wallOf(choice));
  });
}

export function useThemeChoice(): ThemeChoice {
  return useSyncExternalStore(
    (fn) => {
      listeners.add(fn);
      return () => {
        listeners.delete(fn);
      };
    },
    () => choice,
  );
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
