// M2-32: WHAT A RACK OF LIKE ROWS SPENDS ON SAYING ITS COLUMN NAMES ONCE PER ROW.
//
// The row hands me the ratio (declared width over content held, per column) and warns that usage
// and compaction may not be the pages it fits: neither is a rack of like rows in the sense fleet
// and logs were. So this instrument measures BOTH AXES and reports which one carries the defect,
// rather than reporting the axis I brought with me.
//
//   ratio      declared px / content px, per column, per bay -- fleet's question. Above 1.5 is a
//              column sized for something it no longer prints; below 1.0 is a column holding a
//              kind of value no width satisfies (logs text at 0.41). Between is healthy.
//   shape      the field counts present in one bay. ONE shape is a rack of like rows, which is
//              the precondition for the bay printing its column names once (m1 design review B9,
//              the mechanism Bay already ships as its `fields` prop and doctor already uses).
//   label      the vertical half, and the one the ratio cannot see: the height a strip loses to
//              printing its own column names, measured by hiding the label spans IN A CLONE and
//              reading the height back. A rack whose every row repeats the bay's column names
//              pays this per row, and the ratio is blind to it because nothing gets wider.
//   clipped    rows cut at the BAY'S SCROLLPORT EDGE, not at their own declared width. M2-30's
//              defect: a logs line fit its 1536px cell and was still cut by the 1342px bay, and
//              counting against the cell gave a null result that would have closed the row.
//
// LAW 34 -- IF EVERY PAGE THREW, WHAT WOULD THIS PRINT? An empty table reads as "no rack repeats
// its labels", which is the conclusion this row must never reach by accident. It refuses instead,
// and it refuses a page that photographed nothing the same way.
const R = '/home/marcos/Documents/dev/projects/mythos/repo/.claude/worktrees/v0.4.0';
const { mgmtKey, withChrome, show } = await import(`${R}/dev/web-console/lib/cdp.mjs`);
const { urlFor } = await import(`${R}/dev/web-console/lib/fixtures.mjs`);

const PAGES = ['usage', 'compaction'];

