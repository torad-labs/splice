import { describe, expect, test } from 'vitest';
import type { SessionRow } from '../src/types/sessions';
import type { LiveTurn } from '../src/types/turns';
import { cardSays } from '../src/lib/session-says';
import { activityText, cardLine, groupSessions, timingOf, handoffOf, matchesQuery, noConversation, sessionsLede, peerLabel, repoName, sessionKey, sessionLabel, sinceOf, spanText, stateOf, stateTone, stateWord, STUCK_IDLE_MS } from '../src/lib/sessions';

const NOW = 1_790_000_000_000;
const row = (over: Partial<SessionRow> = {}): SessionRow => ({
  pid: 1, session_id: 'aaaaaaaa-1111', name: 'Write the tests', kind: 'interactive', version: '2.0', cwd: '/home/ava/code/tally',
  status: 'busy', status_updated_at: NOW - 60_000, started_at: NOW - 3_600_000, updated_at: NOW - 60_000, address: 'uds:/run/a.sock',
  head: 'claude-splice', availability: 'live', ...over,
});

describe('a session\'s state', () => {
  const turn = (over: Partial<LiveTurn> = {}): LiveTurn => ({ id: 't1', session: 'aaaaaaaa-1111', model: 'm', compact: false, age_ms: 60_000, stopped: false, ...over });
  test('waiting is the client\'s own word', () => expect(stateOf(row({ status: 'waiting' }))).toBe('waiting'));
  test('busy is working, however old its status stamp: the stamp only moves when the client changes status', () => {
    expect(stateOf(row())).toBe('working');
    expect(stateOf(row({ status_updated_at: NOW - 3 * 3_600_000, availability: 'stale' }))).toBe('working');
    expect(stateOf(row({ status: 'shell' }))).toBe('working');
  });
  test('a busy session with a live turn that streamed a moment ago is working', () =>
    expect(stateOf(row(), turn({ idle_ms: 4_000 }))).toBe('working'));
  test('a live turn quiet past the window is stuck, exactly at it is not, and one that streamed then hung counts', () => {
    expect(stateOf(row(), turn({ idle_ms: STUCK_IDLE_MS + 1 }))).toBe('stuck');
    expect(stateOf(row(), turn({ idle_ms: STUCK_IDLE_MS }))).toBe('working');
    expect(stateOf(row(), turn({ age_ms: 40 * 60_000, idle_ms: 6 * 60_000 }))).toBe('stuck');
  });
  test('a daemon that sends no idle figure, no live turn, or a turn already stopping never makes a session stuck', () => {
    expect(stateOf(row(), turn())).toBe('working');
    expect(stateOf(row(), null)).toBe('working');
    expect(stateOf(row(), turn({ idle_ms: STUCK_IDLE_MS * 10, stopped: true }))).toBe('working');
  });
  test('a quiet live turn on an idle session says nothing: idle is idle, stale or not', () => {
    expect(stateOf(row({ status: 'idle', availability: 'stale' }), turn({ idle_ms: STUCK_IDLE_MS * 2 }))).toBe('idle');
    expect(stateOf(row({ availability: 'stale', status: 'waiting' }))).toBe('waiting');
  });
  test('a gone registration is gone, whatever the status', () =>
    expect(stateOf(row({ availability: 'gone', status: 'waiting' }))).toBe('gone'));
  test('idle is idle, and an unknown word is idle', () => {
    expect(stateOf(row({ status: 'idle' }))).toBe('idle');
    expect(stateOf(row({ status: null }))).toBe('idle');
  });
  test('this machine\'s shape, measured 2026-09-29: six busy seats and idle, stale ones, none stuck and nothing needs a person', () => {
    const seats = [
      ...['busy', 'busy', 'busy', 'busy', 'busy', 'shell'].map((status, i) => row({ session_id: `b${i}`, status, availability: i === 0 ? 'live' : 'stale', status_updated_at: NOW - (i + 1) * 3_600_000 })),
      ...[0, 1, 2, 3].map((i) => row({ session_id: `i${i}`, status: 'idle', availability: 'stale', status_updated_at: NOW - 40 * 60_000 })),
    ];
    expect(seats.map((seat) => stateOf(seat))).not.toContain('stuck');
    const keys = groupSessions(seats, 'state').map((group) => group.key);
    expect(keys).not.toContain('needs');
    expect(sessionsLede(seats)).toBe('Six are working. Four finished earlier.');
  });
});

