// The Projects arithmetic: a repo's sentence, and the standing prompt and rule read from and written into splice.toml.
import { describe, expect, test } from 'vitest';
import { liveNames, projectLede, repoLabel, sessionsIn, standingKeys, standingOf, withStanding } from '../src/lib/projects';
import type { ProjectRow } from '../src/types/projects';
import type { SessionRow } from '../src/types/sessions';

const ROOT = '/home/a/tally';
const row = (over: Partial<ProjectRow> = {}): ProjectRow => ({
  id: ROOT, root: ROOT, live_sessions: 2, teams: 1, turns_today: 12, cost_today_usd: 1.5, day_start: 0, last_activity: 1, compaction: [], statusline_roots: [], ...over,
});
const session = (over: Partial<SessionRow>): SessionRow =>
  ({ session_id: 's', name: 'Write it', head: 'claude-grok', availability: 'live', status: 'idle', pid: 1, kind: null, version: null, cwd: null, status_updated_at: null, started_at: null, updated_at: 1, address: null, ...over }) as SessionRow;

describe('a project in words', () => {
  test('its name is the folder', () => {
    expect(repoLabel(ROOT)).toBe('tally');
    expect(repoLabel('/')).toBe('/');
    expect(repoLabel('/home/ava/mythos/repo', 'git@github.com:torad-labs/splice.git')).toBe('splice');
    expect(repoLabel('/home/ava/mythos/repo')).toBe('mythos/repo');
  });
  test('the sentence says what runs, which teams work here and what today cost', () => {
    expect(projectLede(row())).toBe('2 sessions are running and 1 team works here. 12 turns today. About $1.50 of API cost.');
    expect(projectLede(row({ live_sessions: 0, teams: 0, turns_today: 0 }))).toBe('Nothing is running. No turns today.');
  });
  test('turns with no priced turn state no cost rather than a cost of zero', () => {
    expect(projectLede(row({ cost_today_usd: null, teams: 0, live_sessions: 1 }))).toBe('1 session is running. 12 turns today. API cost today –.');
  });
  test('only live sessions whose repo is this one count', () => {
    const here = session({ session_id: 'a', repo: { root: ROOT } } as Partial<SessionRow>);
    const gone = session({ session_id: 'b', availability: 'gone', repo: { root: ROOT } } as Partial<SessionRow>);
    const other = session({ session_id: 'c', repo: { root: '/home/a/other' } } as Partial<SessionRow>);
    expect(sessionsIn([here, gone, other], ROOT).map((one) => one.session_id)).toEqual(['a']);
    expect(liveNames([here, gone, other], ROOT)).toEqual(['Write it']);
  });
});

describe('the standing prompt and rule', () => {
  const held = { projects: { [ROOT]: { system_prompt: 'Be brief.' } }, compaction: { project: [{ path: ROOT, instructions: 'Keep the plan.' }, { path: ROOT, model: 'm', instructions: 'Narrower.' }] } };

  test('reads the repo’s own rule and never a model’s narrower one', () => {
    expect(standingOf(held, ROOT)).toEqual({ prompt: 'Be brief.', promptFile: null, compaction: 'Keep the plan.', compactionFile: null });
    expect(standingOf({}, ROOT)).toEqual({ prompt: '', promptFile: null, compaction: '', compactionFile: null });
  });
  test('a save writes both texts and leaves the model’s row and the input alone', () => {
    const before = JSON.stringify(held);
    const next = withStanding(held, ROOT, { prompt: 'Be terse.', compaction: 'Keep the goal.' });
    expect(next).toEqual({ projects: { [ROOT]: { system_prompt: 'Be terse.' } }, compaction: { project: [{ path: ROOT, instructions: 'Keep the goal.' }, { path: ROOT, model: 'm', instructions: 'Narrower.' }] } });
    expect(JSON.stringify(held)).toBe(before);
  });
  test('a first save on an empty file creates the table and the rule row', () => {
    expect(withStanding({}, ROOT, { prompt: 'Hi.', compaction: 'Keep it.' })).toEqual({ projects: { [ROOT]: { system_prompt: 'Hi.' } }, compaction: { project: [{ path: ROOT, instructions: 'Keep it.' }] } });
  });
  test('clearing both texts removes what only they kept, and nothing else', () => {
    expect(withStanding({ ...held, other: 1, compaction: { project: [{ path: ROOT, instructions: 'x' }] } }, ROOT, { prompt: '', compaction: '' })).toEqual({ other: 1 });
    expect(withStanding(held, ROOT, { prompt: '', compaction: '' })).toEqual({ compaction: { project: [{ path: ROOT, model: 'm', instructions: 'Narrower.' }] } });
  });
  test('a repo’s siblings in the same tables survive an edit', () => {
    const two = { projects: { [ROOT]: { system_prompt: 'a' }, '/x': { system_prompt: 'b' } }, compaction: { project: [{ path: '/x', instructions: 'c' }] } };
    const next = withStanding(two, ROOT, { prompt: '', compaction: 'd' });
    expect(next).toEqual({ projects: { '/x': { system_prompt: 'b' } }, compaction: { project: [{ path: '/x', instructions: 'c' }, { path: ROOT, instructions: 'd' }] } });
  });
  test('a field read from a file is shown and never written beside inline text', () => {
    const filed = { projects: { [ROOT]: { system_prompt_file: '/p.md' } }, compaction: { project: [{ path: ROOT, file: '/r.md' }] } };
    expect(standingOf(filed, ROOT)).toMatchObject({ promptFile: '/p.md', compactionFile: '/r.md' });
    expect(withStanding(filed, ROOT, { prompt: 'x', compaction: 'y' })).toEqual(filed);
  });
  test('names the keys a save touches', () => {
    expect(standingKeys(ROOT)).toEqual([`projects.${ROOT}.system_prompt`, 'compaction.project[].instructions']);
  });
});
