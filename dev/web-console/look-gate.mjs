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
import zlib from 'node:zlib';
import { decodePng } from './lib/png.mjs';
import { mgmtKey, shoot, show, withChrome } from './lib/cdp.mjs';

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
/** The boot axes' own directory (M1-106), NOT `sections/`: checkFieldGrid reads every PNG in the
 *  captures directory, so a frame of a modal over a page would be judged as a rack row and would
 *  red a leg it has nothing to do with. An axis is evidence about a surface, not about the grid. */
const AXES_DIR = flag('axes', 'webui/.impeccable/review/axes');
const SRC      = flag('src', 'webui/src');
const TOKENS   = flag('tokens', 'webui/src/shared/tokens.css');
const COMP     = flag('comp', 'webui/.impeccable/mocks/team-board-a.png');

const findings = [];
const record = (id, blocking, ok, detail) => {
  findings.push({ id, blocking, ok, detail });
  return ok;
};

/**
 * ---- THE PER-PAGE LEDGER (M1-124): WHICH PAGE DID EACH LEG ACTUALLY LOOK AT ----
 *
 * M1-108 fixed the empty-SET case (a leg that read no captures reporting ok) and M1-114 fixed the
 * unjudgeable-SET case (a leg that read captures and judged none of them). Both are set-level. The
 * PER-PAGE case was never swept, and it hid the one page this campaign has a comp of record for:
 * teams paints no `--strip-field-line` ink anywhere on its rack rows, so `mid.length < 2` dropped
 * it at a bare `continue` with no counter, and the field-grid leg has never judged it. The leg said
 * "aligned on 11 captures" and nothing anywhere said which eleven.
 *
 * A COUNT IS NOT A DISPOSITION. `staleCount++` tells a reader that something was skipped and never
 * which page, so "covered 13/13" and "judged 11 of 13" could both be true at once and neither named
 * the gap. §24's rule is that every item needs a disposition and absence is not one; this is that
 * rule applied to pages instead of to tables.
 *
 * WHAT THIS IS NOT: a floor. Nothing here reddens a page for being dropped. A page dropped for a
 * stated structural reason -- settings' rack is not `.myx-strip` (M1-92), so its dump declares none
 * -- is a legitimate drop and stays green; it simply becomes VISIBLE. The only thing the table
 * asserts is that no page is missing from it, which is the one failure an audit can have.
 */
const COVERAGE = new Map();
/** Record what one leg did with one address. `why === null` means it judged it. */
const covers = (leg, address, why = null) => { COVERAGE.set(`${leg}\u0000${address}`, why); };
const coverageOf = (leg, address) => {
  const key = `${leg}\u0000${address}`;
  return COVERAGE.has(key) ? { known: true, why: COVERAGE.get(key) } : { known: false, why: null };
};
/** The address a capture filename belongs to, against the ADDRESSES table rather than by splitting
 *  on the first dash: `fleet-rule-check.png` starts with `fleet-` and is not fleet's capture. Only
 *  the canonical name the capture leg writes counts, so a stray file in the directory is reported
 *  as a stray rather than silently standing in for a page. */
function addressOf(file, list) {
  const [w, h] = CAPTURE_FRAME;
  const found = (list ?? []).find((a) => file === `${a}-${CAPTURE_THEME}-${w}x${h}.png`);
  return found ?? null;
}

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

/** One absence glyph, one meaning, and a denominator that is the sum of what it enumerates.
 *
 *  WHAT THIS COUNTS, and the counting is the point (M1-74). It counts DISPLAY WORDS - the words a
 *  reader sees - and reports the three kinds of quoted declaration that are NOT display words on
 *  their own line, because a warning that folds them together asks every reader to re-derive the
 *  classification from scratch:
 *    a TYPE MEMBER       a word in a union the console prints as a label beside a figure
 *    a WIRE VALUE        a value the DAEMON sends, compared against and never printed
 *    a CSS KEYWORD       `none` on a style assignment, which is not language at all
 *  A site is one of those when the line, or one of the three above it, carries the marker
 *  `not-an-absence: <class>` or the older prose form `NOT AN ABSENCE PHRASE`. The classification
 *  lives in the SOURCE, next to the site, where the person who has to classify it is already
 *  reading - not in a list inside this file that would drift from the tree it describes.
 *
 *  THE TOTAL IS DERIVED, NEVER ASSERTED. The scan counts matches as it walks and the report sums
 *  the per-word map afterwards; the two must agree, and a disagreement is a named failure rather
 *  than a number nobody can reproduce. (This check printed 57 while its own counts summed to 48,
 *  and that number was used to cut a row. The fix is that the printed total IS the sum.) */
function checkAbsenceVocabulary(srcDir) {
  const PHRASES = /'(n\/r|none|unavailable|not reported(?: by provider)?|not built|not declared|not started|not running|no rates(?: declared)?|no fix offered|no turn in flight|ineligible|no window reported|not available|no data|unknown)'/g;
  const MARKER = /(?:not-an-absence:\s*([a-z][a-z-]*)|NOT AN ABSENCE PHRASE)/i;
  const seen = new Map();
  const noise = new Map();
  let scanned = 0;
  const walk = (d) => {
    let ents; try { ents = fs.readdirSync(path.join(ROOTREF.root, d), { withFileTypes: true }); } catch { return; }
    for (const e of ents) {
      const rel = path.join(d, e.name);
      if (e.isDirectory()) walk(rel);
      else if (/\.tsx?$/.test(e.name)) {
        const lines = (readIf(rel) || '').split('\n');
        lines.forEach((line, i) => {
          for (const m of line.matchAll(PHRASES)) {
            scanned += 1;
            // Six lines of headroom: a note above a site is usually one or two lines, but a
            // doc comment with a blank line and a closing brace between them is three or four.
            const above = lines.slice(Math.max(0, i - 6), i + 1).join('\n');
            const mark = above.match(MARKER);
            if (mark === null) seen.set(m[1], (seen.get(m[1]) || 0) + 1);
            else {
              const kind = (mark[1] || 'annotated').trim();
              noise.set(kind, (noise.get(kind) || 0) + 1);
            }
          }
        });
      }
    }
  };
  walk(srcDir);
  const words = [...seen.entries()].sort((a, b) => b[1] - a[1]);
  const enumerated = words.reduce((sum, [, n]) => sum + n, 0);
  const noiseRows = [...noise.entries()].sort((a, b) => b[1] - a[1]);
  const noiseTotal = noiseRows.reduce((sum, [, n]) => sum + n, 0);
  const detail = () => `${words.length} display word(s): `
    + words.map(([k, n]) => `${k}(${n})`).join(' ')
    + ` · ${noiseTotal} quoted declaration(s) that are not display words: `
    + (noiseRows.length === 0 ? 'none' : noiseRows.map(([k, n]) => `${k}(${n})`).join(' '));
  if (scanned !== enumerated + noiseTotal) {
    return record('absence-vocabulary', false, false,
      `the scan counted ${scanned} site(s) and the report enumerates ${enumerated} display + ${noiseTotal} annotated = ${enumerated + noiseTotal}: the total is not the sum of its parts, so neither number can be trusted`);
  }
  return record('absence-vocabulary', false, words.length <= 2, detail());
}

// ---------------------------------------------------------------- capture checks (PNG only)

/**
 * Minimal PNG reader: we only need raw RGB, and pulling in a dependency for a gate is how gates
 * stop being run. Decoded in-process by `lib/png.mjs` on `node:zlib` alone — no library, and no
 * subprocess either (M1-125).
 *
 * WHAT THE SUBPROCESS COST, WHICH IS THE REASON THIS CHANGED RATHER THAN THE LANGUAGE RULE. This
 * shelled out to a Python interpreter with PIL and numpy, and the catch below swallowed its absence
 * exactly as it swallows a corrupt file. So on any machine without PIL EVERY capture check skipped
 * and the gate reported no-checks as though it had looked — a did-not-run dressed as a pass, and
 * documented as intentional, which is how it survived this campaign's whole hunt for that shape.
 *
 * PROVEN BYTE-IDENTICAL BEFORE THE SWAP, over exactly the set this function is called on — the comp
 * plus every PNG in the captures directory, the denominator taken from the three call sites rather
 * than chosen: 28 of 28 byte-identical, 0 differ, all colour type 2. `decodePng` THROWS on anything
 * it does not understand (interlaced, 16-bit, palette) instead of guessing, which is the right
 * default for a gate: a decoder that guesses produces plausible wrong pixels, and a verdict from a
 * misread frame is precisely what this review plane exists to prevent. The one PNG in the tree it
 * refuses is a palette image under `reference/competitors/` that this gate never reads.
 *
 * DROPPING ALPHA RATHER THAN COMPOSITING IT is what `.convert("RGB")` did, so `px()` and every
 * reader downstream are unaffected.
 *
 * THE CATCH STAYS QUIET, AND THAT IS A RULING RATHER THAN AN OVERSIGHT (M1-124). A decode failure
 * no longer means "the interpreter is missing on this machine"; it means the file is broken or its
 * colour type is unsupported. It is still not a floor: the condition is named per address in the
 * coverage table, and `checkFieldGrid` already reds when it judges zero captures — so a single
 * unreadable capture is named and skipped while a total decode failure still fails the gate.
 */
