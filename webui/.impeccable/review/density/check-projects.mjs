#!/usr/bin/env node
// check-projects — the projects page's acceptance, as a script rather than a grep (M1-111).
//
// WHY THIS REPLACED THE GREP. The old verify was `density.mjs | grep -A1 COMP | grep -qvE ^projects`,
// which asserted one thing: projects is not the worst-wasting page. It was red when the row was cut
// and it STAYS red under the change the row prescribed, because the prescribed change moves the
// number by zero — measured by injection: dead 43.7 to 43.7 with the largest unprinted region
// identical at 1432x480 at 104,544. A clause that cannot pass a correct row and cannot fail a wrong
// one is useless in both directions, which is what this file fixes.
//
// WHAT IT CHECKS INSTEAD, in the order the row's own question asks it:
//
//   1. WHAT THE PAGE PRINTS AGAINST WHAT THE ROUTE SERVES. The row's question is "what should
//      projects SAY about a repository that it is not saying", and that is answerable mechanically:
//      ProjectRow's fields are the denominator (read from the type, not listed here), every field
//      must be either a column the page prints or an entry in EXEMPT with a WRITTEN reason, and a
//      field in neither FAILS BY NAME. This is the completeness law's shape — the denominator comes
//      from the source, so the check can fail for something nobody thought to list.
//   2. THE DEAD NUMBER, computed with the instrument's own definitions and printed with the tree it
//      was measured on (a bare percentage is not evidence: three seats saw three denominators in one
//      hour). Reported, not gated: the row established that no sizing change moves it.
//   3. COVERAGE: the capture must be newer than webui/src, or this is a DID NOT RUN rather than a
//      pass on a stale picture (M1-68's rule, applied to the one capture this script reads).
//
// Exit 0 = every check passed. Exit 1 = a check failed (by name). Exit 2 = the SOURCE could not be
// read, which is the only way this script can fail to run: the capture is optional and its absence
// is reported rather than fatal.
import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import zlib from 'node:zlib';
import { cutFor, layers, largestUnprintedRect } from './density.mjs';

const ROOT = path.resolve(import.meta.dirname, '../../../..');
const CAPTURE = path.join(ROOT, 'webui/.impeccable/review/sections/projects-dark-1536x1024.png');
// The two sources this script reads are overridable ONLY so the check can be mutation-proved
// against a copy: a check that has never failed is a tautology, and pointing it at a mutated copy
// is how that is demonstrated without editing the tree it judges.
const flag = (name, dflt) => { const at = process.argv.indexOf(`--${name}`); return at === -1 ? dflt : process.argv[at + 1]; };
const TYPES = flag('types', path.join(ROOT, 'webui/src/entities/project/model/types.ts'));
const PAGE = flag('page', path.join(ROOT, 'webui/src/pages/projects/index.tsx'));
const SRC = path.join(ROOT, 'webui/src');

/**
 * THE FIELDS THE PAGE DOES NOT PRINT, EACH WITH THE FACT THAT MAKES THAT RIGHT.
 *
 * A field listed here without a reason is an absence wearing a label, so the entry IS the reason and
 * an empty one fails. `day_start` is the only one: the route sends it so the console does not print
 * "today" over a boundary it guessed (a local midnight, a rolling 24h and a UTC day render
 * differently and only the daemon knows which one it counted), which makes it a QUALIFIER for the
 * cost column rather than a value of its own.
 */
const EXEMPT = {
  day_start: 'the start of the day the counts cover: a qualifier for the cost column, not a value',
  id: 'the git root, which is also the route id: the same string as root, printed by the repo column',
};

const findings = [];
const read = (p) => fs.readFileSync(p, 'utf8');

