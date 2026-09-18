// Gate captures: every address of the console, in both themes, at two frame sizes, with a contact
// sheet per theme and a manifest that says which of them came back blank.
//
// WHY A GATE AND NOT A SCREENSHOT: a milestone is signed off by looking at the console, and a
// single capture proves one page at one size in one room. The failure this exists to catch is the
// one nobody notices — an address that renders an empty room, at a size the reviewer did not open,
// in the theme they were not using. So the run is exhaustive, and each capture carries the
// measurement that would expose it: the fraction of its pixels that are the room's own colour.
//
// Usage: node .dev/web-console/gate.mjs <milestone> [--dry-run]
import { mkdirSync, readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { mgmtKey, renderHtml, shoot, show, withChrome } from './lib/cdp.mjs';
import { colorFraction, decodePng, hexToRgb } from './lib/png.mjs';

const ROOT = resolve(import.meta.dirname, '../..');
const TERMINAL = process.stdout.isTTY === true;

/** The two rooms and the two frames every address is captured in. */
const THEMES = ['dark', 'light'];
const FRAMES = [[1536, 1024], [1280, 800]];

/** A capture this fraction room-coloured or more is BLANK: the page did not draw. */
const BLANK_AT = 0.98;

/**
 * The dev fixture each address ships, or null for one that captures live.
 *
 * One line per address, read from the pages/*\/fixtures directories as they stand today. The name
 * is the one that address's own `fixtureName()` accepts, which is not the same everywhere: some
 * match a fixed word ('usage', 'settings', 'models', 'compaction', 'hero'), two match 'demo', and
 * four (logs, projects, sessions, turns) take any non-empty value, which 'demo' satisfies. fleet
 * and mcp ship no fixture and are captured against the live daemon.
 */
const FIXTURES = {
  fleet: null,
  turns: 'demo',
  sessions: 'demo',
  teams: 'hero',
  projects: 'demo',
  accounts: 'demo',
  usage: 'usage',
  settings: 'settings',
  models: 'models',
  logs: 'demo',
  compaction: 'compaction',
  mcp: null,
  doctor: 'demo',
};

/**
 * The addresses, read from the shell's own table rather than copied here: a fourteenth address
 * added by a later row has to appear in the gate without an edit, and a list written out in this
 * file could not fail for one that was missing from itself.
 */
function addresses() {
  const source = readFileSync(join(ROOT, 'webui/src/app/rows.ts'), 'utf8');
  const block = source.match(/export const ADDRESSES = \[([\s\S]*?)\] as const;/);
  if (block === null) throw new Error('rows.ts: the ADDRESSES table was not found');
  return [...block[1].matchAll(/'([a-z0-9-]+)'/g)].map((match) => match[1]);
}

/** Both `--room` values, keyed by the theme they belong to. Read from the token sheet, so a room
 *  that is re-derived does not leave this script measuring against the old one. */
function rooms() {
  const sheet = readFileSync(join(ROOT, 'webui/src/shared/tokens.css'), 'utf8');
  const found = {};
  let theme = 'dark';
  for (const line of sheet.split('\n')) {
    if (line.includes('data-theme="light"')) theme = 'light';
    else if (line.includes('data-theme="dark"')) theme = 'dark';
    const room = line.match(/--room:\s*(#[0-9A-Fa-f]{6})/);
    if (room !== null) found[theme] = room[1];
  }
  for (const theme of THEMES) {
    if (found[theme] === undefined) throw new Error(`tokens.css: no --room for the ${theme} theme`);
  }
  return found;
}

function plan() {
  const list = addresses();
  const captures = [];
  for (const address of list) {
    for (const theme of THEMES) {
      for (const [width, height] of FRAMES) {
        captures.push({ address, theme, width, height, file: `${address}-${theme}-${width}x${height}.png` });
      }
    }
  }
  return { addresses: list, captures };
}

const [, , milestone, ...flags] = process.argv;
const dryRun = flags.includes('--dry-run');

if (!milestone || !/^[a-z0-9-]+$/.test(milestone)) {
  console.error('usage: node .dev/web-console/gate.mjs <milestone> [--dry-run]');
  console.error('       <milestone> is a lowercase name, e.g. m1-preview');
  process.exit(2);
}

const { addresses: list, captures } = plan();
const outDir = join(ROOT, 'webui/.impeccable/review/gate', milestone);
const sheetPath = (theme) => join(outDir, `sheet-${theme}.png`);
const fixtures = [...new Set(Object.values(FIXTURES).filter((name) => name !== null))].sort();

if (dryRun) {
  console.log(`gate ${milestone}: ${captures.length} captures into ${outDir}`);
  console.log(`addresses (${list.length}): ${list.join(' ')}`);
  console.log(`themes: ${THEMES.join(' ')}   frames: ${FRAMES.map(([w, h]) => `${w}x${h}`).join(' ')}`);
  console.log(`fixtures: ${fixtures.join(' ')}   live (no fixture): ${Object.entries(FIXTURES).filter(([, n]) => n === null).map(([a]) => a).join(' ')}`);
  for (const capture of captures) console.log(`  ${capture.file}`);
  for (const theme of THEMES) console.log(`  sheet-${theme}.png`);
  process.exit(0);
}

/** The two URL shapes a fixture can arrive in. The shell canonicalises the hash at boot and keeps
 *  the query (rows.ts canonicalHash), and a page may read it from either the search or the hash,
 *  so both carry it — belt and braces, because a fixture that silently fails to load looks exactly
 *  like a page that rendered an empty daemon. */
function urlFor(address, fixture) {
  const query = fixture === null ? '' : `?fixture=${fixture}`;
  return `http://localhost:5173/#/${address}${query}`;
}

function sheetHtml(theme, themeCaptures) {
  const cells = themeCaptures.map((capture) => {
    const width = Math.round(capture.width / 4);
    return `<figure><img src="${capture.file}" width="${width}"><figcaption>${capture.file}<br>`
      + `<span class="blank">${capture.blank.toFixed(3)} room</span>${capture.blank >= BLANK_AT ? ' BLANK' : ''}`
      + '</figcaption></figure>';
  }).join('\n');
  return `<!doctype html><meta charset="utf-8"><title>gate ${milestone} ${theme}</title>
<style>
  :root { color-scheme: dark; }
  body { margin: 0; padding: 16px; background: #0B0E0E; color: #ECEAE2;
         font: 12px/1.4 ui-monospace, monospace; }
  h1 { font-size: 15px; margin: 0 0 14px; }
  .grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 14px; }
  figure { margin: 0; }
  img { display: block; border: 1px solid #3a3d3c; background: #000; }
  figcaption { margin-top: 4px; }
  .blank { color: #9D9B8E; }
</style>
<h1>gate ${milestone} / ${theme} / ${themeCaptures.length} captures</h1>
<div class="grid">
${cells}
</div>`;
}

mkdirSync(outDir, { recursive: true });
// A re-run must not leave a stale capture behind for a file the plan no longer produces.
for (const entry of readdirSync(outDir)) {
  if (entry.endsWith('.png') || entry.endsWith('.html')) rmSync(join(outDir, entry), { force: true });
}

const room = rooms();
const key = mgmtKey();
const manifest = [];
const blanks = [];
const started = Date.now();

await withChrome({ 'myx-mgmt-key': key }, async (send) => {
  let themeScript = null;
  let lastUrl = null;
  for (const theme of THEMES) {
    for (const capture of captures.filter((row) => row.theme === theme)) {
      // ONE theme script at a time: the previous one is removed before the next is registered.
      // Letting them accumulate looked harmless and was not — measured 2026-09-18, the first two
      // captures after a theme change came back in the previous room, because which of the
      // registered writers landed last was not the one this loop had just added.
      if (themeScript !== null) {
        await send('Page.removeScriptToEvaluateOnNewDocument', { identifier: themeScript });
      }
      const added = await send('Page.addScriptToEvaluateOnNewDocument', {
        source: `try { localStorage.setItem('splice.theme', ${JSON.stringify(theme)}); } catch (e) {}`,
      });
      themeScript = added.identifier;
      const url = urlFor(capture.address, FIXTURES[capture.address]);
      // ALWAYS through about:blank. Every console URL is a hash route, so a navigate from one to
      // the next — and from an address to ITSELF in the other theme — is a same-document fragment
      // navigation that does not re-create the frame, so the seed above would not run and the
      // capture would come back in whatever room the previous document chose. Measured twice on
      // 2026-09-18: the first fix (reload when the URL repeats) left `fleet` wrong, because its
      // light capture follows a DIFFERENT address in the previous theme and so was a plain hash
      // change. A blank page in between makes the next load cross-document, every time.
      await send('Page.navigate', { url: 'about:blank' });
      await show(send, url, capture.width, capture.height);
      const bytes = await shoot(send, join(outDir, capture.file));
      const blank = colorFraction(decodePng(bytes), hexToRgb(room[theme]));
      manifest.push({ ...capture, blank: Number(blank.toFixed(4)) });
      if (blank >= BLANK_AT) blanks.push(capture.file);
      if (TERMINAL) process.stdout.write(blank >= BLANK_AT ? 'B' : '.');
    }
  }
  if (TERMINAL) process.stdout.write('\n');

  for (const theme of THEMES) {
    const html = sheetHtml(theme, manifest.filter((row) => row.theme === theme));
    await renderHtml(send, html, join(outDir, `sheet-${theme}.html`), sheetPath(theme), 1640, 2200);
  }
});

// One capture per line, so the file is readable as a list and `grep -c address` counts captures.
writeFileSync(
  join(outDir, 'manifest.json'),
  `[\n${manifest.map((row) => JSON.stringify(row)).join(',\n')}\n]\n`,
);

const seconds = Math.round((Date.now() - started) / 1000);
console.log(`gate ${milestone}: ${manifest.length} captures in ${seconds}s -> ${outDir}`);
console.log(`  sheets: ${THEMES.map((theme) => `sheet-${theme}.png`).join(' ')}`);
if (blanks.length > 0) {
  // Loud on purpose: a blank capture is the failure this gate exists to find, and a gate that
  // reported it as a line among fifty-two would be passing it silently.
  console.error(`  BLANK: ${blanks.length} of ${manifest.length} captures are >= ${BLANK_AT} room colour:`);
  for (const file of blanks) console.error(`    ${file}`);
}
