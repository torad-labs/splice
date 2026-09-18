// M2-26: WHICH RACKS CLIP TODAY, AND CAN THE CLIPPED PART BE REACHED?
//
// The row asks whether the rack's scroll bargain ("strips keep fixed field widths in ch units and
// scroll inside their bay", ui.css:731) needs an affordance. Before pricing one this measures the
// case, per CELL and against each cell's own clipping ancestor, because a strip overhanging by a
// pixel hides nothing and a cell does (design-reviewer's method on accounts).
//
// Every clipped cell gets ONE of three dispositions, because the console clips in more than one
// way and an affordance is only honest where the gesture it advertises works:
//
//   SCROLL    the cell runs past a scrollport that actually scrolls (overflow-x auto|scroll AND
//             scrollWidth > clientWidth). A gesture reaches it. This is the case an affordance is for.
//   VOID      the cell runs past a box that clips and does NOT scroll -- overflow hidden|clip, or
//             auto with scrollWidth === clientWidth (overflow: hidden wearing a better name, which
//             is what logs measured). No gesture reaches it.
//   ELLIPSIS  the cell is whole, but its own TEXT is cut inside it with text-overflow: ellipsis.
//             The ellipsis glyph IS a printed "more exists" -- an affordance the console already has.
//   CUT       as ELLIPSIS but with no ellipsis: text cut inside its cell with nothing printed.
//
// LAW 34, IF EVERY PAGE THREW, WHAT WOULD THIS PRINT? A page with no cells reads as "nothing
// clips". So a page that renders no cell is reported as NOT MEASURED, never as clean.
const R = '/home/user/Documents/dev/projects/atlas/repo/.claude/worktrees/v0.4.0';
const { mgmtKey, withChrome } = await import(`${R}/.dev/web-console/lib/cdp.mjs`);
const { addresses, urlFor } = await import(`${R}/.dev/web-console/lib/fixtures.mjs`);

// Runs IN THE PAGE. Written as a real function and shipped with toString(), so there is no
// template literal to terminate with a stray backtick (the defect column-rest.mjs guards against).
function readPage() {
  const CELLS = '.myx-sfield, .myx-fbox, .myx-board-cell, .myx-cfeed-row, [class*="-cell"]';
  const clips = (cs) => cs.overflowX !== 'visible';
  const scrolls = (el, cs) => (cs.overflowX === 'auto' || cs.overflowX === 'scroll')
    && el.scrollWidth > el.clientWidth + 1;
  const name = (el) => (typeof el.className === 'string' && el.className.trim() !== ''
    ? '.' + el.className.trim().split(/\s+/)[0] : el.tagName.toLowerCase());
  const cells = [...document.querySelectorAll(CELLS)].filter((el) => el.getBoundingClientRect().width >= 1);
  const out = { cells: cells.length, SCROLL: [], VOID: [], ELLIPSIS: 0, CUT: [], ports: {} };
  for (const cell of cells) {
    const box = cell.getBoundingClientRect();
    // the nearest ancestor that clips horizontally is the box that decides what the reader sees
    let anc = cell.parentElement;
    while (anc !== null && anc !== document.body && !clips(getComputedStyle(anc))) anc = anc.parentElement;
    if (anc !== null && anc !== document.body) {
      const cs = getComputedStyle(anc);
      const a = anc.getBoundingClientRect();
      const right = a.left + anc.clientLeft + anc.clientWidth;   // the visible edge, excluding a scrollbar
      const hidden = Math.round(Math.max(0, box.right - right));
      const key = name(anc);
      if (out.ports[key] === undefined) {
        out.ports[key] = { overflowX: cs.overflowX, clientWidth: anc.clientWidth, scrollWidth: anc.scrollWidth,
          scrolls: scrolls(anc, cs), n: 0 };
      }
      out.ports[key].n += 1;
      if (hidden > 1) {
        const whole = box.left >= right;
        const rec = { cell: name(cell), port: key, hidden, whole,
          text: (cell.textContent ?? '').trim().slice(0, 28) };
        if (scrolls(anc, cs)) out.SCROLL.push(rec); else out.VOID.push(rec);
        continue;
      }
    }
    // the cell is whole in its port: is its own text cut inside it?
    for (const t of [cell, ...cell.querySelectorAll('*')]) {
      if (t.scrollWidth <= t.clientWidth + 1 || t.clientWidth === 0) continue;
      const cs = getComputedStyle(t);
      if (!clips(cs)) continue;
      if (cs.textOverflow === 'ellipsis') out.ELLIPSIS += 1;
      else out.CUT.push({ cell: name(cell), el: name(t), hidden: t.scrollWidth - t.clientWidth,
        text: (t.textContent ?? '').trim().slice(0, 28) });
      break;
    }
  }
  return JSON.stringify(out);
}

