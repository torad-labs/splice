/**
 * M1-113: THE NON-STRIP BAND INSIDE A BAY, COUNTED FROM THE DOM OF ALL THIRTEEN ADDRESSES.
 *
 * WHY THIS EXISTS. M1-110 measured logs, the largest field-grid failure in the console at 159px
 * across 23 scanlines, and found it was not a grid failure at all: the leg collects a scanline per
 * pale band in the bay, and the log tail's own header (`.myx-lt-head`) is not a `.myx-strip` yet
 * sits INSIDE the bay, so the leg read the header's BOX BORDER at x=166 against the data rows'
 * first CELL DIVIDER at x=325 and called the difference 159px. design-builder measured the same
 * shape on turns: thirteen scanlines at x=411 exactly, the landed rack agreeing to the pixel, and
 * three outliers at 243/234/240 plus one at 335 from a non-strip `<Band>` inside the landed Bay.
 *
 * So on two of the three reds the rack was never wrong, and on accounts (34px, seven scanlines at
 * 373 against two at 407) it genuinely was. A census that only looked at the pages that happened
 * to be red could not say which, and NOBODY HAD COUNTED THESE BANDS. This is the denominator taken
 * from the source - the DOM of every address, red or not - rather than from the list of reds.
 *
 * THE STRUCTURAL QUESTION IT ANSWERS. A bay is for strips; a widget header is chrome. If these
 * bands belong OUTSIDE the bay, moving them fixes the pages without touching look-gate.mjs at all,
 * dissolves the 41px header-vs-row question, and stops the next page inheriting it. If some
 * genuinely belong inside, this census names which and why, which is the input M1-108 needs to
 * choose its anchor. NOTE WHAT THIS INSTRUMENT DOES NOT DECIDE: it reports, per band, whether it
 * WOULD contribute a false red. It does not move anything.
 *
 * A BAND CONTRIBUTES A FALSE RED WHEN it is (1) not a `.myx-strip`, (2) inside a `.myx-bay`,
 * (3) tall enough that `stripScanlines` collects it as a scanline run, (4) painting a border in
 * `--strip-field-line`'s own ink, so `fieldBorders` finds a pixel on it, and (5) that pixel sits
 * more than 2px from the bay's data rows' first field edge - 2px being the leg's own tolerance.
 * All five are needed: a band that draws no field-line border is invisible to the leg whatever its
 * box, which is why the census reports the border test separately rather than assuming it.
 *
 * Usage: bun webui/.impeccable/review/bands/bands.mjs [out.json]
 *        bun webui/.impeccable/review/bands/bands.mjs --selftest
 */
import fs from 'node:fs';
import path from 'node:path';
import { mgmtKey, withChrome, show } from '../../../../dev/web-console/lib/cdp.mjs';
import { addresses } from '../../../../dev/web-console/lib/fixtures.mjs';
// The gate's own fixture map, not a second copy of it: a band census that loaded the wrong fixture
// would measure a page nobody ships, which is the M1-83 failure this campaign already paid for.
const FIXTURES = {
  accounts: 'accounts', compaction: 'compaction', doctor: 'doctor', logs: 'tail',
  models: 'models', projects: 'list', sessions: 'board', settings: 'settings',
  teams: 'hero', turns: 'board', usage: 'usage',
};

const FRAME = [1536, 1024];
const THEME = 'dark';

/** The address as the browser must receive it, with the fixture in both search and hash (M1-83). */
export function captureUrl(address) {
  const fixture = FIXTURES[address];
  if (fixture === undefined) return `http://localhost:5173/#/${address}`;
  return `http://localhost:5173/?fixture=${fixture}#/${address}?fixture=${fixture}`;
}

/**
 * THE PREDICATE, PURE SO IT CAN BE MUTATION-PROVED (the row asks for a `--selftest`).
 *
 * @param band  { isStrip, height, fieldLineBorder, firstFieldX }
 * @param rows  the bay's data rows' first field edge x, or null when the bay has no rows to compare
 * @param tolerance the leg's own 2px
 */
