// THE CONTRAST WALL: the ink and ground pairs the console prints text, controls, focus and data on clear WCAG AA in BOTH themes
// (docs/design/PRODUCT.md "WCAG AA for text, controls, focus and data inks"). The numbers come from the sheet that ships: tokens.css
// is parsed at test time, never re-typed here, so a second copy cannot agree with itself while the sheet drifts.
//
// The denominator of the text colours is the CSS itself: every `color: var(--x)` under src/ must have a disposition below, or the test
// fails by name, so a colour added to a page cannot skip the wall. Text is held to 4.5:1 (WCAG 1.4.3); controls, focus rings and marks
// that carry state or data to 3:1 (WCAG 1.4.11). Decoration (a separator dot) is not held to either, and says so where it is used.
import { readdirSync, readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, test } from 'vitest';

const src = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', 'src');
const stripComments = (sheet: string): string => sheet.replace(/\/\*[\s\S]*?\*\//g, '');
const cssFiles = (dir: string): string[] =>
  readdirSync(dir, { withFileTypes: true, recursive: true })
    .filter((entry) => entry.isFile() && entry.name.endsWith('.css'))
    .map((entry) => path.join(entry.parentPath, entry.name));
const sheet = stripComments(readFileSync(path.join(src, 'styles/tokens.css'), 'utf8'));

// ---------------------------------------------------------------- the sheet

type Tokens = Record<string, string>;

/** The body of the rule whose selector is exactly `selector`. */
function blockBody(selector: string): string {
  const at = sheet.indexOf(`${selector} {`);
  if (at < 0) throw new Error(`tokens.css: no rule for ${selector}`);
  return sheet.slice(sheet.indexOf('{', at) + 1, sheet.indexOf('\n}', at));
}
const parseTokens = (body: string): Tokens =>
  Object.fromEntries([...body.matchAll(/(--[a-z0-9-]+)\s*:\s*([^;]+);/g)].map(([, name, text]) => [name ?? '', (text ?? '').trim()]));

const shared = parseTokens(blockBody(':root'));
const themes: ReadonlyArray<[name: string, tokens: Tokens]> = ['day', 'night'].map((name) => [name, { ...shared, ...parseTokens(blockBody(`[data-theme='${name}']`)) }]);

// ----------------------------------------------------------------- contrast

type Rgb = [number, number, number];
function rgbOf(color: string): Rgb {
  const hex = /^#([0-9a-f]{3}|[0-9a-f]{6})$/i.exec(color.trim())?.[1];
  if (hex === undefined) throw new Error(`contrast is computed from hex only; got ${color}`);
  const full = hex.length === 3 ? [...hex].map((c) => c + c).join('') : hex;
  const n = parseInt(full, 16);
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
}
function luminance([r, g, b]: Rgb): number {
  const channel = (v: number): number => {
    const s = v / 255;
    return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
  };
  return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b);
}
const ratio = (a: Rgb, b: Rgb): number => {
  const [hi = 0, lo = 0] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (hi + 0.05) / (lo + 0.05);
};
/** `ink` laid at `share` (0..1) over `ground`, as color-mix(in srgb, ink share%, transparent) composites. */
const over = (ink: Rgb, share: number, ground: Rgb): Rgb => [0, 1, 2].map((i) => (ink[i] ?? 0) * share + (ground[i] ?? 0) * (1 - share)) as Rgb;

interface Pairing {
  label: string;
  ink: Rgb;
  ground: Rgb;
  min: number;
}
const failures = (pairings: readonly Pairing[]): string[] => pairings.filter((p) => ratio(p.ink, p.ground) < p.min).map((p) => p.label);

const TEXT_MIN = 4.5;
const MARK_MIN = 3;

function rgb(tokens: Tokens, name: string, theme: string): Rgb {
  const value = tokens[name];
  if (value === undefined) throw new Error(`tokens.css ${theme}: ${name} is not defined`);
  return rgbOf(value);
}

// ------------------------------------------------------ what each ink is for

/** The grounds a card, a page and the sidebar put their text on. */
const PAGE = ['--wall', '--obj', '--obj-sunk', '--obj-hi'];
const GLASS = ['--glass'];

