// Capture one page of the console from the pinned dev server (CONTRACTS.md section 4).
//
// WHY NOT `--screenshot=`: the console sits behind the management-key gate and a headless
// Chrome profile has an empty localStorage, so a plain screenshot captures the gate on every
// page. This seeds the key into localStorage before the app boots, then captures the frame the
// recipe asks for. No source file is touched and the key is never printed. Vendored 2026-09-18
// from design-builder4's M2-02 capture script; the CDP plumbing moved to lib/cdp.mjs the same day
// so gate.mjs could render its contact sheets through the same path, with this command line and
// this behaviour unchanged.
//
// THE SENTENCE THIS ROW EXISTS FOR, and both halves are measured rather than reasoned about:
// **this pipeline cannot distinguish a page from a dead port, and it cannot distinguish a page from
// a page with nothing in it.** The dead port is the easy half (25 KB, no elements, a gate scoring
// room-colour 0.000, and a wrong answer that at least looks wrong). The unseeded page is the
// dangerous half: captured against a live server with no key and no fixture, the console renders its
// chrome and an honest empty — correct dimensions, 96 elements, thousands of distinct colours, exit
// 0 — and splice-design read that frame as evidence for a minute before opening someone else's file
// and finding the finding it seemed to contradict was sound.
//
// A CAPTURE NOW HAS TO PROVE IT CAPTURED A PAGE (M1-32, from M1-27's audit). Pointed at a dead
// port, this script used to write a 25 KB PNG of Chrome's error page and exit 0; gate.mjs then
// scored that frame as room-colour 0.000 and filed it as a measurement. Every number this campaign
// produced about how the console looks — the tonal census, comp-diff's bands, the contact sheets,
// the punch list — is computed from frames this pipeline produced, and the pipeline could not say
// "I captured nothing". That is law 23 at the root of the tree rather than in a leaf.
//
// So a frame is written only when four claims hold, and each claim is the shape law 27 requires —
// a positive assertion of what must be true, not the absence of an error:
//
//   ANSWERED   the console's own root element is in the document with a real box. A dead port, a
//              connection refused page and an unrendered shell all fail here, and they fail BY
//              NAME with the port, because "the page answered" is the claim a flat PNG cannot make.
//   RENDERED   the document reached readyState complete and the root has content in it.
//   NOT FLAT   the frame's single most common colour covers less than FLAT_AT of it. A blank fill
//              from an error page, a white flash or a black paint is a flat fill; a console page
//              never is, in either room.
//   FIXTURE    when the URL asks for `?fixture=<name>`, the page root carries `data-sample` (M1-20)
//              with that exact name. That is the positive assertion of law 23: a page that never
//              loaded its fixture cannot carry the marker, and a page that did cannot lack it.
//
// A capture that cannot make those claims exits non-zero with the reasons and WRITES NOTHING.
//
// AND A THIRD ANSWER, because "not a dead port" is the easy half. An UNSEEDED page is the dangerous
// one: pointed at the live server with no key and no fixture, the console renders its chrome and an
// honest empty on a black frame — correct dimensions, real element count, thousands of distinct
// colours, and a perfectly plausible image that any downstream measurement will happily score
// (measured by splice-design at 3840: root 3840x2160, 96 elements, 90.2% #0b0e0e, exit 0). So the
// verdict has three values, not two:
//
//   NOTHING    a claim above failed. Exit 1, no file.
//   EMPTY      every claim holds and the frame carries NONE of the world's paper in either room —
//              the console photographed an honest empty. LEGITIMATE AND NEVER REFUSED: the frame
//              is written and the state is said out loud, on stderr and in every manifest that
//              carries it, because the failure is only ever that it went unsaid.
//   PAGE       every claim holds and the frame carries paper. Exit 0.
//
// The paper measure is calibrated on real frames rather than guessed (2026-09-18): pages carrying
// data measure 21.15% (teams), 17.53% (mcp) and 3.66% (fleet) paper, while the honest empties
// (projects) measure 0.00% in both rooms. The gap between 3.66 and 0.01 is why PAPER_AT is 0.02.
//
// Usage: node .dev/web-console/capture.mjs '<url>' <absolute out.png> [<width> <height>]
//        node .dev/web-console/capture.mjs --sweep <dir> [<dir> ...]     check frames already on disk
//        node .dev/web-console/capture.mjs --help
import { readdirSync, readFileSync, rmSync, statSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { shoot, show, withChrome } from './lib/cdp.mjs';
import { THEMES, themeValues } from './theme.mjs';
import { colorFraction, decodePng, hexToRgb } from './lib/png.mjs';

const REPO = resolve(import.meta.dirname, '../..');

/** A frame whose single most common colour covers this much of it is a flat fill, not a page. */
const FLAT_AT = 0.98;
/**
 * The world's paper, both rooms, READ OUT OF THE SHEET at run time rather than typed here.
 *
 * This was hard-coded to #DED9C6 / #F6F6F3 for exactly one run, and that run is the reason it is
 * not any more: the light `--strip` moved to #F5EDDD under a live peer row between the calibration
 * and the capture, so all 26 light frames measured 0.02-0.5% paper and were reported as pages with
 * nothing in them. A constant copied into an instrument goes stale silently — the defect this whole
 * campaign keeps finding — and the fix is the same one the gate already uses for the room: parse
 * the token sheet. */
function paperColours() {
  const sheet = readFileSync(join(REPO, 'webui/src/shared/tokens.css'), 'utf8');
  const found = [];
  for (const line of sheet.split('\n')) {
    // BOTH tokens the world prints its paper in: `--strip` is the strip's own ground and
    // `--strip-field` is the boxed field on it. Measured 2026-09-18: turns carries its material as
    // 28.7% of the FIELD fill and only 1.4% of the strip ground, and the light usage page as 12.9%
    // field against 0.5% strip — a measure that knew only `--strip` would call both of them pages
    // with nothing in them, which is the false claim this state exists to avoid making.
    const token = line.match(/--strip(?:-field)?:\s*(#[0-9A-Fa-f]{6})/);
    if (token !== null) found.push(token[1]);
  }
  if (found.length < 4) throw new Error(`tokens.css: expected --strip and --strip-field for both rooms, found ${found.length}`);
  return [...new Set(found)];
}
/** Below this share of paper, the frame is the chrome and an honest empty, not a page with content. */
const PAPER_AT = 0.02;
/** The console's own root. `.myx-console` is the shell's outermost element (app/app.css:56). */
const ROOT = '.myx-console, #root';
/** The smallest a real console root can be: below this the app did not lay out. */
const MIN_ROOT = { w: 300, h: 200 };

const HELP = `usage: node .dev/web-console/capture.mjs '<url>' <absolute out.png> [<width> <height>] [--theme dark|light]
       node .dev/web-console/capture.mjs --sweep <dir> [<dir> ...]

Captures one page of the console and writes the frame only if it can prove it captured a page:
the console root answered with a real box, the document reached a rendered state, the frame is not
a flat fill, and a ?fixture=<name> URL carries data-sample="<name>" on the root.

--sweep decodes frames already on disk and applies the part of the same predicate a PNG can answer
(the flat-fill half), naming the blanks and exiting non-zero if there are any.`;

/** The CLI runs only when this file IS the program: gate.mjs imports capturePage from here, and an
 *  unguarded body made that import run a capture with the gate's own argv (measured 2026-09-18:
 *  `gate.mjs census --out ... --dry-run` tried to navigate to 'census' and threw a CDP bindings
 *  error before it ever reached the dry run). */
const isMain = process.argv[1] !== undefined && import.meta.url === `file://${process.argv[1]}`;

if (isMain && (process.argv.length <= 2 || process.argv.includes('--help'))) {
  console.log(HELP);
  process.exit(process.argv.length <= 2 ? 2 : 0);
}

// ---------------------------------------------------------------- the predicate

/**
 * The claim, read out of the page. Returns geometry and a marker, never a verdict: the thresholds
 * live on this side so the page cannot grade itself.
 */
export function claimScript(url) {
  const asked = new URL(url).hash.includes('?fixture=')
    ? new URLSearchParams(new URL(url).hash.slice(new URL(url).hash.indexOf('?'))).get('fixture')
    : null;
  return `(() => {
    const root = document.querySelector('${ROOT}');
    const box = root === null ? null : root.getBoundingClientRect();
    // The marker sits on the PAGE's root, which is a child of the shell's, so it is looked up
    // wherever it is rather than on ${ROOT}: measured 2026-09-18, reading it off the shell reported
    // data-sample=nothing on a page that carries it.
    const carrier = document.querySelector('[data-sample]');
    const marked = carrier === null ? null : carrier.getAttribute('data-sample');
    return JSON.stringify({
      readyState: document.readyState,
      href: location.href,
      rootFound: root !== null,
      rootBox: box === null ? null : { w: Math.round(box.width), h: Math.round(box.height) },
      rootChildren: root === null ? 0 : root.querySelectorAll('*').length,
      sample: marked,
      sampleOn: carrier === null ? null : carrier.className,
      expected: ${JSON.stringify(asked)},
    });
  })()`;
}

/** The flat-fill share of a captured frame: how much of it is one colour. */
export function topShare(bytes) {
  const png = decodePng(bytes);
  const tally = new Map();
  let sampled = 0;
  for (let i = 0; i < png.pixels.length; i += png.channels * 4) {
    const key = `${png.pixels[i]},${png.pixels[i + 1]},${png.pixels[i + 2]}`;
    tally.set(key, (tally.get(key) ?? 0) + 1);
    sampled += 1;
  }
  const top = Math.max(...tally.values());
  const [colour, count] = [...tally.entries()].sort((a, b) => b[1] - a[1])[0];
  const hex = '#' + colour.split(',').map((v) => Number(v).toString(16).padStart(2, '0')).join('');
  return { share: count / sampled, colour: hex, width: png.width, height: png.height };
}

/** How much of the frame is the world's paper — the material the console prints its strips on. */
export function paperShare(bytes, paper = paperColours()) {
  const png = decodePng(bytes);
  return Math.max(...paper.map((hex) => colorFraction(png, hexToRgb(hex), 8, 3)));
}

/** Every claim, checked. `reasons` is empty when the capture can prove it captured a page. */
export function judge(claim, frame) {
  const reasons = [];
  if (!claim.rootFound) {
    reasons.push(`the console root (${ROOT}) is not in the document — the page did not answer or did not render`);
  } else {
    const box = claim.rootBox ?? { w: 0, h: 0 };
    if (box.w < MIN_ROOT.w || box.h < MIN_ROOT.h) {
      reasons.push(`the console root measured ${box.w}x${box.h}, under the ${MIN_ROOT.w}x${MIN_ROOT.h} a laid-out console has`);
    }
    if (claim.rootChildren < 20) {
      reasons.push(`the console root holds ${claim.rootChildren} elements — an empty shell, not a rendered page`);
    }
  }
  if (claim.readyState !== 'complete') reasons.push(`the document is at readyState '${claim.readyState}', not complete`);
  if (frame !== null && frame.share >= FLAT_AT) {
    reasons.push(`the frame is a flat fill: ${(frame.share * 100).toFixed(1)}% of it is ${frame.colour}`);
  }
  if (claim.expected !== null && claim.sample !== claim.expected) {
    reasons.push(`the URL asked for fixture '${claim.expected}' and the root carries data-sample=${claim.sample === null ? 'nothing' : `'${claim.sample}'`}`);
  }
  return reasons;
}

/** The whole predicate over one live page: navigate, claim, shoot, judge. Throws with the reasons. */
export async function capturePage(send, url, width, height, out, settleMs = 6000) {
  await show(send, url, width, height, settleMs);
  const answer = await send('Runtime.evaluate', { expression: claimScript(url), returnByValue: true });
  const claim = JSON.parse(answer.result.value);
  const bytes = await shoot(send, out);
  const frame = topShare(bytes);
  const reasons = judge(claim, frame);
  const paper = paperShare(bytes);
  const verdict = stateOf({ reasons, paper });
  if (verdict.state === 'nothing') {
    const port = new URL(url).port;
    const error = new Error(`NOTHING PHOTOGRAPHED at ${url} (port ${port}):\n  ${verdict.reasons.join('\n  ')}`);
    error.state = 'nothing';
    throw error;
  }
  return { claim, frame, bytes, paper, state: verdict.state, reasons: verdict.reasons };
}

/**
 * The three-way verdict. `nothing` fails the capture, `empty` is legitimate but must be asked for,
 * `page` is the ordinary answer — and every caller gets the middle state as a value rather than
 * having to infer it from a frame that looks fine.
 */
export function stateOf({ reasons, paper }) {
  if (reasons.length > 0) return { state: 'nothing', reasons };
  if (paper < PAPER_AT) {
    return { state: 'empty', reasons: [`the frame carries ${(paper * 100).toFixed(2)}% of the world's paper (under the ${(PAPER_AT * 100).toFixed(0)}% floor) — an honest empty, not a page with content`] };
  }
  return { state: 'page', reasons: [] };
}

// ------------------------------------------------------------------- the sweep

const argv = process.argv.slice(2);

if (isMain && argv[0] === '--sweep') {
  const dirs = argv.slice(1);
  if (dirs.length === 0) {
    console.error('usage: node .dev/web-console/capture.mjs --sweep <dir> [<dir> ...]');
    process.exit(2);
  }
  const frames = [];
  const walk = (dir) => {
    for (const entry of readdirSync(dir)) {
      const path = join(dir, entry);
      if (statSync(path).isDirectory()) walk(path);
      else if (entry.endsWith('.png')) frames.push(path);
    }
  };
  for (const dir of dirs) {
    try { walk(dir); } catch { console.error(`  (no such directory: ${dir})`); }
  }
  const blanks = [];
  for (const path of frames) {
    const bytes = readFileSync(path);
    const frame = topShare(bytes);
    const paper = paperShare(bytes);
    const flat = frame.share >= FLAT_AT;
    if (flat) blanks.push(`${path} (${(frame.share * 100).toFixed(1)}% ${frame.colour})`);
    console.log(`  ${flat ? 'BLANK' : 'ok   '} ${(frame.share * 100).toFixed(1).padStart(5)}% top colour  ${(paper * 100).toFixed(2).padStart(6)}% paper  ${path}`);
  }
  console.log(`\nswept ${frames.length} frame(s) across ${dirs.length} director(ies): ${blanks.length} flat fill(s)`);
  for (const blank of blanks) console.error(`BLANK ${blank}`);
  process.exit(blanks.length === 0 ? 0 : 1);
}

// ------------------------------------------------------------------ the capture

// --theme, THROUGH theme.mjs (M1-60). This file could photograph the console in one room only:
// it seeded the management key and nothing else, so every frame it has ever written is the default
// room. That is the capability M1-55 put in the shared toolbox and that three seats had each
// rebuilt privately — and this tool, the campaign's main camera, still could not reach it.
const themeAt = argv.indexOf('--theme');
const theme = themeAt === -1 ? 'dark' : argv[themeAt + 1];
const positional = argv.filter((a, i) => a !== '--theme' && argv[i - 1] !== '--theme');
const [url, out, w = '1536', h = '1024'] = positional;
if (isMain && !THEMES.includes(theme)) {
  // REFUSED and not defaulted: an unknown theme renders the default room and writes a frame
  // labelled with a room nobody photographed, which is the lie theme.mjs exists to refuse.
  console.error(`REFUSED: --theme ${theme} is not one of ${THEMES.join(', ')}`);
  process.exit(2);
}
if (isMain && (!url || !out)) {
  console.error(HELP);
  process.exit(2);
}
const width = Number(w);
const height = Number(h);

if (isMain) try {
  await withChrome(themeValues(theme), async (send) => {
    const { claim, frame, paper, state, reasons } = await capturePage(send, url, width, height, out);
    if (state === 'empty') {
      // NAMED, NEVER REFUSED. The first cut of this refused the middle state unless --allow-empty
      // was passed, and design-builder3 hit that refusal four times on frames that plainly rendered
      // a full rack of strips — because the paper measure then knew only `--strip` and not the
      // `--strip-field` the pages actually paint (M1-34). Two lessons, both in this file now: the
      // measure is derived from the sheet, and an escape hatch that gets used routinely is not an
      // escape hatch. A refusal that fires on correct frames teaches everyone to add the flag and
      // stop reading the output, which is worse than the hole it was guarding.
      console.error(`EMPTY PAGE at ${url} — the frame is written and it is NOT a page with content:\n  ${reasons.join('\n  ')}`);
      console.error('  An honest empty is a legitimate thing to photograph. Do not compute a coverage, ink or');
      console.error('  tonal number from this frame without saying that is what it is.');
    }
    console.log(`wrote ${out} (${width}x${height} ${theme}) — ${state === 'page' ? 'PHOTOGRAPHED A PAGE' : 'PHOTOGRAPHED AN EMPTY PAGE'}`);
    console.log(`  root ${claim.rootBox.w}x${claim.rootBox.h}, ${claim.rootChildren} elements, top colour ${(frame.share * 100).toFixed(1)}% ${frame.colour}, paper ${(paper * 100).toFixed(2)}%${claim.sample === null ? '' : `, data-sample=${claim.sample}`}`);
  });
} catch (error) {
  // NOTHING IS WRITTEN. A frame that cannot say it is a page is removed rather than left for a
  // downstream instrument to score as a measurement — that is the whole of this row.
  rmSync(out, { force: true });
  console.error(error.message);
  process.exit(1);
}
