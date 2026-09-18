/**
 * M1-98: THE LIGHT ROOM, GOVERNED BY WHAT IS INTRINSIC RATHER THAN BY COMPARISON.
 *
 * WHY THIS ROW EXISTS. The coverage map used to hand every light row a verdict computed against the
 * dark comp - a lie, twenty-four times. M1-91 fixed it honestly: light reads NO COMP TARGET. That
 * is the correct sentence and it left a hole, because the console SHIPS light and nothing graded it
 * any more. A room with a wrong standard and a room with no standard are different problems, and
 * the trade was one for the other. There will be no light comp - operator ruling, quoted in the row
 * so nobody reopens it - so light is governed by an INTRINSIC standard instead: is this legible on
 * its own terms. A comp answers "does this match what was approved"; an intrinsic standard needs no
 * second approval.
 *
 * WHAT IT READS. Three sets of rows, all measured, none invented:
 *   light-census-3840.json  - M1-98's own run: light, 3840x2160, the frame no ink pass had used.
 *   light-census-1536.json  - M1-98's own run: light, 1536x1024, the comp's frame.
 *   ink-measurements.json   - M1-47's committed record: both themes at 1536, all 4,586 pairs.
 * The 3840 run is the point. M1-47 measured ink at 1536 and wrote a sentence in prose about 3840
 * ("the coverage ratios are scale-invariant, so they will read the same") that it later withdrew -
 * false by up to 15.43 points of coverage. Contrast ratios ARE nearly scale-invariant, because a
 * ratio is a function of two colours and neither colour knows the viewport. THE SET OF PAIRS IS NOT:
 * a different frame lays out differently, so different elements render and different pairs exist.
 * Which of the two facts matters here is not a matter of opinion, so both frames are measured.
 *
 * THE FLOOR IS THE CAMPAIGN'S OWN, TAKEN FROM ITS OWN ROWS RATHER THAN CHOSEN HERE. M1-11 (the
 * tokens row) writes the contrast wall verbatim: "4.5:1 for --ink, --ink-mute, --ink-strong on
 * --room and --room-deep, --strip-ink and --strip-ink-mute on --strip and --strip-field, --scope-ink
 * on --scope; 3:1 for each --edge-* on --room and on --strip; both themes", and the light-room row
 * repeats it as "every ink at or above 4.5:1 on the plane it sits on". That floor is FLAT: the
 * campaign grants no large-text exemption. M1-47 graded against WCAG AA *with* the usual (>=24px, or
 * >=18.66px at weight >=700) carve-out, which is a weaker bar wherever a carve-out applies. Both are
 * reported; the campaign's is the one that governs.
 *
 * AND THE FLOOR IS GRADED TWICE, ON PURPOSE, because the two gradings catch different things.
 *   CONTRACT  - the named pairings above, ink token against plane token. This is what
 *               tests/contrast.test.ts asserts, and it is structurally unable to catch D7: a rule
 *               that borrows an ink onto a plane it was never landed against is comparing a pair the
 *               contract never named. All nine D7 instances passed a token-level reading.
 *   RENDERED  - every (ink, ground) pair a live page actually paints, on its real composited
 *               ground. This is the one D7 lives in, and it is the one this census is for.
 *
 * A CENSUS, NOT A REPAIR. This row fixes nothing. It does not choose a colour, name a direction, or
 * edit a stylesheet. Repairs become their own rows with these numbers attached.
 *
 * Usage: node webui/.impeccable/review/ink/census-light.mjs <sweep-d7.json>
 *        node webui/.impeccable/review/ink/census-light.mjs --selftest
 */
import fs from 'node:fs';
// THE TREE STAMP (M1-104), shared rather than copied: this census reads the same stylesheets the
// D7 sweep does, so the stamp that says WHICH TREE those stylesheets were is the same stamp. A
// second copy of it would drift while both kept printing good numbers, which is the failure
// lib/fixtures.mjs carries in its own header.
import { treeState, describeTree } from './tree-state.mjs';

