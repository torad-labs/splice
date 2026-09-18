#!/usr/bin/env node
// look-gate — the mechanical half of a design review, as a script an agent runs before
// calling a look-bearing row done.
//
// WHY THIS EXISTS. The m1 design review found twenty defects by hand. Most of them are
// arithmetic: a field border that lands at a different x on each strip, a spacing scale used
// only at its floor, a type ladder with no step in it. Arithmetic belongs in a gate, so the
// operator's eye is spent on the things no gate can see.
//
// WHAT IT IS NOT. This does not replace `impeccable detect <url>`, which already carries 60
// rendered-page rules (flat-type-hierarchy, cramped-padding, text-occlusion, low-contrast,
// clipped-overflow-container, design-system-*). Run that FIRST, against the running console,
// not against a source directory. This file is only the delta: the checks that are specific to
// the strip bay, plus the two the detector structurally cannot see.
//
// LAYERS, after the 2026 practitioner consensus (see look-gate.md):
//   1 lint + token validation   <- this file's static checks, and `impeccable detect`
//   2 diff against a baseline   <- this file's comp checks; the comp is the baseline
//   3 rubric judge              <- not automated here
//   4 named human               <- not automatable, and the point of the other three
//
// USAGE
//   node look-gate.mjs [--captures DIR] [--src DIR] [--tokens FILE] [--comp FILE] [--json]
//   node look-gate.mjs --selftest      # mutation proof: every check must fail on a synthetic violation
//
// EXIT 0 = every blocking check passed. EXIT 1 = a blocking check failed.
// Blocking is deliberately small (the research is unanimous that a big blocking set becomes
// noise and gets ignored). Everything else warns.

import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';

const ARGS = process.argv.slice(2);
const flag = (name, dflt) => {
  const i = ARGS.indexOf(`--${name}`);
  return i === -1 ? dflt : ARGS[i + 1];
};
const has = (name) => ARGS.includes(`--${name}`);

// The repo root, derived from THIS file's home rather than from the caller's cwd (M1-16: the gate
// moved here from webui/.impeccable/review/gate/, which is gitignored). Every other default below
// is already relative to the root, so a row can run `node dev/web-console/look-gate.mjs` from the
// worktree root or from anywhere else and read the same tree.
const HERE     = path.dirname(fileURLToPath(import.meta.url));
const ROOT     = flag('root', path.resolve(HERE, '..', '..'));
const CAPTURES = flag('captures', 'webui/.impeccable/review/sections');
const SRC      = flag('src', 'webui/src');
const TOKENS   = flag('tokens', 'webui/src/shared/tokens.css');
const COMP     = flag('comp', 'webui/.impeccable/mocks/team-board-a.png');

const findings = [];
const record = (id, blocking, ok, detail) => {
  findings.push({ id, blocking, ok, detail });
  return ok;
};

// ---------------------------------------------------------------- static checks (no browser)

/** The root every relative read resolves against. Held in an object so the selftest can point
 *  the checks at a temp tree without any of them knowing it happened. */
const ROOTREF = { root: ROOT };
const readIf = (p) => { try { return fs.readFileSync(path.join(ROOTREF.root, p), 'utf8'); } catch { return null; } };
const cssFiles = (dir) => {
  const out = [];
  const walk = (d) => {
    let ents; try { ents = fs.readdirSync(path.join(ROOTREF.root, d), { withFileTypes: true }); } catch { return; }
    for (const e of ents) {
      const rel = path.join(d, e.name);
      if (e.isDirectory()) walk(rel);
      else if (e.name.endsWith('.css')) out.push(rel);
    }
  };
  walk(dir);
  return out;
};

/** A ladder with no step is a ramp: hierarchy cannot be built out of rungs nobody can tell apart.
 *  The bar is the detector's own: a dominant/body pair under 1.25x is "flat". */
function checkLadderSteps(tokensText) {
  const rungs = [];
  for (let n = 1; n <= 9; n++) {
    const m = tokensText && tokensText.match(new RegExp(`--text-${n}\\s*:\\s*(\\d+(?:\\.\\d+)?)px`));
    if (m) rungs.push({ n, px: parseFloat(m[1]) });
  }
  if (rungs.length < 3) return record('ladder-steps', true, true, 'skipped: fewer than three px rungs declared');
  const steps = rungs.slice(1).map((r, i) => +(r.px / rungs[i].px).toFixed(3));
  const strong = steps.filter((s) => s >= 1.25).length;
  const detail = `rungs ${rungs.map((r) => r.px).join('/')} · steps ${steps.join(', ')} · ${strong} of ${steps.length} reach 1.25x`;
  return record('ladder-steps', true, strong >= 1, detail);
}

