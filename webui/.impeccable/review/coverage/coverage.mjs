#!/usr/bin/env node
// coverage — how much of each page's content area is actually carrying anything.
//
// WHY. The blind 3840 pass found the number that contains the others: mean paper coverage of the
// content area across thirteen dark pages is 13.7 percent, from 2.4 on projects to 70.0 on
// settings. Nothing in this layout has a considered relationship to the width — or the height — it
// is given. At 1536 that reads as generous spacing; at 3840 it is an empty room.
//
// WHAT IT MEASURES, per page and per frame: the content area (right of the rail, below the rule)
// classified into pale paper, mid tone and bare floor, with the vertical and horizontal profiles in
// tenths. Coverage = paper + mid, i.e. everything that is not empty floor.
//
// THE COMP IS A ROW IN THE TABLE, NOT A COMMENT. A coverage instrument that reports only our pages
// gives the direction and not the distance: 44.1 measured off team-board-a.png against 13.7 is the
// only reason this defect has a magnitude at all. The target row is printed beside every run.
//
// THE FIXTURE FEEDS EVERY NUMBER, SO IT IS IN THE OUTPUT. These renders are fixture-fed — the
// console's own fixtures, which is right for measuring layout (the same data in the frame each
// time) and is exactly why a coverage figure whose data source is unstated cannot be compared to
// the next run. Each row names its address, and its fixture or "live" when there is none.
//
// Usage: node webui/.impeccable/review/coverage/coverage.mjs [--frame 3840x2160] [--out DIR] '<url>' ...
//        node webui/.impeccable/review/coverage/coverage.mjs --comp      (the comp row alone)
import { readFileSync, mkdirSync, writeFileSync, existsSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import process from 'node:process';
import { mgmtKey, shoot, show, withChrome } from '../../../../dev/web-console/lib/cdp.mjs';
import { decodePng } from '../../../../dev/web-console/lib/png.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '..', '..', '..', '..');
const COMP = 'webui/.impeccable/mocks/team-board-a.png';

const ARGS = process.argv.slice(2);
const flag = (name, dflt) => { const i = ARGS.indexOf(`--${name}`); return i === -1 ? dflt : ARGS[i + 1]; };

/** Pale, mid, floor: the same three classes the m1 review's tonal census uses. */
function classify(png) {
  const bands = (from, to, step) => {
    const out = [];
    for (let i = from; i < to; i += step) out.push([i, Math.min(i + step, to)]);
    return out;
  };
  const share = (x0, x1, y0, y1) => {
    let paper = 0; let mid = 0; let n = 0;
    for (let y = y0; y < y1; y += 2) {
      for (let x = x0; x < x1; x += 2) {
        const i = (y * png.width + x) * png.channels;
        const lum = (png.pixels[i] + png.pixels[i + 1] + png.pixels[i + 2]) / 3;
        if (lum > 150) paper++; else if (lum >= 30) mid++;
        n++;
      }
    }
    return { paper: (paper / n) * 100, mid: (mid / n) * 100, floor: 100 - ((paper + mid) / n) * 100 };
  };
  return { share, bands };
}

/** The content area of one frame, found the way the shell lays it out: right of the rail, below
 *  the rule. The rail and rule are the shell's own boxes, so this reads the render rather than
 *  assuming the comp's proportions at another frame. */
function contentBox(png) {
  const lum = (x, y) => {
    const i = (y * png.width + x) * png.channels;
    return (png.pixels[i] + png.pixels[i + 1] + png.pixels[i + 2]) / 3;
  };
  // The rule is the darkest full-width band at the top; the rail is the first column of rail ink.
  let rule = Math.round(png.height * 0.058);
  for (let y = 0; y < Math.min(png.height, 200); y++) {
    let dark = 0;
    for (let x = 0; x < png.width; x += 8) if (lum(x, y) < 60) dark++;
    if (dark > (png.width / 8) * 0.9) rule = y;
    else if (y > rule + 4) break;
  }
  // The rail is chrome, so the content area starts at the rail's EDGE. It used to be "the first
  // column with ink", which found the rail's first pale tab at x=22 on the comp and counted 129px
  // of chrome as content - worth 1 to 2 points on every number this instrument prints, on the
  // denominator three rows are quoting. The rail's width is a shell constant: --rail-w is 9vw
  // (app.css), and the comp's own rail measures 138 of 1536 = 8.98%. So the floor is 9% of the
  // frame, and on a frame where a real rail is narrower the first inked column still wins.
  let first = png.width;
  for (let x = 0; x < Math.min(png.width, 400); x++) {
    let ink = 0;
    for (let y = rule + 8; y < Math.min(png.height, rule + 400); y += 8) if (lum(x, y) > 90) ink++;
    if (ink > 5) { first = x; break; }
  }
  const rail = Math.max(first, Math.round(png.width * 0.09));
  return { x0: rail, y0: rule, x1: png.width, y1: png.height };
}

/** The two planes a frame is made of: its most common colour and its most common OTHER colour,
 *  far enough apart to be a plane rather than a shade. Reported as L values and their separation,
 *  because coverage says how much of the frame carries something and separation says whether what
 *  it carries reads as LAYERED. Light at +55 and dark at +183 is an asymmetry no single-theme
 *  instrument can see, and no leg of the exit gate seeds a theme at all. */
function planes(png, box) {
  const tally = new Map();
  for (let y = box.y0; y < box.y1; y += 3) {
    for (let x = box.x0; x < box.x1; x += 3) {
      const i = (y * png.width + x) * png.channels;
      const key = `${png.pixels[i] >> 3},${png.pixels[i + 1] >> 3},${png.pixels[i + 2] >> 3}`;
      const slot = tally.get(key) ?? { n: 0, r: 0, g: 0, b: 0 };
      slot.n++; slot.r += png.pixels[i]; slot.g += png.pixels[i + 1]; slot.b += png.pixels[i + 2];
      tally.set(key, slot);
    }
  }
  const top = [...tally.values()].sort((a, b) => b.n - a.n).slice(0, 6)
    .map((s) => ({ n: s.n, L: (s.r + s.g + s.b) / (3 * s.n) }));
  const dominant = top[0];
  const other = top.find((t) => Math.abs(t.L - dominant.L) > 20);
  return {
    L: dominant.L,
    other: other === undefined ? null : other.L,
    separation: other === undefined ? null : Math.abs(other.L - dominant.L),
  };
}

/** THE THIRD LAYER. In the dark theme the bay's ground is L12 against a room of L13.4 - 1.06:1 by
 *  M1-24's ruling, because dark carries the rack with MATERIAL and light carries it with a PLANE.
 *  So a bay that grows adds pixels this instrument calls floor, and coverage in dark cannot grade
 *  anything structural: measured, a bay ground that went from row 1338 to row 2120 of 2160 moved
 *  coverage to 26.4 while the bottom four vertical tenths stayed at 5-6.
 *
 *  What the comp has in that region is slot RULES - 1 to 2px of ink on a ~32px pitch - and 1px
 *  lines contribute almost nothing to an area ratio however many there are. So the layers are
 *  counted separately: PAPER (the strips), PRINTED (mid-tone in runs of 4px or more: plates,
 *  printed members) and RULED (mid-tone in runs of 1-3px: the rack's rules), plus the RULE ROWS -
 *  scanlines a rule crosses - which is the number a bay-height row is actually graded on.
 *  Measured on projects after M1-38: 633 rule rows before, 683 after. */
function layers(png, box) {
  let paper = 0; let printed = 0; let ruled = 0; let n = 0; let ruleRows = 0;
  for (let x = box.x0; x < box.x1; x += 2) {
    let run = 0;
    for (let y = box.y0; y <= box.y1; y += 1) {
      const i = (y * png.width + x) * png.channels;
      const lum = y >= box.y1 ? 0 : (png.pixels[i] + png.pixels[i + 1] + png.pixels[i + 2]) / 3;
      const mid = lum >= 30 && lum <= 150;
      if (mid) { run += 1; continue; }
      if (run > 0) { if (run <= 3) ruled += run * 2; else printed += run * 2; run = 0; }
    }
    n += Math.ceil((box.y1 - box.y0) / 1) * 2;
  }
  // Rule rows: scanlines carrying a long thin mid-tone run, sampled across the bay's width.
  for (let y = box.y0; y < box.y1; y++) {
    let hits = 0;
    for (let x = box.x0; x < box.x1; x += 4) {
      const i = (y * png.width + x) * png.channels;
      const lum = (png.pixels[i] + png.pixels[i + 1] + png.pixels[i + 2]) / 3;
      if (lum >= 30 && lum <= 150) hits++;
    }
    if (hits > (box.x1 - box.x0) / 4 * 0.4) ruleRows += 1;
  }
  return { printed: (printed / n) * 100, ruled: (ruled / n) * 100, ruleRows };
}

function profile(png, box) {
  const c = classify(png);
  const l = layers(png, box);
  const tenths = (axis) => {
    const out = [];
    for (let t = 0; t < 10; t++) {
      if (axis === 'y') {
        const y0 = box.y0 + Math.round(((box.y1 - box.y0) * t) / 10);
        const y1 = box.y0 + Math.round(((box.y1 - box.y0) * (t + 1)) / 10);
        const s = c.share(box.x0, box.x1, y0, y1);
        out.push(s.paper + s.mid);
      } else {
        const x0 = box.x0 + Math.round(((box.x1 - box.x0) * t) / 10);
        const x1 = box.x0 + Math.round(((box.x1 - box.x0) * (t + 1)) / 10);
        const s = c.share(x0, x1, box.y0, box.y1);
        out.push(s.paper + s.mid);
      }
    }
    return out;
  };
  const whole = c.share(box.x0, box.x1, box.y0, box.y1);
  return {
    paper: whole.paper, mid: whole.mid, floor: whole.floor,
    coverage: whole.paper + whole.mid,
    printed: l.printed, ruled: l.ruled, ruleRows: l.ruleRows,
    byHeight: tenths('y'), byWidth: tenths('x'),
  };
}

function rowFor(label, source, png) {
  const box = contentBox(png);
  return { label, source, frame: `${png.width}x${png.height}`, planes: planes(png, box), ...profile(png, box) };
}

function line(r) {
  const f = (n) => n.toFixed(1).padStart(5);
  const tenths = (a) => a.map((v) => v.toFixed(0).padStart(3)).join('');
  return `  ${r.label.padEnd(26)}${r.source.padEnd(16)}${r.frame.padEnd(11)}`
    + `paper${f(r.paper)}  printed${f(r.printed)}  ruled${f(r.ruled)}  RULES${String(r.ruleRows).padStart(4)}  SEPARATION${r.planes.separation === null ? '   -' : f(r.planes.separation)}`
    + `\n      by height ${tenths(r.byHeight)}\n      by width  ${tenths(r.byWidth)}`;
}

// A VALUED FLAG'S VALUE IS NOT A POSITIONAL. Two seats hit this shape in one night in two files:
// here `--frame 3840x2160` put the literal string into the url list and died loudly with "Cannot
// navigate to invalid URL"; in comp-check.mjs `--frame 3840x2160 --address teams` silently made
// 3840x2160 the address and printed a clean table. The loud one and the quiet one are the same bug,
// and the fix that makes it structural rather than patched is to declare which flags take a value,
// so their value can never be mistaken for an address.
const VALUED = new Set(['--frame', '--out', '--theme']);
const urls = ARGS.filter((a, i) => !a.startsWith('--') && !(i > 0 && VALUED.has(ARGS[i - 1])));
const frame = flag('frame', '3840x2160');
const outDir = resolve(ROOT, flag('out', 'webui/.impeccable/review/coverage'));
mkdirSync(outDir, { recursive: true });

const rows = [];
if (existsSync(resolve(ROOT, COMP))) {
  rows.push(rowFor('COMP team-board-a', 'comp (no data)', decodePng(readFileSync(resolve(ROOT, COMP)))));
}

if (urls.length > 0) {
  const [w, h] = frame.split('x').map(Number);
  const themes = flag('theme', 'dark') === 'both' ? ['dark', 'light'] : [flag('theme', 'dark')];
  await withChrome({ 'myx-mgmt-key': mgmtKey() }, async (send) => {
    for (const theme of themes) {
      await send('Runtime.evaluate', { expression: `try { localStorage.setItem('splice.theme', ${JSON.stringify(theme)}); } catch (e) {}` });
    for (const [at, url] of urls.entries()) {
      const slug = url.replace(/[^a-z0-9]+/gi, '-').replace(/^-|-$/g, '').toLowerCase();
      const png = resolve(outDir, `${slug}.png`);
      // Reload ONLY when this row is the same address as the one before it (a resize of the same
      // page is a same-document change, so the seeded values would not re-run). A different address
      // must NAVIGATE: keying this on the index instead of the address captured the first page
      // twice and reported two identical rows as two pages - measured, and the reason the row now
      // compares addresses.
      await show(send, url, w, h, 6000, { reload: at > 0 && url === urls[at - 1] });
      const bytes = await shoot(send, png);
      const fixture = url.includes('fixture=') ? `fixture=${url.split('fixture=')[1].split(/[#&]/)[0]}` : 'live daemon';
      rows.push(rowFor(url.slice(url.indexOf('#') + 1) || url, `${theme}/${fixture}`, decodePng(bytes)));
    }
    }
  });
}

if (process.argv.includes('--json')) {
  console.log(JSON.stringify(rows, null, 2));
} else {
  console.log(`coverage — content area, right of the rail and below the rule\n`);
  for (const r of rows) console.log(line(r));
  const ours = rows.filter((r) => r.source !== 'comp (no data)');
  if (ours.length > 0) {
    const mean = ours.reduce((s, r) => s + r.coverage, 0) / ours.length;
    console.log(`\n  mean coverage, ${ours.length} page(s): ${mean.toFixed(1)}%`);
  }
  console.log(`\n  captures: ${outDir}`);
}
