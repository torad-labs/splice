#!/usr/bin/env bun
// scale — what the console looks like at the frame the operator actually uses.
//
// WHY THIS EXISTS. Every capture, comp-diff, hero score and zone split in this campaign was taken
// at 1536x1024 (the comp's own frame) and 1280x800. The operator's monitors are 3840x2160, and
// nobody had ever measured what he is looking at. The first run of this file found the mechanism in
// one line: THE LAYOUT IS IN vw AND THE TYPE AND SPACING ARE IN px. app.css sets --rail-w: 9vw and
// the bays and strips follow the frame - the bay goes 430px to 1075px, exactly 3840/1536 - while
// --text-1..6 and --space-1..8 are fixed pixels. At his desk every container is 2.5 times wider and
// every glyph is the same 14px, so text holds one fifth of the share of the frame it holds in the
// comp. "Small font" and "no proper spacing" are one defect, and neither is a defect at the only
// size we ever looked at.
//
// WHAT IT REPORTS, per viewport: the bay width, the strip height, a field label's font size, and
// the share of the frame covered by rendered text (every text node's own line boxes) and by the
// gaps between sibling boxes. The text share is the number the row is proved by: it must hold
// roughly flat across frames, because a console that is "the 1536 render scaled" holds its text at
// the same share of the screen at any size.
//
// M1-76 DISPOSITION — the two ways a check can be decorative, answered for this file. CLEAN BOTH,
// by being honest about not being a check at all.
//   SHAPE ONE, does every FAIL reach the exit code? THERE IS NO FAIL TO REACH IT. This file prints
//     no FAIL line anywhere and carries no pass/fail verdict; its only non-zero exit is the usage
//     refusal. gate-coverage disposes it TOOL, NEVER GATES, and that disposition is accurate — it
//     is a measuring instrument a human reads, not a gate. Vacuously clean, and the distinction
//     matters: a file that gates nothing AND CLAIMS NOTHING is not decorative. Shape One is a check
//     that prints FAIL and gates nothing, and the lie is in the word FAIL. This file never says it.
//   SHAPE TWO, if every size produced nothing, what would it print? A TABLE WITH NO ROWS, which
//     carries no verdict either way and misleads nobody, because no verify line greps it and no leg
//     runs it. The reason law 34 bites elsewhere is that an empty denominator gets SUMMARISED into
//     a clean verdict; there is no verdict here to be corrupted.
//   IF THAT DISPOSITION EVER CHANGES — if a leg starts running this or a verify line greps its
//     output — both answers change with it, and the first thing it would need is the guard the rest
//     of this fence has: an empty size list, or a page that rendered no text, must refuse.
//
// Usage: bun console/tools scale '<url>' [--sizes 1536x1024,1920x1080,...] [--json]
import process from 'node:process';
import { mgmtKey, show, withChrome } from '../lib/cdp.ts';

const ARGS = process.argv.slice(2);
const flag = (name, dflt) => {
  const i = ARGS.indexOf(`--${name}`);
  return i === -1 ? dflt : ARGS[i + 1];
};
const has = (name) => ARGS.includes(`--${name}`);

/** The comp's frame first, then the sizes a real desk has: 1080p, 1440p, and the operator's 4K. */
export const SIZES = '1536x1024,1920x1080,2560x1440,3840x2160';

