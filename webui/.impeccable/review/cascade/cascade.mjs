// THE CASCADE DETECTOR (M3-04). Finds the CLASS of defect M2R-01 met three times, rather than any
// one component's symptom: printed text whose ink was decided by a rule that was not written for
// the ground it sits on.
//
// The three known instances, which the control below plants back and must find:
//   1. the base edge label: `.myx-edge-label { color: var(--ink) }` -- the label DECLARED the
//      room's ink while a Flag set it on strip paper (.myx-key).
//   2. the log tail head: `.myx-lt-head` painted strip paper, and the Figure printing `15 new lines`
//      on it kept Figure's ROOM inks (--ink-strong value, --ink-mute unit) at 1.15:1 in the dark
//      room (27f40909's body). The plant restores those two inks under the head.
//   3. the rule's connection cell: `.myx-rule-connection` declared --ink-mute for the whole cell, so
//      the holder edge's state word inherited a mute nobody chose for it.
// Instance 1 is why the rule has two arms. The first design said "flag when the node itself
// declares nothing", and a label that declares the WRONG FAMILY of ink is exactly the node that
// declares something -- so that clause alone cannot see the first instance.
//
// THE RULE, per printed text (an element with its own non-blank text node, visible, with a box):
//   P = the element printing the text.  G = the nearest element at or above P that PAINTS a ground
//   (an opaque background colour).  The ground is ROOM or PAPER by matching its colour to the
//   resolved theme tokens, or, for a ground that is no token (a color-mix, the bay head's tan), to
//   the family whose primary ink reads on it at the higher contrast; only an unreadable colour is
//   counted and never flagged.
//   ARM A, P declares its own colour: flag when that ink belongs to the OTHER ground's family
//     (a room ink on paper, a paper ink on the room). A deliberate mute on its own ground is a
//     choice and is not flagged.
//   ARM B, P declares nothing: D = the nearest ancestor that declares colour. Flag when the colour P
//     inherits is not the ground's primary ink (--ink on the room, --strip-ink on paper). The
//     record says whether D sits below the ground (a container's mute: instance 3) or above it (an
//     ink crossing the ground boundary, which none of the three was: instance 2 is arm A, the
//     Figure's own room inks on the head's paper, as 27f40909's body records).
// "Declares" means an author rule that matches P and sets `color` to something other than
// inherit/unset/currentColor (those ARE inheritance), or an inline style. It is read from the
// stylesheets in the page, so @media rules that apply at the viewport are included.
//
// Usage (from the worktree root, dev server on 5173):
//   node webui/.impeccable/review/cascade/cascade.mjs            the scan, every page, both themes
//   node webui/.impeccable/review/cascade/cascade.mjs --control  the control: plant 1-3, find them
// Exit 0 when the scan ran (findings are data for the review) or the control reproduced all three;
// exit 1 when the control missed one or a page did not render.
import { writeFileSync, mkdirSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, '../../../..');
const { mgmtKey, withChrome, show } = await import(path.join(ROOT, 'dev/web-console/lib/cdp.mjs'));
const { addresses, urlFor } = await import(path.join(ROOT, 'dev/web-console/lib/fixtures.mjs'));

const CONTROL = process.argv.includes('--control');
const SIZE = { w: 1536, h: 1024 };

// The three instances, re-planted as the declarations that were fixed. Each is appended LAST so it
// wins at equal specificity, exactly as the original rule did before its fix.
const PLANTS = {
  'edge-label': { page: 'logs', css: '.myx-edge-label { color: var(--ink); }', expect: { arm: 'A', cls: 'myx-edge-label', ground: 'paper' } },
  'lt-head': { page: 'logs', css: '.myx-lt-head .myx-fig-value { color: var(--ink-strong); } .myx-lt-head .myx-fig-unit { color: var(--ink-mute); }', expect: { arm: 'A', cls: 'myx-fig-value', ground: 'paper', within: 'myx-lt-head' } },
  'rule-cell': { page: 'fleet', css: '.myx-rule-connection { color: var(--ink-mute); }', expect: { arm: 'B', declarerAbove: false, ground: 'room', within: 'myx-rule-connection' } },
};

