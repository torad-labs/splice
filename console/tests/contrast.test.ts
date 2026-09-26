// COLOUR WALL (row M1-01). Every ink/surface pairing the console is allowed to
// put text on clears WCAG AA, in BOTH rooms, and the numbers come from the
// sheet that ships: tokens.css is PARSED at test time, never re-typed here. A
// second copy of the values would agree with itself while the sheet drifted.
//
// The token list is not a hand list either. It is parsed out of section 1 of
// .dev/campaigns/web-console/CONTRACTS.md, which is the lattice every console row builds
// against, so a token the contract names and the sheet forgets fails here BY
// NAME, and a token added to the contract is picked up without editing this
// file.
//
// The last test is the mutation proof: the same checker, handed a pair that is
// deliberately below AA, must report that pair by name. A wall that cannot fail
// is not a wall.
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, test } from 'vitest';

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const read = (relative: string): string => readFileSync(path.join(repoRoot, relative), 'utf8');

const TOKENS_CSS = 'console/src/shared/tokens.css';
const CONTRACTS = '.dev/campaigns/web-console/CONTRACTS.md';

const DARK_SELECTOR = ':root[data-theme="dark"]';
const LIGHT_SELECTOR = ':root[data-theme="light"]';

/** Comments are prose about the sheet, and they name selectors the sheet itself
 *  defines; dropping them first keeps a selector lookup from landing in a
 *  comment and reporting the wrong block as the room. */
