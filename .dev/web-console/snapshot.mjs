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
// M1-76 DISPOSITION — the two ways a check can be decorative, answered for this file. CLEAN BOTH.
//   SHAPE ONE, does every FAIL reach the exit code? YES. The selftest's `fail` counter is the only
//     thing `process.exit(fail === 0 ? 0 : 1)` reads, and the capture path does not print failures
//     at all — it THROWS, which is the strongest form of reaching the exit code. That includes the
//     serialisation floor: a page that comes back under 200 bytes throws rather than writing a
//     snapshot a detector would then read as a clean empty page.
//   SHAPE TWO, if the page produced nothing, what would it print? NOTHING — it throws before
//     writeFileSync, so there is no artifact to mislead the detector and no summary to mistake for
//     a pass. The emptiness is refused at the point it is created rather than counted later.
//   AND THE GUARD THAT MAKES BOTH TRUE was itself the hole once: M1-60 found the isMain suffix form
//     here true on IMPORT, and the `--selftest` call sitting one line ABOVE the guard added to fix
//     that class — so look.mjs growing a --selftest would have run snapshot's instead and exited 0
//     on a suite that never started. Both fixed there; this file has carried pathToFileURL since,
//     and it is the pattern law-check, capture and type-ladder were brought up to in this row.
//
// Usage: node .dev/web-console/snapshot.mjs '<url>' [<out.html>] [<width> <height>]
import { mkdirSync, statSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { show, withChrome } from './lib/cdp.mjs';
import { themeValues } from './theme.mjs';

/** Where the artifacts live. Gitignored (webui/.impeccable/.gitignore), because a snapshot is
 *  regenerable bytes and the page it froze may hold live daemon data. */
export const LOOK_DIR = 'webui/.impeccable/review/look';

/** One address, one file name: `#/teams?fixture=hero` becomes `teams-hero.html`. */
export function nameFor(url) {
  // The address, not the origin: a hash address names its route, a bare URL names its path, and a
  // bare origin is the root. Slugging the WHOLE url (what this did until its own selftest caught
  // it) turned `http://x/` into `http-x.html` - a name that describes the host rather than the
  // page, on the argument every other caller passes as a full URL.
  const after = url.includes('#')
    ? url.slice(url.indexOf('#') + 1)
    : (url.replace(/^https?:\/\/[^/]+/, '') || '/');
  const slug = after.replace(/[^a-z0-9]+/gi, '-').replace(/^-|-$/g, '').toLowerCase();
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

/**
 * `theme` is seeded into localStorage BEFORE the document runs, the same way gate.mjs does it.
 *
 * Added 2026-09-18. Until then nothing here touched the theme, so every rendered rule pass this
 * campaign has run saw one room — and it was not the room with the problem. The light theme has
 * 9.2 L of headroom above its paper against dark's 216.7, three of twelve materials clip in it,
 * and the ghost that recedes on dark ADVANCES on light. None of that was reachable by any check.
 * M1-33 found the general form: no leg of the exit gate seeds a theme, so the light room is
 * unobserved by every automatic instrument we have.
 */
export async function snapshot(url, out, width = 1536, height = 1024, theme = 'dark') {
  // THROUGH themeValues() AND NOT A SECOND addScriptToEvaluateOnNewDocument (M1-60). This function
  // is the reason that row exists: theme.mjs was written in M1-55 so no fourth seat would rebuild
  // theme seeding privately, and this file — edited in M1-55's own receipt — rebuilt it here anyway,
  // six lines below a header comment citing the same rule. Routing through the helper also buys the
  // validation this never had: themeValues throws on an unknown theme, where the inline seed wrote
  // whatever string it was handed into localStorage and rendered the default room without a word.
  return withChrome(themeValues(theme), async (send) => {
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

/** The mutation proof (M1-55): the address mapping and the theme contract must fail on a
 *  synthetic violation before anyone trusts them. A snapshot's file name decides which page a
 *  detector later reads, and its theme decides which room - both were once silently wrong. */
function selftest() {
  let pass = 0; let fail = 0;
  const check = (name, ok, detail) => {
    console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${name}  (${detail})`);
    ok ? pass++ : fail++;
  };
  check('a fixture address maps to its own file name',
    nameFor('http://x/#/teams?fixture=hero') === 'teams-fixture-hero.html',
    nameFor('http://x/#/teams?fixture=hero'));
  check('two different addresses never share a name',
    nameFor('http://x/#/fleet') !== nameFor('http://x/#/teams?fixture=hero'),
    `${nameFor('http://x/#/fleet')} vs ${nameFor('http://x/#/teams?fixture=hero')}`);
  check('an address with no fragment still gets a name',
    nameFor('http://x/') === 'root.html', nameFor('http://x/'));
  console.log(`\nselftest: ${pass} passed, ${fail} failed`);
  process.exit(fail === 0 ? 0 : 1);
}

// SAME GUARD, SAME DEFECT, MEASURED (M1-60). The suffix form read `import.meta.url.endsWith(
// argv[1].replace(/^.*?(?=\/dev\/|$)/, ''))`, and when the IMPORTING program's path holds no
// `/dev/` segment the lookahead falls through to `$`, the replace eats the whole string, and
// `endsWith('')` is true for every string — so isMain is true on import and the CLI body runs. It
// passed only because this checkout sits under ~/Documents/dev/projects and every path here
// contains `/dev/` by accident. look.mjs imports snapshot() from this file, so the hazard was one
// unlucky checkout path away from the gate's look leg. pathToFileURL because import.meta.url is
// percent-encoded and the plain string form goes FALSE on a path with a space — the same bug with
// the quiet face, where the CLI silently does nothing.
const isMain = process.argv[1] !== undefined && import.meta.url === pathToFileURL(process.argv[1]).href;

// AND THE SELFTEST CALL WAS ITSELF UNGUARDED, one line ABOVE the guard added to fix this class:
// `if (process.argv.includes('--selftest')) selftest();` ran on IMPORT whenever the importing
// program's argv happened to carry --selftest, and selftest() ends in process.exit. look.mjs
// imports snapshot() from this file, so the day look.mjs grows a --selftest of its own, snapshot's
// would have run instead and exited 0 before look's ever started — a green tick for a suite that
// never ran. Guarded now, like everything else below it.
if (isMain && process.argv.includes('--selftest')) selftest();

if (isMain) {
  const [, , url, given, w = '1536', h = '1024', theme = 'dark'] = process.argv;
  if (url === undefined || url === '--help') {
    console.log("usage: node .dev/web-console/snapshot.mjs '<url>' [<out.html>] [<width> <height>] [<theme>]");
    console.log(`       default out: ${LOOK_DIR}/<address>.html`);
    process.exit(url === '--help' ? 0 : 2);
  }
  const out = resolve(given ?? `${LOOK_DIR}/${nameFor(url)}`);
  const { bytes } = await snapshot(url, out, Number(w), Number(h), theme);
  console.log(`wrote ${out} (${bytes} bytes, ${statSync(out).size} on disk, ${Number(w)}x${Number(h)} ${theme})`);
}