// ---- 1. what the page prints against what the route serves
const typeText = read(TYPES);
const block = typeText.match(/export interface ProjectRow \{([\s\S]*?)\n\}/);
if (block === null) { console.error('DID NOT RUN: ProjectRow is not where this script reads it'); process.exit(2); }
const served = [...block[1].matchAll(/^\s{2}([a-z_]+)\??:/gm)].map((m) => m[1]);
if (served.length === 0) { console.error('DID NOT RUN: ProjectRow parsed to zero fields'); process.exit(2); }
const pageText = read(PAGE);
// THE MAPPING, READ FROM THE PAGE'S OWN CELL BUILDER: every entry of fieldsOf's `values` object is
// one cell, and the row field it reads is named on the right of `row.`. That is the honest test of
// "does the page say this" -- reading a field into a strip cell -- and it cannot be satisfied by a
// list written here.
// THE MAPPING, READ FROM THE PAGE'S OWN CELL BUILDER. Not a regex over one object literal: two of
// the six cells are computed ABOVE the map (cost through costText, last through timeAgo), so a
// pattern that required `row.x` inside the same braces reported them unprinted -- the same mistake
// as counting by value or by filename, in a third costume. The whole fieldsOf body is the unit: any
// `row.<field>` the function reads is a field the page turns into a cell, and COLUMNS says which
// column prints it.
const builder = pageText.match(/function fieldsOf\(row: ProjectRow, order: readonly string\[\]\): Field\[\] \{([\s\S]*?)\n\}/);
if (builder === null) { console.error("DID NOT RUN: fieldsOf is not where this script reads it"); process.exit(2); }
const readFields = new Set([...builder[1].matchAll(/row\.([a-z_]+)/g)].map((m) => m[1]));
const columns = new Set([...pageText.matchAll(/^\s{2}([a-z_]+): \{ label: S\.\w+, w: \d+ \},$/gm)].map((m) => m[1]));
// THE CELLS THE PAGE ACTUALLY BUILDS: the keys of fieldsOf's own `values` object. A column with no
// cell is a header printed over nothing -- and READING a field is not PRINTING it, which the first
// version of this check got wrong: with the cost cell removed the builder still reads
// `row.cost_today_usd`, so the check passed on a page that had stopped printing it. Both directions
// are mutation-proved in the row's note.
const cells = new Set([...builder[1].matchAll(/^\s{4}([a-z_]+):/gm)].map((m) => m[1]));
if (readFields.size === 0 || columns.size === 0 || cells.size === 0) { console.error('DID NOT RUN: the page parsed to zero read fields, cells or columns'); process.exit(2); }
for (const column of columns) {
  if (!cells.has(column)) findings.push(`${column}: declared as a column and no cell builds it, so the rack would print a header over nothing`);
}
for (const cell of cells) {
  if (!columns.has(cell)) findings.push(`${cell}: a cell with no declared column, so nothing prints its name`);
}
for (const field of served) {
  const printed = readFields.has(field) && columns.size > 0;
  const reason = Object.prototype.hasOwnProperty.call(EXEMPT, field) ? EXEMPT[field] : null;
  const ok = printed || (reason !== null && reason !== '');
  if (!ok) findings.push(`${field}: served by the route and neither printed by a column nor exempt with a written reason`);
}
console.log(`  fields served by /api/projects: ${served.join(', ')}`);
console.log(`  the cell builder reads:         ${[...readFields].join(', ')}`);
console.log(`  columns declared:               ${[...columns].join(', ')}`);
console.log(`  cells the page builds:          ${[...cells].join(', ')}`);
console.log(`  exempt with a written reason:   ${served.filter((f) => !readFields.has(f)).join(', ')}`);

// ---- 2. the capture: REPORTED when it is evidence, NAMED when it is not
//
// THE GATED CHECKS ABOVE READ SOURCE, so they never depend on this. That split is deliberate and it
// is M1-94's lesson applied to my own script: the first version made a stale capture a DID NOT RUN
// for the WHOLE file, and in a six-seat tree any peer's edit to webui/src makes every capture stale
// within minutes -- so the verify would have been red by design and the checks that had nothing to
// do with a picture would have gone with it. A number that cannot be refreshed is reported as not
// measured, stated; it does not take the source checks down.
let newestSource = 0;
const walk = (d) => { for (const e of fs.readdirSync(d, { withFileTypes: true })) {
  const p = path.join(d, e.name);
  if (e.isDirectory()) walk(p); else if (/\.(css|tsx|ts)$/.test(e.name)) newestSource = Math.max(newestSource, fs.statSync(p).mtimeMs); } };
walk(SRC);
const captureAge = fs.existsSync(CAPTURE) ? fs.statSync(CAPTURE).mtimeMs : 0;
if (captureAge === 0) { console.error(`DID NOT RUN: no capture at ${path.relative(ROOT, CAPTURE)}`); process.exit(2); }
const fresh = captureAge >= newestSource;
const tree = execFileSync('git', ['rev-parse', '--short', 'HEAD'], { cwd: ROOT, encoding: 'utf8' }).trim();
const dirty = execFileSync('git', ['status', '--porcelain'], { cwd: ROOT, encoding: 'utf8' }).trim().split('\n').filter(Boolean).length;
console.log(`  capture freshness: ${fresh ? 'fresh' : 'STALE'} (measured at tree ${tree}, ${dirty} dirty files)`);

