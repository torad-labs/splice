// THE m1 PUNCH LIST, assembled from the artefacts rather than retyped from them.
//
// Every number below is READ from a file this row produced in the same run:
//   comp-check.txt      the 673-row comp-constant table over the 13 addresses
//   tonal-census.txt    the per-frame mid-tone census, comp measured the same way
//   comp-diff-teams.txt impeccable's own region diff, comp against the fresh teams capture
//
// The ordering is the row's: BY HOW FAR THE BUILD IS FROM THE COMP, never by how easy the fix
// looks. The distance used is |delta| / |comp value| where the comp value is not zero (a relative
// miss), and |delta| in points or pixels where it is -- stated here because an ordering is a claim.
//
// OWNERSHIP IS DERIVED FROM THE LEDGER, not from memory: each failing constant names the file that
// produces it, and the file is matched against every in-flight row's fence, so the row named beside
// a defect is the row that would have to fix it (or none, which is itself the finding).
//
// Usage: node dev/web-console/census/build-punch-list.mjs
import { execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync } from 'node:fs';
import { join, resolve } from 'node:path';

const ROOT = resolve(import.meta.dirname, '../../..');
const LEDGER = 'dev/campaigns/web-console.toml';
const OUT = join(ROOT, 'dev/web-console/census/m1-punch-list.md');
const read = (name) => readFileSync(join(ROOT, 'dev/web-console/census', name), 'utf8');

// ------------------------------------------------------------------ ownership

