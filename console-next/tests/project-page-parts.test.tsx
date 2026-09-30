// The project page rendered to markup from a seeded cache: its sentence, who works here, the rule and the standing form.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { renderToStaticMarkup } from 'react-dom/server';
import { MemoryRouter, Route, Routes } from 'react-router';
import { describe, expect, test } from 'vitest';
import { projectPath } from '../src/api/projects';
import { ProjectPage } from '../src/pages/projects/ProjectPage';
import type { ProjectRow } from '../src/types/projects';

const ROOT = '/home/a/tally';
const project: ProjectRow = {
  id: ROOT, root: ROOT, live_sessions: 1, teams: 0, turns_today: 3, cost_today_usd: 0.25, day_start: 0, last_activity: null,
  compaction: [{ scope: 'project', source: `project:${ROOT}`, chars: 40 }], statusline_roots: [{ head: 'claude-grok', root: '/home/a', entry: 'home' }],
};

function render(seed: (client: QueryClient) => unknown, id = ROOT): string {
  const client = new QueryClient();
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
  test('is named for the folder, says what runs and lists the session by its name', () => {
    const html = render(seedRow);
    expect(html).toContain('tally');
    expect(html).toContain('1 session is running');
    expect(html).toContain('Write the limiter');
    expect(html).toContain('Grok');
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
  test('while the row is being read it says so', () => {
    expect(render(() => undefined)).toContain('Reading the project.');
  });
});