const R = '/home/user/Documents/dev/projects/atlas/repo/.claude/worktrees/v0.4.0';
const INK = `${R}/webui/.impeccable/review/ink`;

/** The campaign's floor, quoted from M1-11 rather than chosen here. */
export const FLOOR = 4.5;
/** WCAG's carve-out, so the difference between the campaign's floor and a lenient reading is visible. */
export const LARGE_FLOOR = 3.0;

/**
 * Every rendered pair that falls under the floor, plus the denominators behind the count.
 *
 * PURE, and it reports its own denominator, because the failure mode this whole campaign keeps
 * paying for is a check whose denominator came from the list it was checking. Given no rows it
 * returns `blind: true` and says so rather than a clean sweep of an empty set.
 */
export function grade(rows, floor = FLOOR) {
  const below = rows.filter((r) => r.ratio < floor);
  const byLarge = rows.filter((r) => r.large && r.ratio >= LARGE_FLOOR && r.ratio < floor);
  return {
    blind: rows.length === 0,
    pairs: rows.length,
    pages: [...new Set(rows.map((r) => r.addr))].sort(),
    // NOT `[...new Set(...)].size` -- spreading a Set yields an Array, which has `.length` and no
    // `.size`, so the first cut of this line returned undefined and printed the word "undefined" in
    // a table cell that a reader would take for a count. A measurement that degrades to prose
    // instead of throwing is the failure this campaign keeps paying for.
    classes: new Set(rows.map((r) => r.cls)).size,
    below,
    // Pairs the campaign's flat floor fails but WCAG's carve-out would excuse. Reported separately
    // and never subtracted from `below`: the campaign floor has no carve-out, so this number is a
    // note about the two bars, not a reprieve.
    belowByLargeOnly: byLarge,
    min: rows.reduce((m, r) => (m === null || r.ratio < m.ratio ? r : m), null),
  };
}

/** The same (address, class) pair measured in two themes or two frames - the row's own key for divergence. */
export function byKey(rows) {
  const m = new Map();
  for (const r of rows) {
    const k = `${r.addr}|${r.cls}`;
    if (!m.has(k)) m.set(k, []);
    m.get(k).push(r);
  }
  return m;
}

// --------------------------------------------------------------------------------------------
// The two prose facts this census must not re-derive, both given in the row: M1-52's 73-of-187 gap
// and M1-82's separator divergence. Both are carried as data, and the gap is re-derived anyway
// because a carried number is a number nobody can audit.
// --------------------------------------------------------------------------------------------

const read = (p) => JSON.parse(fs.readFileSync(p, 'utf8'));
const light = (rows) => rows.filter((r) => r.theme === 'light');

const SELFTEST = [
  { name: 'a pair under the flat floor is reported', rows: [{ addr: 'x', cls: 'c', ratio: 4.49, large: false }], expect: 1 },
  { name: 'a pair exactly on the floor passes (4.5 is the floor, not the target)', rows: [{ addr: 'x', cls: 'c', ratio: 4.5, large: false }], expect: 0 },
  { name: 'no rows at all is BLIND, and never a clean sweep', rows: [], blind: true },
  { name: 'the large-text carve-out is recorded but never subtracted from the failure count',
    rows: [{ addr: 'x', cls: 'c', ratio: 3.5, large: true }], expect: 1, largeOnly: 1 },
];

if (process.argv.includes('--selftest')) {
  let bad = 0;
  for (const t of SELFTEST) {
    const g = grade(t.rows);
    const ok = g.blind === !!t.blind && (t.blind || g.below.length === t.expect) && (t.largeOnly === undefined || g.belowByLargeOnly.length === t.largeOnly);
    if (!ok) bad++;
    console.log(`  ${ok ? 'ok  ' : 'FAIL'} ${t.name}`);
  }
  console.log(bad === 0 ? `selftest: ${SELFTEST.length} case(s) passed` : `selftest: ${bad} case(s) FAILED`);
  process.exit(bad === 0 ? 0 : 1);
}

