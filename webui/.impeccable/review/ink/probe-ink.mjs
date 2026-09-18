// M1-47: every ink role measured against the plane it ACTUALLY sits on.
// The ground is the composited stack of ancestor background-colors, read from the live DOM,
// not the token an ink was declared against - that substitution is what M1-35 and the E-1
// routing both got wrong.
const R = '/home/marcos/Documents/dev/projects/mythos/repo/.claude/worktrees/v0.4.0';
const { mgmtKey, withChrome, show } = await import(`${R}/dev/web-console/lib/cdp.mjs`);
const { capturePage } = await import(`${R}/dev/web-console/capture.mjs`);
const { addresses, urlFor } = await import(`${R}/dev/web-console/lib/fixtures.mjs`);
const OUT = `${R}/webui/.impeccable/review/ink`;

// ---------------------------------------------------------------- THE GHOST WALL
//
// The ghost is RULED DECORATIVE (M1-52, read off team-board-a.png): it is the motion trail of
// the strip being handed off. The tilted live strip sits directly above it carrying the same
// 14:01, the same GS-42 and the same message at full contrast, and the ghost repeats them
// blurred, rotated and duplicated - its own text garbles to `gs-backend-builderlder2`. It is an
// afterimage, not a second fact, and index.tsx marks both ghost layers `aria-hidden="true"`.
//
// So AA does not apply to it, PROVIDED IT IS NEVER THE ONLY PLACE ITS CONTENT APPEARS. That
// proviso is the whole of the ruling and it is the part that can be walled.
//
// THE WALL NEVER LOOKS AT THE GHOST'S OWN CONTRAST. That is deliberate. A wall phrased as "the
// ghost's text must be faint" is satisfied by fading the ghost until nothing is legible, and
// unreadable-by-design and unreadable-by-accident are identical in a contrast table and opposite
// on screen. This wall asks only: does a node OUTSIDE the ghost carry the same text and clear AA?
// The day the ghost carries something the live strip does not, it has become content and fails.

export const AA = 4.5;

/**
 * The wall's predicate, pure so it can be tested without a browser.
 * @param ghostTexts  text carried inside the ghost
 * @param liveByText  text outside the ghost -> the best contrast ratio it is printed at
 */
export function ghostViolations(ghostTexts, liveByText) {
  const out = [];
  for (const t of ghostTexts) {
    const best = liveByText[t];
    if (best === undefined) out.push({ text: t, why: 'no node outside the ghost carries this text' });
    else if (best < AA) out.push({ text: t, why: `its only sibling outside the ghost is ${best}:1, under ${AA}` });
  }
  return out;
}


