// WCAG AA verification for every ink/surface pairing the dashboard actually
// uses, in BOTH Plate registers (locked design gate: "AA verified in BOTH
// registers for text, controls, focus, data inks"). Token values are pinned
// from the vendored torad-tokens.css; if the sheet is re-vendored with new
// pigments, this suite re-verifies the pairings.
import { describe, expect, test } from 'vitest';

// primitives (torad-tokens.css)
const C = {
  paper0: '#fbf3df', paper50: '#efe6d2', paper100: '#e7dcc2', paper200: '#e0d4b8',
  ink900: '#241d14', ink700: '#5d5240', ink500: '#635741',
  obsidian900: '#1c1610', obsidian800: '#241a12', obsidian700: '#150f09',
  mist100: '#efe2c6', mist400: '#b6a888', mist500: '#968969',
  vermilion700: '#a62815', vermilion800: '#8f2412', vermilion400: '#e2563b', vermilion300: '#f06a4c',
  prussian600: '#1c3c5c', prussian400: '#6fa8cf',
  verdigris600: '#2c6555', verdigris400: '#5bb59c',
  ormolu600: '#7d5419', ormolu400: '#e7c069',
  red600: '#a53b39', red400: '#e85d5d',
};

function luminance(hex: string): number {
  const n = parseInt(hex.slice(1), 16);
  const chan = (v: number) => {
    const s = v / 255;
    return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
  };
  return 0.2126 * chan((n >> 16) & 255) + 0.7152 * chan((n >> 8) & 255) + 0.0722 * chan(n & 255);
}

function ratio(fg: string, bg: string): number {
  const [a, b] = [luminance(fg), luminance(bg)].sort((x, y) => y - x);
  return (a + 0.05) / (b + 0.05);
}

// every (ink, surface) pair the .myx-* composition puts text on
const PAIRINGS: Array<[label: string, fg: string, bg: string]> = [
  // light register (paper): body/strong/mute on surface, raised panels, sunk wells, control fields
  ['light ink-body on surface', C.ink700, C.paper50],
  ['light ink-body on raised', C.ink700, C.paper100],
  ['light ink-body on control', C.ink700, C.paper0],
  ['light ink-strong on raised', C.ink900, C.paper100],
  ['light ink-mute on surface', C.ink500, C.paper50],
  ['light ink-mute on raised', C.ink500, C.paper100],
  ['light ink-mute on sunk', C.ink500, C.paper200],
  ['light accent (links/active tab) on surface', C.vermilion700, C.paper50],
  ['light accent on raised', C.vermilion700, C.paper100],
  ['light on-accent on accent (primary btn)', C.paper0, C.vermilion700],
  ['light on-accent on accent-bright (btn hover)', C.paper0, C.vermilion800],
  ['light data-pos on raised', C.verdigris600, C.paper100],
  ['light data-neg on raised', C.red600, C.paper100],
  ['light data-info on raised', C.prussian600, C.paper100],
  ['light data-amber on raised', C.ormolu600, C.paper100],
  ['light focus ring on surface', C.vermilion700, C.paper50],
  // dark register (observatory)
  ['dark ink-body on surface', C.mist400, C.obsidian900],
  ['dark ink-body on raised', C.mist400, C.obsidian800],
  ['dark ink-strong on raised', C.mist100, C.obsidian800],
  ['dark ink-mute on surface', C.mist500, C.obsidian900],
  ['dark ink-mute on raised', C.mist500, C.obsidian800],
  ['dark ink-mute on sunk', C.mist500, C.obsidian700],
  ['dark accent on surface', C.vermilion400, C.obsidian900],
  ['dark accent on raised', C.vermilion400, C.obsidian800],
  ['dark on-accent on accent (primary btn)', C.obsidian900, C.vermilion400],
  ['dark on-accent on accent-bright (btn hover)', C.obsidian900, C.vermilion300],
  ['dark data-pos on raised', C.verdigris400, C.obsidian800],
  ['dark data-neg on raised', C.red400, C.obsidian800],
  ['dark data-info on raised', C.prussian400, C.obsidian800],
  ['dark data-amber on raised', C.ormolu400, C.obsidian800],
  ['dark focus ring on surface', C.vermilion400, C.obsidian900],
];

describe('WCAG AA (4.5:1 normal text) across both registers', () => {
  for (const [label, fg, bg] of PAIRINGS) {
    test(label, () => {
      expect(ratio(fg, bg)).toBeGreaterThanOrEqual(4.5);
    });
  }
});
