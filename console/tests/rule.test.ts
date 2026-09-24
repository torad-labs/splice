// M2-09: the rule's two new signals.
//
// The window cell is checked against the ENTITY's derivation, on two payloads: the widget used to
// carry its own copy of `nearestWindow`, and a second copy is one that can silently disagree. The
// test builds the expected line from @entities/usage and asserts the rendered cell prints exactly
// that, so a widget that started deciding for itself would fail here rather than in a screenshot.
//
// The cells are rendered from props rather than through Rule(), because a static render sees a
// zustand store's initial state and never its current one (the page rows' boards take the same
// shape for the same reason).
import * as React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, test } from 'vitest';
import { headsReportingNone, nearestWindow } from '../src/entities/usage';
import type { AuthPayload, UsagePayload } from '../src/shared/api';
import { ConnectionCell, NoneCell, PendingRestartCell, WindowCell, healthOf } from '../src/widgets/rule';

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
 * Every word the window cell must print for a payload, built from the ENTITY's derivation. The cell
 * prints each in its own element, so the check is per part rather than on one concatenated string:
 * what matters is that the values are the entity's, not that they are adjacent in the markup.
 */
function expectedWindowParts(payload: UsagePayload | null): string[] {
  const nearest = nearestWindow(payload, auth);
  if (nearest === null) return ['no window reported'];
  return [
    nearest.head,
    nearest.window,
    String(nearest.pct),
    ...(nearest.account === null ? [] : [nearest.account]),
    ...(nearest.reset === null ? [] : [`resets ${nearest.reset}`]),
  ];
}

describe('the rule window cell', () => {
  const crowded = usagePayload([usageHead('claudex', 41), usageHead('claude-grok', 74), usageHead('claude-kimi', 12)]);
  const quiet = usagePayload([usageHead('claudex', 30, 'none'), usageHead('claude-grok', 0, 'none')]);

  test('prints the nearest window the entity derives, for a fleet where one head is ahead', () => {
    const out = render(h(WindowCell, { usage: crowded, auth }));
    expect(out).toContain('nearest window');
    for (const part of expectedWindowParts(crowded)) expect(out).toContain(part);
    expect(out).toContain('74'); // the highest percentage wins, not the first head
    expect(out).not.toContain('>41<');
  });

  test('a fleet where nobody reports a window prints the absence, never a zero', () => {
    const out = render(h(WindowCell, { usage: quiet, auth }));
    for (const part of expectedWindowParts(quiet)) expect(out).toContain(part);
    expect(out).toContain('no window reported');
    expect(out).not.toContain('>0<');
  });

  test('the none count agrees with the entity, and a route that has not answered prints nothing', () => {
    expect(render(h(NoneCell, { usage: quiet }))).toContain(String(headsReportingNone(quiet)));
    expect(render(h(NoneCell, { usage: null }))).toBe('');
  });
});

describe('the rule restart signal', () => {
  test('cocks amber with the count of saved-but-unread knobs', () => {
    const out = render(h(PendingRestartCell, { pending: ['maxInflight', 'debug'] }));
    expect(out).toContain('restart pending');
    expect(out).toContain('myx-edge-amber'); // the gesture, and the label prints beside it
    expect(out).toContain('>2<');
  });

  test('is gone when the store clears', () => {
    expect(render(h(PendingRestartCell, { pending: [] }))).toBe('');
  });
});

describe('the rule connection cell', () => {
  test('prints the state word beside its holder edge, in all three states', () => {
    const live = render(h(ConnectionCell, { status: 'live', lastFrameAt: Date.now() }));
    expect(live).toContain('>live<');
    expect(live).toContain('myx-edge-green');

    const reconnecting = render(h(ConnectionCell, { status: 'reconnecting', lastFrameAt: null }));
    expect(reconnecting).toContain('>reconnecting<');
    expect(reconnecting).toContain('myx-edge-amber');

    const off = render(h(ConnectionCell, { status: 'off', lastFrameAt: null }));
    expect(off).toContain('>off<');
    expect(off).toContain('myx-edge-grey');
  });

  test('a stream that has never delivered a frame says so, and never reads as an age', () => {
    const out = render(h(ConnectionCell, { status: 'live', lastFrameAt: null }));
    expect(out).toContain('no frame yet');
    expect(out).not.toContain('ago');
  });

  test('the age of the last frame is stale past the console-wide fifteen seconds', () => {
    const fresh = render(h(ConnectionCell, { status: 'live', lastFrameAt: Date.now() - 2_000 }));
    expect(fresh).not.toContain('>stale<');

    const old = render(h(ConnectionCell, { status: 'live', lastFrameAt: Date.now() - 60_000 }));
    expect(old).toContain('>stale<');
    // The state word still says the stream is up: a quiet daemon is not a dead one.
    expect(old).toContain('>live<');
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
