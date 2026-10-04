import { describe, expect, test } from 'vitest';
import { projectTeams, projectHandoffs, handoffText } from '../src/lib/project-teams';
import type { SessionEdge, SessionRow } from '../src/types/sessions';

const seat = (id: string, root = '/work/one/app', over: Partial<SessionRow> = {}): SessionRow => ({
  session_id: id, name: id, pid: 1, head: 'test-head', kind: 'interactive', version: null,
  cwd: root, status: 'busy', availability: 'live', address: `uds:/synthetic/${id}`,
  status_updated_at: null, started_at: null, updated_at: null, repo: { root }, ...over,
});
const edge = (from: string, to: string, at = 1, direction: SessionEdge['direction'] = 'out'): SessionEdge => ({ from, to, at, direction });

describe('teams from the registry', () => {
  test('same root is one cohort without any saved team binding', () => {
    const rows = [seat('lead'), seat('builder'), seat('console')];
    expect(projectTeams(rows).groups).toHaveLength(1);
    expect(projectTeams(rows).groups[0]?.sessions).toEqual(rows);
  });
  test('equal folder names in different roots remain different teams', () => {
    expect(projectTeams([seat('one'), seat('two', '/work/two/app')]).groups).toHaveLength(2);
  });
  test('worktrees use the common root already resolved by the daemon', () => {
    expect(projectTeams([seat('one'), seat('two', '/work/one/app', {
      cwd: '/work/scratch', repo: { root: '/work/one/app', worktree: '/work/scratch' },
    })]).groups).toHaveLength(1);
  });
  test('stale sessions stay, ended sessions leave, and reported working folders remain projects', () => {
    const stale = seat('stale', '/work/one/app', { availability: 'stale' });
    const folder = seat('folder');
    delete folder.repo;
    const untrusted = seat('untrusted', '/work/outside', { repo: { root: '/work/outside', reason: 'Outside the trusted set.' } });
    const missing = seat('missing', '', { cwd: null });
    delete missing.repo;
    const result = projectTeams([stale, folder, missing, untrusted, seat('ended', '/work/one/app', { availability: 'gone' })]);
    expect(result.groups[0]?.sessions).toEqual([stale, folder]);
    expect(result.groups[1]?.sessions).toEqual([untrusted]);
    expect(result.unresolved).toEqual([missing]);
  });
});

describe('observed messages within a project', () => {
  const rows = [seat('lead'), seat('builder')];
  test('sender id and recipient address have distinct meanings, and duplicate incoming copies count once', () => {
    const sent = edge('lead', 'uds:/synthetic/builder');
    const received = { ...sent, direction: 'in' as const };
    expect(projectHandoffs(rows, { lead: [sent], builder: [received] })).toEqual([sent]);
  });
  test('names resolve only when unambiguous, and cross-project messages are not internal handoffs', () => {
    expect(projectHandoffs(rows, { lead: [edge('lead', 'builder')] })).toHaveLength(1);
    expect(projectHandoffs([...rows, seat('duplicate', '/work/one/app', { name: 'builder' })], { lead: [edge('lead', 'builder')] })).toEqual([]);
    expect(projectHandoffs(rows, { lead: [edge('lead', 'elsewhere'), edge('lead', 'uds:/unknown')] })).toEqual([]);
  });
  test('newest comes first and only the requested message supplies its text', () => {
    const older = edge('lead', 'builder', 1);
    const latest = edge('builder', 'lead', 2);
    expect(projectHandoffs(rows, { lead: [older, { ...latest, direction: 'in' }], builder: [latest] }).map(row => row.at)).toEqual([2, 1]);
    const message = { ...older, text: 'Synthetic handoff.', text_source: 'synthetic', missing_reason: null };
    expect(handoffText(older, [message])).toBe(message);
    expect(handoffText(latest, [message])).toBeNull();
  });
});
