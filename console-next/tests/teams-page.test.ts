// The team composer's rules and the board's reads: what stops a save, what a save sends, and the day a board shows.
import { describe, expect, test } from 'vitest';
import { addSeat, atRetentionEdge, blankDraft, dayOf, dayWords, draftOf, editSeat, featuresOf, keyFor, peerName, removeSeat, seatsOf, setLead, teamLede, validateDraft, writeOf } from '../src/lib/teams-page';
import type { TeamChatPayload, TeamRow } from '../src/types/teams';
import { M } from '../src/lib/words-teams';

const team = (over: Partial<TeamRow> = {}): TeamRow => ({
  id: 't1', name: 'Rate limiter', goal: 'Add a rate limiter to the API.', features: ['per-key limits'], repo: '/home/a/tally', archived: false,
  created_epoch_millis: 1, updated_epoch_millis: 2, idempotency_key: null, create_fingerprint: null,
  slots: [
    { id: 's1', role: 'Builder', head: 'claude-grok', model: 'grok-4.7', account: 'ava', lead: false, instructions: 'Write src/.', session: 'sess-1', instructions_updated_epoch_millis: null, sessions_history: [] },
    { id: 's2', role: 'Planner', head: 'claude-splice', model: null, account: null, lead: true, instructions: null, session: null, instructions_updated_epoch_millis: null, sessions_history: [] },
  ], ...over,
});
const valid = () => ({ ...editSeat(blankDraft('a'), 0, { role: 'Planner', head: 'h' }), name: 'T', repo: '/r' });

describe('a draft', () => {
  test('a blank draft has one lead seat and every problem in field order', () => {
    expect(validateDraft(blankDraft('a'))).toEqual(['Give the team a name.', 'Say which repository it works in.', 'Seat 1 needs a role', 'Seat 1 needs a command']);
  });
  test('a complete draft is valid', () => {
    expect(validateDraft(valid())).toEqual([]);
  });
  test('exactly one seat leads, and making one lead takes it from the others', () => {
    const two = editSeat(editSeat(addSeat(valid(), 'b'), 1, { role: 'R', head: 'h' }), 1, { lead: true });
    expect(validateDraft(two)).toContain('Exactly one seat leads.');
    expect(validateDraft(editSeat(editSeat(addSeat(valid(), 'b'), 1, { role: 'R', head: 'h' }), 0, { lead: false }))).toContain('Exactly one seat leads.');
    expect(two.seats.map((seat) => seat.lead)).toEqual([true, true]);
    expect(setLead(two, 1).seats.map((seat) => seat.lead)).toEqual([false, true]);
  });
  test('a team with no seat says so and a session in two seats is named', () => {
    expect(validateDraft({ ...valid(), seats: [] })).toContain('Add at least one seat.');
    const both = { ...addSeat(valid(), 'b'), name: 'T' };
    const doubled = editSeat(editSeat(both, 0, { session: 'x' }), 1, { session: 'x', role: 'R', head: 'h' });
    expect(validateDraft(doubled)).toContain('x is in two seats');
  });
  test('removing the only seat is allowed on a draft, and the save is refused', () => {
    expect(validateDraft(removeSeat(valid(), 0))).toContain('Add at least one seat.');
  });
});

describe('what a save sends', () => {
  test('editing changes nothing it does not name: model, account, binding and lead ride through', () => {
    const body = writeOf(draftOf(team()));
    expect(body.slots[0]).toMatchObject({ id: 's1', model: 'grok-4.7', account: 'ava', session: 'sess-1', lead: false });
    expect(body.slots[1]).toMatchObject({ id: 's2', instructions: null, session: null, lead: true });
    expect(body.features).toEqual(['per-key limits']);
  });
  test('blank instructions are none, not an empty prompt', () => {
    const body = writeOf(editSeat(draftOf(team()), 0, { instructions: '   ' }));
    expect(body.slots[0]?.instructions).toBeNull();
  });
  test('features are one per line with blanks dropped', () => {
    expect(featuresOf(' a \n\n b\n')).toEqual(['a', 'b']);
  });
  test('the same body reuses its key and a changed one mints a new one', () => {
    let n = 0;
    const mint = () => `k${++n}`;
    const first = keyFor(null, 'a', mint);
    expect(keyFor(first, 'a', mint)).toBe(first);
    expect(keyFor(first, 'b', mint).key).toBe('k2');
  });
});

describe('the board', () => {
  test('the lead is listed first', () => {
    expect(seatsOf(team()).map((slot) => slot.id)).toEqual(['s2', 's1']);
  });
  test('the lede reads the goal, then how many seats work and how many are open', () => {
    expect(teamLede(team(), 1)).toBe('Add a rate limiter to the API. One working session is listed; one seat is open.');
    expect(teamLede(team({ goal: '' }), 0)).toBe('No goal written. No working session is listed; one seat is open.');
    // A team whose every seat is open says that, not that none of its one seat is working.
    expect(M.ledeSeats(0, 1, 1)).toBe('The one seat is open.');
    expect(M.ledeSeats(0, 3, 3)).toBe('All three seats are open.');
  });
  test('a day is local midnight to local midnight, and words name it', () => {
    const now = new Date(2026, 8, 29, 16, 30).getTime();
    const today = dayOf(now, 0);
    expect(new Date(today.from).getHours()).toBe(0);
    expect(today.to - today.from).toBeGreaterThanOrEqual(23 * 3_600_000);
    expect(dayOf(now, 1).to).toBe(today.from);
    expect(dayWords(now, 0)).toBe('Today');
    expect(dayWords(now, 1)).toBe('Yesterday');
  });
  test('Earlier stops at the edge of what is kept: a day whose chat is not kept, or that holds the oldest kept message', () => {
    const now = new Date(2026, 8, 29, 16, 30).getTime();
    const chat = (over: Partial<TeamChatPayload>): TeamChatPayload => ({ team_id: 't', day_start_epoch_millis: 0, packet_note: '', messages: [], ...over });
    const yesterday = dayOf(now, 1);
    const older = dayOf(now, 2);
    expect(atRetentionEdge(undefined, yesterday)).toBe(false);
    expect(atRetentionEdge(chat({}), yesterday)).toBe(false);
    expect(atRetentionEdge(chat({ state: 'on' }), yesterday)).toBe(false);
    expect(atRetentionEdge(chat({ state: 'not_kept', reason: 'Message history past activityRetentionDays is not kept.', oldest_kept_epoch_millis: yesterday.from }), older)).toBe(true);
    expect(atRetentionEdge(chat({ state: 'not_kept' }), older)).toBe(true);
    expect(atRetentionEdge(chat({ state: 'partially_kept', oldest_kept_epoch_millis: yesterday.from + 3_600_000 }), yesterday)).toBe(true);
    expect(atRetentionEdge(chat({ oldest_kept_epoch_millis: older.from }), yesterday)).toBe(false);
  });
});

describe('who a message names', () => {
  test('a filed session reads as its role, an unfiled socket as another session, and never as the socket', () => {
    expect(peerName('lead', 'uds:/run/user/1000/cc-socks/700.sock')).toBe('lead');
    expect(peerName(null, 'uds:/run/user/1000/cc-socks/700.sock')).toBe('Another session');
    expect(peerName(null, 'sess-1')).toBe('sess-1');
  });
});