function pixels(pngPath) {
  try {
    const im = decodePng(fs.readFileSync(pngPath));
    if (im.channels === 3) return { w: im.width, h: im.height, data: im.pixels };
    const data = Buffer.allocUnsafe(im.width * im.height * 3);
    for (let i = 0, n = im.width * im.height; i < n; i++) {
      data[i * 3] = im.pixels[i * 4];
      data[i * 3 + 1] = im.pixels[i * 4 + 1];
      data[i * 3 + 2] = im.pixels[i * 4 + 2];
    }
    return { w: im.width, h: im.height, data };
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

/** The sibling a declaration dump is written to, beside the PNG it belongs to (M1-92). */
const declName = (png) => png.replace(/\.png$/, '.decl.json');

/**
 * THE GRID VERDICT, GIVEN THE EDGES AND THE DECLARATIONS THE PIXELS CANNOT CARRY (M1-92).
 *
 * WHY THIS IS A SEPARATE FUNCTION: the verdict is the thing the row is about, and a verdict that
 * can only be reached through a directory of PNGs cannot be mutation-proved. This takes the edges
 * and the declarations as data, so the selftest can hand it a declared span and an undeclared one
 * and watch it answer differently.
 *
 * WHAT THE PIXELS CANNOT SAY AND THIS CAN. A screenshot carries consequences, never intent: when
 * `StripField` renders a field that spans two tracks it says so in the DOM as `data-span="2"`, and
 * on the glass that is indistinguishable from a first field that merely happens to be wide. The
 * detector's job is unchanged -- field N at the same x on every strip, because a controller scans
 * down a column -- and a declared span does not weaken it: the declared strips are EXCLUDED from
 * the comparison and NAMED in the output, and every other strip is still compared, still at 2px.
 * That is the difference between honouring a declaration and failing to look, and the output has to
 * carry which one happened.
 *
 * A ROW, NOT A STRIP: the scanlines this leg reads are rows of the rack, and one row can carry
 * several strips side by side. A row counts as declared when the first field of any strip ON IT
 * carries a span, which is the field whose edge that row's leftmost border is measuring.
 *
 * AN EMPTY `declared` IS NOT A DECLARATION: a row whose strips could not be paired with a dump is
 * compared, not excused, so a missing or unreadable dump can never make the grid pass.
 */
/**
 * THE SCANLINES, TURNED INTO ROWS THE VERDICT CAN BE TAKEN OVER (M1-114).
 *
 * LIFTED OUT FOR judgeGrid's OWN REASON, one paragraph down: a decision reachable only through a
 * directory of PNGs cannot be mutation-proved. The span ruling got that treatment in M1-92 and the
 * ANCHOR -- which is what actually decides the number -- did not, so every anchor defect this
 * campaign has found was found on live pages that move under the seat reading them. This takes the
 * image, the scanlines, the ink and the declarations as data, so the selftest can hand it a strip
 * the renderer clipped and watch the row disappear.
 *
 * ONLY STRIPS THE RENDERER ACTUALLY PAINTED. A rect is not evidence that anything was drawn at it:
 * `getBoundingClientRect()` answers for every RENDERED element, and a virtualized rack renders into
 * its own scroll container and then CLIPS. Measured on logs: the dump declares fifteen strips, the
 * capture paints eleven, and clipped strip #3 (y 198..262) spans `.myx-lt-head` (203..283) -- whose
 * own box border is `border: var(--hair) solid var(--strip-field-line)`
 * (webui/src/widgets/log-tail/log-tail.css), the SAME INK as a cell divider. So the leg paired the
 * header's two scanlines to a strip by y-containment, read the band's box border as that row's
 * first field edge, and called the difference 159px. The dump said "this is a strip" about a row
 * that is not one. `painted` is the renderer's own answer, via `elementFromPoint`.
 *
 * THE ANCHOR, AND WHY IT MOVED OFF THE CONSTANT 140. The rule is where a controller scanning a
 * column lands, and a controller scans THE STRIP -- so the scan starts at that strip's own left
 * edge rather than at a constant chosen when every rack began in the same place. The leftmost strip
 * on the row owns the reading, because the leftmost border is its.
 *
 * A row with no painted strip keeps `onStrip: false` rather than being dropped here, so the caller
 * can COUNT what it excluded. "Nothing was declared" and "nothing was looked at" read the same in a
 * number, which is the defect this whole leg keeps re-finding at a new level.
 */
function gridRows(im, mid, line, known) {
  const stripsAt = (y) => (known === null ? [] : known.filter((s) => s.painted && y >= s.y && y < s.y + s.h));
  return mid.map((y) => {
    const at = stripsAt(y);
    const home = at.length === 0 ? null : at.reduce((a, b) => (a.x <= b.x ? a : b));
    const x = home === null ? undefined : fieldBorders(im, y, Math.max(0, home.x), im.w - 4, line)[0];
    // A declared span counts when it is the FIRST field of a strip on this row: that is the field
    // whose edge the scanline's leftmost border is measuring.
    const declared = at.flatMap((s) => {
      const first = s.fields === undefined ? undefined : s.fields[0];
      return first !== undefined && first !== null && first.span !== null && Number(first.span) > 1
        ? [{ label: first.label, span: first.span }] : [];
    });
    return { y, x, declared, onStrip: home !== null, bay: home === null ? undefined : home.bay };
  });
}

function judgeGrid(rows) {
  const declared = rows.filter((r) => r.declared.length > 0);
  const plain = rows.filter((r) => r.declared.length === 0);
  // Deduped: one spanning field shows up on every scanline that crosses its strip, and a message
  // that names the same field four times reads as four fields.
  const honoured = [...new Set(declared.flatMap((r) => r.declared.map((d) => `${d.label === null || d.label === '' ? 'field 1' : d.label} (data-span=${d.span})`)))];
  if (plain.length < 2) {
    return { ok: undefined, honoured, detail: `only ${plain.length} scanline(s) whose first field is not a declared span, so the grid cannot be compared` };
  }
  // ---- THE groupBy THIS LEG NEVER HAD, WHICH IS THE WHOLE DEFECT (M1-114) ----
  //
  // Line 299 of this file has said since it was written that the rule fails when two strips IN ONE
  // BAY disagree. The code then built one scanline list over the WHOLE capture and took one spread
  // across all of it. That is the M1-70 class -- a comment describing an intention the code does
  // not carry -- and it is the same shape as M1-108's, where the words said DID NOT RUN and the
  // verdict said ok. The words were right both times.
  //
  // WHY A CROSS-BAY SPREAD IS NOT A DEFECT, MEASURED (design-builder3, M1-115, DOM-proven): turns
  // has three bays, and the compared edge is FIELD 2's left boundary, because no rack paints field
  // 1's. The in-flight rack is six fields at 220/412/604/720/816/912 and the landed rack is fourteen
  // at 220/336/528/700/854/998/... -- so the leg was reading 412-1 against 336-1 and calling the
  // 76px a misalignment. It is not one. A six-field grid and a fourteen-field grid have no shared
  // column to disagree about, and no anchor reconciles them; each bay is internally exact. One
  // missing groupBy explains all three reds this row was cut for: logs' 159 is a band against a
  // rack, turns' 76 is two racks in two bays, accounts' 34 was two racks.
  //
  // A row whose bay is unknown groups under `undefined` and is compared with its own kind, which is
  // what keeps a dump that carries no bay index from silently passing everything: unknown is one
  // group, not one group each.
  const byBay = new Map();
  for (const r of plain) {
    const key = r.bay === undefined ? 'unknown' : r.bay;
    if (!byBay.has(key)) byBay.set(key, []);
    byBay.get(key).push(r);
  }
  const groups = [...byBay.entries()]
    .map(([bay, rs]) => {
      const xs = rs.map((r) => r.x);
      return { bay, n: rs.length, lo: Math.min(...xs), hi: Math.max(...xs), spread: Math.max(...xs) - Math.min(...xs) };
    })
    .filter((g) => g.n >= 2);
  // EVERY BAY HAS ONE ROW: there is nothing to compare, and saying so beats reporting a clean grid.
  // This is the same refusal as the `plain.length < 2` line above, one level in -- the denominator
  // moved from the capture to the bay, so the emptiness check had to move with it (law 34).
  if (groups.length === 0) {
    return { ok: undefined, honoured, detail: `${plain.length} comparable scanline(s) but no bay holds two, so no column can be compared` };
  }
  groups.sort((a, b) => b.spread - a.spread);
  const worst = groups[0];
  const say = (g) => `bay ${g.bay}: ${g.spread}px across ${g.n} scanlines (x ${g.lo}..${g.hi})`;
  return {
    ok: worst.spread <= 2,
    honoured,
    detail: `first field edge spans ${worst.spread}px within a bay — ${say(worst)}`
      + `${declared.length > 0 ? ', comparing only scanlines whose first field is not a declared span' : ''}`
      + ` · ${groups.length} bay(s) compared${groups.length > 1 ? `: ${groups.map(say).join(' · ')}` : ''}`,
  };
}

/** The honoured clause, appended to whatever the grid verdict says, so the output states in words
 *  that a declaration was obeyed and which field it was (M1-92: silence is what made this class
 *  invisible everywhere else). Empty when nothing was declared. */
const honouredClause = (judged) => (judged.honoured.length === 0 ? ''
  : ` · honoured a declared span: ${judged.honoured.join(', ')} — ${judged.honoured.length} strip(s) excluded from the comparison by declaration, the rest still compared`);

function checkFieldGrid(capturesDir) {
  const files = (() => { try { return fs.readdirSync(path.join(ROOTREF.root, capturesDir)).filter((f) => f.endsWith('.png')); } catch { return []; } })();
  // DID NOT RUN IS A FAILURE ON A BLOCKING LEG (M1-108, orchestrator ruling).
  //
  // This said `DID NOT RUN` in the detail and passed `true` for ok, so the words and the verdict
  // disagreed and the verdict is what the gate reads. It is the M1-78 shape — prose that stayed
  // true while the code stopped matching it — and this file's own capture block states the rule
  // three hundred lines down: a run that cannot capture is a DID NOT RUN for every capture-reading
  // check, never a pass (law 23). It is also the type-ladder rule inverted: M1-72 proved that zero
  // pairs compared must not read as zero rungs off.
  //
  // THE DENOMINATOR IS M1-94'S, NOT A NEW ONE. `addresses()` reads the ADDRESSES table in
  // webui/src/app/rows.ts — the same list the capture leg enumerates and the same one the tonal
  // leg counts coverage against. Two floors that disagree is a worse outcome than the bug.
  const expected = addresses();
  const needed = expected === null ? null : expected.length;
  if (files.length === 0) {
    // Same rule as tonal-drift's comp refusal: a whole-leg exit drops every page by name (M1-124).
    for (const a of expected ?? []) covers('field-grid', a, `no captures at all in ${capturesDir}`);
    return record('field-grid', true, false,
      `DID NOT RUN: got 0 captures in ${capturesDir}, needed at least 1 `
      + (needed === null
        ? `(and ${ADDRESSES_FILE} carries no ADDRESSES table, so the address count could not be read either)`
        : `— the set should hold one per address for ${needed} address(es) in ${ADDRESSES_FILE}`)
      + '. Run the capture leg; if the directory moved, this path is wrong rather than the build.');
  }
  // FRESHNESS, PER FILE (M1-68; the build leg's dist/index.html rule, applied to the capture
  // set). A capture older than the source it claims to measure is not evidence about the current
  // build, so it is SKIPPED AND COUNTED rather than judged -- and rather than taking the whole
  // leg down with it, which is what a leg-wide rule did on the first run of this: eight fresh
  // captures were refused because nineteen stale ones shared their directory.
  const bar = freshnessBar();
  let staleCount = 0;
  const bad = [];
  // Declared spans honoured, dumps that could not be paired, captures read with no dump at all, and
  // captures the grid could not be compared on -- each named in the output rather than folded into a
  // count, because "nothing was declared" and "nothing was looked at" read the same in a number.
  const honoured = [], excluded = [], thin = [], staleDump = [], undeclaredNoDump = [], skippedDetail = [];
  let checked = 0, skipped = 0, unthemed = 0, scanty = 0;
  // EVERY DROP BELOW NAMES ITS PAGE (M1-124). The counters stay -- they are what the detail line
  // reads -- but each one is now paired with a disposition against the ADDRESS, so a reader can ask
  // "what did this leg do with teams" and get an answer instead of a total.
  for (const f of files) {
    const at = addressOf(f, expected);
    const drop = (why) => { if (at !== null) covers('field-grid', at, why); };
    let mtime = 0; try { mtime = fs.statSync(path.join(ROOTREF.root, capturesDir, f)).mtimeMs; } catch { mtime = 0; }
    if (mtime < bar) { staleCount++; drop(`capture is older than ${SRC}`); continue; }
    const im = pixels(path.join(ROOTREF.root, capturesDir, f));
    if (!im) { skipped++; drop('capture is broken or its colour type is unsupported'); continue; }
    const mid = stripScanlines(im, 200, Math.min(1100, im.w - 20));
    // A SILENT DROP WITH NO COUNTER AT ALL (M1-124), AND IT IS NOT THE ONE THAT HID TEAMS.
    // This was a bare `continue`: not a count, not a name, nothing. A page whose paper produced
    // fewer than two strip scanlines left the leg without a trace of any kind.
    // MEASURED, AND STATED BECAUSE IT WOULD BE EASY TO CLAIM OTHERWISE: no address in the console
    // currently reaches this line -- `scanty` is 0 on a full run of all thirteen. teams IS dropped,
    // but one step further down, at the painted-strip guard, which M1-114 already named: teams
    // yields four scanlines and then paints no `--strip-field-line` ink on any of them
    // (`fieldBorders` returns [] at y=98, 342 and 437 from either anchor), so `onStrip` is empty
    // rather than `mid` being short. Two different silent sites, one page. This one is closed on
    // the evidence that it EXISTS, not on evidence that it has bitten -- which is the whole reason
    // an audit enumerates from the source instead of from the list of pages that happen to be red.
    if (mid.length < 2) {
      scanty++;
      drop(`only ${mid.length} strip scanline(s) found, needed 2 — the page paints no field-line ink the detector can pair`);
      continue;
    }
    // A capture that names no theme is skipped AND COUNTED, so the leg can say it did not run
    // for that file rather than passing it by default (law 23).
    const theme = captureTheme(f);
    if (theme === null) { unthemed++; drop('filename names no theme'); continue; }
    // `checked` IS INCREMENTED WHERE THE VERDICT IS REACHED, NOT HERE (M1-114). It used to count
    // every capture that named a theme, which made it a count of files opened rather than of grids
    // judged -- and M1-108 made that number the leg's DID-NOT-RUN floor, so it has to mean what it
    // says. A capture refused below for a dump that cannot answer, or for too few scanlines left on
    // a painted strip, is NOT a capture this leg checked.
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
    // THE DECLARATIONS THE PIXELS CANNOT CARRY, READ FROM THE DUMP BESIDE THIS CAPTURE (M1-92), AND
    // PAIRED BY POSITION RATHER THAN BY COUNTING. A scanline is a ROW of the rack and a row can carry
    // several strips side by side -- teams has seventeen strips across five rows -- so a scanline is
    // paired with every strip whose own rect contains it. The first version paired them by index and
    // the real run said so out loud: "dump describes 17 strips, the capture has 5". Both spaces are
    // pixels (the capture is at deviceScaleFactor 1, so image y is CSS y), which is what makes the
    // containment test exact rather than approximate.
    const dump = (() => { try { return JSON.parse(fs.readFileSync(path.join(ROOTREF.root, capturesDir, declName(f)), 'utf8')); } catch { return null; } })();
    const known = dump !== null && Array.isArray(dump.strips) ? dump.strips : null;
    if (known === null) undeclaredNoDump.push(f);
    // A DUMP WITHOUT `painted` CANNOT ANSWER THIS LEG'S QUESTION, AND SAYS SO (M1-114, law 23).
    // The anchor below is only as good as the claim "this scanline is a strip", and a dump written
    // before `painted` existed cannot make that claim -- every strip it lists might be clipped. So
    // such a capture is NAMED AND REFUSED rather than judged on the old, weaker pairing: that is
    // the difference between honouring a declaration and failing to look, which is the rule this
    // function's own header states. An EMPTY strips array is a different sentence and reaches the
    // same place by the insufficient-coverage path below -- settings' rack is not `.myx-strip` at
    // all (M1-92), so its dump enumerates zero strips and the leg must refuse it out loud rather
    // than report a clean grid over nothing.
    const declaresPaint = known !== null && known.every((s) => typeof s.painted === 'boolean' && typeof s.x === 'number');
    // A REFUSAL IS A DROP (M1-124). This one is correct and transitional -- it clears on the first
    // run that captures, because the PNG and its dump are written together -- but it still means
    // the page was not looked at, so it lands in the table rather than reading as a pass.
    if (known !== null && !declaresPaint) { staleDump.push(f); drop('dump predates `painted` and cannot say which scanlines are strips'); continue; }
    const rows = gridRows(im, mid, line, known);
    // OFF-STRIP SCANLINES ARE EXCLUDED AND NAMED, NOT SILENTLY DROPPED. This is the count that
    // would have told M1-110 what it was looking at in one line, so it is in the output whether or
    // not it changes the verdict: a rack that the leg read two extra rows of is a different claim
    // from one it read cleanly, and "159px" said neither.
    const off = rows.filter((r) => !r.onStrip).length;
    const onStrip = rows.filter((r) => r.onStrip && r.x !== undefined);
    if (off > 0) excluded.push(`${f}: ${off} of ${rows.length} scanline(s) sit on no painted strip`);
    if (onStrip.length < 2) {
      thin.push(`${f}: ${onStrip.length} of ${rows.length} scanline(s) left on a painted strip, needed at least 2`);
      drop(`${onStrip.length} of ${rows.length} scanline(s) left on a painted strip, needed 2`);
      continue;
    }
    checked++;
    const judged = judgeGrid(onStrip);
    if (judged.ok === undefined) {
      skippedDetail.push(`${f}: ${judged.detail}${honouredClause(judged)}`);
      drop(judged.detail);
      continue;
    }
    // JUDGED -- the one disposition that is not a drop. Recorded explicitly rather than inferred
    // from the absence of a drop, because "no drop recorded" is exactly the silence this row exists
    // to remove: a page the loop never reached at all would otherwise read as judged.
    drop(null);
    if (!judged.ok) bad.push(`${f}: ${judged.detail}${honouredClause(judged)}`);
    else if (judged.honoured.length > 0) honoured.push(`${f}: ${judged.honoured.join(', ')}`);
  }
  // AN ADDRESS WITH NO FILE ON DISK IS A DROP, NOT A HOLE IN THE AUDIT (M1-124, found by the
  // red-green on captureSet's wrong-room guard). This loop walks the DIRECTORY, so an address whose
  // capture was never written is never iterated and records nothing -- and `page-coverage` then
  // reports UNACCOUNTED, which is reserved for the audit losing track of a page it should have
  // seen. It had never shown, because all thirteen captures normally exist; forcing one address to
  // fail the capture leg made it visible at once. The distinction is the whole point of the table:
  // "this leg could not look because there was nothing to look at" is a stated reason, and
  // "nobody knows what this leg did" is a defect in the instrument.
  for (const a of expected ?? []) {
    if (!coverageOf('field-grid', a).known) covers('field-grid', a, `no capture on disk in ${capturesDir}`);
  }
  const detail = (bad.length ? bad.join(' · ') : `aligned on ${checked} captures`)
    + (honoured.length ? ` · honoured a declared span: ${honoured.join(' · ')}` : '')
    + (skipped ? ` · ${skipped} skipped (broken PNG or unsupported colour type)` : '')
    + (scanty ? ` · ${scanty} skipped (fewer than 2 strip scanlines found — see page-coverage for which)` : '')
    + (unthemed ? ` · ${unthemed} skipped (filename names no theme, so the wrong room could have been checked)` : '')
    + (undeclaredNoDump.length ? ` · ${undeclaredNoDump.length} capture(s) read with no declaration dump beside them, so nothing could be honoured by declaration: ${undeclaredNoDump.slice(0, 3).join(', ')}${undeclaredNoDump.length > 3 ? ', …' : ''}` : '')
    + (excluded.length ? ` · ${excluded.length} capture(s) with scanlines excluded as not-a-strip: ${excluded.slice(0, 3).join(', ')}${excluded.length > 3 ? ', …' : ''}` : '')
    + (staleDump.length ? ` · DID NOT RUN on ${staleDump.length} capture(s) whose dump predates \`painted\` and so cannot say which scanlines are strips — re-run the capture leg: ${staleDump.slice(0, 3).join(', ')}${staleDump.length > 3 ? ', …' : ''}` : '')
    + (thin.length ? ` · DID NOT RUN on ${thin.length} capture(s) with too few scanlines on a painted strip: ${thin.slice(0, 3).join(', ')}${thin.length > 3 ? ', …' : ''}` : '')
    + (skippedDetail.length ? ` · ${skippedDetail.join(' · ')}` : '')
    + (staleCount ? ` · ${staleCount} skipped (older than webui/src, so not evidence about this build)` : '');
  // THE SECOND EMPTY DENOMINATOR, and the one that survives a directory full of files: every
  // capture can be present and still unjudgeable (all stale, all unreadable, none naming a theme),
  // and this returned ok with the word `skipped`. A leg that looked at N files and judged none of
  // them has not checked the grid; it has reported that it could not. The counts are named because
  // the remedy differs — `0 of 19, 19 stale` means run a capture, `0 of 19, 19 skipped (broken PNG)`
  // means fix the environment, and a bare non-zero would have told the next seat neither (the
  // defect M1-76 found in exit-gate's runLeg, one level down).
  const coverage = expected === null
    ? ` · coverage unknown (${ADDRESSES_FILE}: no ADDRESSES table)`
    : ` · the set covers ${expected.filter((a) => files.some((f) => f.startsWith(`${a}-`))).length}/${expected.length} address(es)`;
  if (checked === 0) {
    return record('field-grid', true, false,
      `DID NOT RUN: judged 0 of ${files.length} capture(s) in ${capturesDir}, needed at least 1`
      + coverage + ' · ' + detail);
  }
  // THE INSUFFICIENT-COVERAGE CASE THIS ANCHOR OWES, AND IT REDS (M1-114, the condition
  // splice-design attached to reading the dump at all). Anchoring on painted strips buys exactness
  // by taking a dependency on the declaration dump, and a dependency that can be absent needs a
  // disposition that is not silence. A capture whose dump cannot say which scanlines are strips --
  // because it predates `painted`, or because it enumerates none at all, which is settings, whose
  // rack is not `.myx-strip` (M1-92) -- is a capture this leg DID NOT CHECK. It says so and it
  // fails, for M1-108's reason: on a blocking leg the words and the verdict must agree, and
  // "I could not look" has never been a pass.
  return record('field-grid', true, bad.length === 0 && staleDump.length === 0 && thin.length === 0, detail);
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
/**
 * WHICH CAPTURES ARE EVIDENCE ABOUT THIS BUILD (M1-94). Pure, and separated from the directory for
 * the same reason M1-92 lifted `judgeGrid` out of `checkFieldGrid`: a decision reachable only
 * through a folder of PNGs cannot be mutation-proved, and this one GATES a leg.
 *
 * The rule is its sibling's, twenty lines up: a capture older than the newest source it claims to
 * measure is not evidence about the current build. It is SKIPPED AND COUNTED, never judged.
 */
function partitionFresh(entries, bar) {
  const fresh = [], stale = [];
  for (const e of entries) (e.mtime < bar ? stale : fresh).push(e);
  return { fresh, stale };
}

/**
 * TONAL DRIFT, OVER THIS BUILD'S CAPTURES ONLY (M1-94).
 *
 * THE DEFECT THIS REPLACES: this leg read every PNG in the directory with no freshness bar of any
 * kind, while `checkFieldGrid` twenty lines up computed one and reported its skips. Measured on the
 * tree when the row was cut, the folder held 27 PNGs of which this build had produced 13, so the
 * leg's headline figure MIXED TWO BUILDS and presented them as one measurement.
 *
 * AND IT GATED ON THE MIXTURE, which is what makes it more than a wrong count: the pass condition
 * is `worst.ratio >= 0.5`, and `worst` was the single lowest-ratio capture across every build in
 * the folder. A stale capture from a previous build could fail this leg, and a stale GOOD capture
 * could hide a bad current one. Neither showed in the output, because the leg never said how many
 * captures it read or from when -- so its provenance is now part of its detail, always, pass or
 * fail. Three rows (M1-67, M1-35, M1-29) quoted this leg's numbers; they are mixtures.
 *
 * AN EMPTY FRESH SET IS NOT A PASS (law 23). When every capture is stale the leg says DID NOT RUN
 * and names the count, rather than returning ok on a denominator of nothing -- which is exactly how
 * a leg that silently drops its whole input reads identically to a leg that looked and approved.
 */
function checkTonalDrift(capturesDir, compPath) {
  // M1-108'S RULING, APPLIED TO THE LAST BRANCH THAT ESCAPED IT (M1-114). The two empty-denominator
  // exits below were made to fail; this one was not, and it is the same sentence: a leg that cannot
  // read its own reference has not measured drift against the comp, it has failed to measure drift
  // against the comp. `skipped` + ok=true is the M1-78 shape this file keeps finding elsewhere --
  // prose that says one thing while the verdict says another -- and the verdict is what is read.
  // THE PATH IS `ROOTREF.root` FOR THE REASON M1-108 CHANGED THE OTHER FOUR: `ROOT` is frozen at
  // load, so the selftest's temp-tree redirect never reached this line and this branch could not be
  // driven from a test at all. A branch no test can reach is how it kept the wrong verdict.
  const comp = pixels(path.join(ROOTREF.root, compPath));
  if (!comp) {
    // A WHOLE-LEG REFUSAL DROPS EVERY PAGE, and the table has to say so for each of them (M1-124).
    // Left silent, this early return is the one shape that makes the audit itself lie: no address
    // carries a disposition, so a reader asking "what did tonal-drift do with teams" gets the same
    // answer as if the loop had never been written.
    for (const a of addresses() ?? []) covers('tonal-drift', a, `comp unreadable at ${compPath}, so nothing was compared`);
    return record('tonal-drift', false, false, `DID NOT RUN: comp unreadable at ${compPath} (broken PNG, unsupported colour type, or the file moved) — nothing was compared`);
  }
  const ref = tonal(comp);
  const files = (() => { try { return fs.readdirSync(path.join(ROOTREF.root, capturesDir)).filter((f) => f.endsWith('.png')); } catch { return []; } })();
  const bar = freshnessBar();
  const stat = (f) => { try { return fs.statSync(path.join(ROOTREF.root, capturesDir, f)).mtimeMs; } catch { return 0; } };
  const { fresh, stale } = partitionFresh(files.map((f) => ({ f, mtime: stat(f) })), bar);
  const rows = [];
  let unreadable = 0;
  // PER-PAGE DISPOSITIONS (M1-124). This leg already named the addresses with NO fresh capture,
  // which is more than its sibling did -- but a capture that was fresh and then failed to decode
  // was only ever a number, so `read 12 of 13` never said which one went missing.
  const list = addresses();
  for (const { f } of stale) {
    const at = addressOf(f, list);
    if (at !== null) covers('tonal-drift', at, `capture is older than ${SRC}`);
  }
  for (const { f } of fresh) {
    const at = addressOf(f, list);
    const im = pixels(path.join(ROOTREF.root, capturesDir, f));
    if (!im) {
      unreadable++;
      if (at !== null) covers('tonal-drift', at, 'capture is broken or its colour type is unsupported');
      continue;
    }
    const t = tonal(im);
    if (at !== null) covers('tonal-drift', at, null);
    rows.push({ f, mid: t.mid, ratio: t.mid / ref.mid });
  }
  // The provenance rides on every outcome: a number with no denominator is what this row is about.
  // COVERAGE, AGAINST A DENOMINATOR FROM THE SOURCE (M1-94). A bar that skips most of the set is
  // not a bar, it is a filter -- so the leg must say what it covered, and the base for that can
  // never be the directory. The folder accumulates captures from every build that ever ran and
  // nothing prunes it, so "fresh / files-on-disk" sags as cruft grows and would read as declining
  // coverage while nothing had changed. The honest denominator is the ADDRESS LIST read from
  // webui/src/app/rows.ts -- the same list the capture leg enumerates: one fresh capture per
  // address, or the addresses that have none are named.
  const expected = addresses();
  const covered = (expected ?? []).filter((a) => rows.some((r) => r.f.startsWith(`${a}-`)));
  const missing = (expected ?? []).filter((a) => !rows.some((r) => r.f.startsWith(`${a}-`)));
  // The same backfill as its sibling: an address with no file on disk is a DROP with a stated
  // reason, never an UNACCOUNTED cell (M1-124).
  for (const a of expected ?? []) {
    if (!coverageOf('tonal-drift', a).known) covers('tonal-drift', a, `no capture on disk in ${capturesDir}`);
  }
  const provenance = `read ${rows.length} of ${files.length} capture(s)`
    + (expected === null ? ` · coverage unknown (${ADDRESSES_FILE}: no ADDRESSES table)`
       : ` · covering ${covered.length}/${expected.length} addresses`)
    + (missing.length ? ` · NO FRESH CAPTURE for ${missing.join(', ')}` : '')
    + (stale.length ? ` · ${stale.length} skipped (older than ${SRC}, so not evidence about this build)` : '')
    + (unreadable ? ` · ${unreadable} skipped (broken PNG or unsupported colour type)` : '');
  // DID NOT RUN IS NOT A PASS, and that is this file's own doctrine rather than an invention of
  // this row: the capture block below already states "a run that cannot capture is a DID NOT RUN
  // for every capture-reading check, never a pass -- law 23". A leg that read nothing reporting ok
  // is indistinguishable from a leg that looked and approved. This leg is NON-BLOCKING, so the
  // honest answer costs a visible `warn` and never the gate's exit code -- which is exactly the
  // case where there is no excuse for rounding an empty denominator up to a pass.
  if (rows.length === 0) return record('tonal-drift', false, false, `DID NOT RUN: ${provenance}`);
  // An address with no fresh capture is not a quiet omission: the leg would be reporting on a
  // console it has only partly seen, which is the difference between a measurement and an average.
  if (missing.length > 0) return record('tonal-drift', false, false, `INCOMPLETE: ${provenance}`);
  rows.sort((a, b) => a.ratio - b.ratio);
  const worst = rows[0];
  const detail = `comp mid-tone ${(ref.mid * 100).toFixed(1)}% · worst capture ${worst.f} at ${(worst.mid * 100).toFixed(1)}% `
    + `(${(worst.ratio * 100).toFixed(0)}% of the comp) · ${rows.filter((r) => r.ratio < 0.5).length}/${rows.length} captures below half the comp`
    + ` · ${provenance}`;
  return record('tonal-drift', false, worst.ratio >= 0.5, detail);
}

// ---------------------------------------------------------------- selftest (the mutation proof)


/** A synthetic one-row capture with two strips whose first field edge is at DIFFERENT x, drawn
 *  in `drawIn`'s theme colour while the detector is told the capture is `declareAs`. A correct
 *  detector FAILS on it (the edges disagree); the pre-M1-65 detector told 'light' while the ink
 *  is dark finds no edges at all and passes -- which is the bug, reproduced. */
/**
 * A SYNTHETIC RACK, DRAWN THE WAY THE PAGES ACTUALLY DRAW ONE (M1-114).
 *
 * Each entry is a row of strip paper with its field boundaries painted in the field-line ink --
 * and, as every real rack does, WITHOUT field 1's left boundary. That omission is not a shortcut in
 * the fixture: it is the measured fact that makes the leg compare FIELD 2's left edge (M1-115's DOM
 * reading of turns, 220/412/604/... with only 412 onward painted), and a fixture that painted field
 * 1's edge would be testing a rack this console does not render.
 *
 * The declarations carry the two things the pixels cannot: which bay a row belongs to, and whether
 * the renderer painted it at all. Both are what the live pages made impossible to test -- they move
 * under the seat reading them, which is how this row's acceptance changed three times in one
 * evening -- so every mechanism below is proved here instead.
 */
function rackProbe(spec, theme = 'dark') {
  const w = 1200, band = 24, gap = 8;
  const h = spec.length * (band + gap) + gap;
  const data = Buffer.alloc(w * h * 3, 0);
  const line = fieldLine(theme).map(Math.round);
  const known = [];
  spec.forEach((row, n) => {
    const top = gap + n * (band + gap);
    for (let y = top; y < top + band; y++) {
      for (let x = row.left; x < w - 20; x++) {
        const i = (y * w + x) * 3;
        data[i] = 222; data[i + 1] = 217; data[i + 2] = 198;
      }
      for (const x of row.edges) {
        const i = (y * w + x) * 3;
        [data[i], data[i + 1], data[i + 2]] = line;
      }
    }
    known.push({
      y: top, h: band, x: row.left, w: w - 20 - row.left, bay: row.bay,
      painted: row.painted !== false,
      fields: [{ label: row.label ?? 'f1', span: null, w: row.edges[0] - row.left }],
    });
  });
  return { im: { w, h, data }, known, line };
}

/** A REAL PNG, ENCODED HERE RATHER THAN CAPTURED (M1-114), so the coverage refusals below can be
 *  driven through `checkFieldGrid` over a directory -- decoder, dump read and `record` included --
 *  instead of over a hand-made pixel object. Two new conditions RED the gate in this row, and a new
 *  red that no test can reach is the decorative check this campaign keeps cataloguing. Colour type
 *  2 (RGB), filter 0 on every row: the smallest encoder `lib/png.mjs` reads. */
function encodePng(im) {
  const chunk = (type, body) => {
    const len = Buffer.alloc(4); len.writeUInt32BE(body.length);
    const tagged = Buffer.concat([Buffer.from(type, 'ascii'), body]);
    const crc = Buffer.alloc(4); crc.writeUInt32BE(zlib.crc32(tagged));
    return Buffer.concat([len, tagged, crc]);
  };
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(im.w, 0); ihdr.writeUInt32BE(im.h, 4);
  ihdr[8] = 8; ihdr[9] = 2;                                   // 8 bits per channel, truecolour
  const stride = 1 + im.w * 3;
  const raw = Buffer.alloc(im.h * stride);
  for (let y = 0; y < im.h; y++) im.data.copy(raw, y * stride + 1, y * im.w * 3, (y + 1) * im.w * 3);
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr), chunk('IDAT', zlib.deflateSync(raw)), chunk('IEND', Buffer.alloc(0)),
  ]);
}

