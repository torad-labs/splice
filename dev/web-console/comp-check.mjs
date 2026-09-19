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
// M1-76 DISPOSITION — the two ways a check can be decorative, answered for this file. This is the
// file the row was cut from, and it carried BOTH shapes at once.
//   SHAPE ONE, does every FAIL reach the exit code? NOW YES. M1-70 fixed the four SIDES loops that
//     printed the word FAIL and pushed nothing, so a constant with no entry, an entry with no
//     constant, a pair with no verdict and a pair whose two sides named DIFFERENT OBJECTS all
//     printed FAIL and exited 0 — in the file built to catch the M1-56 class, with its detector
//     switched off. This row adds the fifth: a `comp()` accessor that THREW was caught into
//     `compValue = null`, which record() prints as `comp n/a` — the same disposition a constant
//     that legitimately has no comp carries — so a renamed token silently stopped a constant being
//     checked while the run stayed green. It now fails by name as `<id>.comp-unreadable`.
//   SHAPE TWO, if every page threw, what would it print? IT PRINTED ITS OWN EMPTINESS AS A CLEAN
//     RUN. `compared` was computed, printed, and read by nothing, so `0 compared against the comp`
//     — an instrument whose entire job is comparing this side to the comp, reporting that it
//     compared nothing — exited 0. Three emptinesses now fail by name: no address measured, no row
//     produced, and zero of N rows compared. The ladder carried the same defect one level down and
//     is fixed with it: checkRatios skipped an unresolvable pair silently, so `ladder: 0 rungs off
//     by more than 0.08` was the literal a verify greps for whether six pairs held or none was
//     looked at. It now prints `N pair(s) compared of 6 named x M addresses`, names every skip, and
//     pushes `ladder-did-not-run` into `failures` when nothing resolved. The first run after that
//     fix reported the live ladder grading 4 of 6 — see type-ladder.mjs beside ROLE_PAIRS.
//   THE SIX MIS-KINDED CONSTANTS (M1-76 item two), judged ONE AT A TIME and each with its own
//     reason written beside it. `kind: 'box'` prints a percent sign; six constants carried it while
//     returning a raw CSS length, so a 1px hairline printed as `1.00%` — 15.36px at this frame —
//     and an 8px inset printed as `8.00%`, which is the one that hid longest because the digits
//     coincide. THE DENOMINATOR WAS ENUMERATED, not eyeballed: all 23 `kind: 'box'` constants were
//     split by whether their `got` divides by a reference length. Sixteen divide by m.frame.w/h and
//     are genuine percentages (rail.plate.* among them, which is why a sweep would have corrupted
//     them). Six divide by nothing and are now 'px'. THE TWENTY-THIRD IS bay.label-centre AND IT
//     STAYS 'box': it divides by the BAY's own width, so it is a percentage of its container rather
//     than of the frame. My first discriminator asked only about the frame and flagged it as a
//     seventh — half-right, the failure mode this campaign has caught five times tonight, caught
//     here by reading the probe field instead of trusting the rule. The corrected rule is
//     "normalised by SOME reference length", frame or container.
//     PROVEN INERT: every one has `comp: () => null`, so record() takes its `target === null` branch
//     and `failed` is `fails(gotValue)` regardless of kind — scaling, tolerance and REPORT_ONLY are
//     all untouched. Measured across 13 addresses before and after: the same 14 failures, by the
//     same names. A display-unit correction that moved no verdict, which is what it was meant to be.
//   WHAT STILL ONLY REPORTS, BY RULING AND NOT BY ACCIDENT: the absolute cap rows (M1-64's
//     REPORT_ONLY) and `field.label-ink-start`/`field.label-rule-offset`. Each prints its delta and
//     its cause and gates nothing DELIBERATELY, with the reason written beside it. A check that
//     says which of its output gates is not decorative; one that lets a reader assume all of it
//     does is, which is why the summary line names the counts separately.
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
  // THE QUANTITY A BOX TOLERANCE IS EXPRESSED IN — the ruling M1-99 was cut to make.
  //
  // It was `1.0 percent of frame`, applied to every box row, and the defect was that this ONE
  // NUMBER IS THREE DIFFERENT CONSTRAINTS. A point of WIDTH is 15.36px at the comp frame; a point
  // of HEIGHT is 10.24px; and bay.label-centre is a share of its BAY, so a point there is a
  // fraction of a container whose size is not the frame at all. Nothing said so, and a reader
  // seeing one number across every row assumes one meaning.
  //
  // Worse in relative terms: rule.h is 5.80% of the frame, so a point let the rule bar be ~17%
  // too tall; rule.wordmark.x is 0.50%, so a point let it be 200% wrong — three times its own
  // size. Uniform in absolute frame-share, wildly non-uniform in the error a reader cares about.
  //
  // THE CHOICE: ABSOLUTE, IN COMP-FRAME PIXELS, converted per constant through the length it is a
  // share OF. One number that means one thing everywhere, printed beside every row in both units.
  //
  // WHY ABSOLUTE AND NOT RELATIVE-TO-THE-CONSTANT, which was the obvious alternative and is
  // REJECTED ON THE MEASUREMENT: the error sources do not scale with the constant's magnitude. A
  // position at 72% of the frame is not measured a hundred times less precisely than one at 0.7%.
  // The two real sources are the comp's own granularity (boxes reported to 0.1 of a point: 1.54px
  // wide, 1.02px tall) and sub-pixel render rounding (~1px) — both ABSOLUTE. And a relative
  // tolerance LOOSENS exactly the rows that are currently exact: at 5% of the constant,
  // rule.none.x (72.00%) would be allowed 3.60 points against today's 1.00, and bay.label-centre
  // (47.14%) would be allowed 2.36 points — which is MORE than its live delta of 2.30, so the one
  // real geometry finding this instrument currently reports would have gone green. A tolerance
  // model that erases a true finding is disqualified by that alone.
  //
  // ALSO REJECTED: a floor-plus-relative pair, max(floor, rel x comp). It inherits the same defect
  // at the top end — the relative term only ever binds on the LARGE constants, which are precisely
  // the ones reading delta 0.00 today and needing no slack. It adds a second number to explain and
  // buys nothing this data supports.
  //
  // WHY 5px: the two error sources sum to about 2.6px at the comp frame, and this is that doubled.
  // It is between a third and a half of today's effective limit, it leaves every currently-passing
  // row passing, and it catches the defect that proved the old limit unreadable — M1-86's mutation
  // I(a), a rule bar 10% too tall, which is 5.94px of error and passed silently at 1.0 point.
  //
  // WHAT MOVED, measured over thirteen addresses at both frames before and after: the comp frame
  // did not change at all (14 failures, same names), and 3840 gained EXACTLY ONE — `teams strip.h`,
  // delta -0.86 against a ±0.49 point limit. It is a real property of the build and not slack
  // taken away: on the twelve list addresses the strip's height moves 63.8px -> 155.1px (x2.431,
  // width-driven less a hairline that does not scale), while on the teams BOARD it moves
  // 63.0px -> 131.3px (x2.085, which is the HEIGHT ratio 2.109). The same element obeys two
  // different scaling laws depending on which layout renders it, and M1-26 says it should obey the
  // width one everywhere. The old limit was wide enough to cover the difference, so an
  // inconsistency of 8.9px at 3840 read as agreement. One new red, with a named mechanism, is what
  // this row wanted: a tolerance change that reddens nothing proved nothing.
  box: { limitPx: 5, unit: 'comp-frame px', why: 'the comp boxes are reported to 0.1 of a point (1.54px wide, 1.02px tall) and rendering rounds by ~1px; 5px is that doubled' },
  // A VERTICAL share of the frame, same source and same granularity as `box`, so the same limit.
  // What differs is not the tolerance but the TARGET: a share of height is only frame-independent
  // when the length is height-driven, and in this console no length is — the root tracks the
  // viewport WIDTH (M1-26). So the comp value is restated by the aspect factor away from the comp
  // frame, and at the comp frame that factor is 1 and nothing moves (M1-86).
  // Same quantity and same 5px for the same reasons; what differs from `box` is only the TARGET,
  // which is restated by the aspect factor away from the comp frame (M1-86). A vertical share is
  // always a share of frame height, so its pixel conversion is the height one.
  vbox: { limitPx: 5, unit: 'comp-frame px', why: 'as box; a vertical share is converted through frame height, and its comp target is restated for the frame\'s aspect because the length is width-driven' },
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
import { decodePng, hexToRgb } from './lib/png.mjs';
// The address-to-fixture mapping lives in ONE place and is checked against the pages. This file
// carried the THIRD copy of it, still naming 'demo' for six addresses, which made every rack row in
// its table a measurement of live daemon data (M1-28).
import { FIXTURES, urlFor as fixtureUrl } from './lib/fixtures.mjs';
// THE LADDER'S RATIOS come from the one module that defines them, so the check and the measurement
// cannot drift apart (M1-50): this file carried exactly ONE type constant against a comp with a
// dozen roles, and every green run was silent about the rung-to-rung hierarchy.
import { ROLE_PAIRS, RATIO_LIMIT, checkRatios, compLadder } from './type-ladder.mjs';

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

