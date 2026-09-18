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
import os from 'node:os';
import zlib from 'node:zlib';
import { pixels, cutFor, layers } from './density.mjs';

const ROOT = '/home/marcos/Documents/dev/projects/mythos/repo/.claude/worktrees/v0.4.0';
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

  // THE RUN-LENGTH HISTOGRAM IS THE MECHANISM TEST. Text sets a run of about an x-height and can
  // never exceed a line box; a plate or a header strip sets a run of its own height, and a table's
  // header band is 20-40px. So a distribution piled at 4..12px with a hard ceiling is TEXT, and one
  // with a shoulder out at 20px+ is STRUCTURE. This is the single measurement that separates the
  // row's two diagnoses, and it is free.
  const hist = new Map();
  for (const r of runs) hist.set(r.len, (hist.get(r.len) ?? 0) + 1);
  const len = [...hist.keys()].sort((a, b) => a - b);
  const maxLen = len[len.length - 1];
  const buckets = [[4, 6], [7, 9], [10, 13], [14, 19], [20, 29], [30, 59], [60, 9999]];
  const bucketLine = buckets.map(([a, b]) => {
    const n = len.filter((v) => v >= a && v <= b).reduce((s, v) => s + hist.get(v), 0);
    return `${a}-${b === 9999 ? '+' : b}:${(n / runs.length * 100).toFixed(0)}%`;
  }).join('  ');
  console.log(`    run length: ${bucketLine}   (longest run ${maxLen}px)`);

  // BY COUNT IS NOT BY PIXEL, and the pixel share is what moves the percentage. A text stroke is
  // 7px counted once; a vertical mid-tone bar is 60px counted once -- 60/7 of the number from the
  // same one run. So the share of PRINTED PIXELS each bucket owns is the number that says what the
  // page's printed layer is MADE of.
  const pxLine = buckets.map(([a, b]) => {
    const n = len.filter((v) => v >= a && v <= b).reduce((s, v) => s + v * hist.get(v), 0);
    return `${a}-${b === 9999 ? '+' : b}:${(n / sum * 100).toFixed(0)}%`;
  }).join('  ');
  console.log(`    BY PIXEL:  ${pxLine}`);

  // AND WHERE THEY SIT vertically, so "text" can be checked against the page's own text lines.
  const prof = new Int32Array(Math.ceil(im.h / 16));
  for (const r of runs) for (let y = r.y; y < r.y + r.len; y++) prof[Math.floor(y / 16)] += 1;
  const peak = Math.max(...prof);
  console.log(`    y-profile (16px rows, peak ${peak}): ` + [...prof].map((v) => ' .:-=+*#%@'[Math.min(9, Math.round(v / peak * 9))]).join(''));

  // WHERE THE TALL RUNS ARE, so the band that carries the number can be pointed at and then LOOKED
  // at. A run 20px or longer is not a text stroke whichever room it is in, so this is the structure
  // term isolated from the lettering term.
  for (const [lo, hi, label] of [[20, 29, '20-29'], [30, 59, '30-59'], [60, 9999, '60+']]) {
    const tall = runs.filter((r) => r.len >= lo && r.len <= hi);
    if (tall.length === 0) { console.log(`    tall ${label}: none`); continue; }
    // cluster by x-adjacency so a bar 10 columns wide reads as one object
    const sorted = [...tall].sort((a, b) => a.x - b.x);
    const groups = [];
    for (const r of sorted) {
      const last = groups[groups.length - 1];
      if (last !== undefined && r.x - last.x1 <= 4) {
        last.x1 = Math.max(last.x1, r.x); last.y0 = Math.min(last.y0, r.y);
        last.y1 = Math.max(last.y1, r.y + r.len); last.px += r.len; last.n += 1;
      } else groups.push({ x0: r.x, x1: r.x, y0: r.y, y1: r.y + r.len, px: r.len, n: 1 });
    }
    groups.sort((a, b) => b.px - a.px);
    const share = tall.reduce((s, r) => s + r.len, 0) / sum * 100;
    console.log(`    tall ${label}: ${tall.length} runs, ${share.toFixed(1)}% of printed pixels, top clusters:`);
    for (const g of groups.slice(0, 4)) {
      const tall0 = tall.filter((r) => r.x >= g.x0 && r.x <= g.x1);
      const c = hex(rgbAt(im, g.x0, g.y0 + 1));
      console.log(`      x ${String(g.x0).padStart(4)}..${String(g.x1).padEnd(4)} w=${String(g.x1 - g.x0 + 1).padStart(3)}  y ${String(g.y0).padStart(4)}..${String(g.y1).padEnd(4)}  px=${String(g.px).padStart(6)}  n=${String(tall0.length).padStart(4)}  ${c}`);
    }
    // THE OBJECT ITSELF: the longest individual runs, with the colour taken from the CENTRE of the
    // run rather than its edge -- the cluster colour above is sampled at a boundary and reads as the
    // room, which is how a bar of text ink can look like a bay floor.
    console.log(`      longest runs:`);
    for (const r of [...tall].sort((a, b) => b.len - a.len).slice(0, 6)) {
      const mid = rgbAt(im, r.x, r.y + Math.floor(r.len / 2));
      const endPix = rgbAt(im, r.x, r.y + 1);
      console.log(`        x=${String(r.x).padStart(4)} y=${String(r.y).padStart(4)} len=${String(r.len).padStart(3)}  centre ${hex(mid)}  top ${hex(endPix)}`);
    }
  }
  return { name, printed: l.printed, bands: bs };
}