export function falseRed(band, rowsFirstFieldX, tolerance = 2) {
  if (band.isStrip) return { contributes: false, why: 'it is a strip, so the leg is entitled to read it' };
  if (band.height < 20) return { contributes: false, why: `its box is ${band.height}px tall, under the 20px run stripScanlines needs` };
  // PALENESS, and this condition was MISSING from the first census, which is why it flagged the Bay
  // primitive's own head on four instances. `stripScanlines` does not collect every box in a bay: it
  // collects PALE RUNS, rows whose ground is more than half above mean luminance 150. A dark band is
  // not a scanline at all - it BREAKS a run - so no border on it is ever read, whatever its box or
  // its ink. Caught by cross-checking the census against a capture: sessions was flagged three times
  // and its capture reads ten scanlines at x=491, spread ZERO. A census that convicts a page the
  // pixels acquit is the same false-red class this row exists to remove, one level up.
  if (!band.pale) return { contributes: false, why: 'its ground is not pale, so stripScanlines never forms a run through it and the leg never reads it' };
  if (!band.fieldLineBorder) return { contributes: false, why: 'it holds a field but draws no border in the field-line ink, so fieldBorders finds nothing' };
  if (rowsFirstFieldX === null) return { contributes: false, why: 'the bay has no rows to compare against' };
  const delta = Math.abs(band.firstFieldX - rowsFirstFieldX);
  if (delta <= tolerance) return { contributes: false, why: `its first border is ${delta}px from the rows', inside the ${tolerance}px tolerance` };
  return { contributes: true, why: `its first field-line border is at x=${band.firstFieldX} against the rows' ${rowsFirstFieldX} - ${delta}px, outside the ${tolerance}px tolerance` };
}

/**
 * The ground an element is actually painted on, composited from its ancestors until opaque, and
 * whether that ground is pale by the leg's own threshold (mean luminance > 150).
 */
export function paleGround(rgb, threshold = 150) {
  if (rgb === null || rgb === undefined) return null;
  return (rgb[0] + rgb[1] + rgb[2]) / 3 > threshold;
}

const SELFTEST = [
  { name: 'a strip is never a false red', band: { isStrip: true, height: 64, pale: true, fieldLineBorder: true, firstFieldX: 166 }, rows: 325, want: false },
  { name: 'the logs header: not a strip, tall, pale, box border, first border far from the rows', band: { isStrip: false, height: 64, pale: true, fieldLineBorder: true, firstFieldX: 166 }, rows: 325, want: true },
  { name: 'a band too short for the leg to collect is not a red', band: { isStrip: false, height: 12, pale: true, fieldLineBorder: true, firstFieldX: 166 }, rows: 325, want: false },
  { name: 'a band that draws no field-line border is invisible to the leg', band: { isStrip: false, height: 64, pale: true, fieldLineBorder: false, firstFieldX: null }, rows: 325, want: false },
  { name: 'a band whose first border AGREES with the rows is not a red', band: { isStrip: false, height: 64, pale: true, fieldLineBorder: true, firstFieldX: 324 }, rows: 325, want: false },
  { name: 'a bay with no rows cannot convict a band', band: { isStrip: false, height: 64, pale: true, fieldLineBorder: true, firstFieldX: 166 }, rows: null, want: false },
  // THE CASE THE FIRST CENSUS NEEDED AND DID NOT HAVE: turns' Band and the Bay primitive's own head
  // both paint a dark ground, so stripScanlines never forms a run through them.
  { name: 'a DARK band is never a red however far its border is from the rows - the leg never reads it', band: { isStrip: false, height: 64, pale: false, fieldLineBorder: true, firstFieldX: 166 }, rows: 325, want: false },
];

if (process.argv.includes('--selftest')) {
  let bad = 0;
  for (const t of SELFTEST) {
    const got = falseRed(t.band, t.rows).contributes;
    const ok = got === t.want;
    if (!ok) bad += 1;
    console.log(`  ${ok ? 'ok  ' : 'FAIL'} ${t.name} (want ${t.want}, got ${got})`);
  }
  // A wall that cannot fail is not a wall: at least one case must produce a red.
  if (!SELFTEST.some((t) => t.want && falseRed(t.band, t.rows).contributes)) {
    console.log('  FAIL the predicate never fired on any synthetic violation');
    bad += 1;
  }
  console.log(bad === 0 ? 'selftest: ok' : `selftest: ${bad} case(s) FAILED`);
  process.exit(bad === 0 ? 0 : 1);
}