/**
 * THE COMP'S PRINTED PLATE, measured off the comp's own image rather than taken from its region box.
 *
 * WHY THIS EXISTS (M1-56): `strip.h` compared the comp's lead-strip REGION (67px, 6.543%) against the
 * build's rendered `.myx-strip` ELEMENT (63px, 6.15%). A region is the detector's box around a thing,
 * a plate is the thing: two different objects, so the constant was a coin that came up defect at some
 * frames and ok at others and nothing in the output said which. Measured 2026-09-18: the comp's
 * lead-strip region spans y205..271 (66px) while its PAPER BAND spans y209..268 (60px, 5.859%) — the
 * region carries ~6px of the room around the printed strip.
 *
 * The band is found the way a reader would: within the region's own box, the rows where the strip
 * paper is the majority colour. Both sides of the constant are then the same object — a plate — and
 * the number is derived from the artefact rather than from the detector's box around it.
 */
function compPlate(spec, regionId, paperHex, tolerance = 12, majority = 0.5) {
  const png = decodePng(readFileSync(join(ROOT, `webui/.impeccable/mocks/${spec.comp.replace(/^.*mocks\//, '')}`)));
  const region = spec.regions.find((r) => r.id === regionId);
  const paper = hexToRgb(paperHex);
  const x0 = Math.round(region.box.x * png.width);
  const x1 = Math.round((region.box.x + region.box.w) * png.width);
  const y0 = Math.round(region.box.y * png.height);
  const y1 = Math.round((region.box.y + region.box.h) * png.height);
  const rows = [];
  for (let y = y0; y < y1; y += 1) {
    let hit = 0;
    let seen = 0;
    for (let x = x0; x < x1; x += 2) {
      const i = (y * png.width + x) * png.channels;
      if (Math.abs(png.pixels[i] - paper[0]) <= tolerance && Math.abs(png.pixels[i + 1] - paper[1]) <= tolerance
        && Math.abs(png.pixels[i + 2] - paper[2]) <= tolerance) hit += 1;
      seen += 1;
    }
    rows.push({ y, share: hit / seen });
  }
  const band = rows.filter((row) => row.share >= majority);
  if (band.length === 0) throw new Error(`compPlate: no paper band found in ${regionId}`);
  return { px: band[band.length - 1].y - band[0].y + 1, fraction: (band[band.length - 1].y - band[0].y + 1) / png.height,
    regionPx: y1 - y0 };
}