describe('what a session is called', () => {
  test('its name, else its repo, else its pid', () => {
    expect(sessionLabel(row())).toBe('Write the tests');
    expect(sessionLabel(row({ name: '', started_at: null }))).toBe('tally');
    expect(sessionLabel(row({ name: null, session_id: null, pid: 42, cwd: null }))).toBe('pid 42');
  });
  test('an unnamed session is its repo and the day it began, never a hex id', () => {
    const began = new Date(2026, 8, 29, 9).getTime();
    expect(sessionLabel(row({ name: null, started_at: began }))).toBe('tally · Sep 29');
    expect(sessionLabel(row({ name: '', started_at: null }))).toBe('tally');
    expect(sessionLabel(row({ name: null, cwd: null, session_id: 'abcdef12-0000', pid: null }))).toBe('a session');
  });
  test('a hand-off from an unnamed session says which repo it sat in', () => {
    const sender = row({ name: null, session_id: 'ffff0000-1', started_at: null });
    const edge = { from: 'ffff0000-1', to: 'uds:/run/x.sock', at: 1, direction: 'in' } as const;
    expect(peerLabel([sender], edge)).toBe('a session in tally');
    expect(peerLabel([], edge)).toBe('an ended session');
    expect(peerLabel([row({ name: 'Builder', session_id: 'ffff0000-1' })], edge)).toBe('Builder');
    expect(peerLabel([], { ...edge, direction: 'out' })).toBe('a session');
  });
  test('a registration with no id still has a key that is not "nothing"', () =>
    expect(sessionKey(row({ session_id: null, pid: 7 }))).toBe('pid:7'));
  test('a repository reads as its folder, never a path', () => {
    expect(repoName(row())).toBe('tally');
    expect(repoName(row({ repo: { root: '/home/ava/code/ledger-api/' } }))).toBe('ledger-api');
    expect(repoName(row({ cwd: null }))).toBeNull();
    expect(repoName(row({ repo: { root: '/home/ava/code/mythos/repo', remote: 'git@github.com:torad-labs/splice.git' } }))).toBe('splice');
    expect(repoName(row({ repo: { root: '/home/ava/code/mythos/repo' } }))).toBe('mythos/repo');
  });
});