const SCAN = String.raw`(() => {
  const probe = document.createElement('span');
  document.body.appendChild(probe);
  const resolve = (name) => { probe.style.color = 'var(' + name + ')'; const c = getComputedStyle(probe).color; probe.style.color = ''; return c; };
  const bgResolve = (name) => { probe.style.backgroundColor = 'var(' + name + ')'; const c = getComputedStyle(probe).backgroundColor; probe.style.backgroundColor = ''; return c; };
  const ROOM_GROUNDS = ['--room', '--room-deep', '--bay', '--bay-slot'].map(bgResolve);
  const PAPER_GROUNDS = ['--strip', '--strip-field', '--plate', '--ghost'].map(bgResolve);
  const ROOM_INKS = { '--ink': resolve('--ink'), '--ink-mute': resolve('--ink-mute'), '--ink-strong': resolve('--ink-strong') };
  const PAPER_INKS = { '--strip-ink': resolve('--strip-ink'), '--strip-ink-mute': resolve('--strip-ink-mute') };
  probe.remove();
  const nameOf = (rgb) => {
    for (const [n, v] of Object.entries(ROOM_INKS)) if (v === rgb) return n;
    for (const [n, v] of Object.entries(PAPER_INKS)) if (v === rgb) return n;
    return rgb;
  };

  // every author rule that sets color, with the media it lives under already checked
  const colourRules = [];
  const walk = (rules) => {
    for (const r of rules) {
      if (r.cssRules && r.media) { if (matchMedia(r.media.mediaText).matches) walk(r.cssRules); continue; }
      if (r.cssRules && !r.selectorText) { walk(r.cssRules); continue; }
      if (!r.style || !r.selectorText) continue;
      const v = r.style.getPropertyValue('color').trim().toLowerCase();
      if (!v || v === 'inherit' || v === 'unset' || v === 'currentcolor' || v === 'revert' || v === 'revert-layer') continue;
      const sels = r.selectorText.split(',').map((s) => s.trim()).filter((s) => !s.includes('::'));
      if (sels.length) colourRules.push({ sels, value: v });
    }
  };
  for (const sheet of document.styleSheets) { try { walk(sheet.cssRules); } catch (e) { /* cross-origin */ } }
  const declares = (el) => {
    const inline = el.style && el.style.getPropertyValue('color').trim().toLowerCase();
    if (inline && !['inherit', 'unset', 'currentcolor'].includes(inline)) return inline;
    let found = null;
    for (const r of colourRules) for (const s of r.sels) { try { if (el.matches(s)) found = r.value; } catch (e) { /* :has etc */ } }
    return found;
  };
  // a colour as [r, g, b, a] in 0-255 / 0-1. A ground painted through color-mix() computes as
  // 'color(srgb r g b / a)', not rgb(): reading only rgb() skipped the active rail tab's plate and
  // judged its word against the rail behind it -- 338 false findings on the first scan
  const rgbOf = (c) => {
    let m = c.match(/^rgba?\(([^)]+)\)/);
    if (m) { const p = m[1].split(/[\s,/]+/).filter(Boolean).map(Number); return [p[0], p[1], p[2], p.length > 3 ? p[3] : 1]; }
    m = c.match(/^color\(srgb ([^)]+)\)/);
    if (m) { const p = m[1].split(/[\s/]+/).filter(Boolean).map(Number); return [p[0] * 255, p[1] * 255, p[2] * 255, p.length > 3 ? p[3] : 1]; }
    return null;
  };
  const opaque = (c) => { const p = rgbOf(c); return p !== null && p[3] > 0.5; };
  const lin = (v) => { const x = v / 255; return x <= 0.04045 ? x / 12.92 : ((x + 0.055) / 1.055) ** 2.4; };
  const lum = (c) => { const p = rgbOf(c); return p ? 0.2126 * lin(p[0]) + 0.7152 * lin(p[1]) + 0.0722 * lin(p[2]) : null; };
  const ratio = (a, b) => { const x = lum(a); const y = lum(b); return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05); };
  const groundOf = (el) => { for (let a = el; a && a !== document.documentElement; a = a.parentElement) { const bg = getComputedStyle(a).backgroundColor; if (opaque(bg)) return a; } return document.body; };
  // a ground that is exactly a token is that token's family; one that is not (a color-mix, the bay
  // head's tan) belongs to the family whose PRIMARY INK reads on it at the higher WCAG ratio -- the
  // families exist because each ink was chosen to read on its grounds. Nearest-in-lightness was the
  // first cut and was wrong: the light rail's active tab is a mix as light as the room, while both
  // light inks are near-black, and it flagged 169 words that read at 15:1. Only a colour with no
  // readable lightness stays unclassified.
  const kind = (g) => {
    const bg = getComputedStyle(g).backgroundColor;
    if (ROOM_GROUNDS.includes(bg)) return 'room'; if (PAPER_GROUNDS.includes(bg)) return 'paper';
    if (lum(bg) === null) return null;
    return ratio(ROOM_INKS['--ink'], bg) > ratio(PAPER_INKS['--strip-ink'], bg) ? 'room' : 'paper';
  };
  const classes = (el) => el === document.body ? 'body' : (typeof el.className === 'string' && el.className.trim() ? el.className.trim() : el.tagName.toLowerCase());
  const tag = (el) => el === document.body ? 'body' : (typeof el.className === 'string' && el.className.trim() ? el.className.trim().split(/\s+/)[0] : el.tagName.toLowerCase());
  const inside = (anc, el) => anc !== el && anc.contains(el);

  const findings = []; let printed = 0; let unclassified = 0;
  for (const el of document.querySelectorAll('.myx-console *')) {
    const own = [...el.childNodes].some((n) => n.nodeType === 3 && n.textContent.trim());
    if (!own) continue;
    const box = el.getBoundingClientRect(); const cs = getComputedStyle(el);
    if (!box.width || !box.height || cs.visibility === 'hidden' || cs.display === 'none' || Number(cs.opacity) === 0) continue;
    printed++;
    const g = groundOf(el); const ground = kind(g);
    if (ground === null) { unclassified++; continue; }
    const ink = cs.color; const inkName = nameOf(ink);
    const cr = Math.round(ratio(ink, getComputedStyle(g).backgroundColor) * 100) / 100;
    const primary = ground === 'room' ? ROOM_INKS['--ink'] : PAPER_INKS['--strip-ink'];
    const text = [...el.childNodes].filter((n) => n.nodeType === 3).map((n) => n.textContent.trim()).join(' ').slice(0, 24);
    if (declares(el) !== null) {
      const wrong = ground === 'paper' ? Object.values(ROOM_INKS).includes(ink) : Object.values(PAPER_INKS).includes(ink);
      if (wrong) findings.push({ arm: 'A', cls: tag(el), text, ground, groundEl: tag(g), groundCls: classes(g), ink: inkName, ratio: cr, declarer: tag(el), declarerCls: classes(el), declarerAbove: false });
      continue;
    }
    if (ink === primary) continue;
    let d = el.parentElement; while (d && d !== document.documentElement && declares(d) === null) d = d.parentElement;
    const declarer = d && d !== document.documentElement ? d : null;
    findings.push({ arm: 'B', cls: tag(el), text, ground, groundEl: tag(g), groundCls: classes(g), ink: inkName, ratio: cr, declarer: declarer ? tag(declarer) : 'none', declarerCls: declarer ? classes(declarer) : 'none', declarerAbove: declarer ? (declarer === g ? false : !inside(g, declarer)) : true });
  }
  return JSON.stringify({ printed, unclassified, findings });
})()`;

