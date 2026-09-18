// M1-109: IS A PAGE THIN BECAUSE ITS LAYOUT WASTES SPACE, OR BECAUSE IT HAS LESS TO SAY?
//
// THE PREMISE THIS ROW INHERITED IS WRONG AND THE FIRST JOB IS TO SHOW IT. M1-94's corrected
// tonal-drift figures were read as "twelve pages carry a quarter to a half the INK of teams".
// tonal() counts pixels whose mean luminance falls in [30,150]. coverage.mjs's layers() uses the
// SAME band and splits it into RULED (mid-tone runs <= 3px: the rack's rules) and PRINTED (runs
// > 3px: plates and printed members). PAPER -- the cream a strip is printed on -- is ABOVE that
// band, and text ink is BELOW it. So the tonal ratio measures neither the paper nor the ink: it
// measures the MID-TONE MATERIAL, which in this world is the rack's ruled structure.
//
// Measured, at 1536 dark, against the comp's own frame: settings carries 46.3% paper to the comp's
// 29.1% and ranks LAST on the tonal ratio at 29%. logs carries 61.3% paper and 31.7% bare ground --
// the fullest page in the set -- and ranks 67%. The comp's own page sits MID-TABLE on bare ground
// with four pages fuller than it. A page is not thin because that ratio is low.
//
// SO THIS INSTRUMENT MEASURES WHAT THE QUESTION ACTUALLY NEEDS, which is the row's own list: the
// three layers separately, the share of frame that is bare ground, and the largest single empty
// rectangle -- because "unused space" is a geometric fact about where the emptiness SITS, and no
// area ratio can tell a page with thin margins everywhere from a page with one dead quadrant.
//
// WHY .mjs AND NOT .py: the repo runs no Python as an instrument (M1-78 ported sweep-d7.py for
// exactly this reason). Python appears here only as an image DECODER subprocess, which is the
// idiom look-gate.mjs's pixels() already established beside this file.
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';

const ROOT = '/home/marcos/Documents/dev/projects/mythos/repo/.claude/worktrees/v0.4.0';
const SECTIONS = path.join(ROOT, 'webui/.impeccable/review/sections');
const COMP = path.join(ROOT, 'webui/.impeccable/mocks/team-board-a.png');
const OUT = path.join(ROOT, 'webui/.impeccable/review/density');
const THEME_GROUND = 11;   // the dark room, the one theme these captures are taken in

/** Decode a PNG to raw RGB. The decoder idiom is look-gate.mjs's, not a new dependency. */
export function pixels(pngPath) {
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
}

const lumAt = (im, x, y) => {
  const i = (y * im.w + x) * 3;
  return (im.data[i] + im.data[i + 1] + im.data[i + 2]) / 3;
};

/**
 * The paper cut, DERIVED FROM THE FRAME'S OWN TWO PLANES -- coverage.mjs's cutFor, reproduced here
 * because that module exports only the one function and this file must not edit it.
 *
 * THE GROUND IS THE MEDIAN, NEVER THE MODE. My first cut used the modal luminance and settings and
 * teams inverted: both frames are more than half cream by pixel count, so the mode IS the paper,
 * the cut landed above it, and two visibly cream-covered pages reported 0.1% and 1.0% paper. The
 * median is stable against exactly that.
 */
export function cutFor(im, themeGround = THEME_GROUND) {
  const l = [];
  for (let y = 0; y < im.h; y += 2) for (let x = 0; x < im.w; x += 2) l.push(lumAt(im, x, y));
  l.sort((a, b) => a - b);
  const at = (q) => l[Math.min(l.length - 1, Math.floor(l.length * q))];
  const ground = at(0.5), paper = at(0.995);
  if (paper - ground >= 10) return (ground + paper) / 2;
  return ground > themeGround + 10 ? themeGround + (ground - themeGround) / 2 : ground + 1;
}

/**
 * The three layers plus the floor, over a whole frame.
 *
 * RULED vs PRINTED is a RUN LENGTH question, not a colour one: a rack rule is 1-3px of mid-tone and
 * a plate is a block of it, and an area ratio cannot tell them apart however many rules there are.
 * That distinction is the whole reason the comp's density is not reproducible by adding fill.
 */
export function layers(im, cut) {
  const CUT = 3 * (im.w / 1536);   // the 3px boundary is tuned on the comp's 1536 raster (M1-71)
  let paper = 0, printed = 0, ruled = 0, floor = 0, n = 0;
  for (let x = 0; x < im.w; x += 2) {
    let run = 0;
    for (let y = 0; y <= im.h; y += 1) {
      const lum = y >= im.h ? 0 : lumAt(im, x, y);
      const isMid = y < im.h && lum >= 30 && lum <= 150;
      if (isMid) { run += 1; continue; }
      if (run > 0) { if (run <= CUT) ruled += run; else printed += run; run = 0; }
      if (y < im.h) { if (lum > cut) paper += 1; else floor += 1; }
      if (y < im.h) n += 1;
    }
  }
  const tot = n + printed + ruled;
  return {
    paper: (paper / tot) * 100, printed: (printed / tot) * 100,
    ruled: (ruled / tot) * 100, floor: (floor / tot) * 100,
  };
}