/** A ladder used only at its floor has no ladder. Counts every font-size declaration and fails
 *  when the two smallest rungs carry more than 60% of them. */
function checkTypeDistribution(files) {
  const counts = new Map();
  for (const f of files) {
    const t = readIf(f) || '';
    for (const m of t.matchAll(/font-size:\s*var\(--text-(\d+)\)/g)) {
      const n = +m[1];
      counts.set(n, (counts.get(n) || 0) + 1);
    }
  }
  const total = [...counts.values()].reduce((a, b) => a + b, 0);
  if (total < 20) return record('type-distribution', false, true, 'skipped: fewer than 20 declarations');
  const rungs = [...counts.keys()].sort((a, b) => a - b);
  const bottomTwo = (counts.get(rungs[0]) || 0) + (counts.get(rungs[1]) || 0);
  const pct = bottomTwo / total;
  const detail = `${bottomTwo}/${total} (${(pct * 100).toFixed(0)}%) of font-size declarations are on the two smallest rungs`
    + ` · ${rungs.map((n) => `--text-${n}:${counts.get(n)}`).join(' ')}`;
  return record('type-distribution', false, pct <= 0.60, detail);
}

/** Tight groups AND generous separation. A scale used only below 8px has a minimum, not a rhythm. */
function checkSpacingDistribution(files) {
  const counts = new Map();
  for (const f of files) {
    const t = readIf(f) || '';
    for (const m of t.matchAll(/var\(--space-(\d+)\)/g)) {
      const n = +m[1];
      counts.set(n, (counts.get(n) || 0) + 1);
    }
  }
  const total = [...counts.values()].reduce((a, b) => a + b, 0);
  if (total < 40) return record('spacing-distribution', false, true, 'skipped: fewer than 40 uses');
  // --space-1..3 are 2/4/8px in this world; anything at or below rung 3 is "tight".
  let tight = 0;
  for (const [n, c] of counts) if (n >= 1 && n <= 3) tight += c;
  const pct = tight / total;
  const detail = `${tight}/${total} (${(pct * 100).toFixed(0)}%) of spacing uses are at rungs 1-3 (<=8px)`
    + ` · ${[...counts.keys()].sort((a, b) => a - b).map((n) => `--space-${n}:${counts.get(n)}`).join(' ')}`;
  return record('spacing-distribution', false, pct <= 0.55, detail);
}

/** THE LAUNDERING WALL. A scale transform changes rendered glyph size and leaves computed
 *  font-size untouched, so every type check in every tool passes while the type is deformed.
 *  This is the one hole the rendered-page detector structurally cannot see. */
