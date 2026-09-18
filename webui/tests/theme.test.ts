// THEME (row M1-01). The two decisions the console makes about its room are
// pure functions over a storage slice and an element slice, so they are tested
// here without a DOM: which theme to open with, and what a switch writes.
//
// The third claim, that a reduced-motion machine holds every duration at zero,
// is a claim about tokens.css rather than about the module, so the sheet is
// parsed and the durations are read out of it. Nothing here re-types a value
// the sheet owns.
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, test } from 'vitest';

import {
  CUT_ATTR,
  DEFAULT_THEME,
  THEME_ATTR,
  THEME_KEY,
  applyTheme,
  endThemeCut,
  initialTheme,
  isTheme,
  persistTheme,
  readTheme,
  type ThemeRoot,
  type ThemeStore,
} from '../src/features/theme';

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const css = readFileSync(path.join(repoRoot, 'webui/src/shared/tokens.css'), 'utf8');

function storeWith(seed: Record<string, string> = {}): ThemeStore & { written: Record<string, string> } {
  const written: Record<string, string> = {};
  return {
    written,
    getItem: (key) => seed[key] ?? null,
    setItem: (key, value) => {
      written[key] = value;
    },
  };
}

function fakeRoot(): ThemeRoot & { attrs: Map<string, string> } {
  const attrs = new Map<string, string>();
  return {
    attrs,
    setAttribute: (name, value) => {
      attrs.set(name, value);
    },
    removeAttribute: (name) => {
      attrs.delete(name);
    },
  };
}

describe('which room the console opens in', () => {
  test('dark when nothing is stored, whatever the system prefers', () => {
    expect(initialTheme(storeWith())).toBe('dark');
    expect(DEFAULT_THEME).toBe('dark');
  });

  test('a stored choice wins', () => {
    expect(initialTheme(storeWith({ [THEME_KEY]: 'light' }))).toBe('light');
    expect(initialTheme(storeWith({ [THEME_KEY]: 'dark' }))).toBe('dark');
  });

  test('a stored value that is not a theme is ignored, not obeyed', () => {
    expect(readTheme(storeWith({ [THEME_KEY]: 'paper' }))).toBeNull();
    expect(initialTheme(storeWith({ [THEME_KEY]: 'paper' }))).toBe('dark');
    expect(isTheme('observatory')).toBe(false);
  });
});

describe('switching rooms', () => {
  test('applyTheme stamps the new room and cuts the switch', () => {
    const root = fakeRoot();
    applyTheme('light', root);
    expect(root.attrs.get(THEME_ATTR)).toBe('light');
    expect(root.attrs.get(CUT_ATTR)).toBe('');
  });

  test('the cut is released once the frame is painted', () => {
    const root = fakeRoot();
    applyTheme('dark', root);
    endThemeCut(root);
    expect(root.attrs.has(CUT_ATTR)).toBe(false);
    expect(root.attrs.get(THEME_ATTR)).toBe('dark');
  });

  test('a chosen room is remembered under the contract key', () => {
    const store = storeWith();
    persistTheme(store, 'light');
    expect(store.written[THEME_KEY]).toBe('light');
    expect(THEME_KEY).toBe('splice.theme');
  });

  test('a storage that refuses writes does not throw', () => {
    const refusing: ThemeStore = {
      getItem: () => null,
      setItem: () => {
        throw new Error('quota');
      },
    };
    expect(() => persistTheme(refusing, 'light')).not.toThrow();
  });
});

describe('reduced motion', () => {
  const reduced = (() => {
    const at = css.indexOf('@media (prefers-reduced-motion: reduce)');
    if (at < 0) throw new Error('tokens.css has no prefers-reduced-motion rule');
    const open = css.indexOf('{', at);
    let depth = 0;
    for (let i = open; i < css.length; i += 1) {
      if (css[i] === '{') depth += 1;
      else if (css[i] === '}') {
        depth -= 1;
        if (depth === 0) return css.slice(open + 1, i);
      }
    }
    throw new Error('tokens.css: unterminated prefers-reduced-motion rule');
  })();

  for (const name of ['--dur-1', '--dur-2', '--dur-3']) {
    test(`${name} is 0ms`, () => {
      const declared = new RegExp(`${name}\\s*:\\s*([^;]+);`).exec(reduced);
      expect(declared?.[1].trim()).toBe('0ms');
    });
  }

  test('the sheet declares all three durations outside the media query too', () => {
    for (const name of ['--dur-1', '--dur-2', '--dur-3']) {
      expect(new RegExp(`${name}\\s*:`).test(css)).toBe(true);
    }
  });
});