describe('grouping', () => {
  const rows = [row({ status: 'waiting', session_id: 'w' }), row({ session_id: 'b1' }), row({ session_id: 'b2' }), row({ status: 'idle', session_id: 'i' })];
  test('by state keeps its fixed order and puts both person-needing states in one group', () => {
    const stuck = row({ session_id: 's' });
    const quiet = (r: SessionRow): LiveTurn | null => (r.session_id === 's' ? { id: 't', session: 's', model: 'm', compact: false, age_ms: 9e5, idle_ms: STUCK_IDLE_MS + 5, stopped: false } : null);
    const groups = groupSessions([...rows, stuck], 'state', quiet);
    expect(groups.map((group) => [group.key, group.sessions.length])).toEqual([['needs', 2], ['working', 2], ['idle', 1]]);
  });
  test('by repo, biggest first, an unplaced row is unattributed and not dropped', () => {
    const groups = groupSessions([row(), row(), row({ cwd: '/x/other' }), row({ cwd: null })], 'repo');
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
    expect(peerLabel([], { from: 'me', to: 'uds:/gone', at: 1, direction: 'out' })).toBe('a session');
    expect(peerLabel([], { from: 'me', to: 'Builder', at: 1, direction: 'out' })).toBe('Builder');
    expect(peerLabel([], { from: 'zzzzzzzz-9', to: 'x', at: 1, direction: 'in' })).toBe('an ended session');
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
  test('work sent to one peer reads as sent to that peer, never as received from it', () =>
    expect(handoffOf([peer], [out('uds:/p1', 1), out('uds:/p1', 2)])).toEqual({ kind: 'to', peer: 'claude-splice' }));
});

describe('time in a state', () => {
  test('every state counts from the last status change, working from when its turn began', () => {
    expect(sinceOf(row(), NOW)).toBe(60_000);
    expect(sinceOf(row({ status: 'waiting' }), NOW)).toBe(60_000);
  });
  test('no time in the registry is null, never zero', () =>
    expect(sinceOf(row({ started_at: null, status_updated_at: null, updated_at: null }), NOW)).toBeNull());
});

describe('a session with no conversation', () => {
  test('says why: no file, nothing to resume, or an empty file', () => {
    expect(noConversation(row({ source: 'history-only' }))).toBe('no-transcript');
    expect(noConversation(row({ resumable: false }))).toBe('nothing-to-resume');
    expect(noConversation(row({ resumable: false, source: 'history+transcript' }))).toBe('empty-transcript');
    expect(noConversation(row())).toBeNull();
  });
});

describe('what a card says', () => {
  test('a span reads in the largest unit that stays small', () => {
    expect([0, 90_000, 59 * 60_000, 3 * 3_600_000, 47 * 3_600_000, 50 * 3_600_000].map(spanText)).toEqual(['under a minute', '1 min', '59 min', '3 h', '47 h', '2 d']);
  });

  test('every state has a word, a tone and an activity line that claims only what is known', () => {
    for (const state of ['working', 'waiting', 'stuck', 'idle', 'gone'] as const) {
      expect(stateWord(state)).not.toBe('');
      expect(activityText(state, null)).not.toMatch(/\bfor \d/);
    }
    expect(activityText('waiting', 2 * 60_000)).toBe('Waiting for your answer for 2 min');
    expect(activityText('stuck', 14 * 60_000)).toBe('Quiet for 14 min');
    expect(stateTone('waiting')).toBe('wait');
    expect(stateTone('gone')).toBe('idle');
  });

  test('a card says what the session last said or did; harness text that rides in a message is dropped', () => {
    const last = (role: 'user' | 'assistant', text: string, tool: string | null = null) => ({ role, tool, text, ts: NOW });
    const says = cardSays;
    // The daemon sends a call as what it was for: a description, else the name of what it touched, else nothing.
    expect(says(last('assistant', 'List the design comps', 'Bash'))).toBe('Bash · List the design comps');
    expect(says(last('assistant', '', 'Bash'))).toBe('Bash');
    expect(says(last('assistant', 'sessions.ts', 'Edit'))).toBe('Edit · sessions.ts');
    expect(says(last('assistant', 'TurnWiring($$$ARGS)', 'mcp__ast-grep__find_code'))).toBe('ast-grep · find code · TurnWiring($$$ARGS)');
    expect(says(last('assistant', 'Which plan should take the session?', 'AskUserQuestion'))).toBe('Which plan should take the session?');
    expect(says(last('assistant', '', 'AskUserQuestion'))).toBe('AskUserQuestion');
    expect(says(last('user', '<local-command-stdout>\u001b[2mCompacted \u001b[22m</local-command-stdout>'))).toBe('Compacted its context');
    expect(says(last('user', '<local-command-stdout>Set effort to high</local-command-stdout>'))).toBeNull();
    expect(says(last('user', '<command-name>/clear</command-name>\n<command-message>clear</command-message>\n<command-args></command-args>'))).toBeNull();
    expect(says(last('user', '<task-notification><summary>new commits on feat/v0.4.0</summary></task-notification>'))).toBeNull();
    expect(says(last('user', '<system-reminder>A process claiming the address uds:/run/user/1000/cc-socks/1.sock asks to be told when you are idle.</system-reminder>'))).toBeNull();
    expect(says(last('assistant', 'The new console is now what splice serves. CI passed on the exact sha, and I am holding the review.'))).toBe('The new console is now what splice serves.');
    expect(says(last('user', 'Fix the census. Then push it.'))).toBe('You: Fix the census.');
    expect(says(last('assistant', 'claude-console committed the fix (`9cf12062b`) and **pushed** it. More follows.'))).toBe('claude-console committed the fix (9cf12062b) and pushed it.');
    expect(says(last('assistant', 'Keeps claude_code and snake_case words whole.'))).toBe('Keeps claude_code and snake_case words whole.');
    const cut = 'I picked an offer whose download rate was $0.051/GB and the earlier boxes were paid for by the run that finished, so the credit went on this one and the next one too and now';
    expect(says(last('assistant', cut))).toBe(`${cut.slice(0, cut.lastIndexOf(' '))}…`);
    expect(cardSays(null)).toBeNull();
    expect(cardSays(undefined)).toBeNull();
  });

  test('a card line is the say or do, else the state\'s own sentence, and a waiting card shows only the question it asked', () => {
    const withLast = (role: 'user' | 'assistant', text: string, tool: string | null = null) => row({ last: { role, tool, text, ts: NOW } });
    expect(cardLine(withLast('assistant', 'Done. It is green.'), 'working', 3 * 60_000, null)).toEqual({ line: 'Done.', note: 'Working 3 min' });
    expect(cardLine(withLast('assistant', 'Done.'), 'idle', null, null)).toEqual({ line: 'Done.', note: null });
    expect(cardLine(withLast('assistant', 'Run the release build', 'Bash'), 'working', 3 * 60_000, null)).toEqual({ line: 'Bash · Run the release build', note: 'Working 3 min' });
    expect(cardLine(withLast('assistant', 'I built it. Should I push it or hold for review?'), 'waiting', 4 * 3_600_000, null)).toEqual({ line: 'Should I push it or hold for review?', note: 'Waiting 4 h' });
    expect(cardLine(withLast('assistant', 'The box run failed and used up the credit, my mistake.'), 'waiting', 8 * 3_600_000, null).line).toBe('Waiting for your answer for 8 h');
    expect(cardLine(withLast('user', '<system-reminder>A process claiming the address uds:/run/user/1000/cc-socks/1.sock</system-reminder>'), 'waiting', 4 * 3_600_000, null).line).toBe('Waiting for your answer for 4 h');
    expect(cardLine(withLast('assistant', 'Which plan should take the session?', 'AskUserQuestion'), 'waiting', null, null).line).toBe('Which plan should take the session?');
  });

  test('with no newest message the card says what the state means, and the note is empty', () => {
    for (const last of [undefined, null, { role: 'assistant' as const, tool: null, text: '  ', ts: null }]) {
      expect(cardSays(last)).toBeNull();
      expect(cardLine(row(last === undefined ? {} : { last }), 'idle', null, null)).toEqual({ line: 'Waiting for your next message', note: null });
    }
  });

  test('the search matches a name, a repository, a head or a team, and an empty search matches all', () => {
    expect(matchesQuery(row(), '')).toBe(true);
    expect(matchesQuery(row(), '  TESTS ')).toBe(true);
    expect(matchesQuery(row(), 'tally')).toBe(true);
    expect(matchesQuery(row(), 'splice')).toBe(true);
    expect(matchesQuery(row({ team: 'billing' }), 'billing')).toBe(true);
    expect(matchesQuery(row(), 'ledger')).toBe(false);
  });
});

describe('the lede', () => {
  test('it counts what works, waits, is stuck and finished, in words', () => {
    const rows = [
      row(), row({ session_id: 'b' }), row({ session_id: 'c' }),
      row({ session_id: 'd', status: 'waiting' }),
      row({ session_id: 'e' }),
      row({ session_id: 'f', status: 'idle' }), row({ session_id: 'g', status: 'idle' }),
    ];
    const quiet = (r: SessionRow): LiveTurn | null => (r.session_id === 'e' ? { id: 't', session: 'e', model: 'm', compact: false, age_ms: 9e5, idle_ms: STUCK_IDLE_MS + 1, stopped: false } : null);
    expect(sessionsLede(rows, quiet)).toBe('Three are working, one is waiting on you, one is stuck. Two finished earlier.');
  });
  test('an empty registry has no sentence to print', () => {
    expect(sessionsLede([])).toBe('');
  });
  test('past twelve the count is a digit', () => {
    expect(sessionsLede(Array.from({ length: 14 }, (_, i) => row({ session_id: String(i) })))).toBe('14 are working.');
  });
});

describe('the two durations of a card', () => {
  const turn = (over: Partial<LiveTurn> = {}): LiveTurn => ({ id: 't', session: 's', model: 'm', compact: false, age_ms: 1, stopped: false, ...over });
  test('a busy session the head says runs no turn is running a tool, quiet since its last registry change', () => {
    const t = timingOf(row(), 'working', null, NOW);
    expect(t.quiet).toBe(60_000);
    expect(activityText('working', t.since, 3 * 60_000)).toBe('Running a tool, quiet for 3 min');
    expect(activityText('working', 60_000, 60_000)).toBe('Working for 1 min');
  });
  test('a live turn, or a head not read yet, is never called quiet', () => {
    expect(timingOf(row(), 'working', turn({ idle_ms: 1_000 }), NOW).quiet).toBeNull();
    expect(timingOf(row(), 'working', undefined, NOW).quiet).toBeNull();
  });
  test('a stuck session counts the live turn\'s own silence', () => {
    expect(timingOf(row(), 'stuck', turn({ idle_ms: 7 * 60_000 }), NOW).since).toBe(7 * 60_000);
  });
});