const PROBE = `(() => {
  const px = v => parseFloat(v) || 0;
  let unparsed = 0;
  const cvs = document.createElement('canvas').getContext('2d');
  const parse = c => {
    const s = String(c || '').trim();
    if (!s || s === 'transparent' || s === 'none') return null;
    let m = s.match(/rgba?\\(([^)]+)\\)/);
    if (m) { const p = m[1].split(/[\\s,\\/]+/).filter(Boolean).map(Number);
             return {r:p[0],g:p[1],b:p[2],a:p.length>3?p[3]:1}; }
    // color-mix() and color(srgb ...) -- .myx-rail-tab's plate is a color-mix, and a regex that
    // only knows rgb() reads it as transparent and walks past the plate to the rail underneath.
    m = s.match(/color\\(srgb\\s+([\\d.]+)\\s+([\\d.]+)\\s+([\\d.]+)(?:\\s*\\/\\s*([\\d.]+))?\\)/);
    if (m) return {r:+m[1]*255, g:+m[2]*255, b:+m[3]*255, a: m[4]===undefined?1:+m[4]};
    try { cvs.fillStyle = '#000'; cvs.fillStyle = s; const n = cvs.fillStyle;
      if (/^#[0-9a-f]{6}$/i.test(n)) return {r:parseInt(n.slice(1,3),16),g:parseInt(n.slice(3,5),16),b:parseInt(n.slice(5,7),16),a:1};
      const q = n.match(/rgba?\\(([^)]+)\\)/);
      if (q) { const p=q[1].split(/[\\s,\\/]+/).filter(Boolean).map(Number);
               return {r:p[0],g:p[1],b:p[2],a:p.length>3?p[3]:1}; }
    } catch (e) {}
    unparsed++;               // NEVER silently treated as transparent
    return null;
  };
  const over = (f,b) => ({r:f.a*f.r+(1-f.a)*b.r, g:f.a*f.g+(1-f.a)*b.g, b:f.a*f.b+(1-f.a)*b.b, a:1});
  const lum = c => { const f=u=>{u/=255; return u<=0.03928?u/12.92:Math.pow((u+0.055)/1.055,2.4);};
    return 0.2126*f(c.r)+0.7152*f(c.g)+0.0722*f(c.b); };
  const ratio = (a,b) => { const x=lum(a), y=lum(b); return (Math.max(x,y)+0.05)/(Math.min(x,y)+0.05); };
  const hex = c => '#'+[c.r,c.g,c.b].map(v=>Math.round(v).toString(16).padStart(2,'0')).join('').toUpperCase();
  const groundOf = el => {
    const st=[]; let n=el, from=null;
    while (n && n.nodeType===1) {
      const bg=parse(getComputedStyle(n).backgroundColor);
      if (bg && bg.a>0) { st.push(bg); if (from===null) from=(typeof n.className==='string'&&n.className)||n.tagName.toLowerCase();
                          if (bg.a>=1) { from=(typeof n.className==='string'&&n.className)||n.tagName.toLowerCase(); break; } }
      n=n.parentElement;
    }
    if (!st.length) return {c:{r:255,g:255,b:255,a:1}, from:'(none found)'};
    let base=st[st.length-1];
    for (let i=st.length-2;i>=0;i--) base=over(st[i],base);
    return {c:base, from};
  };
  // THE ANCESTOR CHAIN, so an INHERITED colour can be attributed to the rule that set it.
  // A rule such as .myx-rule-clocks setting a color never parents a text node itself - its
  // spans do - so a sweep that joins on the text node's own class reports it as "never rendered"
  // when it renders on all thirteen pages. 66 of 78 unresolved rules were this, not absence.
  const ancestry = el => { const out=[]; let n=el;
    while (n && n.nodeType===1 && out.length<14) {
      // NOTE the doubled backslash: this whole block lives inside the PROBE template literal, and a
      // template literal eats an unrecognised escape, so a bare /\s+/ arrives in the page as /s+/ --
      // which silently split 'myx-console' into 'myx-con' and 'ole' and left 'myx-rule-cell' whole.
      if (typeof n.className==='string' && n.className) out.push(...n.className.split(/\\s+/).filter(Boolean));
      n=n.parentElement; }
    return Array.from(new Set(out)); };
  const rows=[]; const seen=new Set();
  const w=document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
  let t;
  while ((t=w.nextNode())) {
    const s=(t.nodeValue||'').trim(); if (!s) continue;
    const el=t.parentElement; if (!el || seen.has(el)) continue; seen.add(el);
    const r=el.getBoundingClientRect(); if (r.width<1 || r.height<1) continue;
    const cs=getComputedStyle(el);
    if (cs.visibility==='hidden' || cs.display==='none' || px(cs.opacity)===0) continue;
    const fg0=parse(cs.color); if (!fg0) continue;
    const g=groundOf(el); const ground=g.c;
    const fg = fg0.a<1 ? over(fg0,ground) : fg0;
    const size=px(cs.fontSize), weight=parseInt(cs.fontWeight)||400;
    rows.push({ cls: (typeof el.className==='string'&&el.className)||el.tagName.toLowerCase(),
      ink:hex(fg), ground:hex(ground), size:+size.toFixed(1), weight,
      large: size>=24 || (size>=18.66 && weight>=700),
      ratio:+ratio(fg,ground).toFixed(2), groundFrom:g.from, anc:ancestry(el), sample:s.slice(0,28) });
  }
  // THE GHOST WALL, evaluated on the live page. The ghost's own contrast is never consulted.
  const ghostTexts = []; const liveByText = {};
  const inGhost = el => !!(el.closest && el.closest('.myx-board-ghost'));
  const w2 = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
  let t2;
  while ((t2 = w2.nextNode())) {
    const s2 = (t2.nodeValue||'').trim(); if (!s2) continue;
    const el = t2.parentElement; if (!el) continue;
    const r2 = el.getBoundingClientRect(); if (r2.width<1 || r2.height<1) continue;
    if (inGhost(el)) { if (!ghostTexts.includes(s2)) ghostTexts.push(s2); continue; }
    const fg0 = parse(getComputedStyle(el).color); if (!fg0) continue;
    const g2 = groundOf(el); const fg2 = fg0.a<1 ? over(fg0,g2.c) : fg0;
    const rt = ratio(fg2, g2.c);
    if (liveByText[s2] === undefined || rt > liveByText[s2]) liveByText[s2] = +rt.toFixed(2);
  }
  return JSON.stringify({rows, unparsed, ghostTexts, liveByText});
})()`;

