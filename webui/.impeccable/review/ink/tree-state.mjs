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
 * @param paths the tree paths this reading depends on. Pass what the instrument ACTUALLY reads -
 *              the stamp is only useful if it names the files that could have moved the answer.
 */
export function treeState(paths = ['webui/src']) {
  const git = (argv) => {
    try {
      return execFileSync('git', argv, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }).trim();
    } catch { return null; }
  };
  const head = git(['rev-parse', 'HEAD']);
  // `--porcelain` prints "XY path", and a rename prints "XY old -> new", so the path is what
  // follows the two status columns and the space after them.
  const porcelain = git(['status', '--porcelain', '--', ...paths]);
  const changed = (porcelain ?? '').split('\n').filter(Boolean).map((l) => l.slice(3).trim());
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
