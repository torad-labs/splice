// NO FIXTURE BYTE SHIPS (M2-12, CONTRACTS.md section 4; evidence rule rebuilt 2026-09-18, M1-20).
//
// WHY THIS EXISTS. A capture fixture is dev-only by contract, and the runtime guard
// (`import.meta.env.DEV`) is not what keeps it out of the artifact: a STATIC import of a fixture
// module is a real dependency edge, so the bundler includes the module and its strings ship inside
// dist/index.html. The build is a single inlined file, so a leaked fixture is not a stray chunk — it
// is bytes in the console the operator downloads. The fix at the source is `await import(...)`
// inside the DEV branch; this script is what proves the fix holds, per fixture, by name.
//
// HOW IT DECIDES, and the three corrections that made it able to fail at all:
//
//  1. THE CORPUS EXCLUDES FIXTURES. The first version ruled a literal out as evidence if any other
//     file under webui/src contained it — and that corpus included the OTHER fixtures, so ten
//     fixtures vetoed each other's evidence and the whole wall could not see a leak that ten
//     sibling files happened to share a string with. The corpus is now application source only.
//
//  2. A LITERAL BELONGS TO THE FIXTURE THAT COULD HAVE SHIPPED IT. A string two fixtures share is
//     evidence for neither on its own: it is in dist if EITHER shipped, so reporting it against
//     both would be a false alarm against one of them. A literal counts only when exactly one
//     fixture owns it, and the report says how many were set aside for that reason.
//
//  3. THE SCANNER IS A SCANNER. The first version paired quotes with a regex, so an apostrophe in
//     a comment ("the operator's own") opened a bogus literal that ran to the next real one — which
//     is how it reported "checked 2" for a fixture with seventeen strings, and how a planted needle
//     re-aligned the pairing and made a pre-existing leak appear. Literals are now read by a
//     character scanner that knows comments, escapes, templates and both quote characters.
//
// WHAT IT DOES NOT COVER, stated rather than implied: the needle is the literal AS WRITTEN, so a
// literal carrying a backslash escape is skipped rather than unescaped (its count is printed beside
// every fixture, so the denominator stays visible).
//
// A FIXTURE WITH NO EVIDENCE IS A FAILURE, not a quiet pass (law 23: an instrument must be able to
// say DID NOT RUN, and it is never the same as PASSED). It exits non-zero, by name, and it never
// reports a count that reads like a check.
//
// M1-76 DISPOSITION — the two ways a check can be decorative, answered for this file. CLEAN BOTH.
//   SHAPE ONE, does every FAIL reach the exit code? YES. Each failure path exits non-zero by name;
//     nothing here prints a finding and returns 0.
//   SHAPE TWO, if the build produced nothing, what would it print? IT REFUSES, TWICE, EXPLICITLY:
//     :166 guards `evidence.length === 0` and :196 guards `markerFiles.length === 0`, each exiting
//     1 rather than reporting a clean sweep of an empty set. The header above already said it in
//     words — "a fixture with no evidence is a FAILURE, not a quiet pass ... it never reports a
//     count that reads like a check" — and unlike the same sentence in comp-check's SIDES doc
//     comment (which described an intention the code did not carry, found by M1-70) this one is
//     true of the code. Verified by reading both guards, not by trusting the paragraph.
//
// Usage: node .dev/web-console/fixture-leak.mjs   (from the worktree root; builds first)
import { execFileSync } from 'node:child_process';
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';

const root = process.cwd();
const webui = join(root, 'webui');
const srcRoot = join(webui, 'src');
const distEntry = join(webui, 'dist', 'index.html');

/** Every file under a directory, recursively. */
function walk(dir) {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    return statSync(path).isDirectory() ? walk(path) : [path];
  });
}

/**
 * The single-quoted literals of 10+ characters in a TypeScript file, and how many were skipped for
 * carrying an escape.
 *
 * A character scanner and not a regex: comments are not code, and an apostrophe inside one is not a
 * quote. It also has to survive `'https://…'` — a literal containing `//` — so comment stripping
 * cannot be a text pass either. States: code, line comment, block comment, single, double, template.
 */
