#!/usr/bin/env node
// look — ONE command a look-bearing row runs before calling itself done: freeze the address, run
// the rendered detector on it, run the look-gate, and report the two AREA-WEIGHTED distributions
// the operator's two complaints ("the font size is too small", "there is no proper spacing") can
// actually be answered with.
//
// Usage: node dev/web-console/look.mjs '<url>' [--out DIR] [--width W] [--height H] [--json]
//
// WHY THE SNAPSHOT AND NOT THE URL: the console is behind the management-key gate (see
// snapshot.mjs).
//
// TWO PASSES, BECAUSE ONE ENGINE CANNOT SEE EVERYTHING.
//   dom    - detect.mjs reading the snapshot AS A FILE. A file target reaches the static-HTML
//            engine, which parses the markup AND resolves the cascade, so it emits rules a source
//            DIRECTORY cannot: a regex pass never sees a computed inset. This is what found the
//            board's flush cells (review B20) mechanically.
//   layout - the detector's own rendered rule set, run on OUR page over CDP. Those rules live in
//            one page-side bundle (detect-antipatterns-browser.js) that detect-url.mjs injects with
//            page.evaluate and then calls as window.impeccableDetect(...); nothing in it wants a
//            puppeteer handle, so the only thing puppeteer provides is evaluate - which this
//            campaign's CDP client does equally well. This is the only route to the rules that need
//            real geometry (clipped-overflow-container, text-occlusion, occlusion, contrast by
//            rect), and it is why no puppeteer install is needed. The bundle is READ FROM THE SKILL
//            at run time and never copied into this repo: a vendored rule set goes stale silently.
//
// THE OLD URL ROUTE IS A TRAP, AND IT IS STILL ASSERTED. Passing the snapshot as a file:// URL
// routes to the browser engine, which needs puppeteer; without it detect.mjs writes "Error:
// puppeteer is required for URL scanning" to stderr and EXITS 0 with `[]` on stdout, so a verify
// line chaining it reports a GREEN with nothing rendered. look.mjs never takes that route, and it
// blocks when neither of its two passes produced a finding.
//
// THE DISTRIBUTIONS. A count of CSS declarations has no weight in it: a strip's internal 4px field
// padding is one declaration and a few hundred pixels, the rack's 34px gap is one declaration and
// most of what the eye reads as rhythm, so counting them equally measures the stylesheet instead of
// the page (measured: the comp-faithful hero scored WORSE than nine of the pages it sets the
// standard for). These two are weighted by what the page actually shows: text by the rendered area
// of its own line boxes, gaps by the area between sibling boxes (the gap's size times the length of
// the edge it runs along).
import { execFileSync, spawnSync } from 'node:child_process';
import { existsSync, readFileSync, statSync } from 'node:fs';
import { resolve } from 'node:path';
import process from 'node:process';
import { mgmtKey, show, withChrome } from './lib/cdp.mjs';
import { LOOK_DIR, nameFor, snapshot } from './snapshot.mjs';

const ARGS = process.argv.slice(2);
const flag = (name, dflt) => {
  const i = ARGS.indexOf(`--${name}`);
  return i === -1 ? dflt : ARGS[i + 1];
};
const has = (name) => ARGS.includes(`--${name}`);

const SKILL = process.env.IMPECCABLE_SKILL ?? '/home/marcos/.claude/skills/impeccable';
const DETECT = process.env.IMPECCABLE_DETECT ?? `${SKILL}/scripts/detect.mjs`;
const GATE = resolve(import.meta.dirname ?? '.', 'look-gate.mjs');