// NO BACKTICKS INSIDE THIS LITERAL. One in a comment terminated a sibling instrument's template and
// cost a SyntaxError that read like a logic bug; the load-time guard below is why that is mechanical
// now rather than a warning in prose. Unrecognised escapes are EATEN here too (a bare \s arrives as
// s), which is how a split halved a denominator on another row.
const READ = `(() => {
  const vis = (el) => { const r = el.getBoundingClientRect(); const cs = getComputedStyle(el);
    return r.width > 1 && r.height > 1 && cs.visibility !== 'hidden' && cs.display !== 'none'; };
  const px = (n) => Math.round(n * 10) / 10;

  // The unconstrained box: a strip is cloned into it with every declared width removed, so each
  // cell falls back to the width its CONTENT wants. Measuring the live cell instead would report
  // the declaration back to me, which is the denominator-from-the-numerator error.
  const pen = document.createElement('div');
  pen.style.cssText = 'position:absolute;left:-10000px;top:0;width:max-content;visibility:hidden';
  document.body.appendChild(pen);
  const natural = (strip) => {
    const clone = strip.cloneNode(true);
    pen.textContent = '';
    pen.appendChild(clone);
    clone.style.width = 'max-content';
    const cells = Array.from(clone.querySelectorAll('.myx-sfield'));
    for (const cell of cells) { cell.style.width = 'max-content'; cell.style.flex = '0 0 auto'; cell.style.maxWidth = 'none'; }
    const out = cells.map((cell) => px(cell.getBoundingClientRect().width));
    return out;
  };
  // The vertical read, and the whole point of it: the SAME clone with its label spans hidden. The
  // difference is what the rack pays per row to repeat the bay's column names.
  const heightWithoutLabels = (strip) => {
    const clone = strip.cloneNode(true);
    pen.textContent = '';
    pen.appendChild(clone);
    clone.style.width = px(strip.getBoundingClientRect().width) + 'px';
    const before = px(clone.getBoundingClientRect().height);
    for (const label of Array.from(clone.querySelectorAll('.myx-sfield-label'))) label.style.display = 'none';
    return { before, after: px(clone.getBoundingClientRect().height) };
  };

  const bays = [];
  for (const bay of Array.from(document.querySelectorAll('.myx-bay')).filter(vis)) {
    const strips = Array.from(bay.querySelectorAll('.myx-strip')).filter(vis);
    if (strips.length === 0) continue;
    const rows = bay.querySelector('.myx-bay-rows');
    const port = rows === null ? null : rows.getBoundingClientRect();

    // SHAPE first: the bay prints its names once only if every row has the same cells. A row whose
    // cell carries data-span states ONE value across several tracks and is excluded BY DECLARATION
    // (M1-73), the same way anything comparing first-field edges excludes it -- counting it made
    // compaction's outcomes bay report "mixed" when its six outcome rows are identical and the
    // seventh is a declared total.
    const shapes = {}, spans = [];
    for (const strip of strips) {
      if (strip.querySelector('.myx-sfield[data-span]') !== null) { spans.push(1); continue; }
      const n = strip.querySelectorAll('.myx-sfield').length;
      shapes[n] = (shapes[n] || 0) + 1;
    }

    // RATIO per column, over the rows of the DOMINANT shape. A span cell declares several tracks
    // and is excluded by declaration (M1-73's data-span), never by happening not to look.
    const dominant = Number(Object.keys(shapes).sort((a, b) => shapes[b] - shapes[a])[0]);
    const same = strips.filter((s) => s.querySelectorAll('.myx-sfield').length === dominant
                                   && s.querySelector('.myx-sfield[data-span]') === null);
    const declared = [], content = [], names = [];
    for (const strip of same) {
      const cells = Array.from(strip.querySelectorAll('.myx-sfield'));
      const nat = natural(strip);
      cells.forEach((cell, i) => {
        declared[i] = Math.max(declared[i] || 0, px(cell.getBoundingClientRect().width));
        content[i] = Math.max(content[i] || 0, nat[i] || 0);
        const label = cell.querySelector('.myx-sfield-label');
        if (label !== null && !names[i]) names[i] = label.textContent.trim();
      });
    }

    // CLIPPED at the scrollport edge, and whether the overflow can be reached at all.
    let clipped = 0, worst = 0;
    for (const strip of strips) {
      const r = strip.getBoundingClientRect();
      const edge = port === null ? window.innerWidth : Math.min(port.right, window.innerWidth);
      if (r.right > edge + 0.5) { clipped++; worst = Math.max(worst, px(r.right - edge)); }
    }

    const h = heightWithoutLabels(same[0] || strips[0]);
    const labelled = strips.filter((s) => s.querySelector('.myx-sfield-label') !== null).length;
    bays.push({
      label: (bay.querySelector('.myx-bay-label') || {}).textContent || '?',
      rows: strips.length, shapes, spans: spans.length, dominant, names, declared, content,
      labelled,
      hasFieldsRow: bay.querySelector('.myx-bay-fields') !== null,
      stripH: h.before, stripHNoLabels: h.after,
      bayH: px(bay.getBoundingClientRect().height),
      port: port === null ? null : px(port.width),
      scrolls: rows === null ? null : rows.scrollWidth > rows.clientWidth + 1,
      clipped, worst,
    });
  }
  pen.remove();
  const main = document.querySelector('main') || document.body;
  return JSON.stringify({ bays, strips: document.querySelectorAll('.myx-strip').length,
                          text: (main.innerText || '').trim().length,
                          frame: [window.innerWidth, window.innerHeight] });
})()`;

// A warning in prose where the tool can check it is a warning that will be read after the failure.
{
  if (READ.includes('`')) throw new Error('READ contains a backtick: it will terminate its own literal');
}

