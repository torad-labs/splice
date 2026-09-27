// V4-345 — A PRESSED ROW OPENER NEVER BECOMES ITS OVERLAY'S CONTAINING BLOCK.
//
// An openable table's primary cell holds a button whose ::after is `position: absolute; inset: 0`
// inside a `position: relative` row (kit.css), so a press anywhere on the row lands on the button. An
// absolute box is placed against its nearest ancestor that is positioned OR carries a transform,
// filter, perspective or containment; the button is the ::after's parent, so the moment the button
// carries any of those, the overlay shrinks from the row to the button.
//
// THE DEFECT THIS WALL NAMES, measured with real mouse events in Chrome 154 on the film home's console
// (62f49b194) and on HEAD: app.css gives every pressed button `transform: scale(.97)`. On mousedown the
// opener took the transform, its overlay shrank to the 92 x 24 px model name, the mouseup landed on the
// time cell under the pointer, and the click fired on the row, which has no handler: pointerdown and
// mousedown on BUTTON.myx-dt-opener, pointerup and mouseup on TD, click on TR, no detail. A press
// anywhere on a row but its model name opened nothing, on every openable table (Marlin's baseline walk
// Q48, film-08: "Clicking a row highlights it. Nothing opens").
//
// WHY THE SOURCE AND NOT A RENDER: vitest runs with `environment: 'node'`, and no layout engine here can
// press a button. So this reads every stylesheet under console/src (the denominator is the tree) for a
// rule that could give a pressed opener a containing block, and requires each to be taken back for the
// opener by a stronger rule. The checker is proven able to fail on the rule that shipped (the last case).
import { readFileSync, readdirSync, statSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, test } from 'vitest';

const here = path.dirname(fileURLToPath(import.meta.url));
const SRC = path.resolve(here, '..', 'src');

/** The opener's class, as kit.tsx's DataTable renders it on its primary cell's button. */
const OPENER = 'myx-dt-opener';

export interface Rule {
  where: string;
  selectors: string[];
  decls: Map<string, string>;
}

function sheets(dir: string): string[] {
  return readdirSync(dir).flatMap((entry) => {
    const full = path.join(dir, entry);
    if (statSync(full).isDirectory()) return sheets(full);
    return entry.endsWith('.css') ? [full] : [];
  });
}

/** The style rules of a sheet, those inside @media and @supports included; @keyframes and @font-face
 *  hold no selectors a button matches and are passed over. */