const COMP = comp();
const COMP_STRIP_PLATE = compPlate(JSON.parse(readFileSync(join(ROOT, 'webui/.impeccable/build/spec.json'), 'utf8')), 'lead-strip', '#DDD8C6');
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

  /* THE CAP RATIO IS MEASURED IN THE FACE AT 200px (M1-64, splice-design's ruling 2026-09-18).
     Measuring a capital AT THE ELEMENT'S OWN SIZE is what this file did first, and that metric is
     quantised to whole pixels: measured across every size 10..24px at weights 400/500/600 it
     returns only 8, 9, 10, 11, 13, 14, 15, 17 -- NOTHING returns 12 or 16 -- so at 17px it read
     11.00 where the true cap is 12.57. Against a 0.5px tolerance that made five of the seven comp
     caps unreachable at ANY rung, and every residual derived from it was wrong with them: a metric
     that reports 11.00 for a true 12.57 corrupts the arithmetic, not just the verdict.
     At 200px one pixel of quantisation is 0.25 percent rather than the 4 percent it is at 12px, so
     this is the face's own ratio and cap = computed size x ratio is continuous. The tolerance stays
     at 0.5px: a 1.0px tolerance on a 10.9px cap is nine percent, and the defects this instrument
     exists to find are five to eight. */
  const capRatio = (family, weight) => { ctx.font = weight + ' 200px ' + family;
    return ctx.measureText('H').actualBoundingBoxAscent / 200; };
  /* THE CALIBRATION, returned by every run and printed with the report: a ratio that stopped being
     measured in the face it names -- a fallback family, a renamed face, a canvas that returns 0 --
     shows up here instead of quietly changing what the instrument can see. */
  const calibration = {};
  for (const [family, weight] of [['Archivo', '400'], ['Archivo', '600'], ['JetBrains Mono', '400']]) {
    calibration[family + ' ' + weight] = capRatio(family, weight);
  }
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
    return { first, weight: s.fontWeight, size: parseFloat(s.fontSize),
      cap: parseFloat(s.fontSize) * capRatio(first, s.fontWeight),
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
            labelRule: fl ? parseFloat(cs(fl).borderBottomWidth) || 0 : 0,
            /* THE RULE'S ROW, which is the quantity the CSS actually pins and the one nothing here
               measured (M1-70). board.css sets the label's line box as a LENGTH -- a line-height
               of calc(var(--space-5) - var(--hair)) -- with a comment explaining why: at the old
               ratio of 1.05 the rule's row was right only by accident of the label being 14px, and
               when M1-64 shrank the label the rule would have printed six plate rows above where
               the comp prints it. So the offset is the geometry, the width is a token, and the
               offset is what moves when something else changes. Measured from the FIELD BOX top to
               the rule itself, which is the label's bottom border edge. */
            labelRuleOffset: lr && fr ? +((lr.y + lr.h) - fr.y).toFixed(1) : null } : null };
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
      role('chat.label', one('.myx-board-bay-chat .myx-bay-label')),
    ].filter(Boolean),
    bays,
    calibration,
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
  // kind 'vbox' (M1-86): a share of frame HEIGHT whose pixel value is width-driven. Measured across
  // twelve addresses, this y moves 68.6px -> 167.6px between the frames, a factor of 2.443 against a
  // width ratio of 2.500 and a height ratio of 2.109 — so it tracks width, and dividing it by height
  // made it read 6.70% at 1536 and 7.76% at 3840 for a reason that is the FRAME and not the build.
  // It is 2.443 rather than a clean 2.500 because the rail column's offset carries a hairline that
  // is one device pixel at both sizes; the residue after the restatement is +0.06 and well inside
  // the 1.0 tolerance, and it is a property of the build, which is the point.
  { id: 'rail.column.y', kind: 'vbox', comp: () => pct('rail-labels', 'y'), got: (m) => m.railTabs && (m.railTabs.y / m.frame.h) * 100 },
  { id: 'rail.column.w', kind: 'box', comp: () => pct('rail-labels', 'w'), got: (m) => m.railTabs && (m.railTabs.w / m.frame.w) * 100 },
  { id: 'rail.plate.x', kind: 'box', comp: () => null, got: (m) => m.railTab && (m.railTab.x / m.frame.w) * 100,
    note: 'spec.json has no plate region; rail.css cites the crop (80px plates in a 107.5px column)' },
  { id: 'rail.plate.w', kind: 'box', comp: () => null, got: (m) => m.railTab && (m.railTab.w / m.frame.w) * 100,
    note: 'spec.json has no plate region; rail.css cites the crop (80px plates in a 107.5px column)' },
  { id: 'rail.plate.h', kind: 'box', comp: () => null, got: (m) => m.railTab && (m.railTab.h / m.frame.h) * 100,
    note: 'spec.json has no plate region; rail.css cites the crop (29px on this column)' },
  { id: 'rail.plates', kind: 'count', comp: () => 13, got: (m) => m.tabs,
    note: 'spec.json rail-labels: "thirteen page labels as small plates"' },
  // kind 'vbox' (M1-86), and this is the PURE case that proves the mechanism: the rule bar's height
  // moves 59.4px -> 148.4px, a factor of 2.499 against the width ratio of 2.500 — width-driven with
  // no residue at all. Its share of height therefore grew by exactly the aspect factor, 6.87/5.80 =
  // 1.1845 against (1.778/1.500) = 1.18519, agreeing to three decimals. It read DEAD-ON against the
  // comp at the comp frame (5.80 against 5.80, delta -0.00) and FAILED by +1.07 at 3840, which is
  // the clearest possible statement that the build was right and the normalisation was wrong. Not a
  // single pixel of the build is changed by this row.
  { id: 'rule.h', kind: 'vbox', comp: () => pct('top-rule', 'h'), got: (m) => m.rule && (m.rule.h / m.frame.h) * 100 },
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
  // PLATE against PLATE since M1-56. The comp side measured this off its own image (the paper band
  // inside the lead-strip region, 60px, 5.859%) rather than from the region box (67px, 6.543%), which
  // is what the got side never measured: the rendered strip element.
  // kind 'vbox' (M1-86). Both sides are height-normalised — the comp side is compPlate().fraction,
  // which is band px / png.height — and the strip's own height moves 63.8px -> 155.1px, a factor of
  // 2.431. Width-driven with a residue, like rail.column.y and for the same reason. WORTH KNOWING
  // IF YOU RE-MEASURE THIS ONE: `teams` is the single address of thirteen whose board layout gives a
  // different reading (6.15 where the other twelve give 6.23), so a spot-check on teams alone makes
  // this constant look as though it does not drift at all. It does; the other twelve say so.
  { id: 'strip.h', kind: 'vbox', comp: () => COMP_STRIP_PLATE.fraction * 100,
    got: (m) => { const s = firstStrip(m); return s && (s.rect.h / m.frame.h) * 100; },
    object: 'strip-plate', compVia: 'comp plate (paper band in the lead-strip region)', gotVia: 'element .myx-strip height' },
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
  // kind 'px' AND NOT 'box' (M1-76): `rails.top` is `parseFloat(s.borderTopWidth)` — a CSS border
  // width in pixels, divided by nothing. As 'box' it printed a 1px hairline as `1.00%`, which at
  // this frame reads as 15.36px. Judged on its own and not swept: the value's own expression in the
  // probe is the evidence, and this one performs no normalisation at all.
  { id: 'bay.rail-top', kind: 'px', comp: () => null, got: (m) => (m.bays[0] ? m.bays[0].rails.top : null),
    note: 'comp carries no rail width; the board draws rails on its slots (board.css:151), a plain bay on itself (ui.css:362)' },
  // kind 'px' for the same reason and judged separately: `parseFloat(s.borderBottomWidth)`, a
  // border width in pixels. Same expression shape as its sibling, same verdict, arrived at by
  // reading the probe field rather than by pattern-matching the neighbouring line.
  { id: 'bay.rail-bottom', kind: 'px', comp: () => null, got: (m) => (m.bays[0] ? m.bays[0].rails.bottom : null),
    note: 'comp carries no rail width; the board draws rails on its slots (board.css:152), a plain bay on itself (ui.css:363)' },
  // The field box: the comp's cell was measured for the CSS (label ink 216, divider 229, value ink
  // 241, strip 208..269) but spec.json carries no field region, so the padding has no machine value
  // to compare against and prints what renders for the reviewer to read against that crop. The
  // divider and the label rule DO have a comp shape — the crop shows both — so they are rules.
  // kind 'px' AND NOT 'box' (M1-76): `padStart` is `parseFloat(fs.paddingInlineStart)`, a CSS
  // padding in pixels with no division by anything. Judged on its own: a padding is an absolute
  // inset, and the note beside it already cites the comp's crop in PIXELS (216, 229, 241), so the
  // unit the reader is asked to compare against was pixels while the printed unit said percent.
  { id: 'field.pad-start', kind: 'px', comp: () => null, got: (m) => firstField(m) && firstField(m).padStart,
    note: 'comp carries no field region; the CSS cites the crop (label ink 216, divider 229, value ink 241)' },
  // kind 'px', judged separately: `parseFloat(fs.paddingInlineEnd)`. Same class as pad-start but a
  // different edge, and the reason is its own expression rather than its sibling's verdict.
  { id: 'field.pad-end', kind: 'px', comp: () => null, got: (m) => firstField(m) && firstField(m).padEnd,
    note: 'comp carries no field region; measured for the reviewer' },
  // kind 'px' (M1-76), and this is the one the row named with its number: it printed `8.00%` for an
  // 8px inset. The two happen to share a digit, which is exactly why it went unread for so long —
  // the value looked plausible as a percentage. `parseFloat(cs(fl).paddingInlineStart)`, no division.
  { id: 'field.label-pad-start', kind: 'px', comp: () => null, got: (m) => firstField(m) && firstField(m).labelPadStart,
    note: 'the inset a composition may move onto the label instead of the box' },
  { id: 'field.label-ink-start', kind: 'px', comp: () => null, got: (m) => firstField(m) && firstField(m).labelInkStart,
    note: 'where the label ink starts inside the box, in px: the number a reviewer reads against the crop' },
  // kind 'px' AND NOT 'box' (M1-76), and this is the one of the six that GATES, so it was judged
  // hardest. Its own `fails` clause settles the unit without reference to the probe: `!(v >= 1)` is
  // a ONE PIXEL floor — the world's `--hair`. Read as a percentage that threshold would mean "at
  // least 1% of the frame", 15.36px at this size, which is not a hairline and is not what the comp
  // shows. So the failure rule and the printed kind DISAGREED, and the failure rule was right.
  // THE VERDICT DOES NOT MOVE: `comp()` is null, so record() takes the `target === null` branch and
  // `failed` is `fails(gotValue)` regardless of kind — the tolerance, the scaling and REPORT_ONLY
  // are all untouched. This is a display-unit correction and cannot change what gates, which is the
  // claim the before/after failure sets are compared to prove.
  { id: 'field.divider', kind: 'px', comp: () => null, got: (m) => firstField(m) && firstField(m).divider,
    fails: (v) => !(v >= 1), note: 'the comp cell carries a vertical divider; the world declares --hair (1px)' },
  // THE NAME AND THE BODY DISAGREED, and the fix is both halves (M1-70). This note read 'the comp
  // cell carries the rule at row y=229' while the body returns a border WIDTH — an offset in the
  // prose, a width in the code, which is the M1-56 class documented at SIDES below and which
  // survived that very sweep. The width is what this constant asserts and the note now says only
  // that; y=229 is a crop ROW and this check has no comp region for a field to compare it against,
  // so quoting it here made a reader expect a comparison nothing performs.
  // kind 'px' AND NOT 'box', which is the same defect in the unit that the note had in the object:
  // fmt() prints 'box' with a % sign, and this value is parseFloat(borderBottomWidth) -- raw px. It
  // reported a 1px hairline as `1.00%`, which at 1536 reads as 15px. Display-only for a constant
  // whose comp side is null: record() scales nothing and applies no tolerance when target is null,
  // and REPORT_ONLY holds 'cap' alone so gating is unchanged. Verified by the run: `got 1.00px`.
  { id: 'field.label-rule', kind: 'px', comp: () => null, got: (m) => firstField(m) && firstField(m).labelRule,
    fails: (v) => !(v >= 1), note: 'the label carries a rule and the world declares it --hair (1px); this is its WIDTH, not its row' },
  // AND THE ROW ITSELF, which nothing measured until now — the quantity board.css pins on purpose
  // and the one M1-64 found moving. REPORTED, NOT GATED, which is this file's own idiom for a
  // build-side number with no comp region (field.label-ink-start, three lines up, is the same
  // shape): the comp carries no field region, so there is nothing to compare it against, and a
  // threshold invented here would be a number with no source. Printed every run so a drift is
  // visible to a reader instead of living in a CSS comment.
  { id: 'field.label-rule-offset', kind: 'px', comp: () => null, got: (m) => firstField(m) && firstField(m).labelRuleOffset,
    note: 'the rule\'s row: field-box top to the label\'s bottom border, in px. board.css pins this as a LENGTH (line-height: calc(--space-5 - --hair)) because at a ratio it was right only by accident of the label being 14px — M1-64 measured that a shrunken label would print it six plate rows high' },
];

