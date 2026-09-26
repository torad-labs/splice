// Theme: two rooms, dark by default.
//
// The console opens dark whatever the machine's preference is: the operator
// runs it on a second monitor beside a terminal, and the graphite room is the
// room they chose. A manual switch is remembered and outranks the default on
// the next open. The OS is never consulted, in either direction.
//
// Switching is a cut, never a fade: applyTheme stamps data-theme-cut for the
// frame of the switch and tokens.css holds every transition still for it.
//
// The decision rules are pure functions over the two interfaces below, so the
// whole module is testable without a DOM; only useTheme() touches React.
import { useCallback, useEffect, useState } from 'react';

export type Theme = 'dark' | 'light';

/** The stored choice. Namespaced beside splice.views.<pageId>. */
export const THEME_KEY = 'splice.theme';

/** Dark is the console's own default, not a fallback for a failed read. */
export const DEFAULT_THEME: Theme = 'dark';

/** The attribute tokens.css keys its two sets off. */
export const THEME_ATTR = 'data-theme';

/** Held for the frame of a switch so nothing animates between rooms. */
export const CUT_ATTR = 'data-theme-cut';

/** The slice of Storage this module needs. */
export interface ThemeStore {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
}

/** The slice of an element this module needs. */
export interface ThemeRoot {
  setAttribute(name: string, value: string): void;
  removeAttribute(name: string): void;
}

export function isTheme(value: unknown): value is Theme {
  return value === 'dark' || value === 'light';
}

/** The stored choice, or null when nothing valid is stored. */
export function readTheme(store: ThemeStore): Theme | null {
  const raw = store.getItem(THEME_KEY);
  return isTheme(raw) ? raw : null;
}

/** What the console opens with: the stored choice, else dark. Never the OS. */
export function initialTheme(store: ThemeStore): Theme {
  return readTheme(store) ?? DEFAULT_THEME;
}

/** Write the choice onto the root element, cut first so the switch does not animate. */
export function applyTheme(theme: Theme, root: ThemeRoot): void {
  root.setAttribute(CUT_ATTR, '');
  root.setAttribute(THEME_ATTR, theme);
}

/** Release the cut once the switched frame has been painted. */
export function endThemeCut(root: ThemeRoot): void {
  root.removeAttribute(CUT_ATTR);
}

/** Remember the choice. A store that refuses writes is not worth a crash. */
export function persistTheme(store: ThemeStore, theme: Theme): void {
  try {
    store.setItem(THEME_KEY, theme);
  } catch {
    /* private mode and full quotas both land here; the console keeps its room */
  }
}

function browserStore(): ThemeStore {
  return window.localStorage;
}

function browserRoot(): ThemeRoot {
  return document.documentElement;
}

export interface ThemeControl {
  theme: Theme;
  set(theme: Theme): void;
}

/**
 * The console's theme, applied to <html> and remembered.
 *
 * On mount it applies the initial theme, so the room is on the element before
 * React has painted anything. A theme written by another tab is ignored: one
 * window, one operator, one room.
 */
export function useTheme(): ThemeControl {
  const [theme, setThemeState] = useState<Theme>(() => {
    try {
      return initialTheme(browserStore());
    } catch {
      return DEFAULT_THEME;
    }
  });

  useEffect(() => {
    const root = browserRoot();
    applyTheme(theme, root);
    const frame = requestAnimationFrame(() => endThemeCut(root));
    return () => cancelAnimationFrame(frame);
  }, [theme]);

  const set = useCallback((next: Theme) => {
    setThemeState(next);
    try {
      persistTheme(browserStore(), next);
    } catch {
      /* unreadable storage: the switch still holds for this session */
    }
  }, []);

  return { theme, set };
}
