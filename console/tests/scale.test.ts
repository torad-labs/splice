// THE READABLE FLOOR (operator ruling 4, 2026-09-25: "The font and scale is too small, are you
// crazy?" and "Do I have to use a magnifier to be able to see and use this?"). His panels are 3840
// wide at desktop scale 1, where the console was a 1920px island of 13px type. Every size is now
// bounded-fluid between a 1600px frame and a 3840px one (tokens.css), and this file holds the floors
// at BOTH frames, evaluated from the tokens themselves, plus the wall that keeps every other size on
// the fluid unit: a raw rem outside tokens.css would stay 1600-sized on a 3840 screen.
import { readdirSync, readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, test } from 'vitest';

const webui = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const tokens = readFileSync(path.join(webui, 'src/shared/tokens.css'), 'utf8');
const kit = readFileSync(path.join(webui, 'src/shared/ui/kit.css'), 'utf8');

/** A token's declared value, verbatim. */
function declared(name: string): string {
  const match = new RegExp(`${name}:\\s*([^;]+);`).exec(tokens);
  if (match === null) throw new Error(`${name} is not declared in tokens.css`);
  return match[1].trim();
}

/** A token's size in px on a window `width` px wide, at the browser's default 16px rem. It reads
 *  the three shapes tokens.css writes: a rem, a clamp of rem and vw, and a multiple of --u. */
function px(name: string, width: number): number {
  const value = declared(name);
  const rem = /^([\d.]+)rem$/.exec(value);
  if (rem !== null) return Number(rem[1]) * 16;
  const clamp = /^clamp\(([\d.]+)rem, ([\d.]+)rem \+ ([\d.]+)vw, ([\d.]+)rem\)$/.exec(value);
  if (clamp !== null) {
    const [lo, base, vw, hi] = clamp.slice(1).map(Number);
    return Math.min(Math.max(lo * 16, base * 16 + (vw * width) / 100), hi * 16);
  }
  const unit = /^calc\(([\d.]+) \* var\(--u\)\)$/.exec(value);
  if (unit !== null) return Number(unit[1]) * px('--u', width);
  throw new Error(`${name}: ${value} is not a shape the scale reads`);
}

/** The declarations of one rule in the kit's sheet. */
function rule(selector: string): string {
  const at = kit.indexOf(`${selector} {`);
  return at < 0 ? '' : kit.slice(at, kit.indexOf('}', at));
}

function files(dir: string, ext: string): string[] {
  return readdirSync(dir, { withFileTypes: true, recursive: true })
    .filter((entry) => entry.isFile() && entry.name.endsWith(ext))
    .map((entry) => path.join(entry.parentPath, entry.name));
}

describe('the scale holds a readable floor at both frames', () => {
  test('at 1600: captions 14px, table cells 16, body 17, a board row 52, a meter 8', () => {
    expect(px('--text-1', 1600)).toBeGreaterThanOrEqual(14);
    expect(px('--text-2', 1600)).toBeGreaterThanOrEqual(16);
    expect(px('--text-3', 1600)).toBeGreaterThanOrEqual(17);
    expect(px('--row-board', 1600)).toBeGreaterThanOrEqual(52);
    expect(px('--meter-h', 1600)).toBeGreaterThanOrEqual(8);
  });

  test('at 3840: captions 16px, table cells 19, body 20, a meter 10', () => {
    expect(px('--text-1', 3840)).toBeGreaterThanOrEqual(16);
    expect(px('--text-2', 3840)).toBeGreaterThanOrEqual(19);
    expect(px('--text-3', 3840)).toBeGreaterThanOrEqual(20);
    expect(px('--meter-h', 3840)).toBeGreaterThanOrEqual(10);
  });

  test('bounded: nothing past 1.5x at 3840, the 2.5x root the operator rejected on 09-24', () => {
    for (const name of ['--u', '--text-1', '--text-2', '--text-3', '--text-4', '--text-5', '--text-6', '--text-7']) {
      expect(px(name, 3840) / px(name, 1600), name).toBeLessThanOrEqual(1.5);
      expect(px(name, 7680), `${name} holds past 3840`).toBe(px(name, 3840));
    }
  });

  test('the board and the meter read their sizes from the tokens', () => {
    expect(rule('.myx-dt td')).toContain('height: var(--row-board);');
    expect(rule('.myx-meter')).toContain('height: var(--meter-h);');
  });
});

describe('every size outside tokens.css rides the fluid unit', () => {
  // A literal like `min-width: 12rem` in a sheet, or `width: '12rem'` on a column, stays 192px on a
  // 3840 screen while the type beside it grows: the clipping this ruling set out to end.
  const raw = /(?<![\w.-])\d*\.?\d+rem\b/g;

  test('no stylesheet but tokens.css writes a raw rem', () => {
    const offenders = files(path.join(webui, 'src'), '.css')
      .filter((file) => !file.endsWith(path.join('shared', 'tokens.css')))
      .flatMap((file) => (readFileSync(file, 'utf8').replace(/\/\*[\s\S]*?\*\//g, '').match(raw) ?? [])
        .map((hit) => `${path.relative(webui, file)}: ${hit}`));
    expect(offenders).toEqual([]);
  });

  test('no component writes a raw rem in a size string', () => {
    const offenders = files(path.join(webui, 'src'), '.tsx')
      .flatMap((file) => (readFileSync(file, 'utf8').match(/['`]\d*\.?\d+rem['`]/g) ?? [])
        .map((hit) => `${path.relative(webui, file)}: ${hit}`));
    expect(offenders).toEqual([]);
  });
});