/** Every text node's own line boxes, and every gap between laid-out sibling boxes: two censuses. */
const MEASURE = `(() => {
  const area = new Map();
  const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
  for (let node = walker.nextNode(); node !== null; node = walker.nextNode()) {
    if (node.textContent.trim() === '') continue;
    const el = node.parentElement;
    if (el === null) continue;
    const style = getComputedStyle(el);
    if (style.display === 'none' || style.visibility === 'hidden' || Number(style.opacity) === 0) continue;
    const range = document.createRange();
    range.selectNodeContents(node);
    let px = 0;
    for (const rect of range.getClientRects()) px += rect.width * rect.height;
    if (px === 0) continue;
    const size = parseFloat(style.fontSize);
    area.set(size, (area.get(size) ?? 0) + px);
  }
  const boxy = new Set(['block', 'flex', 'grid', 'list-item', 'table', 'table-row', 'flow-root']);
  const gaps = new Map();
  for (const parent of document.querySelectorAll('*')) {
    const kids = [...parent.children].filter((kid) => {
      const style = getComputedStyle(kid);
      if (style.display === 'none' || style.visibility === 'hidden') return false;
      if (style.position === 'absolute' || style.position === 'fixed') return false;
      return boxy.has(style.display);
    });
    if (kids.length < 2) continue;
    const rects = kids.map((kid) => kid.getBoundingClientRect());
    for (let i = 0; i + 1 < rects.length; i++) {
      const a = rects[i]; const b = rects[i + 1];
      const overV = Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top);
      const overH = Math.min(a.right, b.right) - Math.max(a.left, b.left);
      const across = b.left - a.right;
      const down = b.top - a.bottom;
      let size = null; let length = 0;
      if (overV > 2 && across >= 0) { size = across; length = overV; }
      else if (overH > 2 && down >= 0) { size = down; length = overH; }
      if (size === null || length <= 0) continue;
      const rung = Math.round(size);
      gaps.set(rung, (gaps.get(rung) ?? 0) + rung * length);
    }
  }
  const series = (map) => [...map.entries()].sort((x, y) => x[0] - y[0]);
  return { type: series(area), gap: series(gaps) };
})()`;

/** Share of a series' total that sits at or below a threshold. */
function below(series, threshold) {
  const total = series.reduce((sum, [, px]) => sum + px, 0);
  if (total === 0) return { pct: 0, total: 0 };
  const hit = series.filter(([size]) => size <= threshold).reduce((sum, [, px]) => sum + px, 0);
  return { pct: (hit / total) * 100, total };
}

function table(series, unit, limit) {
  const total = series.reduce((sum, [, px]) => sum + px, 0);
  if (total === 0) return '    (nothing measured)';
  return series
    .sort((a, b) => b[1] - a[1])
    .slice(0, limit)
    .map(([size, px]) => {
      // A distance of hundreds of pixels between two siblings is not a spacing rung, it is a
      // layout void (a space-between row, an empty rack). It is left in the denominator - dropping
      // it silently would be a denominator chosen to flatter the answer - and labelled, so whoever
      // reads the number knows which of the two they are looking at.
      const note = size > 200 ? '   <- layout void, not a rung' : '';
      return `    ${String(size).padStart(5)}${unit}  ${((px / total) * 100).toFixed(1).padStart(5)}%${note}`;
    })
    .join('\n');
}

/** The comp's own numbers, for the same two questions, from its measured regions: type sizes from
 *  the scaffold's cap heights, gaps from the pixel scans of M1-12. The comp's side is a census of
 *  measured sizes, not an area-weighted sum - its text masks are the next step, and saying which
 *  half is weighted keeps the comparison honest rather than flattering. */
function compReference() {
  const scaffold = 'webui/.impeccable/build/scaffold/layout.css';
  const sizes = new Map();
  try {
    for (const line of readFileSync(scaffold, 'utf8').split('\n')) {
      const size = line.match(/font:\s*(\d+(?:\.\d+)?)px/);
      if (size) sizes.set(Number(size[1]), (sizes.get(Number(size[1])) ?? 0) + 1);
    }
  } catch { /* the comp scaffold is gitignored; the reference is then simply absent */ }
  const named = [...sizes.entries()].sort((a, b) => a[0] - b[0]);
  return {
    type: named.length === 0 ? null : named.map(([px, n]) => `${px}px x${n}`).join(', '),
    gap: 'between-strip 34px, strip pitch 96px, rack slot rule 31-32px, cell padding 8px (M1-12 scans)',
  };
}

// ---------------------------------------------------------------------------------------------

