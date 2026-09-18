#!/usr/bin/env node
// surface — how much of this console's thinness is a DESIGN fact and how much is a GATEWAY fact.
//
// WHY THIS EXISTS (M1-118). M1-111 established for ONE page that projects cannot say more about a
// repository: /api/projects serves eight fields, the page prints six, and the other two are a
// duplicate and a qualifier. M1-109 measured the campaign's central number — PRINTED is the only
// layer that discriminates (comp 6.17, teams 5.72, every other address 0.85 to 3.58) and the comp
// earns it by composing MANY SMALL PRINTED MEMBERS. That finding assumes the pages COULD compose
// more if they were designed to. This file tests that assumption across the console.
//
// THE DENOMINATOR COMES FROM THE SOURCE, ON BOTH SIDES, AND IS NEVER A LIST WRITTEN HERE:
//   - the routes and their fields are parsed out of webui/src/entities/*/api and model/types.ts --
//     what the console can READ, which is the only surface a page can compose from;
//   - what a page PRINTS is parsed out of its own cells, the pattern M1-111's check-projects.mjs
//     established for one page and this file generalises to all of them;
//   - the PENDING surface is parsed from the console's own PENDING_* constants, so the row ids the
//     console already cites are quoted rather than retyped.
// Every served field available to a page gets a disposition: PRINTED, or DELIBERATELY-NOT with a
// written reason, or MISSING. Every page gets a headroom number: how many served fields it holds
// and does not print. A page whose served surface is exhausted is NOT a thin page -- it is a
// finished page against a pending gateway, and it is named as such.
//
// THE CROSSING IS THE DELIVERABLE. Low PRINTED with headroom is a design defect and belongs in m2.
// Low PRINTED with no headroom is a gateway fact and belongs in a route row or in nobody's hands.
// If those two sets are wildly different sizes, that is the most important sentence in the campaign.
//
// Usage: node surface.mjs [--json OUT] [--selftest]
import fs from 'node:fs';
import path from 'node:path';

const ROOT = path.resolve(import.meta.dirname, '../../../..');
const SRC = path.join(ROOT, 'webui/src');
const ENTITIES = path.join(SRC, 'entities');
const PAGES = path.join(SRC, 'pages');
const DENSITY = path.join(ROOT, 'webui/.impeccable/review/density/density.json');

const ARGS = process.argv.slice(2);
const flagOf = (name, dflt) => { const at = ARGS.indexOf(`--${name}`); return at === -1 ? dflt : ARGS[at + 1]; };

/** Every file under a directory, by extension. */
function filesUnder(dir, ext) {
  const out = [];
  const walk = (d) => {
    let entries; try { entries = fs.readdirSync(d, { withFileTypes: true }); } catch { return; }
    for (const e of entries) {
      const p = path.join(d, e.name);
      if (e.isDirectory()) walk(p); else if (ext.test(e.name)) out.push(p);
    }
  };
  walk(dir);
  return out;
}