export function rulesOf(css: string, where: string): Rule[] {
  const text = css.replace(/\/\*[\s\S]*?\*\//g, '');
  const rules: Rule[] = [];
  let at = 0;
  while (at < text.length) {
    const open = text.indexOf('{', at);
    if (open === -1) break;
    let depth = 1;
    let close = open + 1;
    for (; close < text.length && depth > 0; close += 1) {
      if (text[close] === '{') depth += 1;
      else if (text[close] === '}') depth -= 1;
    }
    const prelude = text.slice(at, open).trim();
    const body = text.slice(open + 1, close - 1);
    if (/^@(media|supports|layer|container)\b/.test(prelude)) rules.push(...rulesOf(body, where));
    else if (!prelude.startsWith('@')) {
      const decls = new Map<string, string>();
      for (const decl of body.split(';')) {
        const colon = decl.indexOf(':');
        if (colon > 0) decls.set(decl.slice(0, colon).trim().toLowerCase(), decl.slice(colon + 1).trim().toLowerCase());
      }
      rules.push({ where, selectors: splitTop(prelude, ','), decls });
    }
    at = close;
  }
  return rules;
}

/** [text] split at [sep] outside parentheses and brackets. */
function splitTop(text: string, sep: string): string[] {
  const parts: string[] = [];
  let depth = 0;
  let start = 0;
  for (let i = 0; i < text.length; i += 1) {
    const ch = text[i];
    if (ch === '(' || ch === '[') depth += 1;
    else if (ch === ')' || ch === ']') depth -= 1;
    else if (ch === sep && depth === 0) {
      parts.push(text.slice(start, i).trim());
      start = i + 1;
    }
  }
  parts.push(text.slice(start).trim());
  return parts.filter((part) => part !== '');
}

/** A selector's compounds and the combinator before each, subject last: `a > b c` is
 *  [['', 'a'], ['>', 'b'], [' ', 'c']]. */
function compounds(selector: string): [string, string][] {
  const out: [string, string][] = [];
  let depth = 0;
  let combinator = '';
  let start = 0;
  const flush = (end: number) => {
    const text = selector.slice(start, end).trim();
    if (text !== '') {
      out.push([combinator, text]);
      combinator = '';
    }
  };
  for (let i = 0; i < selector.length; i += 1) {
    const ch = selector[i] ?? '';
    if (ch === '(' || ch === '[') depth += 1;
    else if (ch === ')' || ch === ']') depth -= 1;
    else if (depth === 0 && (ch === ' ' || ch === '>' || ch === '+' || ch === '~')) {
      flush(i);
      if (ch !== ' ' || combinator === '') combinator = ch === ' ' ? ' ' : ch;
      start = i + 1;
    }
  }
  flush(selector.length);
  return out;
}

/** The compound a selector's subject is: what follows its last combinator. */
function subject(selector: string): string {
  return compounds(selector).at(-1)?.[1] ?? '';
}

/** Specificity as [ids, classes, types], :is/:not/:has counting their strongest argument, :where none. */
export function specificity(selector: string): [number, number, number] {
  let ids = 0;
  let classes = 0;
  let types = 0;
  const rest = selector.replace(/:(is|not|has|where)\(((?:[^()]|\([^()]*\))*)\)/g, (_, name: string, args: string) => {
    if (name !== 'where') {
      const best = splitTop(args, ',').map(specificity).sort(compare).at(-1) ?? [0, 0, 0];
      ids += best[0];
      classes += best[1];
      types += best[2];
    }
    return ' ';
  });
  ids += (rest.match(/#[\w-]+/g) ?? []).length;
  classes += (rest.match(/\.[\w-]+|\[[^\]]*\]|(?<!:):[\w-]+(\([^)]*\))?/g) ?? []).length;
  types += (rest.match(/(^|[\s>+~])[a-z][\w-]*/gi) ?? []).length + (rest.match(/::[\w-]+/g) ?? []).length;
  return [ids, classes, types];
}

type Specificity = readonly [number, number, number];

function compare(left: Specificity, right: Specificity): number {
  return (left[0] - right[0]) || (left[1] - right[1]) || (left[2] - right[2]);
}

/** The state pseudo-classes a compound needs to apply (:active, :hover, :focus...). */
function states(compound: string): Set<string> {
  return new Set((compound.replace(/:(is|not|has|where)\([^)]*\)/g, '').match(/(?<!:):[\w-]+/g) ?? []));
}

/** The classes kit.tsx's DataTable can put on the opener's cell, its parent. */
const CELL_CLASSES = new Set(['myx-dt-primary', 'myx-dt-end', 'myx-dt-mono', 'myx-dt-break']);

/** Whether [compound] could be an element of [type] carrying no class outside [classes]: no pseudo-element
 *  (then it styles a generated box), no id, a type of [type] or none. */
function mayBe(compound: string, type: string, classes: ReadonlySet<string>): boolean {
  if (compound.includes('::')) return false;
  const bare = compound.replace(/:(is|not|has|where)\((?:[^()]|\([^()]*\))*\)/g, '').replace(/\[[^\]]*\]/g, '');
  if (/#/.test(bare)) return false;
  const named = /^[a-z][\w-]*/i.exec(bare)?.[0];
  if (named !== undefined && named !== type) return false;
  return (bare.match(/\.[\w-]+/g) ?? []).every((name) => classes.has(name.slice(1)));
}

/** Whether a selector could match the opener button: its subject could be the button, and when a child
 *  combinator ties it to a parent, that parent could be the opener's cell. */
