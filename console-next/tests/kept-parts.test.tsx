// What a turn keeps, rendered from a seeded cache: the failure sentence under its own turn, and the body capture control.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { renderToStaticMarkup } from 'react-dom/server';
import { MemoryRouter } from 'react-router';
import { describe, expect, test } from 'vitest';
import { captureState, savedCapture } from '../src/lib/turns-page';
import { CaptureControl } from '../src/pages/turns/CaptureControl';
import { KeptTabs } from '../src/pages/turns/KeptTabs';
import { PlansKept } from '../src/pages/settings/Kept';
import type { CaptureWire, KeptTurn, TurnRow } from '../src/types/perf';
import type { TopologyState } from '../src/types/topology';

const row = (turn: string): TurnRow => ({ head: 'claude-solo', ts: 1_000_000, model: 'm', outcome: 'error:conn-reset', compact: false, turn });
const kept = (id: string, sentence: string | null): KeptTurn => ({
  read: { head: 'claude-solo', turn: { id, ts: 1, session: null, model: 'm', compact: false, open: false, outcome: 'error:conn-reset', failure_sentence: sentence, rounds: 1, attempts: 1, total_ms: 20 }, records: [] },
});
const capture = (enabled: boolean): CaptureWire => ({ head: 'claude-solo', enabled, retention_days: 7, max_body_chars: 1000, restart_required: true });
const page = (element: React.ReactElement, seed: (client: QueryClient) => void): string => {
  const client = new QueryClient();
  seed(client);
  return renderToStaticMarkup(<QueryClientProvider client={client}><MemoryRouter>{element}</MemoryRouter></QueryClientProvider>);
};

describe('a failed turn\'s sentence', () => {
  const seed = (client: QueryClient): void => {
    client.setQueryData(['trace', 'claude-solo', 'a'], kept('a', 'the connection for a closed mid-request; retry'));
    client.setQueryData(['trace', 'claude-solo', 'b'], kept('b', 'the connection for b closed mid-request; retry'));
  };
  test('is printed whole, and each turn prints the one its own read holds, whichever read answered last', () => {
    const b = page(<KeptTabs row={row('b')} plan="Solo" tab={null} />, seed);
    expect(b).toContain('<p class="failure-sentence">the connection for b closed mid-request; retry</p>');
    expect(b).not.toContain('the connection for a');
    const a = page(<KeptTabs row={row('a')} plan="Solo" tab={null} />, seed);
    expect(a).toContain('the connection for a closed mid-request; retry');
    expect(a).not.toContain('the connection for b');
  });
  test('is absent when the daemon recorded none, and when the trace no longer holds the turn', () => {
    const none = page(<KeptTabs row={row('a')} plan="Solo" tab={null} />, (client) => client.setQueryData(['trace', 'claude-solo', 'a'], kept('a', null)));
    expect(none).not.toContain('failure-sentence');
    const gone = page(<KeptTabs row={row('a')} plan="Solo" tab={null} />, (client) => client.setQueryData(['trace', 'claude-solo', 'a'], { gone: 'no such turn' }));
    expect(gone).not.toContain('failure-sentence');
  });
});

describe('the body capture control', () => {
  const file = (trace: string | null): TopologyState => ({ path: '/x/splice.toml', stale: false, topology: { heads: { 'claude-solo': { overrides: trace === null ? {} : { trace } } } } });
  const control = (enabled: boolean | null, saved: string | null = null): string => page(<CaptureControl head="claude-solo" plan="Solo" />, (client) => {
    if (enabled !== null) client.setQueryData(['capture', 'claude-solo'], capture(enabled));
    if (saved !== null) client.setQueryData(['topology'], file(saved));
  });
  test('is a switch named Body capture that shows what the daemon records now', () => {
    expect(control(false)).toMatch(/role="switch" aria-checked="false" aria-label="Body capture"/);
    const on = control(true);
    expect(on).toMatch(/aria-checked="true"/);
    expect(on).toContain('Recording bodies for Solo.');
    expect(on).not.toContain('Restart to apply');
  });
  test('reads what was saved from splice.toml, so the pending restart is there on any turn\'s page and after a reload', () => {
    const waiting = control(false, 'true');
    expect(waiting).toMatch(/aria-checked="true"/);
    expect(waiting).toContain('Restart to apply. Capture will be on after splice restarts.');
    expect(waiting).toContain('Restart splice');
    expect(waiting).not.toContain('Recording bodies');
    const off = control(true, 'false');
    expect(off).toMatch(/aria-checked="false"/);
    expect(off).toContain('Capture will be off after splice restarts.');
    expect(control(true, 'true')).not.toContain('Restart to apply');
  });
  test('the saved value is the file\'s own trace text for that head, and nothing when the file sets none', () => {
    expect(savedCapture(file('true'), 'claude-solo')).toBe(true);
    expect(savedCapture(file('false'), 'claude-solo')).toBe(false);
    expect(savedCapture(file(null), 'claude-solo')).toBeNull();
    expect(savedCapture(file('true'), 'other')).toBeNull();
    expect(savedCapture({ pending: 'no route' } as unknown as TopologyState, 'claude-solo')).toBeNull();
    expect(savedCapture(undefined, 'claude-solo')).toBeNull();
  });
  test('draws nothing until the daemon has said', () => {
    expect(control(null)).toBe('');
  });
  test('shows what was saved against what runs, and a restart waiting when they differ', () => {
    expect(captureState(false, null)).toEqual({ on: false, recording: false, pending: false });
    expect(captureState(false, true)).toEqual({ on: true, recording: false, pending: true });
    expect(captureState(true, false)).toEqual({ on: false, recording: true, pending: true });
    expect(captureState(true, true)).toEqual({ on: true, recording: true, pending: false });
    expect(captureState(false, false)).toEqual({ on: false, recording: false, pending: false });
    expect(captureState(null, null).on).toBe(false);
  });
});

describe('the plans that keep activity labels', () => {
  const plans = [{ key: 'claudex', label: 'Claudex' }, { key: 'bonsai', label: 'Bonsai' }];
  const control = (value: string): string => renderToStaticMarkup(<PlansKept value={value} plans={plans} onSave={() => undefined} />);
  test('is a switch per plan under a summary, never a text box of plan keys', () => {
    const html = control('claudex');
    expect(html).toContain('1 of 2 plans · Choose plans');
    expect(html).toMatch(/aria-checked="true" aria-label="Keep activity labels for Claudex"/);
    expect(html).toMatch(/aria-checked="false" aria-label="Keep activity labels for Bonsai"/);
    expect(html).not.toContain('<input');
  });
  test('* reads as every plan and empty as none', () => {
    expect(control('*')).toContain('Every plan · Choose plans');
    expect(control('')).toContain('No plan · Choose plans');
  });
});
