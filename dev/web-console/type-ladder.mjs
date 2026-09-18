// THE TYPE AND SPACING LADDER — the one dimension the operator named, measured.
//
// WHY. The operator's own words, unprompted and hedged: "most of the time, what makes something
// that could have a wow factor but it doesnt, it's scale, text size and spacing, at least I think
// Im not sure." The campaign spent three nights on tonal coverage and comp-check.mjs carried exactly
// ONE type constant (`text.bay.label`, region bay-deepseek-label) against a comp with a dozen
// distinct text roles. So the dimension the operator actually named was the one the instrument
// could not see, and every green run was silent about it — law 23 in its inverted face: the check
// did not SAY it did not measure type, it simply did not measure type.
//
// TWO THINGS THIS FILE IS CAREFUL ABOUT, both of them lessons this campaign already paid for.
//
//   RATIOS, NOT A MEAN. A ladder correct on average and wrong at the extremes is exactly what
//   "everything looks flat" feels like from the outside, and a mean hides it perfectly — the same
//   mistake cost the campaign a week on the tonal axis, where one average concealed that settings
//   measured 69.6% and projects 3.5%.
//
//   SPACING IS NOT LEADING. The operator said spacing BESIDE text size, so both are measured:
//   label to value, row to row, plate to plate, and the room's own margins.
//
// THE COMP OF RECORD is webui/.impeccable/mocks/team-board-a.png and its measured numbers come from
// build/spec.json, never retyped here. Roles whose comp measurement is a single glyph or a two-glyph
// crop are EXCLUDED and named as excluded: a cap height resolved from one letterform is the
// matcher failing to find a run, not a small glyph (activity-label 3.3 from 1 glyph, bay-claude-label
// 2.7 from 1, team-header 2.6 from 2).
//
// M1-76 DISPOSITION — the two ways a check can be decorative, answered for this file.
//   SHAPE ONE, does every FAIL reach the exit code? The selftest's does: `bad` drives
//     `process.exit(bad === 0 ? 0 : 1)`. The LIVE report gates nothing by design — gate-coverage
//     disposes this file TOOL, NEVER GATES, and the property it measures gates through comp-check,
//     which imports checkRatios so the two cannot drift. What DID undo both: the CLI hung off
//     `import.meta.url === file://${argv[1]}`, false on a checkout path holding a space, so
//     `--selftest` itself silently exited 0 having run nothing. Fixed, pathToFileURL.
//   SHAPE TWO, if every role went missing, what would it print? `rungs off by more than 0.08: 0` —
//     the clean literal, over nothing. checkRatios skipped an unresolvable pair with a bare
//     `continue` and returned only findings, so a renamed selector, a drifted spec id or an address
//     rendering none of the roles all produced the same three characters a perfect ladder does.
//     It now returns `{ findings, compared, skipped }`, every skip is named with its missing side,
//     the summary carries the denominator, and a ladder that compared NOTHING exits 2. This was not
//     hypothetical: the fix immediately showed the live ladder grading 4 of 6 pairs, for the two
//     reasons written out beside ROLE_PAIRS below.
//
//   node dev/web-console/type-ladder.mjs [--address teams] [--json]
//   node dev/web-console/type-ladder.mjs --selftest
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { mgmtKey, show, withChrome } from './lib/cdp.mjs';
import { urlFor } from './lib/fixtures.mjs';
import { colorFraction, decodePng, hexToRgb } from './lib/png.mjs';

const ROOT = resolve(import.meta.dirname, '../..');
const OUT = join(ROOT, 'webui/.impeccable/review/type-ladder');
const FRAMES = [[1536, 1024], [3840, 2160]];

/** A cap height resolved from fewer glyphs than this is the matcher failing, not a small glyph. */
const MIN_GLYPHS = 6;

// ------------------------------------------------------------------------ the comp

/** The comp's own text ladder, read from its measured spec, largest first. */
export function compLadder(spec) {
  const roles = spec.regions
    .filter((region) => region.type !== null && region.type !== undefined && region.type.comp !== null)
    .map((region) => ({ id: region.id, cap: region.type.comp.capHeightPx, glyphs: region.type.comp.glyphs, note: region.note }))
    .filter((role) => role.cap !== null && role.cap !== undefined);
  const measured = roles.filter((role) => role.glyphs >= MIN_GLYPHS).sort((a, b) => b.cap - a.cap);
  const excluded = roles.filter((role) => role.glyphs < MIN_GLYPHS).sort((a, b) => b.cap - a.cap);
  return { measured, excluded };
}

