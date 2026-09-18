// THE TONAL CENSUS — how much of a frame is neither of its two flat grounds.
//
// WHY THIS EXISTS: the operator's own words are that there is no wow to the console (the design
// review's E4), and the only number anyone had attached to that was a hand census: the comp is
// 11.9 percent mid-tone, the build of that same comp 7.9 rising to 10.1 after M1-12, and the other
// twelve pages 3.3. A console that is 96.7 percent two flat values is what "no wow" measures as.
// This reproduces that census over the fresh captures, and it measures the COMP in the same run by
// the same code, so the denominator is the artefact and not a previous note.
//
// THE BINNING RULE, stated once and applied to every frame including the comp's:
//   A frame's two grounds are its ROOM and its STRIP. A pixel is FLAT when the ground it is NEAREST
//   to is within TOLERANCE of it on every channel; the nearest-ground assignment is what stops the
//   two discs double-counting a pixel where they overlap, which they do in the light room, whose
//   room and strip are only 22 steps apart. The frame's mid-tone share is the fraction of pixels
//   that are NOT flat. TOLERANCE is 8 of 255.
//
// WHAT THIS RULE IS NOT, measured before shipping it: it does NOT reproduce the hand census the row
// quotes (comp 11.9 percent, the build of that comp 7.9 rising to 10.1, the other twelve pages
// 3.3). On the same comp it reads 35.5 percent, and it needs a tolerance of 32 to reach the
// review's 12.6 -- because the comp is a rendered raster whose dark ground is GRAIN, spread over
// more than 8 steps, while a capture of the build is flat to within one. Nor is the tolerance
// free to rise: at 24 the light room's two grounds overlap and at 32 the census double-counts them
// into negative mid-tones. The two numbers are reported together in the punch list as a finding
// about the metric, which is what the row asks for when its instruments disagree: the reviewer's
// 11.9 percent is a clustered palette share (spec.json's own coverage of its top two entries, which
// this file reproduces exactly as 1 - 0.6407 - 0.24), and this file's is a per-pixel distance at a
// stated tolerance. They are different measurements and neither is the other's check.
//
// WHERE THE GROUNDS COME FROM, per frame, so nothing is hand-typed:
//   a build frame  -- the room and strip the sheet declares for that frame's theme, read out of
//                     webui/src/shared/tokens.css at run time
//   the comp       -- its own two dominant palette entries, read out of build/spec.json
//
// Usage: node dev/web-console/census/tonal-census.mjs [--json]
import { readFileSync, readdirSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { colorFraction, decodePng, hexToRgb } from '../lib/png.mjs';

const ROOT = resolve(import.meta.dirname, '../../..');
const CAPTURES = join(ROOT, 'webui/.impeccable/review/census');
const TOLERANCE = 8;

/** The room and strip a theme declares, parsed from the sheet that ships. */
function groundsFromSheet() {
  const css = readFileSync(join(ROOT, 'webui/src/shared/tokens.css'), 'utf8').replace(/\/\*[\s\S]*?\*\//g, '');
  const body = (selector) => {
    const at = css.indexOf(selector);
    const open = css.indexOf('{', at);
    let depth = 0;
    for (let i = open; i < css.length; i += 1) {
      if (css[i] === '{') depth += 1;
      else if (css[i] === '}') { depth -= 1; if (depth === 0) return css.slice(open + 1, i); }
    }
    throw new Error(`tokens.css: no rule for ${selector}`);
  };
  const parse = (text) => {
    const tokens = {};
    for (const [, name, value] of text.matchAll(/(--[a-z0-9-]+)\s*:\s*([^;]+);/g)) tokens[name] = value.trim();
    return tokens;
  };
  const dark = parse(body(':root[data-theme="dark"]'));
  const light = parse(body(':root[data-theme="light"]'));
  for (const [theme, tokens] of [['dark', dark], ['light', light]]) {
    for (const name of ['--room', '--strip']) {
      if (!/^#[0-9a-fA-F]{6}$/.test(tokens[name] ?? '')) throw new Error(`tokens.css ${theme}: ${name} is not a hex`);
    }
  }
  return { dark: { room: dark['--room'], strip: dark['--strip'] }, light: { room: light['--room'], strip: light['--strip'] } };
}

/** The comp's own two grounds, from the measured spec rather than from a note. */
function groundsFromComp() {
  const spec = JSON.parse(readFileSync(join(ROOT, 'webui/.impeccable/build/spec.json'), 'utf8'));
  const [first, second] = [...spec.palette].sort((a, b) => b.coverage - a.coverage);
  return { room: first.hex, strip: second.hex, comp: spec.comp, size: spec.compSize };
}

/** The mid-tone share of one PNG against one pair of grounds, by nearest ground. */
function census(file, roomHex, stripHex) {
  const png = decodePng(readFileSync(file));
  const room = hexToRgb(roomHex);
  const strip = hexToRgb(stripHex);
  let flat = 0;
  let sampled = 0;
  for (let i = 0; i < png.pixels.length; i += png.channels * 3) {
    const pixel = [png.pixels[i], png.pixels[i + 1], png.pixels[i + 2]];
    const step = (ground) => Math.max(...[0, 1, 2].map((c) => Math.abs(pixel[c] - ground[c])));
    const nearest = Math.min(step(room), step(strip));
    if (nearest <= TOLERANCE) flat += 1;
    sampled += 1;
  }
  return { flat: flat / sampled, mid: 1 - flat / sampled };
}

const sheet = groundsFromSheet();
const comp = groundsFromComp();
const rows = [];

const compPng = join(ROOT, `webui/.impeccable/mocks/${comp.comp.replace(/^.*mocks\//, '')}`);
rows.push({
  frame: `comp ${comp.comp.split('/').pop()}`, theme: 'dark', room: comp.room, strip: comp.strip,
  ...census(compPng, comp.room, comp.strip), comp: true,
});

// WHAT THE CAPTURE ITSELF SAID IT PHOTOGRAPHED (M1-32). The gate writes a three-way state per
// frame -- page, empty, nothing -- and this census READS it rather than inferring one, because the
// failure mode it guards against is a number computed from an honest empty without being told.
const states = new Map();
try {
  for (const row of JSON.parse(readFileSync(join(CAPTURES, 'manifest.json'), 'utf8'))) {
    states.set(row.file, { state: row.state ?? 'unknown', captureFailed: row.captureFailed === true });
  }
} catch { /* a census can run without a manifest; every frame then reports state 'unknown' */ }

const captures = readdirSync(CAPTURES).filter((name) => /-(dark|light)-\d+x\d+\.png$/.test(name)).sort();
for (const name of captures) {
  const theme = name.includes('-light-') ? 'light' : 'dark';
  const grounds = sheet[theme];
  const said = states.get(name) ?? { state: 'unknown', captureFailed: false };
  rows.push({ frame: name, theme, room: grounds.room, strip: grounds.strip, ...census(join(CAPTURES, name), grounds.room, grounds.strip), comp: false, state: said.state });
}

const pct = (value) => `${(value * 100).toFixed(1)}%`;
if (process.argv.includes('--json')) {
  console.log(JSON.stringify({ tolerance: TOLERANCE, rows }, null, 1));
} else {
  console.log(`tonal census, binning rule: flat = within ${TOLERANCE}/255 of the frame's room or strip on every channel\n`);
  console.log(`  ${'frame'.padEnd(34)} ${'room'.padEnd(9)} ${'strip'.padEnd(9)} ${'flat'.padStart(6)} ${'mid'.padStart(6)}  ${'capture said'}`);
  for (const row of rows) {
    console.log(`  ${row.frame.padEnd(34)} ${row.room.padEnd(9)} ${row.strip.padEnd(9)} ${pct(row.flat).padStart(6)} ${pct(row.mid).padStart(6)}  ${row.comp ? 'the comp' : row.state}`);
  }
  const compRow = rows.find((row) => row.comp);
  const build = rows.filter((row) => !row.comp);
  const empty = build.filter((row) => row.state === 'empty');
  const failed = build.filter((row) => row.state === 'nothing');
  const byAddress = new Map();
  for (const row of build) {
    const address = row.frame.split('-')[0];
    byAddress.set(address, Math.max(byAddress.get(address) ?? 0, row.mid));
  }
  const worst = [...byAddress].sort((a, b) => b[1] - a[1]);
  console.log(`\n  comp mid-tone                                  ${pct(compRow.mid)}`);
  console.log(`  build mid-tone, best frame per address:`);
  for (const [address, mid] of worst) console.log(`    ${address.padEnd(12)} ${pct(mid)}`);
  if (empty.length > 0 || failed.length > 0) {
    console.log(`\n  FRAMES THE CAPTURE DID NOT CALL A PAGE (not comparable to the comp, and named so no`);
    console.log(`  number here is read as one):`);
    for (const row of empty) console.log(`    EMPTY   ${row.frame}  mid ${pct(row.mid)}`);
    for (const row of failed) console.log(`    NOTHING ${row.frame}  (no frame was written)`);
  }
  console.log(`\n  comp grounds ${comp.room} / ${comp.strip} (spec.json palette, measured off ${comp.comp.split('/').pop()})`);
  console.log(`  build grounds per theme from tokens.css: dark ${sheet.dark.room} / ${sheet.dark.strip}, light ${sheet.light.room} / ${sheet.light.strip}`);
}
