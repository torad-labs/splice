// M1-80: THE SEVENTY-THREE UNEXERCISED RULES, DRIVEN UNTIL THEY RENDER OR ARE PROVEN NOT TO.
//
// M1-52 enumerated 187 colour rules FROM THE SOURCE and gave 114 of them a disposition from a
// measurement. The other 73 never rendered in any capture, so nothing could be said about them --
// and a rule that never renders is not thereby safe. Both known D7 instances were found BY EYE
// before any instrument caught them, and instance 9 was PREDICTED by D7's own comment in
// controls.css:91 ("Nothing had put an Input on a strip yet; the page sweep will"). The 73 are
// that sentence 73 times.
//
// WHY THIS PROBES BY SELECTOR AND NOT BY TEXT NODE. probe-ink.mjs walks text nodes and attributes
// each to a rule through its class chain. That is the right instrument for "what does the operator
// read on this page", and the wrong one here, because it can only see a rule that (a) parents a
// non-empty text node and (b) is currently rendered. This file asks the opposite question --
// given a RULE, can anything in the console make it paint? -- so it starts from the selector,
// finds the element, and measures the element. That difference is why 66 of M1-52's first 78
// unresolved rules turned out to be attribution failures rather than absences.
//
// THREE DISPOSITIONS, AND ABSENCE IS NOT ONE (the row's own law, and M1-52's for the 187):
//   EXERCISED -- something reaches it; its measured ratio against its real ground is on the record
//   DEAD      -- nothing in the console builds it, and here is the evidence
//   DEFERRED  -- reachable, but the fixture is real work; what it needs is named
// A rule in none of the three fails this row BY NAME, in the report at the bottom.
const R = '/home/marcos/Documents/dev/projects/mythos/repo/.claude/worktrees/v0.4.0';
const { mgmtKey, withChrome } = await import(`${R}/dev/web-console/lib/cdp.mjs`);
const { addresses, urlFor } = await import(`${R}/dev/web-console/lib/fixtures.mjs`);
const OUT = `${R}/webui/.impeccable/review/ink`;
const fs = await import('node:fs');

/** The denominator, taken from M1-52's record rather than retyped: 73 rows or this file is wrong. */
export function unexercised(dispositions) {
  return dispositions.filter((rule) => String(rule.disposition).startsWith('UNEXERCISED'));
}

/**
 * Split a selector into the part that can be QUERIED and the states that must be FORCED.
 *
 * A `:hover` rule cannot be found with querySelectorAll -- nothing is hovered -- so the element is
 * located without it and the state is forced onto that element afterwards. An ATTRIBUTE selector is
 * never stripped: `[disabled]` is real DOM state, and stripping it would measure an enabled control
 * and report the disabled rule as rendering. That distinction is the whole correctness of this file.
 */
export const FORCEABLE = ['hover', 'focus', 'focus-visible', 'focus-within', 'active', 'disabled', 'enabled', 'checked', 'placeholder-shown'];

export function splitSelector(sel) {
  const forced = [];
  let base = sel;
  // `:not(...)` is lifted out WHOLE before anything is stripped. Guarding only its opening token is
  // not enough: the inner `:disabled` of `:not(:disabled)` constrains WHICH elements match and is
  // not a state to force, and stripping it left the selector as `:not()`, which matches nothing and
  // would have reported the rule dark. The selftest below caught exactly that.
  const held = [];
  for (;;) {
    const at = base.indexOf(':not(');
    if (at < 0) break;
    const close = base.indexOf(')', at);
    if (close < 0) break;
    held.push(base.slice(at, close + 1));
    base = base.slice(0, at) + '\u0001' + (held.length - 1) + '\u0001' + base.slice(close + 1);
  }
  for (const state of FORCEABLE) {
    const token = ':' + state;
    // only a whole token, never a prefix of a longer one (`:focus` inside `:focus-visible`)
    const parts = base.split(token);
    if (parts.length > 1) {
      base = parts.reduce((acc, part, i) => {
        if (i === 0) return part;
        const nextChar = part.charAt(0);
        const isWholeToken = nextChar === '' || !/[a-z-]/.test(nextChar);
        if (!isWholeToken) return acc + token + part;
        if (!forced.includes(state)) forced.push(state);
        return acc + part;
      }, '');
    }
  }
  for (let i = 0; i < held.length; i++) base = base.split('\u0001' + i + '\u0001').join(held[i]);
  // a pseudo-ELEMENT is not a state; it is measured through getComputedStyle's second argument
  let pseudo = null;
  const pseudoAt = base.indexOf('::');
  if (pseudoAt >= 0) { pseudo = base.slice(pseudoAt); base = base.slice(0, pseudoAt); }
  return { base: base.trim(), forced, pseudo };
}