/** Every token the CSS prints as `color: var(--x)`, and what it must clear. `mark` is a non-text ink held to 3:1. */
const TEXT_INK: Record<string, { grounds: readonly string[]; min: number; why?: string }> = {
  '--ink': { grounds: [...PAGE, '--wall-shade'], min: TEXT_MIN },
  '--body': { grounds: [...PAGE, '--wall-shade'], min: TEXT_MIN },
  '--mute': { grounds: PAGE, min: TEXT_MIN },
  '--ok': { grounds: PAGE, min: TEXT_MIN },
  '--wait': { grounds: PAGE, min: TEXT_MIN },
  '--stuck': { grounds: PAGE, min: TEXT_MIN },
  // the check that marks the chosen entry in a menu, which stands on the menu's own card
  '--charge': { grounds: ['--obj'], min: TEXT_MIN },
  '--glass-ink': { grounds: GLASS, min: TEXT_MIN },
  '--glass-dim': { grounds: GLASS, min: TEXT_MIN },
  '--glass-ok': { grounds: GLASS, min: TEXT_MIN },
  '--glass-wait': { grounds: GLASS, min: TEXT_MIN },
  // a label on the ink that fills a selected segment, a label on the vermilion button
  '--obj': { grounds: ['--ink'], min: TEXT_MIN },
  '--charge-ink': { grounds: ['--charge'], min: TEXT_MIN },
  // only a separator dot between two meta facts and the grip icon: decoration and a control glyph, never a word
  '--faint': { grounds: ['--obj', '--obj-sunk', '--obj-hi'], min: MARK_MIN, why: 'separator dots and the grip icon, never text' },
};

const usedAsColor = (): string[] => {
  const used = new Set<string>();
  for (const file of cssFiles(src)) {
    for (const [, name] of stripComments(readFileSync(file, 'utf8')).matchAll(/(?<![-\w])color\s*:\s*var\((--[a-z0-9-]+)\)/g)) used.add(name ?? '');
  }
  return [...used].sort();
};

/** Marks that carry state or data, on the grounds they stand on (1.4.11). */
const MARKS_ON_PAGE: readonly string[] = ['--ok', '--wait', '--stuck', '--charge'];

/** The plan hues. Each stands as a dot or a window edge with the plan's NAME printed beside it, so colour never carries the plan alone;
 *  in the day theme the pastel hues of the design law sit under 3:1 on the paper, which is recorded here rather than hidden. A hue
 *  listed here must still be under 3:1 (a fixed hue must leave the list), and none may get worse than its recorded floor. */
const HUES = ['--claude', '--gpt', '--grok', '--kimi', '--muse', '--local', '--router', '--deepseek'];
const DAY_HUE_FLOOR: Record<string, number> = {
  '--claude': 1.7, '--gpt': 1.5, '--grok': 1.6, '--kimi': 1.3, '--muse': 1.7, '--local': 1.4, '--router': 1.3, '--deepseek': 1.4,
};

function pairingsFor(tokens: Tokens, theme: string): Pairing[] {
  const pairings: Pairing[] = [];
  for (const [ink, { grounds, min }] of Object.entries(TEXT_INK)) {
    for (const ground of grounds) pairings.push({ label: `${theme}: ${ink} on ${ground}`, ink: rgb(tokens, ink, theme), ground: rgb(tokens, ground, theme), min });
  }
  for (const mark of MARKS_ON_PAGE) {
    for (const ground of PAGE) pairings.push({ label: `${theme}: mark ${mark} on ${ground}`, ink: rgb(tokens, mark, theme), ground: rgb(tokens, ground, theme), min: MARK_MIN });
  }
  // the focus ring is 3px of the vermilion, on every ground a control stands on
  for (const ground of [...PAGE, '--wall-shade']) {
    pairings.push({ label: `${theme}: focus ring --charge on ${ground}`, ink: rgb(tokens, '--charge', theme), ground: rgb(tokens, ground, theme), min: MARK_MIN });
  }
  // a field's border: the ink laid at the share --input names, over the ground the field stands on
  const input = /color-mix\(in srgb, var\((--[a-z-]+)\) (\d+)%, transparent\)/.exec(tokens['--input'] ?? '');
  if (input?.[1] === undefined || input[2] === undefined) throw new Error(`${theme}: --input is not an ink mixed over transparent`);
  for (const ground of ['--obj', '--obj-sunk']) {
    const grounded = rgb(tokens, ground, theme);
    pairings.push({ label: `${theme}: field border on ${ground}`, ink: over(rgb(tokens, input[1], theme), Number(input[2]) / 100, grounded), ground: grounded, min: MARK_MIN });
  }
  // a gauge fills with the glass ink over its dark track, and the hatch of a full one reads against it
  for (const ink of ['--glass-ink', '--glass-hatch']) {
    pairings.push({ label: `${theme}: gauge ${ink} on --glass-track`, ink: rgb(tokens, ink, theme), ground: rgb(tokens, '--glass-track', theme), min: MARK_MIN });
  }
  return pairings;
}