const all = [];
const ghostFails = [];
let pageFails = 0;
let ghostChecked = 0;
for (const theme of ['light','dark']) {
  for (const addr of addresses()) {
    const url = urlFor(addr);
    try {
      const rows = await withChrome({ 'myx-mgmt-key': mgmtKey(), 'splice.theme': theme }, async (send) => {
        if (theme === 'light') {
          await capturePage(send, url, 1536, 1024, `${OUT}/${addr}-light-1536x1024.png`, 6000);
        } else {
          await show(send, url, 1536, 1024, 6000);
        }
        const res = await send('Runtime.evaluate', { expression: PROBE, returnByValue: true });
        return JSON.parse(res.result.value);
      });
      if (rows.unparsed > 0) { console.log(`REFUSE ${theme} ${addr}: ${rows.unparsed} colour string(s) the probe could not parse - a ground it cannot read is not a ground it may skip`); process.exitCode = 1; }
      const gv = ghostViolations(rows.ghostTexts || [], rows.liveByText || {});
      if (gv.length) { ghostFails.push({ theme, addr, gv }); console.log(`GHOST WALL ${theme} ${addr}: ${gv.length} violation(s)`); gv.forEach(v => console.log(`    ${v.text!==undefined?JSON.stringify(v.text):''} - ${v.why}`)); }
      ghostChecked += (rows.ghostTexts || []).length;
      rows.rows.forEach(r => all.push({ theme, addr, ...r }));
      console.log(`ok ${theme.padEnd(5)} ${addr.padEnd(11)} ${rows.rows.length} text elements, ${rows.unparsed} unparsed`);
    } catch (e) { console.log(`FAIL ${theme} ${addr}: ${e.message.split('\n')[0]}`); pageFails++; }
  }
}
const fs = await import('node:fs');
fs.writeFileSync(`${OUT}/ink-measurements.json`, JSON.stringify(all));
console.log(`\nwrote ${all.length} measured (ink, ground) pairs`);
if (pageFails > 0) { console.log(`REFUSE: ${pageFails} page(s) failed to render - a page that threw is not a page that passed`); process.exitCode = 1; }
// A WALL WITH AN EMPTY DENOMINATOR IS NOT A PASS. The team board carries two ghost layers, so a
// run that checked zero ghost nodes did not exercise the wall - it skipped it. The first cut of
// this file reported `0 violations` for exactly that reason: `export const AA` sat in the temporal
// dead zone, ghostViolations threw on both teams pages, the per-page catch swallowed it, and the
// only two pages with a ghost were the only two that never ran.
if (ghostChecked === 0) {
  console.log('REFUSE: the ghost wall checked 0 text nodes. The team board renders two .myx-board-ghost layers,');
  console.log('        so zero means the wall did not run, not that it passed.');
  process.exitCode = 1;
} else {
  console.log(`ghost wall: ${ghostChecked} text node(s) inside a ghost checked for a passing sibling, ${ghostFails.length} page(s) in violation`);
}
if (ghostFails.length) process.exitCode = 1;

const SELFTEST = [
  { name: 'a ghost line whose live sibling clears AA passes',
    ghost: ['14:01'], live: { '14:01': 13.02 }, expect: 0 },
  { name: 'a ghost line with NO sibling fails',
    ghost: ['GS-42'], live: {}, expect: 1 },
  { name: 'a ghost line whose only sibling is under AA fails',
    ghost: ['14:01'], live: { '14:01': 3.63 }, expect: 1 },
  { name: 'fading the ghost does not satisfy the wall (the ghost ratio is never consulted)',
    ghost: ['dearm the machine-update sch'], live: {}, expect: 1 },
  { name: 'several ghost lines, one orphaned, reports exactly the orphan',
    ghost: ['14:01', 'GS-42'], live: { '14:01': 13.02 }, expect: 1 },
];

if (process.argv.includes('--selftest')) {
  let bad = 0;
  for (const t of SELFTEST) {
    const got = ghostViolations(t.ghost, t.live).length;
    const ok = got === t.expect;
    if (!ok) bad++;
    console.log(`  ${ok ? 'ok  ' : 'FAIL'} ${t.name} (expected ${t.expect} violation(s), got ${got})`);
  }
  // A wall that cannot fail is not a wall: at least one case must produce a violation.
  const canFail = SELFTEST.some((t) => t.expect > 0 && ghostViolations(t.ghost, t.live).length > 0);
  if (!canFail) { console.log('  FAIL the wall never fired on any synthetic violation'); bad++; }
  console.log(bad === 0
    ? `selftest: ${SELFTEST.length} case(s) passed, and the wall fires on a synthetic violation`
    : `selftest: ${bad} case(s) FAILED`);
  process.exit(bad === 0 ? 0 : 1);
}