const stripComments = (sheet: string): string => sheet.replace(/\/\*[\s\S]*?\*\//g, '');

const css = stripComments(read(TOKENS_CSS));
const contracts = read(CONTRACTS);

// ---------------------------------------------------------------- the sheet

type Tokens = Record<string, string>;

/** The body of the first rule whose selector list contains `selector`. */
function blockBody(sheet: string, selector: string): string {
  const at = sheet.indexOf(selector);
  if (at < 0) throw new Error(`${TOKENS_CSS}: no rule for ${selector}`);
  const open = sheet.indexOf('{', at);
  let depth = 0;
  for (let i = open; i < sheet.length; i += 1) {
    if (sheet[i] === '{') depth += 1;
    else if (sheet[i] === '}') {
      depth -= 1;
      if (depth === 0) return sheet.slice(open + 1, i);
    }
  }
  throw new Error(`${TOKENS_CSS}: unterminated rule for ${selector}`);
}

/** Every `--name: value;` declared directly in a block body. */
function parseTokens(body: string): Tokens {
  const tokens: Tokens = {};
  for (const [, name, value] of body.matchAll(/(--[a-z0-9-]+)\s*:\s*([^;]+);/g)) {
    tokens[name] = value.trim();
  }
  return tokens;
}

const dark = parseTokens(blockBody(css, DARK_SELECTOR));
const light = parseTokens(blockBody(css, LIGHT_SELECTOR));
const rooms: ReadonlyArray<[name: string, tokens: Tokens]> = [
  ['dark', dark],
  ['light', light],
];

/** The value a pairing must resolve to; a missing token is a failure with a name. */
function value(tokens: Tokens, name: string, room: string): string {
  const found = tokens[name];
  if (found === undefined) throw new Error(`tokens.css ${room} room: ${name} is not defined`);
  return found;
}

// ----------------------------------------------------------------- contrast

function luminance(color: string): number {
  const hex = color.trim();
  if (!/^#[0-9a-fA-F]{6}$/.test(hex)) {
    throw new Error(`contrast is computed from hex only; got ${color} (use a hex token)`);
  }
  const n = parseInt(hex.slice(1), 16);
  const channel = (v: number): number => {
    const s = v / 255;
    return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
  };
  return (
    0.2126 * channel((n >> 16) & 255) +
    0.7152 * channel((n >> 8) & 255) +
    0.0722 * channel(n & 255)
  );
}

function ratio(foreground: string, background: string): number {
  const [hi, lo] = [luminance(foreground), luminance(background)].sort((a, b) => b - a);
  return (hi + 0.05) / (lo + 0.05);
}

interface Pairing {
  /** printed on failure, so the wall names what broke */
  label: string;
  ink: string;
  ground: string;
  min: number;
}

/** The pairings that fail, by label. Empty is the pass. */
function contrastFailures(pairings: readonly Pairing[]): string[] {
  return pairings
    .filter((p) => ratio(p.ink, p.ground) < p.min)
    .map((p) => p.label);
}

// ------------------------------------------------- the contract's token set

/**
 * Section 1 of CONTRACTS.md carries the token tables. Its first column is the
 * denominator: every token named there, with `--a` .. `--b` ranges expanded.
 * The "kept, current values" row names retired tokens that live in the shared
 * block rather than in both rooms, so it is collected separately.
 */
function contractTokens(markdown: string): { bothRooms: string[]; shared: string[] } {
  const start = markdown.indexOf('## 1. Tokens');
  const end = markdown.indexOf('## 2. Primitives');
  if (start < 0 || end < 0) {
    throw new Error(`${CONTRACTS}: section 1 (Tokens) or section 2 (Primitives) is missing`);
  }
  const bothRooms = new Set<string>();
  const shared = new Set<string>();
  const range = /`(--[a-z0-9-]+)`(?:\s*\.\.\s*`(--[a-z0-9-]+)`)?/g;

  for (const line of markdown.slice(start, end).split('\n')) {
    if (!line.trimStart().startsWith('|')) continue;
    const cells = line.split('|');
    if (cells.length < 4) continue;
    const named = [...cells[1].matchAll(range)];
    if (named.length === 0) continue;
    const target = /kept|shared/i.test(cells[2]) ? shared : bothRooms;
    for (const [, from, to] of named) {
      target.add(from);
      if (to !== undefined) {
        const head = from.slice(0, from.lastIndexOf('-') + 1);
        const first = Number(from.slice(head.length));
        const last = Number(to.slice(to.lastIndexOf('-') + 1));
        for (let i = first; i <= last; i += 1) target.add(`${head}${i}`);
      }
    }
  }
  for (const name of bothRooms) shared.delete(name);
  return { bothRooms: [...bothRooms].sort(), shared: [...shared].sort() };
}

const { bothRooms, shared } = contractTokens(contracts);
const headTokens = bothRooms.filter((name) => name.startsWith('--head-'));

// ---------------------------------------------------------------- the pairs

const TEXT_MIN = 4.5;
/** A mark that carries data or state -- a head's bar, a chart series, a status dot -- is non-text
 *  content: WCAG 1.4.11 holds it to 3:1 against what it stands on. */
const MARK_MIN = 3;

/** The grounds text and marks stand on: the page, the raised plane (sidebar, panels, table heads),
 *  a row under the pointer, a selected row. */
const GROUNDS = ['--bg', '--bg-raised', '--bg-hover', '--bg-active'];

/** ink that must clear AA text contrast on each ground it is printed on */
const TEXT_ON: ReadonlyArray<[ink: string, grounds: string[]]> = [
  ['--fg', GROUNDS],
  ['--fg-muted', GROUNDS],
  ['--fg-subtle', GROUNDS],
  // a status colour prints its own word (a badge, a warn figure)
  ['--ok', ['--bg', '--bg-raised']],
  ['--warn', ['--bg', '--bg-raised']],
  ['--danger', ['--bg', '--bg-raised']],
  // the primary button's label on the ink
  ['--accent-fg', ['--accent']],
];

/** marks: every head hue, the neutral head, the chart greys and the status dots, on the two grounds
 *  a chart or a row stands on */
const MARK_ON: ReadonlyArray<[mark: string, grounds: string[]]> = [
  ...[...headTokens, '--series-1', '--series-2', '--series-3', '--ok', '--warn', '--danger']
    .map((mark): [string, string[]] => [mark, ['--bg', '--bg-raised']]),
];

function pairingsFor(tokens: Tokens, room: string): Pairing[] {
  const pairings: Pairing[] = [];
  for (const [list, min] of [[TEXT_ON, TEXT_MIN], [MARK_ON, MARK_MIN]] as const) {
    for (const [ink, grounds] of list) {
      for (const ground of grounds) {
        pairings.push({
          label: `${room}: ${ink} on ${ground}`,
          ink: value(tokens, ink, room),
          ground: value(tokens, ground, room),
          min,
        });
      }
    }
  }
  return pairings;
}

// The strip-bay world held its planes apart by luminance steps (a ladder of room, bay floor and
// strip, each 1.6:1 off the next). The redesign separates planes with a hairline instead (DESIGN.md
// section 8), so no ground is held to a step from another and the ladder is gone with the world.

// ------------------------------------------------------------------- tests

describe('the token sheet carries what the contract names', () => {
  test('section 1 of CONTRACTS.md yielded a denominator', () => {
    expect(bothRooms.length).toBeGreaterThan(20);
    expect(headTokens).toHaveLength(25); // --head-1 .. --head-8, their -up and -down tones, --head-none
  });

  for (const [room, tokens] of rooms) {
    test(`${room}: every contract token is defined in this room`, () => {
      const missing = bothRooms.filter((name) => tokens[name] === undefined);
      expect(missing, `missing from the ${room} room`).toEqual([]);
    });
  }

  test('the shared scale tokens section 1 names are declared in the sheet', () => {
    const missing = shared.filter((name) => !new RegExp(`(^|[;{\\s])${name}\\s*:`).test(css));
    expect(missing, 'shared tokens the contract names').toEqual([]);
  });
});

describe('WCAG AA in both rooms', () => {
  for (const [room, tokens] of rooms) {
    for (const pairing of pairingsFor(tokens, room)) {
      test(`${pairing.label} >= ${pairing.min}:1`, () => {
        expect(ratio(pairing.ink, pairing.ground)).toBeGreaterThanOrEqual(pairing.min);
      });
    }
  }
});

describe('the wall can fail', () => {
  test('a pair below AA is reported by name', () => {
    const fixture: Pairing = {
      label: 'fixture: grey ink on grey ground',
      ink: '#8f8f8f',
      ground: '#7d7d7d',
      min: 4.5,
    };
    expect(contrastFailures([fixture])).toEqual([fixture.label]);
  });

  test('a mark under 3:1 is reported by name', () => {
    // The light neutral head as it first shipped: #8e919a on the raised ground, 2.94:1.
    const faint: Pairing = {
      label: 'fixture: light --head-none on --bg-raised',
      ink: '#8e919a',
      ground: '#f7f7f8',
      min: MARK_MIN,
    };
    expect(contrastFailures([faint])).toEqual([faint.label]);
  });

  test('a pair at the threshold passes', () => {
    const atThreshold: Pairing = {
      label: 'fixture: black on white',
      ink: '#000000',
      ground: '#ffffff',
      min: 21,
    };
    expect(contrastFailures([atThreshold])).toEqual([]);
  });
});
