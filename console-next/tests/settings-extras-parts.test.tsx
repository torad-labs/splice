// The Claude command and upgrade rows rendered to markup from a seeded cache. The try-a-command form moved to the Playground (playground.test.tsx).
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { MemoryRouter } from 'react-router';
import { describe, expect, test } from 'vitest';
import { collapseChecks } from '../src/lib/doctor';
import { CheckMembers } from '../src/pages/settings/CheckMembers';
import { ClaudeHead } from '../src/pages/settings/ClaudeHead';
import { Compaction } from '../src/pages/settings/Compaction';
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
    expect(html).toContain('One login per Claude command at a time.');
  });
  test('wrapped offers an unwrap and prints the binary the shim runs, and a missing one as not read', () => {
    const html = render(<ClaudeHead />, seedCard(card({ mode: 'wrapped', real_binary_path: null })));
    expect(html).toContain('Wrapped');
    expect(html).toContain('<span class="state work"><i></i>Wrapped</span>');
    expect(html).toContain('>Unwrap<');
    expect(html).toContain('It runs');
    expect(html).toContain('Not read');
  });
  test('the daemon\'s sentence about logins prints as prose: no backticks, a capital to begin', () => {
    const html = render(<ClaudeHead />, seedCard(card({ claude_logins: { count: 1, selected: 'max', labels: ['max'], constraint: 'one login per Claude head at a time; `splice login <head> --label <name>` saves or switches it' } })));
    expect(html).toContain('One login per Claude command at a time; splice login &lt;head&gt; --label &lt;name&gt; saves or switches it');
    expect(html).not.toContain('`');
  });
  test.each([{ labels: ['synthetic-saved'] }, { labels: ['synthetic-saved', 'second-synthetic'] }])('saved labels $labels are separate from a missing last selection', ({ labels }) => {
    const html = render(<ClaudeHead />, seedCard(card({ claude_logins: { count: labels.length, selected: null, labels, constraint: '' } })));
    expect(html).toContain('Saved Claude login copies');
    expect(html).toContain('synthetic-saved');
    expect(html).toContain('Accounts shows the identities of the live logins');
    expect(html).toContain('Last saved copy selection');
    expect(html).toContain('No selection recorded');
    expect(html).not.toContain('No label recorded');
    expect(html).not.toContain('Launches with');
    expect(html).not.toContain('None chosen');
  });

  test('Tools reports live locations and delegates their edits to Accounts', () => {
    const common = { kind: 'claude-account', provider: 'anthropic', label: 'claude', credential_path: null, single_login: false, primary: false, selected: false, available: true, pinned: false, next_target: false, credential_present: true, windows: [], heads: ['claude-splice'] };
    const accounts = [
      { ...common, display_name: 'Personal login', identity_verified: true, account: { uuid: 'synthetic-shared', email: 'verified@example.invalid' }, can_remove: true, can_rename: true, edit_target: { kind: 'native', id: 'claude' } },
      { ...common, display_name: 'Work login', identity_verified: false, account: { uuid: 'synthetic-shared', email: 'unverified@example.invalid' }, can_remove: true, can_rename: false, edit_target: { kind: 'pool', id: 'claude' } },
    ];
    const html = render(<ClaudeHead />, client => {
      seedCard(card())(client);
      client.setQueryData(['accounts', '/api/accounts'], { accounts });
    });
    expect(html).toContain('<b>Personal login</b>');
    expect(html).toContain('<b>Work login</b>');
    expect(html).toContain('verified@example.invalid');
    expect(html).not.toContain('unverified@example.invalid');
    expect(html.match(/>Remove<\/button>/g) ?? []).toHaveLength(0);
    expect(html.match(/>Rename<\/button>/g) ?? []).toHaveLength(0);
    expect(html).toContain('href="/accounts"');
    expect(html).toContain('Open Accounts');
    expect(html).toContain('Saved Claude login copies');
  });

  test.each([
    { name: 'remove only', rename: false, remove: true, target: true, heads: ['claude-splice'], why: 'Remove the exact login on Accounts.' },
    { name: 'rename only', rename: true, remove: false, target: true, heads: ['claude-splice'], why: 'Rename the exact login on Accounts.' },
    { name: 'both', rename: true, remove: true, target: true, heads: ['claude-splice'], why: 'Rename or remove the exact login on Accounts.' },
    { name: 'neither', rename: false, remove: false, target: true, heads: ['claude-splice'], why: 'Live logins reported by Claude Code.' },
    { name: 'no edit target', rename: true, remove: true, target: false, heads: ['claude-splice'], why: 'Live logins reported by Claude Code.' },
    { name: 'no command', rename: true, remove: true, target: true, heads: [], why: 'Live logins reported by Claude Code.' },
  ])('live-login explanation matches the rendered actions: $name', ({ rename, remove, target, heads, why }) => {
    const html = render(<ClaudeHead />, client => {
      seedCard(card())(client);
      client.setQueryData(['accounts', '/api/accounts'], { accounts: [{
        kind: 'claude-account', label: 'synthetic-login', display_name: 'Synthetic live login',
        credential_present: true, heads, can_rename: rename, can_remove: remove,
        edit_target: target ? { kind: 'native', id: 'claude' } : null,
      }] });
    });
    expect(html).toContain(why);
    for (const action of ['Rename', 'Remove']) {
      expect(html.includes('>' + action + '</button>')).toBe(false);
    }
  });

  test('saved labels have context and the selection algorithm is prose behind a disclosure', () => {
    const constraint = 'Splice uses the account with quota whose weekly reset comes soonest.';
    const html = render(<ClaudeHead />, seedCard(card({ claude_logins: { count: 1, selected: 'max', labels: ['max'], constraint } })));
    expect(html).toContain('Saved as max');
    expect(html).toContain('<summary>How saved copies are chosen</summary>');
    expect(html).toContain('<p>' + constraint + '</p>');
    expect(html).not.toContain('<span>max</span>');
  });

  test('unread live logins do not promise an unavailable action', () => {
    const html = render(<ClaudeHead />, seedCard(card()));
    expect(html).toContain('Live logins reported by Claude Code.');
    expect(html).toContain('Reading live Claude logins.');
    expect(html).not.toContain('Rename or remove the exact login');
  });

  test('a claude that is not on the path says so rather than printing a blank', () => {
    expect(render(<ClaudeHead />, seedCard(card({ resolves_to: null, claude_logins: { count: 0, selected: null, labels: [], constraint: '' } })))).toContain('Nothing named claude');
  });
});