function mayBeOpener(selector: string): boolean {
  const chain = compounds(selector);
  const [combinator, own] = chain.at(-1) ?? ['', ''];
  if (!mayBe(own, 'button', new Set([OPENER]))) return false;
  const parent = chain.at(-2)?.[1];
  return combinator !== '>' || parent === undefined || mayBe(parent, 'td', CELL_CLASSES);
}

/** The properties that give an element's absolute descendants their containing block, each with the
 *  test for a value that does. */
const CONTAINING: Record<string, (value: string) => boolean> = {
  transform: (value) => value !== 'none',
  translate: (value) => value !== 'none',
  rotate: (value) => value !== 'none',
  scale: (value) => value !== 'none',
  perspective: (value) => value !== 'none',
  filter: (value) => value !== 'none',
  'backdrop-filter': (value) => value !== 'none',
  'will-change': (value) => /transform|translate|rotate|scale|perspective|filter/.test(value),
  contain: (value) => /layout|paint|strict|content/.test(value),
  'container-type': (value) => /size/.test(value),
  position: (value) => /relative|absolute|fixed|sticky/.test(value),
};

export interface Offence {
  where: string;
  selector: string;
  property: string;
  value: string;
}

/** Every rule that could give a pressed opener a containing block with no stronger opener rule taking
 *  it back: one whose subject is the opener, sets the property to a value that does not, needs no
 *  state the offending rule does not, and outranks it. */
export function offences(rules: readonly Rule[]): Offence[] {
  const found: Offence[] = [];
  for (const rule of rules) {
    for (const selector of rule.selectors) {
      const compound = subject(selector);
      if (!mayBeOpener(selector)) continue;
      for (const [property, value] of rule.decls) {
        if (!(CONTAINING[property]?.(value) ?? false)) continue;
        const taken = rules.some((back) => back.selectors.some((mine) => {
          const own = subject(mine);
          const neutral = back.decls.get(property);
          return own.includes(`.${OPENER}`) && !own.includes('::') && neutral !== undefined
            && !(CONTAINING[property]?.(neutral) ?? true)
            && [...states(own)].every((state) => states(compound).has(state))
            && compare(specificity(mine), specificity(selector)) > 0;
        }));
        if (!taken) found.push({ where: rule.where, selector, property, value });
      }
    }
  }
  return found;
}

describe('a pressed row opener keeps its overlay the row\'s', () => {
  const all = sheets(SRC).flatMap((file) => rulesOf(readFileSync(file, 'utf8'), path.relative(SRC, file)));

  test('the wall reads the console\'s sheets, the opener\'s own rule among them', () => {
    expect(all.length).toBeGreaterThan(100);
    expect(all.some((rule) => rule.selectors.includes(`.${OPENER}::after`))).toBe(true);
  });

  test('no rule gives a pressed opener a containing block unless a stronger opener rule takes it back', () => {
    expect(offences(all)).toEqual([]);
  });

  test('the wall fails on the press that shipped, and only a stronger opener rule answers it', () => {
    const shipped = 'button { transition: transform 1s; }\nbutton:not(:disabled):active { transform: scale(.97); }';
    expect(offences(rulesOf(shipped, 'app.css'))).toEqual([
      { where: 'app.css', selector: 'button:not(:disabled):active', property: 'transform', value: 'scale(.97)' },
    ]);
    const weaker = `${shipped}\n.${OPENER}:active { transform: none; }`;
    expect(offences(rulesOf(weaker, 'app.css')), '0,2,0 does not beat 0,2,1').toHaveLength(1);
    const answered = `${shipped}\n.myx-dt-open .${OPENER}:active { transform: none; }`;
    expect(offences(rulesOf(answered, 'app.css'))).toEqual([]);
    expect(offences(rulesOf(`.${OPENER} { position: relative; }`, 'kit.css')), 'a positioned opener').toHaveLength(1);
    expect(offences(rulesOf('.myx-dt-primary > * { position: relative; }', 'x.css')), 'a child of its cell').toHaveLength(1);
    expect(offences(rulesOf('.myx-lane-head > * { position: relative; }', 'x.css')), 'a child of no cell').toEqual([]);
  });
});
