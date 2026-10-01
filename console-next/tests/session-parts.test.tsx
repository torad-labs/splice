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
import { UNKNOWN_HEAD } from '../src/types/sessions';
import { Composer, NoteClosed, NoteRefused } from '../src/pages/session/Composer';
import type { ComposerProps } from '../src/pages/session/Composer';
import { NOTE_LIMIT } from '../src/lib/note';
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
  test('a Bash call reads as its description, with the command in the opened body', () => {
    const html = renderToStaticMarkup(<ToolBlock item={tool({ input: { command: 'git log --oneline -5', description: 'Show the last five commits' } })} />);
    expect(html).toContain('<span class="arg">Show the last five commits</span>');
    expect(html).toContain('<pre class="code">git log --oneline -5</pre>');
  });
  test('an MCP tool reads as its server and its tool', () => {
    const html = renderToStaticMarkup(<ToolBlock item={tool({ tool: 'mcp__ast-grep__find_code_by_rule', input: {}, inputText: '{}' })} />);
    expect(html).toContain('<span class="verb">ast-grep · find code by rule</span>');
    expect(html).not.toContain('mcp__');
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
  test('a hand-off wrapped in a system tag reads as its event, never the tag', () => {
    const html = renderToStaticMarkup(<Handoff edge={edge({ text: '<task-notification><summary>new commits on feat/v0.4.0</summary></task-notification>' })} peer="a session in splice" colour="none" />);
    expect(html).toContain('Background task · new commits on feat/v0.4.0');
    expect(html).not.toContain('task-notification');
  });
  test('one this session sent says to whom, and a text that is gone says why', () => {
    const html = renderToStaticMarkup(<Handoff edge={edge({ direction: 'out', text: null, missing_reason: 'transcript rotated' })} peer="claude-muse" colour="none" />);
    expect(html).toContain('Hand-off to claude-muse');
    expect(html).toContain('transcript rotated');
    expect(html).toContain('var(--tan)');
  });
});

describe('the note box', () => {
  const box = (over: Partial<ComposerProps> = {}) =>
    renderToStaticMarkup(<Composer draft="" phase="idle" failure={null} onDraft={() => undefined} onSend={() => undefined} {...over} />);
  test('an empty box says what a note is, and cannot send', () => {
    const html = box();
    expect(html).toContain('aria-label="Send a note to this session"');
    expect(html).toContain('It reaches the session as a message from another session, not as you typing.');
    expect(html).toMatch(/<button[^>]*disabled[^>]*>Send the note/);
    expect(html).not.toContain('Pending');
  });
  test('a draft can be sent, and sending holds the button and says so', () => {
    expect(box({ draft: 'run the gate' })).toMatch(/<button(?![^>]*disabled)[^>]*>Send the note/);
    const sending = box({ draft: 'run the gate', phase: 'sending' });
    expect(sending).toMatch(/<button[^>]*disabled[^>]*>Sending…/);
  });
  test('a sent note says it was sent and never that it was read', () => {
    const html = box({ phase: 'sent' });
    expect(html).toContain('Sent. splice cannot tell whether the session has read it yet.');
    expect(html).not.toMatch(/delivered|read it\.|received/i);
  });
  test('a refused note shows the daemon’s sentence and keeps the draft', () => {
    const html = box({ draft: 'keep me', phase: 'failed', failure: 'the session is not running' });
    expect(html).toContain('the session is not running');
    expect(html).toContain('keep me');
    expect(html).toContain('status bad');
  });
  test('a note past the limit cannot be sent, and says the limit', () => {
    const html = box({ draft: 'x'.repeat(NOTE_LIMIT + 1) });
    expect(html).toContain('A note is at most 8,000 characters.');
    expect(html).toMatch(/<button[^>]*disabled/);
  });
  test('a session on a version notes do not reach has no box, and says why and what to do', () => {
    const html = renderToStaticMarkup(<NoteRefused text="This session runs Claude Code 2.1.280, and notes reach only 2.1.286. Relaunch it on Claude Code 2.1.286." />);
    expect(html).toContain('Relaunch it on Claude Code 2.1.286.');
    expect(html).not.toContain('<textarea');
  });
  test('a session that is not running has no box, and says why', () => {
    expect(renderToStaticMarkup(<NoteClosed />)).toContain('This session is not running, so it cannot take a note.');
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
    expect(html).toContain('received');
    expect(html).not.toContain('more');
    const sent = render(rail({ seats: [seat('lead'), seat('me', { here: true })], rides: [{ seat: 'lead', direction: 'out', at: 0, text: 'x' }] }));
    expect(sent).toContain('sent');
    expect(sent).not.toContain('received');
  });
  test('a seat says its role once, and the lead wears the tag; a role the session name already is is not said again', () => {
    const html = render(rail({ team: 'T', seats: [seat('Planner', { role: 'Planner', lead: true }), seat('rate-limiter-2', { role: 'Builder' })] }));
    expect(html.match(/>Planner</g)).toHaveLength(1);
    expect(html).toContain('<span class="role">Builder</span>');
    expect(html).toContain('<span class="tag">Lead</span>');
    expect(html.match(/class="tag"/g)).toHaveLength(1);
  });
});