describe('the compaction report', () => {
  const report = (tail: { head: string; ts: number; outcome?: string; ms?: number }[]): string => render(<Compaction />, client => {
    client.setQueryData(['compact'], { stats: { total: tail.length, by_outcome: { model_text: tail.length }, tail } });
    client.setQueryData(['compaction-instructions'], { rules: [], unread: [] });
  });
  test('recent records have named time, command, outcome and duration columns', () => {
    const html = report([
      { head: 'synthetic-long-command', ts: 2, outcome: 'model_text', ms: 10_000 },
      { head: 'synthetic-other-command', ts: 1, outcome: 'empty_model' },
    ]);
    expect(html).toContain('<caption>Recent</caption>');
    for (const label of ['Time', 'Command', 'Outcome', 'Duration']) expect(html).toContain(`<th scope="col">${label}</th>`);
    expect(html).toContain('synthetic-long-command');
    expect(html).toContain('Summary written');
    expect(html).toContain('Empty reply');
    expect(html).toContain('Not reported');
    expect(html).toContain('<h4>How they ended</h4>');
  });
  test('an empty report keeps its explanation and has no invented recent records', () => {
    const html = report([]);
    expect(html).toContain('No compaction has run yet.');
    expect(html).not.toContain('<caption>Recent</caption>');
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
    expect(render(<Upgrade />, (client) => { client.setQueryData(['upgrade'], status); client.setQueryData(['upgrade-run'], { run: { ...run, state: 'running', exit_code: null }, away: true }); })).toContain('splice is restarting');
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