async function scanPage(send, address, theme, plant) {
  // through about:blank every time: the same address twice in a row is a same-document navigation,
  // which neither re-runs the theme seed nor drops a plant left by the previous visit
  await send('Page.navigate', { url: 'about:blank' });
  await show(send, urlFor(address), SIZE.w, SIZE.h, 3500);
  if (plant) {
    await send('Runtime.evaluate', { expression: `(() => { const s = document.createElement('style'); s.textContent = ${JSON.stringify(plant)}; document.head.appendChild(s); })()` });
    await new Promise((r) => setTimeout(r, 200));
  }
  const r = await send('Runtime.evaluate', { returnByValue: true, expression: SCAN });
  if (r.exceptionDetails || typeof r.result.value !== 'string') throw new Error(`${address} ${theme}: the scan did not run`);
  const out = JSON.parse(r.result.value);
  if (out.printed === 0) throw new Error(`${address} ${theme}: no printed text -- the page did not render`);
  return out;
}

const matches = (f, e) => f.arm === e.arm && f.ground === e.ground
  && (e.cls === undefined || f.cls === e.cls)
  && (e.declarerAbove === undefined || f.declarerAbove === e.declarerAbove)
  // `within` names a class on the declarer or the ground; tag() prints only the first class, and the
  // rule's connection cell is `myx-rule-cell myx-rule-connection`, so the match reads the full list
  && (e.within === undefined || f.declarerCls.split(' ').includes(e.within) || f.groundCls.split(' ').includes(e.within));