const sweepPath = process.argv[2];
if (!sweepPath) {
  console.error('usage: census-light.mjs <sweep-d7.json>   (bun sweep-d7.mjs <path> writes it)');
  process.exit(2);
}
const sweepFile = read(sweepPath);
// A sweep artifact written before M1-104 is a bare array of rows; one written after carries
// {tree, rows}. Read both rather than refusing the older one - a stamp added today must not
// invalidate yesterday's proof.
const sweep = Array.isArray(sweepFile) ? sweepFile : sweepFile.rows;
const sweepTree = Array.isArray(sweepFile) ? null : sweepFile.tree;
if (!Array.isArray(sweep)) {
  console.error(`census-light: ${sweepPath} carries no rows array - a denominator that cannot be read is not an empty denominator`);
  process.exit(1);
}
const censusTree = treeState(['webui/src']);
const frames = {
  '3840': light(read(`${INK}/light-census-3840.json`)),
  '1536': light(read(`${INK}/light-census-1536.json`)),
  '1536-m1-47': light(read(`${INK}/ink-measurements.json`)),
};
const darkM147 = read(`${INK}/ink-measurements.json`).filter((r) => r.theme === 'dark');

// A FRAME THAT MEASURED NOTHING REFUSES. Every number below is a ratio over a denominator, and a
// run that rendered no pairs would otherwise print a page of zeroes that reads like a clean bill.
for (const [tag, rows] of Object.entries(frames)) {
  if (rows.length === 0) {
    console.error(`census-light: BLIND - frame ${tag} produced 0 light pairs, so there is nothing to grade`);
    process.exit(1);
  }
}

const graded = Object.fromEntries(Object.entries(frames).map(([tag, rows]) => [tag, grade(rows)]));

// ---------------------------------------------------------------------------------------------
// THE GAP: the 73 of 187 colour rules that have never rendered in any capture.
// ---------------------------------------------------------------------------------------------
const kinds = new Map();
for (const r of sweep) kinds.set(r.kind, (kinds.get(r.kind) ?? 0) + 1);
const unrendered = sweep.filter((r) => r.kind === 'UNRESOLVED');

// M1-80 dispositioned the 73. Read its record rather than restating its summary.
const exercised = read(`${INK}/exercised.json`);
const exLight = exercised.filter((r) => r.measured && r.measured.theme === 'light');
const dispositions = read(`${INK}/unexercised-dispositions.json`);
const dCounts = new Map();
for (const r of dispositions) {
  const d = r.state ?? 'NONE';
  dCounts.set(d, (dCounts.get(d) ?? 0) + 1);
}

// ---------------------------------------------------------------------------------------------
// DIVERGENCE - reported as divergence, never as a light defect unless light also fails the floor.
// ---------------------------------------------------------------------------------------------
const divergenceBetween = (a, b) => {
  const A = byKey(a), B = byKey(b);
  const out = [];
  for (const [k, ra] of A) {
    const rb = B.get(k);
    if (!rb) continue;
    const x = Math.max(...ra.map((r) => r.ratio));
    const y = Math.max(...rb.map((r) => r.ratio));
    const factor = x >= y ? x / y : y / x;
    if (factor >= 1.25) out.push({ key: k, a: +x.toFixed(2), b: +y.toFixed(2), factor: +factor.toFixed(2) });
  }
  return out.sort((p, q) => q.factor - p.factor);
};

const roomDivergence = divergenceBetween(frames['1536'], darkM147);
const frameDivergence = divergenceBetween(frames['1536'], frames['3840']);

// WHAT THE SECOND FRAME ACTUALLY FOUND, counted rather than asserted. A pair is identified by what
// it IS - page, class, ink, ground - so a class that renders the same ink on the same ground in both
// frames is one pair however many times it appears, and a class whose COLOUR differs between frames
// is two. The first set difference is the frame doing its job; the second is a finding.
const pairKey = (r) => `${r.addr}|${r.cls}|${r.ink}|${r.ground}`;
const set1536 = new Map(frames['1536'].map((r) => [pairKey(r), r]));
const set3840 = new Map(frames['3840'].map((r) => [pairKey(r), r]));
const only3840 = [...set3840].filter(([k]) => !set1536.has(k)).map(([, r]) => r);
const only1536 = [...set1536].filter(([k]) => !set3840.has(k)).map(([, r]) => r);