// ------------------------------------------------------------------ the in-page measurement
//
// NOTE ON ESCAPES, twice paid for on this row's siblings: this is a template literal, and an
// unrecognised escape is EATEN, so a bare /\s+/ arrives in the page as /s+/. Every backslash below
// is doubled deliberately. Backticks are not used inside it at all -- one in a comment terminated
// the string and cost a SyntaxError that looked like a logic bug.
const MEASURE = (targetsJson) => `(() => {
  const TARGETS = ${targetsJson};
  const cvs = document.createElement('canvas').getContext('2d');
  let unparsed = 0;
  const parse = (c) => {
    const s = String(c || '').trim();
    if (!s || s === 'transparent' || s === 'none') return null;
    let m = s.match(/rgba?\\(([^)]+)\\)/);
    if (m) { const p = m[1].split(/[\\s,\\/]+/).filter(Boolean).map(Number);
             return {r:p[0],g:p[1],b:p[2],a:p.length>3?p[3]:1}; }
    m = s.match(/color\\(srgb\\s+([\\d.]+)\\s+([\\d.]+)\\s+([\\d.]+)(?:\\s*\\/\\s*([\\d.]+))?\\)/);
    if (m) return {r:+m[1]*255, g:+m[2]*255, b:+m[3]*255, a: m[4]===undefined?1:+m[4]};
    try { cvs.fillStyle = '#000'; cvs.fillStyle = s; const n = cvs.fillStyle;
      if (/^#[0-9a-f]{6}$/i.test(n)) return {r:parseInt(n.slice(1,3),16),g:parseInt(n.slice(3,5),16),b:parseInt(n.slice(5,7),16),a:1};
      const q = n.match(/rgba?\\(([^)]+)\\)/);
      if (q) { const p=q[1].split(/[\\s,\\/]+/).filter(Boolean).map(Number);
               return {r:p[0],g:p[1],b:p[2],a:p.length>3?p[3]:1}; }
    } catch (e) {}
    unparsed++;
    return null;
  };
  const over = (f,b) => ({r:f.a*f.r+(1-f.a)*b.r, g:f.a*f.g+(1-f.a)*b.g, b:f.a*f.b+(1-f.a)*b.b, a:1});
  const lum = (c) => { const f=(u)=>{u/=255; return u<=0.03928?u/12.92:Math.pow((u+0.055)/1.055,2.4);};
    return 0.2126*f(c.r)+0.7152*f(c.g)+0.0722*f(c.b); };
  const ratio = (a,b) => { const x=lum(a), y=lum(b); return (Math.max(x,y)+0.05)/(Math.min(x,y)+0.05); };
  const hex = (c) => '#'+[c.r,c.g,c.b].map((v)=>Math.round(v).toString(16).padStart(2,'0')).join('').toUpperCase();
  // THE GROUND IS COMPOSITED FROM THE ANCESTORS, never the token the ink was declared against.
  // Assuming the declared plane is what M1-35 got wrong and what nearly shipped again on M1-75.
  const groundOf = (el) => {
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
  const out = [];
  for (const t of TARGETS) {
    let nodes = [];
    try { nodes = Array.from(document.querySelectorAll(t.base)); }
    catch (e) { out.push({ key:t.key, found:0, error:'bad selector: '+e.message }); continue; }
    const visible = nodes.filter((el) => {
      const r = el.getBoundingClientRect();
      const cs = getComputedStyle(el);
      return r.width >= 1 && r.height >= 1 && cs.visibility !== 'hidden' && cs.display !== 'none' && (parseFloat(cs.opacity)||0) > 0;
    });
    if (!visible.length) { out.push({ key:t.key, found:nodes.length, visible:0 }); continue; }
    // WHICH ELEMENT ACTUALLY PAINTS THIS RULE'S COLOUR ONTO TEXT.
    //
    // A rule may win on an element that directly parents no text: the bare 'a' rule setting --ink
    // wins on every rail tab, but the only text under it lives in .myx-edge-label, which overrides to
    // --strip-ink. Measuring the tab's own colour against the plate reported 1.21:1 and looked like
    // a tenth D7 instance; the label is near-black on the plate and perfectly readable, exactly as
    // M1-36's crops show. So a rule is only credited with painting text it is STILL THE WINNER for:
    // a descendant text node whose parent computes the SAME colour as the matched element. A rule
    // that paints none is not "fine" and not "dark" -- it is recorded as inherited-then-overridden.
    const painter = (root2, pseudo) => {
      if (pseudo) return root2;
      // A FORM CONTROL PAINTS ITS OWN VALUE, and that value is not a text node -- an input's text
      // lives in the control's shadow content, so a TreeWalker over the light DOM finds nothing and
      // would report .myx-fbox-input and .myx-lt-field input as painting nothing at all. The
      // control itself is the painter.
      const tag = root2.tagName;
      if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') return root2;
      const want = getComputedStyle(root2).color;
      const w = document.createTreeWalker(root2, NodeFilter.SHOW_TEXT);
      let n;
      while ((n = w.nextNode())) {
        if (!(n.nodeValue || '').trim()) continue;
        const pe = n.parentElement;
        if (!pe) continue;
        const r2 = pe.getBoundingClientRect();
        if (r2.width < 1 || r2.height < 1) continue;
        if (getComputedStyle(pe).color === want) return pe;
      }
      return null;
    };
    let el = null;
    for (const cand of visible) { el = painter(cand, t.pseudo); if (el) break; }
    if (!el) {
      out.push({ key:t.key, found:nodes.length, visible:visible.length,
                 note:'inherited-then-overridden: the rule wins on the element but every text node under it is repainted by a more specific rule' });
      continue;
    }
    const cs = getComputedStyle(el, t.pseudo || undefined);
    const fg0 = parse(cs.color);
    if (!fg0) { out.push({ key:t.key, found:nodes.length, visible:visible.length, note:'no colour' }); continue; }
    const g = groundOf(el);
    const fg = fg0.a < 1 ? over(fg0, g.c) : fg0;
    const size = parseFloat(cs.fontSize)||0, weight = parseInt(cs.fontWeight)||400;
    // A pseudo-element carries no text node of its own; its own content is what it prints.
    const text = t.pseudo ? String(cs.content||'').slice(0,28)
      : (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' ? String(el.value||el.placeholder||'(empty)').slice(0,28)
      : (el.textContent||'').trim().slice(0,28));
    out.push({
      key: t.key, found: nodes.length, visible: visible.length,
      ink: hex(fg), ground: hex(g.c), groundFrom: g.from,
      size: +size.toFixed(1), weight,
      large: size >= 24 || (size >= 18.66 && weight >= 700),
      ratio: +ratio(fg, g.c).toFixed(2),
      text,
    });
  }
  return JSON.stringify({ out, unparsed, href: location.hash });
})()`;

