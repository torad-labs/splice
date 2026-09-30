import { describe, expect, test } from 'vitest';
import type { SessionRow } from '../src/types/sessions';
import { groupSessions, handoffOf, noConversation, peerLabel, sinceOf, repoName, sessionKey, sessionLabel, stateOf, STUCK_AFTER_MS } from '../src/lib/sessions';

const NOW = 1_790_000_000_000;
const row = (over: Partial<SessionRow> = {}): SessionRow => ({
  pid: 1, session_id: 'aaaaaaaa-1111', name: 'Write the tests', kind: 'interactive', version: '2.0', cwd: '/home/ava/code/tally',
  status: 'busy', status_updated_at: NOW - 60_000, started_at: NOW - 3_600_000, updated_at: NOW - 60_000, address: 'uds:/run/a.sock',
  head: 'claude-splice', availability: 'live', ...over,
});

describe('a session\'s state', () => {
  test('waiting is the client\'s own word', () => expect(stateOf(row({ status: 'waiting' }), NOW)).toBe('waiting'));
  test('busy and recent is working', () => expect(stateOf(row(), NOW)).toBe('working'));
  test('busy past the stuck window is stuck', () =>
    expect(stateOf(row({ status_updated_at: NOW - STUCK_AFTER_MS - 1 }), NOW)).toBe('stuck'));
  test('exactly at the window is still working', () =>
    expect(stateOf(row({ status_updated_at: NOW - STUCK_AFTER_MS }), NOW)).toBe('working'));
  test('the daemon\'s stale availability is stuck, whatever the status', () =>
    expect(stateOf(row({ availability: 'stale', status: 'idle' }), NOW)).toBe('stuck'));
  test('a gone registration is gone, whatever the status', () =>
    expect(stateOf(row({ availability: 'gone', status: 'waiting' }), NOW)).toBe('gone'));
  test('idle is idle, and an unknown word is idle', () => {
    expect(stateOf(row({ status: 'idle' }), NOW)).toBe('idle');
    expect(stateOf(row({ status: null }), NOW)).toBe('idle');
  });
});

describe('what a session is called', () => {
  test('its name, else 8 of its id, else its pid', () => {
    expect(sessionLabel(row())).toBe('Write the tests');
    expect(sessionLabel(row({ name: '' }))).toBe('aaaaaaaa');
    expect(sessionLabel(row({ name: null, session_id: null, pid: 42 }))).toBe('pid 42');
  });
  test('a registration with no id still has a key that is not "nothing"', () =>
    expect(sessionKey(row({ session_id: null, pid: 7 }))).toBe('pid:7'));
  test('a repository reads as its folder, never a path', () => {
    expect(repoName(row())).toBe('tally');
    expect(repoName(row({ repo: { root: '/home/ava/code/ledger-api/' } }))).toBe('ledger-api');
    expect(repoName(row({ cwd: null }))).toBeNull();
  });
});

describe('grouping', () => {
  const rows = [row({ status: 'waiting', session_id: 'w' }), row({ session_id: 'b1' }), row({ session_id: 'b2' }), row({ status: 'idle', session_id: 'i' })];
  test('by state keeps its fixed order and puts both person-needing states in one group', () => {
    const stuck = row({ session_id: 's', status_updated_at: NOW - STUCK_AFTER_MS - 5 });
    const groups = groupSessions([...rows, stuck], 'state', NOW);
    expect(groups.map((group) => [group.key, group.sessions.length])).toEqual([['needs', 2], ['working', 2], ['idle', 1]]);
  });
  test('by repo, biggest first, an unplaced row is unattributed and not dropped', () => {
    const groups = groupSessions([row(), row(), row({ cwd: '/x/other' }), row({ cwd: null })], 'repo', NOW);
    expect(groups.map((group) => [group.key, group.sessions.length])).toEqual([['tally', 2], ['other', 1], ['unattributed', 1]]);
  });
});

describe('the other end of a hand-off', () => {
  const peer = row({ session_id: 'peer-1234', name: 'claude-splice', address: 'uds:/run/peer.sock' });
  test('a sent edge resolves its address; a received one resolves its sender id', () => {
    expect(peerLabel([peer], { from: 'me', to: 'uds:/run/peer.sock', at: 1, direction: 'out' })).toBe('claude-splice');
    expect(peerLabel([peer], { from: 'peer-1234', to: 'uds:/run/me.sock', at: 1, direction: 'in' })).toBe('claude-splice');
  });
  test('a peer the registry no longer holds prints what the edge carries', () => {
    expect(peerLabel([], { from: 'me', to: 'uds:/gone', at: 1, direction: 'out' })).toBe('uds:/gone');
    expect(peerLabel([], { from: 'zzzzzzzz-9', to: 'x', at: 1, direction: 'in' })).toBe('zzzzzzzz');
  });
});

describe('hand-offs', () => {
  const peer = row({ session_id: 'p1', name: 'claude-splice', address: 'uds:/p1' });
  const out = (to: string, at: number) => ({ from: 'aaaaaaaa-1111', to, at, direction: 'out' as const });
  test('no edges, no hand-off', () => expect(handoffOf([peer], [])).toBeNull());
  test('work sent to two or more distinct peers is a lead of that many', () =>
    expect(handoffOf([peer], [out('uds:/p1', 1), out('uds:/p2', 2), out('uds:/p2', 3)])).toEqual({ kind: 'lead', peers: 2 }));
  test('the newest edge in makes it a session handed work by that peer', () =>
    expect(handoffOf([peer], [out('uds:/p1', 1), { from: 'p1', to: 'uds:/me', at: 5, direction: 'in' }])).toEqual({ kind: 'from', peer: 'claude-splice' }));
});

describe('time in a state', () => {
  test('working counts from the start, waiting from the last status change', () => {
    expect(sinceOf(row(), 'working', NOW)).toBe(3_600_000);
    expect(sinceOf(row({ status: 'waiting' }), 'waiting', NOW)).toBe(60_000);
  });
  test('no time in the registry is null, never zero', () =>
    expect(sinceOf(row({ started_at: null, status_updated_at: null, updated_at: null }), 'working', NOW)).toBeNull());
});

describe('a session with no conversation', () => {
  test('says why: no file, nothing to resume, or an empty file', () => {
    expect(noConversation(row({ source: 'history-only' }))).toBe('no-transcript');
    expect(noConversation(row({ resumable: false }))).toBe('nothing-to-resume');
    expect(noConversation(row({ resumable: false, source: 'history+transcript' }))).toBe('empty-transcript');
    expect(noConversation(row())).toBeNull();
  });
});
