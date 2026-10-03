// The team page rendered to markup from a seeded cache: seats, who sits in them, the day's talk.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { renderToStaticMarkup } from 'react-dom/server';
import { MemoryRouter, Route, Routes } from 'react-router';
import { describe, expect, test } from 'vitest';
import { teamChatPath, teamEconomicsPath } from '../src/api/teams';
import { dayOf } from '../src/lib/teams-page';
import { TeamPage } from '../src/pages/teams/TeamPage';
import type { TeamRow } from '../src/types/teams';

const NOW = Date.now();
const team: TeamRow = {
  id: 't1', name: 'Rate limiter', goal: 'Add a rate limiter to the API.', features: ['per-key limits'], repo: '/home/a/tally', archived: false,
  created_epoch_millis: 1, updated_epoch_millis: 2, idempotency_key: null, create_fingerprint: null,
  slots: [
    { id: 's1', role: 'Builder', head: 'claude-grok', model: null, account: null, lead: false, instructions: 'Write src/.', session: 'sess-1', instructions_updated_epoch_millis: null, sessions_history: [] },
    { id: 's2', role: 'Planner', head: 'claude-splice', model: null, account: null, lead: true, instructions: null, session: null, instructions_updated_epoch_millis: null, sessions_history: [] },
  ],
};

function render(seed: (client: QueryClient) => void): string {
  const client = new QueryClient();
  client.setQueryData(['teams', '/api/teams'], { teams: [team] });
  client.setQueryData(['sessions', '/api/sessions'], { sessions: [{ session_id: 'sess-1', name: 'Write the rate limiter', head: 'claude-grok', availability: 'live', status: 'idle', pid: 1, kind: null, version: null, cwd: null, status_updated_at: null, started_at: null, updated_at: NOW, address: null }] });
  client.setQueryData(['heads', '/api/heads'], { heads: [{ key: 'claude-grok', label: 'Grok', authKind: 'grok' }, { key: 'claude-splice', label: 'Claude', authKind: 'anthropic' }] });
  seed(client);
  return renderToStaticMarkup(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/teams/t1']}>
        <Routes><Route path="/teams/:id" element={<TeamPage />} /></Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('the team page', () => {
  test('seat colours follow registry vendor families rather than their auth labels', () => {
    const html = render((client) => client.setQueryData(['status', '/api/status'], { registry: [
      { key: 'claude-grok', label: 'Grok', authKind: 'api-key', family: 'local' },
      { key: 'claude-splice', label: 'Claude', authKind: 'bearer', family: 'anthropic' },
    ] }));
    expect(html).toContain('seatrow hue local');
    expect(html).toContain('seatrow hue claude');
    expect(html).not.toContain('seatrow hue router');
  });
  test('a seat says its role once, and the session under it names itself only when that adds something', () => {
    const named = render((client) => {
      client.setQueryData(['sessions', '/api/sessions'], { sessions: [{ session_id: 'sess-1', name: 'builder', head: 'claude-grok', availability: 'live', status: 'idle', pid: 1, kind: null, version: null, cwd: null, status_updated_at: null, started_at: null, updated_at: NOW, address: null }] });
    });
    expect(named.match(/>Builder</g)).toHaveLength(1);
    expect(named).not.toContain('>builder<');
    expect(named).toContain('>Open the session<');
    expect(render(() => undefined)).toContain('>Write the rate limiter<');
  });
  test('lists the lead first, names the session in a seat and marks the open one', () => {
    const html = render(() => undefined);
    expect(html).toContain('Rate limiter');
    expect(html.indexOf('Planner')).toBeLessThan(html.indexOf('Builder'));
    expect(html).toContain('Write the rate limiter');
    expect(html).toContain('Nobody is in this seat');
    expect(html).toContain('Open seat');
    expect(html).toContain('per-key limits');
    expect(html).toContain('href="/teams"');
  });
  test('a seat with lifetime tallies says its turns, cost and checks', () => {
    const html = render((client) =>
      client.setQueryData(['team-panels', 'economics', teamEconomicsPath('t1')], {
        team_id: 't1', heads_read: [], unattributed_turns: 3, oldest_turn_epoch_millis: null, roles: [],
        slots: [{ slot: 's1', turns: 31, tokens: { input: 1, cache_read: 0, cache_write: 0, output: 1 }, cost_usd: 1.9, unpriced_turns: 0, last_turn_at_epoch_millis: null, checks: 'fail', checks_source: 'x' }],
      }),
    );
    expect(html).toContain('31 turns · API est. $1.90 · last turn failed');
    expect(html).toContain('3 turns on these commands carry no session');
  });
  test('the day\'s talk is newest first, named by role, and a missing text says why', () => {
    const day = dayOf(NOW, 0);
    const html = render((client) =>
      client.setQueryData(['team-panels', 'chat', teamChatPath('t1', day)], {
        team_id: 't1', day_start_epoch_millis: day.from, packet_note: '',
        messages: [
          { at: NOW - 2000, from: 'a', from_slot: 's2', from_head: null, to: 'b', to_slot: 's1', packet: null, text: 'Take the limiter.', text_source: 'transcript', missing_reason: null },
          { at: NOW - 1000, from: 'b', from_slot: 's1', from_head: null, to: 'a', to_slot: 's2', packet: null, text: null, text_source: null, missing_reason: 'Transcript pruned.' },
        ],
      }),
    );
    expect(html.indexOf('Transcript pruned.')).toBeLessThan(html.indexOf('Take the limiter.'));
    expect(html).toContain('Planner to Builder');
    expect(html).toContain('Builder to Planner');
  });
  test('an unknown team says so', () => {
    const client = new QueryClient();
    client.setQueryData(['teams', '/api/teams'], { teams: [] });
    const html = renderToStaticMarkup(
      <QueryClientProvider client={client}><MemoryRouter initialEntries={['/teams/nope']}><Routes><Route path="/teams/:id" element={<TeamPage />} /></Routes></MemoryRouter></QueryClientProvider>,
    );
    expect(html).toContain('No such team.');
  });
});
