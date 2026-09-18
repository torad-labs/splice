// Gate captures: every address of the console, in both themes, at two frame sizes, with a contact
// sheet per theme and a manifest that says which of them came back blank.
//
// WHY A GATE AND NOT A SCREENSHOT: a milestone is signed off by looking at the console, and a
// single capture proves one page at one size in one room. The failure this exists to catch is the
// one nobody notices — an address that renders an empty room, at a size the reviewer did not open,
// in the theme they were not using. So the run is exhaustive, and each capture carries the
// measurement that would expose it: the fraction of its pixels that are the room's own colour.
//
// M1-76 DISPOSITION — the two ways a check can be decorative, answered for this file. CLEAN BOTH.
//   SHAPE ONE, does every FAIL reach the exit code? YES. The capture loop's catch does not swallow:
//     every failure pushes to `fixtureFailures`, and a non-empty `fixtureFailures` exits 1. There is
//     no path here that prints a failure and returns 0 — traced by reading every catch in the file
//     this row rather than trusting the shape.
//   SHAPE TWO, if every page threw, what would it print? IT WOULD FAIL, LOUDLY. Because the failure
//     path is a PUSH and not a print, an all-throwing run produces the maximum number of entries
//     rather than the minimum: the denominator does not empty itself when the work goes wrong, it
//     fills. That is the inversion law 34 is about, and this file already had it the right way up —
//     the opposite of capture.mjs's --sweep, whose walk() catch let an unreadable directory empty
//     the denominator and report a clean sweep (fixed in this row).
//
// Usage: node .dev/web-console/gate.mjs <milestone> [--dry-run]
import { mkdirSync, readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { mgmtKey, renderHtml, shoot, show, sleep, withChrome } from './lib/cdp.mjs';
// The address-to-fixture mapping lives in ONE place and is checked against the pages, not trusted:
// a copy in this file drifted twice in one day and made every capture of four addresses a lie.
import { FIXTURES, urlFor } from './lib/fixtures.mjs';
// THE PAGE PREDICATE COMES FROM capture.mjs, so a frame this gate writes and a frame capture.mjs
// writes are checked by exactly one piece of code (M1-32). It asserts the page answered, rendered,
// is not a flat fill, and carries data-sample for a fixture-fed address — and it WRITES NOTHING when
// any of those fail, which is what the gate's own run needs: before this, a dead port produced a
// 25 KB PNG of Chrome's error page, this gate scored it as room-colour 0.000, and every downstream
// number inherited it.
import { capturePage } from './capture.mjs';
import { THEMES, themeSeedSource } from './theme.mjs';
import { colorFraction, decodePng, hexToRgb } from './lib/png.mjs';

const ROOT = resolve(import.meta.dirname, '../..');
const TERMINAL = process.stdout.isTTY === true;

/** The two rooms and the two frames every address is captured in. */
// THEMES and the seed both come from theme.mjs (M1-60). This file held its own copy of the list
// AND its own copy of the seeding idiom — the fourth private implementation of a capability M1-55
// put in the shared toolbox precisely to stop a fourth. It is not the same shape as the other three
// callers and that is why it was missed: gate.mjs drives ONE Chrome across every address in both
// themes, so it cannot hand the theme to withChrome (those values are seeded once, at session
// start) and instead re-registers the seed script on each theme change. themeSeedSource() is that
// shape, so the key name lives in one file and this one keeps its own registration discipline.
const FRAMES = [[1536, 1024], [1280, 800]];

/** A capture this fraction room-coloured or more is BLANK: the page did not draw. */
const BLANK_AT = 0.98;

/**
 * The fixture table, the capture URL and the load assertion all come from lib/fixtures.mjs; this
 * file keeps none of them. The history that put them there: this table named 'demo' for turns,
 * sessions, projects and logs — a name no page ships — and the guarded dynamic import swallowed the
 * failure, so a full run of captures came back showing live daemon data that looked exactly like a
 * working capture (M1-19). comp-check.mjs carried a third copy of the same four names and drifted
 * again (M1-28). One module now owns the mapping, and `node .dev/web-console/lib/fixtures.mjs`
 * checks it against the pages by name.
 */

/**
 * The addresses, read from the shell's own table rather than copied here: a fourteenth address
 * added by a later row has to appear in the gate without an edit, and a list written out in this
 * file could not fail for one that was missing from itself.
 */
function addresses() {
  const source = readFileSync(join(ROOT, 'webui/src/app/rows.ts'), 'utf8');
  const block = source.match(/export const ADDRESSES = \[([\s\S]*?)\] as const;/);
  if (block === null) throw new Error('rows.ts: the ADDRESSES table was not found');
  return [...block[1].matchAll(/'([a-z0-9-]+)'/g)].map((match) => match[1]);
}

/** Both `--room` values, keyed by the theme they belong to. Read from the token sheet, so a room
 *  that is re-derived does not leave this script measuring against the old one. */
function rooms() {
  const sheet = readFileSync(join(ROOT, 'webui/src/shared/tokens.css'), 'utf8');
  const found = {};
  let theme = 'dark';
  for (const line of sheet.split('\n')) {
    if (line.includes('data-theme="light"')) theme = 'light';
    else if (line.includes('data-theme="dark"')) theme = 'dark';
    const room = line.match(/--room:\s*(#[0-9A-Fa-f]{6})/);
    if (room !== null) found[theme] = room[1];
  }
  for (const theme of THEMES) {
    if (found[theme] === undefined) throw new Error(`tokens.css: no --room for the ${theme} theme`);
  }
  return found;
}

function plan() {
  const list = addresses();
  const captures = [];
  for (const address of list) {
    for (const theme of THEMES) {
      for (const [width, height] of FRAMES) {
        captures.push({ address, theme, width, height, file: `${address}-${theme}-${width}x${height}.png` });
      }
    }
  }
  return { addresses: list, captures };
}

const [, , milestone, ...flags] = process.argv;
const dryRun = flags.includes('--dry-run');

if (!milestone || !/^[a-z0-9-]+$/.test(milestone)) {
  console.error('usage: node .dev/web-console/gate.mjs <milestone> [--dry-run]');
  console.error('       <milestone> is a lowercase name, e.g. m1-preview');
  process.exit(2);
}

const { addresses: list, captures } = plan();
/** Where the captures land. The default is the M2-11 contract (review/gate/<milestone>); a row
 *  whose own review directory differs passes --out, which is how the census run keeps M1-16's
 *  directory empty and lands its frames where its own contract says. */
const outFlag = flags.indexOf('--out');
const outArg = outFlag === -1 ? null : flags[outFlag + 1];
if (outFlag !== -1 && (outArg === undefined || outArg.startsWith('--'))) {
  console.error('usage: --out <directory> (absolute, or relative to the repo root)');
  process.exit(2);
}
const outDir = outArg === null ? join(ROOT, 'webui/.impeccable/review/gate', milestone) : resolve(ROOT, outArg);
const sheetPath = (theme) => join(outDir, `sheet-${theme}.png`);
const fixtures = [...new Set(Object.values(FIXTURES).filter((f) => f !== null).map((f) => f.name))].sort();

if (dryRun) {
  console.log(`gate ${milestone}: ${captures.length} captures into ${outDir}`);
  console.log(`addresses (${list.length}): ${list.join(' ')}`);
  console.log(`themes: ${THEMES.join(' ')}   frames: ${FRAMES.map(([w, h]) => `${w}x${h}`).join(' ')}`);
  console.log(`fixture names: ${fixtures.join(' ')}   live (no fixture): ${Object.entries(FIXTURES).filter(([, f]) => f === null).map(([a]) => a).join(' ')}`);
  console.log(`fixture modules: ${Object.entries(FIXTURES).filter(([, f]) => f !== null).map(([a, f]) => `${a}=${f.file}`).join(' ')}`);
  for (const capture of captures) console.log(`  ${capture.file}`);
  for (const theme of THEMES) console.log(`  sheet-${theme}.png`);
  process.exit(0);
}

function sheetHtml(theme, themeCaptures) {
  const cells = themeCaptures.map((capture) => {
    const width = Math.round(capture.width / 4);
    return `<figure><img src="${capture.file}" width="${width}"><figcaption>${capture.file}<br>`
      + (capture.blank === null
        ? '<span class="fail">NO FRAME WRITTEN</span>'
        : `<span class="blank">${capture.blank.toFixed(3)} room</span>${capture.blank >= BLANK_AT ? ' BLANK' : ''}`)
      + (capture.state === 'empty' ? ' <span class="fail">EMPTY PAGE</span>' : '')
      + (capture.fixtureOk ? '' : ` <span class="fail">FIXTURE FAILED ${capture.fixtureNote}</span>`)
      + (capture.fixture === null ? '' : ` <span class="blank">${capture.fixtureFile}</span>`)
      + '</figcaption></figure>';
  }).join('\n');
  return `<!doctype html><meta charset="utf-8"><title>gate ${milestone} ${theme}</title>
<style>
  :root { color-scheme: dark; }
  body { margin: 0; padding: 16px; background: #0B0E0E; color: #ECEAE2;
         font: 12px/1.4 ui-monospace, monospace; }
  h1 { font-size: 15px; margin: 0 0 14px; }
  .grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 14px; }
  figure { margin: 0; }
  img { display: block; border: 1px solid #3a3d3c; background: #000; }
  figcaption { margin-top: 4px; }
  .blank { color: #9D9B8E; }
  .fail { color: #ff7a5c; font-weight: 600; }
</style>
<h1>gate ${milestone} / ${theme} / ${themeCaptures.length} captures</h1>
<div class="grid">
${cells}
</div>`;
}

mkdirSync(outDir, { recursive: true });
// A re-run must not leave a stale capture behind for a file the plan no longer produces.
for (const entry of readdirSync(outDir)) {
  if (entry.endsWith('.png') || entry.endsWith('.html')) rmSync(join(outDir, entry), { force: true });
}

const room = rooms();
const key = mgmtKey();
const manifest = [];
const blanks = [];
const fixtureFailures = [];
const started = Date.now();

await withChrome({ 'myx-mgmt-key': key }, async (send) => {
  let themeScript = null;
  let lastUrl = null;
  for (const theme of THEMES) {
    for (const capture of captures.filter((row) => row.theme === theme)) {
      // ONE theme script at a time: the previous one is removed before the next is registered.
      // Letting them accumulate looked harmless and was not — measured 2026-09-18, the first two
      // captures after a theme change came back in the previous room, because which of the
      // registered writers landed last was not the one this loop had just added.
      if (themeScript !== null) {
        await send('Page.removeScriptToEvaluateOnNewDocument', { identifier: themeScript });
      }
      const added = await send('Page.addScriptToEvaluateOnNewDocument', { source: themeSeedSource(theme) });
      themeScript = added.identifier;
      const url = urlFor(capture.address);
      // ALWAYS through about:blank. Every console URL is a hash route, so a navigate from one to
      // the next — and from an address to ITSELF in the other theme — is a same-document fragment
      // navigation that does not re-create the frame, so the seed above would not run and the
      // capture would come back in whatever room the previous document chose. Measured twice on
      // 2026-09-18: the first fix (reload when the URL repeats) left `fleet` wrong, because its
      // light capture follows a DIFFERENT address in the previous theme and so was a plain hash
      // change. A blank page in between makes the next load cross-document, every time.
      await send('Page.navigate', { url: 'about:blank' });
      await show(send, url, capture.width, capture.height);
      const fixture = FIXTURES[capture.address];
      // THE CAPTURE MUST PROVE IT CAPTURED A PAGE. capturePage runs the whole predicate and throws
      // with the reasons; a failure means NO FRAME IS WRITTEN, so nothing downstream can score it.
      let claim = null;
      let frame = null;
      let bytes = null;
      let state = null;
      let paper = 0;
      let failure = null;
      try {
        ({ claim, frame, bytes, state, paper } = await capturePage(send, url, capture.width, capture.height, join(outDir, capture.file)));
      } catch (error) {
        failure = error.message;
      }
      if (failure !== null) {
        const oneLine = failure.replace(/\n\s*/g, ' | ');
        fixtureFailures.push(oneLine);
        manifest.push({
          ...capture,
          fixture: fixture === null ? null : fixture.name,
          fixtureFile: fixture === null ? null : fixture.file,
          fixtureOk: false,
          fixtureNote: 'capture failed',
          captureFailed: true,
          state: 'nothing',
          blank: null,
        });
        // PRINTED AS IT HAPPENS: a later crash in the sheet render must not be able to hide which
        // capture failed, which is exactly what happened on this gate's first real run (2026-09-18).
        console.error(`  NO FRAME: ${oneLine}`);
        if (TERMINAL) process.stdout.write('F');
        continue;
      }
      const blank = colorFraction(decodePng(bytes), hexToRgb(room[theme]));
      manifest.push({
        ...capture,
        fixture: fixture === null ? null : fixture.name,
        fixtureFile: fixture === null ? null : fixture.file,
        fixtureOk: true,
        fixtureNote: `${claim.sample === null ? 'live' : `data-sample=${claim.sample}`} top ${(frame.share * 100).toFixed(1)}%, paper ${(paper * 100).toFixed(2)}%`,
        captureFailed: false,
        state,
        blank: Number(blank.toFixed(4)),
      });
      if (blank >= BLANK_AT) blanks.push(capture.file);
      if (TERMINAL) process.stdout.write(!verdict.ok ? 'F' : blank >= BLANK_AT ? 'B' : '.');
    }
  }
  if (TERMINAL) process.stdout.write('\n');

  for (const theme of THEMES) {
    const html = sheetHtml(theme, manifest.filter((row) => row.theme === theme));
    await renderHtml(send, html, join(outDir, `sheet-${theme}.html`), sheetPath(theme), 1640, 2200);
  }
});

// One capture per line, so the file is readable as a list and `grep -c address` counts captures.
writeFileSync(
  join(outDir, 'manifest.json'),
  `[\n${manifest.map((row) => JSON.stringify(row)).join(',\n')}\n]\n`,
);

const seconds = Math.round((Date.now() - started) / 1000);
console.log(`gate ${milestone}: ${manifest.length} captures in ${seconds}s -> ${outDir}`);
console.log(`  sheets: ${THEMES.map((theme) => `sheet-${theme}.png`).join(' ')}`);
if (blanks.length > 0) {
  // Loud on purpose: a blank capture is the failure this gate exists to find, and a gate that
  // reported it as a line among fifty-two would be passing it silently.
  console.error(`  BLANK: ${blanks.length} of ${manifest.length} captures are >= ${BLANK_AT} room colour:`);
  for (const file of blanks) console.error(`    ${file}`);
}
const empties = manifest.filter((row) => row.state === 'empty');
if (empties.length > 0) {
  // An honest empty is a legitimate frame and the gate keeps it, but no downstream number may be
  // computed from it without being told: every one is named here and marked on the sheet.
  console.error(`  EMPTY PAGE: ${empties.length} of ${manifest.length} frames carry no world paper (chrome and an honest empty):`);
  for (const row of empties) console.error(`    ${row.file}`);
}
if (fixtureFailures.length > 0) {
  // A capture whose fixture did not load is a FAILED capture, not a captured page, and every
  // number a later row reads off these frames is a number about live data wearing a sample's name.
  // So this is not a warning: the run exits non-zero and the frames are not evidence.
  console.error(`  CAPTURE FAILED: ${fixtureFailures.length} of ${manifest.length} frames could not prove they captured a page:`);
  for (const line of fixtureFailures) console.error(`    ${line}`);
  process.exit(1);
}
