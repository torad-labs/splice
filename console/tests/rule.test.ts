// M2-09: the rule's two new signals.
//
// The window cell is checked against the ONE derivation, on two payloads: the widget used to carry
// its own copy of `nearestWindow`, and a second copy is one that can silently disagree. The test
// builds the expected line from @features/nearest-limit and asserts the rendered cell prints exactly
// that, so a widget that started deciding for itself would fail here rather than in a screenshot.
//
// The cells are rendered from props rather than through Rule(), because a static render sees a
// zustand store's initial state and never its current one (the page rows' boards take the same
// shape for the same reason).
import * as React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, test } from 'vitest';
import { headsReportingNone } from '../src/entities/usage';
import { nearestLimit } from '../src/features/nearest-limit';
import type { AuthPayload, UsagePayload } from '../src/shared/api';
import { ConnectionCell, HealthCell, LINK_SILENT_MS, PendingRestartCell, WindowCell, healthOf, limitsOf, strandsOf } from '../src/widgets/rule';
import type { HeadStatus } from '../src/shared/api';

const h = React.createElement;
const render = (el: React.ReactElement): string => renderToStaticMarkup(el);

/** One head's entry in /api/usage. `source: 'none'` is the daemon saying it has no window, which is
 *  not a window at zero. */
function usageHead(key: string, pct: number, source: 'quota' | 'none' = 'quota') {
  return {
    key,
    label: key,
    usage: {
      output_tokens_5h: 1000,
      entries: 4,
      ratelimit: null,
      warn: { level: 'ok' as const, pct, source, reset: '16:40' },
    },
  };
}

function usagePayload(heads: ReturnType<typeof usageHead>[]): UsagePayload {
  return { window_hours: 5, warn_pct: 80, warn_tokens_5h: 1_000_000, heads };
}

const auth: AuthPayload = {
  claudex: { kind: 'chatgpt-oauth', login: 'oauth', present: true, account_id_masked: 'acct-a' },
  'claude-grok': { kind: 'grok-oauth', login: 'oauth', present: true },
};

/**
 * Every word the window cell must print for a payload, built from the ONE derivation. The cell
 * prints each in its own element, so the check is per part rather than on one concatenated string:
 * what matters is that the values are the entity's, not that they are adjacent in the markup.
 */
function expectedWindowParts(payload: UsagePayload | null): string[] {
  const nearest = nearestLimit({ accounts: [], usage: payload, auth }, Date.now());
  if (nearest === null) return ['No plan limits'];
  return [
    ...(nearest.head === null ? [] : [nearest.head]),
    nearest.window,
    `${nearest.pct}%`,
    ...(nearest.account === null ? [] : [nearest.account]),
    ...(nearest.reset === null ? [] : [nearest.reset]),
  ];
}

describe('the rule window cell', () => {
  const crowded = usagePayload([usageHead('claudex', 41), usageHead('claude-grok', 74), usageHead('claude-kimi', 12)]);
  const quiet = usagePayload([usageHead('claudex', 30, 'none'), usageHead('claude-grok', 0, 'none')]);

  test('prints the nearest window the entity derives, for a fleet where one head is ahead', () => {
    const out = render(h(WindowCell, { accounts: [], usage: crowded, auth, limits: 'read' }));
    expect(out).toContain('Closest plan limit'); // the glyph's name, and its tip
    expect(out).toContain('role="meter"'); // the share has its shape as well as its figure
    for (const part of expectedWindowParts(crowded)) expect(out).toContain(part);
    expect(out).toContain('74'); // the highest percentage wins, not the first head
    expect(out).not.toContain('>41<');
  });

  test('the login method is not an account: a head with no account id prints none', () => {
    // claude-grok wins at 74 and its auth card has `login: 'oauth'` and no masked id.
    expect(nearestLimit({ accounts: [], usage: crowded, auth }, Date.now())?.account).toBeNull();
    expect(render(h(WindowCell, { accounts: [], usage: crowded, auth, limits: 'read' }))).not.toContain('oauth');
  });

  test('a fleet where nobody reports a window prints the absence, never a zero', () => {
    const out = render(h(WindowCell, { accounts: [], usage: quiet, auth, limits: 'read' }));
    for (const part of expectedWindowParts(quiet)) expect(out).toContain(part);
    expect(out).toContain('No plan limits');
    expect(out).not.toContain('>0<');
  });

  test('the none count agrees with the entity and rides in the tip, and an unanswered route adds none', () => {
    expect(render(h(WindowCell, { accounts: [], usage: quiet, auth, limits: 'read' }))).toContain(`Closest plan limit, ${headsReportingNone(quiet)} without limits`);
    expect(render(h(WindowCell, { accounts: [], usage: null, auth, limits: 'reading' }))).not.toContain('without limits');
  });

  // Marlin, 2026-09-25: the cell printed "No plan limits" before the usage read had answered. No
  // limit found is a finding only once every read it rests on answered.
  test('before the reads answer, it says it is reading; after one failed, that the limits are unread', () => {
    const reading = render(h(WindowCell, { accounts: [], usage: null, auth, limits: 'reading' }));
    expect(reading).toContain('Reading limits');
    expect(reading).not.toContain('No plan limits');
    const unread = render(h(WindowCell, { accounts: [], usage: null, auth, limits: 'unread' }));
    expect(unread).toContain('Limits unread');
    expect(unread).not.toContain('No plan limits');
  });

  test('the limits are read only once usage and accounts both answered; a failure with one out is unread', () => {
    expect(limitsOf(true, true, false)).toBe('read');
    expect(limitsOf(true, true, true)).toBe('read'); // a later poll failed; the answer held stands
    expect(limitsOf(true, false, false)).toBe('reading');
    expect(limitsOf(false, true, false)).toBe('reading');
    expect(limitsOf(false, false, false)).toBe('reading');
    expect(limitsOf(false, true, true)).toBe('unread');
    expect(limitsOf(true, false, true)).toBe('unread');
  });

  test('a limit that was found prints, whatever read is still out', () => {
    const out = render(h(WindowCell, { accounts: [], usage: crowded, auth, limits: 'reading' }));
    expect(out).toContain('74');
    expect(out).not.toContain('Reading limits');
  });
});