const OPEN = `(() => { const s = document.querySelector('.myx-strip');
  if (s === null) return 'no strip';
  (s.querySelector('button, [role="button"], a') ?? s).click(); return 'clicked'; })()`;

const all = [];
let fails = 0;
for (const addr of addresses()) {
  try {
    const r = await withChrome({ 'myx-mgmt-key': mgmtKey(), 'splice.theme': 'dark' }, async (send) => {
      await send('Page.enable', {});
      await send('Page.navigate', { url: urlFor(addr) });
      await new Promise((k) => setTimeout(k, 3000));
      const read = async () => JSON.parse((await send('Runtime.evaluate',
        { expression: `(${readPage.toString()})()`, returnByValue: true })).result.value);
      const rest = await read();
      const gesture = (await send('Runtime.evaluate', { expression: OPEN, returnByValue: true })).result.value;
      await new Promise((k) => setTimeout(k, 1500));   // the swell is --dur-2; read after it settles
      const open = gesture === 'clicked' ? await read() : null;
      return { rest, open };
    });
    all.push({ addr, ...r });
  } catch (e) {
    fails += 1;
    console.log(`FAIL ${addr}: ${e.message.split('\n')[0]}`);
  }
}

const line = (addr, state, s) => {
  if (s.cells === 0) return `${addr.padEnd(11)} ${state.padEnd(4)}  NOT MEASURED: no cell rendered`;
  const sum = (xs) => xs.reduce((n, x) => n + x.hidden, 0);
  return `${addr.padEnd(11)} ${state.padEnd(4)}  cells ${String(s.cells).padStart(4)}`
    + `  SCROLL ${String(s.SCROLL.length).padStart(3)} (${String(sum(s.SCROLL)).padStart(5)}px)`
    + `  VOID ${String(s.VOID.length).padStart(3)} (${String(sum(s.VOID)).padStart(5)}px)`
    + `  ELLIPSIS ${String(s.ELLIPSIS).padStart(3)}  CUT ${String(s.CUT.length).padStart(3)} (${String(sum(s.CUT)).padStart(5)}px)`;
};
for (const r of all) {
  console.log(line(r.addr, 'rest', r.rest));
  if (r.open !== null) console.log(line(r.addr, 'open', r.open));
  for (const [state, s] of [['rest', r.rest], ['open', r.open]]) {
    if (s === null) continue;
    for (const [k, p] of Object.entries(s.ports)) {
      if (p.scrollWidth > p.clientWidth + 1 || !p.scrolls) {
        console.log(`      ${state} port ${k.padEnd(22)} overflow-x:${p.overflowX.padEnd(6)} client ${p.clientWidth} scroll ${p.scrollWidth}`
          + `  ${p.scrolls ? 'SCROLLS' : (p.scrollWidth > p.clientWidth + 1 ? 'CLIPS, NO SCROLL' : 'fits')}  (${p.n} cells)`);
      }
    }
    for (const c of [...s.VOID, ...s.CUT].slice(0, 4)) {
      console.log(`      ${state} ${s.VOID.includes(c) ? 'VOID' : 'CUT '} ${c.cell} in ${c.port ?? c.el}: ${c.hidden}px ${c.whole ? '(wholly hidden) ' : ''}"${c.text}"`);
    }
  }
}
import('node:fs').then((fs) => fs.writeFileSync(`${R}/webui/.impeccable/review/column/clip.json`, JSON.stringify(all, null, 1)));
if (fails > 0 || all.length === 0) {
  console.log(`\nREFUSE: ${fails} page(s) failed, ${all.length} measured. A page that threw is not a page that fits.`);
  process.exitCode = 1;
}
