// THE HAIRLINE WALL (row M1-75).
//
// `--hair` is a WIDTH (1px, tokens.css:56) and `--hairline` is a COLOUR (:206 dark, :341 light).
// The sheet documents the idiom twenty lines above the definitions: a rail reads
// `border-top: var(--hair) solid var(--hairline)`.
//
// Three rules had the two tokens in swapped slots -- `border-bottom: 1px solid var(--hair)`, which
// substitutes to `1px solid 1px`. That is invalid at computed-value time, so `border-bottom-style`
// resets to its initial `none` and THE RULE DRAWS NOTHING. It is not a `currentColor` fallback.
// Measured on the live DOM before the fix: `.myx-settings .myx-bay-rows .myx-fbox` at n=74 and
// `.myx-head` at n=2 computed `border-bottom-style: none` / `border-bottom-width: 0px`, while
// `.myx-rule`, which spells the idiom correctly, computed `solid` / `1px` / `rgba(236,234,226,.1)`.
//
// WHY THIS NEEDS A WALL RATHER THAN THREE FIXES: no instrument this campaign owns could find it.
// The D7 ink sweep reads 187 `color:` rules and never looks at a border. The coverage map reads
// ink and cannot see ink that was never painted. comp-diff compares frames and a missing hairline
// is a sub-percent tonal difference. A rule that was never drawn has no colour to measure, so the
// only way to catch the class is to read the SOURCE for the swapped slot -- which is what this does.
import { readFileSync, readdirSync, statSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, test } from 'vitest';

const here = path.dirname(fileURLToPath(import.meta.url));
// The tree the wall reads. The env override exists for ONE purpose: to prove this test RED
// against the pre-fix source (`git show HEAD~:...`), which is a stronger red-green than any
// string written by hand -- it fires the shipped test on the defect that actually shipped.
// It cannot be used to weaken the wall: point it at an empty or wrong directory and the
// denominator test below fails, because an empty denominator is never a pass.
const SRC = process.env.HAIRLINE_SCAN_ROOT ?? path.resolve(here, '..', 'src');

/** Every stylesheet under console/src -- the denominator comes from the tree, never from a list. */
export function sheets(dir: string = SRC): string[] {
  const out: string[] = [];
  for (const entry of readdirSync(dir)) {
    const full = path.join(dir, entry);
    if (statSync(full).isDirectory()) out.push(...sheets(full));
    else if (entry.endsWith('.css')) out.push(full);
  }
  return out;
}

const STYLE = '(?:solid|dashed|dotted|double|groove|ridge|inset|outset|none|hidden)';
/** Properties whose whole value is a colour, so a width token in them is always wrong. */
const COLOUR_ONLY = new Set([
  'color', 'border-color', 'border-top-color', 'border-right-color', 'border-bottom-color',
  'border-left-color', 'border-inline-color', 'border-block-color', 'background-color',
  'outline-color', 'text-decoration-color', 'caret-color', 'column-rule-color', 'fill', 'stroke',
]);

export type SlotFault = { line: number; prop: string; value: string; why: string };

/**
 * The predicate, over the text of one stylesheet. Exported so the synthetic proof below runs the
 * SAME code the real scan runs -- a wall tested through a second implementation proves nothing.
 */
export function slotFaults(css: string): SlotFault[] {
  const faults: SlotFault[] = [];
  const stripped = css.replace(/\/\*[\s\S]*?\*\//g, (m) => '\n'.repeat((m.match(/\n/g) ?? []).length));
  stripped.split('\n').forEach((line, i) => {
    const m = /^\s*([a-z-]+)\s*:\s*(.+?);/.exec(line);
    if (!m) return;
    const [, prop, value] = m;
    if (/var\(--hair\)/.test(value)) {
      if (COLOUR_ONLY.has(prop)) {
        faults.push({ line: i + 1, prop, value, why: `--hair is a width; ${prop} takes a colour` });
      } else if (new RegExp(`${STYLE}\\s+var\\(--hair\\)`).test(value)) {
        faults.push({ line: i + 1, prop, value, why: '--hair sits in the colour slot of a border shorthand' });
      }
    }
    if (new RegExp(`var\\(--hairline(?:-strong)?\\)\\s+${STYLE}`).test(value)) {
      faults.push({ line: i + 1, prop, value, why: '--hairline is a colour and is sitting in the width slot' });
    }
  });
  return faults;
}

describe('the hairline wall', () => {
  test('no stylesheet puts --hair in a colour slot, or --hairline in a width slot', () => {
    const found = sheets().flatMap((file) =>
      slotFaults(readFileSync(file, 'utf8')).map((f) => `${path.relative(SRC, file)}:${f.line}  ${f.prop}: ${f.value};  -- ${f.why}`),
    );
    expect(found).toEqual([]);
  });

  // THE WALL MUST BE ABLE TO FAIL. Each of these is a shape that shipped, or its mirror.
  test.each([
    ['the shape that shipped', '.a {\n  border-bottom: 1px solid var(--hair);\n}', '--hair sits in the colour slot'],
    ['the logical-property spelling', '.a {\n  border-block-end: 1px solid var(--hair);\n}', '--hair sits in the colour slot'],
    ['a colour-only property', '.a {\n  border-bottom-color: var(--hair);\n}', 'takes a colour'],
    ['the inverse', '.a {\n  border-top: var(--hairline) solid var(--hair);\n}', ''],
  ])('fires on %s', (_name, css, fragment) => {
    const faults = slotFaults(css);
    expect(faults.length).toBeGreaterThan(0);
    if (fragment) expect(faults.some((f) => f.why.includes(fragment))).toBe(true);
  });

  test('does not fire on the documented idiom', () => {
    expect(slotFaults('.a {\n  border-top: var(--hair) solid var(--hairline);\n}')).toEqual([]);
    expect(slotFaults('.a {\n  border: var(--hair) solid var(--strip-field-line);\n}')).toEqual([]);
  });

  test('a commented-out violation is not a violation', () => {
    expect(slotFaults('/* border-bottom: 1px solid var(--hair); */\n.a { color: red; }')).toEqual([]);
  });

  test('the denominator comes from the tree and is not empty', () => {
    expect(sheets().length).toBeGreaterThan(30);
  });
});