describe('the rule restart signal', () => {
  test('reads warn with the count of saved-but-unread knobs', () => {
    const out = render(h(PendingRestartCell, { pending: ['maxInflight', 'debug'] }));
    expect(out).toContain('Restart pending');
    expect(out).toContain('myx-badge-warn'); // the dot, and the word prints beside it
    expect(out).toContain('>2<');
  });

  test('is gone when the store clears', () => {
    expect(render(h(PendingRestartCell, { pending: [] }))).toBe('');
  });
});

describe('the rule connection cell', () => {
  test('prints the state word beside its status dot, in all three states', () => {
    const live = render(h(ConnectionCell, { status: 'live', lastFrameAt: Date.now() }));
    expect(live).toContain('>Live<');
    expect(live).toContain('myx-badge-ok');

    const reconnecting = render(h(ConnectionCell, { status: 'reconnecting', lastFrameAt: null }));
    expect(reconnecting).toContain('>Reconnecting<');
    expect(reconnecting).toContain('myx-badge-warn');

    const off = render(h(ConnectionCell, { status: 'off', lastFrameAt: null }));
    expect(off).toContain('>Offline<');
    expect(off).toContain('myx-badge-neutral');
  });

  test('a stream that has never delivered a frame says so, and never reads as an age', () => {
    const out = render(h(ConnectionCell, { status: 'live', lastFrameAt: null }));
    expect(out).toContain('No events yet');
    expect(out).not.toContain('ago');
  });

  test('an old event on a link that still beats is not stale: a quiet daemon is not a dead one', () => {
    // Walkthrough S13: the cell turned stale 15 s after the last EVENT, so every quiet, healthy
    // stream read stale. The heartbeat is what says the link is alive.
    const now = 1_790_000_000_000;
    const quiet = render(h(ConnectionCell, { status: 'live', lastFrameAt: now - 120_000, lastBeatAt: now - 5_000, now }));
    expect(quiet).not.toContain('Link silent');
    expect(quiet).toContain('>Live<');
    expect(quiet).toContain('Last event 2m ago'); // the age is detail, in the tip

    const silent = render(h(ConnectionCell, { status: 'live', lastFrameAt: now - 120_000, lastBeatAt: now - LINK_SILENT_MS - 1, now }));
    // A socket still open past its heartbeat is a link in doubt: one state, warn, in words.
    expect(silent).toContain('>Link silent<');
    expect(silent).toContain('myx-badge-warn');
    expect(silent).not.toContain('>Live<');
  });
});

describe('health', () => {
  test('a failed status read is red, a down head is amber, otherwise green', () => {
    expect(healthOf(true, true, false)).toBe('red');
    expect(healthOf(false, true, false)).toBe('amber');
    expect(healthOf(false, false, false)).toBe('green');
  });

  test('a locked console says the key is missing, not that the daemon is unreachable', () => {
    // the status read fails with a 401 while locked: the daemon answered, so red would be false
    expect(healthOf(true, false, true)).toBe('grey');
  });
});

describe('the health cell', () => {
  test('a fine daemon prints its name beside the check, a troubled one prints the trouble', () => {
    expect(render(h(HealthCell, { health: 'green' }))).toContain('<span>Daemon</span>');
    expect(render(h(HealthCell, { health: 'amber' }))).toContain('<span>Daemon degraded</span>');
    expect(render(h(HealthCell, { health: 'red' }))).toContain('<span>Daemon unreachable</span>');
  });
});

describe('the braid', () => {
  const head = (key: string, running: boolean, inflight: number, released: number): HeadStatus => ({
    key, label: `${key}-label`, name: key, port: 1, authKind: 'x', wantVersion: '', running, healthy: true,
    version: null, versionMatch: null, mode: null, maxInflight: null, pids: [],
    health: {} as HeadStatus['health'],
    gate: { inflight, queued: 0, max: 'unlimited', acquired: 0, released, waited: 0, avg_wait_ms: 0, live: [], stream_idle_ms: 0 },
  });

  test('one strand per running head, as long as its turns in flight, in its registry hue', () => {
    const strands = strandsOf([head('a', true, 2, 9), head('b', false, 0, 0), head('c', true, 0, 4)], (key) => (key === 'a' ? 1 : 3));
    expect(strands).toEqual([
      { key: 'a', name: 'a-label', hue: 'myx-hue-1', count: 2, landed: 9 },
      { key: 'c', name: 'c-label', hue: 'myx-hue-3', count: 0, landed: 4 },
    ]);
  });

  test('no heads read yet is no strands, never a strand at zero', () => {
    expect(strandsOf(null, () => 0)).toEqual([]);
  });
});