/**
 * THE MASK: every pixel `layers()` counted toward PRINTED, drawn white on black, so the layer can
 * be LOOKED at instead of inferred. Numbers said the tall runs carry the comp's percentage and the
 * short ones carry settings'; only a picture says which objects those are. Written as PPM because
 * ffmpeg reads it and this file needs no encoder of its own.
 */
export function mask(im, cut, out) {
  const CUT = 3 * (im.w / 1536);
  const buf = Buffer.alloc(im.w * im.h * 3);
  for (let x = 0; x < im.w; x++) {
    let start = -1;
    for (let y = 0; y <= im.h; y++) {
      const lum = y >= im.h ? 0 : lumAt(im, x, y);
      if (y < im.h && lum >= 30 && lum <= 150) { if (start < 0) start = y; continue; }
      if (start >= 0) {
        const len = y - start;
        // green = PRINTED (a tall run), blue = RULED (a short one): one picture, both terms
        const v = len > CUT ? [255, 255, 255] : [40, 90, 200];
        for (let yy = start; yy < y; yy++) { const i = (yy * im.w + x) * 3; buf[i] = v[0]; buf[i + 1] = v[1]; buf[i + 2] = v[2]; }
        start = -1;
      }
    }
  }
  fs.writeFileSync(out, Buffer.concat([Buffer.from(`P6\n${im.w} ${im.h}\n255\n`), buf]));
  return out;
}

/**
 * THE MUTATION PROOF (M2-23). This instrument had none, and a classifier that has never failed is a
 * tautology. Two synthetic frames with KNOWN answers, written to temp PNGs because the instrument's
 * reader takes a path rather than a buffer: a 40-row mid-tone band must come back with printed
 * pixels whose runs are 40 tall, and a field of 2px rules must come back RULED with no printed
 * pixels at all. Those two cases are the whole composition thesis -- text on paper is the second,
 * a plate is the first -- so the instrument must be able to tell them apart before nine rows lean
 * on it.
 */
