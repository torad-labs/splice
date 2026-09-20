// V4-175 — WALLS for the claude-head slice's data layer, asserted through the REAL fetch path with
// a stubbed transport.
//
// The slice was written against a payload V4-129 was expected to serve and then did not: `head`,
// `config_dir`, `auth_kind`, `claude_on_path`, `wrap_supported` were declared here and never
// crossed the wire, while `resolves_to`, `real_binary_path` and `claude_logins` crossed it and
// nothing read them. That survived because every check in this repo took its denominator from the
// declaration it was checking — the page tests supplied their own fixture, and /api/claude-head was
// the one route missing from the daemon's WebuiContractTest.
//
// So these arms assert what a test CAN assert from this side: that the store carries the daemon's
// payload through unchanged, that an action re-reads rather than trusting the click, and that a
// refusal reaches the caller as the daemon's own sentence. The field NAMES are pinned against the
// daemon itself, in WebuiContractTest ("claude-head payload matches ClaudeHeadPayload") — the only
// place the denominator comes from outside.
import { afterEach, describe, expect, test, vi } from 'vitest';
import { fetchClaudeHead, unwrapClaudeHead, wrapClaudeHead } from '../src/entities/claude-head';
import type { ClaudeHeadPayload } from '../src/entities/claude-head';
// Reached by module path, as config-entity.test.ts reaches headOptions: the store is the slice's
// internal and stays off the entity's public surface, which a test must not widen to observe it.
import { claudeHeadStore } from '../src/entities/claude-head/model/store';
import { storeKey } from '../src/shared/api';

const LOGINS = {
  count: 2,
  selected: 'work',
  labels: ['personal', 'work'],
  constraint: 'one login per Claude head at a time, chosen at session launch; no mid-session switch',
};

const SEPARATE: ClaudeHeadPayload = {
  mode: 'separate',
  resolves_to: '/home/op/.local/share/claude/versions/2.1.257',
  shim_path: '/home/op/.local/share/splice/splice-launch',
  real_binary_path: null,
  claude_logins: LOGINS,
};

const WRAPPED: ClaudeHeadPayload = {
  mode: 'wrapped',
  resolves_to: '/home/op/.local/share/splice/splice-launch',
  shim_path: '/home/op/.local/share/splice/splice-launch',
  real_binary_path: '/home/op/.local/share/claude/versions/2.1.257',
  claude_logins: LOGINS,
};

function serves(body: unknown, init: { ok?: boolean; status?: number } = {}): typeof fetch {
  const calls: string[] = [];
  const stub = (async (path: string) => {
    calls.push(String(path));
    return { ok: init.ok ?? true, status: init.status ?? 200, json: async () => body };
  }) as unknown as typeof fetch;
  (stub as unknown as { calls: string[] }).calls = calls;
  return stub;
}

function callsOf(stub: typeof fetch): string[] {
  return (stub as unknown as { calls: string[] }).calls;
}

describe('the claude-head slice reads the daemon, not an expectation', () => {
  afterEach(() => vi.unstubAllGlobals());

  test('the payload lands in the store field for field', async () => {
    storeKey('k');
    vi.stubGlobal('fetch', serves(WRAPPED));
    await fetchClaudeHead();

    // Field-for-field, not a shape check: `real_binary_path` is what an unwrap restores from, and a
    // slice that dropped or renamed it would leave the console unable to say what it would restore.
    expect(claudeHeadStore.get().data).toEqual(WRAPPED);
    expect(claudeHeadStore.get().error).toBeNull();
  });

  // Mutant: keep the PendingRoute union. /api/claude-head is served (V4-129), so a 404 is a daemon
  // that stopped answering — reading it as "not built yet" would print a closed row id over a real
  // failure, and the operator would go looking for work that already shipped.
  test('a 404 is an error now, never a pending row', async () => {
    storeKey('k');
    vi.stubGlobal('fetch', serves({ error: 'unknown route' }, { ok: false, status: 404 }));
    await fetchClaudeHead();

    expect(claudeHeadStore.get().error).toBe('unknown route');
    expect(JSON.stringify(claudeHeadStore.get().data)).not.toContain('pending');
  });

  // Mutant: return the action's own answer and leave the card alone. The mode on screen has to be
  // the daemon's, not the one the click hoped for — a wrap that the daemon refused after the button
  // was pressed would otherwise leave the console claiming a lane that is not in force.
  test('wrap re-reads the card instead of trusting the click', async () => {
    storeKey('k');
    const stub = serves(WRAPPED);
    vi.stubGlobal('fetch', stub);

    const result = await wrapClaudeHead();

    expect(result.mode).toBe('wrapped');
    expect(callsOf(stub)).toEqual(['/api/claude-head/wrap', '/api/claude-head']);
    expect(claudeHeadStore.get().data).toEqual(WRAPPED);
  });

  test('unwrap re-reads the card the same way', async () => {
    storeKey('k');
    const stub = serves(SEPARATE);
    vi.stubGlobal('fetch', stub);

    await unwrapClaudeHead();

    expect(callsOf(stub)).toEqual(['/api/claude-head/unwrap', '/api/claude-head']);
    expect(claudeHeadStore.get().data).toEqual(SEPARATE);
  });

  // Mutant: swallow the refusal, or map it to a status. The control plane writes its reason as a
  // flat `{"error": "<sentence>"}` and the reason is the entire answer — "HTTP 409" is what the
  // console printed before V4-175, and it is nothing an operator can act on.
  test('a refused wrap throws the daemon own sentence', async () => {
    storeKey('k');
    vi.stubGlobal(
      'fetch',
      serves({ error: 'claude is not currently wrapped' }, { ok: false, status: 409 }),
    );

    await expect(wrapClaudeHead()).rejects.toThrow('claude is not currently wrapped');
  });

  test('an unconfigured head refuses with the head named', async () => {
    storeKey('k');
    const reason = "the 'claude-splice' head is not configured — wrap needs its catalog to materialize";
    vi.stubGlobal('fetch', serves({ error: reason }, { ok: false, status: 503 }));

    await expect(wrapClaudeHead()).rejects.toThrow(reason);
  });
});