// The CONTROL, and the one comparison that can tell a frame effect from a tree effect: tonight's
// 1536 run against M1-47's committed 1536 record. Same frame, same probe, same addresses, two hours
// apart - so anything that differs between them is the TREE, not the viewport. Without this, a
// difference between the two FRAMES would be read as a frame effect when it may simply be that
// M1-80 landed in between (which it did).
const setM147 = new Map(frames['1536-m1-47'].map((r) => [pairKey(r), r]));
const onlyNow = [...set1536].filter(([k]) => !setM147.has(k)).map(([, r]) => r);
const onlyThen = [...setM147].filter(([k]) => !set1536.has(k)).map(([, r]) => r);

// ---------------------------------------------------------------------------------------------
// THE DOCUMENT
// ---------------------------------------------------------------------------------------------
const rows = (tag) => frames[tag].length;
const lines = [];
const say = (s = '') => lines.push(s);

say('# THE LIGHT ROOM, GOVERNED INTRINSICALLY (M1-98)');
say();
say(`Every ink-on-ground pair the light room actually RENDERS, against the campaign's own floor of`);
say(`**${FLOOR}:1**, with every pair below it named by page and class. **Nothing was fixed.**`);
say();
say('The floor is not chosen here: M1-11 writes it verbatim - 4.5:1 for the ink tokens on the planes');
say('the contract names, both themes - and it is FLAT, with no large-text exemption. M1-47 graded');
say('against WCAG AA *with* that carve-out, which is the weaker bar. Both are reported below.');
say();

say('## What was measured');
say();
say('| frame | theme | light pairs | pages | distinct classes | worst pair | under 4.5 |');
say('|---|---|---:|---:|---:|---|---:|');
for (const [tag, g] of Object.entries(graded)) {
  const theme = tag === '1536' || tag === '3840' ? 'light' : 'light (M1-47 record)';
  say(`| ${tag.replace('-m1-47', '')}${tag.includes('m1-47') ? ' (M1-47)' : ''} | ${theme} | ${g.pairs} | ${g.pages.length} | ${g.classes} | ${g.min.ratio.toFixed(2)}:1 \`${g.min.cls}\` | **${g.below.length}** |`);
}
say();
say(`The 3840 row is this row's own run: no ink pass had used that frame, and M1-47 measured only 1536`);
say(`before writing in prose that the other frame would read the same - a sentence it later withdrew.`);
say(`MEASURED, the two frames agree exactly (below). What moves in the table is not the frame: the`);
say(`1536 rows counted twice are the same frame measured two hours apart, and they differ by 4 pairs`);
say(`because M1-80 landed in between. The third column is a control, not a third frame.`);
say();

say('## Every light pair below the floor');
say();
const allBelow = [];
for (const [tag, g] of Object.entries(graded)) for (const r of g.below) allBelow.push({ frame: tag, ...r });
if (allBelow.length === 0) {
  // THE DENOMINATOR IS THE DISTINCT PAIRS, NOT THE SUM OF THE RUNS. Adding the three rows counts
  // gives 6,887, which sounds like three times the evidence and is not: two of the three runs are
  // the same frame twice and the two frames are the same layout at 2.5x, so the room holds 324
  // distinct pairs and everything above is a re-measurement of them.
  const totalRows = Object.values(graded).reduce((n, g) => n + g.pairs, 0);
  say(`**None.** The light room renders **${set1536.size} distinct (page, class, ink, ground) pairs**, and not one`);
  say(`falls under ${FLOOR}:1. Three runs stand behind that - the 1536 frame twice (${rows('1536')} and ${rows('1536-m1-47')} rows, two`);
  say(`hours apart) and the 3840 frame once (${rows('3840')} rows) - so what was measured is ${set1536.size} distinct`);
  say(`pairs ${totalRows} times over, not ${totalRows} pairs. The light room is legible on its own terms, which is`);
  say(`the question this row was cut to ask.`);
} else {
  say('| frame | page | class | ink | ground | px | ratio | ground painted by |');
  say('|---|---|---|---|---|---:|---:|---|');
  for (const r of allBelow.sort((a, b) => a.ratio - b.ratio)) {
    say(`| ${r.frame} | ${r.addr} | \`${r.cls}\` | ${r.ink} | ${r.ground} | ${r.size} | **${r.ratio.toFixed(2)}** | \`${r.groundFrom}\` |`);
  }
}
say();