/** Consecutive ratios down a ladder. The relationship between rungs is what reads as hierarchy. */
export function ratios(ladder) {
  const out = [];
  for (let i = 1; i < ladder.length; i += 1) {
    out.push({ from: ladder[i - 1].id, to: ladder[i].id, ratio: ladder[i - 1].cap / ladder[i].cap });
  }
  return out;
}

/** How far a build ratio may sit from the comp's before it is a defect rather than rounding: the
 *  comp's caps are ink runs on a raster, so a rung pair carries about two of its 0.5px steps. */
export const RATIO_LIMIT = 0.08;

/** The comp's rung pair each build role corresponds to, so ratios are compared like for like. */
export const ROLE_PAIRS = [
  ['rule.wordmark', 'rule.clocks', 'wordmark', 'clocks'],
  ['rule.clocks', 'rule.none', 'clocks', 'no-window'],
  ['rule.none', 'rule.window', 'no-window', 'nearest-window'],
  ['rule.window', 'bay.label', 'nearest-window', 'bay-deepseek-label'],
  ['bay.label', 'chat.label', 'bay-deepseek-label', 'chat-label'],
  // text.health was observed by NO gating leg: no pair, and its cap row only reported. It carried no
  // font-size at all before M1-64 and nothing failed - which is how a 16px health sat under an 18px
  // contract unnoticed. The comp does have the pair (nearest-window 17 against health 18), so it is
  // PAIRED rather than merely named; and everything still unpaired is named in the output below,
  // because a role the ladder pairs with nothing is a role it cannot grade (law 23 at the ladder).
  ['rule.window', 'rule.health', 'nearest-window', 'health'],
];

// ---------------------------------------------------------------------------------------------
// THE PARAGRAPH ABOVE IS WRONG AND TWO OF THESE SIX PAIRS HAVE NEVER RESOLVED (M1-76, measured).
//
// This is the defect M1-76 exists to find, sitting inside the mechanism M1-72 added to close the
// same class. It was invisible because checkRatios skipped an unresolvable pair SILENTLY, so both
// instruments printed `0 rungs off by more than 0.08` over four pairs while naming six.
//
// MEASURED 2026-09-18 from build/spec.json, not recalled:
//
//   the comp ladder, cap heights, largest first    the consecutive keys ratios() can produce
//     15.90  wordmark                                wordmark|health          1.282
//     12.40  health            <- not 18             health|clocks            1.033
//     12.00  clocks                                  clocks|no-window         1.000
//     12.00  no-window                               no-window|nearest-window 1.026
//     11.70  nearest-window                          nearest-window|bay-…     1.073
//     10.90  bay-deepseek-label                      bay-…|chat-label         1.112
//      9.80  chat-label
//
// TWO FACTS, EACH ENOUGH ON ITS OWN:
//
//   1. `nearest-window 17 against health 18` are FONT SIZES. This ladder is in CAP HEIGHTS, where
//      health is 12.40 and sorts ABOVE nearest-window (11.70), not below it. The pair was written
//      in the wrong unit, so `nearest-window|health` is not a key ratios() can ever emit — health
//      was never paired, and the row that said it was closed the hole did not close it.
//
//   2. WORSE, AND THE PART NOBODY COULD HAVE SEEN: ratios() emits CONSECUTIVE rungs only. Putting
//      health into the ladder at 12.40 inserted it BETWEEN wordmark and clocks, which destroyed the
//      `wordmark|clocks` key — so `rule.wordmark/rule.clocks`, a pair that DID resolve before
//      M1-72, has been ungraded ever since. Adding a rung silently unpaired its neighbours.
//
// So the live ladder grades 4 of 6 named pairs and has printed the clean literal throughout. The
// `NOT COMPARED` lines below the summary now say so on every run, which is this row's fix.
//
// WHAT IS *NOT* FIXED HERE, DELIBERATELY. Re-pointing these pairs changes WHAT GATES on a shared
// instrument, and there are at least two defensible remedies — let ratios() answer non-adjacent
// pairs (every named pair resolves, and the ladder stops being a chain), or re-point ROLE_PAIRS at
// the adjacencies the comp actually has (wordmark|health, health|clocks) and grade the rungs the
// comp really carries. That is a ruling about the ladder's meaning, of the same kind M1-71 took for
// frame normalisation, and one seat making it inside a sweep row is how a repo gets two
// conventions. Reported with the measurement and the two candidate remedies; the ruling is the
// orchestrator's.
// ---------------------------------------------------------------------------------------------