/** A capture directory the way the gate writes one: a PNG per address and the declaration dump
 *  beside it, under a root the selftest can point ROOTREF at. `strips` is written verbatim so a
 *  case can ship a dump that predates `painted`, or one that enumerates none at all. */
function seedCaptures(tmp, addresses, { im, strips }) {
  fs.mkdirSync(path.join(tmp, 'caps'), { recursive: true });
  fs.mkdirSync(path.join(tmp, 'webui/src/app'), { recursive: true });
  fs.writeFileSync(path.join(tmp, 'webui/src/app/rows.ts'),
    `export const ADDRESSES = [${addresses.map((a) => `'${a}'`).join(', ')}] as const;\n`);
  const png = encodePng(im);
  for (const a of addresses) {
    fs.writeFileSync(path.join(tmp, 'caps', `${a}-dark-1536x1024.png`), png);
    fs.writeFileSync(path.join(tmp, 'caps', `${a}-dark-1536x1024.decl.json`),
      JSON.stringify({ url: `#/${a}`, frame: [im.w, im.h], strips }));
  }
}

/** The leg's own pipeline over a synthetic rack: scanlines, rows, exclusions, verdict. Mirrors
 *  checkFieldGrid's body so a case proves what the gate does, not what a helper does. */
function rackVerdict(spec, label) {
  const { im, known, line } = rackProbe(spec);
  const mid = stripScanlines(im, 200, Math.min(1100, im.w - 20));
  const rows = gridRows(im, mid, line, known);
  const on = rows.filter((r) => r.onStrip && r.x !== undefined);
  const off = rows.length - on.length;
  findings.length = 0;
  if (on.length < 2) {
    record('field-grid', true, false, `DID NOT RUN: ${label} — ${on.length} of ${rows.length} scanline(s) on a painted strip`);
    return findings[0];
  }
  const judged = judgeGrid(on);
  record('field-grid', true, judged.ok === true,
    `${label} — ${judged.detail}${off ? ` · ${off} scanline(s) excluded as not-a-strip` : ''}`);
  return findings[0];
}

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