function checkNoTypeTransform(files) {
  const hits = [];
  for (const f of files) {
    const t = readIf(f);
    if (!t) continue;
    // split into rule bodies; flag any body that both sets a font-size and scales
    for (const m of t.matchAll(/\{([^{}]*)\}/g)) {
      const body = m[1];
      if (!/transform:\s*[^;]*scale[XY]?\(/.test(body)) continue;
      const scale = body.match(/transform:\s*([^;]*scale[^;]*)/);
      const sets = /font-size\s*:/.test(body);
      // a scale on a rule that does not set font-size is still suspect when it carries text;
      // report it, but only a font-size-bearing rule is a laundered rung.
      const line = t.slice(0, m.index).split('\n').length;
      hits.push({ f, line, scale: scale ? scale[1].trim() : '?', sets });
    }
  }
  const laundered = hits.filter((h) => h.sets);
  const detail = hits.length === 0
    ? 'no scale transform on any rule'
    : hits.map((h) => `${h.f}:${h.line} ${h.scale}${h.sets ? '  <- ON A font-size RULE' : ''}`).join(' · ');
  return record('no-type-transform', true, laundered.length === 0, detail);
}

/** One absence glyph, one meaning. Thirteen phrasings for "nothing here" is a rack nobody can
 *  scan, because the eye must read every cell to learn it says nothing. */
function checkAbsenceVocabulary(srcDir) {
  const PHRASES = /'(n\/r|none|unavailable|not reported(?: by provider)?|not built|not declared|not started|not running|no rates(?: declared)?|no fix offered|no turn in flight|ineligible|no window reported|not available|no data|unknown)'/g;
  const seen = new Map();
  const walk = (d) => {
    let ents; try { ents = fs.readdirSync(path.join(ROOTREF.root, d), { withFileTypes: true }); } catch { return; }
    for (const e of ents) {
      const rel = path.join(d, e.name);
      if (e.isDirectory()) walk(rel);
      else if (/\.tsx?$/.test(e.name)) {
        const t = readIf(rel) || '';
        for (const m of t.matchAll(PHRASES)) seen.set(m[1], (seen.get(m[1]) || 0) + 1);
      }
    }
  };
  walk(srcDir);
  const distinct = [...seen.keys()];
  const detail = `${distinct.length} distinct absence phrasings: `
    + distinct.sort((a, b) => seen.get(b) - seen.get(a)).map((k) => `${k}(${seen.get(k)})`).join(' ');
  return record('absence-vocabulary', false, distinct.length <= 2, detail);
}

// ---------------------------------------------------------------- capture checks (PNG only)

/** Minimal PNG reader: we only need raw RGB, and pulling in a dependency for a gate is how
 *  gates stop being run. Uses `python3 -c` with PIL, which this repo's review already relies on;
 *  when PIL is absent the capture checks skip rather than fail, and say so. */
function pixels(pngPath) {
  try {
    const out = execFileSync('python3', ['-c', `
import sys
from PIL import Image
import numpy as np
im=Image.open(sys.argv[1]).convert("RGB")
a=np.array(im)
sys.stdout.buffer.write(bytes(f"{a.shape[1]} {a.shape[0]}\\n","ascii"))
sys.stdout.buffer.write(a.tobytes())
`, pngPath], { maxBuffer: 1 << 28 });
    const nl = out.indexOf(0x0a);
    const [w, h] = out.slice(0, nl).toString('ascii').trim().split(' ').map(Number);
    return { w, h, data: out.slice(nl + 1) };
  } catch {
    return null;
  }
}
const px = (im, x, y) => {
  const i = (y * im.w + x) * 3;
  return [im.data[i], im.data[i + 1], im.data[i + 2]];
};
const mean = (c) => (c[0] + c[1] + c[2]) / 3;

/** ---- THE FIELD LINE'S COLOUR, READ FROM THE TOKEN SHEET PER THEME (M1-65) ----
 *  This was `line = [0xa8, 0xa3, 0x92]`, a hardcoded hex. `#A8A392` is `--strip-field-line`
 *  IN THE DARK BLOCK ONLY: the light block defines the same token as `rgba(31,36,34,.55)`,
 *  which is a different colour entirely. So the detector has never been able to work in the
 *  light room -- and it did not FAIL there, which is the dangerous half: it found no edges at
 *  all and reported whatever no-edges means, silently, on every light capture the gate took.
 *  Reading the token is also what stops this drifting again: the anchor was a measurement of
 *  the token, and E-1 (M1-24) then changed what `--strip-field-line` is drawn on without the
 *  hex knowing, which is how the index below came to be off by one field for a whole row.
 *  An alpha token is composited over the paper it is drawn on, because that is what the
 *  renderer does and the capture is a photograph of the render. */
function themeTokens(theme) {
  const css = readIf(TOKENS) || '';
  // Split at each theme block so a dark value can never answer for a light question.
  const blocks = css.split(/:root\[data-theme="(light|dark)"\]/);
  let body = '';
  for (let i = 1; i < blocks.length; i += 2) if (blocks[i] === theme) body = blocks[i + 1];
  if (!body) body = theme === 'dark' ? css.split(':root,')[1] || '' : '';
  const read = (name) => {
    const m = new RegExp(`--${name}\\s*:\\s*([^;]+);`).exec(body);
    return m ? m[1].trim() : null;
  };
  return { read };
}
function parseColor(value, ground = [255, 255, 255]) {
  if (value === null) return null;
  const hex = /^#([0-9a-f]{6})$/i.exec(value);
  if (hex) return [0, 2, 4].map((i) => parseInt(hex[1].slice(i, i + 2), 16));
  const rgba = /^rgba?\(\s*([\d.]+)\s*,\s*([\d.]+)\s*,\s*([\d.]+)\s*(?:,\s*([\d.]+)\s*)?\)$/i.exec(value);
  if (!rgba) return null;
  const a = rgba[4] === undefined ? 1 : Number(rgba[4]);
  return [1, 2, 3].map((i) => Number(rgba[i]) * a + ground[i - 1] * (1 - a));
}
/** The colour `--strip-field-line` actually paints, in one theme, on the strip it divides. */
function fieldLine(theme) {
  const t = themeTokens(theme);
  const paper = parseColor(t.read('strip')) || [222, 217, 198];
  return parseColor(t.read('strip-field-line'), paper) || [0xa8, 0xa3, 0x92];
}
/** THE FIELD GRID. The whole legibility of a rack is that field N is at the same x on every
 *  strip: a controller scans down a column, never across a row. Detects the vertical field
 *  rules on each strip's scanline and fails when two strips in one bay disagree. */
function fieldBorders(im, y, x0, x1, line = [0xa8, 0xa3, 0x92], tol = 26) {
  const xs = [];
  for (let x = x0; x < x1; x++) {
    const c = px(im, x, y);
    if (Math.abs(c[0] - line[0]) + Math.abs(c[1] - line[1]) + Math.abs(c[2] - line[2]) < tol) xs.push(x);
  }
  const out = []; let prev = -9;
  for (const x of xs) { if (x - prev > 2) out.push(x); prev = x; }
  return out;
}
/** The theme a capture was taken in, read from its own filename (the gate names them
 *  `<address>-<theme>-<w>x<h>.png`). A filename that names no theme is reported as such
 *  rather than defaulted, because a default here would silently check the wrong room. */
function captureTheme(file) {
  if (/-light-/.test(file)) return 'light';
  if (/-dark-/.test(file)) return 'dark';
  return null;
}
/** Rows whose middle is strip paper: the scanline through each strip's value row. */
function stripScanlines(im, x0, x1) {
  const rows = [];
  for (let y = 0; y < im.h; y++) {
    let pale = 0;
    for (let x = x0; x < x1; x += 4) if (mean(px(im, x, y)) > 150) pale++;
    rows.push(pale > ((x1 - x0) / 4) * 0.5);
  }
  const runs = []; let s = null;
  for (let i = 0; i < rows.length; i++) {
    if (rows[i] && s === null) s = i;
    if (!rows[i] && s !== null) { if (i - s > 20) runs.push(Math.round((s + i) / 2)); s = null; }
  }
  return runs;
}

function checkFieldGrid(capturesDir) {
  const files = (() => { try { return fs.readdirSync(path.join(ROOT, capturesDir)).filter((f) => f.endsWith('.png')); } catch { return []; } })();
  if (files.length === 0) return record('field-grid', true, true, `skipped: no captures in ${capturesDir}`);
  const bad = [];
  let checked = 0, skipped = 0, unthemed = 0;
  for (const f of files) {
    const im = pixels(path.join(ROOT, capturesDir, f));
    if (!im) { skipped++; continue; }
    const mid = stripScanlines(im, 200, Math.min(1100, im.w - 20));
    if (mid.length < 2) continue;
    // A capture that names no theme is skipped AND COUNTED, so the leg can say it did not run
    // for that file rather than passing it by default (law 23).
    const theme = captureTheme(f);
    if (theme === null) { unthemed++; continue; }
    checked++;
    // the first field's right edge; it must not move between strips.
    // THE INDEX IS [0] AND IT USED TO BE [1], which is not a tuning change. `fieldBorders` finds
    // pixels in `--strip-field-line`'s colour, and that used to match the strip's own outer box
    // line as well as the cell dividers -- so the list began with the strip's border and the first
    // FIELD edge was the second entry. M1-24's E-1 replaced the strip's outer line by measurement
    // (the material spec's own finding is that --strip-field-line, L 163, cannot carry the comp's
    // #73716A, L 113 shade), so the strip's border is no longer in this colour family and the list
    // begins at the field edge it was always looking for. The rule is unchanged; only the anchor
    // it counted from moved, and reading [1] now measures the SECOND field's edge, which is a
    // different claim than the one this rule makes.
    // The line's colour comes from the token, IN THIS CAPTURE'S THEME. Hardcoding the dark
    // block's #A8A392 is what made the light room invisible to this rule since it was written.
    const line = fieldLine(theme);
    const seconds = mid.map((y) => fieldBorders(im, y, 140, im.w - 4, line)[0]).filter((v) => v !== undefined);
    if (seconds.length < 2) continue;
    const spread = Math.max(...seconds) - Math.min(...seconds);
    if (spread > 2) bad.push(`${f}: first field edge spans ${spread}px across ${seconds.length} strips (x ${Math.min(...seconds)}..${Math.max(...seconds)})`);
  }
  const detail = (bad.length ? bad.join(' · ') : `aligned on ${checked} captures`)
    + (skipped ? ` · ${skipped} skipped (no PIL)` : '')
    + (unthemed ? ` · ${unthemed} skipped (filename names no theme, so the wrong room could have been checked)` : '');
  if (checked === 0) return record('field-grid', true, true, `skipped: ${detail}`);
  return record('field-grid', true, bad.length === 0, detail);
}

/** Tonal drift against the comp. Not a pixel diff — a distribution diff, which survives content
 *  changing while still catching a world losing its middle register. */
function tonal(im) {
  let room = 0, paper = 0, mid = 0;
  for (let y = 0; y < im.h; y += 2) for (let x = 0; x < im.w; x += 2) {
    const m = mean(px(im, x, y));
    if (m < 30) room++; else if (m > 150) paper++; else mid++;
  }
  const n = room + paper + mid;
  return { room: room / n, paper: paper / n, mid: mid / n };
}
function checkTonalDrift(capturesDir, compPath) {
  const comp = pixels(path.join(ROOT, compPath));
  if (!comp) return record('tonal-drift', false, true, 'skipped: comp unreadable (no PIL?)');
  const ref = tonal(comp);
  const files = (() => { try { return fs.readdirSync(path.join(ROOT, capturesDir)).filter((f) => f.endsWith('.png')); } catch { return []; } })();
  const rows = [];
  for (const f of files) {
    const im = pixels(path.join(ROOT, capturesDir, f));
    if (!im) continue;
    const t = tonal(im);
    rows.push({ f, mid: t.mid, ratio: t.mid / ref.mid });
  }
  if (rows.length === 0) return record('tonal-drift', false, true, 'skipped: no readable captures');
  rows.sort((a, b) => a.ratio - b.ratio);
  const worst = rows[0];
  const detail = `comp mid-tone ${(ref.mid * 100).toFixed(1)}% · worst capture ${worst.f} at ${(worst.mid * 100).toFixed(1)}% `
    + `(${(worst.ratio * 100).toFixed(0)}% of the comp) · ${rows.filter((r) => r.ratio < 0.5).length}/${rows.length} captures below half the comp`;
  return record('tonal-drift', false, worst.ratio >= 0.5, detail);
}

// ---------------------------------------------------------------- selftest (the mutation proof)


/** A synthetic one-row capture with two strips whose first field edge is at DIFFERENT x, drawn
 *  in `drawIn`'s theme colour while the detector is told the capture is `declareAs`. A correct
 *  detector FAILS on it (the edges disagree); the pre-M1-65 detector told 'light' while the ink
 *  is dark finds no edges at all and passes -- which is the bug, reproduced. */
function fieldProbe(declareAs, drawIn) {
  const w = 1200, h = 40;
  const data = Buffer.alloc(w * h * 3, 0);
  const paint = (x, y, rgb) => { const i = (y * w + x) * 3; data[i] = rgb[0]; data[i + 1] = rgb[1]; data[i + 2] = rgb[2]; };
  for (const y of [8, 28]) for (let x = 0; x < w; x++) paint(x, y, [222, 217, 198]);   // paper rows
  const line = fieldLine(drawIn);
  for (const [y, xs] of [[8, [300, 700]], [28, [380, 700]]]) for (const x of xs) paint(x, y, line);
  const im = { w, h, data };
  const mid = [8, 28];
  const seconds = mid.map((y) => fieldBorders(im, y, 140, w - 4, fieldLine(declareAs))[0]).filter((v) => v !== undefined);
  findings.length = 0;
  if (seconds.length < 2) { record('field-grid', true, true, `no edges found (ink drawn in ${drawIn}, read as ${declareAs})`); return findings[0]; }
  const spread = Math.max(...seconds) - Math.min(...seconds);
  record('field-grid', true, spread <= 2, `first field edge spans ${spread}px`);
  return findings[0];
}

function selftest() {
  // §24: a gate that has never failed is a tautology. Each check is run against a synthetic
  // violation and must FAIL, then against a compliant form and must PASS.
  const cases = [
    ['ladder-steps', () => { findings.length = 0; checkLadderSteps('--text-1:12px; --text-2:14px; --text-3:16px;'); return findings[0]; }, false],
    ['ladder-steps', () => { findings.length = 0; checkLadderSteps('--text-1:12px; --text-2:16px; --text-3:24px;'); return findings[0]; }, true],
    ['no-type-transform', () => {
      findings.length = 0;
      const tmp = fs.mkdtempSync('/tmp/lookgate-');
      fs.writeFileSync(path.join(tmp, 'a.css'), '.x { font-size: var(--text-1); transform: scaleY(1.5); }');
      const saved = ROOTREF.root; ROOTREF.root = tmp;
      checkNoTypeTransform(['a.css']); ROOTREF.root = saved;
      fs.rmSync(tmp, { recursive: true, force: true });
      return findings[0];
    }, false],
    ['no-type-transform', () => {
      findings.length = 0;
      const tmp = fs.mkdtempSync('/tmp/lookgate-');
      fs.writeFileSync(path.join(tmp, 'a.css'), '.x { font-size: var(--text-1); font-stretch: 78%; }');
      const saved = ROOTREF.root; ROOTREF.root = tmp;
      checkNoTypeTransform(['a.css']); ROOTREF.root = saved;
      fs.rmSync(tmp, { recursive: true, force: true });
      return findings[0];
    }, true],
    // ---- THE FIELD DETECTOR, IN BOTH THEMES (M1-65). The light case was NEVER exercised:
    // the colour was the dark block's hex, so a light capture found no edges and the rule
    // passed by finding nothing. These two cases are the mutation proof that it can now fail
    // in the light room as well as the dark one, and that it still refuses the other room's
    // colour -- which is the exact defect, reproduced as a test rather than argued.
    ['field-grid', () => fieldProbe('dark', 'dark'), false],
    ['field-grid', () => fieldProbe('light', 'light'), false],
    ['field-grid', () => fieldProbe('light', 'dark'), true],
  ];
  let pass = 0, fail = 0;
  for (const [id, run, expectOk] of cases) {
    const f = run();
    const good = f && f.ok === expectOk;
    console.log(`  ${good ? 'PASS' : 'FAIL'}  ${id} expected ok=${expectOk} got ok=${f && f.ok}  (${f && f.detail})`);
    good ? pass++ : fail++;
  }
  console.log(`\nselftest: ${pass} passed, ${fail} failed`);
  process.exit(fail === 0 ? 0 : 1);
}
// ---------------------------------------------------------------- main

if (has('selftest')) selftest();

const files = cssFiles(SRC);
checkLadderSteps(readIf(TOKENS) || '');
checkTypeDistribution(files);
checkSpacingDistribution(files);
checkNoTypeTransform(files);
checkAbsenceVocabulary(SRC);
checkFieldGrid(CAPTURES);
checkTonalDrift(CAPTURES, COMP);

if (has('json')) {
  console.log(JSON.stringify({ findings }, null, 2));
} else {
  const w = Math.max(...findings.map((f) => f.id.length));
  console.log('look-gate\n');
  for (const f of findings) {
    const tag = f.ok ? 'ok  ' : (f.blocking ? 'FAIL' : 'warn');
    console.log(`  ${tag}  ${f.id.padEnd(w)}  ${f.detail}`);
  }
  const blocked = findings.filter((f) => !f.ok && f.blocking);
  console.log(`\n  ${blocked.length === 0 ? 'LOOK-GATE: PASS' : `LOOK-GATE: FAIL (${blocked.length} blocking)`}`);
}
process.exit(findings.some((f) => !f.ok && f.blocking) ? 1 : 0);
