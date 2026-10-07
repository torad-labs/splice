// The team page rendered to markup from a seeded cache: seats, who sits in them, the day's talk.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { renderToStaticMarkup } from 'react-dom/server';
import { MemoryRouter, Route, Routes } from 'react-router';
import { describe, expect, test } from 'vitest';
import { teamActivityPath, teamChatPath, teamEconomicsPath } from '../src/api/teams';
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
  test('a known binding with unread sessions and economics does not claim an empty or idle seat', () => {
    const html = render(client => {
      client.setQueryData(['teams', '/api/teams'], { teams: [{ ...team, goal: '', slots: [team.slots[0]] }] });
      client.removeQueries({ queryKey: ['sessions'] });
    });
    expect(html).toContain('No working session is listed.');
    expect(html).toContain('No session is listed for this seat');
    expect(html).toContain('No turns are listed for this seat');
    expect(html).not.toContain('seat is not working');
    expect(html).not.toContain('Nobody is in this seat');
    expect(html).not.toContain('Open seat');
    expect(html).not.toContain('No turns yet');
  });
  test('two seats bound to one busy session list one working session', () => {
    const html = render(client => {
      client.setQueryData(['teams', '/api/teams'], { teams: [{
        ...team, goal: '', slots: team.slots.map(slot => ({ ...slot, session: 'sess-1' })),
      }] });
      const sessions = client.getQueryData<{ sessions: Record<string, unknown>[] }>(['sessions', '/api/sessions']);
      client.setQueryData(['sessions', '/api/sessions'], { sessions: sessions?.sessions.map(row => ({ ...row, status: 'busy' })) });
    });
    expect(html).toContain('One working session is listed.');
    expect(html).not.toContain('Two working sessions are listed.');
    expect(html.match(/>Working</g)).toHaveLength(2);
  });
  test('distinct busy session bindings retain their separate working-session count', () => {
    const html = render(client => {
      client.setQueryData(['teams', '/api/teams'], { teams: [{
        ...team, goal: '', slots: team.slots.map((slot, index) => ({ ...slot, session: 'sess-' + (index + 1) })),
      }] });
      client.setQueryData(['sessions', '/api/sessions'], { sessions: ['sess-1', 'sess-2'].map(session_id => ({
        session_id, name: session_id, head: 'claude-grok', availability: 'live', status: 'busy', pid: 1,
        kind: null, version: null, cwd: null, status_updated_at: null, started_at: null, updated_at: NOW, address: null,
      })) });
    });
    expect(html).toContain('Two working sessions are listed.');
  });
  test('retained message requests do not promise successful delivery', () => {
    const day = dayOf(NOW, 0);
    // Delivery refusal is not represented in this call-observer payload.
    const html = render(client => client.setQueryData(['team-panels', 'chat', teamChatPath('t1', day)], {
      team_id: 't1', day_start_epoch_millis: day.from, packet_note: '',
      messages: [{ at: NOW, from: 'synthetic', to: 'Synthetic', from_slot: null, to_slot: null,
        packet: null, text: 'Synthetic', text_source: '/synthetic/transcript.jsonl', missing_reason: null }],
    }));
    expect(html).toContain('Recorded message requests, newest first.');
    expect(html).toContain('Delivery is not confirmed here.');
    expect(html).not.toContain('What the seats sent each other');
  });
  test('a busy text-only turn with no activity sample does not promise a periodic sample', () => {
    const html = render(client => {
      const sessions = client.getQueryData<{ sessions: Record<string, unknown>[] }>(['sessions', '/api/sessions']);
      client.setQueryData(['sessions', '/api/sessions'], { sessions: sessions?.sessions.map(row => ({
        ...row, status: 'busy', last: { role: 'assistant', text: 'Hello', tool: null, ts: NOW },
      })) });
      client.setQueryData(['team-panels', 'activity', teamActivityPath('t1', dayOf(NOW, 0))], { entries: [] });
    });
    expect(html).toContain('Recorded activity samples. Not every turn produces one.');
    expect(html).not.toContain('about every half minute');
  });
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
    expect(html).toContain('No session is listed for this seat');
    expect(html).toContain('Not listed');
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
  test('team messages render Markdown and code instead of printing their syntax', () => {
    const day = dayOf(NOW, 0);
    const html = render(client => client.setQueryData(['team-panels', 'chat', teamChatPath('t1', day)], {
      team_id: 't1', day_start_epoch_millis: day.from, packet_note: '',
      messages: [{ at: NOW, from: 'a', from_slot: 's2', from_head: null, to: 'b', to_slot: 's1', packet: null,
        text: '**Synthetic finding**\n\n- Check the boundary\n\n```typescript\nconst limit = 3;\n```',
        text_source: 'synthetic', missing_reason: null }],
    }));
    expect(html).toContain('<strong>Synthetic finding</strong>');
    expect(html).toContain('<li>Check the boundary</li>');
    expect(html).toContain('class="code"');
    expect(html).toContain('Planner to Builder');
    expect(html).not.toContain('**Synthetic finding**');
    expect(html).not.toContain('```typescript');
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

function usageTallyPage(fields: { unreported_usage_turns?: number; unpriced_turns?: number; cost_usd?: number | null } = {}): string {
  const base = { turns: 31, tokens: { input: 1000, cache_read: 0, cache_write: 0, output: 200 },
    cost_usd: 1.9, unpriced_turns: 0, last_turn_at_epoch_millis: null, checks: 'fail', checks_source: 'synthetic' };
  return render(client => client.setQueryData(['team-panels', 'economics', teamEconomicsPath('t1')], {
    team_id: 't1', heads_read: [], unattributed_turns: 7, oldest_turn_epoch_millis: null,
    roles: [{ ...base, role: 'Builder', unreported_usage_turns: 99 }],
    slots: [{ ...base, slot: 's1', ...fields }, { ...base, slot: 's2', cost_usd: 4, unreported_usage_turns: 0 }],
  }));
}

describe('team usage completeness', () => {
  test.each([undefined, 0])('an absent or zero usage-gap count %s preserves the recorded price', count => {
    const html = usageTallyPage(count === undefined ? {} : { unreported_usage_turns: count });
    expect(html).toContain('31 turns · API est. $1.90 · last turn failed');
    expect(html).not.toContain('incomplete or missing usage report');
    expect(html).not.toContain('at least');
  });
  test.each([1, 2])('a usage-gap count %s qualifies only its slot and does not recount turns', count => {
    const html = usageTallyPage({ unreported_usage_turns: count });
    expect(html).toContain('31 turns · API est. at least $1.90 · last turn failed');
    expect(html).toContain(count === 1
      ? '1 turn has an incomplete or missing usage report, so these totals include only reported usage and are lower bounds.'
      : '2 turns have incomplete or missing usage reports, so these totals include only reported usage and are lower bounds.');
    const neighbor = html.match(/<li[^>]*aria-label="Planner"[^>]*>(.*?)<\/li>/s)?.[1];
    expect(neighbor).toContain('31 turns · API est. $4.00 · last turn failed');
    expect(neighbor).not.toContain('lower bound');
    expect(neighbor).not.toContain('incomplete or missing');
    expect(html).toContain('7 turns on these commands carry no session and are counted apart.');
    expect(html).not.toContain('99 turns');
    expect(html).not.toContain('reported no usage');
    expect(html).not.toContain('not in these');
  });
  test('a price-only gap qualifies dollars without manufacturing a missing-usage sentence', () => {
    const html = usageTallyPage({ unpriced_turns: 3, unreported_usage_turns: 0 });
    expect(html).toContain('API est. at least $1.90');
    expect(html).not.toContain('incomplete or missing usage report');
  });
  test('overlapping usage and price gaps retain their separate counts', () => {
    const html = usageTallyPage({ unreported_usage_turns: 2, unpriced_turns: 3 });
    expect(html).toContain('2 turns have incomplete or missing usage reports');
    expect(html).toContain('API est. at least $1.90');
    expect(html).not.toContain('3 turns have incomplete');
  });
  test('recorded zero dollars remain a known lower bound rather than becoming unknown', () => {
    const html = usageTallyPage({ unreported_usage_turns: 1, unpriced_turns: 1, cost_usd: 0 });
    expect(html).toContain('31 turns · API est. at least $0.00 · last turn failed');
    expect(html).toContain('1 turn has an incomplete or missing usage report');
  });
  test('a missing usage report with unknown dollars never manufactures zero or a numeric lower bound', () => {
    const html = usageTallyPage({ unreported_usage_turns: 1, unpriced_turns: 31, cost_usd: null });
    expect(html).toContain('31 turns · – · last turn failed');
    expect(html).toContain('1 turn has an incomplete or missing usage report');
    const own = html.match(/<li[^>]*aria-label="Builder"[^>]*>(.*?)<\/li>/s)?.[1];
    expect(own).not.toContain('API est.');
    expect(own).not.toContain('$0.00');
  });
});
