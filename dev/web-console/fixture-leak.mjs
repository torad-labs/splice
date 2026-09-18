// NO FIXTURE BYTE SHIPS (M2-12, CONTRACTS.md section 4).
//
// WHY THIS EXISTS. A capture fixture is dev-only by contract, and the runtime guard
// (`import.meta.env.DEV`) is not what keeps it out of the artifact: a STATIC import of a fixture
// module is a real dependency edge, so the bundler includes the module and its strings ship inside
// dist/index.html. The build is a single inlined file, so a leaked fixture is not a stray chunk — it
// is bytes in the console the operator downloads. The fix at the source is `await import(...)`
// inside the DEV branch; this script is what proves the fix holds, per fixture, by name.
//
// HOW IT DECIDES. A string is EVIDENCE only if it exists nowhere else in the source: a literal the
// app itself also prints would be in dist legitimately, and reporting it would be a false alarm that
// teaches everyone to ignore the wall. So each fixture's single-quoted literals of 10+ characters
// are kept only when no other file under webui/src contains them, and those are searched for in
// dist/index.html. A hit can then only have come from the fixture.
//
// WHAT IT DOES NOT COVER, stated rather than implied: the needle is the literal AS WRITTEN, so a
// literal carrying a backslash escape is skipped rather than unescaped (the count of skipped ones
// is printed, so the denominator is visible); and a fixture whose every string also appears in
// application code has no evidence left to search for — it is reported with its count so that
// "checked 0" can never be mistaken for "clean".
//
// Usage: node dev/web-console/fixture-leak.mjs   (from the worktree root; builds first)
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

/** The single-quoted literals of 10+ characters in a file, and how many were skipped for escapes. */
function literalsOf(text) {
  const found = new Set();
  let skipped = 0;
  for (const match of text.matchAll(/'((?:[^'\\\n]|\\.){10,})'/g)) {
    const literal = match[1];
    if (literal.includes('\\')) skipped += 1;
    else found.add(literal);
  }
  return { literals: found, skipped };
}

// 1. Build. The dist this script reads has to be the dist the tree produces right now; a stale
//    artifact is a green run about code that is not there any more (the shell writes it, and the
//    orchestrator commits it at the milestone gate).
console.log('building webui…');
execFileSync('npm', ['run', 'build', '-w', 'webui'], { cwd: root, stdio: 'inherit' });
if (!existsSync(distEntry)) {
  console.error(`fixture-leak: no ${relative(root, distEntry)} after the build`);
  process.exit(1);
}

// 2. The corpus: every source file, so a literal the app itself prints can be ruled out as evidence.
const sources = walk(srcRoot).filter((path) => /\.(ts|tsx)$/.test(path));

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
const leaks = [];
let checkedTotal = 0;

for (const fixture of fixtures) {
  const own = readFileSync(fixture, 'utf8');
  const { literals, skipped } = literalsOf(own);

  // Evidence: a literal no OTHER source file contains.
  const others = sources
    .filter((path) => path !== fixture)
    .map((path) => readFileSync(path, 'utf8'))
    .join('\n');
  const evidence = [...literals].filter((literal) => !others.includes(literal));

  const leaked = evidence.filter((literal) => dist.includes(literal));
  checkedTotal += evidence.length;
  leaks.push(...leaked.map((literal) => ({ fixture, literal })));

  const name = relative(root, fixture);
  const note = skipped > 0 ? `, ${skipped} skipped (escapes)` : '';
  if (leaked.length === 0 && evidence.length === 0) {
    // "checked 0, 0 in dist" reads like a pass and is not one: every string this fixture holds also
    // appears in application code, so there is no evidence left to search for. It says so, loudly,
    // because a wall that cannot fail must never be read as a wall that passed.
    console.log(`${name}: BLIND — 0 literal(s) of 10+ characters are unique to this fixture, so nothing could be checked${note}`);
  } else if (leaked.length === 0) {
    console.log(`${name}: checked ${evidence.length} literal(s) of 10+ characters${note} — 0 in dist`);
  } else {
    for (const literal of leaked) {
      console.error(`LEAK ${name}: ${JSON.stringify(literal)} ships in webui/dist/index.html`);
    }
  }
}

if (leaks.length > 0) {
  console.error(`\nfixture-leak: ${leaks.length} fixture literal(s) ship in dist across ${new Set(leaks.map((l) => l.fixture)).size} fixture(s)`);
  process.exit(1);
}
console.log(`\nfixture-leak: ${fixtures.length} fixture(s), ${checkedTotal} literal(s) checked, 0 in dist`);
