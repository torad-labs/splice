// V4-402: a hand-off's recipient prints as the member it reached, by role, and never as a socket path.
// Marlin's walk of V4-391 (a team whose sessions had ended): every recipient read 'uds:/…/<n>.sock',
// because the daemon could not name the session that held the socket and the board printed the
// address it was given. A recipient the board cannot name reads as another session.
import { describe, expect, test } from 'vitest';
import type { TeamChatMessage, TeamChatPayload, TeamRow, TeamSlot } from '../src/entities/team';
import { membersOf, messagesOf } from '../src/pages/teams/board';

const NOW = Date.UTC(2026, 8, 25, 20, 0, 0);
const SOCKET = 'uds:/run/cc-socks/700.sock';

const slot = (id: string, role: string, session: string, lead = false): TeamSlot => ({
  id, role, head: 'claude', model: null, account: null, lead, instructions: null, session,
  instructions_updated_epoch_millis: null, sessions_history: [session],
});

const TEAM: TeamRow = {
  id: 'team-1', name: 'atlas', goal: 'ship', features: [], repo: '/repo', archived: false,
  created_epoch_millis: NOW, updated_epoch_millis: NOW, idempotency_key: null, create_fingerprint: null,
  slots: [slot('claude', 'lead', 'aaaaaaaa-1', true), slot('grok', 'builder', 'bbbbbbbb-2')],
};

const message = (to: string, toSlot: string | null): TeamChatMessage => ({
  at: NOW, from: 'bbbbbbbb-2', from_slot: 'grok', from_head: 'claude', to, to_slot: toSlot, packet: null,
  text: 'done', text_source: null, missing_reason: null,
});

const chat = (...messages: TeamChatMessage[]): TeamChatPayload => ({
  team_id: 'team-1', day_start_epoch_millis: NOW, packet_note: 'No packet', messages,
});

describe('the recipient a hand-off prints', () => {
  const members = membersOf(TEAM, [], null, NOW);

  test('a socket the daemon could not file under a member reads as another session, never as a path', () => {
    const [printed] = messagesOf(members, chat(message(SOCKET, null)), TEAM) ?? [];
    expect(printed.to).toBe('Another session');
    expect(printed.to).not.toMatch(/[:/]/);
    expect(printed.toRole).toBeUndefined();
  });

  test('a socket the daemon filed under a member reads as that member, by role', () => {
    const [printed] = messagesOf(members, chat(message(SOCKET, 'claude')), TEAM) ?? [];
    expect(printed.toRole).toBe('lead');
    expect(printed.to).not.toContain('/');
  });

  test('a name the daemon filed under a member reads as that member', () => {
    const [printed] = messagesOf(members, chat(message('claude', 'claude')), TEAM) ?? [];
    expect(printed.toRole).toBe('lead');
  });

  test('a name the board does not rack keeps its own string', () => {
    const [printed] = messagesOf(members, chat(message('someone-else', null)), TEAM) ?? [];
    expect(printed.to).toBe('someone-else');
  });
});
