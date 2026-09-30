// A Needs-you card rendered to markup: what it says and which act it offers, for each kind of fix.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { renderToStaticMarkup } from 'react-dom/server';
import { MemoryRouter } from 'react-router';
import { describe, expect, test } from 'vitest';
import { K } from '../src/lib/words-needs';
import { NeedCard } from '../src/pages/needs/NeedCard';
import type { Need } from '../src/types/needs';
import type { SessionRow } from '../src/types/sessions';

const need = (over: Partial<Need> = {}): Need => ({
  key: 'heads:claudex', severity: 'warn', source: 'heads', kind: K.failing, head: 'claudex', subject: 'claudex', finding: 'Not running.',
  fix: { kind: 'start', head: 'claudex' }, at: '#/fleet/claudex', ...over,
});
const render = (item: Need, row: SessionRow | null = null) =>
  renderToStaticMarkup(
    <QueryClientProvider client={new QueryClient()}>
      <MemoryRouter>
        <ul>
          <NeedCard need={item} row={row} />
        </ul>
      </MemoryRouter>
    </QueryClientProvider>,
  );

describe('a need card', () => {
  test('it is an attention window with a state word, who it is about, one sentence and one act', () => {
    const html = render(need());
    expect(html).toContain('win attn need');
    expect(html).toContain('Failing');
    expect(html).toContain('<h2>claudex</h2>');
    expect(html).toContain('Not running.');
    expect(html).toContain('>Start<');
  });
  test('a quiet link opens what the card is about, unless the act already goes there', () => {
    expect(render(need())).toContain('href="/fleet/claudex"');
    const same = render(need({ fix: { kind: 'open', href: '#/fleet/claudex', label: 'See the command' } }));
    expect(same).toContain('See the command');
    expect(same.match(/href="\/fleet\/claudex"/g)).toHaveLength(1);
    expect(render(need({ at: null }))).not.toContain('btn quiet');
  });
  test('the act of each kind of fix is named', () => {
    expect(render(need({ fix: { kind: 'restart', head: 'claudex' } }))).toContain('>Restart<');
    expect(render(need({ fix: { kind: 'restart-daemon' }, at: null }))).toContain('Restart splice');
    expect(render(need({ fix: { kind: 'login', head: 'claudex' } }))).toContain('>Sign in<');
    expect(render(need({ fix: { kind: 'login', head: 'claudex', label: 'work' } }))).toContain('Sign in again');
    expect(render(need({ fix: { kind: 'copy', command: 'splice key set OPENAI_KEY' } }))).toContain('Copy the command');
    expect(render(need({ fix: { kind: 'doctor-fix', id: 'x' } }))).toContain('>Fix it<');
  });
  test('the daemon restart is armed first: its card offers Restart splice and no Drain and restart until it is pressed', () => {
    const html = render(need({ fix: { kind: 'restart-daemon' }, at: null }));
    expect(html).toContain('Restart splice');
    expect(html).not.toContain('Drain and restart');
    expect(html).not.toContain('Turns in flight finish first');
  });
  test('a stop-turn with no live turn to name opens the session instead', () => {
    const html = render(need({ kind: K.stuck, fix: { kind: 'stop-turn', head: 'claudex', session: 's1' }, at: null }));
    expect(html).toContain('href="/sessions/s1"');
    expect(html).toContain('Open the session');
  });
  test('a waiting session quotes its question in bold, names its repo, and offers a resume copy beside the open act', () => {
    const waiting = need({
      kind: K.waiting, state: 'waiting', source: 'sessions', head: 'claudex', subject: 'implementer', finding: 'Waiting for your answer for 2 min',
      session: { id: 's1', said: 'Run npm run migrate?', repo: 'tally' }, fix: { kind: 'open', href: '#/sessions/s1', label: 'Open the session' }, at: '#/sessions/s1',
    });
    const row = { session_id: 's1', head: 'claudex' } as SessionRow;
    const html = render(waiting, row);
    expect(html).toContain('<b>Run npm run migrate?</b>');
    expect(html).toContain('It is a session in <b>tally</b>.');
    expect(html).toContain('Copy resume command');
    expect(render(waiting)).not.toContain('Copy resume command');
    expect(render(need({ kind: K.stuck, state: 'stuck', session: { id: 's1', said: null, repo: null }, at: null }), row)).not.toContain('Copy resume command');
  });
  test('a fix whose command holds a redacted value is never copyable and never printed', () => {
    const html = render(need({ fix: { kind: 'masked', command: 'splice key set <redacted:key>' }, at: null }));
    expect(html).toContain('keeps out of this page');
    expect(html).not.toContain('Copy the command');
    expect(html).not.toContain('redacted');
  });
  test('a fix that opens a page also prints the command the row carries', () => {
    const html = render(need({ fix: { kind: 'open', href: '#/fleet/x?tab=log', label: 'Open log', fallback: 'splice logs --head x' }, at: null }));
    expect(html).toContain('splice logs --head x');
  });
});