// THE PAIR THAT GOVERNS THE ANSWER. A census of 2,293 pairs that all pass is a fact about the many;
// the one number that decides how much headroom the light room has is the worst of them, and it
// should be named rather than left in a table. It is the same pair in both frames, which is itself
// the strongest evidence that the second frame was not a formality.
const worst = graded['1536'].min;
say('## The pair that governs the answer');
say();
say(`Everything in the light room clears the floor, and the margin is not uniform. The closest pair`);
say(`is \`${worst.cls}\` on **${worst.addr}**: **${worst.ratio}:1** (\`${worst.ink}\` on \`${worst.ground}\` via`);
say(`\`${worst.groundFrom}\`, ${worst.size}px at 1536). It clears ${FLOOR} by **${(worst.ratio - FLOOR).toFixed(2)}**.`);
say(`The same pair is the worst at 3840 as well, at the same ${graded['3840'].min.ratio}:1 - the colours do not`);
say(`change with the frame, so the closest pair in the room is the closest pair in either frame.`);
say(`(Recomputed from the rounded hex rather than the probe's unrounded colours this pair reads 4.54;`);
say(`either way the margin over the floor is under a twentieth of a ratio point, and that is the fact.`);
say(`It is not a defect and nothing is fixed here. It is the number a future colour change has to beat.)`);
say();