/**
 * Force a set of pseudo-classes onto every element matching a selector, through CDP.
 * Returns how many nodes were actually forced, so a state that reached nothing says so rather
 * than leaving the caller to read an unchanged measurement as "the rule is fine".
 */
export async function forceStates(send, base, states) {
  const { root } = await send('DOM.getDocument', { depth: -1, pierce: true });
  const { nodeIds } = await send('DOM.querySelectorAll', { nodeId: root.nodeId, selector: base });
  for (const nodeId of nodeIds) {
    await send('CSS.forcePseudoState', { nodeId, forcedPseudoClasses: states });
  }
  return nodeIds.length;
}

// ------------------------------------------------------------------ the openers
//
// An overlay no capture ever opened is not a rule that CANNOT render; it is a rule nothing CLICKED.
// Each opener is a named gesture performed on the page before measuring, and each one names what
// its success would PROVE -- a selector that exists only once the gesture worked.
//
// THE GESTURE AND ITS PROOF ARE SEPARATE, AND THE PROOF IS ALLOWED TO WAIT. The first cut read the
// DOM synchronously on the line after .click(), before React had re-rendered, so four openers
// reported NEVER OPENED while their rules were being measured two hundred milliseconds later with
// those openers' names attached. The flag printed the same words whether the gesture had missed or
// my wait had been too short, which is law 34 exactly, and it is why `proves` is polled rather
// than sampled. A gesture that genuinely reaches nothing still reports NEVER OPENED, and that
// still fails the run -- an opener nobody can trust must not be read as evidence of absence.
export const OPENERS = [
  { name: 'base', gesture: 'true', proves: null },

  // Every address but teams, logs and compaction carries the views bar; its edit key opens the
  // editor that owns .myx-views-field-label and the three .myx-views-action rules.
  { name: 'views-edit', gesture: `(() => { const b = document.querySelector('.myx-views-edit'); if (!b) return false; b.click(); return true; })()`,
    proves: '.myx-views-field, .myx-views-action, .myx-views-field-label' },

  // A rack row opens its detail panel: the four *-detail-name rules and the waterfall live there.
  //
  // EVERY ROW IS TRIED, NOT THE FIRST. shared/ui/strip.tsx puts onClick on the .myx-strip div
  // itself, so a synthetic click is the right gesture -- but the first strip on most addresses is a
  // header or summary row whose `open` does nothing, and clicking only that one reported the detail
  // panel unreachable on 20 of 26 page-themes. It is reachable; the gesture was landing on the
  // wrong row.
  { name: 'detail', gesture: `(async () => {
      const proof = '.myx-fleet-detail-name, .myx-tn-detail-name, .myx-sx-detail-name, .myx-px-detail-name, .myx-wf-legend-name';
      const rows = Array.from(document.querySelectorAll('.myx-strip')).slice(0, 12);
      if (!rows.length) return false;
      for (const row of rows) {
        row.click();
        for (let i = 0; i < 6; i++) {
          if (document.querySelector(proof) !== null) return true;
          await new Promise((r) => setTimeout(r, 80));
        }
      }
      return false;
    })()`,
    proves: '.myx-fleet-detail-name, .myx-tn-detail-name, .myx-sx-detail-name, .myx-px-detail-name, .myx-wf-legend-name' },

  // The palette is mounted in App.tsx for every address and opens on the world's own shortcut,
  // which it listens for on `document` (features/palette/index.tsx:40).
  { name: 'palette', gesture: `(() => { document.dispatchEvent(new KeyboardEvent('keydown', { key: 'k', ctrlKey: true, bubbles: true })); return true; })()`,
    proves: '.myx-palette-input, .myx-palette-item, .myx-palette-empty' },

  // The palette with a query NOTHING matches: the only way .myx-palette-empty can paint. Typing
  // through the value setter plus an input event is how a React-controlled field takes a value;
  // assigning .value alone updates the DOM and never tells React, so the list never re-filters.
  { name: 'palette-empty', gesture: `(async () => {
      document.dispatchEvent(new KeyboardEvent('keydown', { key: 'k', ctrlKey: true, bubbles: true }));
      for (let i = 0; i < 20 && !document.querySelector('.myx-palette-input'); i++) await new Promise((r) => setTimeout(r, 100));
      const box = document.querySelector('.myx-palette-input');
      if (!box) return false;
      const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
      setter.call(box, 'zzzzz-no-such-command-zzzzz');
      box.dispatchEvent(new Event('input', { bubbles: true }));
      return true;
    })()`,
    proves: '.myx-palette-empty' },

  // compaction's reveal key opens .myx-reveal-body.
  { name: 'reveal', gesture: `(() => { const b = document.querySelector('.myx-reveal-btn'); if (!b) return false; b.click(); return true; })()`,
    proves: '.myx-reveal-body' },

  // accounts: the head strip opens the login flow that owns the account-login sheet.
  { name: 'account', gesture: `(() => { const s = document.querySelector('.myx-head-auth-strip, .myx-acct-open, .myx-strip'); if (!s) return false; s.click(); return true; })()`,
    proves: '.myx-acct-name, .myx-acct-code, .myx-acct-btn, .myx-acct-note' },
];