if (CONTROL) {
  let ok = true;
  for (const theme of ['dark', 'light']) {
    await withChrome({ 'myx-mgmt-key': mgmtKey(), 'splice.theme': theme }, async (send) => {
      await send('Page.enable', {});
      for (const [name, p] of Object.entries(PLANTS)) {
        const clean = await scanPage(send, p.page, theme, null);
        const planted = await scanPage(send, p.page, theme, p.css);
        const before = clean.findings.filter((f) => matches(f, p.expect)).length;
        const after = planted.findings.filter((f) => matches(f, p.expect));
        const found = after.length > before;
        if (!found) ok = false;
        console.log(`${found ? 'FOUND ' : 'MISSED'} ${theme.padEnd(5)} ${name.padEnd(10)} clean ${before} -> planted ${after.length}${after[0] ? `  e.g. "${after[0].text}" ${after[0].cls} ink ${after[0].ink} on ${after[0].ground} (${after[0].groundEl}), declared by ${after[0].declarer}` : ''}`);
      }
    });
  }
  console.log(ok ? 'CONTROL: all three known instances reproduced in both themes' : 'CONTROL FAILED: an instance was not reproduced');
  process.exit(ok ? 0 : 1);
}

const report = { size: SIZE, pages: {} };
let total = 0;
for (const theme of ['dark', 'light']) {
  await withChrome({ 'myx-mgmt-key': mgmtKey(), 'splice.theme': theme }, async (send) => {
    await send('Page.enable', {});
    for (const address of addresses()) {
      const out = await scanPage(send, address, theme, null);
      report.pages[`${address}/${theme}`] = out;
      total += out.findings.length;
      console.log(`${theme.padEnd(5)} ${address.padEnd(11)} printed ${String(out.printed).padStart(4)}  unclassified ${String(out.unclassified).padStart(3)}  findings ${out.findings.length}`);
    }
  });
}
// group by shape: the same rule on many strips is one finding, not forty
const shapes = new Map();
for (const [where, out] of Object.entries(report.pages)) {
  for (const f of out.findings) {
    const key = `${f.arm} ${f.cls} ink ${f.ink} on ${f.ground} (${f.groundEl}) declared by ${f.declarer}${f.arm === 'B' ? (f.declarerAbove ? ' ABOVE the ground' : ' below the ground') : ''}`;
    const s = shapes.get(key) ?? { key, count: 0, where: new Set(), text: f.text, minRatio: Infinity };
    s.count++; s.where.add(where); s.minRatio = Math.min(s.minRatio, f.ratio); shapes.set(key, s);
  }
}
report.shapes = [...shapes.values()].sort((a, b) => b.count - a.count).map((s) => ({ ...s, where: [...s.where] }));
mkdirSync(HERE, { recursive: true });
writeFileSync(path.join(HERE, 'report.json'), JSON.stringify(report, null, 1));
console.log(`\n${total} findings in ${report.shapes.length} shapes:`);
for (const s of report.shapes) console.log(`  ${String(s.count).padStart(4)}  ${s.key}  e.g. "${s.text}"  min ${s.minRatio}:1  [${s.where.join(' ')}]`);
