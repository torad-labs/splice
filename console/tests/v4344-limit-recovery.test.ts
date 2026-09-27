// V4-344: a limit notice must lead to a truthful alternative and then to a resumable session.
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, test } from 'vitest';
import { NOT_REREAD } from '../src/entities/account';
import type { AccountRow } from '../src/entities/account';
import type { UsagePayload } from '../src/shared/api';
import { AccountsBoard, openHeadKey } from '../src/pages/accounts';
import { accountKey } from '../src/widgets/account-table';
import { sessionKey } from '../src/entities/session';
import type { SessionRow } from '../src/entities/session';
import { DEFAULT_VIEWS, SessionsBoard, stateWord } from '../src/pages/sessions';
import { statOf, tableOf } from './lib/markup';
import { Conversation } from '../src/widgets/conversation';
import type { TranscriptSlice } from '../src/entities/transcript';
import { transcriptStore } from '../src/entities/transcript/model/store';

const NOW = 1_800_000_000_000;
const HOUR = 3_600;

const accounts: AccountRow[] = [
  {
    kind: 'chatgpt-oauth', label: 'spare', single_login: false, credential_path: null,
    primary: false, selected: false, available: true, pinned: false, next_target: true,
    credential_present: true, heads: ['claudex'],
    windows: [{ seconds: 5 * HOUR, used_percent: 20, reset_epoch_seconds: NOW / 1000 + 30 * 60 }],
  },
];
const usage: UsagePayload = {
  window_hours: 5, warn_pct: 80, warn_tokens_5h: 0,
  heads: [{
    key: 'claude-splice', label: 'Claude', usage: {
      output_tokens_5h: 0, entries: 0, ratelimit: null,
      warn: { level: 'ok', pct: 0, source: 'none', reset: null },
      quota: { five_hour: { used_pct: 98, resets_at: NOW / 1000 + 4 * HOUR } },
    },
  }],
};

function board(): string {
  return renderToStaticMarkup(createElement(AccountsBoard, {
    payload: { accounts }, usage, nowMs: NOW,
    headRows: [{ head: 'claude-splice', kind: 'client', present: true, masked: null, note: null }],
  }));
}

const historical: SessionRow = {
  pid: null, session_id: 'finished-id', name: 'Atlas parser', kind: null, version: null,
  cwd: '/work/atlas', repo: { root: '/work/atlas' }, status: null, status_updated_at: null,
  started_at: null, updated_at: NOW, address: null, head: 'claudex', availability: 'gone',
  source: 'history+transcript', account: 'spare',
};

const byProject = DEFAULT_VIEWS.find((entry) => entry.id === 'by-project');
if (byProject === undefined) throw new Error('Sessions is missing its project view');