/** Poll for what an opener PROVES, so a slow re-render is never read as a gesture that missed. */
export function provePoll(selector, budgetMs) {
  return `(async () => {
    const deadline = Date.now() + ${budgetMs};
    for (;;) {
      if (document.querySelector(${JSON.stringify(selector)}) !== null) return true;
      if (Date.now() > deadline) return false;
      await new Promise((r) => setTimeout(r, 100));
    }
  })()`;
}

export function buildTargets(rules) {
  return rules.map((rule) => {
    const { base, forced, pseudo } = splitSelector(rule.sel);
    return { key: `${rule.file}:${rule.line}`, sel: rule.sel, base, forced, pseudo, ink: rule.ink };
  });
}

// ------------------------------------------------------------------ the sweep
if (process.argv.includes('--selftest')) {
  // splitSelector is the one piece of logic here that can be wrong silently, so it is proven
  // against the exact shapes the 73 contain, INCLUDING the one that must NOT be split.
  const cases = [
    ['.myx-views-action:hover:not(:disabled)', '.myx-views-action:not(:disabled)', ['hover'], null],
    ['.myx-views-action:disabled', '.myx-views-action', ['disabled'], null],
    ['.myx-input-box[disabled]', '.myx-input-box[disabled]', [], null],
    ['.myx-input-box::placeholder', '.myx-input-box', [], '::placeholder'],
    ['.myx-palette-item[aria-disabled=\'true\']', '.myx-palette-item[aria-disabled=\'true\']', [], null],
    ['.myx-doc-btn:hover', '.myx-doc-btn', ['hover'], null],
    ['.myx-panel-title', '.myx-panel-title', [], null],
    ['a', 'a', [], null],
  ];
  let bad = 0;
  for (const [sel, wantBase, wantForced, wantPseudo] of cases) {
    const got = splitSelector(sel);
    const ok = got.base === wantBase && got.forced.join(',') === wantForced.join(',') && (got.pseudo ?? null) === wantPseudo;
    if (!ok) { bad++; console.log(`  FAIL ${sel}\n       want base=${wantBase} forced=[${wantForced}] pseudo=${wantPseudo}\n       got  base=${got.base} forced=[${got.forced}] pseudo=${got.pseudo}`); }
    else console.log(`  ok   ${sel}  ->  ${got.base}${got.forced.length ? '  force=[' + got.forced + ']' : ''}${got.pseudo ? '  pseudo=' + got.pseudo : ''}`);
  }
  // An ATTRIBUTE selector surviving into the query is the correctness this file rests on: prove
  // that stripping it would have been wrong by asserting it is still there.
  const attr = splitSelector('.myx-input-box[disabled]');
  if (!attr.base.includes('[disabled]')) { bad++; console.log('  FAIL [disabled] was stripped from the query -- an enabled control would be measured'); }
  console.log(bad === 0 ? 'selftest: ok' : `selftest: ${bad} FAILED`);
  process.exit(bad === 0 ? 0 : 1);
}