/** The roles no pair mentions, printed by name: `pair it, or say you do not`. */
export function unpairedRoles() {
  const paired = new Set(ROLE_PAIRS.flatMap(([a, b]) => [a, b]));
  return BUILD_ROLES.filter((r) => r.comp !== null && !paired.has(r.id)).map((r) => r.id);
}

// ----------------------------------------------------------------------- the build

/** Every role the build renders, with the selector that carries it. */
export const BUILD_ROLES = [
  { id: 'rule.wordmark', sel: '.myx-rule-wordmark', comp: 'wordmark' },
  { id: 'rule.clocks', sel: '.myx-rule-clocks', comp: 'clocks' },
  { id: 'rule.health', sel: '.myx-rule-health', comp: 'health' },
  { id: 'rule.window', sel: '.myx-rule-window', comp: 'nearest-window' },
  { id: 'rule.none', sel: '.myx-rule-none', comp: 'no-window' },
  { id: 'bay.label', sel: '.myx-bay-label', comp: 'bay-deepseek-label' },
  { id: 'chat.label', sel: '.myx-board-bay-chat .myx-bay-label', comp: 'chat-label' },
  { id: 'rail.tab', sel: '.myx-rail-tab', comp: null },
  { id: 'strip.field-label', sel: '.myx-sfield-label', comp: null },
  { id: 'strip.field-value', sel: '.myx-sfield-value', comp: null },
  { id: 'page.title', sel: '.myx-page-title, h1.myx-rule-cell, .myx-lt-head + *', comp: null },
];

/**
 * The spacing roles, each a pair of elements whose gap the eye reads, measured WITHIN ONE CONTAINER.
 *
 * The container is the whole point and it was learned by measuring wrong first: on the team board the
 * strips are absolutely positioned into the board's composition, so "the first two `.myx-strip`
 * elements" are two strips from different bays and their vertical gap read 93.1px against the comp's
 * 33.3 — a number about the board, not about the rack's rhythm. Scoped to the first bay that holds
 * two strips, the same page measures the module's own rhythm.
 */
export const SPACE_ROLES = [
  { id: 'label-to-value', container: '.myx-sfield', from: '.myx-sfield-label', to: '.myx-sfield-value',
    note: 'the gap inside one field box: label bottom to value top' },
  { id: 'row-to-row', container: '.myx-bay', need: 2, from: '.myx-strip', to: '.myx-strip', sibling: true,
    note: 'two strips of the same rack: pitch minus strip height' },
  { id: 'bay-label-to-rack', container: '.myx-bay', need: 1, from: '.myx-bay-label', to: '.myx-strip',
    note: 'the bay head plate to the first strip under it, in the same rack' },
  { id: 'plate-to-plate', from: '.myx-rail-tab', to: '.myx-rail-tab', sibling: true, comp: null,
    note: 'rail plate pitch minus plate height' },
];

/** The comp's spacing, derived from its own region boxes rather than typed. */
export function compSpacing(spec) {
  const box = (id) => spec.regions.find((r) => r.id === id).box;
  const strip = box('lead-strip');
  const strip2 = box('lead-strip-2');
  const strip3 = box('lead-strip-3');
  const bayLabel = box('bay-deepseek-label');
  const pitch = ((strip3.y - strip.y) / 2) * spec.compSize.height;
  return {
    'row-to-row': { px: pitch - strip.h * spec.compSize.height, note: `${(pitch).toFixed(1)}px pitch − ${(strip.h * spec.compSize.height).toFixed(1)}px strip` },
    'bay-label-to-rack': { px: (strip.y - bayLabel.y - bayLabel.h) * spec.compSize.height, note: 'label plate bottom to the first strip' },
  };
}

// ----------------------------------------------------------------- the in-page probe

/** The rendered cap of every role, measured as ink: a capital's actualBoundingBoxAscent in the
 *  face that renders, at the element's own size, times its accumulated transform scale. */