/**
 * BOTH SIDES OF EVERY CONSTANT, NAMED — what object the comp side measures and what object the got
 * side measures. M1-56's sweep, and the reason it exists: `strip.h` compared the comp's lead-strip
 * REGION against the build's rendered PLATE, two different objects, so it was a coin that came up
 * defect at some frames and ok at others and nothing in the output said which. design-builder proved
 * the build's strips matched the comp's plate to three decimals while the check reported a delta.
 *
 * A constant whose sides name different objects is re-pointed or deleted, never tolerated: a wrong
 * number sitting inside tolerance is a defect waiting for a frame that exposes it.
 *
 * The audit is two-way and drift-proof: a constant with no entry here FAILS BY NAME, and an entry
 * with no constant fails too — so a new constant cannot be added without saying what it compares.
 */
const SIDES = {
  'rail.x': ['rail', 'region rail/x', 'element .myx-rail left edge', 'same'],
  'rail.w': ['rail', 'region rail/w', 'element .myx-rail width', 'same'],
  'rail.column.x': ['rail-label-column', 'region rail-labels/x', 'element .myx-rail-tabs left edge', 'same'],
  'rail.column.y': ['rail-label-column', 'region rail-labels/y', 'element .myx-rail-tabs top', 'same'],
  'rail.column.w': ['rail-label-column', 'region rail-labels/w', 'element .myx-rail-tabs width', 'same'],
  'rail.plate.x': ['rail-plate', 'no comp value (no plate region)', 'element .myx-rail-tab left edge', 'same'],
  'rail.plate.w': ['rail-plate', 'no comp value (no plate region)', 'element .myx-rail-tab width', 'same'],
  'rail.plate.h': ['rail-plate', 'no comp value (no plate region)', 'element .myx-rail-tab height', 'same'],
  'rail.plates': ['rail-plate-count', 'the comp region note "thirteen page labels as small plates"', 'count of elements .myx-rail-tab', 'same'],
  'rule.h': ['rule-band', 'region top-rule/h', 'element .myx-rule height', 'same'],
  'rule.wordmark.x': ['rule-cell-x', 'region wordmark/x', 'element .myx-rule-wordmark left edge', 'same'],
  'rule.clocks.x': ['rule-cell-x', 'region clocks/x', 'element .myx-rule-clocks left edge', 'same'],
  'rule.health.x': ['rule-cell-x', 'region health/x', 'element .myx-rule-health left edge', 'same'],
  'rule.window.x': ['rule-cell-x', 'region nearest-window/x', 'element .myx-rule-window left edge', 'same'],
  'rule.none.x': ['rule-cell-x', 'region no-window/x', 'element .myx-rule-none left edge', 'same'],
  'strip.inset-x': ['strip-inset-in-bay', 'region lead-strip/x minus region bay-claude/x', 'element .myx-strip left edge minus its bay left edge', 'same'],
  // WAS `unlike` and re-pointed: the comp side read the lead-strip REGION (67px, 6.543%) against the
  // build's printed plate. Both sides now measure a plate, the comp's off its own image.
  'strip.h': ['strip-plate', 'comp plate: the paper band inside region lead-strip', 'element .myx-strip height', 'same'],
  'bay.label-centre': ['bay-label-plate-centre', 'region bay-claude-label centre within region bay-claude', 'element .myx-bay-label centre within its own bay', 'same'],
  'bay.rail-top': ['bay-rail', 'no comp value (the comp carries no rail width)', 'element .myx-bay border-top-width', 'nocomp'],
  'bay.rail-bottom': ['bay-rail', 'no comp value (the comp carries no rail width)', 'element .myx-bay border-bottom-width', 'nocomp'],
  'field.pad-start': ['field-padding', 'no comp region', 'element .myx-sfield padding-inline-start', 'nocomp'],
  'field.pad-end': ['field-padding', 'no comp region', 'element .myx-sfield padding-inline-end', 'nocomp'],
  'field.label-pad-start': ['field-padding', 'no comp region', 'element .myx-sfield-label padding-inline-start', 'nocomp'],
  'field.label-ink-start': ['field-label-ink', 'no comp region', 'element .myx-sfield-label ink offset', 'nocomp'],
  'field.divider': ['field-divider', 'no comp value (the comp cell carries a divider)', 'element .myx-sfield border-inline-end-width', 'same'],
  'field.label-rule': ['field-label-rule', 'no comp value (the comp cell carries the rule; y=229 is its crop row, not a comparable region)', 'element .myx-sfield-label border-bottom-width', 'nocomp'],
  'field.label-rule-offset': ['field-label-rule', 'no comp value (no field region to take a row from)', 'element .myx-sfield-label bottom border edge minus the field box top', 'nocomp'],
};

const SIDES_MISSING = CONSTANTS.filter((constant) => SIDES[constant.id] === undefined).map((constant) => constant.id);
const SIDES_ORPHANED = Object.keys(SIDES).filter((id) => CONSTANTS.every((constant) => constant.id !== id));

/**
 * The comp roles the instrument CANNOT measure, carried as exclusions with their reason rather than
 * as absence. Three roles the ladder cannot see must say so on every run, or the next seat reads the
 * seven measured rungs as the whole ladder — which is exactly the silence that let type go
 * unmeasured for a week while every comp-check run came back green.
 */
