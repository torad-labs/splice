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
// M1-76 DISPOSITION — the two ways a check can be decorative, answered for this file. CLEAN BOTH.
//   SHAPE ONE, does every FAIL reach the exit code? YES. The CLI's per-theme catch prints
//     `FAIL <theme> <message>` and sets `process.exitCode = 1` on the same line — it does not
//     `continue` past a failure silently. themeValues() THROWS on an unknown theme rather than
//     defaulting, which is what makes `--theme nonsense` a refusal instead of a frame labelled with
//     a room nobody photographed. The selftest's own `fail` counter drives its exit.
//   SHAPE TWO, if the tree went empty, what would it print? IT ASSERTS ITS DENOMINATOR: the wall
//     case is `tree.size > 0 && live.unexempt.length === 0`, so a walk that returned nothing FAILS
//     rather than reporting one implementation in an empty toolbox. That conjunct is the whole
//     lesson of M1-49 one row later, and it is why this wall cannot be defeated by breaking the
//     walk. The CLI loop's denominator (`wanted`) is THEMES or a single named theme and cannot be
//     empty; an unknown name throws rather than producing an empty set.
//   THE WALL IS CURRENTLY RED AND NOT BECAUSE OF THIS FILE: look-gate.mjs:480 seeds the key inline
//     instead of through themeValues(), so `--selftest` reports 15/1 SECOND IMPLEMENTATION. That is
//     M1-74's fence and is reported in the M1-76 notes rather than reached into. The wall firing on
//     a real second implementation is the wall working.
//
// Usage: node .dev/web-console/theme.mjs --selftest
//        node .dev/web-console/theme.mjs '<url>' [--theme both|light|dark] [--out DIR] [--width W --height H]
import { mkdirSync } from 'node:fs';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import process from 'node:process';
import { mgmtKey, shoot, show, withChrome } from './lib/cdp.mjs';
import { decodePng } from './lib/png.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '..', '..');

export const THEMES = ['dark', 'light'];

/** The localStorage key the app reads at boot. Declared here and NOWHERE ELSE in .dev/web-console —
 *  the wall at the bottom of this file is what keeps that true. */
const SEED_KEY = 'splice.theme';

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

/**
 * The seed script itself, for a session that must change theme WITHOUT reopening the browser.
 *
 * gate.mjs is that caller and it is the reason this export exists. It drives one Chrome across
 * every address in both themes, so it cannot pass the theme to withChrome — those values are seeded
 * once at session start — and it re-registers the script on each theme change, removing the
 * previous one first. That is a legitimately different shape from themeValues(), and before M1-60
 * it was met by writing the key literal a fourth time. THE KEY NAME NOW EXISTS IN THIS FILE ONLY.
 * Validated like everything else here: an unknown theme throws rather than seeding a string the app
 * will silently ignore while rendering the default room.
 */