const PROBE = (roles, spaces) => `(() => {
  const cs = (el) => getComputedStyle(el);
  const canvas = document.createElement('canvas');
  const ctx = canvas.getContext('2d');
  const scaleOf = (el) => { let s = 1;
    for (let n = el; n && n.nodeType === 1; n = n.parentElement) {
      const t = cs(n).transform;
      if (t && t !== 'none') { const m = new DOMMatrix(t); const det = Math.abs(m.a * m.d - m.b * m.c); if (det > 0) s *= Math.sqrt(det); } }
    return s; };
  // THE CONTINUOUS CAP (M1-72). This used to be ctx.measureText('H').actualBoundingBoxAscent, which
  // returns WHOLE PIXELS: measured across 10-24px at 400/500/600 it yields only 8,9,10,11,13,14,15,17,
  // so every ratio the gating ladder computed was a quotient of rounded integers. rule.window/bay.label
  // read 1.000 (11/11) against a true 1.0625 (17/16) - spending 0.073 of the 0.08 tolerance on
  // rounding alone, where comp-check's continuous ladder reads -0.011 for the same pair. Same property,
  // two instruments, and the one that GATES was the retired one.
  //
  // cap = computed font-size x the face's own cap ratio. Measured twice by independent instruments:
  // the canvas metric at 200px, and rasterized ink rows of a capital counted off a screenshot (138
  // rows for Archivo 400 and 600, 146 for Mono 400) - agreeing to the pixel. NOTE THE ORDERING IS THE
  // REVERSE of the 0.7390/0.7000 this campaign carried until today: these faces measure MONO TALLER,
  // not Archivo.
  const CAP_RATIO = { 'Archivo': 0.6900, 'JetBrains Mono': 0.7300 };
  const faceOf = (el) => { const s = cs(el);
    const size = parseFloat(s.fontSize);
    const family = s.fontFamily.split(',')[0].trim().replace(/["']/g, '');
    const ratio = CAP_RATIO[family];
    ctx.font = s.fontWeight + ' ' + s.fontSize + ' ' + s.fontFamily;
    // An unknown face falls back to the canvas metric and SAYS SO: a silent fallback here would put
    // the rounded integer back into the gating path without anyone seeing it.
    const cap = ratio === undefined ? ctx.measureText('H').actualBoundingBoxAscent : size * ratio;
    return { cap, size, weight: s.fontWeight, family, scale: scaleOf(el),
      measured: ratio === undefined ? 'canvas (face not in CAP_RATIO)' : 'continuous' }; };
  const rect = (el) => { const r = el.getBoundingClientRect(); return { x: r.x, y: r.y, w: r.width, h: r.height }; };
  const roles = ${JSON.stringify(roles)}.map((role) => {
    const el = document.querySelector(role.sel);
    return el === null ? { id: role.id, absent: true } : Object.assign({ id: role.id, comp: role.comp }, faceOf(el), { rect: rect(el) });
  });
  const spaces = ${JSON.stringify(spaces)}.map((role) => {
    let first; let second;
    if (role.container !== undefined) {
      const holder = [...document.querySelectorAll(role.container)]
        .find((h) => h.querySelectorAll(role.sibling === true ? role.from : role.from).length >= (role.need ?? 1)
          && (role.sibling !== true || h.querySelectorAll(role.from).length >= 2));
      if (holder === undefined) return { id: role.id, absent: true };
      const inside = [...holder.querySelectorAll(role.from)];
      first = inside[0];
      second = role.sibling === true ? inside[1] : holder.querySelector(role.to);
    } else {
      const all = [...document.querySelectorAll(role.from)];
      first = all[0];
      second = role.sibling === true ? all[1] : document.querySelector(role.to);
    }
    if (first === null || first === undefined || second === null || second === undefined) return { id: role.id, absent: true };
    const a = rect(first); const b = rect(second);
    return { id: role.id, gap: (b.y - (a.y + a.h)), pitch: role.sibling === true ? (b.y - a.y) : null, a, b };
  });
  return JSON.stringify({ frame: { w: window.innerWidth, h: window.innerHeight }, roles, spaces });
})()`;

// --------------------------------------------------------------------------- report

const pct = (value) => `${(value * 100).toFixed(0)}%`;

