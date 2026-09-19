/**
 * THE TREE STAMP (M1-104): which tree a reading was taken from.
 *
 * WHY THIS MODULE EXISTS AND WHY IT IS ONE MODULE. Every artifact in this directory that carries a
 * denominator - the D7 sweep's 187 (now 186, now 189), the ink census's 324 pairs, M1-80's 73
 * dispositions - is a READING OF A TREE, and until tonight none of them said which. Measured
 * 2026-09-18: sweep-d7.mjs returned 189 rules / 73 unresolved and then 186 / 70 within minutes,
 * both correct, describing different trees, and nothing in either output said so. The consequence
 * was already real - three of the rules M1-80 dispositioned no longer exist and its record cannot
 * tell you that.
 *
 * It is ONE module rather than a function copied into each instrument for the reason lib/fixtures.mjs
 * carries in its own header: a duplicated measurement drifts silently, and both copies keep printing
 * good numbers. Three copies of one address table drifted twice in one day and produced captures
 * that looked exactly like good captures (M1-19, M1-28).
 *
 * WHAT IT DOES NOT DO, on purpose. It does not pin a denominator to a fixed number, and it does not
 * refuse a dirty tree. A campaign in flight is dirty by construction; an instrument that refused to
 * run would be worse than the problem it was guarding. It says what it looked at and nothing more.
 *
 * WHAT IT CANNOT SEE: git reports changes to files it tracks. A change committed between two runs
 * is invisible except as a different HEAD, and a genuine re-reading is the only way to know. The
 * stamp is evidence, not proof.
 */
import { execFileSync } from 'node:child_process';

/**
 * One porcelain line per file -> the paths, with the two status columns and their space removed.
 *
 * PURE AND EXPORTED SO THE PARSE CAN BE PROVEN RATHER THAN TRUSTED. The one-line bug this function
 * exists to guard was found by re-reading a committed artifact, not by any test: ` git status --porcelain`
 * prints `XY<space>path`, the leading space of the FIRST line is real, and the previous version
 * `.trim()`ed the whole git output before splitting — so the first line arrived as `M path`, the
 * uniform `slice(3)` below ate one character too many, and the first changed file came back as
 * `ebui/src/pages/fleet/fleet.css` while every other line was correct. A caller that only ever
 * looks at counts never sees it, which is why it survived a full row.
 */
export function parsePorcelain(text) {
  return String(text ?? '')
    .split('\n')
    .filter(Boolean)
    .map((line) => line.slice(3).replace(/\s+$/, ''));
}

/**
 * Raw git output, TRAILING whitespace trimmed only. Hoisted and exported so the selftest can run the
 * REAL command rather than a mock, because the bug this guards lived here and not in the parser:
 * `.trim()` on the whole output ate the LEADING SPACE of porcelain's first line -- ` M path` became
 * `M path` -- after which the uniform `slice(3)` in `parsePorcelain` removed one character too many
 * and the first changed file came back as `ebui/src/pages/fleet/fleet.css`. SILENT: the stamp still
 * said `dirty`, still counted correctly, and still looked entirely plausible; only the FIRST entry of
 * each listing was mangled, which are exactly the odds that survive inspection. A reading that
 * misnames the files it is reporting on is the failure this module exists to remove, so it does not
 * get to be the exception. `rev-parse HEAD` is unaffected: trailing trim is all it ever needed.
 */
export function gitOut(argv) {
  try {
    return execFileSync('git', argv, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] })
      .replace(/[\s\r\n]+$/, '');
  } catch { return null; }
}

/** Every path git reports as changed under `paths`. The one place the porcelain call is made. */
export function changedPaths(paths = ['webui/src']) {
  // `--porcelain` prints "XY path", and a rename prints "XY old -> new", so the path is what follows
  // the two status columns and the space after them.
  return parsePorcelain(gitOut(['status', '--porcelain', '--', ...paths]));
}

/**
 * @param paths the tree paths this reading depends on. Pass what the instrument ACTUALLY reads -
 *              the stamp is only useful if it names the files that could have moved the answer.
 */