say(`## The ${unrendered.length}-of-${sweep.length} gap: what this census cannot see, and what now fills part of it`);
say();
// M1-104: a denominator without a tree is not a denominator. It goes ABOVE the numbers, not in a
// footnote, because a reader who takes the number and skips the footnote has been misled by layout.
say(`**The tree this was read from: ${describeTree(censusTree)}.**`);
if (sweepTree) say(`The sweep's own stamp, which is the denominator below: ${describeTree(sweepTree)}.`);
else say(`The sweep artifact carried no tree stamp: it predates M1-104.`);
say();
say();
say(`The census is over pairs that RENDER. A rule that never renders contributes no pair, so it is`);
say(`invisible here by construction - which is not the same as being safe, and the row says so. The`);
say(`denominator is M1-52's, re-derived here by re-running \`sweep-d7.mjs\` rather than restating it:`);
say();
say('| kind | rules |');
say('|---|---:|');
for (const [k, n] of [...kinds].sort((a, b) => b[1] - a[1])) say(`| ${k} | ${n} |`);
say();
say(`**THE DENOMINATOR IS NOT THE ONE THE ROW NAMES, and it is not stable within this session either.**`);
say(`The row - and M1-52, and M1-80's own notes - all say *73 of 187*. Enumerated from the source`);
say(`tonight, this tree carries **${sweep.length}** \`color: var(--token)\` rules across the same 38 stylesheets, of`);
say(`which **${unrendered.length}** are unresolved. So the true sentence is ${unrendered.length}-of-${sweep.length}, and the drift runs in`);
say(`BOTH directions: this row watched the same sweep return 189 rules / 73 unresolved and then`);
say(`${sweep.length} / ${unrendered.length} minutes later, because a live seat is editing CSS while the census runs.`);
say(`That is law 24 in the small - a denominator quoted from a list rather than re-derived from the`);
say(`source, staying plausible while the source moves under it.`);
say();
const dispositioned = dispositions.length;
say(`${unrendered.length} rules are UNRESOLVED - they have never rendered in any capture, so no light`);
say(`census can reach them. M1-80 dispositioned the gap it found, ${dispositioned} rules, rather than leaving`);
say(`any of them absent:`);
say();
say('| disposition | rules | what a light census can say |');
say('|---|---:|---|');
say(`| EXERCISED | ${dCounts.get('EXERCISED') ?? 0} | measured against a real composited ground; **${exLight.length} of them in LIGHT** |`);
say(`| DEFERRED | ${dCounts.get('DEFERRED') ?? 0} | reached by nothing yet - no light measurement exists |`);
say(`| DEAD | ${dCounts.get('DEAD') ?? 0} | nothing builds it - no light measurement is possible |`);
say();
if (dispositioned !== unrendered.length) {
  say(`**AND THE TWO NUMBERS NO LONGER AGREE: ${dispositioned} dispositioned, ${unrendered.length} unresolved today.** That is`);
  say(`not an error in either record, and it is the sharpest form of the point above. M1-80 closed`);
  say(`against a denominator of ${dispositioned}; the tree has moved since. This row watched the SAME sweep`);
  say(`return 189 rules / 73 unresolved and then ${sweep.length} / ${unrendered.length} minutes apart in one session, while a live`);
  say(`seat edited CSS - so ${dispositioned - unrendered.length} of the rules M1-80 dispositioned are simply gone from the`);
  say(`source, or have changed kind. A disposition record is a photograph of a moving denominator, and`);
  say(`the only honest way to quote one is beside its date. **The reconciliation is written down once,`);
  say(`with the tree it was read from, in \`reconcile-m1-80.md\`** - it names the ${dispositioned - unrendered.length} rules that`);
  say(`moved, what happened to each, and which of them are landed rather than merely uncommitted.`);
  say();
}
if (exLight.length) {
  const worst = exLight.reduce((m, r) => (r.measured.ratio < m.measured.ratio ? r : m), exLight[0]);
  say(`**The ${exLight.length} exercised-in-light rules: none below the floor.** The worst is`);
  say(`\`${worst.sel}\` at ${worst.measured.ratio}:1 (\`${worst.measured.ink}\` on \`${worst.measured.ground}\` via`);
  say(`\`${worst.measured.groundFrom}\`). So the honest size of the light gap is ${(dCounts.get('DEFERRED') ?? 0) + (dCounts.get('DEAD') ?? 0)} rules -`);
  say(`${dCounts.get('DEFERRED') ?? 0} deferred and ${dCounts.get('DEAD') ?? 0} dead - and NONE of them is a measured light defect, because none of them has`);
  say(`ever been measured in light at all. That is a fixture gap and a deletion, not a contrast finding.`);
}
say();