const URL_ARG = ARGS.find((a) => !a.startsWith('--'));
if (URL_ARG === undefined || has('help')) {
  console.log("usage: node dev/web-console/look.mjs '<url>' [--out DIR] [--width W] [--height H] [--theme dark|light] [--json]");
  console.log('  freezes the address, runs the rendered detector and the look-gate on it, and');
  console.log('  reports the two area-weighted distributions (type area, sibling-gap area).');
  process.exit(URL_ARG === undefined && !has('help') ? 2 : 0);
}

const outDir = flag('out', LOOK_DIR);
const width = Number(flag('width', '1536'));
const height = Number(flag('height', '1024'));
// The theme is part of the snapshot's IDENTITY, not a setting applied to it, so it is in the
// filename. Two themes writing one path would have the second silently overwrite the first and
// the run would report two passes over one room — the shape of defect this campaign keeps
// finding, arriving this time as a filename collision.
const theme = flag('theme', 'dark');
if (theme !== 'dark' && theme !== 'light') {
  console.error(`REFUSED: --theme ${theme} is not dark or light. An unknown theme renders the default and reports on a room nobody asked for.`);
  process.exit(2);
}
const out = resolve(outDir, nameFor(URL_ARG).replace(/\.html$/, `-${theme}.html`));
const blocks = [];

// 1 — the snapshot
let bytes = 0;
try {
  ({ bytes } = await snapshot(URL_ARG, out, width, height, theme));
  if (!existsSync(out) || statSync(out).size < 1000) throw new Error('the snapshot is empty');
} catch (error) {
  blocks.push(['snapshot', error.message]);
}

// 2a — the DOM pass: the detector CLI reading the SNAPSHOT AS A FILE. A file target reaches the
// static-HTML engine, which parses the markup AND resolves the cascade (computed padding, colors,
// font sizes), so it emits rules a source-DIRECTORY scan structurally cannot: a regex pass never
// sees a computed inset. Passing the file as a URL instead routes to the browser engine, which
// needs puppeteer - and which, in this build, prints its failure to stderr and EXITS 0, so a verify
// line chaining it reports a green with nothing rendered.
let domFindings = [];
let domError = null;
if (blocks.length === 0) {
  const run = spawnSync('node', [DETECT, '--json', out], { encoding: 'utf8', maxBuffer: 1 << 28 });
  const stderr = run.stderr ?? '';
  try {
    domFindings = JSON.parse(run.stdout);
    if (!Array.isArray(domFindings)) throw new Error('no findings array');
  } catch (error) {
    domFindings = [];
    domError = /puppeteer is required/i.test(stderr)
      ? `the detector reached its browser engine and stopped there: ${stderr.trim().split('\n')[0]}`
      : `the detector printed no JSON (${error.message})${stderr.trim() === '' ? '' : `: ${stderr.trim().split('\n')[0]}`}`;
  }
}

// 2b — the LAYOUT pass, on our own page. The detector's rendered rule set lives in one page-side
// bundle (detect-antipatterns-browser.js) that detect-url.mjs injects with page.evaluate and then
// calls as window.impeccableDetect(...). Nothing in it wants a puppeteer handle: the rules run
// inside the page against the real DOM, so the only thing puppeteer provides at that point is
// evaluate - which this campaign's own CDP client does equally well. The bundle is READ FROM THE
// SKILL at run time and never copied into this repo, so a rule that changes upstream changes here.
const BUNDLE = process.env.IMPECCABLE_BROWSER_BUNDLE ?? `${SKILL}/scripts/detector/detect-antipatterns-browser.js`;

async function layoutFindings(send) {
  if (!existsSync(BUNDLE)) throw new Error(`the rule bundle is not at ${BUNDLE}`);
  const source = readFileSync(BUNDLE, 'utf8');
  await send('Runtime.evaluate', {
    expression: 'window.__IMPECCABLE_CONFIG__ = Object.assign(window.__IMPECCABLE_CONFIG__ || {}, { autoScan: false });',
  });
  await send('Runtime.evaluate', { expression: source });
  const out2 = await send('Runtime.evaluate', {
    expression: "JSON.stringify(typeof window.impeccableDetect === 'function' ? window.impeccableDetect({ decorate: false, serialize: true }) : null)",
    returnByValue: true,
  });
  const raw = out2?.result?.value;
  if (typeof raw !== 'string') throw new Error('the bundle did not expose window.impeccableDetect');
  const groups = JSON.parse(raw);
  if (groups === null) throw new Error('the bundle exposed no scan function');
  return groups.flatMap((group) => (group.findings ?? []).map((f) => ({ id: f.type, detail: f.detail, severity: f.severity })));
}

