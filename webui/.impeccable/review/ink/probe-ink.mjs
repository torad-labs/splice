// M1-47: every ink role measured against the plane it ACTUALLY sits on.
// The ground is the composited stack of ancestor background-colors, read from the live DOM,
// not the token an ink was declared against - that substitution is what M1-35 and the E-1
// routing both got wrong.
const R = '/home/user/Documents/dev/projects/atlas/repo/.claude/worktrees/v0.4.0';
const { mgmtKey, withChrome, show } = await import(`${R}/.dev/web-console/lib/cdp.mjs`);
const { capturePage } = await import(`${R}/.dev/web-console/capture.mjs`);
const { addresses, urlFor } = await import(`${R}/.dev/web-console/lib/fixtures.mjs`);
const OUT = `${R}/webui/.impeccable/review/ink`;

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
      ratio:+ratio(fg,ground).toFixed(2), groundFrom:g.from, sample:s.slice(0,28) });
  }
  return JSON.stringify({rows, unparsed});
})()`;

const all = [];
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
      rows.rows.forEach(r => all.push({ theme, addr, ...r }));
      console.log(`ok ${theme.padEnd(5)} ${addr.padEnd(11)} ${rows.rows.length} text elements, ${rows.unparsed} unparsed`);
    } catch (e) { console.log(`FAIL ${theme} ${addr}: ${e.message.split('\n')[0]}`); }
  }
}
const fs = await import('node:fs');
fs.writeFileSync(`${OUT}/ink-measurements.json`, JSON.stringify(all));
console.log(`\nwrote ${all.length} measured (ink, ground) pairs`);