describe('a session card', () => {
  const row = (over: Partial<SessionRow> = {}): SessionRow => ({
    pid: 1, session_id: 'sess-1', name: 'Write the tests', kind: 'interactive', version: null, cwd: '/home/ava/work/tally', status: 'busy',
    status_updated_at: 0, started_at: 0, updated_at: 0, address: null, head: 'claude-grok', availability: 'live', ...over,
  });
  const facts = (over: Partial<CardFacts> = {}): CardFacts => ({ row: row(), state: 'working', colour: 'grok', head: 'claude-grok', hand: null, since: 42 * 60_000, quiet: null, ...over });
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
    expect(html).toContain('last message from claude-splice');
    expect(html).toContain('href="/sessions/sess-1"');
    expect(html).not.toContain('class="acts"');
    expect(html).toContain('win grok');
    expect(html).toContain('<span class="model m-grok">claude-grok</span>');
    expect(html).not.toContain('attn');
  });
  test('a hand-off line says what happened: the last message to a session, or the sessions a lead messaged', () => {
    expect(render(facts({ hand: { kind: 'to', peer: 'claude-muse' } }))).toContain('last message to claude-muse');
    expect(render(facts({ hand: { kind: 'lead', peers: 50 } }))).toContain('messaged 50 sessions');
    expect(render(facts({ hand: { kind: 'lead', peers: 50 } }))).not.toContain('lead of');
  });
  test('a card that needs a person drops its hue, opens the session and offers the resume command', () => {
    const html = render(facts({ state: 'waiting', row: row({ status: 'waiting' }) }));
    expect(html).toContain('win grok attn');
    expect(html).toContain('Open the session');
    expect(html).toContain('Copy resume command');
    expect(html).toContain('Waiting on you');
  });
  test('an earlier session offers its resume command on its own plan and on another, and no open link', () => {
    const html = render(facts({ state: 'gone', row: row({ status: null, availability: 'gone', pid: null }) }));
    expect(html).toContain('Copy resume command');
    expect(html).toContain('Resume on another command');
    expect(html).not.toContain('Open the session');
    expect(html).not.toContain('Stop the turn');
  });
  test('a working or idle card offers no resume', () => {
    expect(render(facts({ state: 'idle' }))).not.toContain('Copy resume command');
    expect(render(facts())).not.toContain('Copy resume command');
  });
  test('a session splice did not start asks which head to resume on, and offers no second button', () => {
    const html = render(facts({ state: 'gone', head: null, colour: 'none', row: row({ head: UNKNOWN_HEAD, availability: 'gone' }) }));
    expect(html).toContain('Copy resume command');
    expect(html).not.toContain('Resume on another command');
  });
  test('a session splice did not start shows no head and no invented colour', () => {
    const html = render(facts({ head: null, colour: 'none' }));
    expect(html).not.toContain('claude-grok');
    expect(html).not.toContain('Not started');
  });
});
