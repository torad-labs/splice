// M1-109, the half the pixels cannot answer: ROWS RENDERED AGAINST ROWS THE LAYOUT COULD HOLD.
//
// A frame tells you how much ground is bare. It cannot tell you whether that ground is a container
// that wanted more rows and did not get them, or a container correctly sized to what it holds --
// and those are the row's two diagnoses, with opposite remedies. So this asks the DOM: for every
// rack on a page, how tall is it, what is its row pitch, how many rows are in it, and how many
// would fit. A rack at 6 of 30 is a layout holding space it does not use; a rack at 6 of 6 that is
// simply short is a page with less to say, and the frame cannot distinguish them.
//
// IT DOES NOT MEASURE THE PAGE HEADER BAND. A first cut here did, and reported 1513 of 1528px used
// on all thirteen addresses -- the same number everywhere, which is the signature of a measurement
// that is not measuring (a full-width divider saturated the rightmost-edge scan). It was removed
// rather than patched: the pixel pass answers that question properly with the largest unprinted
// region, and two instruments guessing at one number is how the campaign got 15/8/25.
const R = '/home/user/Documents/dev/projects/atlas/repo/.claude/worktrees/v0.4.0';
const { mgmtKey, withChrome } = await import(`${R}/.dev/web-console/lib/cdp.mjs`);
const { addresses, urlFor } = await import(`${R}/.dev/web-console/lib/fixtures.mjs`);
const fs = await import('node:fs');

// NOTE ON ESCAPES: this is a template literal and an unrecognised escape is EATEN (a bare \s
// arrives in the page as s). No backticks inside, either -- one in a comment terminated this
// string on a sibling instrument and cost a SyntaxError that read like a logic bug.
const PROBE = `(() => {
  const vis = (el) => { const r = el.getBoundingClientRect(); const cs = getComputedStyle(el);
    return r.width > 1 && r.height > 1 && cs.visibility !== 'hidden' && cs.display !== 'none'; };

  // THE BAY, NOT THE STRIPS' PARENT. The first cut grouped strips by parentElement and reported
  // every rack full -- 7/7, 4/4, 10/10 -- which was true and useless: the slot RAILS are painted by
  // the enclosing .myx-bay, which is taller than the element the strips sit in. On projects the
  // strips' parent is 391px holding 4 rows while the bay runs to the bottom of the frame with
  // roughly fifteen empty rails below them, and the pixel pass measures that region at 43.7% of the
  // whole frame. Measuring the wrong box turned the row's central question into a null result.
  const bays = [];
  for (const bay of Array.from(document.querySelectorAll('.myx-bay, .myx-board-bay')).filter(vis)) {
    const box = bay.getBoundingClientRect();
    const strips = Array.from(bay.querySelectorAll('.myx-strip')).filter(vis);
    const tops = strips.map((s) => s.getBoundingClientRect().top).sort((a, b) => a - b);
    const gaps = [];
    for (let i = 1; i < tops.length; i++) gaps.push(tops[i] - tops[i - 1]);
    gaps.sort((a, b) => a - b);
    const pitch = gaps.length ? gaps[Math.floor(gaps.length / 2)]
      : (strips.length ? strips[0].getBoundingClientRect().height : 0);
    bays.push({
      cls: (typeof bay.className === 'string' ? bay.className : '').slice(0, 32),
      h: Math.round(box.height), w: Math.round(box.width), top: Math.round(box.top),
      rows: strips.length, pitch: Math.round(pitch),
      capacity: pitch > 0 ? Math.floor(box.height / pitch) : strips.length,
    });
  }
  // The header band measurement that stood here is REMOVED, not fixed-in-place: it scanned for the
  // rightmost painted edge inside the band's vertical range and a full-width divider saturated it,
  // so it reported 1513 of 1528px used on all thirteen addresses -- the same number everywhere,
  // which is the signature of a measurement that is not measuring. The pixel pass already answers
  // that question properly with the largest unprinted region, so this probe does not guess at it.
  return JSON.stringify({ bays, strips: document.querySelectorAll('.myx-strip').length,
                          frame: [window.innerWidth, window.innerHeight] });
})()`;

const out = [];
let fails = 0;
for (const addr of addresses()) {
  try {
    const res = await withChrome({ 'myx-mgmt-key': mgmtKey(), 'splice.theme': 'dark' }, async (send) => {
      await send('Page.enable', {});
      await send('Page.navigate', { url: urlFor(addr) });
      await new Promise((r) => setTimeout(r, 2500));
      const r = await send('Runtime.evaluate', { expression: PROBE, returnByValue: true });
      return JSON.parse(r.result.value);
    });
    // the bay with the most UNUSED slots is the one that decides whether the layout holds space
    const worst = res.bays.slice().sort((a, b) => (b.capacity - b.rows) - (a.capacity - a.rows))[0] ?? null;
    const totCap = res.bays.reduce((n, b) => n + b.capacity, 0);
    const totRows = res.bays.reduce((n, b) => n + b.rows, 0);
    out.push({ addr, ...res, worst, totCap, totRows });
    console.log(`ok ${addr.padEnd(11)} bays ${String(res.bays.length).padStart(2)}  rows ${String(totRows).padStart(3)}/${String(totCap).padStart(3)} slots`
      + (worst ? `   emptiest bay ${String(worst.rows).padStart(2)}/${String(worst.capacity).padStart(2)} (pitch ${worst.pitch}px, h ${worst.h})` : '   (no bay)'));
  } catch (e) {
    console.log(`FAIL ${addr}: ${e.message.split('\n')[0]}`);
    fails++;
  }
}
// LAW 34: if every page threw, what would this print? Without this, an empty table reads as
// "no page holds unused rows", which is the conclusion this row must never reach by accident.
if (fails > 0) {
  console.log(`\nREFUSE: ${fails} page(s) failed. A page that threw is not a page with a full rack.`);
  process.exitCode = 1;
}
if (out.length === 0) {
  console.log('REFUSE: no page was measured, which is not the same sentence as "no page wastes space".');
  process.exitCode = 1;
}
fs.writeFileSync(`${R}/webui/.impeccable/review/density/capacity.json`, JSON.stringify(out, null, 1));
console.log(`\nwrote capacity.json for ${out.length} addresses`);
