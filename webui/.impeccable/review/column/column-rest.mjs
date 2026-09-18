// M1-116: WHAT THE DETAIL COLUMN COSTS AT REST, AND WHAT THE RACK GETS BACK.
//
// The row rules for the COLLAPSE idiom over UNMOUNT and says not to inherit M1-102's 396px but to
// re-measure it, because collapse must deliver the same room with a better transition and an
// assumed number is how this campaign has lost afternoons. So this measures the same three
// quantities on every page in the fence, at rest and opened:
//
//   bays    the rack area's width -- the number the operator actually reads data in
//   aside   the detail column's width
//   past    how far the widest child is painted PAST THE FRAME -- not past the column. M1-121
//           drew the distinction the earlier read missed: fixed ch widths are the brief's own
//           contract ("strips keep fixed field widths in ch units and scroll inside their bay"),
//           so a strip WIDER THAN ITS COLUMN is correct and past-column-edge is not a defect.
//           What is a defect is paint that lands beyond the FRAME with no scrollport to reach it.
//           So this reports both, plus whether the box can actually be scrolled to the content.
//   gap     the COMPUTED column-gap, which is the idiom's own defect: a grid gap applies between
//           a 1fr track and a 0 track, so a collapsed column still costs --space-4 of the frame.
//           A zero track that still costs a gap is not zero.
//
// It opens a strip by clicking one and POLLING for the proof, rather than sampling the DOM on the
// line after .click() -- that sampled-too-early mistake produced a false "NEVER OPENED" flag on
// M1-80 and was withdrawn. The proof here is the aside gaining width.
//
// LAW 34 -- IF EVERY PAGE THREW, WHAT WOULD THIS PRINT? An empty table reads as "the column costs
// nothing", which is the conclusion this row must never reach by accident. It refuses instead.
const R = '/home/user/Documents/dev/projects/atlas/repo/.claude/worktrees/v0.4.0';
const { mgmtKey, withChrome } = await import(`${R}/.dev/web-console/lib/cdp.mjs`);
const { urlFor } = await import(`${R}/.dev/web-console/lib/fixtures.mjs`);

// PAGES: [address, the body/board element, the rack area, the detail aside]
const PAGES = [
  ['fleet', '.myx-fleet-body', '.myx-fleet-bays', '.myx-fleet-detail'],
  ['models', '.myx-models-body', '.myx-models-bays', '.myx-models-detail'],
  ['sessions', '.myx-sx-board', '.myx-sx-bays', '.myx-sx-detail'],
];

// No backticks inside this literal: one in a comment terminated a sibling instrument's template
// and cost a SyntaxError that read like a logic bug. Unrecognised escapes are EATEN here too.
const READ = (body, bays, aside) => `(() => {
  const w = (sel) => { const el = document.querySelector(sel);
    return el === null ? null : Math.round(el.getBoundingClientRect().width * 10) / 10; };
  const b = document.querySelector('${body}');
  const cs = b === null ? null : getComputedStyle(b);
  // THE OVERFLOW READ: past the column edge (expected, the ch contract), past the FRAME (a
  // defect), and whether the content is REACHABLE -- a clipped box with no scrollport hides it.
  const a = document.querySelector('${aside}');
  let past = null;
  if (a !== null) {
    const ab = a.getBoundingClientRect(), fw = window.innerWidth;
    let col = 0, frame = 0;
    for (const el of a.querySelectorAll('*')) {
      const r = el.getBoundingClientRect();
      if (r.width < 1) continue;
      col = Math.max(col, Math.round((r.right - ab.right) * 10) / 10);
      if (getComputedStyle(a).overflowX === 'visible') frame = Math.max(frame, Math.round((r.right - fw) * 10) / 10);
    }
    // `scrolls` is the whole question for a clipped column: overflow-x auto with
    // scrollWidth === clientWidth is overflow: hidden wearing a better name.
    past = { col, frame, overflowX: getComputedStyle(a).overflowX,
             scrolls: a.scrollWidth > a.clientWidth };
  }
  return JSON.stringify({ past,
    body: w('${body}'), bays: w('${bays}'), aside: w('${aside}'),
    asidePresent: document.querySelector('${aside}') !== null,
    gap: cs === null ? null : cs.columnGap,
    tracks: cs === null ? null : cs.gridTemplateColumns,
    cls: b === null ? null : b.className,
    frame: window.innerWidth,
  });
})()`;

