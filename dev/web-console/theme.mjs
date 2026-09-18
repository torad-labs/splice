#!/usr/bin/env node
// theme — the ONE place a theme is seeded, and the ONE place a capture proves which theme it made.
//
// WHY THIS EXISTS. M1-33 found that no leg of the exit gate seeds a theme, so the light room was
// unobserved by every automatic instrument. M1-35 reported that as a missing capability and was
// wrong: light PNGs already existed in the tree, because THREE seats had each rebuilt theme-seeded
// capture privately inside their own row instrument (review/ink/probe-ink.mjs,
// review/light-3840/capture-light.mjs, review/coverage/coverage.mjs). The defect was not missing
// capability, it was UNSHARED capability - which is worse, because every seat believes the
// limitation is real and none of them can see that their neighbour solved it. This file is that
// capability, in the shared toolbox, so the next row does not rebuild a fourth copy.
//
// THE SEED MUST LAND BEFORE THE DOCUMENT RUNS. The theme feature reads `splice.theme` at module
// scope on boot, so a seed applied after load paints the first frame in the WRONG theme and
// produces a capture that is a lie which looks right. lib/cdp.mjs already seeds through
// Page.addScriptToEvaluateOnNewDocument, which runs before any page script - so the correct recipe
// is to pass the theme as a `values` entry to withChrome, and that is all this module does. The
// bug M1-35 hit was writing the value with Runtime.evaluate after show(): both themes then rendered
// dark and the tool printed two identical rows.
//
// AND A CAPTURE THAT CANNOT PROVE ITS THEME IS WORTH LESS THAN NO CAPTURE, because it will be
// believed. So `themedCapture` reads the rendered room back out of the page and refuses to write a
// frame when the theme it asked for is not the theme the page reports. That refusal is the whole
// point: `--strip-field: #FFFFFF` survived three nights on the plane the light room shows most of,
// unphotographed by anything.
//
// Usage: node dev/web-console/theme.mjs --selftest
//        node dev/web-console/theme.mjs '<url>' [--theme both|light|dark] [--out DIR] [--width W --height H]
import { mkdirSync } from 'node:fs';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import process from 'node:process';
import { mgmtKey, shoot, show, withChrome } from './lib/cdp.mjs';
import { decodePng } from './lib/png.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '..', '..');

export const THEMES = ['dark', 'light'];

/** The room each theme renders, measured off the approved comps (tokens.css, section 1). A capture
 *  is only accepted when the room it renders is the room its theme declares. */
export const ROOM = { dark: [11, 14, 14], light: [224, 226, 223] };

/** The values one themed browser session needs. Anything else the caller wants seeded rides along. */
export function themeValues(theme, extra = {}) {
  if (!THEMES.includes(theme)) {
    throw new Error(`unknown theme '${theme}' - one of: ${THEMES.join(', ')}`);
  }
  return { 'myx-mgmt-key': mgmtKey(), 'splice.theme': theme, ...extra };
}

/** What the page says it rendered: the room colour and the theme attribute, read from the DOM. */
const PROBE = `(() => {
  const root = getComputedStyle(document.documentElement);
  const room = root.getPropertyValue('--room').trim();
  return JSON.stringify({
    attr: document.documentElement.getAttribute('data-theme'),
    room,
    scheme: root.colorScheme,
  });
})()`;