/**
 * THE LARGEST SINGLE EMPTY RECTANGLE, in frame-percent of area, plus where it sits.
 *
 * This is the measurement that separates the row's two diagnoses, and no ratio can do it. A page
 * whose emptiness is spread as breathing room around live content is a different object from one
 * carrying a single dead quadrant, and both report the same bare-ground share. Classic maximal
 * rectangle over a binary matrix (histogram per row, monotonic stack), on a coarse grid because the
 * answer is a region, not a pixel.
 */
export function largestEmptyRect(im, cut, cell = 8) {
  const gw = Math.floor(im.w / cell), gh = Math.floor(im.h / cell);
  // a cell is EMPTY when every sample in it is below the paper cut and out of the mid band --
  // i.e. bare room. One lit pixel disqualifies the cell, which keeps this honest about "empty".
  const empty = new Uint8Array(gw * gh);
  for (let gy = 0; gy < gh; gy++) {
    for (let gx = 0; gx < gw; gx++) {
      let bare = 1;
      for (let y = gy * cell; y < (gy + 1) * cell && bare; y += 2) {
        for (let x = gx * cell; x < (gx + 1) * cell; x += 2) {
          const lum = lumAt(im, x, y);
          if (lum > 30) { bare = 0; break; }
        }
      }
      empty[gy * gw + gx] = bare;
    }
  }
  const heights = new Int32Array(gw);
  let best = { area: 0, x: 0, y: 0, w: 0, h: 0 };
  for (let gy = 0; gy < gh; gy++) {
    for (let gx = 0; gx < gw; gx++) heights[gx] = empty[gy * gw + gx] ? heights[gx] + 1 : 0;
    const stack = [];
    for (let gx = 0; gx <= gw; gx++) {
      const hcur = gx === gw ? 0 : heights[gx];
      let start = gx;
      while (stack.length && stack[stack.length - 1].h >= hcur) {
        const top = stack.pop();
        const area = top.h * (gx - top.i);
        if (area > best.area) best = { area, x: top.i, y: gy - top.h + 1, w: gx - top.i, h: top.h };
        start = top.i;
      }
      stack.push({ i: start, h: hcur });
    }
  }
  return {
    pct: (best.area * cell * cell) / (im.w * im.h) * 100,
    x: best.x * cell, y: best.y * cell, w: best.w * cell, h: best.h * cell,
  };
}

/**
 * THE LARGEST UNPRINTED REGION, which is the one that finds real layout waste.
 *
 * largestEmptyRect asks for bare room and is too strict to see the defect this row is about. On
 * models the rack's content stops at x=1085 and the right quarter of the frame carries nothing but
 * the rack's own slot rails, which cross the full width -- so every cell there holds lit pixels and
 * reads as "not empty" while being visibly dead. Measured: that page's largest BARE rectangle is
 * 4.4% and sits in the header band, while the dead column beside its rack is several times larger.
 * M1-102 found the same shape on fleet, by eye, and called it a quarter of the width.
 *
 * So a cell counts as UNPRINTED when it carries no paper at all and only a thin scatter of mid-tone
 * -- a rule crossing a cell is a few scanlines of it, a plate or a printed member is most of it.
 * That admits the ruled-but-empty column and still refuses a chart, a strip, or a plate.
 */
export function largestUnprintedRect(im, cut, cell = 8) {
  const gw = Math.floor(im.w / cell), gh = Math.floor(im.h / cell);
  const free = new Uint8Array(gw * gh);
  for (let gy = 0; gy < gh; gy++) {
    for (let gx = 0; gx < gw; gx++) {
      let lit = 0, mid = 0, n = 0, paper = 0;
      for (let y = gy * cell; y < (gy + 1) * cell; y += 2) {
        for (let x = gx * cell; x < (gx + 1) * cell; x += 2) {
          const lum = lumAt(im, x, y); n++;
          if (lum > cut) paper++;
          else if (lum >= 30 && lum <= 150) mid++;
          if (lum > 30) lit++;
        }
      }
      // no paper anywhere in the cell, and mid-tone confined to a thin scatter (a rule, not a plate)
      free[gy * gw + gx] = (paper === 0 && mid / n < 0.35) ? 1 : 0;
    }
  }
  const heights = new Int32Array(gw);
  let best = { area: 0, x: 0, y: 0, w: 0, h: 0 };
  for (let gy = 0; gy < gh; gy++) {
    for (let gx = 0; gx < gw; gx++) heights[gx] = free[gy * gw + gx] ? heights[gx] + 1 : 0;
    const stack = [];
    for (let gx = 0; gx <= gw; gx++) {
      const hcur = gx === gw ? 0 : heights[gx];
      let start = gx;
      while (stack.length && stack[stack.length - 1].h >= hcur) {
        const top = stack.pop();
        const area = top.h * (gx - top.i);
        if (area > best.area) best = { area, x: top.i, y: gy - top.h + 1, w: gx - top.i, h: top.h };
        start = top.i;
      }
      stack.push({ i: start, h: hcur });
    }
  }
  return { pct: (best.area * cell * cell) / (im.w * im.h) * 100,
           x: best.x * cell, y: best.y * cell, w: best.w * cell, h: best.h * cell };
}

