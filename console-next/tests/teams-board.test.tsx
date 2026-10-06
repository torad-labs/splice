import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { renderToStaticMarkup } from 'react-dom/server';
import { MemoryRouter } from 'react-router';
import { describe, expect, test } from 'vitest';
import { MgmtError } from '../src/api/client';
import { TeamsPage } from '../src/pages/teams/TeamsPage';
import { MessageText } from '../src/pages/teams/HandoffMessage';
import { ProjectTeam } from '../src/pages/teams/ProjectTeam';
import type { TurnsState } from '../src/types/perf';
import type { SessionRow } from '../src/types/sessions';

const seat = (id: string, over: Partial<SessionRow> = {}): SessionRow => ({
  session_id: id, name: id, pid: 1, kind: 'interactive', version: null,
  head: 'test-head', cwd: '/work/tally', repo: { root: '/work/tally' },
  status: 'busy', availability: 'live', address: `uds:/synthetic/${id}`,
  status_updated_at: null, started_at: null, updated_at: null, ...over,
});

function render(seed: (client: QueryClient) => void = () => undefined): string {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, retryOnMount: false } } });
  client.setQueryData(['sessions', '/api/sessions'], { note: 'Synthetic registry limitation.', sessions: [
    seat('lead', { last: { role: 'assistant', tool: null, text: 'Assigning the API work.', ts: 1 } }),
    seat('builder', { availability: 'stale' }),
  ] });
  client.setQueryData(['teams', '/api/teams'], { teams: [] });
  client.setQueryData(['edges', '/api/sessions/edges'], { sessions: {
    lead: [{ from: 'lead', to: 'uds:/synthetic/builder', at: 1, direction: 'out' }],
  } });
  client.setQueryData(['heads', '/api/heads'], { heads: [{ key: 'test-head', label: 'Test model' }] });
  seed(client);
  return renderToStaticMarkup(<QueryClientProvider client={client}><MemoryRouter><TeamsPage /></MemoryRouter></QueryClientProvider>);
}

describe('the project teams board', () => {
  test('real project membership and current activity need no persistent TeamStore row', () => {
    const html = render();
    expect(html).toContain('tally');
    expect(html).toContain('lead');
    expect(html).toContain('builder');
    expect(html).toContain('Assigning the API work.');
    expect(html).toContain('last status update is old');
    expect(html).toContain('lead to builder');
    expect(html).toContain('Recorded messages between these sessions, newest first.');
    expect(html).not.toContain('Recent messages');
    expect(html).toContain('href="/sessions/lead"');
    expect(html).not.toContain('uds:');
    expect(html).not.toContain('No teams');
    expect(html).not.toContain('Reading the message');
  });
  test.each(['transport', 'envelope'])('registry %s failure never looks like an empty team list', kind => {
    const html = render(client => {
      if (kind === 'envelope') client.setQueryData(['sessions', '/api/sessions'], { error: 'Synthetic registry refused.', note: '', sessions: [] });
      else client.getQueryCache().find({ queryKey: ['sessions', '/api/sessions'] })?.setState({ status: 'error', error: new MgmtError(500, 'Synthetic registry refused.') });
    });
    expect(html).toContain('Synthetic registry refused.');
    expect(html).not.toContain('No sessions are open');
  });
  test('an unresolved project stays visible without grouping by the working folder', () => {
    const row = seat('unresolved', { cwd: null });
    delete row.repo;
    const html = render(client => client.setQueryData(['sessions', '/api/sessions'], { note: '', sessions: [row] }));
    expect(html).toContain('Project not reported');
    expect(html).toContain('unresolved');
    expect(html).not.toContain('href="/projects/');
  });
  test('missing or disabled handoff history is not reported as no messages', () => {
    const pending = render(client => client.removeQueries({ queryKey: ['edges'] }));
    expect(pending).toContain('Reading the handoffs');
    expect(pending).not.toContain('No handoffs recorded');
    const off = render(client => client.setQueryData(['edges', '/api/sessions/edges'], { state: 'off', reason: 'Synthetic transcript view is off.', sessions: {} }));
    expect(off).toContain('Synthetic transcript view is off.');
    expect(off).not.toContain('No handoffs recorded');
  });
  test.each(['live', 'stale'] as const)('an %s idle session awaits a message rather than reporting unknown work or an old-status warning', availability => {
    const html = render(client => client.setQueryData(['sessions', '/api/sessions'], { note: '', sessions: [seat('idle', { status: 'idle', availability })] }));
    expect(html).toContain('>Idle<');
    expect(html).toContain('Waiting for your next message');
    expect(html).not.toContain('Current work is not reported');
    expect(html).not.toContain('last status update is old');
    expect(html).not.toContain('1 status old');
    expect(html).not.toContain('>Ended<');
  });
  test('an absent client status is unknown rather than idle', () => {
    const html = render(client => client.setQueryData(['sessions', '/api/sessions'], { note: '', sessions: [seat('unknown', { status: null })] }));
    expect(html).toContain('Status not reported');
    expect(html).not.toContain('>Idle<');
  });
  test('saved teams remain reachable, and archived teams are labelled rather than deleted', () => {
    const html = render(client => client.setQueryData(['teams', '/api/teams'], { teams: [{
      id: 'saved', name: 'Release crew', goal: 'Ship the synthetic release.', repo: '/work/tally', archived: true, slots: [],
    }] }));
    expect(html).toContain('Release crew');
    expect(html).toContain('Archived');
    expect(html).toContain('href="/teams/saved"');
  });
});