const COMP_EXCLUSIONS = (() => {
  const spec = JSON.parse(readFileSync(join(ROOT, 'webui/.impeccable/build/spec.json'), 'utf8'));
  return compLadder(spec).excluded;
})();

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
  // The comp measured this one too (chat-label, 25 glyphs, 9.8px). It was missing here, and its
  // absence is what made the bay.label/chat.label rung invisible.
  { id: 'text.chat.label', probe: 'chat.label', region: 'chat-label' },
];

const LICENSED = ['Archivo', 'JetBrains Mono'];

// ------------------------------------------------------------------- the report

const failures = [];
const rows = [];
/** The ladder's own tally, printed as its own line: this is the leg that GATES (see REPORT_ONLY). */
const LADDER = { findings: 0, addresses: 0, compared: 0, skipped: new Set() };
/** The face ratios the probe actually measured, keyed by face and weight, as the run's calibration. */
const calibrationSeen = {};

const UNIT = { box: '%', cap: 'px' };

/**
 * THE ABSOLUTE CAP ROWS REPORT; THEY DO NOT GATE (M1-64, splice-design's ruling 2026-09-18).
 *
 * A cap is compared against the comp's own ink height at the comp's own size. Measured with the
 * continuous metric this file now uses, the caps land between -5.4 and +1.3 percent of the comp's
 * constants and six of the seven sit INSIDE the 0.5px tolerance -- so the gate would be nearly green,
 * not red. It still does not gate, and the reason is now the honest one: `text.health` cannot be
 * reached by any rung (the comp's 12.40px needs a 17.97px Archivo and the ladder steps 17 then 20),
 * and the whole comparison rests on a comp face NOTHING IN THE TREE NAMES. A row that can never go
 * green teaches the next seat to weaken it; a row that gates on an unnamed face's ink teaches it to
 * tune the rung. Both are the operator's question, so the numbers print and the ladder gates.
 *
 * This is M1-45's precedent applied to a second instrument: gate on the defect, print the census.
 * WHAT GATES INSTEAD IS THE RATIO LADDER below, because the ratios are the property the console must
 * satisfy and they are invariant under the face: a role bound to the wrong rung moves a ratio, which
 * is exactly the defect this row fixed. The cap rows still print every run with the face's own delta
 * named as their cause, so the operator's question stays visible instead of being tuned away.
 */
const REPORT_ONLY = new Set(['cap']);

// ------------------------------------------------- what each box constant is a share OF (M1-99)

/**
 * A box constant's REFERENCE LENGTH: the thing its percentage is a percentage of. A tolerance in
 * pixels is only meaningful through this, and it differs per constant — which is the fact the old
 * single `1.0 percent of frame` concealed.
 *
 * DERIVED FROM THE ACCESSOR'S OWN SOURCE, not from a hand-kept list beside it, so a constant added
 * later cannot silently inherit the wrong axis: the two lists could not disagree, because there is
 * only one. A constant this cannot resolve is NOT defaulted to an axis — it fails by name at load
 * (§24: every item needs a disposition and absence is not one). The container-normalised constants
 * are the declared exception, and they are declared HERE rather than guessed.
 */
const REF_CONTAINER = {
  // bay.label-centre is a share of the BAY it sits in, never of the frame — the reason a sweep over
  // `kind: box` would have corrupted it (M1-76) and the reason it needs its own reference here. The
  // comp's own bay-claude width is the length to convert through, read from the spec like every
  // other comp value rather than typed.
  'bay.label-centre': { of: "the comp's bay-claude width", px: () => pct('bay-claude', 'w') / 100 * DEFAULT_FRAME[0] },
};

function refOf(constant) {
  const declared = REF_CONTAINER[constant.id];
  if (declared !== undefined) return { of: declared.of, px: declared.px() };
  const src = String(constant.got);
  const byWidth = /m\.frame\.w\b/.test(src);
  const byHeight = /m\.frame\.h\b/.test(src);
  if (byWidth && !byHeight) return { of: 'frame width', px: DEFAULT_FRAME[0] };
  if (byHeight && !byWidth) return { of: 'frame height', px: DEFAULT_FRAME[1] };
  return null;
}

/** The tolerance this constant is actually held to, in its own printed unit and in pixels. */
function toleranceOf(constant) {
  const spec = TOLERANCE[constant.kind];
  if (spec?.limitPx === undefined) return { points: spec?.limit, px: null, of: null };
  const ref = refOf(constant);
  return { points: (spec.limitPx / ref.px) * 100, px: spec.limitPx, of: ref.of, refPx: ref.px };
}

// THE ASSERTION THAT MAKES THE DERIVATION SAFE. Without it, a box constant whose accessor names
// neither axis (or both) would fall through to `undefined` and take whatever tolerance that
// produced — the silent default this row exists to end. It runs at load, before any page is
// opened, so the failure is immediate and names the constant.
{
  const unresolved = CONSTANTS.filter((c) => TOLERANCE[c.kind]?.limitPx !== undefined && refOf(c) === null);
  if (unresolved.length > 0) {
    for (const c of unresolved) {
      console.error(`FAIL tolerance-reference ${c.id}: its got() names neither m.frame.w nor m.frame.h (or names both), so what its percentage is a percentage OF cannot be derived. Declare it in REF_CONTAINER or make the accessor name one axis.`);
    }
    process.exit(2);
  }
}

const fmt = (value, kind) => {
  if (typeof value !== 'number' || !Number.isFinite(value)) return String(value);
  if (kind === 'cap' || kind === 'px') return `${value.toFixed(2)}px`;
  if (kind === 'box' || kind === 'vbox') return `${value.toFixed(2)}%`;
  return String(value);
};

/** Record one constant. `fails` is the verdict rule: the tolerance by default, and a named rule
 *  where the comp carries a shape rather than a number (a bay whose rail is missing). */