function literalsOf(text) {
  const found = new Set();
  let skipped = 0;
  let at = 0;
  let state = 'code';
  let start = 0;
  while (at < text.length) {
    const ch = text[at];
    const next = text[at + 1];
    if (state === 'code') {
      if (ch === '/' && next === '/') { state = 'line'; at += 2; continue; }
      if (ch === '/' && next === '*') { state = 'block'; at += 2; continue; }
      if (ch === "'") { state = 'single'; start = at + 1; at += 1; continue; }
      if (ch === '"') { state = 'double'; at += 1; continue; }
      if (ch === '`') { state = 'template'; at += 1; continue; }
      at += 1; continue;
    }
    if (state === 'line') { if (ch === '\n') state = 'code'; at += 1; continue; }
    if (state === 'block') {
      if (ch === '*' && next === '/') { state = 'code'; at += 2; continue; }
      at += 1; continue;
    }
    if (state === 'template') {
      // A template can hold an expression, but for evidence purposes the text between backticks is
      // not a literal this wall can search for: skip to the closing backtick.
      if (ch === '\\') { at += 2; continue; }
      if (ch === '`') state = 'code';
      at += 1; continue;
    }
    // inside a single or a double quoted string
    if (ch === '\\') { at += 2; continue; }
    if ((state === 'single' && ch === "'") || (state === 'double' && ch === '"')) {
      if (state === 'single') {
        const literal = text.slice(start, at);
        if (literal.length >= 10) {
          if (literal.includes('\\')) skipped += 1;
          else found.add(literal);
        }
      }
      state = 'code'; at += 1; continue;
    }
    at += 1;
  }
  return { literals: found, skipped };
}

// 1. Build. The dist this script reads has to be the dist the tree produces right now; a stale
//    artifact is a green run about code that is not there any more (the shell writes it, and the
//    orchestrator commits it at the milestone gate).
console.log('building webui…');
// `npx vite build` and not `npm run build -w webui` (law 25): the npm script is tsc-then-vite, so a
// peer's live type error would mask a CSS defect, and this script's build leg exists to be the one
// real CSS parser in the campaign. The bundler is the only instrument here that reads a stylesheet
// as a stylesheet - eslint does not lint these files, tsc never sees them, vitest renders to a
// string with no stylesheet, ast-grep's rules are TypeScript and detect reads text. Measured
// 2026-09-18: a stray declaration at ui.css:350 sat through a green M1-17 verify and was found by
// whoever built next.
execFileSync('npx', ['vite', 'build'], { cwd: webui, stdio: 'inherit' });
if (!existsSync(distEntry)) {
  console.error(`fixture-leak: no ${relative(root, distEntry)} after the build`);
  process.exit(1);
}

// 2. The corpus: application source ONLY. A fixture in the corpus vetoes its siblings' evidence,
//    which is how this wall spent its life green while three fixtures shipped.
const sources = walk(srcRoot).filter((path) => /\.(ts|tsx)$/.test(path));
const isFixture = (path) => path.includes('/fixtures/');
const appText = sources
  .filter((path) => !isFixture(path))
  .map((path) => readFileSync(path, 'utf8'))
  .join('\n');

// 3. Every fixture the pages ship.
const pagesDir = join(srcRoot, 'pages');
const fixtures = readdirSync(pagesDir).flatMap((page) => {
  const dir = join(pagesDir, page, 'fixtures');
  if (!existsSync(dir)) return [];
  return readdirSync(dir)
    .filter((name) => name.endsWith('.ts'))
    .map((name) => join(dir, name));
});

const dist = readFileSync(distEntry, 'utf8');
const scans = new Map(fixtures.map((path) => [path, literalsOf(readFileSync(path, 'utf8'))]));
const leaks = [];
const blind = [];
let checkedTotal = 0;
let sharedTotal = 0;

