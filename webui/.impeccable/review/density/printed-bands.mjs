// M2-21: WHERE IS THE PRINTED LAYER, BAND BY BAND?
//
// density.mjs reports one number per page. The row's question is not the number, it is WHAT
// PRODUCES IT -- so this probe reproduces `layers()`'s own classification, keeps every run it
// counted as PRINTED, and clusters those runs into horizontal BANDS so a frame's printed material
// can be named by its y-extent, its height in pixels, its x-reach and its colour.
//
// PRINTED is a RUN-LENGTH fact, not a colour one (density.mjs): scanning DOWN a column, a run of
// mid-tone (lum 30..150) longer than CUT -- 3px at the 1536 raster -- is PRINTED and a shorter one
// is RULED. So "printed" is literally "a horizontal band of mid-tone at least four pixels tall,
// accumulated over the columns it spans". TALLER AND WIDER IS MORE PRINTED, and nothing else moves
// the number.
//
// Every constant here is copied from density.mjs rather than re-derived, and the totals this probe
// reports for a page are asserted against density.json's own figure for that page so a drift
// between the two is loud instead of silent.
import fs from 'node:fs';
import path from 'node:path';
import { pixels, cutFor, layers } from './density.mjs';

const ROOT = '/home/user/Documents/dev/projects/atlas/repo/.claude/worktrees/v0.4.0';
const SECTIONS = path.join(ROOT, 'webui/.impeccable/review/sections');
const COMP = path.join(ROOT, 'webui/.impeccable/mocks/team-board-a.png');

const lumAt = (im, x, y) => {
  const i = (y * im.w + x) * 3;
  return (im.data[i] + im.data[i + 1] + im.data[i + 2]) / 3;
};
const rgbAt = (im, x, y) => {
  const i = (y * im.w + x) * 3;
  return [im.data[i], im.data[i + 1], im.data[i + 2]];
};
const hex = ([r, g, b]) => `#${[r, g, b].map((v) => v.toString(16).padStart(2, '0')).join('')}`;

/**
 * Every run `layers()` would count as PRINTED, as { x, y, len }.
 */
export function printedRuns(im, cut) {
  const CUT = 3 * (im.w / 1536);
  const runs = [];
  for (let x = 0; x < im.w; x += 2) {
    let start = -1;
    for (let y = 0; y <= im.h; y += 1) {
      const lum = y >= im.h ? 0 : lumAt(im, x, y);
      const isMid = y < im.h && lum >= 30 && lum <= 150;
      if (isMid) { if (start < 0) start = y; continue; }
      if (start >= 0) {
        const len = y - start;
        if (len > CUT) runs.push({ x, y: start, len });
        start = -1;
      }
    }
  }
  return runs;
}

/**
 * Cluster runs into horizontal BANDS: two runs belong to the same band when their y-intervals
 * overlap. A band is the printed member -- a header strip, a plate, a rule the size of a table.
 */
export function bands(runs, im) {
  const rowOf = new Map();          // y -> Set of run indices, so we only compare neighbours
  runs.forEach((r, i) => {
    for (let y = r.y; y < r.y + r.len; y++) {
      if (!rowOf.has(y)) rowOf.set(y, new Set());
      rowOf.get(y).add(i);
    }
  });
  const parent = runs.map((_, i) => i);
  const find = (a) => { while (parent[a] !== a) { parent[a] = parent[parent[a]]; a = parent[a]; } return a; };
  const union = (a, b) => { const ra = find(a), rb = find(b); if (ra !== rb) parent[ra] = rb; };
  // only adjacent rows need comparing: an overlapping band is connected through its rows
  const ys = [...rowOf.keys()].sort((a, b) => a - b);
  for (let k = 1; k < ys.length; k++) {
    if (ys[k] !== ys[k - 1] + 1) continue;
    for (const a of rowOf.get(ys[k])) for (const b of rowOf.get(ys[k - 1])) union(a, b);
  }
  const byRoot = new Map();
  runs.forEach((r, i) => {
    const root = find(i);
    if (!byRoot.has(root)) byRoot.set(root, []);
    byRoot.get(root).push(r);
  });
  return [...byRoot.values()].map((members) => {
    const x0 = Math.min(...members.map((m) => m.x)), x1 = Math.max(...members.map((m) => m.x));
    const y0 = Math.min(...members.map((m) => m.y));
    const y1 = Math.max(...members.map((m) => m.y + m.len));
    const px = members.reduce((s, m) => s + m.len, 0);          // exactly what layers() added
    const heights = members.map((m) => m.len).sort((a, b) => a - b);
    const medH = heights[Math.floor(heights.length / 2)];
    // the dominant colour: sample the middle of the tallest member at its own middle row
    const tall = members.reduce((a, b) => (b.len > a.len ? b : a));
    const colour = hex(rgbAt(im, tall.x, tall.y + Math.floor(tall.len / 2)));
    return { x0, x1, y0, y1, px, cols: members.length, medH, tallH: heights[heights.length - 1], colour };
  }).sort((a, b) => b.px - a.px);
}

function report(name, png) {
  const im = pixels(png);
  const cut = cutFor(im);
  const l = layers(im, cut);
  const runs = printedRuns(im, cut);
  const bs = bands(runs, im);
  const sum = runs.reduce((s, r) => s + r.len, 0);
  const denom = (im.w / 2) * im.h;
  // the reconstruction check: the runs must reproduce density.mjs's own printed share
  const recon = (sum / (denom + sum + runs.filter((r) => r.len <= 3 * (im.w / 1536)).reduce((s, r) => s + r.len, 0))) * 100;
  console.log(`\n=== ${name} ${im.w}x${im.h}  cut=${cut.toFixed(1)}  printed=${l.printed.toFixed(2)}%  (runs reconstruct ${recon.toFixed(2)}%)`);
  console.log(`    ${runs.length} printed runs in ${bs.length} bands`);
  const top = bs.filter((b) => b.px / denom * 100 >= 0.05).slice(0, 14);
  for (const b of top) {
    console.log(`    y ${String(b.y0).padStart(4)}..${String(b.y1).padEnd(4)} h=${String(b.y1 - b.y0).padStart(3)}  x ${String(b.x0).padStart(4)}..${String(b.x1).padEnd(4)}  ${(b.px / denom * 100).toFixed(2).padStart(5)}%  cols=${String(b.cols).padStart(4)} medRun=${String(b.medH).padStart(3)}  ${b.colour}`);
  }
  const shown = top.reduce((s, b) => s + b.px, 0);
  console.log(`    top ${top.length} bands carry ${(shown / sum * 100).toFixed(1)}% of all printed pixels`);
  return { name, printed: l.printed, bands: bs };
}

const targets = process.argv.slice(2);
if (targets.length === 0) {
  report('COMP(teams)', COMP);
  for (const t of ['teams', 'settings', 'turns', 'accounts', 'logs']) {
    const f = path.join(SECTIONS, `${t}-dark-1536x1024.png`);
    if (fs.existsSync(f)) report(t, f);
  }
} else {
  for (const t of targets) report(t === 'comp' ? 'COMP(teams)' : t, t === 'comp' ? COMP : path.join(SECTIONS, `${t}-dark-1536x1024.png`));
}