function record(address, id, kind, compValue, gotValue, note, fails, tol) {
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
  // A VERTICAL SHARE-OF-HEIGHT IS RESTATED IN THIS FRAME'S TERMS BY THE ASPECT FACTOR (M1-86).
  //
  // `vbox` is a vertical length expressed as a share of frame HEIGHT whose underlying pixel length
  // is width-driven — which, since M1-26 made the root track the viewport WIDTH, is every vertical
  // length in this console. A share of height is therefore NOT frame-independent: it carries the
  // aspect ratio. Measured across twelve addresses at the two frames, and the controls are what
  // prove it rather than the failures — EVERY length scales with width, horizontal and vertical
  // alike (rule.h 59.4->148.4px = 2.499x, rail.column.w 106.8->268.0px = 2.511x, the width ratio
  // being 2.500 and the height ratio 2.109). The width-normalised constants read flat across frames
  // only because they divide by width; these three drifted only because they divide by height.
  //
  // THE ALGEBRA IS THE SAME RULE `px` AND `cap` ALREADY USE, written in the unit this constant
  // prints in. target = comp% x (W/H)/(Wc/Hc) reduces to comp_px x widthRatio / frameHeight, so the
  // comparison being made is "is the build's vertical length the comp's, scaled by width?" — which
  // is M1-26's own claim stated as an assertion, and is like-for-like. It is NOT a fudge factor
  // applied to make a red row green: at the comp's own frame the factor is exactly 1, so every
  // comp-frame reading is unchanged to the last digit, which this row's acceptance requires and
  // which is checked by diffing the 1536 run before and against after.
  const aspect = kind === 'vbox';
  const aspectRatio = (width / height) / (DEFAULT_FRAME[0] / DEFAULT_FRAME[1]);
  const target = compValue === null ? compValue
    : absolute && !atComp ? compValue * ratio
    : aspect && !atComp ? compValue * aspectRatio
    : compValue;
  // THE TOLERANCE THIS ROW IS HELD TO, per constant and not per kind (M1-99). `tol` carries the
  // 5px converted through THIS constant's own reference length, so one number in the source means
  // one thing on every row. Falls back to the kind's flat limit for the kinds that have one (cap,
  // count, family, scale), which are absolute already and need no conversion.
  const flat = absolute && !atComp ? TOLERANCE[kind].limit * ratio : TOLERANCE[kind]?.limit;
  const tolerance = tol?.points ?? flat;
  const comparable = typeof target === 'number' && typeof gotValue === 'number'
    && Number.isFinite(target) && Number.isFinite(gotValue);
  const delta = comparable ? gotValue - target : null;
  const gating = !REPORT_ONLY.has(kind);
  const failed = target === null
    ? (fails === undefined ? false : fails(gotValue))
    : (comparable ? (gating && Math.abs(delta) > tolerance) : true);
  if (failed) failures.push(`${address} ${id}`);
  // A report-only row says WHY it does not gate, in its own line: how far this role sits from the
  // comp's own ink band at this size, so the number the operator has to answer for is on the sheet
  // rather than in a commit message.
  const deltaPct = comparable && target !== 0 ? (gotValue / target - 1) * 100 : null;
  const faceDelta = !gating && deltaPct !== null
    ? `reports rather than gates: ${deltaPct >= 0 ? '+' : ''}${deltaPct.toFixed(1)}% against the comp's ink band at this size (the calibration line names the ratios)` : null;
  const scaledHere = absolute && !atComp && compValue !== null;
  // M1-71's ruling, carried: an instrument never prints a cross-frame delta without stating the
  // frame it was taken at and the factor that makes it comparable. This is that sentence for the
  // vertical shares, on the row itself rather than in a header nobody reads beside the number.
  const aspectHere = aspect && !atComp && compValue !== null;
  // THE THRESHOLD, ON THE ROW (M1-99, and the row said this may be worth more than the choice of
  // quantity — the defect was that the constraint was UNREADABLE, not that it was wrong). A reader
  // can now see what THIS constant is held to instead of inferring it from a global number that
  // meant three different things. Both units, because the pixel figure is what the number was
  // chosen in and the point figure is what the delta beside it is printed in.
  const held = tol?.px === undefined || tol?.px === null
    ? (tolerance === undefined ? null : `±${tolerance} ${TOLERANCE[kind]?.unit ?? ''}`.trim())
    : `±${tol.px}px = ±${tol.points.toFixed(2)}% of ${tol.of}`;
  rows.push({
    address, id, held,
    comp: target === null ? 'comp n/a' : `comp ${fmt(target, kind)}`,
    got: gotValue === null || gotValue === undefined ? 'got n/a' : `got ${fmt(gotValue, kind)}`,
    delta: delta === null ? '' : `delta ${delta >= 0 ? '+' : ''}${delta.toFixed(2)}`,
    verdict: target === null && fails === undefined ? 'comp n/a' : failed ? 'FAIL' : (!gating && comparable ? 'report' : 'ok'),
    note: [scaledHere ? `comp value scaled ${ratio}x for this frame` : null,
      aspectHere ? `comp value restated for this frame's aspect (x${aspectRatio.toFixed(4)}): a share of HEIGHT is not frame-independent when the length is width-driven` : null,
      faceDelta, note].filter(Boolean).join('; '),
  });
}