const dispositions = JSON.parse(fs.readFileSync(`${OUT}/d7-dispositions.json`, 'utf8'));
const rules = unexercised(dispositions);
if (rules.length !== 73) {
  console.log(`REFUSE: M1-52 recorded 73 unexercised rules and this file found ${rules.length}. The denominator moved; re-read the record before trusting any number below.`);
  process.exitCode = 1;
}
const targets = buildTargets(rules);
const targetsJson = JSON.stringify(targets.map((t) => ({ key: t.key, base: t.base, pseudo: t.pseudo })));

const best = new Map();    // key -> the WORST measurement seen, across every theme/page/opener
const reached = new Map(); // key -> the set of "theme/addr/opener" that made it render
const openerRan = new Map();    // opener -> how many page-themes it was attempted on
const openerOpened = new Map(); // opener -> how many of those it actually opened something on
let pageFails = 0;

// THE MANAGEMENT KEY IS A FIXTURE AXIS, NOT A CONSTANT. features/unlock-mgmt renders its modal
// only when the console has NO management key -- and every instrument in this campaign seeds a
// valid one before boot, so the three .myx-modal rules could never have rendered in any capture
// ever taken. That is not a dead rule and not a missing fixture; it is a fixture that was always
// seeded past. The unkeyed pass exists to reach exactly that surface.
const SEEDS = [
  { name: 'keyed', storage: () => ({ 'myx-mgmt-key': mgmtKey() }) },
  { name: 'unkeyed', storage: () => ({}) },
];

