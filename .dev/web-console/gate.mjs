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
import { mgmtKey, renderHtml, shoot, show, sleep, withChrome } from './lib/cdp.mjs';
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
 * `name` is the value the address's address-bar query must carry and `file` is the module on disk
 * under pages/<address>/fixtures/. They are NOT the same string everywhere, and assuming they were
 * is what made this table lie: turns, sessions, projects and logs interpolate the query value into
 * a dynamic import -- turns/index.tsx:337 is
 *
 *     import(/* @vite-ignore *\/ `./fixtures/${name}.ts`).catch(() => undefined)
 *
 * -- so a name that is not the file name fails the import, the `.catch` swallows it, and the page
 * renders LIVE daemon data that looks exactly like a successful capture. Those four said 'demo'
 * until 2026-09-18, when the real files were read off disk: board, board, list, tail.
 *
 * The other nine were checked against their own pages rather than assumed, and TWO OF THEM ARE ALSO
 * WRONG in a way the first audit missed and this gate's assertion then caught: accounts and doctor
 * said 'demo' too. Their modules are imported STATICALLY (accounts/index.tsx:25) and `fixtureName`
 * really does accept any non-empty name — but the fixture module's own ACCESSOR re-checks it against
 * its file name: accounts/fixtures/accounts.ts:100 `return name === 'accounts' ? DEMO_ACCOUNTS : null`
 * and doctor/fixtures/doctor.ts:46 the same shape. So 'demo' returned null, the page fell through to
 * live daemon data, and it looked exactly like a capture (measured 2026-09-18: with `?fixture=demo`
 * accounts renders live account names and no `sample data` label; with `?fixture=accounts` both).
 * The lesson is the table's own: the name is the FILE NAME on every page, and the only safe way to
 * hold it is to assert it. teams, usage, settings, models and compaction compare the query value to a
 * literal in the page; fleet and mcp ship no fixture and capture the live daemon.
 *
 * `mark` says whether the page prints the fixture's own `sample data` label, which is the visible
 * half of the assertion below. Every fixture page but teams and doctor passes `sample` into its
 * board; those two are held to the module proof alone.
 */
const FIXTURES = {
  fleet: null,
  turns: { name: 'board', file: 'board', mark: true },
  sessions: { name: 'board', file: 'board', mark: true },
  teams: { name: 'hero', file: 'hero', mark: false },
  projects: { name: 'list', file: 'list', mark: true },
  accounts: { name: 'accounts', file: 'accounts', mark: true },
  usage: { name: 'usage', file: 'usage', mark: true },
  settings: { name: 'settings', file: 'settings', mark: true },
  models: { name: 'models', file: 'models', mark: true },
  logs: { name: 'tail', file: 'tail', mark: true },
  compaction: { name: 'compaction', file: 'compaction', mark: true },
  mcp: null,
  doctor: { name: 'doctor', file: 'doctor', mark: false },
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
/** Where the captures land. The default is the M2-11 contract (review/gate/<milestone>); a row
 *  whose own review directory differs passes --out, which is how the census run keeps M1-16's
 *  directory empty and lands its frames where its own contract says. */
const outFlag = flags.indexOf('--out');
const outArg = outFlag === -1 ? null : flags[outFlag + 1];
if (outFlag !== -1 && (outArg === undefined || outArg.startsWith('--'))) {
  console.error('usage: --out <directory> (absolute, or relative to the repo root)');
  process.exit(2);
}
const outDir = outArg === null ? join(ROOT, 'webui/.impeccable/review/gate', milestone) : resolve(ROOT, outArg);
const sheetPath = (theme) => join(outDir, `sheet-${theme}.png`);
const fixtures = [...new Set(Object.values(FIXTURES).filter((f) => f !== null).map((f) => f.name))].sort();

if (dryRun) {
  console.log(`gate ${milestone}: ${captures.length} captures into ${outDir}`);
  console.log(`addresses (${list.length}): ${list.join(' ')}`);
  console.log(`themes: ${THEMES.join(' ')}   frames: ${FRAMES.map(([w, h]) => `${w}x${h}`).join(' ')}`);
  console.log(`fixture names: ${fixtures.join(' ')}   live (no fixture): ${Object.entries(FIXTURES).filter(([, f]) => f === null).map(([a]) => a).join(' ')}`);
  console.log(`fixture modules: ${Object.entries(FIXTURES).filter(([, f]) => f !== null).map(([a, f]) => `${a}=${f.file}`).join(' ')}`);
  for (const capture of captures) console.log(`  ${capture.file}`);
  for (const theme of THEMES) console.log(`  sheet-${theme}.png`);
  process.exit(0);
}

/** The two URL shapes a fixture can arrive in. The shell canonicalises the hash at boot and keeps
 *  the query (rows.ts canonicalHash), and a page may read it from either the search or the hash,
 *  so both carry it — belt and braces, because a fixture that silently fails to load looks exactly
 *  like a page that rendered an empty daemon. */
function urlFor(address, fixture) {
  const query = fixture === null ? '' : `?fixture=${fixture.name}`;
  return `http://localhost:5173/#/${address}${query}`;
}

/**
 * Did the fixture actually load? The question is asked of the page, in two halves, because a
 * fixture that failed to load is INDISTINGUISHABLE from a page that rendered live or empty data —
 * turns, sessions, projects and logs interpolate the name into a dynamic import and swallow the
 * rejection, so a typo in the table above produces a frame that looks perfect and is a lie.
 *
 *   the module: a fetch of the file the page's own import asks for must answer 200. That is the
 *               half that catches a wrong or renamed name outright.
 *   the mark:   where the page prints the fixture's `sample data` label, it must be on screen.
 *               That is the half that catches the name being right while the page still renders
 *               live data (models gates on an exact match, so both halves are needed).
 *
 * teams and doctor do not print the mark (they hand the fixture straight to their board), so they
 * are held to the module proof alone, and the table says so per address rather than by a guess here.
 */
function fixtureProbe(address, fixture) {
  return `(async () => {
    const url = '/src/pages/${address}/fixtures/${fixture.file}.ts';
    let status = 0;
    try { status = (await fetch(url)).status; } catch (e) { status = -1; }
    const root = document.querySelector('[data-fixture]');
    return JSON.stringify({
      url,
      status,
      mark: document.body.innerText.toLowerCase().includes('sample data'),
      marker: root === null ? null : root.getAttribute('data-fixture'),
    });
  })()`;
}

/**
 * A capture whose fixture did not load is a FAILED capture, not a captured page.
 *
 * The DOM half prefers the marker M1-20 is putting on the fixture-fed page roots, because a marker
 * that carries the fixture's own FILE NAME is exact where a `sample data` label is merely present:
 * a page could print the label and still be showing live data. Until that marker exists this reads
 * null on every page and the label carries the half. Both sides are the same handshake, agreed in
 * the ledger before either pinned it: the attribute is `data-fixture` and its value is the module's
 * file name, which is also the string this gate fetches and asserts.
 */
function fixtureVerdict(fixture, answer) {
  if (fixture === null) return { ok: true, note: 'live' };
  const loaded = answer.status === 200;
  const byMarker = answer.marker !== null && answer.marker !== undefined;
  const domOk = byMarker ? answer.marker === fixture.file : (fixture.mark ? answer.mark : true);
  return {
    ok: loaded && domOk,
    note: `${fixture.file}:${answer.status}`
      + (byMarker ? (domOk ? ` +marker=${answer.marker}` : ` MARKER=${answer.marker}`)
        : (fixture.mark ? (answer.mark ? ' +mark' : ' NO-MARK') : '')),
  };
}

function sheetHtml(theme, themeCaptures) {
  const cells = themeCaptures.map((capture) => {
    const width = Math.round(capture.width / 4);
    return `<figure><img src="${capture.file}" width="${width}"><figcaption>${capture.file}<br>`
      + `<span class="blank">${capture.blank.toFixed(3)} room</span>${capture.blank >= BLANK_AT ? ' BLANK' : ''}`
      + (capture.fixtureOk ? '' : ` <span class="fail">FIXTURE FAILED ${capture.fixtureNote}</span>`)
      + (capture.fixture === null ? '' : ` <span class="blank">${capture.fixtureFile}</span>`)
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
  .fail { color: #ff7a5c; font-weight: 600; }
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
const fixtureFailures = [];
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
      const fixture = FIXTURES[capture.address];
      // ASKED BEFORE THE SHUTTER, so a frame whose fixture did not load is recorded as failed
      // rather than captured. WAITED FOR rather than sampled once: three pages resolve their
      // fixture through a dynamic import after first paint, and a single read at settle time made
      // usage, settings and models fail one theme and pass the other (measured 2026-09-18). The
      // poll asks the same question until it is answered or the budget runs out, and the LAST
      // answer is the verdict, so a page that never loads its fixture is still a failure.
      let verdict = fixtureVerdict(fixture, { status: -1 });
      if (fixture !== null) {
        for (let attempt = 0; attempt < 12; attempt += 1) {
          const asked = await send('Runtime.evaluate', {
            expression: fixtureProbe(capture.address, fixture),
            awaitPromise: true, returnByValue: true,
          });
          verdict = fixtureVerdict(fixture, JSON.parse(asked.result.value));
          if (verdict.ok) break;
          await sleep(500);
        }
      }
      const bytes = await shoot(send, join(outDir, capture.file));
      const blank = colorFraction(decodePng(bytes), hexToRgb(room[theme]));
      manifest.push({
        ...capture,
        fixture: fixture === null ? null : fixture.name,
        fixtureFile: fixture === null ? null : fixture.file,
        fixtureOk: verdict.ok,
        fixtureNote: verdict.note,
        blank: Number(blank.toFixed(4)),
      });
      if (blank >= BLANK_AT) blanks.push(capture.file);
      if (!verdict.ok) fixtureFailures.push(`${capture.file} (${verdict.note})`);
      if (TERMINAL) process.stdout.write(!verdict.ok ? 'F' : blank >= BLANK_AT ? 'B' : '.');
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
if (fixtureFailures.length > 0) {
  // A capture whose fixture did not load is a FAILED capture, not a captured page, and every
  // number a later row reads off these frames is a number about live data wearing a sample's name.
  // So this is not a warning: the run exits non-zero and the frames are not evidence.
  console.error(`  FIXTURE FAILED: ${fixtureFailures.length} of ${manifest.length} captures did not load their fixture:`);
  for (const line of fixtureFailures) console.error(`    ${line}`);
  process.exit(1);
}
