// Why does it feel busy? Each variant changes one cause, on Sessions and on the session page, Day, 1440:
//   now  the approved comps, untouched
//   A    fewer outlines: one edge treatment, quiet chips and pills, text buttons, no frame inside the frame
//   B    more room: about twice the gutters, section spacing and inner padding; A's outlines stay as they were
//   C    less per card: one title, one state, one activity line, one quiet meta line
//   D    A + B + C, and the two checks the operator's words raised: mono only for code and terminal text, and one accent per card
// Render: node docs/design/comps/variants.mjs   ->  png/variants/<page>-<variant>-1440.png and compare-<page>.png
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { chromium } from '/home/marcos/Documents/dev/projects/mythos/repo/node_modules/@playwright/test/index.mjs';
import { shell } from './shell.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const out = join(here, 'png', 'variants');
mkdirSync(out, { recursive: true });

// A: fewer outlines -------------------------------------------------------------------------------------------
const A = `
.win { border-width: 3px; box-shadow: 8px 9px 0 var(--rim); }
.win.attn { border-color: var(--edge); }
.tag { border: 0; background: color-mix(in srgb, var(--ink) 8%, transparent); padding: 2px 10px; }
.state { border: 0; padding: 0; gap: 7px; color: var(--ink) !important; }
.state.work i { background: var(--ok); } .state.wait i { background: var(--charge); } .state.stuck i { background: var(--stuck); } .state.idle i { background: var(--faint); } .state.quota i { background: var(--wait); }
.btn { border-width: 2px; }
.btn:not(.go) { background: transparent; border-color: transparent; box-shadow: none; color: var(--ink); text-decoration: underline; text-decoration-thickness: 2px; text-underline-offset: 4px; text-decoration-color: var(--hair); padding-inline: 6px; }
.btn:not(.go):hover { transform: none; text-decoration-color: var(--ink); }
.btn.go { box-shadow: none; }
.seg, .search { border-width: 2px; border-color: var(--hair); }
.win { overflow: hidden; }
.win .glass { margin: 0; border-radius: 0; padding: 14px 18px; }
.win .strip { margin: 0; border-radius: 0; }
.acts { padding-top: 14px; }
.prose code { border: 0; background: color-mix(in srgb, var(--ink) 8%, transparent); }
.you { border-width: 0; box-shadow: none; }
.peer { border: 0; }
.peer::before { border-radius: 10px 0 0 10px; left: 0; top: 0; bottom: 0; }
.seat, .ride, .composer, .meter { border-width: 2px; border-color: var(--hair); }
.seat.here { box-shadow: 4px 5px 0 var(--c); }
.team::before { border-width: 0; }
.seat::after { border-width: 2px; }
.sheet.win, .win.sheet { overflow: visible; }
.tool { border-radius: 10px; }
`;

// B: more room ------------------------------------------------------------------------------------------------
const B = `
.wall { padding: 56px clamp(40px, 4.5vw, 72px) 120px; }
.page-head { margin-bottom: 56px; }
.page-head .lede { margin-top: 18px; }
.grid, .idle-row { gap: 72px 72px !important; padding-right: 24px !important; }
.grid.first { grid-template-columns: repeat(auto-fill, minmax(560px, 1fr)) !important; }
.group-head { margin: 84px 0 34px; }
.group-head:first-of-type { margin-top: 40px; }
.bar { padding: 22px 26px 12px 26px; gap: 14px; }
.meta { padding: 0 26px 20px; gap: 12px; }
.glass { margin: 0 20px 20px; padding: 20px 24px; }
.strip { margin: -20px 20px 20px; padding: 10px 24px 14px; }
.acts { padding: 0 26px 26px; }
.side { padding: 40px 30px 32px; }
.nav a { padding: 12px 12px 12px 14px; }
/* the session page */
.top { margin-bottom: 56px; }
.top .facts { margin-top: 20px; gap: 14px; }
.cols { gap: 56px; grid-template-columns: minmax(0, 1fr) 300px; }
.sheet .bar { padding: 22px 40px 16px; }
.convo { padding: 24px 48px 48px; gap: 52px; }
.msg { gap: 14px; }
.prose { line-height: 1.75; }
.prose p + p, .prose p + ul, .prose ul + p, .prose ol + p, .prose p + ol { margin-top: 20px; }
.prose li { margin: 9px 0; }
.peer { padding: 24px 32px 26px 38px; }
.composer { margin-bottom: 44px; }
.composer .in { padding: 24px 26px 10px; }
.rail { gap: 30px; }
.team { gap: 24px; }
.seat { padding: 14px 18px; }
.ride { padding: 12px 16px; }
.facts-list { gap: 16px; }
`;

