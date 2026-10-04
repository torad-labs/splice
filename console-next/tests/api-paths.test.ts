// The api layer's URL builders: every segment the operator or the daemon chose (a head key, a session id, a turn
// id, a cursor) is encoded, and each query string is spelled exactly as the old console spelled it.
import { describe, expect, test } from 'vitest';
import { upstreamModelsPath } from '../src/api/models';
import { clampTail, logsPath } from '../src/api/logs';
import {
  capturePath,
  conversationPath,
  instructionsPath,
  perfSummaryPath,
  perfTurnsPath,
  traceTurnPath,
  tracePath,
  wirePath,
} from '../src/api/turns';
import { teamActivityPath, teamChatPath, teamEconomicsPath, teamPath, teamSessionsPath } from '../src/api/teams';
import { addPath } from '../src/api/usage';
import type { PerfWindowLabel } from '../src/types/perf';

describe('models', () => {
  test('the upstream read names a provider only when asked, encoded', () => {
    expect(upstreamModelsPath()).toBe('/api/models/upstream');
    expect(upstreamModelsPath('open router/x&y')).toBe('/api/models/upstream?provider=open%20router%2Fx%26y');
  });
});

describe('logs', () => {
  test('the head is encoded and the tail is clamped to 10..2000', () => {
    expect(logsPath('a/b c', 200)).toBe('/api/logs/a%2Fb%20c?tail=200');
    expect(logsPath('h', 5)).toBe('/api/logs/h?tail=10');
    expect(logsPath('h', 99_999)).toBe('/api/logs/h?tail=2000');
    expect(clampTail(2000)).toBe(2000);
  });
});

describe('turns', () => {
  test('the summary window is encoded', () => {
    expect(perfSummaryPath('24h')).toBe('/api/perf/summary?window=24h');
    expect(perfSummaryPath('1h&x=1' as PerfWindowLabel)).toBe('/api/perf/summary?window=1h%26x%3D1');
  });

  test('the turns read names head and n, and since only for a window; the head is encoded', () => {
    expect(perfTurnsPath('claude', 200)).toBe('/api/perf/turns?head=claude&n=200');
    expect(perfTurnsPath('a b&c', 2000, { since: 1_700_000_000_000 })).toBe('/api/perf/turns?head=a+b%26c&n=2000&since=1700000000000');
  });

  test('until and each filter ride the read only when asked, encoded; local steps are left out only on request', () => {
    const filter = { outcome: 'error:rate-limited', model: 'gpt 6', account: 'me@x.io', session: 's1', unattributed: 'model', compact: true, local: false } as const;
    expect(perfTurnsPath('h', 200, { since: 1, until: 2, filter })).toBe(
      '/api/perf/turns?head=h&n=200&since=1&until=2&outcome=error%3Arate-limited&model=gpt+6&account=me%40x.io&session=s1&unattributed=model&compact=1&local=0',
    );
    expect(perfTurnsPath('h', 200, { filter: { compact: false, local: true } })).toBe('/api/perf/turns?head=h&n=200&compact=0');
  });

  test('a head read of trace, wire and capture encodes the head', () => {
    expect(tracePath('a/b')).toBe('/api/heads/a%2Fb/trace');
    expect(wirePath('a/b')).toBe('/api/heads/a%2Fb/wire');
    expect(capturePath('a/b')).toBe('/api/heads/a%2Fb/capture');
  });

  test('a traced turn is asked by an encoded id', () => {
    expect(traceTurnPath('a/b', 't 1&2')).toBe('/api/heads/a%2Fb/trace?turn=t%201%262');
  });

  test('a conversation names the session and the response id, both encoded', () => {
    expect(conversationPath('a/b', 's&1', 'msg_01/x y')).toBe('/api/heads/a%2Fb/conversation?session=s%261&message=msg_01%2Fx+y');
  });

  test('the compaction rules are asked per head, encoded', () => {
    expect(instructionsPath('a b&c')).toBe('/api/compaction/instructions?head=a%20b%26c');
  });
});

describe('teams', () => {
  test('a team id is encoded in every team path', () => {
    expect(teamPath('t/1 x')).toBe('/api/teams/t%2F1%20x');
    expect(teamSessionsPath('t/1 x')).toBe('/api/teams/t%2F1%20x/sessions');
    expect(teamEconomicsPath('t/1 x')).toBe('/api/teams/t%2F1%20x/economics');
  });

  test('the day panels bound the local day with from and to', () => {
    const day = { from: 1_700_000_000_000, to: 1_700_086_400_000 };
    expect(teamChatPath('t/1', day)).toBe('/api/teams/t%2F1/chat?from=1700000000000&to=1700086400000');
    expect(teamActivityPath('t/1', day)).toBe('/api/teams/t%2F1/activity?from=1700000000000&to=1700086400000');
  });
});

describe('add', () => {
  test('an add id is encoded, with the step after it', () => {
    expect(addPath('a/b c')).toBe('/api/add/a%2Fb%20c');
    expect(addPath('a/b c', '/verify')).toBe('/api/add/a%2Fb%20c/verify');
  });
});
