// V4-421: the Sessions page offers Resume only where the daemon says a session can be resumed. Found by
// the wire probe on the everyday daemon: /api/sessions listed Eli's Telegram bridge (it registers in
// Claude Code's session registry and never writes a transcript) and the resume route answered 404 for it,
// so the row's Resume would have failed the same way. Rows from /api/sessions now carry `resumable`.
// What is pinned: a live row that cannot be resumed offers no Resume and says why without claiming a
// session file exists (it may not); a row that can, or one the daemon did not measure (an older daemon,
// or the transcript view off), keeps the Resume it had.
//
// A `.ts` test cannot hold JSX (TS1161), so elements are built with createElement and asserted against
// renderToStaticMarkup's string.
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, test } from 'vitest';
import type { SessionRow } from '../src/entities/session';
import { sessionKey } from '../src/entities/session';
import { SessionsBoard } from '../src/pages/sessions';

const NOW = 1_790_000_000_000;

/** The resume section's intro paragraph: rendered whenever the page offers Resume, before any head list loads. */
const RESUME = 'myx-sx-resume-intro';

function row(over: Partial<SessionRow> = {}): SessionRow {
  return {
    pid: 3105, session_id: 'a282960a-1111-4222-8333-444455556666', name: 'bridge', kind: 'interactive',
    version: 'eli-telegram/0.2.0', cwd: '/work/eli', status: 'idle', status_updated_at: NOW, started_at: NOW - 60_000,
    updated_at: NOW, address: null, head: 'unknown head', availability: 'live', ...over,
  };
}

function detail(session: SessionRow): string {
  return renderToStaticMarkup(createElement(SessionsBoard, {
    payload: { note: '', sessions: [session] }, linked: sessionKey(session),
  }));
}

describe('a listed session that cannot be resumed offers no Resume (V4-421)', () => {
  test('a live row the daemon says is not resumable says so, and offers no command', () => {
    const html = detail(row({ resumable: false }));
    expect(html).toContain('Nothing to resume');
    expect(html).not.toContain(RESUME);
  });

  test('a finished row from the registry says the same, at the place the resume section sits for it', () => {
    const html = detail(row({ resumable: false, availability: 'gone' }));
    expect(html).toContain('Nothing to resume');
    expect(html).not.toContain(RESUME);
  });

  test('the sentence does not claim a session file exists, because for a registry-only session none does', () => {
    const html = detail(row({ resumable: false }));
    expect(html).not.toContain('Empty transcript');
    expect(html).not.toContain('wrote a session file');
  });

  test('a row the daemon says is resumable keeps its Resume', () => {
    const html = detail(row({ resumable: true }));
    expect(html).toContain(RESUME);
    expect(html).not.toContain('Nothing to resume');
  });

  test('a row the daemon did not measure keeps the Resume it had, since absence claims nothing', () => {
    const html = detail(row());
    expect(html).toContain(RESUME);
    expect(html).not.toContain('Nothing to resume');
  });
});