const CLICK = `(() => {
  const s = document.querySelector('.myx-strip');
  if (s === null) return 'no strip';
  (s.querySelector('button, [role="button"], a') ?? s).click();
  return 'clicked';
})()`;

const rows = [];
let fails = 0;
for (const [addr, body, bays, aside] of PAGES) {
  try {
    const r = await withChrome({ 'myx-mgmt-key': mgmtKey(), 'splice.theme': 'dark' }, async (send) => {
      await send('Page.enable', {});
      await send('Page.navigate', { url: urlFor(addr) });
      await new Promise((k) => setTimeout(k, 2500));
      const read = async () => JSON.parse((await send('Runtime.evaluate',
        { expression: READ(body, bays, aside), returnByValue: true })).result.value);
      const rest = await read();
      const gesture = (await send('Runtime.evaluate', { expression: CLICK, returnByValue: true })).result.value;
      // POLL for what the gesture PROVES -- the aside taking width -- never sample the next line
      // TWO POLLS, NOT ONE. The first waits for the gesture's PROOF (the aside taking width); the
      // second waits for the transition to SETTLE (two consecutive reads agreeing). A first cut
      // stopped at the proof and read gaps of 11.1/15.3/11.5px where the rules declare 12/16/12 --
      // the column caught mid-swell. That reads like a defect in the CSS and is a defect in the
      // instrument, which is the same family as sampling the DOM before React has re-rendered.
      let open = rest, proved = false;
      for (let i = 0; i < 40; i++) {
        await new Promise((k) => setTimeout(k, 100));
        open = await read();
        if ((open.aside ?? 0) > 1 && open.bays !== rest.bays) { proved = true; break; }
      }
      let settled = false;
      for (let i = 0; i < 40 && proved; i++) {
        await new Promise((k) => setTimeout(k, 100));
        const next = await read();
        if (next.bays === open.bays && next.gap === open.gap) { settled = true; open = next; break; }
        open = next;
      }
      if (proved && !settled) console.log('   [NOT SETTLED: the open state was still moving]');
      return { rest, open, gesture, proved };
    });
    rows.push({ addr, ...r });
    const d = (r.open.bays ?? 0) - (r.rest.bays ?? 0);
    console.log(`ok ${addr.padEnd(9)} frame ${r.rest.frame}`
      + `  REST bays ${String(r.rest.bays).padStart(7)} aside ${String(r.rest.aside).padStart(6)} gap ${String(r.rest.gap).padStart(6)}`
      + `  OPEN bays ${String(r.open.bays).padStart(7)} aside ${String(r.open.aside).padStart(6)} gap ${String(r.open.gap).padStart(6)}`
      + `  rack gives up ${Math.round(-d * 10) / 10}px on open`
      + (r.proved ? '' : `  [NOT PROVED OPEN: ${r.gesture}]`));
    console.log(`   rest tracks: ${r.rest.tracks}   |   open tracks: ${r.open.tracks}`);
    const p = r.open.past;
    if (p !== null) console.log(`   OPEN overflow: ${p.col}px past the column edge (the ch contract,`
      + ` not a defect), ${p.frame}px past the FRAME, overflow-x:${p.overflowX},`
      + ` ${p.scrolls ? 'scrollable to the rest' : 'nothing to scroll'}`);
  } catch (e) {
    console.log(`FAIL ${addr}: ${e.message.split('\n')[0]}`);
    fails++;
  }
}
if (fails > 0 || rows.length === 0) {
  console.log(`\nREFUSE: ${fails} page(s) failed, ${rows.length} measured. A page that threw is not`
    + ' a page whose column costs nothing.');
  process.exitCode = 1;
}
const unproved = rows.filter((r) => !r.proved).map((r) => r.addr);
if (unproved.length > 0) {
  console.log(`\nINCOMPLETE: the open state was never proved on ${unproved.join(', ')} -- those`
    + ' OPEN columns are the rest state read twice and mean nothing.');
  process.exitCode = 1;
}