const out = [];
let fails = 0;
for (const addr of PAGES) {
  try {
    const res = await withChrome({ 'myx-mgmt-key': mgmtKey(), 'splice.theme': 'dark' }, async (send) => {
      await send('Page.enable', {});
      await show(send, urlFor(addr), 1536, 1024, 6000);
      const r = await send('Runtime.evaluate', { expression: READ, returnByValue: true });
      return JSON.parse(r.result.value);
    });
    // A capture that measured an empty page is not a page with no repeated labels (law 23's
    // inverted face): it is named, not silently counted as a clean result.
    if (res.text < 40) {
      console.log(`EMPTY ${addr}: the page rendered ${res.text} characters -- not a reading`);
      fails++;
      continue;
    }
    out.push({ addr, ...res });
    console.log(`\n== ${addr}  ${res.strips} strips, frame ${res.frame[0]}x${res.frame[1]}`);
    for (const bay of res.bays) {
      const shape = Object.entries(bay.shapes).map(([n, c]) => `${n}x${c}`).join(' ');
      const like = Object.keys(bay.shapes).length === 1 ? 'LIKE ROWS' : 'mixed';
      // THE FILL FACTOR, AND WITHOUT IT THE RATIO LIES ON EVERY PAGE BUT THE ONE IT WAS BORN ON.
      // A rack fills its bay by flex-grow = the cell's own ch (M1-39 + strip-field.tsx), so every
      // cell renders ch_i x (1 + slack/sum(ch)) and EVERY ratio on the page carries that same
      // multiplier. The 1.0-1.5 band measured on fleet and logs is therefore a band about those
      // pages' fill, not about columns: dividing it out is what makes the number comparable across
      // pages, and what turns usage's alarming 1.57-2.25 into a flat rack with nothing wrong.
      const sumD = bay.declared.reduce((n, v) => n + v, 0);
      const sumC = bay.content.reduce((n, v) => n + (v || 0), 0);
      const fill = sumC > 0 ? sumD / sumC : 0;
      console.log(`  bay ${bay.label.padEnd(18)} rows ${String(bay.rows).padStart(2)}  shape ${shape.padEnd(10)} ${like}`
        + `${bay.spans ? ` +${bay.spans} span` : ''}  labels per strip ${bay.labelled}/${bay.rows}  bay head prints names: ${bay.hasFieldsRow}`);
      console.log(`      strip h ${bay.stripH}px -> ${bay.stripHNoLabels}px without labels`
        + `   bay h ${bay.bayH}px   port ${bay.port}px  scrolls ${bay.scrolls}  clipped ${bay.clipped}/${bay.rows}${bay.clipped ? ` worst ${bay.worst}px` : ''}`);
      console.log(`      rack fill ${fill.toFixed(2)}x (declared ${sumD.toFixed(0)}px over content ${sumC.toFixed(0)}px) -- normalised ratios divide it out`);
      bay.declared.forEach((d, i) => {
        const c = bay.content[i] || 0;
        console.log(`      col ${String(i).padStart(2)} ${String(bay.names[i] || '-').padEnd(16)} declared ${String(d).padStart(7)}  content ${String(c).padStart(7)}`
          + `  ratio ${c > 0 ? (d / c).toFixed(2) : 'n/a'}  normalised ${c > 0 && fill > 0 ? (d / c / fill).toFixed(2) : 'n/a'}`);
      });
    }
  } catch (e) {
    console.log(`FAIL ${addr}: ${e.message.split('\n')[0]}`);
    fails++;
  }
}
if (fails > 0) {
  console.log(`\nREFUSE: ${fails} page(s) failed or rendered empty. A page that threw is not a page without the defect.`);
  process.exitCode = 1;
}
if (out.length === 0) {
  console.log('REFUSE: no page was measured, which is not the same sentence as "no rack repeats its labels".');
  process.exitCode = 1;
}