// ---------------------------------------------------------------------------------------------
// THE DOM PROBE
// ---------------------------------------------------------------------------------------------
const PROBE = `(() => {
  const css = getComputedStyle(document.documentElement);
  const fieldLine = css.getPropertyValue('--strip-field-line').trim();
  const norm = (s) => {
    const d = document.createElement('div'); d.style.color = s; document.body.appendChild(d);
    const v = getComputedStyle(d).color; d.remove(); return v;
  };
  const want = fieldLine === '' ? null : norm(fieldLine);
  const box = (el) => { const r = el.getBoundingClientRect();
    return { x: Math.round(r.left), y: Math.round(r.top), w: Math.round(r.width), h: Math.round(r.height) }; };
  const path = (el) => {
    const bits = []; let n = el;
    while (n && n.nodeType === 1 && bits.length < 4) {
      let s = n.tagName.toLowerCase();
      if (typeof n.className === 'string' && n.className.trim()) s += '.' + n.className.trim().split(/\\s+/).join('.');
      bits.unshift(s); n = n.parentElement;
    }
    return bits.join(' > ');
  };
  // THE GROUND, COMPOSITED FROM ANCESTORS UNTIL OPAQUE - the same substitution probe-ink.mjs makes,
  // and for the same reason: an element is not painted on the token it was declared against, it is
  // painted on the stack above it. Paleness is then the legs own threshold, mean luminance > 150.
  const parseBg = (s) => {
    const t = String(s || '').trim();
    if (!t || t === 'transparent' || t === 'none') return null;
    let m = t.match(/rgba?\(([^)]+)\)/);
    if (m) { const p = m[1].split(/[\s,\/]+/).filter(Boolean).map(Number);
             return { r: p[0], g: p[1], b: p[2], a: p.length > 3 ? p[3] : 1 }; }
    m = t.match(/color\(srgb\s+([\d.]+)\s+([\d.]+)\s+([\d.]+)(?:\s*\/\s*([\d.]+))?\)/);
    if (m) return { r: +m[1] * 255, g: +m[2] * 255, b: +m[3] * 255, a: m[4] === undefined ? 1 : +m[4] };
    return null;
  };
  const over = (f, b) => ({ r: f.a * f.r + (1 - f.a) * b.r, g: f.a * f.g + (1 - f.a) * b.g,
                            b: f.a * f.b + (1 - f.a) * b.b, a: 1 });
  const composite = (el) => {
    const stack = []; let n = el;
    while (n && n.nodeType === 1 && stack.length < 14) {
      const bg = parseBg(getComputedStyle(n).backgroundColor);
      if (bg && bg.a > 0) { stack.push(bg); if (bg.a >= 1) break; }
      n = n.parentElement;
    }
    if (stack.length === 0) return null;
    let base = stack[stack.length - 1];
    for (let i = stack.length - 2; i >= 0; i--) base = over(stack[i], base);
    return base;
  };
  const bays = [...document.querySelectorAll('.myx-bay')];
  const out = [];
  for (const bay of bays) {
    const strips = [...bay.querySelectorAll('.myx-strip')];
    const firstFields = strips.map((s) => {
      const f = s.querySelector('.myx-sfield');
      return f === null ? null : Math.round(f.getBoundingClientRect().left);
    }).filter((x) => x !== null);
    // The rows' first field edge: the mode, because one outlier row must not define the bay.
    const tally = new Map();
    for (const x of firstFields) tally.set(x, (tally.get(x) ?? 0) + 1);
    const rowsFirstFieldX = firstFields.length === 0 ? null
      : [...tally.entries()].sort((a, b) => b[1] - a[1] || a[0] - b[0])[0][0];
    const candidates = [...bay.querySelectorAll('*')].filter((el) => {
      if (el.closest('.myx-strip') !== null) return false;          // it IS a strip or lives in one
      if (el.classList.contains('myx-bay')) return false;            // the bay itself
      const r = el.getBoundingClientRect();
      if (r.width < 40 || r.height < 8) return false;                // nothing the leg could collect
      const cs = getComputedStyle(el);
      if (cs.visibility === 'hidden' || cs.display === 'none' || Number(cs.opacity) === 0) return false;
      return true;
    });
    // KEEP THE OUTERMOST CANDIDATE PER REGION, and the first cut of this got it backwards. The leg
    // does not read elements, it reads PALE RUNS: stripScanlines scans the bay width row by row and
    // takes the middle of each run over 20px tall, so the unit that forms a scanline is the BAND,
    // not the controls painted inside it. Keeping the innermost candidate reported logs as "the
    // search input and the follow Flag would red the leg" - both true of those boxes and both beside
    // the point, because they sit INSIDE the header band whose run is what the leg collects. A
    // census whose unit is one level below the thing counted counts the wrong population.
    // NOTE, paid for once already in this file: backticks are illegal in this comment. The whole
    // probe is a template literal, so one terminates it - the same scar M1-80 recorded against the
    // MEASURE template, and this comment quoted a function name in backticks and did exactly that.
    const outer = candidates.filter((el) => !candidates.some((o) => o !== el && o.contains(el)));
    const isBordered = (el) => {
      if (want === null) return false;
      const cs = getComputedStyle(el);
      return [cs.borderTopColor, cs.borderRightColor, cs.borderBottomColor, cs.borderLeftColor].some((c) => c === want);
    };
    for (const el of outer) {
      // THE BOX THE LEG ACTUALLY READS A BORDER ON: the leftmost element in this band subtree that
      // paints a border in --strip-field-line, self included. Neither of the two earlier units was
      // this. The INNERMOST candidate reported the search input and the follow Flag - elements
      // painted inside the band, not the band. The OUTERMOST counted the wrappers (.myx-bay-rows,
      // .myx-lt), which paint no field-line border at all and so convicted nothing: 99 bands, 0
      // reds, while the capture goes on reading 166 against 325. A predicate about borders has to be
      // evaluated on the box that HAS one, and the leftmost such box is the first pixel the leg
      // meets scanning right along the run.
      // AND IT MUST EXCLUDE THE STRIPS' OWN INTERIORS. Third correction, same class: the unit of
      // measurement. The band is the box the leg meets, but the pixels it reads between the band's
      // edge and the rows' first divider include every strip's field boxes - so a scan of the whole
      // subtree took its minimum over the ROWS THEMSELVES, found the rows' own x, and cleared every
      // band on the page: turns reported 0 reds with 24, 39 and 84 bordered boxes per bay-rows,
      // none of them the Band design-builder had already measured at 243. The question is what the
      // run reads BEFORE the rows' own grid, so the scan is the band subtree MINUS anything inside a
      // .myx-strip - the same exclusion the candidate filter above already makes, applied twice.
      const bordered = [el, ...el.querySelectorAll('*')]
        .filter((b) => b.closest('.myx-strip') === null)
        .filter(isBordered);
      const firstBorderX = bordered.length === 0 ? null
        : Math.min(...bordered.map((b) => Math.round(b.getBoundingClientRect().left)));
      const f = el.querySelector('.myx-sfield');
      out.push({
        bay: path(bay), sel: path(el), box: box(el),
        isStrip: el.classList.contains('myx-strip'),
        height: Math.round(box(el).h),
        ground: (() => { const g = composite(el); return g === null ? null : Math.round((g.r + g.g + g.b) / 3); })(),
        pale: (() => { const g = composite(el); return g === null ? null : (g.r + g.g + g.b) / 3 > 150; })(),
        fieldLineBorder: bordered.length > 0,
        borderedBoxes: bordered.length,
        firstFieldX: firstBorderX,
        hasField: f !== null,
        fieldX: f === null ? null : Math.round(f.getBoundingClientRect().left),
        rowsFirstFieldX,
        stripCount: strips.length,
        text: (el.textContent || '').trim().slice(0, 40),
      });
    }
  }
  return JSON.stringify({ fieldLine, want, bands: out });
})()`;

