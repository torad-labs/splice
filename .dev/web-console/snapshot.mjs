// Snapshot one console address as ONE self-contained HTML file, so a rendered-page detector can
// read the console without a running server, a key, or a browser of its own.
//
// WHY. impeccable's detect.mjs carries ~60 RENDERED rules (clipped-overflow-container,
// cramped-padding, text-occlusion, low-contrast, line-length, tight-leading, flat-type-hierarchy,
// design-system-*) and reaches them only when its target is an http(s) or file:// URL. Every verify
// line in this campaign passes it a SOURCE DIRECTORY, which only ever reaches the regex engine, so
// those rules have never seen this console. Pointed at the running console they would have caught
// three findings of the m1 review with no human in the loop (B7, B20, D7).
//
// WHY A SNAPSHOT AND NOT THE URL. The console sits behind the management-key gate: a bare URL
// renders the gate, not the console. The key is a secret, so it cannot go in a verify line, a note,
// a filename or any output - it is read here, seeded into localStorage through CDP before the app
// boots (lib/cdp.mjs, the same recipe capture.mjs uses), and never printed.
//
// WHAT IS FROZEN. The rendered DOM as it stands, with every stylesheet's text inlined (fonts
// included, as data: URIs - a relative font URL would not resolve from a file:// page and the
// fallback face would move every measurement the detector takes), and with every `<script>` removed
// so the artifact cannot re-boot the app, re-fetch an API it has no route to, or lose its fixture.
// What the detector then reads is the frame the browser actually laid out.
//
// Usage: node .dev/web-console/snapshot.mjs '<url>' [<out.html>] [<width> <height>]
import { mkdirSync, statSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { mgmtKey, show, withChrome } from './lib/cdp.mjs';

/** Where the artifacts live. Gitignored (webui/.impeccable/.gitignore), because a snapshot is
 *  regenerable bytes and the page it froze may hold live daemon data. */
export const LOOK_DIR = 'webui/.impeccable/review/look';

/** One address, one file name: `#/teams?fixture=hero` becomes `teams-hero.html`. */
export function nameFor(url) {
  const fragment = url.includes('#') ? url.slice(url.indexOf('#') + 1) : url;
  const slug = fragment.replace(/[^a-z0-9]+/gi, '-').replace(/^-|-$/g, '').toLowerCase();
  return `${slug === '' ? 'root' : slug}.html`;
}

/** Serialize the live document: scripts out, stylesheets in (with their fonts), one string. */
const COLLECT = `(async () => {
  const sheets = [];
  for (const sheet of document.styleSheets) {
    try { sheets.push([...sheet.cssRules].map((rule) => rule.cssText).join('\\n')); } catch (e) { /* cross-origin: skip */ }
  }
  let css = sheets.join('\\n');
  const wanted = new Set();
  for (const match of css.matchAll(/url\\((['"]?)([^'")]+)\\1\\)/g)) {
    const url = match[2];
    if (!url.startsWith('data:') && !url.startsWith('#')) wanted.add(new URL(url, document.baseURI).href);
  }
  const inlined = new Map();
  for (const url of wanted) {
    try {
      const bytes = await (await fetch(url)).blob();
      inlined.set(url, await new Promise((ok, no) => {
        const reader = new FileReader();
        reader.onload = () => ok(reader.result);
        reader.onerror = () => no(new Error('read failed'));
        reader.readAsDataURL(bytes);
      }));
    } catch (e) { /* leave it absolute; the detector will say so if it mattered */ }
  }
  css = css.replace(/url\\((['"]?)([^'")]+)\\1\\)/g, (whole, quote, url) => {
    const hit = inlined.get(new URL(url, document.baseURI).href);
    return hit === undefined ? whole : 'url("' + hit + '")';
  });
  const clone = document.documentElement.cloneNode(true);
  for (const node of clone.querySelectorAll('script, link, style')) node.remove();
  const style = document.createElement('style');
  style.setAttribute('data-look-snapshot', '1');
  style.textContent = css;
  clone.querySelector('head').appendChild(style);
  return '<!doctype html>\\n' + clone.outerHTML;
})()`;

export async function snapshot(url, out, width = 1536, height = 1024) {
  return withChrome({ 'myx-mgmt-key': mgmtKey() }, async (send) => {
    await show(send, url, width, height);
    const result = await send('Runtime.evaluate', {
      expression: COLLECT,
      awaitPromise: true,
      returnByValue: true,
    });
    const html = result?.result?.value;
    if (typeof html !== 'string' || html.length < 200) {
      throw new Error(`the page serialised to ${typeof html === 'string' ? `${html.length} bytes` : 'nothing'}`);
    }
    mkdirSync(dirname(out), { recursive: true });
    writeFileSync(out, html);
    return { out, bytes: html.length };
  });
}

const isMain = process.argv[1] !== undefined && import.meta.url.endsWith(process.argv[1].replace(/^.*?(?=\/dev\/|$)/, ''));
if (isMain) {
  const [, , url, given, w = '1536', h = '1024'] = process.argv;
  if (url === undefined || url === '--help') {
    console.log("usage: node .dev/web-console/snapshot.mjs '<url>' [<out.html>] [<width> <height>]");
    console.log(`       default out: ${LOOK_DIR}/<address>.html`);
    process.exit(url === '--help' ? 0 : 2);
  }
  const out = resolve(given ?? `${LOOK_DIR}/${nameFor(url)}`);
  const { bytes } = await snapshot(url, out, Number(w), Number(h));
  console.log(`wrote ${out} (${bytes} bytes, ${statSync(out).size} on disk)`);
}