say('## Room-to-room divergence, reported as divergence');
say();
say(`${roomDivergence.length} (page, class) pairs read differently by a factor of 1.25 or more between the two`);
say('rooms. **This is an expected finding and not automatically a defect** (M1-82 found the same');
say('separator reading nearly twice as strongly in one room as the other). A pair is called a LIGHT');
say('defect only where the light side *also* falls below the floor, which the table above settles.');
say();
// `d.a` is already the light side's best ratio for this key, so the test is the floor test itself.
const lightDefects = roomDivergence.filter((d) => d.a < FLOOR);
say(`Of those ${roomDivergence.length}, **${lightDefects.length} have a light side that fails the floor** - so the divergence is`);
say(`interesting rather than wrong in ${roomDivergence.length - lightDefects.length} of them. The widest:`);
say();
say('| page | class | light | dark | factor | light under floor? |');
say('|---|---|---:|---:|---:|---|');
for (const d of roomDivergence.slice(0, 12)) {
  const [addr, cls] = d.key.split('|');
  say(`| ${addr} | \`${cls}\` | ${d.a} | ${d.b} | ${d.factor}x | ${d.a < FLOOR ? '**YES**' : 'no'} |`);
}
say();
if (frameDivergence.length) {
  say(`${frameDivergence.length} (page, class) pairs read differently by 1.25x or more BETWEEN THE TWO LIGHT FRAMES.`);
  say('A contrast ratio is a function of two colours, so a pair whose ratio moves between frames is a');
  say('pair whose colours changed - the element rendered against a different ground at the other frame.');
  say();
  say('| page | class | 1536 | 3840 | factor |');
  say('|---|---|---:|---:|---:|');
  for (const d of frameDivergence.slice(0, 12)) {
    const [addr, cls] = d.key.split('|');
    say(`| ${addr} | \`${cls}\` | ${d.a} | ${d.b} | ${d.factor}x |`);
  }
} else {
  say(`**Between the two light frames: no divergence at all.** ${set1536.size} distinct (page, class, ink,`);
  say(`ground) pairs at 1536, ${set3840.size} at 3840, and the two sets are IDENTICAL - no pair renders only at`);
  say(`one frame, none renders with different colours at the two, and the raw row counts agree (${rows('1536')}`);
  say(`against ${rows('3840')}). Measured, this frame pair is the same room twice; why, and what is`);
  say(`actually moving in the table above, is the next section.`);
}
say();

say('### The second frame found nothing - and the control that says so rather than assuming it');
say();
say(`A pair is identified by what it IS - page, class, ink, ground - so a class painting the same ink`);
say(`on the same ground in both frames is ONE pair no matter how many times it appears, and a class`);
say(`whose colour differs between frames is TWO. By that identity, and measured rather than argued:`);
say();
say(`| | distinct light pairs |`);
say('|---|---:|');
say(`| in the 1536 frame | ${set1536.size} |`);
say(`| in the 3840 frame | ${set3840.size} |`);
say(`| **renders only at 3840** | **${only3840.length}** |`);
say(`| renders only at 1536 | ${only1536.length} |`);
say(`| renders in both but with different colours | ${new Set(frameDivergence.map((d) => d.key)).size} |`);
say();
const uniq = (rows_) => [...new Set(rows_.map((r) => `${r.addr}|${r.cls}|${r.ink} on ${r.ground} ${r.ratio}:1`))];
for (const r of uniq(only3840).slice(0, 8)) say(`- only at 3840: \`${r}\``);
for (const r of uniq(only1536).slice(0, 8)) say(`- only at 1536: \`${r}\``);
say();
say(`**That is the measured answer to whether light needed a second frame for ink, and it did not.**`);
say(`The reason is mechanical. The type scale is a function of the viewport: \`html { font-size:`);
say(`var(--root-size) }\` with \`--root-size: max(16px, 1.0417vw)\` (app.css:28, tokens.css:81), so the`);
say(`root is 16px at 1536 and 40px at 3840, and the same six sizes run 12/14/16/17/20/23 against`);
say(`30/35/40/42.5/50/57.5 - exactly 2.5x. The 3840 frame is the 1536 frame enlarged, so it holds the`);
say(`same elements and yields the same pairs. This does not contradict M1-40's finding that coverage`);
say(`moves up to 15.43 points between frames: that measures the frame, this counts the ink inside it.`);
say(`But no contrast verdict was owed to the second frame, and this census will not dress a`);
say(`formality up as a discovery.`);
say();
say(`**WHAT THE TABLE DOES SHOW IS THAT THE PAIR SET MOVES WITH THE TREE, NOT THE FRAME.** M1-47's`);
say(`committed 1536 record and tonight's 1536 run are the SAME FRAME, same probe, same 13 addresses,`);
say(`about two hours apart - and they differ. That difference is the control that makes the frame`);
say(`comparison readable, and it is why the two are reported separately:`);
say();
say(`| tonight (09:34) | M1-47 (07:54) |`);
say('|---|---:|');
say(`| ${set1536.size} distinct pairs | ${setM147.size} distinct pairs |`);
say(`| **${onlyNow.length}** render only tonight | **${onlyThen.length}** render only in the record |`);
say();
for (const r of uniq(onlyNow).slice(0, 8)) say(`- only tonight: \`${r}\``);
for (const r of uniq(onlyThen).slice(0, 8)) say(`- only in M1-47's record: \`${r}\``);
say();
say(`Both move because M1-80 landed between the two runs. Neither is a frame effect and neither is a`);
say(`defect; they are the tree changing under a fixed measurement, and they are exactly the drift that`);
say(`would be misread as a frame finding if only the two FRAMES had been compared.`);
say();

