#!/usr/bin/env node
// map — thirteen pages by four layers, two themes, and the comp's own row at the top.
//
// WHY. The look gate has carried one headline since the first night: "comp mid-tone 12.3%, worst
// capture projects.png at 2.1% — 17% of the comp — eleven of fourteen below half." That is a single
// MEAN, and a mean cannot tell "this page is empty" from "this page is full of the wrong
// substance". M1-35 proved it on the two pages a person uses: paper at or ABOVE the comp (42.2 and
// 50.4 against 29.5) while printed and ruled are a third of it. A page at 2.1% and a page at 50.4%
// paper need OPPOSITE work, and both were being read as the same number.
//
// WHAT IT IS. A classification, not a score. Per page: which layer is short and which is over.
// It reuses coverage.mjs — the instrument with a mutation-proven cut (M1-63) — through its JSON
// output, so there is one classifier in the tree and this file only arranges what it returns.
//
// THE KNOWN LIMITS ARE COLUMNS. A limit in a footnote is a limit nobody reads:
//   - the bay ground is L12 against a dark room of L13.4 (1.06:1, by M1-24's ruling) so it lands in
//     NO layer and is floor by every classifier this instrument has: a bay that grows adds nothing;
//   - RULES is page-dependent and fenced off on the pages M1-53 rewrote (projects, doctor read 1
//     against a builder's 633) — the RULED percentage is consistent and is the column to read;
//   - mcp has no fixtures directory, so it cannot be captured with content at all: it is a MISSING
//     ROW NAMED AS MISSING, not an absent one.
//
// Usage: node webui/.impeccable/review/coverage/map.mjs [--frame 3840x2160] [--out FILE]
import { execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import process from 'node:process';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '..', '..', '..', '..');
const COVERAGE = resolve(HERE, 'coverage.mjs');
const ARGS = process.argv.slice(2);
const flag = (n, d) => { const i = ARGS.indexOf(`--${n}`); return i === -1 ? d : ARGS[i + 1]; };

const FRAME = flag('frame', '3840x2160');
const OUT = resolve(ROOT, flag('out', 'webui/.impeccable/review/coverage/map.txt'));
const BASE = 'http://localhost:5173/';

/** The thirteen addresses, with the fixture each one needs and whether one exists at all. */
export const PAGES = [
  ['fleet', '#/fleet', 'live daemon'],
  ['turns', '#/turns', 'live daemon'],
  ['sessions', '#/sessions', 'live daemon'],
  ['teams', '#/teams?fixture=hero', 'fixture=hero'],
  ['projects', '#/projects', 'live daemon'],
  ['accounts', '#/accounts', 'live daemon'],
  ['usage', '#/usage', 'live daemon'],
  ['settings', '#/settings', 'live daemon'],
  ['models', '#/models', 'live daemon'],
  ['logs', '#/logs', 'live daemon'],
  ['compaction', '#/compaction', 'live daemon'],
  ['doctor', '#/doctor', 'live daemon'],
  ['mcp', null, 'NO FIXTURE DIRECTORY - cannot be captured with content'],
];

// RESUMABLE (M1-67): rows accumulate in a JSON sidecar and map.txt is re-rendered from it after
// every page, so a killed run leaves everything it finished. The first version wrote map.txt once
// at the end: a 26-capture artifact that must succeed atomically is one nobody re-runs, and this one
// was killed at the ten-minute budget having left nothing at all. `--pages a,b,c` runs a slice.
const SIDECAR = resolve(ROOT, flag('sidecar', 'webui/.impeccable/review/coverage/map-rows.json'));
const wanted = flag('pages', '') === '' ? null : new Set(flag('pages', '').split(','));

const COMP = { paper: 29.5, printed: 6.5, ruled: 5.8 };

/** The classification: which layer is short, which is over, said in words rather than a score. */
export function verdict(row) {
  if (row.missing === true) return 'MISSING ROW (no fixture, cannot be captured with content)';
  if (row.error !== undefined) return `UNCAPTURED (${row.error})`;
  const notes = [];
  const over = (v, target) => v > target * 1.15;
  const under = (v, target) => v < target * 0.6;
  if (over(row.paper, COMP.paper)) notes.push(`paper OVER (+${(row.paper - COMP.paper).toFixed(1)})`);
  else if (under(row.paper, COMP.paper)) notes.push(`paper short (${row.paper.toFixed(1)} of ${COMP.paper})`);
  if (under(row.printed, COMP.printed)) notes.push('printed short');
  if (under(row.ruled, COMP.ruled)) notes.push('rules short');
  if (notes.length === 0) notes.push('layers within reach of the comp');
  return notes.join('; ');
}