export function themeSeedSource(theme) {
  if (!THEMES.includes(theme)) {
    throw new Error(`unknown theme '${theme}' - one of: ${THEMES.join(', ')}`);
  }
  return `try { localStorage.setItem(${JSON.stringify(SEED_KEY)}, ${JSON.stringify(theme)}); } catch (e) {}`;
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

// ---------------------------------------------------------------- the wall
//
// A RULE THAT LIVES ONLY IN A HEADER COMMENT HAS NOW LOST FOUR TIMES. Three seats rebuilt
// theme-seeded capture privately (review/ink/probe-ink.mjs, review/light-3840/capture-light.mjs,
// review/coverage/coverage.mjs), this file was written to stop a fourth, and snapshot.mjs — edited
// in this file's OWN row and receipt — went on seeding inline anyway. The comment at the top of
// this file asked nicely and was ignored by the very next commit. So the rule is executable now.
//
// THE IDIOM IS THE KEY NAME. A tool seeds a theme by writing the key into localStorage before the
// document runs; that string is the thing no file but this one may contain (SEED_KEY, declared at
// the top beside THEMES). lib/cdp.mjs is exempt BY CONSTRUCTION rather than by name: it seeds
// whatever key/value pairs it is handed and never names this one, which is exactly why
// themeValues() hands them to it — the kind of exemption a file cannot claim by asking for it.
//
// AND THERE IS NO EXEMPTION TABLE. M1-60 shipped one for gate.mjs, the fourth copy, which sat
// outside the row's original fence; the orchestrator widened the fence instead, and the reason is
// the right one — a mechanism built to defer a three-line change is more machinery than the change,
// and a wall with one exemption teaches the next seat that the wall is negotiable.

/**
 * Every file that spells the seeding key, from the tree. `files` is path -> text so the selftest can
 * drive it with synthetic input; the caller supplies the denominator, and it walks a DIRECTORY —
 * never a list — for the reason M1-49 spent a row on.
 */
export function seedingSites(files) {
  return [...files.entries()]
    .filter(([path, text]) => !path.endsWith('theme.mjs') && text.includes(`'${SEED_KEY}'`))
    .map(([path]) => path)
    .sort();
}

/** The verdict: any file but this one that spells the key. No exemptions, by design. */
export function seedingVerdict(files) {
  const sites = seedingSites(files);
  return { sites, unexempt: sites };
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

  // ---- THE WALL, mutation-proven on synthetic input before it is pointed at the tree
  const synth = new Map([
    ['theme.mjs', `the key is '${SEED_KEY}' and this file is allowed to say it`],
    ['lib/cdp.mjs', 'seeds Object.entries(values) and names no key of its own'],
    ['clean.mjs', 'no seeding here'],
  ]);
  check('the helper itself is never its own violation', seedingSites(synth).length === 0, seedingSites(synth).join(', ') || 'none');
  check('a generic seeder that names no key is not a violation', !seedingSites(synth).includes('lib/cdp.mjs'), 'lib/cdp.mjs is exempt by construction');
  const dirty = new Map([...synth, ['rogue.mjs', `localStorage.setItem('${SEED_KEY}', t)`]]);
  check('a SECOND implementation is caught by name', seedingSites(dirty).includes('rogue.mjs'), seedingSites(dirty).join(', '));
  check('and a second implementation is a FAILURE, with nothing to exempt it',
    seedingVerdict(dirty).unexempt.includes('rogue.mjs'), 'rogue.mjs fails');
  check('a clean set has nothing to report', seedingVerdict(synth).unexempt.length === 0, 'none');

  // ---- THE HELPER MUST BE IMPORTABLE, which is the fact that caused this whole row. M1-57 found
  // that importing this file RAN its CLI; M1-60 found the guard that fixed it still returned true
  // when the importer's path held no `/dev/` segment. A child process from a directory with no such
  // segment is the only honest test, because in-process the guard is already resolved.
  const { mkdtempSync, writeFileSync, rmSync } = await import('node:fs');
  const { tmpdir } = await import('node:os');
  const { join } = await import('node:path');
  const { spawnSync } = await import('node:child_process');
  const box = mkdtempSync(join(tmpdir(), 'theme-import-'));
  try {
    const probe = join(box, 'probe.mjs');
    writeFileSync(probe, `const m = await import(${JSON.stringify(fileURLToPath(import.meta.url))});\n` +
      "console.log('RETURNED ' + typeof m.themeValues);\n");
    const r = spawnSync(process.execPath, [probe], { encoding: 'utf8' });
    check('importing this file returns instead of running its CLI',
      r.status === 0 && (r.stdout ?? '').includes('RETURNED function'),
      `exit ${r.status}, stdout ${JSON.stringify((r.stdout ?? '').trim().slice(0, 60))}`);
    const withFlag = spawnSync(process.execPath, [probe, '--selftest'], { encoding: 'utf8' });
    check('and still returns when the IMPORTER was run with --selftest',
      withFlag.status === 0 && (withFlag.stdout ?? '').includes('RETURNED function'),
      `exit ${withFlag.status}`);
  } finally {
    rmSync(box, { recursive: true, force: true });
  }

  // ---- AND THE REAL TREE, because a wall proven only on input this function wrote proves the
  // function and says nothing about the thing it guards (M1-49's lesson, one row later). The
  // denominator is the DIRECTORY, walked, never a list in this file.
  const { readdirSync, statSync } = await import('node:fs');
  const walk = (dir, prefix = '') => readdirSync(dir).sort().flatMap((name) => {
    const full = join(dir, name);
    const rel = prefix === '' ? name : `${prefix}/${name}`;
    return statSync(full).isDirectory() ? walk(full, rel) : (name.endsWith('.mjs') ? [[rel, readFileSync(full, 'utf8')]] : []);
  });
  const tree = new Map(walk(HERE));
  const live = seedingVerdict(tree);
  check(`ONE implementation in the whole toolbox (${tree.size} .mjs walked)`,
    tree.size > 0 && live.unexempt.length === 0,
    live.unexempt.length === 0 ? 'no file but theme.mjs names the key' : `SECOND IMPLEMENTATION: ${live.unexempt.join(', ')}`);

  // THE HARDCODED ROOMS MUST STILL BE THE SHEET'S ROOMS. themeLanded() compares a rendered page
  // against ROOM above, and ROOM is a constant while tokens.css is the source of record — gate.mjs
  // derives the same two values FROM the sheet, which is the better shape and the reason this was
  // worth checking rather than assuming. Measured 2026-09-18: no drift, dark #0B0E0E -> 11,14,14 and
  // light #E0E2DF -> 224,226,223, both exact. Asserted now so a re-derived room cannot leave this
  // file silently comparing every capture against a colour the console stopped using.
  const sheet = readFileSync(join(ROOT, 'webui/src/shared/tokens.css'), 'utf8');
  let which = 'dark';
  const declared = {};
  for (const line of sheet.split('\n')) {
    if (line.includes('data-theme="light"')) which = 'light';
    else if (line.includes('data-theme="dark"')) which = 'dark';
    const hit = line.match(/--room:\s*(#[0-9A-Fa-f]{6})/);
    if (hit !== null) declared[which] = hit[1];
  }
  for (const theme of THEMES) {
    const fromSheet = declared[theme] === undefined ? null : channels(declared[theme]);
    check(`the ${theme} room here is still tokens.css's ${theme} room`,
      fromSheet !== null && fromSheet.join(',') === ROOM[theme].join(','),
      `sheet ${declared[theme] ?? 'MISSING'} -> ${fromSheet?.join(',') ?? '-'}, here ${ROOM[theme].join(',')}`);
  }

  console.log(`\nselftest: ${pass} passed, ${fail} failed`);
  process.exit(fail === 0 ? 0 : 1);
}

// The CLI runs only when this file IS the program. Importing it for themeValues() otherwise RAN the
// selftest, exited, and left the importing tool with no output at all - the same guard capture.mjs
// documents at its own top ("an unguarded body made that import run a capture with the gate's own
// argv"). Found by M1-57 on its first run (design-builder4).
//
// THE SUFFIX FORM OF THAT GUARD DID NOT HOLD, and M1-60 measured it before adding three importers.
// It was `import.meta.url.endsWith(argv[1].replace(/^.*?(?=\/dev\/|$)/, ''))`. When the IMPORTING
// program's path contains no `/dev/` segment the lookahead falls through to `$`, the replace
// consumes the whole string, and `endsWith('')` is TRUE FOR EVERY STRING - so isMain is true on
// import and the CLI runs anyway. It passed here only because this checkout happens to live under
// ~/Documents/dev/projects, so every path on this machine contains `/dev/` by coincidence. Measured
// 2026-09-18 from a scratch directory with no such segment: the import printed the usage text and
// exited 2, and never returned to its caller. Three forms compared in the same run, as program and
// as import: house `=== file://${argv[1]}` correct both ways, pathToFileURL correct both ways,
// suffix correct as program and WRONG as import.
//
// pathToFileURL and not string concatenation, because import.meta.url is percent-encoded: a
// worktree under a path with a space would make the string form FALSE when this file is the
// program, which is the same defect wearing the quiet face - the CLI would silently do nothing.
const isMain = process.argv[1] !== undefined && import.meta.url === pathToFileURL(process.argv[1]).href;

if (isMain && process.argv.includes('--selftest')) await selftest();

const url = isMain ? positional[0] : undefined;
if (isMain && (url === undefined || ARGS.includes('--help'))) {
  console.log("usage: node .dev/web-console/theme.mjs '<url>' [--theme both|light|dark] [--out DIR] [--width W --height H]");
  console.log('       node .dev/web-console/theme.mjs --selftest');
  process.exit(url === undefined && !ARGS.includes('--help') ? 2 : 0);
}

const wanted = flag('theme', 'both') === 'both' ? THEMES : [flag('theme', 'dark')];
const outDir = resolve(ROOT, flag('out', 'webui/.impeccable/review/theme'));
const width = Number(flag('width', '1536'));
const height = Number(flag('height', '1024'));

if (isMain) {
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
}