if (process.argv.includes('--selftest')) {
  // The two ends the classifier must pin, and the rectangle finder must find a planted hole.
  const mk = (fill) => { const d = Buffer.alloc(64 * 64 * 3, fill); return { w: 64, h: 64, data: d }; };
  let bad = 0;
  const room = mk(11), page = mk(220);
  const cRoom = cutFor(room), cPage = cutFor(page);
  const lRoom = layers(room, cRoom), lPage = layers(page, cPage);
  const check = (name, ok, got) => { if (!ok) { bad++; console.log(`  FAIL ${name}: ${got}`); } else console.log(`  ok   ${name} (${got})`); };
  check('a pure room frame holds no paper', lRoom.paper < 1, `${lRoom.paper.toFixed(1)}% paper`);
  check('a pure paper frame is not floor', lPage.floor < 1, `${lPage.floor.toFixed(1)}% floor`);
  // a frame that is all room is one empty rectangle
  const r1 = largestEmptyRect(room, cRoom);
  check('an empty frame is one empty rectangle', r1.pct > 90, `${r1.pct.toFixed(1)}% of frame`);
  // ...and a frame that is all paper has none
  const r2 = largestEmptyRect(page, cPage);
  check('a full frame has no empty rectangle', r2.pct < 1, `${r2.pct.toFixed(1)}% of frame`);
  // THE MUTATION: plant a known hole and require it back within a cell of its size
  const mixed = mk(220);
  for (let y = 0; y < 32; y++) for (let x = 0; x < 32; x++) {
    const i = (y * 64 + x) * 3; mixed.data[i] = mixed.data[i + 1] = mixed.data[i + 2] = 5;
  }
  const r3 = largestEmptyRect(mixed, cutFor(mixed));
  check('a planted 32x32 hole in a 64x64 frame reads ~25%', r3.pct > 20 && r3.pct < 30, `${r3.pct.toFixed(1)}%`);
  console.log(bad === 0 ? 'selftest: ok' : `selftest: ${bad} FAILED`);
  process.exit(bad === 0 ? 0 : 1);
}

const comp = pixels(COMP);
const compCut = cutFor(comp);
const compL = layers(comp, compCut);
const rows = [];
for (const f of fs.readdirSync(SECTIONS).filter((x) => x.endsWith('-dark-1536x1024.png')).sort()) {
  const im = pixels(path.join(SECTIONS, f));
  const cut = cutFor(im);
  const l = layers(im, cut);
  const hole = largestEmptyRect(im, cut);
  const dead = largestUnprintedRect(im, cut);
  rows.push({ page: f.split('-')[0], ...l, hole, dead });
}
rows.sort((a, b) => b.dead.pct - a.dead.pct);   // ordered by how much frame each one wastes
const pad = (s, n) => String(s).padEnd(n);
const num = (v, n = 6) => v.toFixed(1).padStart(n);
console.log(`${pad('page', 12)} ${'paper'.padStart(6)} ${'print'.padStart(6)} ${'ruled'.padStart(6)} ${'floor'.padStart(6)}   ${'bare%'.padStart(6)} ${'dead%'.padStart(6)}  largest unprinted region`);
console.log(`${pad('COMP(teams)', 12)} ${num(compL.paper)} ${num(compL.printed)} ${num(compL.ruled)} ${num(compL.floor)}`);
for (const r of rows) {
  console.log(`${pad(r.page, 12)} ${num(r.paper)} ${num(r.printed)} ${num(r.ruled)} ${num(r.floor)}   ${num(r.hole.pct)} ${num(r.dead.pct)}  ${r.dead.w}x${r.dead.h} at ${r.dead.x},${r.dead.y}`);
}
fs.writeFileSync(path.join(OUT, 'density.json'), JSON.stringify({ comp: compL, pages: rows }, null, 1));
console.log(`\nwrote ${path.join(OUT, 'density.json')}`);