// 3 — the look-gate (its own static + capture checks, blocking set included)
const gate = spawnSync('node', [GATE, '--captures', 'webui/.impeccable/review/sections'], { encoding: 'utf8' });
if (gate.status !== 0) blocks.push(['look-gate', (gate.stdout ?? '').trim().split('\n').filter((l) => /FAIL/.test(l)).join(' · ') || 'blocking check failed']);

// 3b/4 — ONE browser session does both: the layout rules on the live page, and the two
// area-weighted censuses. Same Chrome the snapshot used, same seeded key, no second engine.
let measured = null;
let layout = [];
let layoutError = null;
try {
  const both = await withChrome({ 'myx-mgmt-key': mgmtKey() }, async (send) => {
    await show(send, URL_ARG, width, height);
    let rules = null;
    let failure = null;
    try { rules = await layoutFindings(send); } catch (error) { failure = error.message; }
    const result = await send('Runtime.evaluate', { expression: MEASURE, returnByValue: true });
    return { rules, failure, measured: result?.result?.value ?? null };
  });
  layout = both.rules ?? [];
  layoutError = both.failure;
  measured = both.measured;
} catch (error) {
  blocks.push(['measure', error.message]);
}

const ref = compReference();
if (has('json')) {
  console.log(JSON.stringify({ url: URL_ARG, snapshot: out, bytes, dom: domFindings, layout, measured, comp: ref, blocks }, null, 2));
} else {
  // frame and theme in the headline: this is run more than once per gate now, and two reports that
  // do not say which room and which size they read are two reports nobody can tell apart.
  console.log(`look — ${URL_ARG}  ${width}x${height} ${theme}\n`);
  console.log(`  snapshot   ${out} (${bytes} bytes)`);
  console.log(`  dom        ${domError === null ? `${domFindings.length} findings (static-HTML engine: markup + resolved cascade)` : `DID NOT RUN - ${domError}`}`);
  for (const finding of domFindings.slice(0, 8)) {
    console.log(`               ${finding.name ?? finding.antipattern ?? '?'}  ${(finding.description ?? finding.snippet ?? '').slice(0, 80)}`);
  }
  console.log(`  layout     ${layoutError === null ? `${layout.length} findings (the detector's own rule bundle, on our page over CDP)` : `DID NOT RUN - ${layoutError}`}`);
  for (const finding of layout.slice(0, 8)) {
    console.log(`               ${finding.id ?? '?'}  ${(finding.detail ?? '').slice(0, 88)}`);
  }
  if (domFindings.length === 0 && layout.length === 0) {
    blocks.push(['detect', `no rule ran on the page: ${domError ?? ''} ${layoutError ?? ''}`.trim()]);
  }
  console.log(`  look-gate  ${blocks.some(([who]) => who === 'look-gate') ? 'FAIL (blocking)' : 'pass'}`);
  if (measured !== null) {
    const type = below(measured.type, 12);
    const gap = below(measured.gap, 8);
    console.log('\n  TYPE by rendered text area (live boxes)');
    console.log(table(measured.type, 'px', 8));
    console.log(`    ${type.pct.toFixed(0)}% of the text area is set at 12px or smaller`);
    console.log(`  comp reference (measured regions): ${ref.type ?? 'scaffold not present'}`);
    console.log('\n  GAP by area between sibling boxes (live boxes)');
    console.log(table(measured.gap, 'px', 8));
    console.log(`    ${gap.pct.toFixed(0)}% of the gap area is 8px or smaller`);
    console.log(`  comp reference (pixel scans): ${ref.gap}`);
  }
  for (const [who, detail] of blocks) console.log(`\n  BLOCKED  ${who}: ${detail}`);
  console.log(`\n  LOOK: ${blocks.length === 0 ? 'PASS' : `BLOCKED (${blocks.length})`}`);
}
process.exit(blocks.length === 0 ? 0 : 1);