// ---- 3. the dead number, reported with the tree when the picture is evidence
const dead = fresh ? (() => {
  const png = readPng(CAPTURE);
  const cut = cutFor(png);
  const l = layers(png, cut);
  return { ...l, ...largestUnprintedRect(png, cut) };
})() : null;
if (dead !== null) {
  console.log(`  projects at 1536 dark: paper ${dead.paper.toFixed(1)} print ${dead.printed.toFixed(1)} ruled ${dead.ruled.toFixed(1)} floor ${dead.floor.toFixed(1)}`);
  console.log(`  dead% ${dead.pct.toFixed(1)} — largest unprinted region ${dead.w}x${dead.h} at ${dead.x},${dead.y}`);
  console.log('  (reported, not gated: M1-111 established by injection that no sizing change moves this number)');
} else {
  console.log('  dead% NOT MEASURED this run: the capture predates webui/src, so it is not evidence about this build.');
  console.log('  (the checks above read source and did run; refresh the captures with look-gate to report the number)');
}

for (const f of findings) console.error(`FAIL projects ${f}`);
// A STALE CAPTURE IS A DID NOT RUN, NOT A FINDING: it exits 2 like every other instrument in this
// tree, because "I measured nothing" and "I found something" are different answers and a build
// script reading only the exit code must be able to tell them apart (law 23).
console.log(findings.length === 0 ? '\ncheck-projects: PASS' : `\ncheck-projects: ${findings.length} finding(s)`);
process.exit(findings.length === 0 ? 0 : 1);

/**
 * The instrument's decoder is not exported (pixels() is internal), so this script decodes with the
 * gate's own reader and feeds the instrument's PURE functions. Cut here rather than copied: the
 * three definitions above are imported, so they cannot drift from the instrument.
 */
function readPng(file) {
  const buf = fs.readFileSync(file);
  let at = 8; const idat = []; let width = 0, height = 0, depth = 0, colour = 0;
  while (at < buf.length) {
    const len = buf.readUInt32BE(at); const type = buf.toString('ascii', at + 4, at + 8);
    const data = buf.subarray(at + 8, at + 8 + len);
    if (type === 'IHDR') { width = data.readUInt32BE(0); height = data.readUInt32BE(4); depth = data[8]; colour = data[9]; }
    if (type === 'IDAT') idat.push(data);
    at += 12 + len;
  }
  if (depth !== 8 || colour !== 2) throw new Error(`${file}: expected 8-bit RGB`);
  const raw = zlib.inflateSync(Buffer.concat(idat));
  const stride = width * 3; const pixels = Buffer.alloc(stride * height);
  let prev = Buffer.alloc(stride);
  for (let y = 0; y < height; y += 1) {
    const filter = raw[y * (stride + 1)]; const line = Buffer.from(raw.subarray(y * (stride + 1) + 1, (y + 1) * (stride + 1)));
    for (let i = 0; i < stride; i += 1) {
      const a = i >= 3 ? line[i - 3] : 0, b = prev[i], c = i >= 3 ? prev[i - 3] : 0;
      if (filter === 1) line[i] = (line[i] + a) & 0xff;
      else if (filter === 2) line[i] = (line[i] + b) & 0xff;
      else if (filter === 3) line[i] = (line[i] + ((a + b) >> 1)) & 0xff;
      else if (filter === 4) { const pp = a + b - c; const pa = Math.abs(pp - a), pb = Math.abs(pp - b), pc = Math.abs(pp - c);
        line[i] = (line[i] + (pa <= pb && pa <= pc ? a : pb <= pc ? b : c)) & 0xff; }
    }
    line.copy(pixels, y * stride); prev = line;
  }
  // THE INSTRUMENT'S OWN SHAPE: its lumAt reads im.data with 3 channels implied, so this
  // decoder returns that rather than its own naming. If the decode is wrong the number below
  // will not be 43.7, which is the calibration this script gets for free.
  return { w: width, h: height, data: pixels };
}