describe('transcript view off', () => {
  test('a prior registry title stays hidden until the live switch answers this visit', () => {
    const html = renderToStaticMarkup(createElement(SessionsBoard, {
      payload: { note: 'Live registry', sessions: [{ ...historical, name: 'PRIVATE_TITLE', availability: 'live' }] },
      history: { sessions: [{ ...historical, name: 'PRIVATE_HISTORY' }], next: null },
      viewChecked: false, linked: sessionKey(historical), view: byProject,
      edges: {
        last: {
          key: 'finished-id', at: NOW,
          data: { session_id: 'finished-id', edges: [{
            from: 'finished-id', to: 'uds:/peer.sock', at: NOW, direction: 'out' as const,
            text: 'PRIVATE_HANDOFF', text_source: '/work/private.jsonl', missing_reason: null,
          }] },
        },
        failures: new Map(),
      },
    }));
    expect(html).not.toContain('PRIVATE_TITLE');
    expect(html).not.toContain('PRIVATE_HISTORY');
    expect(html).not.toContain('Show message');
    expect(html).toContain('finished-id');
  });

  test('sessions hide a registry title while keeping its id and repository when view is off', () => {
    const html = renderToStaticMarkup(createElement(SessionsBoard, {
      payload: { note: 'Live registry', sessions: [{ ...historical, name: 'PRIVATE_TITLE', availability: 'live' }] },
      historyOff: 'Transcript view is off. Turn it on in Request detail.',
      linked: sessionKey(historical), view: byProject,
    }));
    expect(html).not.toContain('PRIVATE_TITLE');
    expect(html).toContain('finished-id');
    expect(html).toContain('Request detail');
  });

  test('cached hand-offs expose no message controls after the view turns off', () => {
    const html = renderToStaticMarkup(createElement(SessionsBoard, {
      payload: { note: 'Live registry', sessions: [{ ...historical, availability: 'live' }] },
      linked: sessionKey(historical), historyOff: 'Transcript view is off. Turn it on in Request detail.',
      edges: {
        last: {
          key: 'finished-id', at: NOW,
          data: { session_id: 'finished-id', edges: [{
            from: 'finished-id', to: 'uds:/peer.sock', at: NOW, direction: 'out' as const,
            text: 'PRIVATE_HANDOFF', text_source: '/work/private.jsonl', missing_reason: null,
          }] },
        },
        failures: new Map(),
      },
      view: byProject,
    }));
    expect(html).toContain('View off');
    expect(html).not.toContain('Show message');
    expect(html).not.toContain('PRIVATE_HANDOFF');
  });

  test('cached conversation text waits for a fresh switch verdict when a page remounts', () => {
    transcriptStore.land('cached-off', {
      sessionId: 'cached-off', path: '/work/private.jsonl',
      messages: [{ index: 0, role: 'user', text: 'PRIVATE_MARKER' }],
      cursor: { sessionId: 'cached-off', next: null, pages: 1, complete: true },
    });
    const html = renderToStaticMarkup(createElement(Conversation, { sessionId: 'cached-off' }));
    expect(html).not.toContain('PRIVATE_MARKER');
    expect(html).not.toContain('/work/private.jsonl');
  });

  test('the conversation names the switch rather than showing an empty page or crashing', () => {
    const off: TranscriptSlice = { state: 'off', reason: 'Transcript view is off. Turn it on in Request detail.' };
    const html = renderToStaticMarkup(createElement(Conversation, { sessionId: 'finished-id', slice: off }));
    expect(html).toContain('Transcript view is off');
    expect(html).toContain('Request detail');
    expect(html).not.toContain('No transcript');
  });
});

describe('limit recovery on Sessions', () => {
  test('live registry rows distinguish working from waiting without guessing when status is absent', () => {
    const html = renderToStaticMarkup(createElement(SessionsBoard, {
      payload: { note: 'Live registry', sessions: [
        { ...historical, pid: 7, session_id: 'working-id', name: 'worker', status: 'busy', availability: 'live' },
        { ...historical, pid: 8, session_id: 'waiting-id', name: 'waiter', status: 'idle', availability: 'live' },
      ] },
      view: byProject,
    }));
    const rows = tableOf(html, 'Sessions').cells;
    expect(rows.find((row) => row.includes('worker'))).toContain('Working');
    expect(rows.find((row) => row.includes('waiter'))).toContain('Waiting');
    expect(stateWord({ ...historical, status: null, availability: 'live' })).toBe('Live');
    expect(stateWord({ ...historical, status: 'idle', availability: 'stale' })).toBe('Stale');
    expect(stateWord(historical)).toBe('Gone');
  });

  test('finds a finished session by name and repository when the live registry is empty', () => {
    const html = renderToStaticMarkup(createElement(SessionsBoard, {
      payload: { note: 'Live registry', sessions: [] },
      history: { sessions: [historical], next: 'next-page' },
      historyQuery: 'Atlas',
      view: byProject,
    }));
    expect(html).toContain('Atlas parser');
    expect(html).toContain('atlas');
    expect(html).not.toContain('No sessions');
    expect(html).toContain('More sessions');
  });

  test('an unreadable registry is not presented as an empty machine', () => {
    const html = renderToStaticMarkup(createElement(SessionsBoard, {
      payload: { note: 'Live registry', error: 'registrations unreadable', sessions: [] },
      history: { sessions: [], next: null }, view: byProject,
    }));
    expect(html).toContain('registrations unreadable');
    expect(html).not.toContain('No sessions');
  });

  test('an empty transcript stays in history but offers no unusable resume command', () => {
    const empty = { ...historical, resumable: false };
    const html = renderToStaticMarkup(createElement(SessionsBoard, {
      payload: { note: 'Live registry', sessions: [] }, history: { sessions: [empty], next: null },
      linked: sessionKey(empty), view: byProject,
    }));
    expect(html).toContain('Atlas parser');
    expect(html).toContain('Empty transcript');
    expect(html).not.toContain('Resume on');
  });

  test('a history-only row names its account but does not offer a command without a transcript', () => {
    const missing = { ...historical, source: 'history-only' as const };
    const html = renderToStaticMarkup(createElement(SessionsBoard, {
      payload: { note: 'Live registry', sessions: [] }, history: { sessions: [missing], next: null },
      linked: sessionKey(missing), view: byProject,
    }));
    expect(html).toContain('spare');
    expect(html).toContain('No transcript');
    expect(html).not.toContain('Resume on');
  });

  test('shows a session once when registry and durable history both know it', () => {
    const html = renderToStaticMarkup(createElement(SessionsBoard, {
      payload: { note: 'Live registry', sessions: [{ ...historical, pid: 42, name: null, account: null, availability: 'live' }] },
      history: { sessions: [historical], next: null },
      linked: sessionKey(historical), view: byProject,
    }));
    expect(tableOf(html, 'Sessions').cells).toHaveLength(1);
    expect(html).toContain('Atlas parser');
    expect(html).toContain('spare');
  });
});

