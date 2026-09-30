// The parts of a session's page and of a card, rendered to markup: what each says in each of its states.
import { DndContext } from '@dnd-kit/core';
import { SortableContext } from '@dnd-kit/sortable';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { renderToStaticMarkup } from 'react-dom/server';
import { MemoryRouter } from 'react-router';
import { describe, expect, test } from 'vitest';
import type { Item } from '../src/lib/conversation';
import type { Rail as RailFacts } from '../src/lib/rail';
import type { HandedEdge, SessionRow } from '../src/types/sessions';
import { Composer } from '../src/pages/session/Composer';
import { Handoff } from '../src/pages/session/Handoff';
import { Rail } from '../src/pages/session/Rail';
import { OUTPUT_CAP, ToolBlock } from '../src/pages/session/ToolBlock';
import { SessionCard } from '../src/pages/sessions/SessionCard';
import type { CardFacts } from '../src/pages/sessions/SessionCard';

type Tool = Extract<Item, { kind: 'tool' }>;
const tool = (over: Partial<Tool> = {}): Tool => ({
  kind: 'tool', index: 1, ts: 1, tool: 'Bash', input: { command: 'npm test' }, inputText: '{"command":"npm test"}', output: '18 passed\nall green', last: false, ...over,
});

describe('a tool block', () => {
  test('a finished call names its verb, its target and the size of what came back', () => {
    const html = renderToStaticMarkup(<ToolBlock item={tool()} />);
    expect(html).toContain('Bash');
    expect(html).toContain('npm test');
    expect(html).toContain('2 lines');
    expect(html).not.toContain('res run');
  });
  test('a call with no result yet is running, never a size of zero', () => {
    const html = renderToStaticMarkup(<ToolBlock item={tool({ output: null })} />);
    expect(html).toContain('res run');
    expect(html).toContain('running');
    expect(html).not.toContain('0 lines');
  });
  test('an edit prints its removed and added lines as a diff', () => {
    const html = renderToStaticMarkup(
      <ToolBlock item={tool({ tool: 'Edit', input: { file_path: 'a.ts', old_string: 'one', new_string: 'two\nthree' }, output: 'ok' })} />,
    );
    expect(html).toContain('ln del');
    expect(html).toContain('- one');
    expect(html).toContain('+ two');
    expect(html).toContain('+ three');
    expect(html).toContain('+2 −1');
  });
  test('a result past the cap is cut and says so', () => {
    const html = renderToStaticMarkup(<ToolBlock item={tool({ output: 'Q'.repeat(OUTPUT_CAP + 500) })} />);
    expect(html).toContain('Only the start of this output is shown.');
    expect(html.match(/Q/g)?.length).toBe(OUTPUT_CAP);
  });
});

describe('a hand-off', () => {
  const edge = (over: Partial<HandedEdge> = {}): HandedEdge => ({
    from: 'a', to: 'uds:/x', at: new Date(2026, 8, 29, 15, 4).getTime(), direction: 'in', text: 'Build the limiter.', text_source: 't', missing_reason: null, ...over,
  });
  test('what was handed over shows, and says from whom', () => {
    const html = renderToStaticMarkup(<Handoff edge={edge()} peer="claude-splice" colour="claude" />);
    expect(html).toContain('Hand-off from claude-splice');
    expect(html).toContain('Build the limiter.');
    expect(html).toContain('var(--claude)');
  });
  test('one this session sent says to whom, and a text that is gone says why', () => {
    const html = renderToStaticMarkup(<Handoff edge={edge({ direction: 'out', text: null, missing_reason: 'transcript rotated' })} peer="claude-muse" colour="none" />);
    expect(html).toContain('Hand-off to claude-muse');
    expect(html).toContain('transcript rotated');
    expect(html).toContain('var(--tan)');
  });
});

describe('the composer', () => {
  test('it is disabled and says why', () => {
    const html = renderToStaticMarkup(<Composer />);
    expect(html).toMatch(/<textarea[^>]*disabled/);
    expect(html).toContain('Pending: splice cannot send to a session yet');
    expect(html).toMatch(/<button[^>]*disabled/);
  });
});

describe('the rail', () => {
  const rail = (over: Partial<RailFacts> = {}): RailFacts => ({ team: null, seats: [], rides: [], ...over });
  const seat = (key: string, over = {}) => ({ key, label: key, head: null, state: 'working' as const, here: false, role: null, lead: false, ...over });
  const render = (facts: RailFacts) =>
    renderToStaticMarkup(
      <MemoryRouter>
        <Rail rail={facts} colourOf={() => 'grok'} pathOf={(s) => `/sessions/${s.key}`} />
      </MemoryRouter>,
    );
  test('a session with no team and no hand-offs says it works alone', () => {
    expect(render(rail({ seats: [seat('me', { here: true })] }))).toContain('Working alone');
  });
  test('seats show, the current one marked, with what rode between them under the peer', () => {
    const html = render(rail({ team: 'Rate limiter', seats: [seat('lead'), seat('me', { here: true })], rides: [{ seat: 'lead', direction: 'in', at: 0, text: 'src/rateLimit.js\nmore' }] }));
    expect(html).toContain('Rate limiter');
    expect(html).toContain('seat here');
    expect(html).toContain('href="/sessions/lead"');
    expect(html).toContain('src/rateLimit.js');
    expect(html).toContain('to this session');
    expect(html).not.toContain('more');
  });
});

describe('a session card', () => {
  const row = (over: Partial<SessionRow> = {}): SessionRow => ({
    pid: 1, session_id: 'sess-1', name: 'Write the tests', kind: 'interactive', version: null, cwd: '/home/ava/work/tally', status: 'busy',
    status_updated_at: 0, started_at: 0, updated_at: 0, address: null, head: 'claude-grok', availability: 'live', ...over,
  });
  const facts = (over: Partial<CardFacts> = {}): CardFacts => ({ row: row(), state: 'working', colour: 'grok', head: 'claude-grok', hand: null, now: 42 * 60_000, ...over });
  const render = (f: CardFacts) =>
    renderToStaticMarkup(
      <QueryClientProvider client={new QueryClient()}>
        <MemoryRouter>
          <DndContext>
            <SortableContext items={['sess-1']}>
              <ul>
                <SessionCard facts={f} />
              </ul>
            </SortableContext>
          </DndContext>
        </MemoryRouter>
      </QueryClientProvider>,
    );
  test('a working card is a title, a state, one line and one quiet line, and no act', () => {
    const html = render(facts({ hand: { kind: 'from', peer: 'claude-splice' } }));
    expect(html).toContain('Write the tests');
    expect(html).toContain('Working');
    expect(html).toContain('Working for 42 min');
    expect(html).toContain('tally');
    expect(html).toContain('from claude-splice');
    expect(html).toContain('href="/sessions/sess-1"');
    expect(html).not.toContain('class="acts"');
    expect(html).toContain('win grok');
    expect(html).not.toContain('attn');
  });
  test('a card that needs a person drops its hue, opens the session and offers the resume command', () => {
    const html = render(facts({ state: 'waiting', row: row({ status: 'waiting' }) }));
    expect(html).toContain('win grok attn');
    expect(html).toContain('Open the session');
    expect(html).toContain('Copy resume command');
    expect(html).toContain('Waiting on you');
  });
  test('a session splice did not start shows no head and no invented colour', () => {
    const html = render(facts({ head: null, colour: 'none' }));
    expect(html).not.toContain('claude-grok');
    expect(html).not.toContain('Not started');
  });
});
