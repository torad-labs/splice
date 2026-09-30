// CSS SCOPE WALL. Every sheet is loaded for every page (the pages are one bundle), so a page's bare `.name { }` rule is a global one:
// it lands after the shared sheets and beats them at equal weight, and it reaches every other page that uses the name. A page sheet
// may not define a bare class the shared sheets define, and two pages may share a bare class only with the same declarations.
//
// The denominator is globbed from the tree; the planted case proves the check can fail.
import { describe, expect, test } from 'vitest';

const sheets = import.meta.glob('../src/**/*.css', { eager: true, query: '?raw', import: 'default' }) as Record<string, string>;

/** Bare class selectors (`.name` alone before its brace) mapped to their declaration text, one entry per sheet. */
function bareRules(css: string): Map<string, string> {
  const rules = new Map<string, string>();
  for (const match of css.replace(/\/\*[\s\S]*?\*\//g, '').matchAll(/(?<=^|[}\n])\s*(\.[\w-]+)\s*\{([^{}]*)\}/g)) {
    const [, name, body] = match;
    if (name !== undefined && body !== undefined) rules.set(name, `${rules.get(name) ?? ''}${body.replace(/\s+/g, ' ').trim()};`);
  }
  return rules;
}

/** Names a page sheet defines that a shared sheet defines, or another page defines differently. */
export function collisions(shared: Record<string, string>, pages: Record<string, string>): string[] {
  const sharedNames = new Set(Object.values(shared).flatMap((css) => [...bareRules(css).keys()]));
  const seen = new Map<string, { file: string; body: string }>();
  const found: string[] = [];
  for (const [file, css] of Object.entries(pages)) {
    for (const [name, body] of bareRules(css)) {
      if (sharedNames.has(name)) found.push(`${file}: ${name} is also defined by a shared sheet`);
      const earlier = seen.get(name);
      if (earlier !== undefined && earlier.body !== body) found.push(`${file}: ${name} differs from ${earlier.file}`);
      if (earlier === undefined) seen.set(name, { file, body });
    }
  }
  return found;
}

const inPages = (file: string): boolean => file.includes('/src/pages/');

describe('css scope', () => {
  test('no page sheet redefines a shared class or disagrees with another page about one', () => {
    const shared = Object.fromEntries(Object.entries(sheets).filter(([file]) => !inPages(file)));
    const pages = Object.fromEntries(Object.entries(sheets).filter(([file]) => inPages(file)));
    expect(Object.keys(shared).length).toBeGreaterThan(0);
    expect(Object.keys(pages).length).toBeGreaterThan(0);
    expect(collisions(shared, pages)).toEqual([]);
  });

  test('the check fails on a page rule that shadows a shared one and on two pages that disagree', () => {
    const shared = { 'base.css': '.live { display: inline-flex; }' };
    const pages = { 'a.css': '.live { display: grid; } .crumb { gap: 5px; }', 'b.css': '.crumb { gap: 4px; }' };
    expect(collisions(shared, pages)).toEqual(['a.css: .live is also defined by a shared sheet', 'b.css: .crumb differs from a.css']);
  });
});