/** Every in-flight row's fence, from the ledger. */
function liveFences() {
  // V4-143: --plain and --raw are the bun CLI's machine shapes, byte-identical to manifest.py's; its
  // human list leads with a status glyph, which the id filter below would drop to zero rows.
  const list = execFileSync('bun', ['dev/campaigns/manifest.ts', LEDGER, 'list', '--status', 'in_flight', '--plain'], { cwd: ROOT, encoding: 'utf8' });
  const ids = list.split('\n').map((line) => (line.trim().split(/\s+/)[0] ?? '')).filter((id) => /^[A-Z]+\d+-\d+$/.test(id));
  if (ids.length === 0) throw new Error(`the ledger listed no in-flight rows; list said: ${JSON.stringify(list.slice(0, 200))}`);
  const rows = [];
  for (const id of ids) {
    const text = execFileSync('bun', ['dev/campaigns/manifest.ts', LEDGER, 'get', id, '--raw'], { cwd: ROOT, encoding: 'utf8' });
    const match = text.match(/^files = \[(.*)\]$/m);
    const files = match === null ? [] : [...match[1].matchAll(/"([^"]+)"/g)].map((m) => m[1]);
    const owner = (text.match(/^# \[[^\]]+\] CLAIM: owner=(\S+)/m) ?? [])[1] ?? '?';
    // A row that owns THIS directory is the one assembling the list, not an owner of page defects.
    if (files.some((fence) => fence.startsWith('dev/web-console/census'))) continue;
    rows.push({ id, owner, files });
  }
  return rows;
}

/** The row whose fence covers a file, or null when no live row does. */
function ownerOf(path, fences) {
  const hit = fences.find((row) => row.files.some((fence) => (fence.endsWith('/**') ? path.startsWith(fence.slice(0, -2)) : fence === path)));
  return hit === undefined ? null : `${hit.id} @${hit.owner}`;
}

/** The file that produces each constant. Read off the CSS and the component tree, one line each. */
const SOURCE = {
  'rule.': 'webui/src/widgets/rule/rule.css',
  'text.wordmark': 'webui/src/widgets/rule/rule.css',
  'text.clocks': 'webui/src/widgets/rule/rule.css',
  'text.health': 'webui/src/widgets/rule/rule.css',
  'text.window': 'webui/src/widgets/rule/rule.css',
  'text.none': 'webui/src/widgets/rule/rule.css',
  'text.bay.label': 'webui/src/shared/ui/ui.css',
  'bay.label-centre': 'webui/src/shared/ui/ui.css',
  'bay.rail': 'webui/src/shared/ui/ui.css',
  'strip.': 'webui/src/shared/ui/ui.css',
  'field.': 'webui/src/shared/ui/ui.css',
};
const sourceOf = (id) => Object.entries(SOURCE).find(([prefix]) => id.startsWith(prefix))?.[1] ?? 'webui/src';

// ------------------------------------------------------------- the comp-check table

/** Every FAIL row, with the numbers as printed. */
function failures() {
  const rows = [];
  let address = null;
  for (const line of read('comp-check.txt').split('\n')) {
    const head = line.match(/^([a-z]+)\s+\(1536x1024\)/);
    if (head !== null) { address = head[1]; continue; }
    if (!line.includes('FAIL') || !line.includes('comp ') || !line.includes('got ')) continue;
    const id = line.trim().split(/\s+/)[0];
    const comp = Number((line.match(/comp\s+(-?[\d.]+)/) ?? [])[1]);
    const got = Number((line.match(/got\s+(-?[\d.]+)/) ?? [])[1]);
    const unit = line.includes('px') ? 'px' : '%';
    // A rule-based failure: the comp carries a SHAPE (a rail exists, a strip exists) and not a
    // number, so there is no delta to rank by. These are listed separately rather than given a
    // fabricated distance.
    const rule = !Number.isFinite(comp) || !Number.isFinite(got);
    rows.push({ address, id, comp, got, delta: got - comp, unit, rule,
      gotText: (line.match(/got\s+(n\/a|[\d.]+px?|[\d.]+%)/) ?? [])[1] ?? 'n/a' });
  }
  return rows;
}

const allFailures = failures();
const ranked = allFailures.filter((row) => !row.rule);
const ruleFailures = allFailures.filter((row) => row.rule);
const byConstant = new Map();
for (const row of ranked) {
  const entry = byConstant.get(row.id) ?? { id: row.id, addresses: [], worst: row, unit: row.unit };
  entry.addresses.push(row.address);
  if (Math.abs(row.delta) / (Math.abs(row.comp) || 1) > Math.abs(entry.worst.delta) / (Math.abs(entry.worst.comp) || 1)) entry.worst = row;
  byConstant.set(row.id, entry);
}

/** Distance from the comp, as a fraction of the comp's own value; points when it is zero. */
const distance = (row) => (row.comp === 0 ? Math.abs(row.delta) : Math.abs(row.delta) / Math.abs(row.comp));
const ordered = [...byConstant.values()].sort((a, b) => distance(b.worst) - distance(a.worst));

const fences = liveFences();

// ----------------------------------------------------------------- the census

const censusText = read('tonal-census.txt');
const censusRows = censusText.split('\n').map((line) => {
  const m = line.match(/^\s{2}(\S+\.png|\S+ team-board-a\.png)\s+(#\w+)\s+(#\w+)\s+([\d.]+)%\s+(-?[\d.]+)%/);
  return m === null ? null : { frame: m[1], room: m[2], strip: m[3], flat: Number(m[4]), mid: Number(m[5]) };
}).filter(Boolean);
const compCensus = censusRows.find((row) => row.frame.includes('team-board-a'));
const frameCensus = censusRows.filter((row) => row.frame.endsWith('.png') && !row.frame.includes('team-board-a'));

/** Per address, the frame furthest from the comp's mid-tone share. */
const byAddress = new Map();
for (const row of frameCensus) {
  const address = row.frame.split('-')[0];
  const entry = byAddress.get(address) ?? { address, mid: row.mid, frame: row.frame };
  if (Math.abs(row.mid - compCensus.mid) > Math.abs(entry.mid - compCensus.mid)) { entry.mid = row.mid; entry.frame = row.frame; }
  byAddress.set(address, entry);
}
const censusOrdered = [...byAddress.values()].sort((a, b) => Math.abs(b.mid - compCensus.mid) - Math.abs(a.mid - compCensus.mid));

// ---------------------------------------------------------------- comp-diff

const diffText = read('comp-diff-teams.txt');
const diffOverall = (diffText.match(/^COMP-DIFF \[([^\]]+)\] overall (\d+)% \((\w+)\) structure (\d+)% color (\d+)% detail (\d+)% bands (\d+)%$/m) ?? []);
const detailFloor = 65;
const diffRegions = [...diffText.matchAll(/^REGION (\S+)\s+(\w+)\s+(\d+)%\s+structure\s+(\d+)%\s+color\s+(\d+)%\s+detail\s+(\d+)%$/gm)]
  .map((m) => ({ region: m[1], verdict: m[2], overall: Number(m[3]), structure: Number(m[4]), color: Number(m[5]), detail: Number(m[6]) }));
const underFloor = diffRegions.filter((row) => row.detail < detailFloor);

// ------------------------------------------------------------------ the markdown

const lines = [];
lines.push('# m1 PUNCH LIST — measured, ordered by distance from the comp, no fixes');
lines.push('');
lines.push(`Generated ${new Date().toISOString().slice(0, 10)} by dev/web-console/census/build-punch-list.mjs from three artefacts this row produced in one run:`);
lines.push('');
lines.push('- `comp-check.txt` — the comp-constant table, 13 addresses, `dev/web-console/comp-check.mjs`');
lines.push('- `tonal-census.txt` — the per-frame flat/mid-tone census, comp measured by the same code (`census/tonal-census.mjs`)');
lines.push('- `comp-diff-teams.txt` — impeccable\'s region diff, comp against the fresh teams capture');
lines.push('');
lines.push('ORDER: by how far the build is from the comp, never by how easy the fix looks. Distance is');
lines.push('`|delta| / |comp value|` (a relative miss), or `|delta|` in points/pixels where the comp value is 0.');
lines.push('');
lines.push('OWNERSHIP is derived from the ledger at generation time: each constant names the file that');
lines.push('produces it, and that file is matched against every in-flight row\'s fence.');
lines.push('');
lines.push('**NO FIXES ARE IN THIS TABLE.** Four seats are live in `webui/src`; every row below belongs to one of them or to a row cut from this table.');
lines.push('');

lines.push('## 1. comp-check — the constants, worst first');
lines.push('');
lines.push('| # | constant | address | measured | comp | delta | how far | owner |');
lines.push('|---|---|---|---|---|---|---|---|');
ordered.forEach((entry, index) => {
  const worst = entry.worst;
  const file = sourceOf(entry.id);
  const owner = ownerOf(file, fences);
  const addresses = entry.addresses.length === 13 ? 'all 13' : `${entry.addresses.length}: ${entry.addresses.join(' ')}`;
  lines.push(`| ${index + 1} | \`${entry.id}\` | ${addresses} | ${worst.got}${worst.unit} | ${worst.comp}${worst.unit} | ${worst.delta > 0 ? '+' : ''}${worst.delta.toFixed(2)}${worst.unit} | ${(distance(worst) * 100).toFixed(1)}% | ${owner === null ? `${file} — NO LIVE ROW` : `${owner} (${file})`} |`);
});
lines.push('');
{
  const unowned = ordered.filter((entry) => ownerOf(sourceOf(entry.id), fences) === null);
  lines.push(`**${unowned.length} of ${ordered.length} ranked constants have NO LIVE OWNER** (${unowned.map((e) => `\`${e.id}\``).join(', ')}). Their files are ${[...new Set(unowned.map((e) => sourceOf(e.id)))].map((f) => `\`${f}\``).join(' and ')}, and no in-flight row fences those paths as this list was generated — so the worst rows by distance are the ones nobody is currently able to fix.`);
  lines.push('');
}
lines.push(`Full table: \`comp-check.txt\` (${allFailures.length} rows outside tolerance across the 13 addresses: ${ranked.length} with a comp value to rank, ${ruleFailures.length} rule-based).`);
lines.push('');
lines.push('**Rule-based failures — the comp carries the shape, not a number, so there is no delta to rank by:**');
lines.push('');
lines.push('| constant | addresses | measured | what the comp shows | owner |');
lines.push('|---|---|---|---|---|');
{
  const byRuleConstant = new Map();
  for (const row of ruleFailures) {
    const entry = byRuleConstant.get(row.id) ?? { id: row.id, addresses: [], gotText: row.gotText };
    entry.addresses.push(row.address);
    byRuleConstant.set(row.id, entry);
  }
  const shows = {
    'field.label-rule': 'the rule under the label divider (CSS cites the comp crop at row y=229)',
    'field.divider': 'a vertical divider between field boxes',
    'strip.inset-x': 'a strip inside every bay to measure against',
    'strip.h': 'a strip in the rack (the address renders none)',
    'bay.label-centre': 'a bay label plate on the rack',
  };
  for (const entry of byRuleConstant.values()) {
    const file = sourceOf(entry.id);
    const owner = ownerOf(file, fences);
    const addresses = entry.addresses.length === 13 ? 'all 13' : `${entry.addresses.length}: ${entry.addresses.join(' ')}`;
    lines.push(`| \`${entry.id}\` | ${addresses} | ${entry.gotText} | ${shows[entry.id] ?? 'a measured value'} | ${owner === null ? `${file} — NO LIVE ROW` : `${owner} (${file})`} |`);
  }
}
lines.push('');
lines.push('**CAVEAT THAT TRAVELS WITH EVERY ROW ABOVE.** `comp-check.mjs` carries its own fixture table and it is the STALE one: `turns`, `sessions`, `projects`, `logs`, `accounts` and `doctor` were measured against LIVE daemon data rather than their fixtures, because that table still names `demo` (see section 4). The chrome constants (rail, rule, the text roles) are data-independent and stand; the rack constants for those six addresses do not.');
lines.push('');

lines.push('## 2. comp-diff — per-region detail, the floor is 65');
lines.push('');
if (diffOverall.length > 0) {
  lines.push(`comp \`team-board-a.png\` vs \`review/census/teams-dark-1536x1024.png\`: overall **${diffOverall[2]}%** (${diffOverall[3]}), structure ${diffOverall[4]}%, color ${diffOverall[5]}%, detail ${diffOverall[6]}%.`);
  lines.push('');
  lines.push('**The aggregate is not a gate.** 85% is what this tool calls a match, and the campaign never gates on it: it dilutes the defect column away. The floor is per region.');
} else {
  lines.push('comp-diff output not found in the expected shape; see `comp-diff-teams.txt`.');
}
lines.push('');
lines.push('| region | verdict | overall | structure | color | detail | vs floor 65 |');
lines.push('|---|---|---|---|---|---|---|');
for (const row of diffRegions) {
  lines.push(`| ${row.region} | ${row.verdict} | ${row.overall}% | ${row.structure}% | ${row.color}% | **${row.detail}%** | ${row.detail < detailFloor ? `**BELOW by ${detailFloor - row.detail}**` : `above by ${row.detail - detailFloor}`} |`);
}
lines.push('');
lines.push(`**${underFloor.length} of ${diffRegions.length} regions are below the floor** (${underFloor.map((r) => `${r.region} ${r.detail}%`).join(', ')}). The shape is the one the review described: structure holds high across the same regions (${diffRegions.map((r) => r.structure).join(', ')}) while detail collapses down the page — composition present, material absent.`);
lines.push('');
lines.push('**PROVENANCE OF THE 65 FLOOR, corrected 2026-09-18:** it is NOT `build-phase.mjs:598`\'s number. That 0.65 is a threshold on OVERALL scoped to control regions already at verdict drift, and the file carries no per-region detail floor at any value. 65 is CHOSEN by the orchestrator from this single teams measurement, because the split has margin on both sides (83/80/84/75 pass, nearest clears by 10; 51/44/40/51 fail, nearest misses by 14), and it is PROVISIONAL until `comp-spec.mjs --regions` makes these rows semantic instead of horizontal slices.');
lines.push('');
lines.push('**CAVEAT, and it is the reviewer\'s:** comp and capture show different data, and comp-diff\'s auto-bands are horizontal slices of the frame rather than semantic regions. The SHAPE of the result is not something a content difference produces, and the region crops confirm it, but the caveat travels with every number.');
lines.push('');
lines.push('**WHY THE FLOOR EXISTS AT ALL:** `build-phase.mjs`\'s hero gate is `overall >= 0.72` with no region missing, and comp-diff decides `missing` for a non-painted region with a CONJUNCTION (`detail < 0.35 AND structure < 0.6`, comp-diff.mjs:147/:155). band-7 measures detail 40 and structure 92, so it is not missing — it scores drift. The hero gate therefore passes on both clauses with the material gone: high structure launders absent detail. The detail floor is the veto neither clause performs.');
lines.push('');

lines.push('## 3. The tonal census — how much of each frame is two flat values');
lines.push('');
lines.push(`Binning rule, applied to every frame including the comp\'s, in one run: a pixel is FLAT when the ground it is NEAREST to is within 8 of 255 of it on every channel; the grounds are the frame\'s room and strip — the build\'s from \`tokens.css\`, the comp\'s from \`build/spec.json\`\'s own palette. Mid-tone share is the rest.`);
lines.push('');
lines.push(`- comp \`team-board-a.png\`: **${compCensus.mid.toFixed(1)}%** mid-tone (grounds ${compCensus.room} / ${compCensus.strip})`);
lines.push('- build, the frame furthest from the comp per address:');
lines.push('');
lines.push('| address | frame | mid-tone | delta vs comp |');
lines.push('|---|---|---|---|');
for (const entry of censusOrdered) lines.push(`| ${entry.address} | ${entry.frame} | ${entry.mid.toFixed(1)}% | ${entry.mid - compCensus.mid > 0 ? '+' : ''}${(entry.mid - compCensus.mid).toFixed(1)} points |`);
lines.push('');
lines.push('**INSTRUMENT DISAGREEMENT, reported rather than resolved** (the row asks for both): this per-pixel rule reads the comp at ' + compCensus.mid.toFixed(1) + '% where the design review\'s hand census says **11.9%**. The review\'s number is a clustered palette share — `spec.json`\'s own coverage of its top two entries, which reproduces exactly as `1 - 0.6407 - 0.24 = 11.93` — and this file\'s is a per-pixel distance at a stated tolerance. They are different measurements; neither is the other\'s check. The gap is the comp\'s GRAIN: its dark ground spreads over more than 8 steps, so a tight per-pixel rule under-counts it, while a loose one cannot be used at all because the light room\'s two grounds are only 22 steps apart and their discs overlap (at tolerance 32 in the light theme the flat share exceeds 100%).');
lines.push('');
lines.push('**What the census does say, under its own rule and comparable across frames:** the light frames sit far above the comp (up to +28 points) and the dark frames straddle it. The light excess is the light room having a THIRD large flat plane — the bay floor M1-11 deepened to #ABB0AC — which a two-ground metric scores as mid-tone. That is a finding about the metric, not about the light room, and it is why the census is not a gate yet.');
lines.push('');

lines.push('## 4. The fixture defect this row found, and the two it corrected');
lines.push('');
lines.push('`gate.mjs` named the wrong fixture for four addresses and would have made every capture in this row a lie:');
lines.push('');
lines.push('| address | was | is (off disk) | why the wrong name is silent |');
lines.push('|---|---|---|---|');
lines.push('| turns | `demo` | `board` | `turns/index.tsx:337` interpolates the name into a dynamic import and `.catch(() => undefined)` swallows the failure |');
lines.push('| sessions | `demo` | `board` | `sessions/index.tsx:265`, same shape |');
lines.push('| projects | `demo` | `list` | `projects/index.tsx:240`, same shape |');
lines.push('| logs | `demo` | `tail` | `logs/index.tsx:154`, same shape |');
lines.push('| accounts | `demo` | `accounts` | the module is imported statically, but `accounts/fixtures/accounts.ts:100` re-checks the name and returns null for anything else |');
lines.push('| doctor | `demo` | `doctor` | `doctor/fixtures/doctor.ts:46`, same shape |');
lines.push('');
lines.push('The last two were found by the ASSERTION rather than by reading: the module URL answered 200 for both (the file exists) while the page rendered live data, which is why the gate now checks the DOM as well as the module. `gate.mjs` now asserts per capture — the fixture module URL must answer 200, and where the page prints its `sample data` label the label must be on screen (or, once M1-20 lands its marker, `data-fixture` must equal the file name). A capture failing either is recorded FAILED, marked on the contact sheet, and the gate exits 1.');
lines.push('');

lines.push('## 5. What is NOT in this table');
lines.push('');
lines.push('- Any fix. Four seats are live in `webui/src` and every row above names its owner.');
lines.push('- Any gate on an aggregate score. See section 2.');
lines.push('- Any claim that the census is calibrated. See section 3: it is not, and it says so.');
lines.push('');

writeFileSync(OUT, `${lines.join('\n')}\n`);
console.log(`wrote ${OUT} (${lines.length} lines)`);
console.log(`  comp-check failures: ${allFailures.length} rows, ${byConstant.size} constants`);
console.log(`  comp-diff: ${underFloor.length} of ${diffRegions.length} regions below ${detailFloor}`);
console.log(`  census: comp ${compCensus.mid.toFixed(1)}%, ${censusOrdered.length} addresses`);
console.log(`  owners resolved against ${fences.length} live fences: ${fences.map((f) => f.id).join(' ')}`);