// ------------------------------------------------- the captures this gate judges (M1-68)

/** THE GATE PRODUCES WHAT IT JUDGES. It read `webui/.impeccable/review/sections`, a directory it
 *  did not produce and nothing tracked (gitignored): measured 2026-09-18, the twelve PNGs there
 *  were dated 03:31-04:19 against a 12:25 run -- eight hours and about forty commits stale,
 *  including every tonal, rail, rung and token change landed in between. So every tonal and
 *  grid finding this gate printed all night described a build that no longer existed, in the
 *  confident voice of a measurement.
 *  THE SET, decided and recorded rather than taken cheaply. THIRTEEN ADDRESSES x DARK x
 *  1536x1024, because that is what the twelve stale files were trying to be (the comp of record
 *  is a 1536x1024 dark frame) and because it is the set every other instrument in this campaign
 *  is calibrated at. LIGHT IS EXCLUDED ON EVIDENCE, not on cost: law 32, every light finding
 *  produced before 12:40 on 2026-09-18 is suspect because look.mjs named and printed a theme
 *  its browser sessions never seeded, and seeding a state and rendering it are different
 *  claims. 3840 IS EXCLUDED ON COST: it quadruples the runtime for a frame the comp is not
 *  drawn at, and the exit gate runs this on every milestone. The address list is read from the
 *  shell's own table for gate.mjs's own reason -- a fourteenth address must appear here without
 *  an edit, and a list written out in this file cannot fail for one missing from itself. */
const ADDRESSES_FILE = 'webui/src/app/rows.ts';
const CAPTURE_THEME = 'dark';
const CAPTURE_FRAME = [1536, 1024];

/** THE DECLARATIONS, READ FROM THE SAME RENDER THE PNG COMES FROM (M1-92). Per strip, in DOM order,
 *  every field's label, its declared track span (`data-span`, absent when it is one track) and its
 *  laid-out width. The strip order is the pairing key the field-grid leg uses, which is why the
 *  count of strips is written too: a dump that describes a different number of strips than the
 *  capture has cannot be paired, and saying so beats pairing it wrongly.
 *
 *  `x`/`w` AND `painted` ARE M1-114'S, AND `painted` IS THE WHOLE ROW. A rect is not evidence that
 *  anything was drawn at it. `getBoundingClientRect()` answers for every RENDERED element, and a
 *  virtualized rack renders items its own scroll container then CLIPS -- so logs, in follow mode,
 *  declares fifteen strips of which the capture paints eleven. Measured: strips #0..#3 (y 6..262)
 *  sit where the logs capture is dark or is covered by `.myx-lt-head`, and #4..#14 coincide exactly
 *  with the pale bands the pixels show. The leg paired a scanline to a strip by y-containment, so
 *  the header's two scanlines landed inside CLIPPED strip #3 (198..262, spanning the header at
 *  203..283) and came back `paired: true` -- the dump said "this is a strip" about a row that is
 *  not one, and the leg then read the header's own box border as that row's first field edge.
 *  `elementFromPoint` is the exact question the rect cannot answer: it returns what is actually
 *  painted at a coordinate, so clipping, overlap and z-order are all decided by the renderer rather
 *  than re-derived here. A strip counts as painted when the element at its own midpoint is the
 *  strip or lives inside it. */
const DECLARATIONS = `(() => {
  const strips = [...document.querySelectorAll('.myx-strip')];
  const bays = [...document.querySelectorAll('.myx-bay')];
  return JSON.stringify({
    url: location.hash,
    frame: [window.innerWidth, window.innerHeight],
    strips: strips.map((s) => {
      const r = s.getBoundingClientRect();
      const cx = r.left + r.width / 2, cy = r.top + r.height / 2;
      const on = cx >= 0 && cy >= 0 && cx < window.innerWidth && cy < window.innerHeight;
      const hit = on ? document.elementFromPoint(cx, cy) : null;
      return {
        y: Math.round(r.top), h: Math.round(r.height),
        x: Math.round(r.left), w: Math.round(r.width),
        bay: bays.indexOf(s.closest('.myx-bay')),
        painted: hit !== null && hit.closest('.myx-strip') === s,
        fields: [...s.querySelectorAll('.myx-sfield')].map((f) => {
          const label = f.querySelector('.myx-sfield-label');
          return { label: label === null ? null : label.textContent.trim(),
            span: f.getAttribute('data-span'),
            w: Math.round(f.getBoundingClientRect().width) };
        }),
      };
    }),
  });
})()`;

/** ELEVEN OF THIRTEEN PAGES SHIP A DESIGN FIXTURE AND THE GATE NEVER ASKED FOR ONE (M1-83). Its
 *  addresses were bare route names, so `#/teams` rendered NO TEAMS ROUTE / V4-131 PENDING over an
 *  empty rack - the very page the comp is drawn of, judged as a blank. The map lives HERE rather
 *  than in the shell's ADDRESSES table because the gate owns which fixture each address needs, the
 *  same way it owns the frame size, and because that table is not this row's to edit.
 *  THE NAMES ARE NOT THE PAGE NAMES: logs ships `tail`, projects `list`, sessions and turns `board`.
 *  A map derived from the address would have loaded nothing on four pages and looked like it worked.
 *  fleet and mcp ship none and are captured bare, which is honest - and the refusal below is what
 *  stops a bare capture from passing as a content plane. */
