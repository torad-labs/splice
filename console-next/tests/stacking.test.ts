// A menu is portaled to <body>, so the page's layers decide whether it can be clicked from inside a dialog.
import { readFileSync } from 'node:fs';
import { describe, expect, test } from 'vitest';

const base = readFileSync(new URL('../src/styles/base.css', import.meta.url), 'utf8');
const layer = (selector: string): number => {
  const rule = base.split('\n').find((line) => line.startsWith(`${selector} {`) || line.startsWith(`${selector}{`));
  const found = rule === undefined ? null : /z-index:\s*(\d+)/.exec(rule);
  if (found?.[1] === undefined) throw new Error(`${selector} sets no z-index on its own line`);
  return Number(found[1]);
};

describe('the menu’s height', () => {
  test('a menu is capped to the room Radix reports beside its trigger and scrolls inside itself', () => {
    const rule = base.split('\n').find((line) => line.startsWith('.menu {')) ?? '';
    expect(rule).toContain('max-height: var(--radix-dropdown-menu-content-available-height)');
    expect(rule).toContain('overflow-y: auto');
  });
});

describe('the layers', () => {
  test('a menu opened from a control inside a dialog stacks above its scrim and its window', () => {
    expect(layer('.menu')).toBeGreaterThan(layer('.scrim'));
    expect(layer('.menu')).toBeGreaterThan(dialogLayer());
  });
});

function dialogLayer(): number {
  const found = /\.dialog \{[^}]*z-index:\s*(\d+)/.exec(base);
  if (found?.[1] === undefined) throw new Error('.dialog sets no z-index');
  return Number(found[1]);
}