const rows = [];
try {
  // Dedup on load as well as on write: the sidecar written before the replace fix still carries a
  // page twice, and a table that doubles a row is a table nobody can count from.
  const seen = new Map();
  for (const r of JSON.parse(readFileSync(SIDECAR, 'utf8'))) seen.set(`${r.name}|${r.theme ?? '-'}`, r);
  rows.push(...seen.values());
} catch { /* first run */ }
for (const [name, hash, source] of PAGES) {
  if (wanted !== null && !wanted.has(name)) continue;
  if (hash === null) {
    rows.push({ name, source, missing: true });
    // Land it like any other page: skipping the write here is how mcp appeared in one render and
    // vanished from the next chunk's load.
    writeFileSync(SIDECAR, JSON.stringify(rows, null, 1));
    render(rows);
    console.log(`  ${name} done (named missing, not captured)`);
    continue;
  }
  for (const theme of ['dark', 'light']) {
    let parsed = [];
    try {
      const out = execFileSync('node', [COVERAGE, '--json', '--theme', theme, '--frame', FRAME, BASE + hash], {
        encoding: 'utf8', maxBuffer: 1 << 28, cwd: ROOT,
      });
      parsed = JSON.parse(out).filter((r) => !r.source.startsWith('comp'));
    } catch (error) {
      rows.push({ name, theme, source, error: String(error.message).split('\n')[0] });
      continue;
    }
    // REPLACE, do not append: a page re-run in a later chunk must not double itself. The sidecar is
    // what makes a killed run leave a usable table, and the first version of it also doubled fleet
    // (its rows from the 2-page chunk, appended again when a later chunk re-ran the page).
    for (const r of parsed) {
      const at = rows.findIndex((x) => x.name === name && x.theme === theme);
      if (at >= 0) rows.splice(at, 1);
      rows.push({ name, theme, source: `${theme} / ${source}`, frame: r.frame, ...r });
    }
  }
  // Land after EVERY page, so an interrupted chunk still leaves a usable table.
  writeFileSync(SIDECAR, JSON.stringify(rows, null, 1));
  render(rows);
  console.log(`  ${name} done (${rows.length} rows so far)`);
}

function render(all) {
const rows = all;
const frame = rows.find((r) => r.frame !== undefined)?.frame ?? FRAME;
const lines = [
  `the map — thirteen pages by four layers, both themes, at ${frame} (M1-67)`,
  '',
  'THE COMP IS A ROW IN THE DARK COLUMN AND HAS NO LIGHT COUNTERPART: team-board-a.png is a dark',
  'frame with no light twin, so a light coverage figure has NO COMP TARGET and is not graded against',
  'it. The dark column is the one with a target.',
  '',
  `  COMP team-board-a (dark, comp frame)   paper ${COMP.paper}   printed ${COMP.printed}   ruled ${COMP.ruled}`,
  '',
];
for (const theme of ['dark', 'light']) {
  lines.push(`  ${theme.toUpperCase()}`);
  lines.push('  page        source                    frame        paper  printed   ruled  rules  verdict');
  for (const row of rows.filter((r) => r.missing === true || r.theme === theme)) {
    if (row.missing === true && theme === 'light') continue;
    const f = (v) => (v === undefined ? '    -' : String(v.toFixed(1)).padStart(6));
    lines.push(`  ${row.name.padEnd(11)} ${String(row.source).slice(0, 24).padEnd(25)}`
      + `${String(row.frame ?? '-').padEnd(12)}${f(row.paper)}${f(row.printed)}${f(row.ruled)}`
      + `${String(row.ruleRows ?? '-').padStart(6)}  ${verdict(row)}`);
  }
  lines.push('');
}
lines.push('  LIMITS, AS COLUMNS RATHER THAN FOOTNOTES');
lines.push('  - bay ground: L12 against a dark room of L13.4 (1.06:1), so it is floor by every classifier');
lines.push('    here and lands in no layer. A bay that grows into its room adds nothing to this table.');
lines.push('  - RULES is page-dependent and fenced off where M1-53 rewrote the rack: projects and doctor');
lines.push('    read 1 against a builder\'s 633-683. Read RULED, which is consistent, not RULES.');
lines.push('  - mcp is a missing row named as missing: no fixtures directory, so no content to capture.');

writeFileSync(OUT, lines.join('\n') + '\n');
}

if (rows.length > 0) render(rows);
console.log(`map: ${rows.length} rows -> ${OUT}`);