function report(comp, measurements, spec, spaceAddress) {
  const lines = [];
  const measured = comp.measured;
  lines.push('THE TYPE LADDER — the comp of record against the build, rung by rung');
  lines.push(`comp: ${spec.comp} (${spec.compSize.width}x${spec.compSize.height}), caps from its measured spec`);
  lines.push('');
  lines.push(`  COMP (${measured.length} roles with real lettering, largest first)`);
  for (const role of measured) lines.push(`    ${String(role.cap).padStart(6)}px  ${role.id}`);
  lines.push(`  comp roles EXCLUDED as unmeasured (a cap from fewer than ${MIN_GLYPHS} glyphs is the matcher failing):`);
  for (const role of comp.excluded) lines.push(`    ${String(role.cap).padStart(6)}px  ${role.id} (${role.glyphs} glyph${role.glyphs === 1 ? '' : 's'})`);
  lines.push('');
  const compRatios = new Map(ratios(measured).map((r) => [`${r.from}|${r.to}`, r.ratio]));
  const frameKeys = Object.keys(measurements).filter((key) => !key.startsWith('__'));
  for (const frame of frameKeys) {
    const roles = measurements[frame];
    lines.push(`  BUILD at ${frame}`);
    const ranked = roles.filter((r) => r.absent !== true).sort((a, b) => b.cap * b.scale - a.cap * a.scale);
    for (const role of ranked) {
      const rendered = role.cap * role.scale;
      const compRole = role.comp === null ? null : measured.find((m) => m.id === role.comp);
      const delta = compRole === null || compRole === undefined ? null : rendered - compRole.cap;
      // AWAY FROM THE COMP'S OWN FRAME an absolute-pixel delta is meaningless — the root tracks the
      // viewport since M1-26, so every cap is SUPPOSED to be larger at 3840. What carries across
      // frames is the ratio, which the section below prints. (comp-check learned this first and
      // prints `scaled` there; this file follows it.)
      const atCompFrame = frame === `${spec.compSize.width}x${spec.compSize.height}`;
      lines.push(`    ${rendered.toFixed(2).padStart(6)}px  ${role.id.padEnd(18)} ${role.family} ${role.weight} ${role.size}px`
        + (compRole === undefined || compRole === null ? ''
          : atCompFrame ? `   comp ${compRole.cap}px  delta ${delta >= 0 ? '+' : ''}${delta.toFixed(2)}`
            : `   comp ${compRole.cap}px  scaled ×${(rendered / compRole.cap).toFixed(3)}`));
    }
    for (const role of roles.filter((r) => r.absent === true)) lines.push(`    ${'—'.padStart(6)}     ${role.id.padEnd(18)} absent on this address`);
    lines.push('');
    lines.push('  THE RUNG-TO-RUNG RATIOS, which is what reads as hierarchy (a mean hides a flat rung):');
    lines.push('    pair                                     comp    build   delta');
    const byId = new Map(ranked.map((r) => [r.id, r.cap * r.scale]));
    for (const [buildFrom, buildTo, compFrom, compTo] of ROLE_PAIRS) {
      const a = byId.get(buildFrom); const b = byId.get(buildTo);
      const compRatio = compRatios.get(`${compFrom}|${compTo}`);
      if (a === undefined || b === undefined || compRatio === undefined) continue;
      const buildRatio = a / b;
      const delta = buildRatio - compRatio;
      const flag = Math.abs(delta) > RATIO_LIMIT ? '  <-- OFF' : '';
      lines.push(`    ${`${buildFrom} / ${buildTo}`.padEnd(40)} ${compRatio.toFixed(3)}   ${buildRatio.toFixed(3)}   ${delta >= 0 ? '+' : ''}${delta.toFixed(3)}${flag}`);
    }
    lines.push('');
  }
  lines.push('THE SPACING LADDER — gaps, not leading; the operator named spacing beside text size');
  lines.push(`rack gaps measured on #/${spaceAddress}; the rail plates are measured wherever they render`);
  lines.push('');
  const compSpace = compSpacing(spec);
  for (const frame of frameKeys) {
    lines.push(`  BUILD at ${frame}${frame === `${spec.compSize.width}x${spec.compSize.height}` ? '' : ' (absolute gaps scale; the ratio to the frame is what travels)'}`);
    for (const space of (measurements.__rack ?? {})[frame] ?? measurements.__spaces[frame] ?? []) {
      if (space.absent === true) { lines.push(`    ${space.id.padEnd(20)} absent on this address`); continue; }
      const compValue = compSpace[space.id];
      lines.push(`    ${space.id.padEnd(20)} ${space.gap.toFixed(1).padStart(7)}px`
        + (space.pitch === null ? '' : `  (pitch ${space.pitch.toFixed(1)}px)`)
        + (compValue === undefined ? '   comp —' : `   comp ${compValue.px.toFixed(1)}px  delta ${(space.gap - compValue.px) >= 0 ? '+' : ''}${(space.gap - compValue.px).toFixed(1)}`));
    }
    lines.push('');
  }
  lines.push(`  COMP, derived from its own region boxes rather than typed:`);
  for (const [id, value] of Object.entries(compSpace)) lines.push(`    ${id.padEnd(20)} ${value.px.toFixed(1).padStart(7)}px   (${value.note})`);
  return lines.join('\n');
}