const CENSUS = `(() => {
  const frame = innerWidth * innerHeight;
  let text = 0;
  const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
  for (let node = walker.nextNode(); node !== null; node = walker.nextNode()) {
    if (node.textContent.trim() === '') continue;
    const el = node.parentElement;
    if (el === null) continue;
    const style = getComputedStyle(el);
    if (style.display === 'none' || style.visibility === 'hidden' || Number(style.opacity) === 0) continue;
    const range = document.createRange();
    range.selectNodeContents(node);
    for (const rect of range.getClientRects()) text += rect.width * rect.height;
  }
  const boxy = new Set(['block', 'flex', 'grid', 'list-item', 'table', 'table-row', 'flow-root']);
  let gap = 0;
  for (const parent of document.querySelectorAll('*')) {
    const kids = [...parent.children].filter((kid) => {
      const style = getComputedStyle(kid);
      if (style.display === 'none' || style.visibility === 'hidden') return false;
      if (style.position === 'absolute' || style.position === 'fixed') return false;
      return boxy.has(style.display);
    });
    if (kids.length < 2) continue;
    const rects = kids.map((kid) => kid.getBoundingClientRect());
    for (let i = 0; i + 1 < rects.length; i++) {
      const a = rects[i]; const b = rects[i + 1];
      const overV = Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top);
      const overH = Math.min(a.right, b.right) - Math.max(a.left, b.left);
      const across = b.left - a.right; const down = b.top - a.bottom;
      if (overV > 2 && across >= 0) gap += across * overV;
      else if (overH > 2 && down >= 0) gap += down * overH;
    }
  }
  const bay = document.querySelector('.myx-bay');
  const strip = document.querySelector('.myx-strip');
  const label = document.querySelector('.myx-sfield-label') ?? document.querySelector('.myx-bay-label');
  const root = getComputedStyle(document.documentElement).fontSize;
  return {
    frame: { w: innerWidth, h: innerHeight, px: frame },
    bay: bay === null ? null : Math.round(bay.getBoundingClientRect().width),
    strip: strip === null ? null : Math.round(strip.getBoundingClientRect().height * 10) / 10,
    label: label === null ? null : Math.round(parseFloat(getComputedStyle(label).fontSize) * 10) / 10,
    root: Math.round(parseFloat(root) * 10) / 10,
    textPct: (text / frame) * 100,
    gapPct: (gap / frame) * 100,
  };
})()`;

export async function measure(url, sizes = SIZES) {
  const frames = sizes.split(',').map((s) => s.split('x').map(Number));
  return withChrome({ 'myx-mgmt-key': mgmtKey() }, async (send) => {
    const rows = [];
    for (const [at, [width, height]] of frames.entries()) {
      // The first frame NAVIGATES; the rest only resize, and a resize of the same address is a
      // same-document change, so they reload (see show() in lib/cdp.mjs for why that matters).
      await show(send, url, width, height, 4000, { reload: at > 0 });
      const result = await send('Runtime.evaluate', { expression: CENSUS, returnByValue: true });
      rows.push({ viewport: `${width}x${height}`, ...result?.result?.value });
    }
    return rows;
  });
}

if (process.argv[1] !== undefined && import.meta.url.endsWith(process.argv[1].split('/').pop())) {
  const url = ARGS.find((a) => !a.startsWith('--'));
  if (url === undefined || has('help')) {
    console.log("usage: bun console/tools scale '<url>' [--sizes 1536x1024,1920x1080,...] [--json]");
    console.log('  reports, per frame: bay width, strip height, label size, root size,');
    console.log('  and the share of the frame covered by rendered text and by sibling gaps.');
    process.exit(url === undefined && !has('help') ? 2 : 0);
  }
  const rows = await measure(url, flag('sizes', SIZES));
  if (has('json')) {
    console.log(JSON.stringify(rows, null, 2));
  } else {
    console.log(`scale — ${url}\n`);
    const col = (v, w) => String(v).padStart(w);
    console.log(`  ${'frame'.padEnd(11)}${col('bay', 7)}${col('strip', 8)}${col('label', 8)}${col('root', 8)}${col('text%', 8)}${col('gap%', 8)}`);
    for (const r of rows) {
      console.log(`  ${r.viewport.padEnd(11)}`
        + col(r.bay ?? '-', 7)
        + col(r.strip ?? '-', 8)
        + col(r.label === null ? '-' : `${r.label}px`, 8)
        + col(`${r.root}px`, 8)
        + col(`${r.textPct.toFixed(2)}%`, 8)
        + col(`${r.gapPct.toFixed(2)}%`, 8));
    }
  }
}
