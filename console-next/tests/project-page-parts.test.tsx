// The project page rendered to markup from a seeded cache: its sentence, who works here, the rule and the standing form.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { renderToStaticMarkup } from 'react-dom/server';
import { MemoryRouter, Route, Routes } from 'react-router';
import { describe, expect, test } from 'vitest';
import { projectPath } from '../src/api/projects';
import { MgmtError } from '../src/api/client';
import { ProjectPage } from '../src/pages/projects/ProjectPage';
import type { ProjectRow } from '../src/types/projects';

const ROOT = '/home/a/tally';
const project: ProjectRow = {
  id: ROOT, root: ROOT, live_sessions: 1, teams: 0, turns_today: 3, cost_today_usd: 0.25, day_start: 0, last_activity: null,
  compaction: [{ scope: 'project', source: `project:${ROOT}`, chars: 40 }], statusline_roots: [{ head: 'claude-grok', root: '/home/a', entry: 'home' }],
};

function render(seed: (client: QueryClient) => unknown, id = ROOT): string {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, retryOnMount: false } } });
  client.setQueryData(['teams', '/api/teams'], { teams: [] });
  client.setQueryData(['sessions', '/api/sessions'], { sessions: [{ session_id: 'sess-1', name: 'Write the limiter', head: 'claude-grok', availability: 'live', status: 'idle', pid: 1, kind: null, version: null, cwd: ROOT, status_updated_at: null, started_at: null, updated_at: 1, address: null, repo: { root: ROOT } }] });
  client.setQueryData(['heads', '/api/heads'], { heads: [{ key: 'claude-grok', label: 'Grok', authKind: 'grok' }] });
  client.setQueryData(['topology'], { path: '/c/splice.toml', topology: { projects: { [ROOT]: { system_prompt: 'Be brief.' } } }, stale: false });
  seed(client);
  return renderToStaticMarkup(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[`/projects/${encodeURIComponent(id)}`]}>
        <Routes><Route path="/projects/:id" element={<ProjectPage />} /></Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}
const seedRow = (client: QueryClient): void => void client.setQueryData(['projects', 'row', projectPath(ROOT)], project);

describe('the project page', () => {
  test('a live session with no activity timestamps does not claim no session ever touched the project', () => {
    const html = render(client => {
      seedRow(client);
      client.setQueryData(['sessions', '/api/sessions'], { sessions: [{
        session_id: 'synthetic', name: 'Synthetic', head: 'synthetic', availability: 'live',
        status: 'busy', pid: 1, cwd: ROOT, repo: { root: ROOT },
        started_at: null, status_updated_at: null, updated_at: null,
      }] });
    });
    expect(html).toContain('Last activity time is not reported.');
    expect(html).not.toContain('No session has touched it.');
  });
  test('is named for the folder, says what runs and lists the session by its name', () => {
    const html = render(seedRow);
    expect(html).toContain('tally');
    expect(html).toContain('1 session is running');
    expect(html).toContain('Write the limiter');
    expect(html).toContain('Grok');
  });
  test('the headline counts the same alive sessions it lists, including stale registrations', () => {
    const html = render((client) => {
      seedRow(client);
      const session = { session_id: 'one', name: 'One', head: 'claude-grok', availability: 'live', status: 'idle', pid: 1, cwd: ROOT, repo: { root: ROOT } };
      client.setQueryData(['sessions', '/api/sessions'], { sessions: [session, { ...session, session_id: 'two', name: 'Two', availability: 'stale' }, { ...session, session_id: 'gone', name: 'Gone', availability: 'gone' }, { ...session, session_id: 'elsewhere', name: 'Elsewhere', repo: { root: '/another/repo' } }] });
    });
    expect(html).toContain('2 sessions are running');
    expect(html).toContain('Two');
    expect(html).not.toContain('Gone');
    expect(html).not.toContain('Elsewhere');
  });
  test.each(['envelope', 'transport'])('a failed session enumeration via %s remains a failure, not an empty project', (kind) => {
    const html = render((client) => {
      seedRow(client);
      client.setQueryData(['sessions', '/api/sessions'], { sessions: [], ...(kind === 'envelope' ? { error: 'Synthetic registry permission denied' } : {}) });
      if (kind === 'transport') client.getQueryCache().find({ queryKey: ['sessions', '/api/sessions'] })?.setState({ status: 'error', error: new MgmtError(500, 'Synthetic registry permission denied') });
    });
    expect(html).toContain('Synthetic registry permission denied');
    expect(html).toContain('1 session is running');
    expect(html).not.toContain('Nothing is running');
    expect(html).not.toContain('No session is running in this repo.');
    expect(html).not.toContain('No live sessions.');
  });
  test('a pending session enumeration is visibly pending rather than claiming the project is empty', () => {
    const html = render((client) => {
      seedRow(client);
      client.removeQueries({ queryKey: ['sessions', '/api/sessions'] });
    });
    expect(html).toContain('Reading the sessions.');
    expect(html).not.toContain('No session is running in this repo.');
    expect(html).not.toContain('No live sessions.');
  });
  test.each([404, 500])('a project error %s is not confused with another failure or printed as a raw missing-project diagnostic', (status) => {
    const html = render((client) => {
      client.getQueryCache().build(client, { queryKey: ['projects', 'row', projectPath(ROOT)] }).setState({ status: 'error', error: new MgmtError(status, status === 404 ? 'not a project root splice has seen: ' + ROOT : 'Synthetic server failure') });
    });
    expect(html).not.toContain('not a project root splice has seen');
    if (status === 404) expect(html).toContain('No such project');
    else {
      expect(html).toContain('Synthetic server failure');
      expect(html).not.toContain('No such project');
    }
  });
  test('does not print the folder under the name: it is there on request', () => {
    const html = render(seedRow);
    expect(html).toContain('<summary>Show the folder</summary>');
    expect(html).toContain('<code>/home/a/tally</code>');
    expect(html).not.toContain('<p class="hint">/home/a/tally');
  });
  test('shows the standing prompt as it is held, in a form that starts unsaved', () => {
    const html = render(seedRow);
    expect(html).toContain('Be brief.');
    expect(html).toContain('Standing prompt');
    expect(html).toMatch(/<button[^>]*disabled[^>]*>Save<\/button>/);
  });
  test('names the plan’s trusted folder rather than the internal entry', () => {
    const html = render(seedRow);
    expect(html).toContain('Home folder');
    expect(html).not.toContain('statuslineGitRoots');
  });
  test('prints each compaction rule with its length, and says so when a rule is an opt-out or unreadable', () => {
    expect(render(seedRow)).toContain('40 characters');
    const held = (chars: number | null) => (client: QueryClient): void => void client.setQueryData(['projects', 'row', projectPath(ROOT)], { ...project, compaction: [{ scope: 'project', source: `project:${ROOT}`, chars }] });
    expect(render(held(0))).toContain('Explicitly none.');
    expect(render(held(null))).toContain('Its file cannot be read.');
  });
  test('a session splice did not start names no head, where the page would otherwise print the code for it', () => {
    const html = render((client) => {
      seedRow(client);
      client.setQueryData(['sessions', '/api/sessions'], { sessions: [{ session_id: 'sess-2', name: 'Plain session', head: 'unknown head', availability: 'live', status: 'idle', pid: 2, kind: null, version: null, cwd: ROOT, status_updated_at: null, started_at: null, updated_at: 1, address: null, repo: { root: ROOT } }] });
    });
    expect(html).toContain('Plain session');
    expect(html).not.toContain('unknown head');
  });
  test('while the row is being read it says so', () => {
    expect(render(() => undefined)).toContain('Reading the project.');
  });
});