// ------------------------------------------------------------------------- selftest

/** The ratio maths, mutation-proven: a flat rung must be flagged and a matching ladder must not. */
export function checkRatios(compLadder_, buildLadder) {
  const compRatios = new Map(ratios(compLadder_).map((r) => [`${r.from}|${r.to}`, r.ratio]));
  const byId = new Map(buildLadder.map((r) => [r.id, r.cap]));
  const findings = [];
  const skipped = [];
  for (const [buildFrom, buildTo, compFrom, compTo] of ROLE_PAIRS) {
    const a = byId.get(buildFrom); const b = byId.get(buildTo);
    const compRatio = compRatios.get(`${compFrom}|${compTo}`);
    // EVERY SKIP IS NAMED WITH ITS MISSING SIDE (M1-76). This was a bare `continue`, so a pair that
    // could not be compared left no trace anywhere — and since the only thing returned was the
    // findings array, `0 findings` was the same sentence whether six pairs held or zero pairs were
    // looked at. Rename a role, take the probe to an address that renders none of them, or let the
    // spec's region ids drift, and both instruments printed the exact literal their verify greps
    // for: `0 rungs off by more than 0.08`. The count of what was COMPARED is the denominator, and
    // it now travels with the findings so neither caller can summarise without it (law 34).
    if (a === undefined || b === undefined || compRatio === undefined) {
      skipped.push({
        pair: `${buildFrom}/${buildTo}`,
        why: [a === undefined ? `build has no ${buildFrom}` : null, b === undefined ? `build has no ${buildTo}` : null,
          compRatio === undefined ? `comp has no ${compFrom}|${compTo}` : null].filter(Boolean).join(', '),
      });
      continue;
    }
    const delta = a / b - compRatio;
    if (Math.abs(delta) > RATIO_LIMIT) findings.push({ pair: `${buildFrom}/${buildTo}`, comp: compRatio, build: a / b, delta });
  }
  return { findings, compared: ROLE_PAIRS.length - skipped.length, skipped };
}