const FIXTURES = {
  accounts: 'accounts', compaction: 'compaction', doctor: 'doctor', logs: 'tail',
  models: 'models', projects: 'list', sessions: 'board', settings: 'settings',
  teams: 'hero', turns: 'board', usage: 'usage',
};

/** The address as the browser must receive it. The fixture rides in BOTH the search and the hash
 *  because the pages are split on which they read (`useLocation().search` against the raw
 *  `window.location.search`), and the shell canonicalises one of them. Whichever it drops, the
 *  other survives; a page that reads neither is caught by the refusal below rather than passing. */
function captureUrl(address) {
  const fixture = FIXTURES[address];
  if (fixture === undefined) return `http://localhost:5173/#/${address}`;
  return `http://localhost:5173/?fixture=${fixture}#/${address}?fixture=${fixture}`;
}

function addresses() {
  const src = readIf(ADDRESSES_FILE);
  if (src === null) return null;
  const block = src.match(/export const ADDRESSES = \[([\s\S]*?)\] as const;/);
  if (block === null) return null;
  return [...block[1].matchAll(/'([a-z0-9-]+)'/g)].map((m) => m[1]);
}

/** Capture the set into CAPTURES with the gate's own `<address>-<theme>-<w>x<h>.png` naming,
 *  immediately before judging it. Returns the count and the measured runtime. */
/**
 * THE BOOT AXES (M1-106): the states the app is built around that no CAPTURE instrument could
 * reach, because every one of them seeded the management key before boot.
 *
 * M1-100 established six of them and M1-80 found the cost: `features/unlock-mgmt` renders its
 * modal ONLY when the console has no management key, so `.myx-modal-title`, `.myx-modal-field-label`
 * and two siblings could not have rendered in ANY capture ever taken here. Not dead rules and not
 * missing fixtures - a fixture that was always seeded past. The ink instrument already drives this
 * axis (exercise.mjs:355-356 seeds `keyed` and `unkeyed`), so THE AXIS EXISTED AND ONLY THE CAMERA
 * LACKED IT: an instrument measured the surface while no picture of it existed, which is the gap
 * this leg closes.
 *
 * IT IS A SEPARATE LEG AND A SEPARATE DIRECTORY ON PURPOSE. `checkFieldGrid` reads every PNG in the
 * captures directory, so an unkeyed frame - a modal over a page - would be collected as a rack row
 * and would red a grid leg it has nothing to say about. An axis is evidence about a surface.
 *
 * WHAT IT ASSERTS IS THE OPPOSITE OF THE KEYED PASS, and that is the point rather than a detail.
 * The keyed capture refuses a frame whose fixture marker did not load; on this axis the fixture
 * CANNOT load, because the console never gets far enough to ask for it. So the rule inverts: a
 * frame is written only when the console has put the unlock surface on the glass, by name.
 */
async function captureAxes(dir) {
  const list = addresses();
  if (list === null) return { ok: false, detail: `${ADDRESSES_FILE}: the ADDRESSES table was not found` };
  fs.mkdirSync(path.join(ROOTREF.root, dir), { recursive: true });
  const [w, h] = CAPTURE_FRAME;
  const started = Date.now();
  const wrong = [];
  let wrote = 0;
  try {
    // NO KEY IS SEEDED. This is the whole axis: the console boots without one and must answer with
    // the unlock surface rather than a blank, and nothing else in this file withholds it.
    await withChrome({ 'splice.theme': CAPTURE_THEME }, async (send) => {
      for (const address of list) {
        const file = path.join(ROOTREF.root, dir, `${address}-unkeyed-${CAPTURE_THEME}-${w}x${h}.png`);
        // The fixture still rides in the URL: a page that never gets its key renders the unlock
        // modal, and asking for the fixture anyway is what makes this the SAME address in the one
        // state that differs, rather than a different address that also happens to be unkeyed.
        await show(send, captureUrl(address), w, h);
        const seen = await send('Runtime.evaluate', { returnByValue: true, expression: `(() => {
          const scrim = document.querySelector('.myx-modal-scrim[role="dialog"]');
          const title = document.querySelector('.myx-modal-title');
          return JSON.stringify({
            unlock: scrim !== null,
            label: scrim === null ? null : (scrim.getAttribute('aria-label') || ''),
            title: title === null ? null : title.textContent.trim(),
            planes: document.querySelectorAll('.myx-bay, .myx-strip, .myx-scope, .myx-fbox').length,
          });
        })()` });
        const got = JSON.parse(seen.result.value);
        if (!got.unlock) {
          wrong.push(`${address} (no unlock dialog: planes=${got.planes})`);
          covers('boot-axes', address, 'rendered no unlock dialog without a key');
          continue;
        }
        await shoot(send, file);
        wrote++;
      }
    });
  } catch (e) { return { ok: false, detail: `axis capture failed after ${wrote} file(s): ${e.message}` }; }
  const ms = Date.now() - started;
  if (wrong.length > 0) return { ok: false, detail: `${wrong.length} address(es) rendered no unlock dialog while unkeyed: ${wrong.join(', ')}` };
  if (wrote !== list.length) return { ok: false, detail: `captured ${wrote} of ${list.length}` };
  return { ok: true, count: wrote, ms,
    detail: `unkeyed (no myx-mgmt-key) x ${CAPTURE_THEME} x ${w}x${h}: ${wrote} captures in ${(ms / 1000).toFixed(1)}s · the unlock surface, which no keyed capture can reach` };
}

async function captureSet(dir) {
  const list = addresses();
  if (list === null) return { ok: false, detail: `${ADDRESSES_FILE}: the ADDRESSES table was not found` };
  fs.mkdirSync(path.join(ROOTREF.root, dir), { recursive: true });
  const [w, h] = CAPTURE_FRAME;
  const started = Date.now();
  const blanks = [];
  const wrongRoom = [];
  let wrote = 0;
  // Every address starts as NOT ATTEMPTED. A page the loop never reaches -- because the browser
  // threw halfway, which is the `catch` below -- keeps this disposition, so an aborted run says
  // which pages it never got to instead of reporting a total (M1-124).
  for (const a of list) covers('capture-set', a, 'not attempted (the capture run ended first)');
  try {
    await withChrome({ 'myx-mgmt-key': mgmtKey(), 'splice.theme': CAPTURE_THEME }, async (send) => {
      for (const address of list) {
        const file = path.join(ROOTREF.root, dir, `${address}-${CAPTURE_THEME}-${w}x${h}.png`);
        // THE FIXTURE RIDES IN THE URL (M1-83), through the one helper that decides how an address
        // is addressed: eleven of thirteen pages ship a design fixture and a bare `#/teams` renders
        // NO TEAMS ROUTE over an empty rack - the very page the comp is drawn of, judged as a blank
        // and photographed as one. `captureUrl` was written beside `FIXTURES` and never called, so
        // the map only fed the refusal message: the gate could say a fixture HAD NOT loaded and
        // could not ask for one. The refusal below is what proves this call did its job.
        await show(send, captureUrl(address), w, h);
        // PROVE THE ROOM TOOK before trusting the filename: law 32 -- look.mjs named and printed
        // a theme its sessions never seeded, and seeding a state is not rendering it. The page's
        // own background is read back from the render, and a capture that did not come back in
        // the room it claims is not written at all.
        const room = await send('Runtime.evaluate', { returnByValue: true, expression: 'getComputedStyle(document.documentElement).colorScheme' });
        // `continue`, NOT `return` -- AND THE DIFFERENCE IS TWELVE PAGES (M1-124). This was a bare
        // `return`, and it does not return from the per-address step: it returns from the
        // `withChrome` callback, ABORTING THE LOOP. One page coming back in the wrong room meant
        // every address after it was never attempted, and all of them were reported as the single
        // set-level line `captured N of M (the room did not take)` -- a count with no names, for
        // pages that were never even asked. Every sibling refusal in this loop already used
        // `continue` and named its address; this one was the odd one out, and it is the largest
        // silent per-page drop in the file.
        if (room.result.value !== CAPTURE_THEME) {
          wrongRoom.push(`${address} (asked for ${CAPTURE_THEME}, got ${room.result.value})`);
          covers('capture-set', address, `page rendered in ${room.result.value}, not ${CAPTURE_THEME}`);
          continue;
        }
        // A BLANK CAPTURE PASSES EVERY GEOMETRIC CHECK SILENTLY (M1-83): a field grid holds
        // perfectly across zero strips, so twelve empty rack frames scored as a clean grid all
        // night. So the gate asks the page what it actually put on the glass, and refuses to write
        // a frame that cannot answer. `data-sample` is the marker the page carries when its fixture
        // loaded (M1-20); a page that ships no fixture declares that instead of being assumed
        // empty, which is the difference between a named absence and an unnoticed one.
        const content = await send('Runtime.evaluate', {
          returnByValue: true,
          expression: `(() => {
            const carrier = document.querySelector('[data-sample]');
            const planes = document.querySelectorAll('.myx-bay, .myx-strip, .myx-scope, .myx-fbox').length;
            return JSON.stringify({
              sample: carrier === null ? null : carrier.getAttribute('data-sample'),
              planes,
              body: document.body.innerText.trim().length,
            });
          })()`,
        });
        const seen = JSON.parse(content.result.value);
        const wanted = FIXTURES[address];
        // THE MARKER IS THE PROOF, AND A PLANE COUNT IS NOT. The first version of this refusal asked
        // `planes === 0`, and it could not have caught the page the row is about: the blank hero
        // renders NO TEAMS ROUTE / V4-131 PENDING over an EMPTY RACK, so it has .myx-bay planes and
        // a body full of words, and it would have passed. `data-sample` is the marker a page carries
        // when its fixture loaded (M1-20), so a page that ships a fixture and does not carry it is
        // refused BY NAME whatever it drew; the two fixtureless pages are judged on content alone,
        // and that difference is stated here rather than implied by a threshold.
        const blank = wanted === undefined ? seen.planes === 0 : seen.sample !== wanted;
        if (blank) {
          blanks.push(`${address}${wanted === undefined ? '' : ` (fixture=${wanted} did not load: sample=${seen.sample})`}`);
          covers('capture-set', address, wanted === undefined ? 'no content plane' : `fixture=${wanted} did not load (sample=${seen.sample})`);
          continue;
        }
        await shoot(send, file);
        // THE MARKUP THE PIXELS CANNOT CARRY, WRITTEN BESIDE THEM (M1-92). `StripField` declares a
        // spanning field as `data-span="2"` and on the glass that is indistinguishable from a first
        // field that merely happens to be wide -- which is why the field-grid leg reported compaction
        // at 285px and was RIGHT: the number is a true statement about pixels. The dump is the second
        // input that lets the leg read the declaration instead of inferring intent from width, and it
        // is written at the same moment as the PNG from the same render, so the two cannot describe
        // different builds. Read by `checkFieldGrid` through `judgeGrid`.
        const decl = await send('Runtime.evaluate', { returnByValue: true, expression: DECLARATIONS });
        if (decl.result && decl.result.value !== undefined) {
          fs.writeFileSync(path.join(ROOTREF.root, dir, declName(path.basename(file))), decl.result.value);
        }
        covers('capture-set', address, null);
        wrote++;
      }
    });
  } catch (e) {
    return { ok: false, detail: `capture failed after ${wrote} file(s): ${e.message}` };
  }
  const ms = Date.now() - started;
  if (blanks.length > 0) {
    // NAMED, not counted: which address, and whether its fixture failed to load.
    return { ok: false, detail: `${blanks.length} capture(s) refused with no content plane: ${blanks.join(', ')}` };
  }
  // NAMED, not counted: this used to be `captured N of M (the room did not take)` for pages that,
  // after the `return` above, had never been asked at all (M1-124).
  if (wrongRoom.length > 0) {
    return { ok: false, detail: `${wrongRoom.length} capture(s) came back in the wrong room: ${wrongRoom.join(', ')}` };
  }
  if (wrote !== list.length) {
    const missed = list.filter((a) => coverageOf('capture-set', a).why !== null);
    return { ok: false, detail: `captured ${wrote} of ${list.length} — not written: ${missed.join(', ')}` };
  }
  // WHAT EACH CAPTURE ASKED FOR, printed rather than implied: the strengthened verify greps for
  // `fixture=hero` precisely because the old one passed on thirteen blank pages, so the set must
  // say which fixtures it loaded rather than that it loaded thirteen files.
  const loaded = list.map((a) => (FIXTURES[a] === undefined ? `${a} (no fixture)` : `${a}=${FIXTURES[a]}`));
  return { ok: true, count: wrote, ms,
    detail: `${wrote} captures in ${(ms / 1000).toFixed(1)}s (${(ms / wrote / 1000).toFixed(1)}s each)`
      + ` · fixtures: ${loaded.join(' ')}` };
}

