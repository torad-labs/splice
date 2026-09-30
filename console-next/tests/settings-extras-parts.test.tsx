// The Claude command, upgrade and try-a-plan rows rendered to markup from a seeded cache.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { MemoryRouter } from 'react-router';
import { describe, expect, test } from 'vitest';
import { collapseChecks } from '../src/lib/doctor';
import { CheckMembers } from '../src/pages/settings/CheckMembers';
import { ClaudeHead } from '../src/pages/settings/ClaudeHead';
import { Playground } from '../src/pages/settings/Playground';
import { Upgrade } from '../src/pages/settings/Upgrade';
import type { ClaudeHeadPayload } from '../src/types/claude-head';
import type { DoctorCheck, UpgradePayload, UpgradeRun } from '../src/types/doctor';

const render = (element: ReactElement, seed: (client: QueryClient) => unknown): string => {
  const client = new QueryClient();
  seed(client);
  return renderToStaticMarkup(<QueryClientProvider client={client}><MemoryRouter>{element}</MemoryRouter></QueryClientProvider>);
};

const card = (over: Partial<ClaudeHeadPayload> = {}): ClaudeHeadPayload => ({
  mode: 'separate', resolves_to: '/home/a/.local/share/claude/versions/2.1', shim_path: '/home/a/.local/share/splice/splice-launch', real_binary_path: null,
  claude_logins: { count: 1, selected: 'max', labels: ['max'], constraint: 'One login per Claude head at a time.' }, ...over,
});
const seedCard = (payload: ClaudeHeadPayload) => (client: QueryClient) => client.setQueryData(['claude-head', '/api/claude-head'], payload);

describe('the claude command row', () => {
  test('separate offers a wrap, says where claude resolves, and hides the wrapped-only path', () => {
    const html = render(<ClaudeHead />, seedCard(card()));
    expect(html).toContain('Separate');
    expect(html).toContain('>Wrap<');
    expect(html).not.toContain('>Unwrap<');
    expect(html).toContain('/home/a/.local/share/claude/versions/2.1');
    expect(html).not.toContain('It runs');
    expect(html).toContain('One login per Claude head at a time.');
  });
  test('wrapped offers an unwrap and prints the binary the shim runs, and a missing one as not read', () => {
    const html = render(<ClaudeHead />, seedCard(card({ mode: 'wrapped', real_binary_path: null })));
    expect(html).toContain('Wrapped');
    expect(html).toContain('>Unwrap<');
    expect(html).toContain('It runs');
    expect(html).toContain('Not read');
  });
  test('the daemon\'s sentence about logins prints as prose: no backticks, a capital to begin', () => {
    const html = render(<ClaudeHead />, seedCard(card({ claude_logins: { count: 1, selected: 'max', labels: ['max'], constraint: 'one login per Claude head at a time; `splice login <head> --label <name>` saves or switches it' } })));
    expect(html).toContain('One login per Claude head at a time; splice login &lt;head&gt; --label &lt;name&gt; saves or switches it');
    expect(html).not.toContain('`');
  });
  test('a claude that is not on the path says so rather than printing a blank', () => {
    expect(render(<ClaudeHead />, seedCard(card({ resolves_to: null, claude_logins: { count: 0, selected: null, labels: [], constraint: '' } })))).toContain('Nothing named claude');
  });
});

describe('the upgrade rows', () => {
  const status: UpgradePayload = { installed: '0.3.2', latest: '0.4.0', latest_basis: 'measured', rollback_target: '0.3.1', rollback_basis: 'measured', rollback_unavailable_reason: null, checked_at_epoch_millis: 1 };
  const run: UpgradeRun = { id: 'r', args: ['upgrade'], state: 'succeeded', started_at_epoch_millis: 1, exit_code: 0, output: ['downloaded', 'installed'] };
  test('says what runs, that it is behind, and offers an upgrade and a rollback', () => {
    const html = render(<Upgrade />, (client) => client.setQueryData(['upgrade'], status));
    expect(html).toContain('Running 0.3.2. The newest release is 0.4.0.');
    expect(html).toContain('Behind');
    expect(html).toContain('>Upgrade<');
    expect(html).toContain('>Roll back<');
  });
  test('offers no rollback when none is on disk', () => {
    expect(render(<Upgrade />, (client) => client.setQueryData(['upgrade'], { ...status, rollback_target: null }))).not.toContain('>Roll back<');
  });
  test('a finished run prints its command, its state, its exit and what it printed', () => {
    const html = render(<Upgrade />, (client) => { client.setQueryData(['upgrade'], status); client.setQueryData(['upgrade-run'], { run, away: false }); });
    expect(html).toContain('splice upgrade');
    expect(html).toContain('Finished');
    expect(html).toContain('Exit 0');
    expect(html).toContain('downloaded');
    expect(html).toContain('Reload this page');
  });
  test('a run read while splice restarts says it is restarting', () => {
    expect(render(<Upgrade />, (client) => { client.setQueryData(['upgrade'], status); client.setQueryData(['upgrade-run'], { run: { ...run, state: 'running', exit_code: null }, away: true }); })).toContain('Splice is restarting');
  });
});

describe('try a plan', () => {
  test('starts with nothing sent, the send held until a plan and a prompt exist', () => {
    const html = render(<Playground />, (client) => client.setQueryData(['heads', '/api/heads'], { heads: [{ key: 'claude-grok', label: 'Grok', authKind: 'grok' }] }));
    expect(html).toContain('Try a plan');
    expect(html).toContain('Choose a plan');
    expect(html).toMatch(/<button[^>]*disabled[^>]*>Send<\/button>/);
    expect(html).not.toContain('Answered with');
  });
});

describe('a check row that folds several checks', () => {
  const check = (detail: string, id = 'installation/wrapper'): DoctorCheck => ({ id, status: 'warn', detail, fix: 'splice install --all' });
  const html = (checks: DoctorCheck[]): string => {
    const [row] = collapseChecks(checks);
    if (row === undefined) throw new Error('no row');
    return renderToStaticMarkup(<CheckMembers row={row} />);
  };
  test('keeps every member\'s own finding reachable, not only the first and a count', () => {
    const out = html([check('claude-a is not linked'), check('claude-b is not linked'), check('claudex points at another copy'), check('claude-d is not linked')]);
    expect(out).toContain('Show all 4 findings');
    for (const detail of ['claude-a is not linked', 'claude-b is not linked', 'claudex points at another copy', 'claude-d is not linked']) expect(out).toContain(detail);
  });
  test('names the head of a member whose id carries one', () => {
    const out = html([check('no prompt', 'configuration/system-prompt:claude-a'), check('no prompt', 'configuration/system-prompt:claude-b')]);
    expect(out).toContain('<b>claude-a </b>');
    expect(out).toContain('<b>claude-b </b>');
  });
  test('a row of one check has nothing more to show', () => {
    expect(html([check('one')])).toBe('');
  });
});