const outPath = process.argv[2] ?? path.join(import.meta.dirname, 'bands.json');
const all = [];
let failed = 0;

for (const address of addresses()) {
  try {
    const res = await withChrome({ 'myx-mgmt-key': mgmtKey(), 'splice.theme': THEME }, async (send) => {
      await show(send, captureUrl(address), FRAME[0], FRAME[1], 6000);
      const r = await send('Runtime.evaluate', { expression: PROBE, returnByValue: true });
      return JSON.parse(r.result.value);
    });
    const bands = res.bands.filter((b) => !b.isStrip);
    for (const b of bands) {
      const v = falseRed(b, b.rowsFirstFieldX);
      all.push({ address, ...b, contributes: v.contributes, why: v.why });
    }
    const red = bands.filter((b) => falseRed(b, b.rowsFirstFieldX).contributes);
    console.log(`${address.padEnd(11)} ${bands.length} band(s) inside a bay, ${red.length} would red the leg`
      + (red.length ? `  -> ${[...new Set(red.map((b) => b.sel))].join(' | ')}` : ''));
  } catch (e) {
    failed += 1;
    console.log(`FAIL ${address}: ${e.message.split('\n')[0]}`);
  }
}

fs.writeFileSync(outPath, JSON.stringify({ frame: FRAME, theme: THEME, at: new Date().toISOString(), bands: all }, null, 1));

// THE CENSUS IS THE DENOMINATOR, so an empty one refuses rather than printing a clean sweep.
if (all.length === 0 && failed === 0) {
  console.error('\nbands: BLIND - no band was found on any address, so nothing was counted. The probe or the pages changed shape.');
  process.exit(1);
}
const reds = all.filter((b) => b.contributes);
const bySel = new Map();
for (const b of reds) bySel.set(b.sel, (bySel.get(b.sel) ?? 0) + 1);
console.log(`\n${all.length} band(s) across ${addresses().length} address(es); ${reds.length} would contribute a false red`);
for (const [sel, n] of [...bySel].sort((a, b) => b[1] - a[1])) console.log(`  ${String(n).padStart(3)}  ${sel}`);
console.log(`wrote ${outPath}`);
if (failed > 0) { console.error(`REFUSE: ${failed} address(es) failed to render - a page that threw is not a page that passed`); process.exit(1); }
