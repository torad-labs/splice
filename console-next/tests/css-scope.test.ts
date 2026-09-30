// CSS SCOPE WALL. Every sheet is loaded for every page (the pages are one bundle), so a page's bare `.name { }` rule is a global one:
// it lands after the shared sheets and beats them at equal weight, and it reaches every other page that uses the name. A page sheet
// may not define a bare class the shared sheets define, and two pages may share a bare class only with the same declarations.
//
// The denominator is the directory listing; the planted case proves the check can fail.
import { readdirSync, readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, test } from 'vitest';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

// Read from disk: vitest's CSS handling turns an imported sheet, `?raw` included, into an empty string.
const sheets: Record<string, string> = Object.fromEntries(
  (readdirSync(path.join(root, 'src'), { recursive: true }) as string[])
    .filter((file) => file.endsWith('.css'))
    .map((file) => [`src/${file}`, readFileSync(path.join(root, 'src', file), 'utf8')]),
);

/** Bare class selectors (`.name` alone before its brace) mapped to their declaration text, one entry per sheet. */
function bareRules(css: string): Map<string, string> {
  const rules = new Map<string, string>();
  for (const match of css.replace(/\/\*[\s\S]*?\*\//g, '').matchAll(/(?<=^|[}\n])\s*(\.[\w-]+)\s*\{([^{}]*)\}/g)) {
    const [, name, body] = match;
    if (name !== undefined && body !== undefined) rules.set(name, `${rules.get(name) ?? ''}${body.replace(/\s+/g, ' ').trim()};`);
  }
  return rules;
}

/** Every class a sheet's selectors name, alone or compounded (`.grid.first` names `.grid` and `.first`). */
function classesOf(css: string): string[] {
  const selectors = css.replace(/\/\*[\s\S]*?\*\//g, '').matchAll(/(?<=^|[}\n])\s*([^{}@]+?)\s*\{/g);
  return [...selectors].flatMap((match) => match[1]?.match(/\.[\w-]+/g) ?? []);
}

/** Names a page sheet defines that a shared sheet defines, or another page defines differently. */
export function collisions(shared: Record<string, string>, pages: Record<string, string>): string[] {
  const sharedNames = new Set(Object.values(shared).flatMap((css) => classesOf(css)));
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

const inPages = (file: string): boolean => file.startsWith('src/pages/');

describe('css scope', () => {
  test('no page sheet redefines a shared class or disagrees with another page about one', () => {
    const shared = Object.fromEntries(Object.entries(sheets).filter(([file]) => !inPages(file)));
    const pages = Object.fromEntries(Object.entries(sheets).filter(([file]) => inPages(file)));
    expect(Object.keys(shared).length).toBeGreaterThan(0);
    expect(Object.keys(pages).length).toBeGreaterThan(0);
    expect(collisions(shared, pages)).toEqual([]);
  });

  test('the check fails on a page rule that shadows a shared one and on two pages that disagree', () => {
    const shared = { 'base.css': '.live { display: inline-flex; } .grid.first { gap: 0; }' };
    const pages = { 'a.css': '.live { display: grid; } .crumb { gap: 5px; } .first { gap: 1px; }', 'b.css': '.crumb { gap: 4px; }' };
    expect(collisions(shared, pages)).toEqual(['a.css: .live is also defined by a shared sheet', 'a.css: .first is also defined by a shared sheet', 'b.css: .crumb differs from a.css']);
  });
});