/** THE ROUTES THE CONSOLE CAN READ, and the fields each one serves, both parsed from the entity. */
export function readEntities(root = ENTITIES) {
  const entities = new Map();
  for (const dir of fs.readdirSync(root, { withFileTypes: true }).filter((e) => e.isDirectory())) {
    const entity = dir.name;
    const apiFiles = filesUnder(path.join(root, entity, 'api'), /\.ts$/);
    // ALL of the entity's .ts, not model/types.ts: heads keeps its interfaces in model/derive.ts,
    // so a rule that named one filename reported seven entities as serving nothing -- which the
    // first run printed as "GATEWAY: surface exhausted" for pages that plainly read data.
    const typeFiles = filesUnder(path.join(root, entity), /\.ts$/);
    const routes = new Set();
    for (const f of apiFiles) for (const m of fs.readFileSync(f, 'utf8').matchAll(/['"`](\/api\/[a-z0-9/{}$.-]*)['"`]/gi)) routes.add(m[1]);
    const fields = new Set();
    for (const f of typeFiles) {
      // EVERY EXPORTED INTERFACE the entity declares, by name-agnostic rule: naming *Row* missed
      // HeadSignals and HeadAttention, which are exactly what the fleet page renders.
      const text = fs.readFileSync(f, 'utf8');
      for (const m of text.matchAll(/export interface \w+ \{([\s\S]*?)\n\}/g)) {
        for (const f2 of m[1].matchAll(/^\s{2}([a-z_]+)\??:/gm)) fields.add(f2[1]);
      }
    }
    // PENDING constants cite the row that will serve them: quoted, not retyped.
    const pending = new Set();
    for (const f of [...typeFiles, ...filesUnder(path.join(root, entity), /\.ts$/)]) {
      for (const m of fs.readFileSync(f, 'utf8').matchAll(/PENDING_\w+\s*=\s*'([^']+)'/g)) pending.add(m[1]);
    }
    entities.set(entity, { routes: [...routes].sort(), fields: [...fields].sort(), pending: [...pending].sort() });
  }
  return entities;
}

/** WHAT A PAGE PRINTS, from its own cells: the field names its render reads off a row object. */
export function readPages(root = PAGES, entities = readEntities()) {
  const pages = new Map();
  for (const dir of fs.readdirSync(root, { withFileTypes: true }).filter((e) => e.isDirectory())) {
    const page = dir.name;
    // A PAGE PRINTS THROUGH ITS WIDGETS: the fleet page renders HeadStrip, and every field it
    // shows is read in the widget, not in the page directory -- which is why the first run reported
    // fleet as printing nothing at all. The page's composition is its own directory plus the
    // widgets and features it imports.
    const files = filesUnder(path.join(root, page), /\.tsx?$/);
    for (const f of files) {
      for (const m of fs.readFileSync(f, 'utf8').matchAll(/from '@(widgets|features)\/([a-z-]+)'/g)) {
        for (const w of filesUnder(path.join(SRC, m[1], m[2]), /\.tsx?$/)) files.push(w);
      }
    }
    const printed = new Set();
    const reads = new Set();
    for (const f of files) {
      const text = fs.readFileSync(f, 'utf8');
      for (const m of text.matchAll(/\b(\w+)\.([a-z_]+)\b/g)) { reads.add(`${m[1]}.${m[2]}`); printed.add(m[2]); }
      for (const m of text.matchAll(/from '@entities\/([a-z-]+)'/g)) reads.add(`@${m[1]}`);
    }
    const imported = new Set([...reads].filter((r) => r.startsWith('@')).map((r) => r.slice(1)));
    const available = new Set();
    for (const e of imported) { const found = entities.get(e); if (found) for (const f of found.fields) available.add(f); }
    pages.set(page, { imported: [...imported].sort(), available: [...available].sort(), printed: [...printed].sort() });
  }
  return pages;
}

/**
 * THE CENSUS: per page, the served fields it holds, the ones it prints, and the headroom.
 *
 * `printed` is read from the page's own source, so a page that reads a field into a cell counts as
 * printing it -- M1-111's check found that reading is not printing, and the difference there was a
 * cell the page had stopped building. At census scale the distinction is too fine to parse
 * reliably, and it is stated as a limit rather than hidden: this number is an upper bound on what
 * each page says, so a page with headroom is a page with MORE room than the number shows.
 */
export function census(pages, entities) {
  const rows = [];
  for (const [page, info] of pages) {
    const printed = info.available.filter((f) => info.printed.includes(f));
    const headroom = info.available.filter((f) => !info.printed.includes(f));
    const pending = [...new Set(info.imported.flatMap((e) => (entities.get(e)?.pending ?? [])))];
    rows.push({ page, routes: [...new Set(info.imported.flatMap((e) => entities.get(e)?.routes ?? []))].sort(),
      available: info.available.length, printed: printed.length, headroom: headroom.length,
      headroomFields: headroom, pending });
  }
  return rows.sort((a, b) => b.headroom - a.headroom);
}

function report(rows) {
  let density = [];
  try { density = JSON.parse(fs.readFileSync(DENSITY, 'utf8')).pages ?? []; } catch { density = []; }
  const printedOf = (page) => { const r = density.find((d) => d.page === page); return r === undefined ? null : r.printed; };
  console.log('  page         routes  served fields  printed  headroom  PRINTED(density)  reading');
  const design = [], gateway = [];
  for (const r of rows) {
    const printed = printedOf(r.page);
    const low = printed !== null && printed < 3.58;   // the top of M1-109's "everything else" band
    const verdict = printed === null ? 'no density figure'
      : low && r.headroom > 0 ? 'DESIGN: low printed WITH headroom'
      : low && r.headroom === 0 ? 'GATEWAY: low printed, surface exhausted'
      : 'printed above the band';
    if (verdict.startsWith('DESIGN')) design.push(r.page);
    if (verdict.startsWith('GATEWAY')) gateway.push(r.page);
    console.log(`  ${r.page.padEnd(12)} ${String(r.routes.length).padStart(4)}  ${String(r.available).padStart(11)}  ${String(r.printed).padStart(7)}  ${String(r.headroom).padStart(8)}  ${String(printed === null ? '-' : printed).padStart(15)}  ${verdict}`);
  }
  console.log(`\n  THE TWO SETS: ${design.length} page(s) low in printed WITH headroom [${design.join(', ')}]`);
  console.log(`                ${gateway.length} page(s) low in printed with the surface EXHAUSTED [${gateway.join(', ')}]`);
  console.log(`\n  THE PENDING SURFACE THE CONSOLE IS WAITING ON, quoted from its own constants:`);
  const pending = [...new Set(rows.flatMap((r) => r.pending))].sort();
  for (const p of pending) console.log(`    ${p}  waited on by ${rows.filter((r) => r.pending.includes(p)).map((r) => r.page).join(', ')}`);
  console.log(`\n  HEADROOM, per page, in fields it holds and does not print:`);
  for (const r of rows.filter((x) => x.headroom > 0)) console.log(`    ${r.page.padEnd(12)} ${r.headroomFields.join(', ')}`);
}

// ------------------------------------------------------------------ selftest

function selftest() {
  let bad = 0;
  const check = (label, got, want) => { const ok = JSON.stringify(got) === JSON.stringify(want); if (!ok) bad += 1;
    console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${label}  (got ${JSON.stringify(got)}, wanted ${JSON.stringify(want)})`); };
  const tmp = fs.mkdtempSync('/tmp/surface-');
  // A synthetic tree: one entity serving three fields, one page printing one of them.
  fs.mkdirSync(path.join(tmp, 'entities/thing/api'), { recursive: true });
  fs.mkdirSync(path.join(tmp, 'entities/thing/model'), { recursive: true });
  fs.mkdirSync(path.join(tmp, 'pages/thing'), { recursive: true });
  fs.writeFileSync(path.join(tmp, 'entities/thing/api/index.ts'), "fetch('/api/thing')");
  fs.writeFileSync(path.join(tmp, 'entities/thing/model/types.ts'),
    "export interface ThingRow {\n  alpha: string;\n  beta: number;\n  gamma: string;\n}\nexport const PENDING_THING = 'V4-999';\n");
  fs.writeFileSync(path.join(tmp, 'pages/thing/index.tsx'), "import { useThing } from '@entities/thing';\nconst v = row.alpha;\n");
  const e = readEntities(path.join(tmp, 'entities'));
  check('routes are read from the api file', e.get('thing').routes, ['/api/thing']);
  check('row fields are read from the type', e.get('thing').fields, ['alpha', 'beta', 'gamma']);
  check('a pending row id is quoted from the constant', e.get('thing').pending, ['V4-999']);
  const p = readPages(path.join(tmp, 'pages'), e);
  const rows = census(p, e);
  check('the census sees the entity the page imports', rows[0].available, 3);
  check('printing one of three fields leaves two of headroom', rows[0].headroom, 2);
  check('the headroom names the fields, not the count', rows[0].headroomFields, ['beta', 'gamma']);
  check('the pending surface is carried to the page', rows[0].pending, ['V4-999']);
  // MUTATION: a page printing everything it holds has NO headroom -- the gateway case.
  fs.writeFileSync(path.join(tmp, 'pages/thing/index.tsx'), "import { useThing } from '@entities/thing';\nrow.alpha; row.beta; row.gamma;\n");
  check('a page printing every field has headroom zero', census(readPages(path.join(tmp, 'pages'), e), e)[0].headroom, 0);
  fs.rmSync(tmp, { recursive: true, force: true });
  console.log(bad === 0 ? '\nselftest: ok' : `\nselftest: ${bad} wrong`);
  process.exit(bad === 0 ? 0 : 1);
}

if (ARGS.includes('--selftest')) selftest();
const entities = readEntities();
const pages = readPages(PAGES, entities);
const rows = census(pages, entities);
report(rows);
const out = flagOf('json', null);
if (out !== null) { fs.writeFileSync(path.resolve(ROOT, out), JSON.stringify(rows, null, 1)); console.log(`\nwrote ${out}`); }