function selftest() {
  const comp = [
    { id: 'wordmark', cap: 16 }, { id: 'clocks', cap: 12 }, { id: 'no-window', cap: 12 },
    { id: 'nearest-window', cap: 11.7 }, { id: 'bay-deepseek-label', cap: 10.9 }, { id: 'chat-label', cap: 9.8 },
  ];
  // `compared` is asserted on EVERY case, not just the new ones (M1-76). Five of the six named
  // pairs, because these synthetic ladders carry no `rule.health` and this comp carries no
  // `health` — which is a genuine fact about the fixtures and exactly the kind of quiet shortfall
  // that used to be invisible: `0 findings` looked identical at five pairs and at zero.
  const cases = [
    { label: 'a build ladder matching the comp has no findings', want: 0, pairs: 5,
      build: [{ id: 'rule.wordmark', cap: 16 }, { id: 'rule.clocks', cap: 12 }, { id: 'rule.none', cap: 12 },
        { id: 'rule.window', cap: 11.7 }, { id: 'bay.label', cap: 10.9 }, { id: 'chat.label', cap: 9.8 }] },
    // Moving ONE rung changes BOTH pairs it belongs to, so a single-rung defect reports twice —
    // which is correct and worth pinning: the tool names every relationship the rung broke.
    { label: 'a FLAT top rung is flagged on both pairs it touches, lower rungs exact', want: 2, pairs: 5,
      build: [{ id: 'rule.wordmark', cap: 16 }, { id: 'rule.clocks', cap: 14 }, { id: 'rule.none', cap: 12 },
        { id: 'rule.window', cap: 11.7 }, { id: 'bay.label', cap: 10.9 }, { id: 'chat.label', cap: 9.8 }] },
    { label: 'a whole ladder scaled by one factor has no findings (ratios are scale-free)', want: 0, pairs: 5,
      build: [{ id: 'rule.wordmark', cap: 32 }, { id: 'rule.clocks', cap: 24 }, { id: 'rule.none', cap: 24 },
        { id: 'rule.window', cap: 23.4 }, { id: 'bay.label', cap: 21.8 }, { id: 'chat.label', cap: 19.6 }] },
    { label: 'an inverted rung is flagged, and it flags BOTH pairs it touches', want: 2, pairs: 5,
      build: [{ id: 'rule.wordmark', cap: 16 }, { id: 'rule.clocks', cap: 20 }, { id: 'rule.none', cap: 12 },
        { id: 'rule.window', cap: 11.7 }, { id: 'bay.label', cap: 10.9 }, { id: 'chat.label', cap: 9.8 }] },
  ];
  // ---- THE EMPTY DENOMINATOR (M1-76). Both ways a ladder can compare NOTHING while still
  // returning `0 findings` — the build side drifting away from the pair ids, and the comp side
  // doing it. Before this row both printed the exact literal the verify lines grep for.
  cases.push(
    { label: 'a build ladder naming NONE of the pairs compares nothing (was: `0 rungs off`)', want: 0, pairs: 0,
      build: [{ id: 'renamed.wordmark', cap: 16 }, { id: 'renamed.clocks', cap: 12 }] },
    { label: 'an EMPTY build ladder compares nothing', want: 0, pairs: 0, build: [] },
    { label: 'a comp whose ids drifted compares nothing, even with a perfect build ladder', want: 0, pairs: 0,
      comp: [{ id: 'headline', cap: 16 }, { id: 'subhead', cap: 12 }],
      build: [{ id: 'rule.wordmark', cap: 16 }, { id: 'rule.clocks', cap: 12 }, { id: 'rule.none', cap: 12 },
        { id: 'rule.window', cap: 11.7 }, { id: 'bay.label', cap: 10.9 }, { id: 'chat.label', cap: 9.8 }] },
  );
  let bad = 0;
  for (const c of cases) {
    const { findings, compared, skipped } = checkRatios(c.comp ?? comp, c.build);
    // BOTH NUMBERS, because either alone is a half-check: the findings say what was WRONG and
    // `compared` says how much was LOOKED AT, and the defect this row found is entirely in the
    // second. A case that asserts only the first passes identically at five pairs and at zero.
    const ok = findings.length === c.want && compared === c.pairs && compared + skipped.length === ROLE_PAIRS.length;
    if (!ok) bad += 1;
    console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${c.label} — wanted ${c.want} finding(s) over ${c.pairs} pair(s), got ${findings.length} over ${compared}`
      + (findings.length > 0 && !ok ? ` (${findings.map((f) => f.pair).join(', ')})` : ''));
  }
  const total = cases.length;
  console.log(bad === 0 ? `\nselftest ${total}/${total} PASS` : `\nselftest ${total - bad}/${total}, ${bad} wrong`);
  process.exit(bad === 0 ? 0 : 1);
}

// ------------------------------------------------------------------------------ CLI

// pathToFileURL AND NOT `file://${argv[1]}` (M1-76). import.meta.url is percent-encoded and argv[1]
// is not, so on a checkout path holding a space this is FALSE when the file IS the program and the
// whole CLI — selftest included — silently does nothing and exits 0. Measured on the identical
// guard in law-check.mjs: 97 rows from this worktree, nothing at all from a copy under `has space`.
if (process.argv[1] !== undefined && import.meta.url === pathToFileURL(process.argv[1]).href) {
  if (process.argv.includes('--selftest')) selftest();

  const spec = JSON.parse(readFileSync(join(ROOT, 'webui/.impeccable/build/spec.json'), 'utf8'));
  const comp = compLadder(spec);
  const address = (() => {
    const at = process.argv.indexOf('--address');
    return at === -1 ? 'teams' : process.argv[at + 1];
  })();
  // The spacing half is measured on a page that renders the rack module plainly: the comp's own page
  // puts its strips into an absolutely-positioned board, whose gaps are composition rather than
  // rhythm. Named in the output so a reader knows which page each gap came from.
  const spaceAddress = (() => {
    const at = process.argv.indexOf('--space-address');
    return at === -1 ? 'fleet' : process.argv[at + 1];
  })();

  const measurements = { __spaces: {} };
  await withChrome({ 'myx-mgmt-key': mgmtKey() }, async (send) => {
    for (const [width, height] of FRAMES) {
      await send('Page.navigate', { url: 'about:blank' });
      await show(send, urlFor(address), width, height, 5500);
      const out = await send('Runtime.evaluate', { expression: PROBE(BUILD_ROLES, SPACE_ROLES), returnByValue: true });
      const value = JSON.parse(out.result.value);
      measurements[`${width}x${height}`] = value.roles;
      measurements.__spaces[`${width}x${height}`] = value.spaces;
      // the gaps again, on the rack page
      await send('Page.navigate', { url: 'about:blank' });
      await show(send, urlFor(spaceAddress), width, height, 5000);
      const rack = await send('Runtime.evaluate', { expression: PROBE([], SPACE_ROLES), returnByValue: true });
      measurements.__rack = measurements.__rack ?? {};
      measurements.__rack[`${width}x${height}`] = JSON.parse(rack.result.value).spaces;
    }
  });

  const text = report(comp, measurements, spec, spaceAddress);
  mkdirSync(OUT, { recursive: true });
  writeFileSync(join(OUT, 'ladder.txt'), `${text}\n`);
  console.log(text);
  console.log(`\nwritten to webui/.impeccable/review/type-ladder/ladder.txt`);

  const { findings, compared, skipped } = checkRatios(comp.measured, (measurements['1536x1024'] ?? []).filter((r) => r.absent !== true).map((r) => ({ id: r.id, cap: r.cap * r.scale })));
  // BOTH LADDERS: NOT YET PRODUCED (M1-72), and it says so rather than printing an empty table.
  //
  // The port, the health pair and the unpaired-roles naming are landed and selftested; this print -
  // every pair's ratio from the continuous caps against the same caps rounded to whole pixels, so a
  // reader can see what the port moved instead of taking it on faith - is the one piece that did not
  // land. Two attempts looked up the comp's caps under `comp.caps`, and they live under
  // `comp.measured`; the third read that field and the rows still did not match, so the roles in
  // `measurements[frame]` are reaching the pair ids through a mapping the report applies and this
  // block does not. The next touch is a diagnostic on that mapping, not a fourth guess.
  //
  // It is stubbed rather than silent because an instrument that says what it did not print is worth
  // more than one that prints a header over nothing, and because the port - the part that GATES - is
  // unaffected: `rungs off by more than ${RATIO_LIMIT}` below is computed from the continuous caps.
  console.log(`\n  both ladders: NOT PRODUCED (M1-72) - the continuous caps gate below; this print`);
  console.log('  would show each pair continuous against rounded, and its comp-cap lookup is unfinished.');
  const framesSeen = Object.keys(measurements).filter((k) => !k.startsWith('__'));
  console.log(`  (frames captured: ${framesSeen.join(', ')}; a cap from one frame is not comparable with`);
  console.log('   a comp cap from another frame - the ruling that settles this is M1-71, adopted here.');
  const unpaired = unpairedRoles();
  if (unpaired.length > 0) {
    console.log(`\n  graded against NOTHING (no pair names them): ${unpaired.join(', ')}`);
  }

  // THE DENOMINATOR BESIDE THE COUNT, ALWAYS (M1-76). `rungs off: 0` is the same three characters
  // whether every pair held or no pair was looked at, and a ladder that compared nothing is the
  // report a renamed role or a drifted spec id produces. The skipped pairs are named with the side
  // that was missing, and a ladder that compared NOTHING is DID NOT RUN, not a clean ladder.
  console.log(`\nrungs off by more than ${RATIO_LIMIT}: ${findings.length} (of ${compared} pair(s) compared, ${ROLE_PAIRS.length} named)`);
  for (const finding of findings) console.log(`  ${finding.pair}: comp ${finding.comp.toFixed(3)}, build ${finding.build.toFixed(3)}, delta ${finding.delta.toFixed(3)}`);
  for (const skip of skipped) console.log(`  NOT COMPARED ${skip.pair}: ${skip.why}`);
  if (compared === 0) {
    console.error(`DID NOT RUN: not one of the ${ROLE_PAIRS.length} named pairs could be compared, so \`${findings.length} rungs off\` is a statement about nothing`);
    process.exitCode = 2;
  }
}