// C: less per card --------------------------------------------------------------------------------------------
const C = `
.bar .grip { display: none; }
.win .glass.one { padding: 14px 18px; margin-bottom: 8px; }
.win .glass.one p { color: var(--glass-ink); }
.quiet-meta { display: flex; flex-wrap: wrap; align-items: center; gap: 4px 0; padding: 0 18px 16px; font: 500 12.5px var(--mono); color: var(--mute); }
.quiet-meta span + span::before { content: '·'; margin: 0 9px; color: var(--faint); }
.top .facts.quiet-meta { padding: 0; margin-top: 16px; font-size: 13.5px; }
.acts { padding: 0 18px 18px; margin-top: -4px; }
`;

// D's typography and accent checks ----------------------------------------------------------------------------
// Mono is for code and terminal text: the glass block, tool blocks, code, commands. Meta, buttons, nav and
// labels take the reading face. One accent per card: the head's colour is the card's identity (its shadow); a card
// that needs a person drops it and keeps the one vermilion button.
const D_TYPE = `
:root { --meta: 'Source Serif 4', Georgia, serif; }
.nav a, .side .foot, .btn, .seg button, .search, .kbd, .tag, .state, .eyebrow, .hand, .model, .quiet-meta, .age, .who, .stamp,
.crumb, .pend, .seat b, .seat span.s2, .ride, .ride small, .facts-list, .prose th, .group-head .n, .nav .count, .mark { font-family: var(--meta) !important; }
.btn, .seg button, .search, .tag, .state, .nav a, .quiet-meta, .crumb, .seat b, .who { font-weight: 600; letter-spacing: 0; }
.quiet-meta { font-size: 14.5px; font-weight: 500; }
.tag { font-size: 14px; }
.state { font-size: 14px; }
.btn { font-size: 14.5px; }
.nav a { font-size: 16px; }
.seat b, .who, .ride, .facts-list { font-size: 15px; }
.prose th { font-size: 13.5px; letter-spacing: .02em; }
.prose li::marker { font-family: var(--meta); }
.win .strip, .win .strip .model, .win .glass { font-family: var(--mono) !important; }
`;
const D_ACCENT = `
.win.attn { --rim: var(--tan) !important; border-color: var(--edge); }
.win .strip { --m: var(--glass-ink) !important; }
.win .strip .model { color: var(--glass-ink); }
.state.wait, .state.stuck { color: var(--ink) !important; }
.seat span.s2 { color: var(--mute) !important; }
.seat span.s2.w { color: var(--mute); }
`;

const VARIANTS = {
  now: { css: '', compact: false },
  a: { css: A, compact: false },
  b: { css: B, compact: false },
  c: { css: C, compact: true },
  d: { css: A + B + C + D_TYPE + D_ACCENT + '.grid.first { grid-template-columns: repeat(auto-fill, minmax(460px, 1fr)) !important; }', compact: true },
};
const CAPTION = {
  now: 'Now: the approved comps.',
  a: 'A, fewer outlines: one edge (the hue shadow), chips and pills quiet, text buttons, no frame inside the card.',
  b: 'B, more room: about twice the gutters, section spacing and padding; outlines as now.',
  c: 'C, less per card: title, state, one activity line, one quiet meta line; the rest lives on the session page.',
  d: 'D, the calm version: A + B + C, mono only for code and terminal text, one accent per card.',
};

const browser = await chromium.launch({ args: ['--no-sandbox'] });
const shots = {};
for (const name of ['sessions', 'session']) {
  const mod = await import(pathToFileURL(join(here, 'pages', `${name}.mjs`)).href);
  shots[name] = {};
  for (const [id, v] of Object.entries(VARIANTS)) {
    const html = shell({ current: 'sessions', theme: 'day', body: mod.body({ compact: v.compact }), css: (mod.css ?? '') + v.css });
    const file = join(here, `variant-${name}-${id}.html`);
    writeFileSync(file, html);
    const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
    await page.goto(pathToFileURL(file).href);
    await page.evaluate(() => document.fonts.ready);
    const png = join(out, `${name}-${id}-1440.png`);
    await page.screenshot({ path: png, fullPage: true });
    shots[name][id] = png;
    await page.close();
  }
}
// One sheet per page: the five side by side at half size, each under its caption.
for (const name of Object.keys(shots)) {
  const cells = Object.entries(shots[name]).map(([id, png]) => `<figure><img src="${pathToFileURL(png).href}"><figcaption>${CAPTION[id]}</figcaption></figure>`).join('');
  const html = `<!doctype html><meta charset="utf-8"><style>body{margin:0;padding:24px;background:#fff;font:16px/1.4 system-ui}
.row{display:flex;gap:20px;align-items:flex-start}figure{margin:0;width:720px;flex:none}img{width:720px;display:block;border:1px solid #bbb}figcaption{padding:10px 4px;font-weight:600}</style><div class="row">${cells}</div>`;
  const file = join(here, `compare-${name}.html`);
  writeFileSync(file, html);
  const page = await browser.newPage({ viewport: { width: 3700, height: 1000 } });
  await page.goto(pathToFileURL(file).href);
  await page.screenshot({ path: join(out, `compare-${name}.png`), fullPage: true });
  await page.close();
}
await browser.close();
console.log(Object.values(CAPTION).join('\n'));
