// COLOUR WALL (row M1-01). Every ink/surface pairing the console is allowed to
// put text on clears WCAG AA, in BOTH rooms, and the numbers come from the
// sheet that ships: tokens.css is PARSED at test time, never re-typed here. A
// second copy of the values would agree with itself while the sheet drifted.
//
// The token list is not a hand list either. It is parsed out of section 1 of
// dev/web-console/CONTRACTS.md, which is the lattice every console row builds
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

const TOKENS_CSS = 'webui/src/shared/tokens.css';
const CONTRACTS = 'dev/web-console/CONTRACTS.md';

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
    const target = /kept/i.test(cells[2]) ? shared : bothRooms;
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
const edgeTokens = bothRooms.filter((name) => name.startsWith('--edge-'));

// ---------------------------------------------------------------- the pairs

const TEXT_MIN = 4.5;
const EDGE_MIN = 3;
const PLANE_MIN = 1.6;
const FIELD_MIN = 1.05;

/** ink that must clear AA text contrast on each ground it is printed on */
const TEXT_ON: ReadonlyArray<[ink: string, grounds: string[]]> = [
  ['--ink', ['--room', '--room-deep']],
  ['--ink-mute', ['--room', '--room-deep']],
  ['--ink-strong', ['--room', '--room-deep']],
  ['--strip-ink', ['--strip', '--strip-field']],
  ['--strip-ink-mute', ['--strip', '--strip-field']],
  ['--scope-ink', ['--scope']],
];

/** the holder edge carries attention, so it must be seen on the room and on a strip */
const EDGE_ON = ['--room', '--strip'];

function pairingsFor(tokens: Tokens, room: string): Pairing[] {
  const pairings: Pairing[] = [];
  for (const [ink, grounds] of TEXT_ON) {
    for (const ground of grounds) {
      pairings.push({
        label: `${room}: ${ink} on ${ground}`,
        ink: value(tokens, ink, room),
        ground: value(tokens, ground, room),
        min: TEXT_MIN,
      });
    }
  }
  for (const edge of edgeTokens) {
    for (const ground of EDGE_ON) {
      pairings.push({
        label: `${room}: ${edge} on ${ground}`,
        ink: value(tokens, edge, room),
        ground: value(tokens, ground, room),
        min: EDGE_MIN,
      });
    }
  }
  return pairings;
}

// ------------------------------------------------------- the plane ladder

/**
 * A room is a ladder of planes, and the rungs are GEOMETRY, not taste: the bay
 * paints its own ground (ui.css:310, `background: var(--room-deep)`) and the
 * strip paints its own (ui.css:222, `background: var(--strip)`), so the planes
 * that touch on screen are room|room-deep and room-deep|strip. Below 1.6:1 a
 * boundary between two planes is a gradient, not an edge: the light room shipped
 * a page, a bay floor and a strip all within 1.3:1 of each other and read as one
 * sheet of paper (measured 2026-09-18 off the gate's own captures: strip-field
 * 1.30:1, strip 1.20:1, room-deep 1.14:1 against the room).
 *
 * The field box is a box ON the strip, not a plane: it is carried by its own
 * step and by `--strip-field-line` (the dark room's line measures 1.79:1 on its
 * strip), so it is held to a step and never to the plane floor.
 *
 * The dark room's rail pair is deliberately NOT held to the floor, and the
 * exemption is asserted rather than skipped: tokens.css declares the graphite
 * room one field whose rule, rail and floor between bays all measure
 * #0A0C0D..#0C1010, so room and room-deep stay within a step of each other
 * there. Pulling them apart on the way to a green light room fails that test by
 * name, which is the point: the light room's rungs are not the dark room's.
 */
const PLANES: ReadonlyArray<[room: string, upper: string, lower: string, min: number]> = [
  ['dark', '--room-deep', '--strip', PLANE_MIN],
  ['light', '--room', '--room-deep', PLANE_MIN],
  ['light', '--room-deep', '--strip', PLANE_MIN],
  ['dark', '--strip', '--strip-field', FIELD_MIN],
  ['light', '--strip', '--strip-field', FIELD_MIN],
];

// ------------------------------------------------------------------- tests

describe('the token sheet carries what the contract names', () => {
  test('section 1 of CONTRACTS.md yielded a denominator', () => {
    expect(bothRooms.length).toBeGreaterThan(20);
    expect(edgeTokens).toHaveLength(4);
  });

  for (const [room, tokens] of rooms) {
    test(`${room}: every contract token is defined in this room`, () => {
      const missing = bothRooms.filter((name) => tokens[name] === undefined);
      expect(missing, `missing from the ${room} room`).toEqual([]);
    });
  }

  test('retired tokens named in section 1 survive somewhere in the sheet', () => {
    const missing = shared.filter((name) => !new RegExp(`(^|[;{\\s])${name}\\s*:`).test(css));
    expect(missing, 'retired tokens the old pages still read').toEqual([]);
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

describe('the plane ladder holds in both rooms', () => {
  for (const [room, upper, lower, min] of PLANES) {
    const tokens = room === 'dark' ? dark : light;
    test(`${room}: ${upper} stands off ${lower} by >= ${min}:1`, () => {
      const measured = ratio(value(tokens, upper, room), value(tokens, lower, room));
      expect(measured).toBeGreaterThanOrEqual(min);
    });
  }

  test('dark: the rail stays one field with the room', () => {
    expect(ratio(value(dark, '--room', 'dark'), value(dark, '--room-deep', 'dark'))).toBeLessThan(PLANE_MIN);
  });
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

  test('a flat room is reported by name', () => {
    // Today's values: the light room against the light bay floor, 1.14:1. This is
    // the pair the light block shipped, so the wall is proven against the real
    // defect and not only against a synthetic grey.
    const flat: Pairing = {
      label: 'fixture: light room above the light bay floor',
      ink: '#E0E2DF',
      ground: '#D2D5D1',
      min: PLANE_MIN,
    };
    expect(contrastFailures([flat])).toEqual([flat.label]);
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