// ------------------------------------------------------------------- tests

describe('the colours the CSS prints text in', () => {
  test('every `color: var(--x)` in src/ has a disposition here, and every disposition is used', () => {
    const used = usedAsColor();
    expect(used.filter((name) => TEXT_INK[name] === undefined), 'printed in a colour the wall does not hold').toEqual([]);
    expect(Object.keys(TEXT_INK).filter((name) => !used.includes(name)), 'held but no longer printed in').toEqual([]);
  });
});

describe('WCAG AA in both themes', () => {
  for (const [theme, tokens] of themes) {
    for (const pairing of pairingsFor(tokens, theme)) {
      test(`${pairing.label} >= ${pairing.min}:1`, () => {
        expect(ratio(pairing.ink, pairing.ground)).toBeGreaterThanOrEqual(pairing.min);
      });
    }
  }
});

describe('the plan hues', () => {
  const byTheme = Object.fromEntries(themes);
  test('in the night theme every hue clears 3:1 on the card and on the wall', () => {
    const night = byTheme.night ?? {};
    const pairs = HUES.flatMap((hue) => ['--obj', '--wall'].map((ground): Pairing => ({ label: `night: ${hue} on ${ground}`, ink: rgb(night, hue, 'night'), ground: rgb(night, ground, 'night'), min: MARK_MIN })));
    expect(failures(pairs)).toEqual([]);
  });
  test('in the day theme each hue is still under 3:1 and none is worse than its recorded floor', () => {
    const day = byTheme.day ?? {};
    for (const hue of HUES) {
      const least = Math.min(...['--obj', '--wall'].map((ground) => ratio(rgb(day, hue, 'day'), rgb(day, ground, 'day'))));
      expect(least, `day ${hue} now clears 3:1: remove it from the recorded exceptions`).toBeLessThan(MARK_MIN);
      expect(least, `day ${hue} fell under its recorded floor`).toBeGreaterThanOrEqual(DAY_HUE_FLOOR[hue] ?? Infinity);
    }
  });
});

describe('the wall can fail', () => {
  test('a pair below AA is reported by name', () => {
    const fixture: Pairing = { label: 'fixture: grey ink on grey ground', ink: rgbOf('#8f8f8f'), ground: rgbOf('#7d7d7d'), min: TEXT_MIN };
    expect(failures([fixture])).toEqual([fixture.label]);
  });
  test('a mark under 3:1 is reported by name', () => {
    const faint: Pairing = { label: 'fixture: a pale mark on the paper', ink: rgbOf('#8e919a'), ground: rgbOf('#f7f7f8'), min: MARK_MIN };
    expect(failures([faint])).toEqual([faint.label]);
  });
  test('a pair at the threshold passes', () => {
    expect(failures([{ label: 'fixture: black on white', ink: rgbOf('#000000'), ground: rgbOf('#ffffff'), min: 21 }])).toEqual([]);
  });
  test('the colour a sheet edit would break is reported: --mute darkened to the ground fails its pairs', () => {
    const day = Object.fromEntries(themes).day ?? {};
    const broken = { ...day, '--mute': day['--obj'] ?? '' };
    expect(failures(pairingsFor(broken, 'day')).some((label) => label.includes('--mute'))).toBe(true);
  });
});