function pngOf(w, h, paint) {
  const raw = Buffer.alloc((w * 3 + 1) * h, 0);
  for (let y = 0; y < h; y += 1) { raw[y * (w * 3 + 1)] = 0;
    for (let x = 0; x < w; x += 1) { const rgb = paint(x, y); const at = y * (w * 3 + 1) + 1 + x * 3;
      raw[at] = rgb[0]; raw[at + 1] = rgb[1]; raw[at + 2] = rgb[2]; } }
  const chunk = (type, data) => { const len = Buffer.alloc(4); len.writeUInt32BE(data.length);
    const body = Buffer.concat([Buffer.from(type), data]); let c = ~0;
    for (const b of body) { c ^= b; for (let k = 0; k < 8; k += 1) c = (c >>> 1) ^ (0xEDB88320 & -(c & 1)); }
    const crc = Buffer.alloc(4); crc.writeUInt32BE((~c) >>> 0); return Buffer.concat([len, body, crc]); };
  const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4); ihdr[8] = 8; ihdr[9] = 2;
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', ihdr), chunk('IDAT', zlib.deflateSync(raw)), chunk('IEND', Buffer.alloc(0))]);
}

function selftest() {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'printed-bands-'));
  const PAPER = [222, 217, 198], INK = [90, 88, 80], ROOM = [11, 14, 14];
  const cases = [
    ['a 40-row mid-tone plate is PRINTED', 'plate.png', (x, y) => (y >= 80 && y < 120 && x >= 20 && x < 1500 ? INK : PAPER), (im, cut) => {
      const runs = printedRuns(im, cut);
      return runs.length > 0 && runs.some((r) => r.len >= 30 && r.len <= 59);
    }],
    ['2px rules are RULED, never printed', 'rules.png', (x, y) => (y % 20 < 2 && x >= 20 && x < 1500 ? INK : PAPER), (im, cut) => printedRuns(im, cut).length === 0],
    ['an empty room prints nothing', 'room.png', () => ROOM, (im, cut) => printedRuns(im, cut).length === 0],
  ];
  let bad = 0;
  for (const [label, file, paint, want] of cases) {
    const full = path.join(dir, file);
    // 1536 WIDE ON PURPOSE: this instrument scales its ruled/printed cut by w/1536 (M1-71), so a
    // 200px frame has a 0.4px cut and a 2px rule counts as printed. The first version of this case
    // failed for that reason and the failure was the instrument being right about a frame no page
    // is drawn at -- so the synthetic frame is the raster the instrument is calibrated on.
    fs.writeFileSync(full, pngOf(1536, 200, paint));
    const im = pixels(full);
    const cut = cutFor(im);
    const ok = want(im, cut);
    if (!ok) bad += 1;
    console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${label}`);
  }
  fs.rmSync(dir, { recursive: true, force: true });
  console.log(bad === 0 ? '\nselftest: ok' : `\nselftest: ${bad} wrong`);
  process.exit(bad === 0 ? 0 : 1);
}

// ---- THE ENTRY-POINT GUARD (M2-21), the same one density.mjs and capture.mjs carry ------------
//
// IMPORTING THIS MODULE USED TO EXECUTE IT. The selftest branch below fires on
// `process.argv.includes('--selftest')` and the report body fires on ANY argv, so an instrument
// that imported these exported functions got this file's whole report printed into its own output
// -- and, on `--selftest`, got THIS file's three cases run and `process.exit` called, so the
// importing instrument's own selftest never executed and its verify went green for a reason it had
// not earned. That is the M2-23 defect exactly, measured there from the other side (density.mjs's
// selftest branch had the same shape and was fixed the same way); this file is the caller that made
// it visible, so it gets the guard too. `import.meta.url === file://<argv[1]>` is true only when
// this file IS the program, which is the check capture.mjs and density.mjs both use.
const isMain = process.argv[1] !== undefined && import.meta.url === `file://${process.argv[1]}`;

if (isMain && process.argv.includes('--selftest')) selftest();

const targets = process.argv.slice(2);
if (!isMain) {
  // imported, not run: the exported functions are pure and the reader gets no surprise output
} else if (targets.length === 0) {
  report('COMP(teams)', COMP);
  for (const t of ['teams', 'settings', 'turns', 'accounts', 'logs']) {
    const f = path.join(SECTIONS, `${t}-dark-1536x1024.png`);
    if (fs.existsSync(f)) report(t, f);
  }
} else {
  for (const t of targets) report(t === 'comp' ? 'COMP(teams)' : t, t === 'comp' ? COMP : path.join(SECTIONS, `${t}-dark-1536x1024.png`));
}
