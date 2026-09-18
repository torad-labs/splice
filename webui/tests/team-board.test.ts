// TEAM BOARD, the hero (row M1-05). Three claims, each one a thing that could
// silently stop being true:
//
//   1. the board renders the comp's words — all of them, listed from the spec's
//      text regions, because the operator approved that comp with those words;
//   2. against a daemon that has no teams route yet, the page prints the honest
//      empty naming the work item rather than an empty board;
//   3. the fixture cannot reach a production bundle: its bytes are behind the
//      dev guard, and the built file is grepped for them in the row's note.
//
// A .ts file holds no JSX (CONTRACTS.md section 4), so the elements are built
// with createElement and read back through renderToStaticMarkup.
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, test } from 'vitest';

import { PENDING_TEAMS } from '../src/entities/team';
import { MgmtError, pendingOf } from '../src/shared/api';
import { TeamBoard } from '../src/widgets/team-board';
import { heroBoard } from '../src/pages/teams/fixtures/hero';
import { teamsBodyFor } from '../src/pages/teams';

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const read = (relative: string): string => readFileSync(path.join(repoRoot, relative), 'utf8');

/** Static markup escapes quotes and ampersands; the words are the words. */
function unescapeHtml(html: string): string {
  return html
    .replace(/&quot;/g, '"')
    .replace(/&#x27;/g, "'")
    .replace(/&gt;/g, '>')
    .replace(/&lt;/g, '<')
    .replace(/&amp;/g, '&');
}

const boardHtml = unescapeHtml(renderToStaticMarkup(createElement(TeamBoard, { board: heroBoard })));

// Every word the comp prints, gathered from the spec's text and control
// regions: the header strip, the two session strips' three lines each, the bay
// labels, the chat strips, the activity strips and the footer.
const COMP_WORDS: string[] = [
  // team header strip
  'team', 'goal', 'repo', 'slots', 'lead driving',
  'grailseeker-backend', 'promoter bookkeeping lives on fly raw',
  '~/Documents/dev/infra/grailseeker-backend', '3 slots, 3 bound', 'gs-backend-claude',
  // session strip, line one
  'name', 'role', 'model', 'account', 'window', 'last turn', 'state',
  'lead', 'fable', 'acct-a', '5h 74%', '14:01', 'driving',
  // session strip, line two
  'head', 'session id', 'created', 'uptime', 'turns', 'tokens in', 'tokens out', 'cost est',
  'claude', 's-1a7c9e2d', '09:12:33', '4h 49m', '27', '182,341', '71,552', '$0.412',
  // session strip, line three
  'context left', 'scratchpad', 'workspace', 'branch', 'base', 'diff', 'checks',
  '35%', '12.8 k', '~/.../grailseeker-backend', 'feature/gs-41', 'main', '+412 -37', 'pass',
  // the builder's three lines
  'gs-backend-builder', 'builder', 'deepseek-flash', 'n/r', '13:58', 'building GS-41 done',
  'claude-deepseek', 's-5f3b8a11', '09:18:02', '4h 44m', '18', '93,274', '38,611', '$0.183',
  '8.1 k', '+289 -37',
  // bay labels
  'head: claude', 'head: claude-deepseek',
  'team chat (newest at bottom)', 'activity, sampled every 30 s',
  // the chat strips
  'time', 'from', '→', 'to', 'packet', 'message',
  '13:41', 'packet GS-41: promoter bookkeeping lives on fly raw. build, run parity, report',
  'GS-41 done, see ledger', 'gs-backend-builder2', 'GS-42',
  'packet GS-42: dearm the machine-update schedule',
  // the activity strips
  'member', 'activity', 'detail',
  '14:01:30', 'editing PromoterLedger.kt', 'src/ledger/PromoterLedger.kt:142',
  'running checks/gate.sh', 'checks/gate.sh --all',
  'searching for fly_raw', 'rg -n "fly_raw"',
  '14:02:00', 'reading MANIFEST.toml', 'MANIFEST.toml',
  'messaging a peer session', 'to gs-backend-builder2',
  // the footer
  'team id:', 'team_7f2c1b4a', 'created:', '2025-05-22 09:12:33', 'updated:', '2025-05-22 14:02:05',
];

describe('the board renders the comp', () => {
  test('the fixture is the comp, word for word', () => {
    const missing = COMP_WORDS.filter((word) => !boardHtml.includes(word));
    expect(missing, 'words the comp prints and the board does not').toEqual([]);
  });

  test('every comp word is a real string in the output, not markup', () => {
    expect(boardHtml.startsWith('<section')).toBe(true);
    expect(boardHtml.length).toBeGreaterThan(2000);
  });

  test('the board racks one bay per head the members run on', () => {
    expect(boardHtml).toContain('head: claude');
    expect(boardHtml).toContain('head: claude-deepseek');
  });

  test('a figure no provider reports prints n/r rather than a zero', () => {
    const builder = heroBoard.members[1];
    expect(builder.window).toBeNull();
    expect(builder.contextLeftPct).toBeNull();
    expect(boardHtml).toContain('n/r');
    // Read the cells, not the markup: the board's rows carry their own pitch in
    // a style attribute, and a row at the top of its rack is legitimately 0%.
    const values = [...boardHtml.matchAll(/myx-sfield-text">([^<]*)</g)].map((match) => match[1]);
    expect(values).not.toContain('0%');
  });
});

describe('against a daemon that has no teams route', () => {
  test('the page prints the honest empty naming V4-131', () => {
    const pending = { pending: 'V4-131' };
    const html = unescapeHtml(renderToStaticMarkup(teamsBodyFor({ fixture: null, view: 'by-head', teams: pending, team: pending })));
    expect(html).toContain('no teams route');
    expect(html).toContain('V4-131 pending');
  });

  test('before anything has answered, the page claims neither absence nor failure', () => {
    const html = unescapeHtml(renderToStaticMarkup(teamsBodyFor({ fixture: null, view: 'by-head', teams: null, team: null })));
    expect(html).toContain('reading teams');
    expect(html).not.toContain('V4-131 pending');
    expect(html).not.toContain('unreadable');
  });

  test('a 404 is what makes it the honest empty, through the shared mapping', () => {
    const pending = pendingOf(new MgmtError(404, 'unknown route'), PENDING_TEAMS);
    expect(pending).toEqual({ pending: 'V4-131' });
    const html = unescapeHtml(
      renderToStaticMarkup(teamsBodyFor({ fixture: null, view: 'by-head', teams: pending, team: pending })),
    );
    expect(html).toContain('V4-131 pending');
  });

  test('a daemon that answers with no teams is not dressed as a missing route', () => {
    const empty = { teams: [] };
    const html = unescapeHtml(
      renderToStaticMarkup(teamsBodyFor({ fixture: null, view: 'by-head', teams: empty, team: null })),
    );
    expect(html).toContain('no teams yet');
    expect(html).not.toContain('V4-131 pending');
  });

  test('the two views this row does not build say so', () => {
    for (const view of ['by-role', 'timeline']) {
      const html = unescapeHtml(renderToStaticMarkup(teamsBodyFor({ fixture: heroBoard, view, teams: null, team: null })));
      expect(html, view).toContain('view not built');
      expect(html, view).toContain('row M2-08');
    }
  });
});

describe('the fixture stays out of a production bundle', () => {
  const source = read('webui/src/pages/teams/index.tsx');

  test('the fixture is never reached by a static import, and its specifier is not a literal', () => {
    // A static import makes the fixture reachable whether or not the guard's branch runs, so the
    // bundler keeps its bytes. Measured: the comp's words were in dist/index.html until this import
    // became a dynamic one. And the specifier must be COMPOSED AT RUNTIME (M1-20): a statically
    // analyzable `import('./fixtures/hero')` stays a dependency edge through the single-file build
    // even when the branch around it is dead, which shipped this fixture's bytes again - the wall
    // named 15 of them.
    expect(source).not.toMatch(/^import .*from '\.\/fixtures\//m);
    expect(source).toContain('import(/* @vite-ignore */ `./fixtures/${FIXTURE}.ts`)');
  });

  test('every use of the fixture sits behind the dev guard', () => {
    const guard = source.indexOf('import.meta.env.DEV');
    expect(guard, 'the dev guard is gone from the page').toBeGreaterThan(-1);
    const uses = [...source.matchAll(/heroBoard/g)].map((match) => match.index ?? -1);
    expect(uses.length, 'the fixture must be referenced at its load').toBeGreaterThan(0);
    for (const at of uses) expect(at).toBeGreaterThan(guard);
  });

  test('the guard is a static condition the bundler can eliminate', () => {
    // The guard moved into `wantsFixture` (M1-20) so that the load and the capture marker are one
    // decision, and it is still a STATIC conjunction: `import.meta.env.DEV` is replaced by false in
    // a production build, so the branch — and the fixture with it — is dropped. A guard read from a
    // variable would keep the branch reachable and the bytes with it, which is the measured leak
    // this whole group exists for.
    expect(source).toContain("return import.meta.env.DEV && new URLSearchParams(search).get('fixture') === FIXTURE;");
    // The branch is braced because it CLEARS the fixture state before returning: an early return
    // that left the previous value in place kept a stale capture marker across a hash change
    // (measured in a browser, M1-20).
    expect(source).toContain('if (!wantsFixture(search)) {');
    expect(source).toContain('setSample(null);');
  });
});