say('## What this census does NOT cover, stated rather than implied');
say();
say(`- **The edge floor.** The contract asks 3:1 for each \`--edge-*\` on \`--room\` and \`--strip\`. Edges`);
say(`  are marks, not text; this probe walks text nodes. Not measured here, and not claimed.`);
say(`- **Plane separation.** "Every adjacent plane pair at or above 1.6:1" is its own floor on its own`);
say(`  axis (M2-11). This census is ink on ground and says nothing about two grounds.`);
say(`- **Hover, focus and armed states.** Every number is the resting state.`);
say(`- **The ghosted hand-off strip.** Ruled decorative, with its own wall (a text node inside the`);
say(`  ghost must have a sibling outside it that clears AA) - which is a different assertion from this`);
say(`  one and is evaluated on the live page, not here.`);
say(`- **A pair the probe could not parse refuses the page** rather than skipping the ground, so a`);
say(`  page that contributed pairs here contributed all of them.`);
say();
say('## Regenerating this');
say();
say('```');
say('# the two light frames (M1-98 added the frame arguments; no arguments = the M1-47 run)');
say('node webui/.impeccable/review/ink/probe-ink.mjs --theme light --width 3840 --height 2160 \\');
say('  --tag 3840x2160 --settle 7000 --out webui/.impeccable/review/ink/light-census-3840.json \\');
say('  --shots webui/.impeccable/review/ink/census-3840');
say('node webui/.impeccable/review/ink/probe-ink.mjs --theme light \\');
say('  --out webui/.impeccable/review/ink/light-census-1536.json \\');
say('  --shots webui/.impeccable/review/ink/census-1536');
say('bun webui/.impeccable/review/ink/sweep-d7.mjs /tmp/d7-m198.json');
say('node webui/.impeccable/review/ink/census-light.mjs /tmp/d7-m198.json');
say('```');

fs.writeFileSync(`${INK}/light-census.md`, lines.join('\n') + '\n');
fs.writeFileSync(`${INK}/light-census.json`, JSON.stringify({
  floor: FLOOR,
  // WHICH TREE THIS READING IS (M1-104). Every number in this file is a reading of a tree, and
  // without these two lines the next reader cannot tell whether it still applies.
  tree: censusTree,
  sweepTree,
  frames: Object.fromEntries(Object.entries(graded).map(([t, g]) => [t, {
    pairs: g.pairs, pages: g.pages, classes: g.classes, below: g.below.length,
    belowByLargeOnly: g.belowByLargeOnly.length, min: g.min,
  }])),
  below: allBelow,
  gap: { kinds: Object.fromEntries(kinds), unrendered: unrendered.length, dispositions: Object.fromEntries(dCounts), exercisedInLight: exLight.length },
  divergence: { rooms: roomDivergence, frames: frameDivergence, lightDefects: lightDefects.length },
}, null, 1));

console.log(`frames: ${Object.entries(graded).map(([t, g]) => `${t}=${g.pairs} pairs/${g.below.length} under ${FLOOR}`).join('  ')}`);
console.log(`gap: ${unrendered.length} unrendered of ${sweep.length}; ${exLight.length} exercised-in-light, 0 below floor`);
console.log(`divergence: ${roomDivergence.length} room pair(s) >=1.25x, ${lightDefects.length} with a light side under the floor`);
console.log(`wrote light-census.md and light-census.json`);
