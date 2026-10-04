import { describe, expect, test } from 'vitest';
import { railOf } from '../src/lib/rail';
import type { HandedEdge, SessionRow } from '../src/types/sessions';
import type { TeamRow } from '../src/types/teams';

const NOW = 1_790_000_000_000;
const row = (id: string, over: Partial<SessionRow> = {}): SessionRow => ({
  pid: 1, session_id: id, name: id, kind: 'interactive', version: null, cwd: null, status: 'busy', status_updated_at: NOW, started_at: NOW, updated_at: NOW,
  address: `uds:/${id}`, head: 'claude-grok', availability: 'live', ...over,
});
const edge = (over: Partial<HandedEdge>): HandedEdge => ({ from: 'me', to: 'uds:/lead', at: 1, direction: 'out', text: null, text_source: null, missing_reason: null, ...over });
const team = (over: Partial<TeamRow> = {}): TeamRow => ({
  id: 't1', name: 'Rate limiter', goal: '', features: [], repo: '', archived: false, created_epoch_millis: 0, updated_epoch_millis: 0, idempotency_key: null, create_fingerprint: null,
  slots: [
    { id: 's1', role: 'tests', head: 'claude-muse', model: null, account: null, lead: false, instructions: null, session: 'muse', instructions_updated_epoch_millis: null, sessions_history: [] },
    { id: 's2', role: 'lead', head: 'claude-splice', model: null, account: null, lead: true, instructions: null, session: 'lead', instructions_updated_epoch_millis: null, sessions_history: [] },
    { id: 's3', role: 'reviewer', head: 'claudex', model: null, account: null, lead: false, instructions: null, session: null, instructions_updated_epoch_millis: null, sessions_history: [] },
  ], ...over,
});

describe('the rail of a session bound to a team', () => {
  const rows = [row('me', { team: 't1' }), row('lead'), row('muse')];
  test('shows the team\'s bound seats, the lead first, this session marked; an open seat is not a seat', () => {
    const rail = railOf(rows[0] as SessionRow, rows, [], [team()]);
    expect(rail.team).toBe('Rate limiter');
    expect(rail.seats.map((seat) => [seat.key, seat.here, seat.lead])).toEqual([['lead', false, true], ['muse', false, false], ['me', true, false]]);
  });
  test('a bound session the registry does not list still holds its seat, with no state to claim', () => {
    const rail = railOf(rows[0] as SessionRow, [rows[0] as SessionRow], [], [team()]);
    expect(rail.seats.find((seat) => seat.key === 'lead')).toMatchObject({ state: null, role: 'lead', head: 'claude-splice' });
  });
});

describe('the rail of a session with no team', () => {
  const rows = [row('me'), row('lead')];
  test('is this session and the sessions it exchanged hand-offs with', () => {
    const rail = railOf(rows[0] as SessionRow, rows, [edge({ text: 'build it' }), edge({ direction: 'in', from: 'lead', to: 'uds:/me', at: 2, text: 'ready' })], []);
    expect(rail.team).toBeNull();
    expect(rail.seats.map((seat) => seat.key)).toEqual(['me', 'lead']);
    expect(rail.rides).toEqual([
      { seat: 'lead', direction: 'out', at: 1, text: 'build it' },
      { seat: 'lead', direction: 'in', at: 2, text: 'ready' },
    ]);
  });
  test('a peer the registry no longer holds is a seat named a session, never its socket', () => {
    const rail = railOf(rows[0] as SessionRow, [rows[0] as SessionRow], [edge({ to: 'uds:/gone' })], []);
    expect(rail.seats.at(-1)).toMatchObject({ key: 'uds:/gone', label: 'a session', state: null });
  });
  test('a session with no hand-offs is alone on its rail', () =>
    expect(railOf(rows[0] as SessionRow, rows, [], []).seats).toHaveLength(1));
});