/**
 * ---- THE TABLE (M1-124): EVERY ADDRESS-SCOPED LEG, EVERY PAGE, JUDGED OR DROPPED AND WHY ----
 *
 * THE DENOMINATOR IS TAKEN FROM TWO SOURCES AND NEITHER IS THIS FUNCTION. The addresses come from
 * the ADDRESSES table in webui/src/app/rows.ts, the same list the capture leg enumerates; the legs
 * come from the list below, which is the set of legs that READ CAPTURES. §24's rule is that a check
 * whose denominator comes from the same list it checks cannot fail for anything absent from that
 * list, so neither half is written out here as a literal count.
 *
 * WHICH LEGS ARE IN SCOPE, AND WHY THE OTHER FIVE ARE NOT. `ladder-steps`, `type-distribution`,
 * `spacing-distribution`, `no-type-transform` and `absence-vocabulary` read the SOURCE TREE -- a
 * glob of .css files, or a directory walk -- and never enumerate addresses at all. A page cannot be
 * dropped from a leg that has no notion of pages, so those five are out of scope BY A STATED
 * REASON rather than by omission, which is the same distinction this table exists to draw.
 *
 * WHAT IT ASSERTS, AND WHAT IT DELIBERATELY DOES NOT. It fails ONLY when an address has no
 * disposition from a leg that should have given it one -- a hole in the audit itself. It does NOT
 * fail because a page was dropped: settings' rack is not `.myx-strip` (M1-92) and teams paints no
 * field-line ink, and both are legitimate drops that belong in the open rather than in a counter.
 * Adding a floor here would redden pages this row was explicitly told not to redden, and would also
 * be the wrong instrument: the fixes are page-side and live on other rows.
 */
const PAGE_LEGS = ['capture-set', 'field-grid', 'tonal-drift'];

function checkPageCoverage() {
  const list = addresses();
  if (list === null) {
    return record('page-coverage', false, false,
      `DID NOT RUN: ${ADDRESSES_FILE} carries no ADDRESSES table, so there is no denominator to audit against`);
  }
  const holes = [];
  const lines = [];
  for (const a of list) {
    const cells = PAGE_LEGS.map((leg) => {
      const { known, why } = coverageOf(leg, a);
      if (!known) { holes.push(`${leg}/${a}`); return `${leg}=UNACCOUNTED`; }
      return why === null ? `${leg}=judged` : `${leg}=DROPPED (${why})`;
    });
    lines.push(`    ${a.padEnd(11)} ${cells.join(' · ')}`);
  }
  const dropped = list.filter((a) => PAGE_LEGS.some((leg) => coverageOf(leg, a).why !== null));
  const head = `${list.length} address(es) x ${PAGE_LEGS.length} address-scoped leg(s) = ${list.length * PAGE_LEGS.length} dispositions`
    + `, ${holes.length} unaccounted`
    + ` · ${dropped.length} page(s) dropped by at least one leg${dropped.length ? `: ${dropped.join(', ')}` : ''}`
    + ` · the other legs read the source tree and enumerate no addresses, so no page can be dropped from them`
    + (holes.length ? ` · UNACCOUNTED: ${holes.join(', ')}` : '');
  return record('page-coverage', false, holes.length === 0, `${head}\n${lines.join('\n')}`);
}

/** THE FRESHNESS RULE, and it outlives this row. A check that reads an artifact it did NOT
 *  produce must compare that artifact's mtime against the thing it claims to measure and say
 *  DID NOT RUN when the artifact is older. The build leg in exit-gate.mjs has done exactly this
 *  for dist/index.html since it was written ("dist/index.html is older than this run: the build
 *  did not rewrite it"); the captures never got the same treatment. Here the thing they claim
 *  to measure is the console's own source, so the newest mtime under webui/src is the bar. */
function newestSourceMtime() {
  let newest = 0;
  const walk = (d) => {
    let ents; try { ents = fs.readdirSync(path.join(ROOTREF.root, d), { withFileTypes: true }); } catch { return; }
    for (const e of ents) {
      const rel = path.join(d, e.name);
      if (e.isDirectory()) walk(rel);
      else if (/\.(css|tsx|ts)$/.test(e.name)) {
        try { const m = fs.statSync(path.join(ROOTREF.root, rel)).mtimeMs; if (m > newest) newest = m; } catch { /* unreadable */ }
      }
    }
  };
  walk(SRC);
  return newest;
}
/**
 * ---- THE BAR IS SAMPLED ONCE, BEFORE THE RUN, AND THE RULE'S OWN WORDS SAY WHY ----
 *
 * `newestSourceMtime()` was called by each leg as it ran, which is AFTER `captureSet` has finished
 * writing. In a shared worktree that is a race, and it is not theoretical: measured on 2026-09-18,
 * another seat wrote `webui/src/pages/settings/settings.css` at 10:57:07 during a 79-second capture
 * run, and the five addresses captured before that instant -- fleet 10:56:41 through projects
 * 10:57:06 -- were all reported DROPPED as older than their own source, with the cut landing
 * exactly on the write. They had captured perfectly well. The leg said `aligned on 7 captures`
 * and the coverage table said five pages were dropped, and both were artefacts of a moving bar.
 *
 * WHY SAMPLING EARLIER IS THE FIX AND NOT JUST THE CONVENIENT CHOICE: the rule above states its own
 * scope in its first line -- "a check that reads an artifact it did NOT produce". Captures written
 * by this run are artifacts the gate DID produce. The freshness rule was written for the leftovers
 * of an EARLIER build sharing the directory, which is real and still caught: those are older than a
 * bar taken before this run starts. Applying it to the run's own output was a category error, and
 * the shared worktree only made it visible.
 *
 * AND THE RACE IS NAMED RATHER THAN SMOOTHED. A bar frozen at the start makes the run's captures
 * survive, but if the source really did move mid-run then the set is internally inconsistent --
 * early frames show the old console and late ones the new. That is worth knowing and it is exactly
 * the kind of thing that reads as a quiet number otherwise, so the second sample is kept and the
 * difference is reported. Freezing the bar without saying the tree moved would trade a false
 * DROPPED for a silent inconsistency, which is the worse of the two.
 */
const BAR = { at: null, movedTo: null };
/** The bar every freshness question is asked against. Frozen on first call -- which the run does
 *  before `captureSet` -- so every leg downstream asks the same question. */
function freshnessBar() {
  if (BAR.at === null) BAR.at = newestSourceMtime();
  return BAR.at;
}