export function treeState(paths = ['webui/src']) {
  const head = gitOut(['rev-parse', 'HEAD']);
  const changed = changedPaths(paths);
  const cssChanged = changed.filter((p) => p.endsWith('.css')).sort();
  return {
    head: head === null ? null : head.slice(0, 8),
    dirty: changed.length > 0,
    paths,
    // The CSS is separated out because CSS is what moves a denominator derived from stylesheets;
    // everything else is counted rather than listed so the stamp stays short enough to read.
    cssChanged,
    otherChanged: changed.length - cssChanged.length,
    at: new Date().toISOString(),
  };
}

/** One line, for a console or a document. */
export function describeTree(tree) {
  if (tree.head === null) return 'not a git tree';
  const where = `HEAD ${tree.head}, ${tree.dirty ? 'dirty' : 'clean'}`;
  if (!tree.dirty) return where;
  const css = tree.cssChanged.length ? `${tree.cssChanged.length} css file(s) differ` : 'no css differs';
  return `${where} — ${css}, ${tree.otherChanged} other file(s) differ`;
}

// -------------------------------------------------------------------------------------------
// SELFTEST (M1-113). The parse is pure so it can be checked without a tree, and the LIVE case runs
// the real porcelain call because that is where the bug actually was.
//
// The second case asserts the MANGLED form deliberately. A selftest that only asserts the right
// answer cannot show that the wrong one was ever possible, and the difference between `webui/...`
// and `ebui/...` is one character that a reader skims - so the bug's signature is pinned here as
// the thing the first case differs from.
// -------------------------------------------------------------------------------------------
export const SELFTEST = [
  {
    name: 'a porcelain line keeps the leading space its path depends on',
    got: () => parsePorcelain(' M webui/src/a.css\n'),
    want: ['webui/src/a.css'],
  },
  {
    name: "the bug's signature: the pre-trim line mangles the first character (this is what the fixed gitOut no longer produces)",
    got: () => parsePorcelain('M webui/src/a.css\n'),
    want: ['ebui/src/a.css'],
  },
  {
    name: 'every line of a multi-entry listing parses, untracked and modified alike',
    got: () => parsePorcelain(' M webui/src/a.css\n?? webui/src/b.css\nMM webui/src/c.css\n'),
    want: ['webui/src/a.css', 'webui/src/b.css', 'webui/src/c.css'],
  },
  {
    name: 'no output is no paths, not a crash',
    got: () => parsePorcelain(null),
    want: [],
  },
];

if (process.argv.includes('--selftest')) {
  let bad = 0;
  for (const t of SELFTEST) {
    const got = JSON.stringify(t.got());
    const ok = got === JSON.stringify(t.want);
    if (!ok) bad += 1;
    console.log(`  ${ok ? 'ok  ' : 'FAIL'} ${t.name}`);
    if (!ok) console.log(`       want ${JSON.stringify(t.want)}\n       got  ${got}`);
  }
  // THE LIVE CASE: the real command, the real trim, against a path that has dirt under it. A path
  // that has lost its first character is not under the requested prefix, so this is the assertion
  // the shipped bug would have failed. An empty listing is DID NOT RUN, not a pass (law 23).
  const live = changedPaths(['webui/src']);
  if (live.length === 0) {
    console.log('  ??   live porcelain: DID NOT RUN - webui/src is clean, so nothing could be misparsed');
  } else {
    const malformed = live.filter((p) => !p.startsWith('webui/src/'));
    const ok = malformed.length === 0;
    if (!ok) bad += 1;
    console.log(`  ${ok ? 'ok  ' : 'FAIL'} live porcelain: ${live.length} changed path(s), every one intact${ok ? '' : ` - MALFORMED: ${JSON.stringify(malformed)}`}`);
  }
  console.log(bad === 0 ? 'selftest: ok' : `selftest: ${bad} case(s) FAILED`);
  process.exit(bad === 0 ? 0 : 1);
}