for (const seed of SEEDS) {
for (const theme of ['dark', 'light']) {
  for (const addr of addresses()) {
    // the unkeyed pass is about one surface, and it is the same on every address: one is enough
    if (seed.name === 'unkeyed' && addr !== 'fleet') continue;
    const url = urlFor(addr);
    try {
      const res = await withChrome({ ...seed.storage(), 'splice.theme': theme }, async (send) => {
        await send('Page.enable', {});
        await send('DOM.enable', {});
        await send('CSS.enable', {});
        const passes = [];
        for (const opener of OPENERS) {
          // EVERY OPENER STARTS FROM A FRESH PAGE. Openers are not composable: the views editor
          // left open changes what a later click lands on, and a detail panel opened over it would
          // report a rule reached by a gesture that did not reach it.
          await send('Page.navigate', { url: 'about:blank' });
          await send('Page.navigate', { url });
          await new Promise((r) => setTimeout(r, 2300));
          let opened = true;
          if (opener.name !== 'base') {
            const g = await send('Runtime.evaluate', { expression: opener.gesture, returnByValue: true, awaitPromise: true });
            // The gesture must find something to act on AND its proof must appear. Both, or the
            // opener did not open: a click on a button that is not there and a click that opened
            // nothing are different failures, and neither is evidence that a rule is dark.
            const acted = g.result.value === true;
            const proved = acted && opener.proves !== null
              ? (await send('Runtime.evaluate', { expression: provePoll(opener.proves, 3000), returnByValue: true, awaitPromise: true })).result.value === true
              : acted;
            opened = acted && proved;
          }
          let forcedNodes = 0;
          for (const t of targets) {
            if (!t.forced.length) continue;
            try { forcedNodes += await forceStates(send, t.base, t.forced); } catch (e) { /* absent here */ }
          }
          const out = await send('Runtime.evaluate', { expression: MEASURE(targetsJson), returnByValue: true });
          passes.push({ opener: opener.name, opened, forcedNodes, ...JSON.parse(out.result.value) });
        }
        return passes;
      });

      let hits = 0;
      for (const pass of res) {
        openerRan.set(pass.opener, (openerRan.get(pass.opener) ?? 0) + 1);
        if (pass.opened) openerOpened.set(pass.opener, (openerOpened.get(pass.opener) ?? 0) + 1);
        if (pass.unparsed > 0) {
          console.log(`REFUSE ${theme} ${addr} ${pass.opener}: ${pass.unparsed} colour string(s) unparsed - a ground it cannot read is not a ground it may skip`);
          process.exitCode = 1;
        }
        for (const row of pass.out) {
          if (row.ratio === undefined) continue;
          if (!reached.has(row.key)) reached.set(row.key, new Set());
          reached.get(row.key).add(`${seed.name}/${theme}/${addr}/${pass.opener}`);
          const prev = best.get(row.key);
          // KEEP THE WORST. A rule that reads badly on one plane is not excused by reading well on
          // another: that is the entire D7 class, and taking the best would hide every instance.
          if (prev === undefined || row.ratio < prev.ratio) best.set(row.key, { ...row, seed: seed.name, theme, addr, opener: pass.opener });
        }
      }
      hits = new Set([...reached.keys()].filter((k) => [...reached.get(k)].some((w) => w.startsWith(`${seed.name}/${theme}/${addr}/`)))).size;
      const openedHere = res.filter((pa) => pa.opened && pa.opener !== 'base').map((pa) => pa.opener);
      console.log(`ok ${seed.name.padEnd(7)} ${theme.padEnd(5)} ${addr.padEnd(11)} ${String(hits).padStart(2)} of the 73 rendered   opened: ${openedHere.join(' ') || '(none)'}`);
    } catch (e) {
      console.log(`FAIL ${seed.name} ${theme} ${addr}: ${e.message.split('\n')[0]}`);
      pageFails++;
    }
  }
}
}

// AN OPENER THAT NEVER OPENED ANYTHING IS A BROKEN INSTRUMENT, NOT A DARK RULE. Without this the
// palette gesture could silently miss and six palette rules would be filed "nothing reaches them".
console.log('');
for (const opener of OPENERS) {
  if (opener.name === 'base') continue;
  const ran = openerRan.get(opener.name) ?? 0;
  const ok = openerOpened.get(opener.name) ?? 0;
  const verdict = ok === 0 ? 'NEVER OPENED - its rules cannot be called dark on this evidence' : `opened on ${ok}/${ran} page-themes`;
  console.log(`opener ${opener.name.padEnd(12)} ${verdict}`);
  if (ok === 0) process.exitCode = 1;
}

// LAW 34: if every page threw, what would this print? Without this, nothing reached and every rule
// would be reported "still dark" -- a full green sheet of false absences.
if (pageFails > 0) {
  console.log(`\nREFUSE: ${pageFails} page(s) failed to render. A page that threw is not a page whose rules are absent.`);
  process.exitCode = 1;
}

const record = targets.map((t) => ({
  key: t.key, sel: t.sel, ink: t.ink, forced: t.forced, pseudo: t.pseudo,
  reached: [...(reached.get(t.key) ?? [])].sort(),
  measured: best.get(t.key) ?? null,
}));
fs.writeFileSync(`${OUT}/exercised.json`, JSON.stringify(record, null, 1));

const lit = record.filter((r) => r.measured !== null);
console.log(`\n${lit.length} of ${record.length} now render and are measured; ${record.length - lit.length} still dark`);
console.log(`wrote ${OUT}/exercised.json`);