function staleCaptures(dir) {
  let files; try { files = fs.readdirSync(path.join(ROOTREF.root, dir)).filter((f) => f.endsWith('.png')); } catch { return null; }
  if (files.length === 0) return { files: [], stale: 0, oldest: null };
  const bar = freshnessBar();
  let stale = 0, oldest = Infinity, oldestName = null;
  for (const f of files) {
    let m; try { m = fs.statSync(path.join(ROOTREF.root, dir, f)).mtimeMs; } catch { continue; }
    if (m < oldest) { oldest = m; oldestName = f; }
    if (m < bar) stale++;
  }
  return { files, stale, oldest, oldestName, bar };
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
    // ---- THE FRESHNESS BAR ON TONAL-DRIFT (M1-94). The leg read every PNG in the directory with
    // no bar at all while its sibling twenty lines up had one, so its headline mixed builds AND
    // gated on the mixture. These cases prove the partition can bite, that it keeps what it should,
    // and -- the one that matters -- that an all-stale directory cannot be rounded up to a pass.
    // Measured when this row was cut: 27 PNGs in the sections directory, ZERO of them fresh.
    ['tonal-drift', () => {
      findings.length = 0;
      const { fresh, stale } = partitionFresh(
        [{ f: 'old.png', mtime: 100 }, { f: 'new.png', mtime: 300 }], 200);
      record('tonal-drift', false, fresh.length === 1 && stale.length === 1 && fresh[0].f === 'new.png',
        `partition kept ${fresh.map((e) => e.f).join(',') || '(none)'} and skipped ${stale.map((e) => e.f).join(',') || '(none)'}`);
      return findings[0];
    }, true],
    ['tonal-drift', () => {
      findings.length = 0;
      // EVERY capture older than the bar: the state the tree was actually in.
      const { fresh, stale } = partitionFresh(
        [{ f: 'a.png', mtime: 10 }, { f: 'b.png', mtime: 20 }], 999);
      // a partition that kept nothing must be reported as nothing, never as a clean sweep
      record('tonal-drift', false, fresh.length === 0 && stale.length === 2,
        `all ${stale.length} captures stale, ${fresh.length} readable -- DID NOT RUN`);
      return findings[0];
    }, true],
    ['tonal-drift', () => {
      findings.length = 0;
      // THE MUTATION: a bar of 0 is the leg as it was written -- no bar -- and it must NOT
      // partition anything away, which is precisely how two builds got averaged into one number.
      const { fresh, stale } = partitionFresh(
        [{ f: 'stale-from-a-previous-build.png', mtime: 1 }], 0);
      record('tonal-drift', false, stale.length > 0,
        `with no bar the leg keeps ${fresh.length} stale capture(s) and skips ${stale.length} -- the defect, reproduced`);
      return findings[0];
    }, false],
    // ---- THE COVERAGE FLOOR (M1-94). The denominator is the address list, never the directory:
    // the folder keeps every capture any build ever wrote, so a fraction over it sags as cruft
    // grows. These two prove the floor names what is missing, and that a full sweep clears it.
    ['tonal-drift', () => {
      findings.length = 0;
      const expected = ['fleet', 'turns', 'sessions'];
      const read = [{ f: 'fleet-dark-1536x1024.png' }, { f: 'turns-dark-1536x1024.png' }];
      const missing = expected.filter((a) => !read.some((r) => r.f.startsWith(`${a}-`)));
      record('tonal-drift', false, missing.length === 0,
        `covering ${expected.length - missing.length}/${expected.length} addresses`
        + (missing.length ? ` · NO FRESH CAPTURE for ${missing.join(', ')}` : ''));
      return findings[0];
    }, false],
    ['tonal-drift', () => {
      findings.length = 0;
      const expected = ['fleet', 'turns'];
      const read = [{ f: 'fleet-dark-1536x1024.png' }, { f: 'turns-dark-1536x1024.png' }];
      const missing = expected.filter((a) => !read.some((r) => r.f.startsWith(`${a}-`)));
      record('tonal-drift', false, missing.length === 0, `covering ${expected.length}/${expected.length} addresses`);
      return findings[0];
    }, true],
    // ---- THE FIELD DETECTOR, IN BOTH THEMES (M1-65). The light case was NEVER exercised:
    // the colour was the dark block's hex, so a light capture found no edges and the rule
    // passed by finding nothing. These two cases are the mutation proof that it can now fail
    // in the light room as well as the dark one, and that it still refuses the other room's
    // colour -- which is the exact defect, reproduced as a test rather than argued.
    // ---- DID NOT RUN IS A FAILURE (M1-108), proven in BOTH directions on the REAL function against
    // a REAL directory, not on a stub. The two emptinesses are separate branches with separate
    // remedies, so each gets its own case: a directory with no PNG at all, and a directory FULL of
    // PNGs none of which can be judged. The third case is the one that makes the other two mean
    // something — a set that judges cleanly must still PASS, because a leg that fails on everything
    // is not a gate either. All three drive checkFieldGrid through ROOTREF, which is why the
    // readdir in that function had to stop using ROOT: it was the one reader the redirect could not
    // reach, so this proof was unreachable until it did.
    ['field-grid', () => {
      findings.length = 0;
      const tmp = fs.mkdtempSync('/tmp/lookgate-empty-');
      fs.mkdirSync(path.join(tmp, 'caps'), { recursive: true });
      fs.mkdirSync(path.join(tmp, 'webui/src/app'), { recursive: true });
      fs.writeFileSync(path.join(tmp, 'webui/src/app/rows.ts'),
        "export const ADDRESSES = ['fleet', 'turns', 'sessions'] as const;\n");
      const saved = ROOTREF.root; ROOTREF.root = tmp;
      checkFieldGrid('caps'); ROOTREF.root = saved;
      fs.rmSync(tmp, { recursive: true, force: true });
      return findings[0];
    }, false],
    ['field-grid', () => {
      findings.length = 0;
      const tmp = fs.mkdtempSync('/tmp/lookgate-unjudgeable-');
      fs.mkdirSync(path.join(tmp, 'caps'), { recursive: true });
      // Present, named for a real address and theme, and not a PNG any decoder will read: the
      // `files.length` branch is satisfied and the `checked` branch is the one under test.
      for (const a of ['fleet', 'turns', 'sessions']) {
        fs.writeFileSync(path.join(tmp, 'caps', `${a}-dark-1536x1024.png`), 'not an image');
      }
      fs.mkdirSync(path.join(tmp, 'webui/src/app'), { recursive: true });
      fs.writeFileSync(path.join(tmp, 'webui/src/app/rows.ts'),
        "export const ADDRESSES = ['fleet', 'turns', 'sessions'] as const;\n");
      const saved = ROOTREF.root; ROOTREF.root = tmp;
      checkFieldGrid('caps'); ROOTREF.root = saved;
      fs.rmSync(tmp, { recursive: true, force: true });
      return findings[0];
    }, false],
    // ---- THE TWO CASES THIS ROW TURNS ON (M1-114). The leg's rule is "two strips IN ONE BAY
    // disagree", and for as long as it has existed the code took one spread over every scanline in
    // the capture. These two are the same disagreement -- 373 against 407 -- moved between bays,
    // and they must answer differently. A leg that reds both has not learned the rule; a leg that
    // greens both has been deleted. The live pages cannot make this point any more: accounts was
    // the standing red and M1-107 greened it page-side while this row was in flight.
    ['field-grid', () => rackVerdict([
      { bay: 0, left: 219, edges: [373, 527, 680] },
      { bay: 0, left: 219, edges: [373, 527, 680] },
      { bay: 0, left: 219, edges: [407, 594, 829] },
    ], 'two racks IN ONE BAY, field 2 at 373 against 407'), false],
    ['field-grid', () => rackVerdict([
      // turns' real geometry (M1-115): the in-flight rack's six fields against the landed rack's
      // fourteen. 411 against 335 is 76px and it is not a misalignment -- a six-field grid and a
      // fourteen-field grid share no column to disagree about. Each bay is internally exact.
      { bay: 0, left: 219, edges: [411, 603, 719, 815, 911] },
      { bay: 0, left: 219, edges: [411, 603, 719, 815, 911] },
      { bay: 2, left: 220, edges: [335, 527, 699, 853, 997] },
      { bay: 2, left: 220, edges: [335, 527, 699, 853, 997] },
    ], 'two racks in DIFFERENT bays, field 2 at 411 against 335'), true],
    // ---- `painted`, PROVED BY MUTATION ON ONE BOOLEAN (M1-114). Identical pixels both times; the
    // only change is whether the declarations say the renderer painted the band. This is logs, and
    // the red case reproduces M1-110's 159px exactly -- which is the evidence that `painted` is
    // what does the work here, and not the anchor or the grouping.
    ['field-grid', () => rackVerdict([
      { bay: 0, left: 166, edges: [166, 1094, 1107], painted: false, label: 'lt-head' },
      { bay: 0, left: 219, edges: [325, 498, 575] },
      { bay: 0, left: 219, edges: [325, 498, 575] },
      { bay: 0, left: 219, edges: [325, 498, 575] },
    ], 'logs: a bay band the renderer did NOT paint as a strip'), true],
    ['field-grid', () => rackVerdict([
      { bay: 0, left: 166, edges: [166, 1094, 1107], painted: true, label: 'lt-head' },
      { bay: 0, left: 219, edges: [325, 498, 575] },
      { bay: 0, left: 219, edges: [325, 498, 575] },
      { bay: 0, left: 219, edges: [325, 498, 575] },
    ], 'logs: the SAME pixels with the band declared painted — M1-110\'s 159px returns'), false],
    // ---- THE ANCHOR (M1-114). Field-line ink sits at x=166, outside the strip's own paper, on the
    // first row only. Scanning from the constant 140 reads it and calls the rack 207px wide;
    // scanning from the strip's own left edge reads the field boundary the rule is about. A
    // controller scans a column of THE STRIP.
    ['field-grid', () => rackVerdict([
      { bay: 0, left: 219, edges: [166, 373, 527] },
      { bay: 0, left: 219, edges: [373, 527] },
    ], 'ink left of the strip\'s own left edge is not its field edge'), true],
    // ---- THE INSUFFICIENT-COVERAGE CASES THIS ANCHOR OWES, END TO END (M1-114). Reading the dump
    // buys exactness and takes a dependency, and a dependency that can be absent needs a
    // disposition that is not silence. Both are driven through checkFieldGrid over a real
    // directory, because both are conditions I added that RED the gate -- and a new red no test can
    // reach is the decorative check this file keeps finding in other people's legs.
    ['field-grid', () => {
      findings.length = 0;
      const { im, known } = rackProbe([
        { bay: 0, left: 219, edges: [373, 527] }, { bay: 0, left: 219, edges: [373, 527] },
      ]);
      const tmp = fs.mkdtempSync('/tmp/lookgate-olddump-');
      // A dump as it was written before `painted` existed: it cannot say which scanlines are
      // strips, so the leg must refuse rather than fall back to the pairing that produced M1-110.
      seedCaptures(tmp, ['fleet'], { im, strips: known.map(({ y, h, fields }) => ({ y, h, fields })) });
      const saved = ROOTREF.root; ROOTREF.root = tmp;
      checkFieldGrid('caps'); ROOTREF.root = saved;
      fs.rmSync(tmp, { recursive: true, force: true });
      return findings[0];
    }, false],
    ['field-grid', () => {
      findings.length = 0;
      const { im } = rackProbe([
        { bay: 0, left: 219, edges: [373, 527] }, { bay: 0, left: 219, edges: [373, 527] },
      ]);
      const tmp = fs.mkdtempSync('/tmp/lookgate-nostrips-');
      // settings' shape (M1-92): a readable capture whose rack is not `.myx-strip`, so the dump
      // enumerates ZERO strips. The pixels are a perfectly aligned grid and the leg must still
      // refuse them, because it has nothing that says they are strips. This is the boring case
      // §24 names -- an empty item waved through -- and it is the one that gets waved through.
      seedCaptures(tmp, ['settings'], { im, strips: [] });
      const saved = ROOTREF.root; ROOTREF.root = tmp;
      checkFieldGrid('caps'); ROOTREF.root = saved;
      fs.rmSync(tmp, { recursive: true, force: true });
      return findings[0];
    }, false],
    ['field-grid', () => {
      findings.length = 0;
      const { im, known } = rackProbe([
        { bay: 0, left: 219, edges: [373, 527] }, { bay: 0, left: 219, edges: [373, 527] },
      ]);
      const tmp = fs.mkdtempSync('/tmp/lookgate-whole-');
      // THE COMPLIANT FORM, THROUGH THE SAME DOOR. Without this the two refusals above would be
      // satisfied by a leg that refuses everything, which is how a gate stops being a gate.
      seedCaptures(tmp, ['fleet'], { im, strips: known });
      const saved = ROOTREF.root; ROOTREF.root = tmp;
      checkFieldGrid('caps'); ROOTREF.root = saved;
      fs.rmSync(tmp, { recursive: true, force: true });
      return findings[0];
    }, true],
    // ---- THE LAST BRANCH THAT STILL PASSED ON AN EMPTY DENOMINATOR (M1-114, applying M1-108's
    // ruling). `skipped: comp unreadable` returned ok=true: the leg had not read its reference, had
    // compared nothing, and said so in prose while the verdict said fine. THE EXPECTATION FLIPS
    // WITH THE CODE, which is the point -- a test that was asserting the old verdict is not
    // evidence for the new one. This case also could not exist before the same edit, because the
    // comp was read through frozen `ROOT` and the redirect below never reached it.
    ['tonal-drift', () => {
      findings.length = 0;
      const tmp = fs.mkdtempSync('/tmp/lookgate-nocomp-');
      fs.mkdirSync(path.join(tmp, 'caps'), { recursive: true });
      const saved = ROOTREF.root; ROOTREF.root = tmp;
      checkTonalDrift('caps', 'mocks/absent.png'); ROOTREF.root = saved;
      fs.rmSync(tmp, { recursive: true, force: true });
      return findings[0];
    }, false],
    // ---- THE TABLE, MUTATION-PROVED ON ITS OWN TERMS (M1-124) ----
    //
    // A coverage report that cannot tell dropped-and-named from judged-and-passed is the vacuous
    // green this campaign keeps finding -- the same shape as the diff of two empty files that
    // reported them identical in my own measuring harness on M1-108. So the proof is: plant one
    // address every leg must drop and one that must be judged, run the real legs over a real
    // directory, and require the table to say something DIFFERENT about each. A table that said
    // `judged` for both, or `DROPPED` for both, would pass a weaker assertion than this one.
    ['page-coverage', () => {
      findings.length = 0; COVERAGE.clear();
      const good = rackProbe([
        { bay: 0, left: 219, edges: [373, 527] }, { bay: 0, left: 219, edges: [373, 527] },
      ]);
      const tmp = fs.mkdtempSync('/tmp/lookgate-table-');
      seedCaptures(tmp, ['fleet'], { im: good.im, strips: good.known });
      // `settings` gets the SAME pixels and a dump that declares no strips -- M1-92's real shape,
      // and the only difference between the two pages. Identical glass, opposite dispositions.
      seedCaptures(tmp, ['settings'], { im: good.im, strips: [] });
      fs.writeFileSync(path.join(tmp, 'webui/src/app/rows.ts'),
        "export const ADDRESSES = ['fleet', 'settings'] as const;\n");
      fs.mkdirSync(path.join(tmp, 'mocks'), { recursive: true });
      fs.writeFileSync(path.join(tmp, 'mocks/comp.png'), encodePng(good.im));
      // THE FRESHNESS BAR IS `webui/src`, AND THIS FIXTURE WRITES rows.ts LAST, so without this the
      // captures are older than the source they claim to measure and BOTH pages drop as stale --
      // which is the leg working correctly and the fixture lying. Caught because the assertion
      // demanded two DIFFERENT dispositions and got the same one twice; a case that only checked
      // "settings is dropped" would have passed while proving nothing.
      const future = new Date(Date.now() + 10_000);
      for (const f of fs.readdirSync(path.join(tmp, 'caps'))) fs.utimesSync(path.join(tmp, 'caps', f), future, future);
      const saved = ROOTREF.root; ROOTREF.root = tmp;
      // capture-set's dispositions are seeded the way a capture run leaves them; this case is
      // about the table, and captureSet itself needs a browser.
      covers('capture-set', 'fleet', null); covers('capture-set', 'settings', null);
      checkFieldGrid('caps');
      checkTonalDrift('caps', 'mocks/comp.png');
      checkPageCoverage();
      ROOTREF.root = saved;
      fs.rmSync(tmp, { recursive: true, force: true });
      const table = findings[findings.length - 1];
      const ok = /fleet\s+capture-set=judged · field-grid=judged/.test(table.detail)
        && /settings\s+capture-set=judged · field-grid=DROPPED/.test(table.detail)
        && !/UNACCOUNTED/.test(table.detail);
      findings.length = 0;
      record('page-coverage', false, ok,
        ok ? 'the planted judged page reads judged and the planted dropped page reads DROPPED, by name'
           : `the table did not distinguish them: ${table.detail.replace(/\n/g, ' | ')}`);
      return findings[0];
    }, true],
    // A LEG THAT NEVER RAN LEAVES A HOLE, AND THE HOLE IS THE ONE THING THIS TABLE FAILS FOR.
    // Without this the table would be satisfied by recording nothing at all: every address absent,
    // every cell blank, and a clean green over an empty denominator (law 34, one level up).
    ['page-coverage', () => {
      findings.length = 0; COVERAGE.clear();
      const tmp = fs.mkdtempSync('/tmp/lookgate-hole-');
      fs.mkdirSync(path.join(tmp, 'webui/src/app'), { recursive: true });
      fs.writeFileSync(path.join(tmp, 'webui/src/app/rows.ts'),
        "export const ADDRESSES = ['fleet', 'teams'] as const;\n");
      const saved = ROOTREF.root; ROOTREF.root = tmp;
      // fleet is fully accounted for; teams is accounted for by two legs and missed by the third.
      for (const leg of PAGE_LEGS) covers(leg, 'fleet', null);
      covers('capture-set', 'teams', null); covers('field-grid', 'teams', 'paints no field-line ink');
      checkPageCoverage(); ROOTREF.root = saved;
      fs.rmSync(tmp, { recursive: true, force: true });
      return findings[0];
    }, false],
    // The boring case §24 names: no denominator at all. A table with no addresses to audit must
    // refuse rather than report zero holes over zero pages.
    ['page-coverage', () => {
      findings.length = 0; COVERAGE.clear();
      const tmp = fs.mkdtempSync('/tmp/lookgate-noaddr-');
      const saved = ROOTREF.root; ROOTREF.root = tmp;
      checkPageCoverage(); ROOTREF.root = saved;
      fs.rmSync(tmp, { recursive: true, force: true });
      return findings[0];
    }, false],
    // ---- THE DROP SITE NO PAGE CURRENTLY REACHES (M1-124). `mid.length < 2` was a bare `continue`
    // with no counter, and `scanty` is 0 on a full run of all thirteen addresses -- so if it is
    // ever to be visible, a synthetic page is the only thing that can prove it. One band of paper:
    // one scanline, which cannot be compared against itself.
    ['page-coverage', () => {
      findings.length = 0; COVERAGE.clear();
      const one = rackProbe([{ bay: 0, left: 219, edges: [373, 527] }]);
      const tmp = fs.mkdtempSync('/tmp/lookgate-scanty-');
      seedCaptures(tmp, ['fleet'], { im: one.im, strips: one.known });
      const saved = ROOTREF.root; ROOTREF.root = tmp;
      covers('capture-set', 'fleet', null); covers('tonal-drift', 'fleet', null);
      checkFieldGrid('caps'); checkPageCoverage(); ROOTREF.root = saved;
      fs.rmSync(tmp, { recursive: true, force: true });
      const table = findings[findings.length - 1];
      const ok = /fleet.*field-grid=DROPPED \(only 1 strip scanline/.test(table.detail);
      findings.length = 0;
      record('page-coverage', false, ok,
        ok ? 'a page dropped at the no-counter site is named in the table'
           : `the silent site stayed silent: ${table.detail.replace(/\n/g, ' | ')}`);
      return findings[0];
    }, true],
    // ---- AN ADDRESS WITH NO FILE ON DISK IS A DROP, NOT A HOLE (M1-124). Both reading legs walk
    // the DIRECTORY, so an address whose capture was never written is never iterated and records
    // nothing -- and the table then calls it UNACCOUNTED, which is reserved for the instrument
    // losing track. Found by the red-green on captureSet's wrong-room guard, where forcing one
    // address to fail the capture leg made every downstream cell for it read as an audit hole.
    // `fleet` has a capture here and `teams` does not; both must end up with a stated disposition.
    ['page-coverage', () => {
      findings.length = 0; COVERAGE.clear();
      const good = rackProbe([
        { bay: 0, left: 219, edges: [373, 527] }, { bay: 0, left: 219, edges: [373, 527] },
      ]);
      const tmp = fs.mkdtempSync('/tmp/lookgate-nofile-');
      seedCaptures(tmp, ['fleet'], { im: good.im, strips: good.known });
      fs.writeFileSync(path.join(tmp, 'webui/src/app/rows.ts'),
        "export const ADDRESSES = ['fleet', 'teams'] as const;\n");
      fs.mkdirSync(path.join(tmp, 'mocks'), { recursive: true });
      fs.writeFileSync(path.join(tmp, 'mocks/comp.png'), encodePng(good.im));
      const future = new Date(Date.now() + 10_000);
      for (const f of fs.readdirSync(path.join(tmp, 'caps'))) fs.utimesSync(path.join(tmp, 'caps', f), future, future);
      const saved = ROOTREF.root; ROOTREF.root = tmp;
      covers('capture-set', 'fleet', null); covers('capture-set', 'teams', 'not written');
      checkFieldGrid('caps'); checkTonalDrift('caps', 'mocks/comp.png'); checkPageCoverage();
      ROOTREF.root = saved;
      fs.rmSync(tmp, { recursive: true, force: true });
      const table = findings[findings.length - 1];
      const ok = /teams\s+capture-set=DROPPED .* field-grid=DROPPED \(no capture on disk/.test(table.detail)
        && !/UNACCOUNTED/.test(table.detail);
      findings.length = 0;
      record('page-coverage', false, ok,
        ok ? 'an address with no capture on disk is DROPPED with a reason, not UNACCOUNTED'
           : `a missing capture read as an audit hole: ${table.detail.replace(/\n/g, ' | ')}`);
      return findings[0];
    }, true],
    // ---- THE FRESHNESS BAR IS FROZEN BEFORE THE RUN, AND BOTH HALVES ARE PROVED ----
    //
    // Measured failure: another seat wrote settings.css at 10:57:07 during a 79-second capture run
    // and the five addresses captured before that instant were reported DROPPED as older than their
    // own source, the cut landing exactly on the write. The mtimes here are set explicitly rather
    // than left to the clock, because the whole defect is about ORDER and a fixture that relies on
    // two writes landing in different milliseconds proves nothing.
    ['field-grid', () => {
      findings.length = 0; COVERAGE.clear(); BAR.at = null; BAR.movedTo = null;
      const good = rackProbe([
        { bay: 0, left: 219, edges: [373, 527] }, { bay: 0, left: 219, edges: [373, 527] },
      ]);
      const tmp = fs.mkdtempSync('/tmp/lookgate-race-');
      seedCaptures(tmp, ['fleet'], { im: good.im, strips: good.known });
      const t = Date.now();
      const at = (p, ms) => fs.utimesSync(path.join(tmp, p), new Date(ms), new Date(ms));
      at('webui/src/app/rows.ts', t);                      // the source, as the run begins
      for (const f of fs.readdirSync(path.join(tmp, 'caps'))) at(path.join('caps', f), t + 10_000);
      const saved = ROOTREF.root; ROOTREF.root = tmp;
      freshnessBar();                                      // frozen here, as the run freezes it
      // A CONCURRENT SEAT LANDS A ROW while the browser is still working.
      fs.writeFileSync(path.join(tmp, 'webui/src/app/later.css'), '.x { color: red; }');
      at('webui/src/app/later.css', t + 20_000);
      checkFieldGrid('caps'); ROOTREF.root = saved;
      fs.rmSync(tmp, { recursive: true, force: true });
      BAR.at = null; BAR.movedTo = null;
      return findings[0];
    }, true],
    // THE CONTROL, AND IT IS NOT OPTIONAL: freezing a bar could "fix" staleness by removing the
    // protection entirely, and a leftover from an earlier build must still be caught. Same tree,
    // same frozen bar, one capture backdated to before it.
    ['field-grid', () => {
      findings.length = 0; COVERAGE.clear(); BAR.at = null; BAR.movedTo = null;
      const good = rackProbe([
        { bay: 0, left: 219, edges: [373, 527] }, { bay: 0, left: 219, edges: [373, 527] },
      ]);
      const tmp = fs.mkdtempSync('/tmp/lookgate-stale-');
      seedCaptures(tmp, ['fleet'], { im: good.im, strips: good.known });
      const t = Date.now();
      const at = (p, ms) => fs.utimesSync(path.join(tmp, p), new Date(ms), new Date(ms));
      at('webui/src/app/rows.ts', t);
      for (const f of fs.readdirSync(path.join(tmp, 'caps'))) at(path.join('caps', f), t - 60_000);
      const saved = ROOTREF.root; ROOTREF.root = tmp;
      freshnessBar();
      checkFieldGrid('caps'); ROOTREF.root = saved;
      fs.rmSync(tmp, { recursive: true, force: true });
      BAR.at = null; BAR.movedTo = null;
      return findings[0];
    }, false],
    ['field-grid', () => fieldProbe('dark', 'dark'), false],
    ['field-grid', () => fieldProbe('light', 'light'), false],
    ['field-grid', () => fieldProbe('light', 'dark'), true],
    // ---- THE DECLARED SPAN, THREE WAYS (M1-92). The same geometry every time -- one strip whose
    // first field edge sits 80px left of the other two, which is compaction's shape -- and the only
    // thing that changes is what the declarations say about it. A detector that passes all three
    // has failed to look; one that fails all three has not been taught.
    ['field-grid', () => {
      findings.length = 0;
      const judged = judgeGrid([
        { y: 100, x: 300, declared: [{ label: 'total', span: '2' }] },
        { y: 200, x: 380, declared: [] },
        { y: 300, x: 380, declared: [] },
      ]);
      record('field-grid', true, judged.ok, judged.detail + honouredClause(judged));
      return findings[0];
    }, true],
    ['field-grid', () => {
      findings.length = 0;
      const judged = judgeGrid([
        { y: 100, x: 300, declared: [] },
        { y: 200, x: 380, declared: [] },
        { y: 300, x: 380, declared: [] },
      ]);
      record('field-grid', true, judged.ok, judged.detail + honouredClause(judged));
      return findings[0];
    }, false],
    ['field-grid', () => {
      findings.length = 0;
      const judged = judgeGrid([
        { y: 100, x: 300, declared: [], paired: false },
        { y: 200, x: 380, declared: [], paired: false },
        { y: 300, x: 380, declared: [], paired: false },
      ]);
      record('field-grid', true, judged.ok, judged.detail + honouredClause(judged));
      return findings[0];
    }, false],
    // ...and the case where honouring leaves nothing to compare: it must SAY SO rather than report
    // a clean grid it did not measure.
    ['field-grid', () => {
      findings.length = 0;
      const judged = judgeGrid([
        { y: 100, x: 300, declared: [{ label: 'total', span: '2' }] },
        { y: 200, x: 380, declared: [{ label: 'reason', span: '4' }] },
      ]);
      record('field-grid', true, judged.ok, judged.detail + honouredClause(judged));
      return findings[0];
    }, undefined],
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

// ---- THE CAPTURES THIS GATE JUDGES (M1-68). It produces them, then checks they are not older
// than the source they claim to measure. A run that cannot capture (no dev server) is a
// DID NOT RUN for every capture-reading check, never a pass -- law 23, and the same shape the
// build leg uses when dist/index.html is older than the run.
let CAPTURE_NOTE = 'captures not produced this run';
// FREEZE THE BAR BEFORE A SINGLE FRAME IS WRITTEN. Every freshness question below asks this one
// sample, so a write into webui/src while the browser is working can no longer retroactively
// invalidate frames this run produced itself.
freshnessBar();
if (!has('no-capture')) {
  const result = await captureSet(CAPTURES);
  CAPTURE_NOTE = result.ok ? result.detail : `DID NOT RUN: ${result.detail}`;
  if (!result.ok) process.stderr.write(`  ! captures: ${result.detail}\n`);
} else {
  CAPTURE_NOTE = 'captures not produced (--no-capture)';
  // `--no-capture` IS A STATED REASON, NOT A HOLE IN THE AUDIT. Without this every address reads
  // capture-set=UNACCOUNTED, which is reserved for the instrument losing track of a page it should
  // have seen — and the leg deliberately not running is the opposite of that. Found by the control
  // for the freshness fix, which runs in exactly this mode; it is the same shape as the missing-file
  // case on the two reading legs, one leg further up.
  for (const a of addresses() ?? []) covers('capture-set', a, 'the capture leg did not run (--no-capture)');
}
// DID THE TREE MOVE WHILE WE WERE LOOKING AT IT? The frozen bar keeps this run's own captures, and
// this is the other half: if the source really did change mid-run, the set mixes two consoles and
// that has to be said rather than absorbed. A second sample costs one directory walk.
const barAfter = newestSourceMtime();
if (barAfter > BAR.at) BAR.movedTo = barAfter;
if (BAR.movedTo !== null) {
  process.stderr.write(`  ! captures: ${SRC} changed DURING this run (bar ${new Date(BAR.at).toISOString()} -> ${new Date(BAR.movedTo).toISOString()});`
    + ' frames taken before that write show the previous console\n');
}
const freshness = staleCaptures(CAPTURES);
if (freshness && freshness.stale > 0) {
  process.stderr.write(`  ! captures: ${freshness.stale} of ${freshness.files.length} are older than webui/src (oldest ${freshness.oldestName})\n`);
}
// THE SET AND ITS COST, in the output rather than in a note (M1-68): the row that chose the set
// has to state what it cost, and a reader has to be able to see it without opening the ledger.
record('capture-set', false, true, `${CAPTURE_NOTE} · set: ${(addresses() || []).length} addresses x ${CAPTURE_THEME} x ${CAPTURE_FRAME.join('x')} · freshness bar: newest mtime under ${SRC}, sampled BEFORE the run`
  + (BAR.movedTo === null ? '' : ` · ${SRC} CHANGED DURING THIS RUN, so frames taken before that write show the previous console — re-run for a set from one build`));

// THE BOOT AXIS (M1-106), its own line so a reader sees WHICH state was reached rather than a
// count of files. Non-blocking for the same reason capture-set is: a leg that cannot run is a
// DID NOT RUN, and the surfaces it is about are pages, not the grid this gate exists to protect.
let AXES_OK = false;
let AXES_NOTE = 'axes not produced this run';
if (!has('no-capture')) {
  const axes = await captureAxes(AXES_DIR);
  AXES_OK = axes.ok;
  AXES_NOTE = axes.ok ? axes.detail : `DID NOT RUN: ${axes.detail}`;
  if (!axes.ok) process.stderr.write(`  ! boot-axes: ${axes.detail}\n`);
} else {
  AXES_NOTE = 'axes not produced (--no-capture)';
}
record('boot-axes', false, AXES_OK, AXES_NOTE);

const files = cssFiles(SRC);
checkLadderSteps(readIf(TOKENS) || '');
checkTypeDistribution(files);
checkSpacingDistribution(files);
checkNoTypeTransform(files);
checkAbsenceVocabulary(SRC);
checkFieldGrid(CAPTURES);
checkTonalDrift(CAPTURES, COMP);
// LAST, because it audits what the legs above recorded (M1-124).
checkPageCoverage();

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