describe('limit recovery on Accounts', () => {
  test('the reset beside the nearest limit belongs to that limit, not a different plan', () => {
    const html = board();
    expect(statOf(html, 'Nearest limit')?.value).toBe('98%');
    expect(statOf(html, 'Limit resets')?.value).toBe('in 4h 0m');
  });

  test('an unmeasured Grok credit window explains its unknown state without treating it as empty', () => {
    const grok: AccountRow = {
      ...accounts[0] as AccountRow,
      kind: 'grok-oauth', label: 'grok', heads: ['claude-grok'], windows: [],
    };
    const html = renderToStaticMarkup(createElement(AccountsBoard, { payload: { accounts: [grok] }, nowMs: NOW }));
    expect(html).toContain('Grok billing has not returned a current credit reading.');
    expect(tableOf(html, 'Accounts').cells[0]?.slice(3, 5)).toEqual(['–', '–']);
  });

  test('a Claude window that reset is not presented as fresh quota', () => {
    const stale: UsagePayload = {
      ...usage,
      heads: usage.heads.map((entry) => ({
        ...entry,
        usage: entry.usage === null ? null : {
          ...entry.usage,
          quota: { five_hour: { used_pct: 99, resets_at: NOW / 1000 - 1 } },
        },
      })),
    };
    const html = renderToStaticMarkup(createElement(AccountsBoard, {
      payload: { accounts }, usage: stale, nowMs: NOW, linked: openHeadKey('claude-splice'),
      headRows: [{ head: 'claude-splice', kind: 'client', present: true, masked: null, note: null }],
    }));
    const claude = tableOf(html, 'Claude logins');
    expect(claude.cells[0]).toContain(NOT_REREAD);
    expect(claude.rows[0]).not.toContain('aria-valuenow="99"');
    expect(html).not.toContain('href="#/sessions?head=claude-splice"');
  });

  test('opening a plan with room carries its head into the session search', () => {
    const spare = accounts[0];
    if (spare === undefined) throw new Error('missing spare login');
    const html = renderToStaticMarkup(createElement(AccountsBoard, {
      payload: { accounts }, linked: accountKey(spare), nowMs: NOW,
    }));
    expect(html).toContain('href="#/sessions?head=claudex"');
  });

  test('a Claude plan with a current reading carries its head into session search', () => {
    const html = renderToStaticMarkup(createElement(AccountsBoard, {
      payload: { accounts }, usage, nowMs: NOW, linked: openHeadKey('claude-splice'),
      headRows: [{ head: 'claude-splice', kind: 'client', present: true, masked: null, note: null }],
    }));
    expect(html).toContain('href="#/sessions?head=claude-splice"');
  });

  test('Claude and the available plan have comparable readings and a path to sessions', () => {
    const html = board();
    const claude = tableOf(html, 'Claude logins');
    expect(claude.names).toContain('5h');
    expect(claude.cells[0]).toContain('98%in 4h 0m');
    const pooled = tableOf(html, 'Accounts');
    expect(pooled.cells[0]).toContain('20%30m 0s');
    expect(html).toContain('href="#/sessions"');
  });
});