function report(measurement, address) {
  for (const constant of CONSTANTS) {
    let compValue = null;
    // A COMP ACCESSOR THAT THROWS IS A FAILURE, NOT A `comp n/a` (M1-76). `comp()` reads spec.json
    // and layout.css, and every constant whose accessor threw became a row with no comp value —
    // which record() prints as `comp n/a` and does NOT compare, the same disposition a constant
    // that legitimately has no comp carries. Rename a token in layout.css or a region id in the
    // spec and the affected constants stopped being checked while the run stayed green. If every
    // accessor threw, every row read `comp n/a`, `compared` went to 0, `failures` stayed empty and
    // the summary printed `N rows, 0 compared against the comp, 0 outside tolerance`, exit 0 —
    // law 34's empty denominator, wearing the same words a clean run uses.
    try { compValue = constant.comp(); } catch (error) {
      failures.push(`${address} ${constant.id}.comp-unreadable`);
      console.error(`FAIL comp-unreadable ${constant.id}: its comp accessor threw (${error.message}) — the constant was NOT compared`);
    }
    record(address, constant.id, constant.kind, compValue, constant.got(measurement), constant.note, constant.fails, toleranceOf(constant));
  }
  // THE RUNG-TO-RUNG RATIOS, checked here because a ladder correct on average and wrong at the
  // extremes is what "everything looks flat" feels like from the outside. Same pairs, same limit,
  // same code as the measurement (type-ladder.mjs), so this can never again be silent about type.
  {
    const spec = JSON.parse(readFileSync(join(ROOT, 'webui/.impeccable/build/spec.json'), 'utf8'));
    const compLadderRows = compLadder(spec).measured;
    const buildCaps = measurement.text.map((t) => ({ id: t.name, cap: t.cap * t.scale }));
    LADDER.addresses += 1;
    // THE PAIRS ACTUALLY COMPARED, CARRIED OUT OF THE LOOP (M1-76). checkRatios skips a pair whose
    // build role or comp rung it cannot find, and until this row it skipped SILENTLY and returned
    // only the findings — so `ladder: 0 rungs off by more than 0.08` was the same sentence whether
    // six pairs held or the probe had stopped resolving any of them. That literal is what this
    // row's own verify line greps for. The denominator travels now and the summary refuses to be
    // printed without it.
    const ladder = checkRatios(compLadderRows, buildCaps);
    LADDER.compared += ladder.compared;
    for (const skip of ladder.skipped) LADDER.skipped.add(`${skip.pair} (${skip.why})`);
    for (const finding of ladder.findings) {
      LADDER.findings += 1;
      failures.push(`${address} ladder.${finding.pair}`);
      rows.push({ address, id: `ladder.${finding.pair}`, comp: `comp ${finding.comp.toFixed(3)}`,
        got: `got ${finding.build.toFixed(3)}`, delta: `delta ${finding.delta >= 0 ? '+' : ''}${finding.delta.toFixed(3)}`,
        verdict: 'FAIL', note: `a rung off by more than ${RATIO_LIMIT}` });
    }
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
  console.log(`usage: node dev/web-console/comp-check.mjs [--list] [--address <name>] [--json] [--frame WxH]

Measures the approved comp's own constants on the live console, address by address, and fails by
name on any delta outside the tolerance that constant is held to.

The comp is webui/.impeccable/build/spec.json + build/scaffold/layout.css; nothing is retyped.
Constants (${CONSTANTS.length} geometry + ${TEXT_ROLES.length} text roles):
${[...CONSTANTS.map((c) => c.id), ...TEXT_ROLES.map((r) => r.id)].map((id) => `  ${id}`).join('\n')}

Tolerances: box ${TOLERANCE.box.limit}% of frame, cap ${TOLERANCE.cap.limit}px, family and scale exact.
WHAT GATES AND WHAT ONLY REPORTS (M1-64): geometry, counts, family and scale gate on their tolerance,
and so does the rung-to-rung RATIO LADDER, which is the property the console must satisfy and which
is invariant under the comp's unnamed face. The ABSOLUTE cap rows report and do not gate: the comp's
constants are its own printed ink measured by a detector while this side is a capital's cap height in
a face the tree does not name, and the one role that is out (text.health, -0.67px) cannot be reached
by any rung at all. Each cap row prints its own percentage against the comp's band and every run
prints the face ratios it measured, so the question stays on the sheet instead of being tuned away.
The cap is measured continuously, as the computed size times the face's own cap ratio taken at 200px,
because the previous whole-pixel metric (a capital at the element's size) returned only
8, 9, 10, 11, 13, 14, 15, 17 and made five of the seven comp caps unreachable at any rung.
Exit 0 when every GATING constant is inside its tolerance, 1 when any is not.

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

/**
 * The tolerance arithmetic, mutation-proved without a browser (M1-99).
 *
 * This file had no selftest at all: every claim it makes was only ever exercised by driving Chrome
 * against a live server, so the pure arithmetic underneath — which is what this row changed — had
 * no way to fail on its own. The cases below are the ones that would have caught the defect the row
 * was cut for, and the one that proves the load-time reference assertion can fire.
 */
function selftest() {
  let pass = 0; let fail = 0;
  const check = (name, ok, detail) => { ok ? pass++ : fail++; console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${name}  (${detail})`); };
  const [W, H] = DEFAULT_FRAME;

  const x = CONSTANTS.find((c) => c.id === 'rule.wordmark.x');
  const h = CONSTANTS.find((c) => c.id === 'rule.h');
  check('an x constant resolves its reference to frame WIDTH', refOf(x)?.px === W, `${refOf(x)?.of} = ${refOf(x)?.px}px`);
  check('a vertical constant resolves its reference to frame HEIGHT', refOf(h)?.px === H, `${refOf(h)?.of} = ${refOf(h)?.px}px`);
  const bay = CONSTANTS.find((c) => c.id === 'bay.label-centre');
  check('a container-normalised constant resolves to its DECLARED container, not an axis',
    refOf(bay) !== null && refOf(bay).px !== W && refOf(bay).px !== H, `${refOf(bay)?.of} = ${refOf(bay)?.px.toFixed(1)}px`);
  // The assertion at load can only be trusted if this branch is reachable, so it is driven here.
  check('a constant naming NEITHER axis resolves to null, which is what makes the load guard fire',
    refOf({ id: 'synthetic', got: (m) => m.whatever }) === null, 'null');
  check('a constant naming BOTH axes also resolves to null rather than guessing one',
    refOf({ id: 'synthetic2', got: (m) => (m.a / m.frame.w) * (m.b / m.frame.h) }) === null, 'null');

  const tx = toleranceOf(x); const th = toleranceOf(h);
  check('5px through frame width is 0.33 of a point', Math.abs(tx.points - (5 / W) * 100) < 1e-9, `${tx.points.toFixed(4)}%`);
  check('5px through frame height is 0.49 of a point', Math.abs(th.points - (5 / H) * 100) < 1e-9, `${th.points.toFixed(4)}%`);
  // THE DEFECT THIS ROW FIXED, AS AN ASSERTION: one number in the source used to mean two different
  // constraints and nothing said so. It still means two, and now it says so on every row.
  check('the two axes are DIFFERENT constraints, which the old single number concealed',
    Math.abs(tx.points - th.points) > 0.15, `width ±${tx.points.toFixed(2)}% vs height ±${th.points.toFixed(2)}%`);

  // M1-86's mutation I(a), as arithmetic: a rule bar 10% too tall passed the old 1.0-point limit
  // and must not pass this one. This is the failure-to-fail that made the old tolerance visible.
  const ruleBarPx = 0.058 * H;
  const tenPercentTooTall = ruleBarPx * 0.10;
  check('a rule bar 10% too tall PASSED the old 1.0-point limit (the reason this row exists)',
    (tenPercentTooTall / H) * 100 <= 1.0, `${tenPercentTooTall.toFixed(2)}px = ${((tenPercentTooTall / H) * 100).toFixed(2)} points`);
  check('and the same defect FAILS the 5px limit', tenPercentTooTall > TOLERANCE.vbox.limitPx,
    `${tenPercentTooTall.toFixed(2)}px against ±${TOLERANCE.vbox.limitPx}px`);

  // THE LIVE DENOMINATOR, walked rather than sampled: every box/vbox constant in the real list must
  // resolve a reference. A selftest over hand-written inputs proves the function and says nothing
  // about the thing it guards (M1-49's lesson, carried).
  const needRef = CONSTANTS.filter((c) => TOLERANCE[c.kind]?.limitPx !== undefined);
  const unresolved = needRef.filter((c) => refOf(c) === null);
  check(`every box/vbox constant in the LIVE list resolves a reference (${needRef.length} walked)`,
    needRef.length > 0 && unresolved.length === 0, unresolved.length === 0 ? 'all resolved' : unresolved.map((c) => c.id).join(', '));

  // The aspect restatement must be exactly 1 at the comp frame, which is what keeps M1-86's
  // comp-frame readings unchanged. Asserted rather than assumed, because it is the whole safety
  // argument for that row.
  const aspectAt = (w, hh) => (w / hh) / (W / H);
  check('the aspect restatement is exactly 1 at the comp frame', aspectAt(W, H) === 1, `${aspectAt(W, H)}`);
  check('and is the measured 1.1852 at 3840x2160', Math.abs(aspectAt(3840, 2160) - 1.18519) < 1e-4, `${aspectAt(3840, 2160).toFixed(5)}`);

  console.log(`\nselftest ${pass}/${pass + fail} PASS`);
  process.exit(fail === 0 ? 0 : 1);
}

if (flags.includes('--selftest')) selftest();

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
    Object.assign(calibrationSeen, measurement.calibration ?? {});
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
  console.log(`  ${row.id.padEnd(24)} ${row.comp.padEnd(22)} ${row.got.padEnd(16)} ${row.delta.padEnd(13)} ${(row.held ?? '').padEnd(34)} ${row.verdict}${row.note ? `  (${row.note})` : ''}`);
}

const compared = rows.filter((row) => row.comp.startsWith('comp ') && row.comp !== 'comp n/a').length;
const scaled = rows.filter((row) => (row.note ?? '').includes('comp value scaled')).length;
// The frame is in the summary line because the gate runs this twice and two identical-looking
// clean runs at one size is exactly the report M1-33 found the gate was giving.
// AND `compared` GATES (M1-76). It was printed and nothing read it, so `0 compared against the comp`
// was a clean run: an instrument whose whole job is comparing this side to the comp, reporting that
// it compared nothing, and exiting 0. Same for zero rows and zero addresses — three emptinesses, one
// sentence each, all three red. This is the boring case §24 names: the one that gets waved through
// because it does not look like a violation, it looks like a quiet morning.
const outsideTolerance = failures.length;   // snapshot: the three below are not tolerance failures
if (list.length === 0) failures.push('did-not-run: no address was measured');
if (rows.length === 0) failures.push('did-not-run: no row was produced');
if (compared === 0) failures.push(`did-not-run: 0 of ${rows.length} rows were compared against the comp`);
console.log(`\nat ${width}x${height}${atComp ? ' (the comp frame)' : ''}: ${list.length} addresses, ${rows.length} rows, ${compared} compared against the comp${scaled ? `, ${scaled} px rows compared against a ${(width / DEFAULT_FRAME[0])}x-scaled comp value` : ''}, ${outsideTolerance} outside tolerance${failures.length > outsideTolerance ? `, and ${failures.length - outsideTolerance} DID NOT RUN` : ''}`);

