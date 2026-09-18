/**
 * M1-106: THE SIX BOOT CONDITIONS, COUNTED IN RULES RATHER THAN IN FILES.
 *
 * WHY THIS EXISTS. M1-100 established that six conditions the app is built around have never been
 * rendered in any capture this campaign has taken -- no management key, a failed fetch, loading and
 * not-yet-arrived, declared-empty, route-pending, and first-run-no-config -- and the reason is
 * structural rather than negligent: the harness seeds a key and data for ANYTHING to render, and
 * seeding them is exactly what skips all six. Only ONE of the six ever got a rule count, because
 * M1-80 measured it while chasing a different question: four rules are reachable only through the
 * unlock modal. THE OTHER FIVE HAVE FILE COUNTS -- 15, 8, 25, 10 -- AND A FILE COUNT IS NOT A RULE
 * COUNT. A file that mentions `Fault` is not a rule that only a failed fetch can paint, and quoting
 * the first as the second is the same defect as a denominator taken from the list being checked.
 *
 * WHAT IT MEASURES, and the denominator comes from the source: every `color:`-declaring rule in
 * every .css under webui/src (the campaign's own 187-rule denominator, re-derived rather than
 * quoted), attributed to a condition when its SELECTOR names a class that only that condition's
 * surface puts on the glass. A rule whose selector names none of them is counted under no condition
 * and the total is printed, so the unattributed remainder is visible rather than implied.
 *
 * WHAT IT CANNOT SEE, stated rather than implied. A rule is attributed by the CLASS in its selector,
 * so a rule that reaches one of these surfaces by element, by a state pseudo-class, or by inheriting
 * from an ancestor is not counted for it; and a class that two conditions share (`.myx-field-label`
 * is on the modal AND on ordinary forms) is reported under BOTH rather than split by a guess. The
 * counts are therefore an attribution over selectors, not a rendering census -- which is exactly why
 * the unkeyed capture leg now exists beside this file: the leg shows the surface, this counts what
 * is written for it.
 *
 * Usage: bun webui/.impeccable/review/axes/axes.mjs [out.json]
 *        bun webui/.impeccable/review/axes/axes.mjs --selftest
 */
import fs from 'node:fs';
import path from 'node:path';

const R = '/home/user/Documents/dev/projects/atlas/repo/.claude/worktrees/v0.4.0';
const SRC = path.join(R, 'webui/src');

/**
 * THE CONDITIONS AND THE CLASSES THAT ONLY THEY PUT ON THE GLASS, each class read out of the
 * primitive that renders it rather than remembered: `Fault` is controls/fault.tsx and paints
 * myx-fault/-field/-message; `Blank` is controls/blank.tsx and paints myx-blank/-strip; `Empty` is
 * ui/empty.tsx and paints myx-empt/-text/-source; the unlock surface is features/unlock-mgmt and
 * paints myx-modal-scrim/-modal/-title. The phrases and the page counts M1-100 recorded are carried
 * as data so the two denominators can be compared side by side.
 */
export const CONDITIONS = [
  { id: 'no-key', name: 'no management key', source: 'features/unlock-mgmt/index.tsx',
    classes: ['myx-modal-scrim', 'myx-modal', 'myx-modal-title', 'myx-field-label', 'myx-fault-message'],
    files: 4, note: 'M1-80 measured four rules here; that count is the one this row generalises' },
  { id: 'fault', name: 'a failed fetch', source: 'shared/controls/fault.tsx',
    classes: ['myx-fault', 'myx-fault-field', 'myx-fault-message'], files: 15 },
  { id: 'blank', name: 'loading, not yet arrived', source: 'shared/controls/blank.tsx',
    classes: ['myx-blank', 'myx-blank-strip'], files: 8 },
  { id: 'empty', name: 'declared-empty', source: 'shared/ui/empty.tsx',
    classes: ['myx-empt', 'myx-empt-text', 'myx-empt-source'], files: 25, phrases: 30 },
  { id: 'route-pending', name: 'route pending', source: 'the Empty primitive, source named per page',
    classes: ['myx-empt', 'myx-empt-text', 'myx-empt-source'], pages: 10 },
  { id: 'first-run', name: 'first run, no config', source: 'not yet enumerated',
    classes: [], files: null,
    note: 'NO CLASSES ARE KNOWN FOR THIS CONDITION, so it is reported at zero rather than guessed at. M1-100 named it as one of the six and nothing here can attribute a rule to it; the honest disposition is UNMEASURED and the row says so.' },
];

