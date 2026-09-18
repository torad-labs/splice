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
//   node dev/web-console/type-ladder.mjs [--address teams] [--json]
//   node dev/web-console/type-ladder.mjs --selftest
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { join, resolve } from 'node:path';
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
];

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
  const faceOf = (el) => { const s = cs(el);
    ctx.font = s.fontWeight + ' ' + s.fontSize + ' ' + s.fontFamily;
    const cap = ctx.measureText('H').actualBoundingBoxAscent;
    return { cap, size: parseFloat(s.fontSize), weight: s.fontWeight,
      family: s.fontFamily.split(',')[0].trim().replace(/["']/g, ''), scale: scaleOf(el) }; };
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
  for (const [buildFrom, buildTo, compFrom, compTo] of ROLE_PAIRS) {
    const a = byId.get(buildFrom); const b = byId.get(buildTo);
    const compRatio = compRatios.get(`${compFrom}|${compTo}`);
    if (a === undefined || b === undefined || compRatio === undefined) continue;
    const delta = a / b - compRatio;
    if (Math.abs(delta) > RATIO_LIMIT) findings.push({ pair: `${buildFrom}/${buildTo}`, comp: compRatio, build: a / b, delta });
  }
  return findings;
}

function selftest() {
  const comp = [
    { id: 'wordmark', cap: 16 }, { id: 'clocks', cap: 12 }, { id: 'no-window', cap: 12 },
    { id: 'nearest-window', cap: 11.7 }, { id: 'bay-deepseek-label', cap: 10.9 }, { id: 'chat-label', cap: 9.8 },
  ];
  const cases = [
    { label: 'a build ladder matching the comp has no findings', want: 0,
      build: [{ id: 'rule.wordmark', cap: 16 }, { id: 'rule.clocks', cap: 12 }, { id: 'rule.none', cap: 12 },
        { id: 'rule.window', cap: 11.7 }, { id: 'bay.label', cap: 10.9 }, { id: 'chat.label', cap: 9.8 }] },
    // Moving ONE rung changes BOTH pairs it belongs to, so a single-rung defect reports twice —
    // which is correct and worth pinning: the tool names every relationship the rung broke.
    { label: 'a FLAT top rung is flagged on both pairs it touches, lower rungs exact', want: 2,
      build: [{ id: 'rule.wordmark', cap: 16 }, { id: 'rule.clocks', cap: 14 }, { id: 'rule.none', cap: 12 },
        { id: 'rule.window', cap: 11.7 }, { id: 'bay.label', cap: 10.9 }, { id: 'chat.label', cap: 9.8 }] },
    { label: 'a whole ladder scaled by one factor has no findings (ratios are scale-free)', want: 0,
      build: [{ id: 'rule.wordmark', cap: 32 }, { id: 'rule.clocks', cap: 24 }, { id: 'rule.none', cap: 24 },
        { id: 'rule.window', cap: 23.4 }, { id: 'bay.label', cap: 21.8 }, { id: 'chat.label', cap: 19.6 }] },
    { label: 'an inverted rung is flagged, and it flags BOTH pairs it touches', want: 2,
      build: [{ id: 'rule.wordmark', cap: 16 }, { id: 'rule.clocks', cap: 20 }, { id: 'rule.none', cap: 12 },
        { id: 'rule.window', cap: 11.7 }, { id: 'bay.label', cap: 10.9 }, { id: 'chat.label', cap: 9.8 }] },
  ];
  let bad = 0;
  for (const c of cases) {
    const found = checkRatios(comp, c.build);
    const ok = found.length === c.want;
    if (!ok) bad += 1;
    console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${c.label} — wanted ${c.want} finding(s), got ${found.length}`
      + (found.length > 0 && !ok ? ` (${found.map((f) => f.pair).join(', ')})` : ''));
  }
  const total = cases.length;
  console.log(bad === 0 ? `\nselftest ${total}/${total} PASS` : `\nselftest ${total - bad}/${total}, ${bad} wrong`);
  process.exit(bad === 0 ? 0 : 1);
}

// ------------------------------------------------------------------------------ CLI

if (process.argv[1] !== undefined && import.meta.url === `file://${process.argv[1]}`) {
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

  const findings = checkRatios(comp.measured, (measurements['1536x1024'] ?? []).filter((r) => r.absent !== true).map((r) => ({ id: r.id, cap: r.cap * r.scale })));
  console.log(`\nrungs off by more than ${RATIO_LIMIT}: ${findings.length}`);
  for (const finding of findings) console.log(`  ${finding.pair}: comp ${finding.comp.toFixed(3)}, build ${finding.build.toFixed(3)}, delta ${finding.delta.toFixed(3)}`);
}
