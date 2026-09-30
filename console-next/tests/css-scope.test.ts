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

// TYPE FLOORS AT THE OPERATOR'S FRAME (ruling 4, 3840 wide, root 20px): a caption reads at 16px or more (0.8rem) and a cell or control
// at 19px or more (0.96rem). A size is one or the other: a caption rung from 0.8rem to 0.875rem, or a cell rung from 0.96rem up.
// The font-size wall reads only `font-size:`; every size written in a `font:` shorthand slipped past it.
const CAPTION_FLOOR = 0.8;
const CAPTION_CEILING = 0.875;
const CELL_FLOOR = 0.96;

/** Every rem size a sheet writes in `font` or `font-size`, with the declaration it came from. */
export function textSizes(css: string): { rem: number; text: string }[] {
  const out: { rem: number; text: string }[] = [];
  for (const match of css.replace(/\/\*[\s\S]*?\*\//g, '').matchAll(/(?<![\w-])font(?:-size)?\s*:\s*([^;}]*)/g)) {
    const text = match[1] ?? '';
    const rem = /(?<![\w.-])(\d*\.?\d+)rem\b/.exec(text)?.[1];
    if (rem !== undefined) out.push({ rem: Number(rem), text: text.trim() });
  }
  return out;
}

/** Sizes that are neither a caption nor a cell. */
export const offScale = (sizes: readonly { rem: number }[]): number[] =>
  [...new Set(sizes.map((size) => size.rem).filter((rem) => rem < CAPTION_FLOOR || (rem > CAPTION_CEILING && rem < CELL_FLOOR)))].sort((a, b) => a - b);

describe('type floors', () => {
  test('every size in every sheet is a caption of 16px or more at 3840 or a cell of 19px or more (0.96rem, so the float arithmetic cannot land a hair under)', () => {
    const sizes = Object.entries(sheets).filter(([file]) => file !== 'src/styles/tokens.css').flatMap(([, css]) => textSizes(css));
    expect(sizes.length).toBeGreaterThan(100);
    expect(offScale(sizes)).toEqual([]);
  });

  test('the check fails on a shorthand size between the rungs and one below the caption floor', () => {
    const planted = textSizes('.a { font: 500 0.9375rem var(--meta); } .e { font: 500 0.95rem var(--meta); } .b { font: 600 0.75rem/1 var(--meta); } .c { font: 0.875rem var(--meta); } .d { font-size: 1rem; }');
    expect(offScale(planted)).toEqual([0.75, 0.9375, 0.95]);
  });
});

