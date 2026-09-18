// THE COMP'S CONSTANTS ARE A WALL.
//
// WHY THIS EXISTS: the operator ruled that the approved comp is right and the build is wrong
// (campaign law COMP OF RECORD), so every look defect is a build defect until measured otherwise.
// The only instrument that ever compared build to comp was the hero diff, and it looked at one
// page. Twelve addresses have never been measured against anything. This measures the same
// constants the comp was measured for, on every address, and fails by name.
//
// THE COMP IS TWO FILES, BOTH MACHINE-MEASURED, NEVER RE-TYPED HERE:
//   webui/.impeccable/build/spec.json          region boxes in fractions of the 1536x1024 frame,
//                                              with the measured cap height of every text region
//   webui/.impeccable/build/scaffold/layout.css  the same values as `--r-<region>-<key>` customs
// A number copied into this file would agree with itself while the comp drifted, which is the
// failure mode the whole campaign is about.
//
// THE TWO CONSTANTS THE REVIEW FOUND BROKEN, and what each one catches:
//   the x of a strip's field origin inside its bay — the rack staggers today (review B1), so two
//     strips in one bay start their fields at different x. The comp puts every strip 0.7% of the
//     frame inside its bay's x (lead-strip 10.5 - bay-claude 9.8; builder-strip 39.7 - bay-deepseek
//     39.0), so the constant is the OFFSET, which is what a stagger moves.
//   the rendered cap height and family of every text role — two files fake a condensed face with
//     scale transforms (review B2), so a font-size check passes while the glyphs render at a size
//     nothing measured. The cap height here is the INK height of a capital in the face that
//     actually renders, multiplied by the element's accumulated transform scale.
//
// TOLERANCES — each one is what the comp itself justifies, and the reason is the entry:
const TOLERANCE = {
  // The spec's detector reports boxes rounded to 0.1% of the frame and the scaffold is generated
  // from those, so a point of the frame is the width of the source's own uncertainty. Tighter
  // would fail the comp against itself.
  box: { limit: 1.0, unit: 'percent of frame', why: 'the comp boxes are reported to 0.1% and the scaffold is generated from them' },
  // Cap heights are ink runs measured on a 1536-wide raster; half a pixel is that measurement's
  // granularity and is below a visible step at these sizes.
  cap: { limit: 0.5, unit: 'px', why: 'the comp caps are ink runs on a 1536-wide raster; half a pixel is their granularity' },
  // A count is not a measurement: the comp shows a fixed set, and one more or one fewer is a
  // different page rather than a drifted number.
  count: { limit: 0, unit: 'exact', why: 'a count is not a measurement; the comp shows a fixed set' },
  // Reported in CSS pixels with no comp value: a reading for the reviewer, never a comparison.
  px: { limit: 0, unit: 'px', why: 'no comp region carries this; it is printed for the reviewer' },
  // A face is a name, not a measurement. The world has exactly two (CONTRACTS.md section 1:
  // --font-label Archivo, --font-figure JetBrains Mono), so a near miss is a different face.
  family: { limit: 0, unit: 'exact', why: 'CONTRACTS.md section 1 licenses exactly two faces' },
  // A scale transform on text is how a condensed face is faked: it makes a font-size check pass
  // while the glyphs render at a size nothing measured.
  scale: { limit: 0, unit: 'exact', why: 'a scaled text element renders at a size no token declares (review B2)' },
};

import { readFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { mgmtKey, show, withChrome } from './lib/cdp.mjs';
// The address-to-fixture mapping lives in ONE place and is checked against the pages. This file
// carried the THIRD copy of it, still naming 'demo' for six addresses, which made every rack row in
// its table a measurement of live daemon data (M1-28).
import { FIXTURES, urlFor as fixtureUrl } from './lib/fixtures.mjs';

const ROOT = resolve(import.meta.dirname, '../..');
// The comp's own frame, and the default because every constant here was measured on it.
//
// It was spelled `FRAMES = [[1536, 1024]]` until 2026-09-18 and only `FRAMES[0]` was ever read —
// generality that looked like multi-frame support and was not. M1-33 found what that cost: nothing
// in the milestone exit gate rendered at a second frame, so every finding of the 3840 blind pass
// (13.7% paper coverage, 9px ink on knob names, 801px voids in the rule bar) passed all nine legs.
// The operator's monitors are 3840x2160 and the console was checked at 1536 for two days.
//
// A constant is still only COMPARED against the comp at the comp's frame — the comp is 1536x1024
// and a delta against it at another size would be a number about the scalar, not about the build.
// What a second frame buys is the rest of the run: the text roles, the box measures and the
// overflow they expose are real at any size, and that is where the 3840 defects live.
const DEFAULT_FRAME = [1536, 1024];

// ---------------------------------------------------------------------- the comp

/** Region boxes (fractions of the frame) and the scaffold's `--r-*` customs, read from the two
 *  files the row names. Nothing here is retyped: a comp constant is only ever a lookup. */
function comp() {
  const spec = JSON.parse(readFileSync(join(ROOT, 'webui/.impeccable/build/spec.json'), 'utf8'));
  const layout = readFileSync(join(ROOT, 'webui/.impeccable/build/scaffold/layout.css'), 'utf8');
  const boxes = {};
  for (const region of spec.regions) boxes[region.id] = region.box;
  const vars = {};
  for (const [, name, value] of layout.matchAll(/(--r-[a-z0-9-]+)\s*:\s*([^;]+);/g)) vars[name.trim()] = value.trim();
  return { boxes, vars, frame: spec.compSize, source: { spec: spec.comp, regions: spec.regions.length } };
}

const COMP = comp();
const box = (id) => COMP.boxes[id];
const pct = (id, key) => box(id)[key] * 100;
const custom = (name) => {
  const value = COMP.vars[name];
  if (value === undefined) throw new Error(`layout.css carries no ${name}`);
  return parseFloat(value);
};

// ------------------------------------------------------------------ the addresses

/** The addresses, read from the shell's own table: a fourteenth address added by a later row has
 *  to appear here without an edit, and a list written out in this file could not fail for one that
 *  was missing from itself. */
function addresses() {
  const source = readFileSync(join(ROOT, 'webui/src/app/rows.ts'), 'utf8');
  const block = source.match(/export const ADDRESSES = \[([\s\S]*?)\] as const;/);
  if (block === null) throw new Error('rows.ts: the ADDRESSES table was not found');
  return [...block[1].matchAll(/'([a-z0-9-]+)'/g)].map((match) => match[1]);
}

/** The live probe. It returns geometry and type, never a verdict: every comparison happens on this
 *  side, so the thresholds live in one place and the page cannot grade itself. */
const PROBE = `(() => {
  const rect = (el) => { if (!el) return null; const r = el.getBoundingClientRect();
    return { x: r.x, y: r.y, w: r.width, h: r.height }; };
  const one = (sel, root) => (root || document).querySelector(sel);
  const cs = (el) => getComputedStyle(el);

  /* The accumulated transform scale up the ancestor chain. */
  const scaleOf = (el) => { let s = 1;
    for (let n = el; n && n.nodeType === 1; n = n.parentElement) {
      const t = cs(n).transform;
      if (t && t !== 'none') { const m = new DOMMatrix(t);
        const det = Math.abs(m.a * m.d - m.b * m.c);
        if (det > 0) s *= Math.sqrt(det); } }
    return s; };

  const canvas = document.createElement('canvas');
  const ctx = canvas.getContext('2d');
  const probeText = 'splice HANDOFF 09:41:27';
  /* The ink height of a capital in the face that renders, at the element's own computed size. */
  const capOf = (el) => { const s = cs(el);
    ctx.font = s.fontWeight + ' ' + s.fontSize + ' ' + s.fontFamily;
    const m = ctx.measureText('H');
    return m.actualBoundingBoxAscent; };
  const faceOf = (el) => { const s = cs(el);
    const first = s.fontFamily.split(',')[0].trim().replace(/^["']|["']$/g, '');
    ctx.font = '100px ' + s.fontFamily;
    const withFace = ctx.measureText(probeText).width;
    ctx.font = '100px sans-serif';
    const sans = ctx.measureText(probeText).width;
    ctx.font = '100px monospace';
    const mono = ctx.measureText(probeText).width;
    /* A named face whose advance is byte-identical to a generic one is not rendering. */
    const generic = first === 'sans-serif' || first === 'monospace' || first === 'serif';
    const fellBack = !generic && (Math.abs(withFace - sans) < 0.01 || Math.abs(withFace - mono) < 0.01);
    return { first, weight: s.fontWeight, size: parseFloat(s.fontSize), cap: capOf(el),
      scale: scaleOf(el), fellBack }; };
  const role = (name, el) => (el ? Object.assign({ name }, faceOf(el)) : null);

  const bays = [...document.querySelectorAll('.myx-bay')].map((b) => {
    const s = cs(b);
    const label = one('.myx-bay-label', b);
    return { rect: rect(b),
      rails: { top: parseFloat(s.borderTopWidth) || 0, bottom: parseFloat(s.borderBottomWidth) || 0 },
      labelCentre: label && b.getBoundingClientRect().width > 0
        ? (((rect(label).x + rect(label).w / 2) - rect(b).x) / b.getBoundingClientRect().width) * 100 : null,
      strips: [...b.querySelectorAll('.myx-strip')].map((st) => {
        const first = one('.myx-sfield', st);
        const fs = first ? cs(first) : null;
        const fl = first ? one('.myx-sfield-label', first) : null;
        const fr = rect(first); const lr = rect(fl);
        return { rect: rect(st), fieldOrigin: rect(first), fields: st.querySelectorAll('.myx-sfield').length,
          field: fs ? {
            /* The declared padding, and where the label's INK starts inside the box. Both are
               reported because a composition may move the inset onto the label: the board does, and
               it widens the label by its own padding into a full-width rule (negative margins), so
               an ink-relative reading goes negative there and the declared value is the honest one. */
            padStart: parseFloat(fs.paddingInlineStart) || 0,
            padEnd: parseFloat(fs.paddingInlineEnd) || 0,
            padTop: parseFloat(fs.paddingBlockStart) || 0,
            labelPadStart: fl ? parseFloat(cs(fl).paddingInlineStart) || 0 : null,
            labelInkStart: lr && fr ? +((lr.x + (parseFloat(cs(fl).paddingInlineStart) || 0)) - fr.x).toFixed(1) : null,
            divider: parseFloat(fs.borderInlineEndWidth) || parseFloat(fs.borderRightWidth) || 0,
            labelRule: fl ? parseFloat(cs(fl).borderBottomWidth) || 0 : 0 } : null };
      }) };
  });

  return JSON.stringify({
    frame: { w: window.innerWidth, h: window.innerHeight },
    rail: rect(one('.myx-rail')), railTabs: rect(one('.myx-rail-tabs')), railTab: rect(one('.myx-rail-tab')),
    tabs: document.querySelectorAll('.myx-rail-tab').length,
    rule: rect(one('.myx-rule')),
    cells: { wordmark: rect(one('.myx-rule-wordmark')), clocks: rect(one('.myx-rule-clocks')),
      health: rect(one('.myx-rule-health')), window: rect(one('.myx-rule-window')),
      none: rect(one('.myx-rule-none')) },
    text: [
      role('rule.wordmark', one('.myx-rule-wordmark')),
      role('rule.clocks', one('.myx-rule-clocks')),
      role('rule.health', one('.myx-rule-health')),
      role('rule.window', one('.myx-rule-window')),
      role('rule.none', one('.myx-rule-none')),
      role('bay.label', one('.myx-bay-label')),
      role('strip.field-label', one('.myx-sfield-label')),
      role('strip.field-value', one('.myx-sfield-value')),
      role('rail.tab', one('.myx-rail-tab')),
    ].filter(Boolean),
    bays,
  });
})()`;

// ------------------------------------------------------------------- the constants

/** Every constant, as one row: what it is, where the comp carries it, and how to read it off a
 *  measurement. `comp` is a thunk so a missing comp value is a named failure rather than a NaN. */
const CONSTANTS = [
  { id: 'rail.x', kind: 'box', comp: () => pct('rail', 'x'), got: (m) => m.rail && (m.rail.x / m.frame.w) * 100 },
  { id: 'rail.w', kind: 'box', comp: () => pct('rail', 'w'), got: (m) => m.rail && (m.rail.w / m.frame.w) * 100 },
  // The comp's `rail-labels` region is the LABEL COLUMN (x 1.2%, w 7.0%), not one plate: the plates
  // sit inside it. So the column is what compares, and the plate prints beside it uncompared.
  { id: 'rail.column.x', kind: 'box', comp: () => pct('rail-labels', 'x'), got: (m) => m.railTabs && (m.railTabs.x / m.frame.w) * 100 },
  { id: 'rail.column.y', kind: 'box', comp: () => pct('rail-labels', 'y'), got: (m) => m.railTabs && (m.railTabs.y / m.frame.h) * 100 },
  { id: 'rail.column.w', kind: 'box', comp: () => pct('rail-labels', 'w'), got: (m) => m.railTabs && (m.railTabs.w / m.frame.w) * 100 },
  { id: 'rail.plate.x', kind: 'box', comp: () => null, got: (m) => m.railTab && (m.railTab.x / m.frame.w) * 100,
    note: 'spec.json has no plate region; rail.css cites the crop (80px plates in a 107.5px column)' },
  { id: 'rail.plate.w', kind: 'box', comp: () => null, got: (m) => m.railTab && (m.railTab.w / m.frame.w) * 100,
    note: 'spec.json has no plate region; rail.css cites the crop (80px plates in a 107.5px column)' },
  { id: 'rail.plate.h', kind: 'box', comp: () => null, got: (m) => m.railTab && (m.railTab.h / m.frame.h) * 100,
    note: 'spec.json has no plate region; rail.css cites the crop (29px on this column)' },
  { id: 'rail.plates', kind: 'count', comp: () => 13, got: (m) => m.tabs,
    note: 'spec.json rail-labels: "thirteen page labels as small plates"' },
  { id: 'rule.h', kind: 'box', comp: () => pct('top-rule', 'h'), got: (m) => m.rule && (m.rule.h / m.frame.h) * 100 },
  { id: 'rule.wordmark.x', kind: 'box', comp: () => pct('wordmark', 'x'), got: (m) => m.cells.wordmark && (m.cells.wordmark.x / m.frame.w) * 100 },
  { id: 'rule.clocks.x', kind: 'box', comp: () => pct('clocks', 'x'), got: (m) => m.cells.clocks && (m.cells.clocks.x / m.frame.w) * 100 },
  { id: 'rule.health.x', kind: 'box', comp: () => pct('health', 'x'), got: (m) => m.cells.health && (m.cells.health.x / m.frame.w) * 100 },
  // The comp fixes these two at an x. The console FLOWS them inside .myx-rule-signals (rule.css
  // carries the reason: two signals the comp never had sit beside health, and a reserved slot made
  // the connection clip), so what is compared is where the readout's ink starts.
  { id: 'rule.window.x', kind: 'box', comp: () => pct('nearest-window', 'x'), got: (m) => m.cells.window && (m.cells.window.x / m.frame.w) * 100,
    note: 'flows in .myx-rule-signals (rule.css); the comp fixes the x' },
  { id: 'rule.none.x', kind: 'box', comp: () => pct('no-window', 'x'), got: (m) => m.cells.none && (m.cells.none.x / m.frame.w) * 100,
    note: 'flows in .myx-rule-signals (rule.css); the comp fixes the x' },
  // The rack: a strip's own inset inside the bay it is racked in, which is what a stagger moves.
  { id: 'strip.inset-x', kind: 'box', comp: () => pct('lead-strip', 'x') - pct('bay-claude', 'x'),
    got: (m) => { const bay = m.bays.find((b) => b.strips.length > 0); return bay ? ((bay.strips[0].rect.x - bay.rect.x) / m.frame.w) * 100 : null; } },
  { id: 'strip.h', kind: 'box', comp: () => pct('lead-strip', 'h'), got: (m) => { const s = firstStrip(m); return s && (s.rect.h / m.frame.h) * 100; } },
  // The head plate, as the comp actually places it: the label plate's CENTRE inside its own bay,
  // measured against the bay's own width (the plate is centred on the rack, not pinned to its left
  // edge — the comp's two bays put it at 45.0% and 47.0% of their widths, so the band is 2 points
  // and the tolerance is the box's 1 point around the first).
  // BAY-CLAUDE on both sides, corrected 2026-09-18 (found by design-builder on M1-24).
  //
  // This compared two DIFFERENT BAYS and had done since it was written: the comp side read
  // bay-DEEPSEEK's plate (45.45% of its rack) while `got` takes the FIRST bay in the DOM, which is
  // bay-claude. It therefore failed on all thirteen pages with the build correct — the comp's own
  // bay-claude plate sits at 47.14% and the build renders 47.13%.
  //
  // It is worth naming what the failure looked like, because it is the reason it survived: a
  // constant that is red everywhere reads as a real world-wide defect, and a red row nobody can
  // fix eventually gets a punch-list entry instead of an audit. The tell was that the delta never
  // moved no matter what anyone did to the bays.
  //
  // The two bays genuinely differ (45.45% and 47.14%), so there is no single number here and no
  // averaging it: the comp side must name the bay the `got` side reads.
  { id: 'bay.label-centre', kind: 'box',
    comp: () => ((pct('bay-claude-label', 'x') + pct('bay-claude-label', 'w') / 2 - pct('bay-claude', 'x')) / pct('bay-claude', 'w')) * 100,
    got: (m) => { const bay = m.bays.find((b) => b.labelCentre !== null && b.labelCentre !== undefined);
      return bay ? bay.labelCentre : null; },
    note: 'first bay in the DOM against the comp\'s first bay; the comp\'s two bays differ (47.1% and 45.5%)' },
  // The rack's rails: spec.json carries the bay as a region whose rails the comp's crop shows as
  // hairlines, but carries no rail WIDTH, so the rule is the shape rather than a number: a bay with
  // no top and bottom rail did not apply the comp.
  // REPORTED, NOT FAILED: the comp shows the rack's hairlines but spec.json carries no rail width, so
  // there is no comp number to be outside of. It prints because the board composes its rack
  // differently from a plain bay — its bays are border:none (board.css:102) and the hairlines come
  // from the empty slots (board.css:151-152), while a plain bay carries them itself (ui.css:362-363)
  // — and a reviewer needs to see which composition an address used.
  { id: 'bay.rail-top', kind: 'box', comp: () => null, got: (m) => (m.bays[0] ? m.bays[0].rails.top : null),
    note: 'comp carries no rail width; the board draws rails on its slots (board.css:151), a plain bay on itself (ui.css:362)' },
  { id: 'bay.rail-bottom', kind: 'box', comp: () => null, got: (m) => (m.bays[0] ? m.bays[0].rails.bottom : null),
    note: 'comp carries no rail width; the board draws rails on its slots (board.css:152), a plain bay on itself (ui.css:363)' },
  // The field box: the comp's cell was measured for the CSS (label ink 216, divider 229, value ink
  // 241, strip 208..269) but spec.json carries no field region, so the padding has no machine value
  // to compare against and prints what renders for the reviewer to read against that crop. The
  // divider and the label rule DO have a comp shape — the crop shows both — so they are rules.
  { id: 'field.pad-start', kind: 'box', comp: () => null, got: (m) => firstField(m) && firstField(m).padStart,
    note: 'comp carries no field region; the CSS cites the crop (label ink 216, divider 229, value ink 241)' },
  { id: 'field.pad-end', kind: 'box', comp: () => null, got: (m) => firstField(m) && firstField(m).padEnd,
    note: 'comp carries no field region; measured for the reviewer' },
  { id: 'field.label-pad-start', kind: 'box', comp: () => null, got: (m) => firstField(m) && firstField(m).labelPadStart,
    note: 'the inset a composition may move onto the label instead of the box' },
  { id: 'field.label-ink-start', kind: 'px', comp: () => null, got: (m) => firstField(m) && firstField(m).labelInkStart,
    note: 'where the label ink starts inside the box, in px: the number a reviewer reads against the crop' },
  { id: 'field.divider', kind: 'box', comp: () => null, got: (m) => firstField(m) && firstField(m).divider,
    fails: (v) => !(v >= 1), note: 'the comp cell carries a vertical divider; the world declares --hair (1px)' },
  { id: 'field.label-rule', kind: 'box', comp: () => null, got: (m) => firstField(m) && firstField(m).labelRule,
    fails: (v) => !(v >= 1), note: 'the comp cell carries the rule at row y=229; the world declares --hair (1px)' },
];

const firstStrip = (m) => { for (const b of m.bays) if (b.strips.length > 0) return b.strips[0]; return null; };
const firstField = (m) => { const s = firstStrip(m); return s && s.field; };

/** The text roles, each against the comp region that measured it. A role the comp never measured
 *  prints its rendered numbers with no comp value rather than a pass it did not earn. */
const TEXT_ROLES = [
  { id: 'text.wordmark', probe: 'rule.wordmark', region: 'wordmark' },
  { id: 'text.clocks', probe: 'rule.clocks', region: 'clocks' },
  { id: 'text.health', probe: 'rule.health', region: 'health' },
  { id: 'text.window', probe: 'rule.window', region: 'nearest-window' },
  { id: 'text.none', probe: 'rule.none', region: 'no-window' },
  // The comp measured three bay labels; two were single-glyph crops whose cap is noise (2.7px,
  // 3.3px). The two that measured real lettering are the ones compared: the deepseek bay label
  // (23 glyphs, 10.9px) and the chat label (25 glyphs, 9.8px). This role uses the first.
  { id: 'text.bay.label', probe: 'bay.label', region: 'bay-deepseek-label', cap: custom('--r-bay-deepseek-label-cap') },
  // No comp region measured the strip's own lettering.
  { id: 'text.strip.field-label', probe: 'strip.field-label', region: null },
  { id: 'text.strip.field-value', probe: 'strip.field-value', region: null },
  { id: 'text.rail.tab', probe: 'rail.tab', region: null },
];

const LICENSED = ['Archivo', 'JetBrains Mono'];

// ------------------------------------------------------------------- the report

const failures = [];
const rows = [];

const UNIT = { box: '%', cap: 'px' };
const fmt = (value, kind) => {
  if (typeof value !== 'number' || !Number.isFinite(value)) return String(value);
  if (kind === 'cap' || kind === 'px') return `${value.toFixed(2)}px`;
  if (kind === 'box') return `${value.toFixed(2)}%`;
  return String(value);
};

/** Record one constant. `fails` is the verdict rule: the tolerance by default, and a named rule
 *  where the comp carries a shape rather than a number (a bay whose rail is missing). */
function record(address, id, kind, compValue, gotValue, note, fails) {
  // An absolute-pixel constant is compared against the comp's value SCALED BY THE FRAME, not
  // dropped and not compared raw.
  //
  // Raw would be wrong: since M1-26 the root tracks the viewport, so the comp's 10.9px cap is a
  // CORRECT 27.25px cap at 3840 and failing it there measures the scalar rather than the build.
  // Dropping it would be worse, and was this function's first draft tonight — it would have taken
  // the only rows that can see ink-too-small and made them invisible at exactly the frame where
  // the operator's 9px labels live. The whole finding of 2026-09-18 is that the console was
  // measured at 1536 and used at 3840; a check that stops measuring type at 3840 re-creates it.
  //
  // Scaled is also the sharper test, because it is M1-26's own claim stated as an assertion: the
  // console at any viewport IS the comp frame's render scaled. A cap that holds its ratio passes
  // at every size; one that was pinned in px fails here and nowhere else.
  const absolute = kind === 'px' || kind === 'cap';
  const ratio = width / DEFAULT_FRAME[0];
  const target = compValue !== null && absolute && !atComp ? compValue * ratio : compValue;
  const tolerance = absolute && !atComp ? TOLERANCE[kind].limit * ratio : TOLERANCE[kind]?.limit;
  const comparable = typeof target === 'number' && typeof gotValue === 'number'
    && Number.isFinite(target) && Number.isFinite(gotValue);
  const delta = comparable ? gotValue - target : null;
  const failed = target === null
    ? (fails === undefined ? false : fails(gotValue))
    : (comparable ? Math.abs(delta) > tolerance : true);
  if (failed) failures.push(`${address} ${id}`);
  const scaledHere = absolute && !atComp && compValue !== null;
  rows.push({
    address, id,
    comp: target === null ? 'comp n/a' : `comp ${fmt(target, kind)}`,
    got: gotValue === null || gotValue === undefined ? 'got n/a' : `got ${fmt(gotValue, kind)}`,
    delta: delta === null ? '' : `delta ${delta >= 0 ? '+' : ''}${delta.toFixed(2)}`,
    verdict: target === null && fails === undefined ? 'comp n/a' : failed ? 'FAIL' : 'ok',
    note: scaledHere ? `${note ? `${note}; ` : ''}comp value scaled ${ratio}x for this frame` : note,
  });
}

function report(measurement, address) {
  for (const constant of CONSTANTS) {
    let compValue = null;
    try { compValue = constant.comp(); } catch { compValue = null; }
    record(address, constant.id, constant.kind, compValue, constant.got(measurement), constant.note, constant.fails);
  }
  for (const role of TEXT_ROLES) {
    const face = measurement.text.find((t) => t.name === role.probe);
    if (!face) {
      rows.push({ address, id: role.id, comp: '', got: '', delta: '',
        verdict: 'absent', note: 'this address does not render the role' });
      continue;
    }
    if (role.region === null) {
      rows.push({ address, id: `${role.id}.cap`, comp: 'comp n/a', got: `got ${face.cap.toFixed(2)}px`,
        delta: '', verdict: 'comp n/a', note: `rendered ${face.first} ${face.weight} ${face.size}px, scale ${face.scale}` });
    } else {
      const compCap = role.cap === undefined ? custom(`--r-${role.region}-cap`) : role.cap;
      record(address, `${role.id}.cap`, 'cap', compCap, face.cap * face.scale, `rendered ${face.first} ${face.weight} ${face.size}px`);
    }
    // A face is a name: exactly one of the two licensed, and actually rendering.
    const familyOk = LICENSED.includes(face.first) && !face.fellBack;
    if (!familyOk) failures.push(`${address} ${role.id}.family`);
    rows.push({ address, id: `${role.id}.family`, comp: `comp ${LICENSED.join(' or ')}`, got: `got ${face.first}`,
      delta: '', verdict: familyOk ? 'ok' : 'FAIL',
      note: face.fellBack ? 'the named face is not rendering: the fallback measures identically' : '' });
    // A scaled text element renders at a size no token declares.
    const scaleOk = Math.abs(face.scale - 1) < 1e-6;
    if (!scaleOk) failures.push(`${address} ${role.id}.scale`);
    rows.push({ address, id: `${role.id}.scale`, comp: 'comp 1', got: `got ${face.scale}`,
      delta: '', verdict: scaleOk ? 'ok' : 'FAIL', note: scaleOk ? '' : 'a transform scale on text (review B2)' });
  }
}

// ------------------------------------------------------------------------ the CLI

const [, , ...flags] = process.argv;
const wantsHelp = flags.includes('--help');
const wantsList = flags.includes('--list');
// Flags that take a value. Without this set the value is also the first non-`--` token, so
// `--frame 3840x2160` silently became the ADDRESS and the run measured a nonexistent page while
// printing a clean-looking table — caught by running it rather than by reading it, one edit after
// adding the flag. `--address` was in the usage text and never implemented for the same reason:
// its value was landing in the positional slot and working by accident.
const VALUED = new Set(['--frame', '--address']);
const valueOf = (name) => flags.find((f) => f.startsWith(`${name}=`))?.slice(name.length + 1)
  ?? (flags.includes(name) ? flags[flags.indexOf(name) + 1] : undefined);
const positional = flags.filter((f, i) => !f.startsWith('--') && !(i > 0 && VALUED.has(flags[i - 1])));
const only = valueOf('--address') ?? positional[0];

if (wantsHelp) {
  console.log(`usage: node .dev/web-console/comp-check.mjs [--list] [--address <name>] [--json] [--frame WxH]

Measures the approved comp's own constants on the live console, address by address, and fails by
name on any delta outside the tolerance that constant is held to.

The comp is webui/.impeccable/build/spec.json + build/scaffold/layout.css; nothing is retyped.
Constants (${CONSTANTS.length} geometry + ${TEXT_ROLES.length} text roles):
${[...CONSTANTS.map((c) => c.id), ...TEXT_ROLES.map((r) => r.id)].map((id) => `  ${id}`).join('\n')}

Tolerances: box ${TOLERANCE.box.limit}% of frame, cap ${TOLERANCE.cap.limit}px, family and scale exact.
Exit 0 when every measured constant is inside its tolerance, 1 when any is not.

--frame renders at another viewport, default ${DEFAULT_FRAME.join('x')} which is the comp's own.
Away from the comp's frame the absolute-pixel rows (px, cap) print as \`scaled\` and do not fail —
since M1-26 the root tracks the viewport, so those values are SUPPOSED to move — while every
percentage-of-frame and count row is compared exactly as it is at 1536. Those are the rows that
should hold at any size, and at 3840x2160 they are the ones that do not.`);
  process.exit(0);
}

if (wantsList) {
  for (const constant of CONSTANTS) console.log(constant.id);
  for (const role of TEXT_ROLES) console.log(role.id);
  process.exit(0);
}

const list = only === undefined ? addresses() : [only];

// --frame WxH, defaulting to the comp's own. Refused rather than silently defaulted when it does
// not parse: a gate that asked for 3840x2160 and quietly measured 1536x1024 would report a clean
// run at a frame it never rendered, which is the failure this flag exists to end.
const frameFlag = valueOf('--frame');
const [width, height] = frameFlag === undefined
  ? DEFAULT_FRAME
  : (() => {
      const m = /^(\d{3,5})x(\d{3,5})$/.exec(frameFlag);
      if (m === null) {
        console.error(`REFUSED: --frame ${frameFlag} is not WxH (e.g. 3840x2160). A frame that does not parse would silently measure ${DEFAULT_FRAME.join('x')}.`);
        process.exit(2);
      }
      return [Number(m[1]), Number(m[2])];
    })();
const atComp = width === DEFAULT_FRAME[0] && height === DEFAULT_FRAME[1];
const urlFor = (address) => fixtureUrl(address);
await withChrome({ 'myx-mgmt-key': mgmtKey() }, async (send) => {
  for (const address of list) {
    // Through about:blank every time: every console URL is a hash route, so navigating from one to
    // the next is a same-document fragment change that does not re-create the frame (measured in
    // M2-11's gate, twice).
    await send('Page.navigate', { url: 'about:blank' });
    await show(send, urlFor(address), width, height, 5000);
    const probe = await send('Runtime.evaluate', { expression: PROBE, returnByValue: true });
    if (probe.exceptionDetails) throw new Error(`${address}: the probe threw: ${JSON.stringify(probe.exceptionDetails)}`);
    const measurement = JSON.parse(probe.result.value);
    report(measurement, address);
  }
});

// ---------------------------------------------------------------------- the wall

let current = null;
for (const row of rows) {
  if (row.address !== current) {
    current = row.address;
    console.log(`\n${current}  (${width}x${height})`);
  }
  console.log(`  ${row.id.padEnd(24)} ${row.comp.padEnd(22)} ${row.got.padEnd(16)} ${row.delta.padEnd(13)} ${row.verdict}${row.note ? `  (${row.note})` : ''}`);
}

const compared = rows.filter((row) => row.comp.startsWith('comp ') && row.comp !== 'comp n/a').length;
const scaled = rows.filter((row) => (row.note ?? '').includes('comp value scaled')).length;
// The frame is in the summary line because the gate runs this twice and two identical-looking
// clean runs at one size is exactly the report M1-33 found the gate was giving.
console.log(`\nat ${width}x${height}${atComp ? ' (the comp frame)' : ''}: ${list.length} addresses, ${rows.length} rows, ${compared} compared against the comp${scaled ? `, ${scaled} px rows compared against a ${(width / DEFAULT_FRAME[0])}x-scaled comp value` : ''}, ${failures.length} outside tolerance`);
for (const failure of failures) console.error(`FAIL ${width}x${height} ${failure}`);
process.exit(failures.length === 0 ? 0 : 1);