/** Every .css under webui/src, and every `color:`-declaring rule in it. */
export function colourRules(dir = SRC) {
  const out = [];
  const walk = (d) => {
    let entries; try { entries = fs.readdirSync(d, { withFileTypes: true }); } catch { return; }
    for (const e of entries) {
      const p = path.join(d, e.name);
      if (e.isDirectory()) walk(p);
      else if (e.name.endsWith('.css')) out.push(p);
    }
  };
  walk(dir);
  const rules = [];
  for (const file of out.sort()) {
    const text = fs.readFileSync(file, 'utf8').replace(/\/\*[\s\S]*?\*\//g, '');
    for (const m of text.matchAll(/([^{}]+)\{([^{}]*)\}/g)) {
      const sel = m[1].trim();
      if (sel.startsWith('@') || sel.startsWith(':root')) continue;
      if (!/(?:^|[^-\w])color\s*:/.test(m[2])) continue;
      rules.push({ file: path.relative(R, file), sel });
    }
  }
  return rules;
}

/** A rule belongs to a condition when its SELECTOR names one of that condition's classes. */
export function rulesFor(condition, rules) {
  // THE BOUNDARY IS `\w` AND NOT `[\w-]`, and the selftest is why. Excluding the hyphen as well
  // refused `.myx-fault-message` for the class `myx-fault` — it reported ZERO rules for the condition
  // whose whole family is hyphenated, while the unit test beside it still had to reject
  // `.myx-faulted`. `\w` is letters, digits and underscore: a hyphen is outside it, so dropping the
  // hyphen from the lookahead keeps `.myx-fault-message` and still stops `.myx-faulted`. A boundary
  // rule that refuses the family it names is worse than no boundary at all, because it reads clean.
  const hit = (sel) => condition.classes.some((c) => new RegExp(`\\.${c}(?!\\w)`).test(sel));
  return rules.filter((r) => hit(r.sel));
}

const SELFTEST = [
  { name: 'a rule whose selector names the condition class is attributed',
    cond: { classes: ['myx-fault'] }, rules: [{ file: 'a.css', sel: '.myx-fault-message' }], want: 1 },
  { name: 'a rule that merely CONTAINS the string is not: the boundary is enforced',
    cond: { classes: ['myx-fault'] }, rules: [{ file: 'a.css', sel: '.myx-faulted' }], want: 0 },
  { name: 'a condition with no classes attributes nothing, however many rules exist',
    cond: { classes: [] }, rules: [{ file: 'a.css', sel: '.anything' }], want: 0 },
  { name: 'a compound selector still attributes',
    cond: { classes: ['myx-modal'] }, rules: [{ file: 'a.css', sel: '.myx-modal .myx-field-label' }], want: 1 },
];

if (process.argv.includes('--selftest')) {
  let bad = 0;
  for (const t of SELFTEST) {
    const got = rulesFor(t.cond, t.rules).length;
    const ok = got === t.want;
    if (!ok) bad += 1;
    console.log(`  ${ok ? 'ok  ' : 'FAIL'} ${t.name} (want ${t.want}, got ${got})`);
  }
  console.log(bad === 0 ? 'selftest: ok' : `selftest: ${bad} case(s) FAILED`);
  process.exit(bad === 0 ? 0 : 1);
}

const rules = colourRules();
const rows = CONDITIONS.map((c) => ({ ...c, rules: rulesFor(c, rules).length,
  selectors: [...new Set(rulesFor(c, rules).map((r) => r.sel))] }));
const attributed = rows.flatMap((r) => rulesFor(r, rules));
const unattributed = rules.length - new Set(attributed.map((r) => `${r.file}:${r.sel}`)).size;

const out = process.argv[2] ?? path.join(import.meta.dirname, 'axes.json');
fs.writeFileSync(out, JSON.stringify({ denominator: rules.length, conditions: rows, unattributed }, null, 1));

console.log(`DENOMINATOR: ${rules.length} colour-declaring rules across webui/src (re-derived, not quoted)`);
console.log(`\n  ${'condition'.padEnd(16)} ${'files/ pages'.padStart(11)} ${'RULES'.padStart(6)}   what only this condition can paint`);
for (const r of rows) {
  const files = r.files === undefined || r.files === null ? (r.pages === undefined ? '-' : `${r.pages} pages`) : String(r.files);
  console.log(`  ${r.id.padEnd(16)} ${files.padStart(11)} ${String(r.rules).padStart(6)}   ${r.selectors.slice(0, 3).join(', ')}${r.selectors.length > 3 ? ` …(${r.selectors.length})` : ''}`);
}
console.log(`\n  rules attributed to no condition: ${unattributed} of ${rules.length}`);
console.log(`  conditions with NO classes known: ${rows.filter((r) => r.classes.length === 0).map((r) => r.id).join(', ') || 'none'}`);
console.log(`wrote ${out}`);

// A count over an empty denominator is not a count (law 23).
if (rules.length === 0) { console.error('axes: BLIND - no colour rule was found under webui/src, so nothing was counted'); process.exit(1); }