for (const fixture of fixtures) {
  const { literals, skipped } = scans.get(fixture);
  // Evidence: a literal the app does not print AND exactly one fixture owns. A string two fixtures
  // share is in dist if EITHER shipped, so it is set aside rather than blamed on both.
  const candidates = [...literals].filter((literal) => !appText.includes(literal));
  const evidence = candidates.filter((literal) =>
    fixtures.filter((other) => scans.get(other).literals.has(literal)).length === 1);
  sharedTotal += candidates.length - evidence.length;

  const leaked = evidence.filter((literal) => dist.includes(literal));
  checkedTotal += evidence.length;
  leaks.push(...leaked.map((literal) => ({ fixture, literal })));

  const name = relative(root, fixture);
  const note = skipped > 0 ? `, ${skipped} skipped (escapes)` : '';
  if (evidence.length === 0) {
    // Not a pass: there is nothing here this wall can search for. It is named, counted, and the run
    // fails below (law 23).
    blind.push(fixture);
    console.error(`${name}: BLIND — 0 literal(s) are this fixture's own, so nothing could be checked${note}`);
  } else if (leaked.length === 0) {
    console.log(`${name}: checked ${evidence.length} literal(s) this fixture alone owns${note} — 0 in dist`);
  } else {
    for (const literal of leaked) {
      console.error(`LEAK ${name}: ${JSON.stringify(literal)} ships in webui/dist/index.html`);
    }
  }
}

// 4. The capture marker (M1-20, law 23). Two assertions off one enumeration each, and the second
//    is the one that catches a page added LATER:
//      a. every page that ships a fixture must DECLARE the marker. A page without one is a capture
//         no instrument can check: a fixture name that does not exist fails a guarded dynamic
//         import silently, the page renders live data, and the frame looks like a working capture.
//         The denominator is the fixtures directory listing, never a hand-written list of pages.
//      b. the marker's own NAME must be absent from dist. It is set inside a DEV-guarded
//         expression, so a production build drops the branch and the string with it - and if that
//         ever stops being true, the marker is being written on a branch that ships.
const MARKER = 'data-sample';
const markerFiles = walk(srcRoot)
  .filter((path) => path.endsWith('.tsx') && readFileSync(path, 'utf8').includes(MARKER))
  .map((path) => relative(root, path));
const fixturePages = [...new Set(fixtures.map((path) => path.split('/pages/')[1].split('/')[0]))];
const missingMarker = fixturePages.filter((page) => !markerFiles.some((path) => path.includes(`/pages/${page}/`)));

if (markerFiles.length === 0) {
  // The same rule the literal check follows: a wall that cannot fail must never read as a wall
  // that passed. No declaration anywhere means the assertion below has no subject.
  console.error(`fixture-leak: BLIND — no source file declares ${MARKER}, so the marker assertion cannot fail`);
  process.exit(1);
}
if (missingMarker.length > 0) {
  for (const page of missingMarker) {
    console.error(`MARKER MISSING webui/src/pages/${page}: ships a fixture and declares no ${MARKER}, so a capture of it cannot be checked`);
  }
  process.exit(1);
}
if (dist.includes(MARKER)) {
  console.error(`LEAK ${MARKER} ships in webui/dist/index.html: it is set inside a DEV-guarded expression, so a production build must drop its name`);
  process.exit(1);
}
console.log(`marker: ${markerFiles.length} source file(s) declare ${MARKER}, all ${fixturePages.length} fixture page(s) covered — absent from dist`);

if (leaks.length > 0) {
  console.error(`\nfixture-leak: ${leaks.length} fixture literal(s) ship in dist across ${new Set(leaks.map((l) => l.fixture)).size} fixture(s)`);
  process.exit(1);
}
if (blind.length > 0) {
  console.error(`\nfixture-leak: ${blind.length} fixture(s) contributed no evidence, so they were NOT checked: ${blind.map((f) => relative(root, f)).join(', ')}`);
  process.exit(1);
}
console.log(`\nfixture-leak: ${fixtures.length} fixture(s), ${checkedTotal} literal(s) checked${sharedTotal > 0 ? ` (${sharedTotal} set aside as shared between fixtures)` : ''}, 0 in dist`);