// THE LADDER'S LINE, AND IT IS THE ONE THAT GATES THE VERIFY: the cap rows above report because the
// face is not the tree's to fix, and a line that can never go green teaches the next seat to weaken
// it. This line is computed from checkRatios' own findings and printed on every run, clean or not, so
// `grep -qE "0 rungs off by"` is a claim about the ratios rather than about the run having happened.
// THE TWO SHAPES ARE THE POINT, not a style choice. The clean line carries the literal the verify
// greps for; the red line puts the denominator BETWEEN the count and the word "off", so no red count
// can satisfy that grep. Without it a ladder of ten or more pairs going red with exactly ten findings
// would print "10 rungs off" and pass a grep looking for "0 rungs off" — the check would report clean
// on the one number that means it is not.
// AND THE DENOMINATOR IS IN THE TAIL (M1-76), which is the half this line did not have. It printed
// `${ROLE_PAIRS.length} pairs per address` — a LITERAL, six, true of the source and not of the run —
// beside a findings count computed from however many pairs checkRatios could actually resolve. Zero
// resolved pairs printed the clean shape verbatim. `compared` is counted from the loop, the skipped
// pairs are named with the side that was missing, and a run that compared nothing FAILS: it goes
// into `failures`, so it reaches the exit code rather than merely being visible in the tail.
// THE TOLERANCE MODEL, STATED ONCE PER RUN (M1-99). The per-row thresholds above say what each
// constant is held to; this says what the number MEANS, so a reader never has to infer the model
// from a column. The defect this replaced was not a wrong limit but an unreadable one.
{
  const box = TOLERANCE.box.limitPx;
  const byW = (box / DEFAULT_FRAME[0]) * 100; const byH = (box / DEFAULT_FRAME[1]) * 100;
  console.log(`\ngeometry tolerance: ±${box} comp-frame px, converted through each constant's own reference length — ` +
    `±${byW.toFixed(2)}% of frame width, ±${byH.toFixed(2)}% of frame height, and a share of its own container where that is what the constant measures. ` +
    `ABSOLUTE and not relative to the constant: the error sources (comp granularity ±0.1 point, render rounding ~1px) do not scale with a constant's size, ` +
    `and a relative limit would allow rule.none.x (72.00%) 3.60 points where it now reads 0.00.`);
}

const ladderTail = `${LADDER.compared} pair(s) compared of ${ROLE_PAIRS.length} named x ${LADDER.addresses} address${LADDER.addresses === 1 ? '' : 'es'} measured`;
if (LADDER.compared === 0) failures.push(`ladder-did-not-run (0 of ${ROLE_PAIRS.length * LADDER.addresses} pair-addresses resolved)`);
console.log(LADDER.findings === 0
  ? `ladder: 0 rungs off by more than ${RATIO_LIMIT} (${ladderTail})`
  : `ladder: ${LADDER.findings} rungs (of ${ROLE_PAIRS.length} pairs) off by more than ${RATIO_LIMIT} — ${ladderTail}`);
for (const skip of [...LADDER.skipped].sort()) console.log(`  NOT COMPARED ${skip}`);
console.log(`cap calibration, measured in the faces at 200px so the metric is continuous: ${Object.keys(calibrationSeen).length === 0 ? 'NOT MEASURED — the probe returned no calibration' : Object.entries(calibrationSeen).map(([face, ratio]) => `${face} ${ratio.toFixed(4)}`).join(', ')}`);

// BOTH SIDES OF EVERY CONSTANT, and the comp roles the instrument cannot see. Printed on every run:
// silence about what was NOT measured is the defect this row is about.
// A SIDES entry must carry a verdict: 'same' if the two sides measure one object, 'unlike' if they do
// not. A substring test cannot decide this — it flagged six constants that compare like for like —
// so the verdict is recorded per pair and the audit enforces that every pair HAS one, and that no
// pair is left saying 'unlike'. A constant that cannot be made like-for-like is deleted; the audit
// is what stops one being tolerated instead.
const VERDICTS = new Set(['same', 'unlike', 'nocomp']);
const unverdict = Object.entries(SIDES).filter(([, side]) => !VERDICTS.has(side[3])).map(([id]) => id);
const mismatched = Object.entries(SIDES).filter(([, side]) => side[3] === 'unlike').map(([id]) => id);
console.log(`\nsides — every constant names the object each side measures (${Object.keys(SIDES).length} named, ${CONSTANTS.length} constants):`);
for (const constant of CONSTANTS) {
  const side = SIDES[constant.id];
  // the row cannot be printed without its entry; the FAILURE is raised with its three siblings
  // below, so all four read the same way and SIDES_MISSING is what carries it
  if (side === undefined) continue;
  const verdict = side[3] === 'same' ? 'same  ' : side[3] === 'nocomp' ? 'n/a   ' : 'UNLIKE';
  console.log(`  ${verdict} ${constant.id.padEnd(24)} ${side[0].padEnd(24)} comp: ${side[1]}  | got: ${side[2]}`);
}
// ALL FOUR OF THESE PRINTED `FAIL` AND GATED NOTHING (M1-70). `failures` is the only thing feeding
// the exit code, and not one of these loops pushed to it — so a constant with no SIDES entry, a
// SIDES entry with no constant, a pair with no verdict and a pair whose two sides name DIFFERENT
// OBJECTS all printed the word FAIL to stderr and exited 0. The doc comment above SIDES says "the
// audit is two-way and drift-proof: a constant with no entry here FAILS BY NAME ... so a new
// constant cannot be added without saying what it compares", and that sentence was not true: I
// added field.label-rule-offset this row, deleted its SIDES entry to prove the audit would catch
// it, and the run came back exit 0. This is the row's own subject — an assertion nobody re-runs
// rots into a lie the next seat believes — found in the mechanism the row told me to rely on, and
// the sentence was describing an intention rather than the code. All four gate now. Every one of
// the four sets is EMPTY on this tree, so nothing changes today; what changes is that the next
// constant added without its entry cannot pass.
for (const id of SIDES_MISSING) { console.error(`FAIL sides-missing ${id}: this constant does not say what its two sides measure`); failures.push(`sides-missing ${id}`); }
for (const id of SIDES_ORPHANED) { console.error(`FAIL sides-orphaned ${id}: named in SIDES and there is no such constant`); failures.push(`sides-orphaned ${id}`); }
for (const id of unverdict) { console.error(`FAIL sides-unjudged ${id}: this pair does not say whether its two sides measure one object`); failures.push(`sides-unjudged ${id}`); }
for (const id of mismatched) { console.error(`FAIL sides-mismatch ${id}: comp side says "${SIDES[id][1]}" and got side says "${SIDES[id][2]}" — re-point it at one object or delete the constant`); failures.push(`sides-mismatch ${id}`); }
console.log(`\ncomp type roles the instrument CANNOT measure, carried as exclusions with their reason (${COMP_EXCLUSIONS.length}):`);
for (const role of COMP_EXCLUSIONS) {
  console.log(`  ${String(role.cap).padStart(5)}px  ${role.id.padEnd(20)} ${role.glyphs} glyph${role.glyphs === 1 ? '' : 's'} — a cap from fewer than ${6} glyphs is the matcher failing, not a measurement`);
}
console.log(`  the type ladder below is the ${compLadder(JSON.parse(readFileSync(join(ROOT, 'webui/.impeccable/build/spec.json'), 'utf8'))).measured.length} roles it CAN measure, not the whole ladder`);
for (const failure of failures) console.error(`FAIL ${width}x${height} ${failure}`);
process.exit(failures.length === 0 ? 0 : 1);
