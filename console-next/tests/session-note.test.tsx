// V4-444: the note box reads the registry the way Sessions and Requests do. A STALE session is a pid still alive whose
// registration has not refreshed, so it is running and takes a note; only a GONE one cannot. The persona walk of
// 36218a37c found claude-builder Working on Sessions and streaming on Requests while its page said it was not running.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, test } from 'vitest';
import { P } from '../src/pages/session/copy';
import { SessionNote } from '../src/pages/session/SessionNote';
import type { SessionRow } from '../src/types/sessions';

const row = (over: Partial<SessionRow>): SessionRow => ({
  pid: 7, session_id: 'sess-1', name: 'claude-builder', kind: 'interactive', version: '2.1.288', cwd: '/repo', status: 'busy',
  status_updated_at: 0, started_at: 0, updated_at: 0, address: null, head: 'claudex', availability: 'live',
  ...over,
});

const note = (over: Partial<SessionRow>): string =>
  renderToStaticMarkup(createElement(QueryClientProvider, { client: new QueryClient() }, createElement(SessionNote, { row: row(over), versions: ['2.1.288'] })));

describe('who takes a note', () => {
  test('a live busy registration without an addressable id does not claim the process stopped', () => {
    const html = note({ pid: 1, availability: 'live', session_id: null, status: 'busy' });
    expect(html).toContain('A note cannot be sent to this session.');
    expect(html).not.toContain('This session is not running');
    expect(html).not.toContain('<textarea');
  });
  test('a live session and a stale one both offer the note box', () => {
    for (const availability of ['live', 'stale'] as const) {
      expect(note({ availability })).toContain(`aria-label="${P.noteLabel}"`);
      expect(note({ availability })).not.toContain(P.noteNotRunning);
    }
  });
  test('a gone session, or one with no id to address, says it cannot take a note', () => {
    expect(note({ availability: 'gone' })).toContain(P.noteNotRunning);
    expect(note({ session_id: null })).toContain(P.noteNotRunning);
  });
});