describe('project metadata', () => {
  test('stale working sessions count as working, and model labels come from their recorded requests', () => {
    const client = new QueryClient();
    client.setQueryData(['edges', '/api/sessions/edges'], { sessions: {} });
    const row = seat('synthetic', { availability: 'stale' });
    const data: TurnsState = { inflight: [], unread: [], truncated: [], matched: 1, matchedBy: { 'test-head': 1 }, landed: [{
      head: 'test-head', session_id: 'synthetic', ts: 1, model: 'actual-model', compact: false, outcome: 'failed', cost_usd: 0.5, in_tokens: 100, out_tokens: 20,
    }], usageBy: { 'test-head': { totals: { requests: 1, input_tokens: 100, cached_tokens: 0, output_tokens: 20, cost_usd: 0.5, cache_share: 0, unpriced_requests: 0, missing_input_requests: 0, missing_output_requests: 0, missing_cache_requests: 0 }, models: [], accounts: [], days: [], sessions: [{ key: 'synthetic', requests: 1, input_tokens: 100, cached_tokens: 0, output_tokens: 20, cost_usd: 0.5, cache_share: 0, unpriced_requests: 0, missing_input_requests: 0, missing_output_requests: 0, missing_cache_requests: 0, last_model: 'actual-model', last_model_ts_epoch_ms: 1 }] } } };
    const html = renderToStaticMarkup(<QueryClientProvider client={client}><MemoryRouter><ProjectTeam group={{ root: '/work/tally', sessions: [row] }} reading={{ data, models: undefined, pending: false, error: null, retry: () => undefined }} /></MemoryRouter></QueryClientProvider>);
    expect(html).toContain('1 working');
    expect(html).toContain('>Working<');
    expect(html).toContain('Last model: actual-model');
    expect(html).toContain('Requests in the last 24 hours');
    expect(html).toContain('$0.50');
    expect(html).not.toContain('Last model: test-head');
  });
});

describe('one opened handoff', () => {
  const edge = { from: 'lead', to: 'builder', at: 1, direction: 'out' as const };
  const renderMessage = (text: string | null, reason: string | null = null, at = 1): string => {
    const client = new QueryClient();
    client.setQueryData(['edges', '/api/sessions/lead/edges'], { session_id: 'lead', edges: [{
      ...edge, at, text, text_source: null, missing_reason: reason,
    }] });
    return renderToStaticMarkup(<QueryClientProvider client={client}><MessageText edge={edge} /></QueryClientProvider>);
  };
  test('reads exactly the observed message, not another edge from the same sender', () => {
    expect(renderMessage('Synthetic message body.')).toContain('Synthetic message body.');
    expect(renderMessage('A different message.', null, 2)).not.toContain('A different message.');
    expect(renderMessage('A different message.', null, 2)).toContain('The message text is no longer available.');
  });
  test('an opened handoff renders Markdown rather than raw message syntax', () => {
    const html = renderMessage('**Synthetic handoff**\n\n```typescript\nconst boundary = 1;\n```');
    expect(html).toContain('<strong>Synthetic handoff</strong>');
    expect(html).toContain('class="code"');
    expect(html).not.toContain('**Synthetic handoff**');
    expect(html).not.toContain('```typescript');
  });
  test('retained metadata with unavailable text keeps the daemon reason visible', () => {
    expect(renderMessage(null, 'Synthetic transcript was removed.')).toContain('Synthetic transcript was removed.');
  });
});

describe('reading an observed handoff', () => {
  const edge = { from: 'lead', to: 'builder', at: 42, direction: 'out' as const };
  const message = { ...edge, text: 'Synthetic packet for this edge.', text_source: 'synthetic', missing_reason: null };
  function text(payload: unknown): string {
    const client = new QueryClient();
    client.setQueryData(['edges', '/api/sessions/lead/edges'], payload);
    return renderToStaticMarkup(<QueryClientProvider client={client}><MessageText edge={edge} /></QueryClientProvider>);
  }
  test('only the selected handoff is read, never a neighboring transcript message', () => {
    const html = text({ session_id: 'lead', edges: [{ ...message, at: 41, text: 'Other synthetic packet.' }, message] });
    expect(html).toContain('Synthetic packet for this edge.');
    expect(html).not.toContain('Other synthetic packet.');
  });
  test('a pruned message keeps its reported reason', () => {
    expect(text({ session_id: 'lead', edges: [{ ...message, text: null, missing_reason: 'Synthetic transcript pruned.' }] })).toContain('Synthetic transcript pruned.');
  });
  test('disabled transcript viewing stays a refusal, not invented message text', () => {
    expect(text({ session_id: 'lead', state: 'off', reason: 'Synthetic transcript viewing disabled.', edges: [] })).toContain('Synthetic transcript viewing disabled.');
  });
});