/** Parse `#rrggbb` or `rgb(r, g, b)` into three channels. */
export function channels(value) {
  const hex = value.match(/^#([0-9a-f]{6})$/i);
  if (hex !== null) {
    const n = parseInt(hex[1], 16);
    return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
  }
  const rgb = value.match(/rgba?\(\s*(\d+)[,\s]+(\d+)[,\s]+(\d+)/i);
  if (rgb !== null) return [Number(rgb[1]), Number(rgb[2]), Number(rgb[3])];
  return null;
}

/** Did the page render the theme we asked for? Compared on the room, not on the attribute alone:
 *  the attribute says what was requested, the room says what resolved. */
export function themeLanded(theme, probe) {
  const seen = channels(probe.room);
  if (seen === null) return { ok: false, why: `the room resolved to '${probe.room}', which is not a colour` };
  const want = ROOM[theme];
  const distance = Math.abs(seen[0] - want[0]) + Math.abs(seen[1] - want[1]) + Math.abs(seen[2] - want[2]);
  if (distance > 30) {
    return { ok: false, why: `asked for ${theme} (room ${want.join(',')}) and the page rendered ${seen.join(',')}` };
  }
  return { ok: true, why: `room ${seen.join(',')} is the ${theme} room`, seen };
}

/** Capture one address in one theme, and REFUSE to write a frame that cannot prove its theme. */
export async function themedCapture(url, out, width, height, theme, settleMs = 6000) {
  mkdirSync(dirname(out), { recursive: true });
  return withChrome(themeValues(theme), async (send) => {
    await show(send, url, width, height, settleMs);
    const probe = JSON.parse((await send('Runtime.evaluate', { expression: PROBE, returnByValue: true })).result.value);
    const verdict = themeLanded(theme, probe);
    if (!verdict.ok) throw new Error(`the seed did not land: ${verdict.why}`);
    const bytes = await shoot(send, out);
    return { out, theme, room: verdict.seen, attr: probe.attr, bytes: bytes.length };
  });
}

/** The most common colour of a frame: used to prove a plane, e.g. the light field box. */
export function plane(bytes) {
  const png = decodePng(bytes);
  const tally = new Map();
  for (let i = 0; i < png.pixels.length; i += png.channels * 4) {
    const key = `${png.pixels[i]},${png.pixels[i + 1]},${png.pixels[i + 2]}`;
    tally.set(key, (tally.get(key) ?? 0) + 1);
  }
  const [colour, count] = [...tally.entries()].sort((a, b) => b[1] - a[1])[0];
  const [r, g, b] = colour.split(',').map(Number);
  return { r, g, b, hex: '#' + [r, g, b].map((v) => v.toString(16).padStart(2, '0')).join(''), share: count };
}

const ARGS = process.argv.slice(2);
const flag = (n, d) => { const i = ARGS.indexOf(`--${n}`); return i === -1 ? d : ARGS[i + 1]; };
const VALUED = new Set(['--theme', '--out', '--width', '--height']);
const positional = ARGS.filter((a, i) => !a.startsWith('--') && !(i > 0 && VALUED.has(ARGS[i - 1])));

/** The mutation proof: a bogus theme must be REFUSED, and the two real themes must be told apart. */
async function selftest() {
  let pass = 0; let fail = 0;
  const check = (name, ok, detail) => {
    console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${name}  (${detail})`);
    ok ? pass++ : fail++;
  };
  for (const theme of THEMES) {
    const landed = themeLanded(theme, { room: `rgb(${ROOM[theme].join(', ')})`, attr: theme });
    check(`‘${theme}’ is accepted when the room matches`, landed.ok, landed.why);
    const other = theme === 'dark' ? 'light' : 'dark';
    const wrong = themeLanded(theme, { room: `rgb(${ROOM[other].join(', ')})`, attr: theme });
    check(`‘${theme}’ is REFUSED when the room is the other theme`, !wrong.ok, wrong.why);
  }
  try {
    themeValues('nonsense');
    check('an unknown theme throws', false, 'it did not throw');
  } catch (error) {
    check('an unknown theme throws', true, error.message);
  }
  const unparseable = themeLanded('dark', { room: 'transparent', attr: null });
  check('an unreadable room is refused rather than assumed', !unparseable.ok, unparseable.why);
  console.log(`\nselftest: ${pass} passed, ${fail} failed`);
  process.exit(fail === 0 ? 0 : 1);
}

if (process.argv.includes('--selftest')) await selftest();

const url = positional[0];
if (url === undefined || ARGS.includes('--help')) {
  console.log("usage: node dev/web-console/theme.mjs '<url>' [--theme both|light|dark] [--out DIR] [--width W --height H]");
  console.log('       node dev/web-console/theme.mjs --selftest');
  process.exit(url === undefined && !ARGS.includes('--help') ? 2 : 0);
}

const wanted = flag('theme', 'both') === 'both' ? THEMES : [flag('theme', 'dark')];
const outDir = resolve(ROOT, flag('out', 'webui/.impeccable/review/theme'));
const width = Number(flag('width', '1536'));
const height = Number(flag('height', '1024'));
const slug = (url.replace(/[^a-z0-9]+/gi, '-').replace(/^-|-$/g, '') || 'root').toLowerCase();

for (const theme of wanted) {
  const out = resolve(outDir, `${theme}-${width}.png`);
  try {
    const result = await themedCapture(url, out, width, height, theme);
    console.log(`ok   ${theme.padEnd(5)} ${out}  room ${result.room.join(',')}  attr ${result.attr ?? '-'}`);
  } catch (error) {
    console.log(`FAIL ${theme.padEnd(5)} ${error.message}`);
    process.exitCode = 1;
  }
}
