// A Fleet card rendered to markup: what it says in each state.
import { DndContext } from '@dnd-kit/core';
import { SortableContext } from '@dnd-kit/sortable';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { renderToStaticMarkup } from 'react-dom/server';
import { MemoryRouter } from 'react-router';
import { describe, expect, test } from 'vitest';
import type { FleetCard } from '../src/lib/fleet';
import type { AccountRow } from '../src/types/accounts';
import { AccountRowView } from '../src/pages/fleet/AccountRows';
import { FleetCardView } from '../src/pages/fleet/FleetCard';
import { FleetFix } from '../src/pages/fleet/FleetFix';
import type { HeadStatus } from '../src/types/core';
import { WindowBars } from '../src/pages/fleet/WindowBars';
import { localInstantText, localZonedInstantText } from '../src/lib/heads';

const card = (over: Partial<FleetCard> = {}): FleetCard => ({
  key: 'claude-grok', title: 'claude-grok', colour: 'grok', tone: 'work', standing: 'ready', state: 'Ready', attention: false,
  line: { kind: 'gauge', name: '5 hours', pct: 41, note: 'resets Oct 5, 4:40 PM', full: false }, meta: ['grok', 'Ava’s Grok', '2 sessions'], fix: null, none: null, ...over,
});
const render = (facts: FleetCard, fix: string | null = null) =>
  renderToStaticMarkup(
    <MemoryRouter>
      <DndContext>
        <SortableContext items={[facts.key]}>
          <ul>
            <FleetCardView facts={facts} fix={fix === null ? null : <button type="button">{fix}</button>} />
          </ul>
        </SortableContext>
      </DndContext>
    </MemoryRouter>,
  );

describe('a fleet card', () => {
  test('a command card offers named Move controls with the edge action disabled', () => {
    const facts = card();
    const html = renderToStaticMarkup(<MemoryRouter><DndContext><SortableContext items={[facts.key]}><FleetCardView facts={facts} fix={null} ordering={{ earlier: null, later: () => undefined }} /></SortableContext></DndContext></MemoryRouter>);
    expect(html).toMatch(/<button(?=[^>]*aria-label="Move claude-grok earlier")(?=[^>]*disabled="")[^>]*>/);
    expect(html).toContain('aria-label="Move claude-grok later"');
  });

  test('a ready card is a title that opens its page, a state, one window and one quiet line', () => {
    const html = render(card());
    expect(html).toContain('href="/models/claude-grok"');
    expect(html).toContain('Ready');
    expect(html).toContain('5 hours');
    expect(html).toContain('41%');
    expect(html).toContain('width:41%');
    expect(html).toContain('Ava’s Grok');
    expect(html).toContain('win grok');
    expect(html).not.toContain('class="acts"');
  });
  test('session launch guidance uses the daemon wrapper label, never the internal key or a runtime command', () => {
    const head: HeadStatus = {
      key: 'grok-internal', label: 'claude-grok', name: 'grok-internal', port: 1, authKind: 'grok-oauth',
      wantVersion: '1', running: true, healthy: true, version: '1', versionMatch: true, mode: null,
      gate: null, maxInflight: null, health: {} as HeadStatus['health'], pids: [],
    };
    const html = renderToStaticMarkup(
      <QueryClientProvider client={new QueryClient()}>
        <FleetFix fix="copy-launch" head={head} pool={[]} now={0} />
      </QueryClientProvider>,
    );
    expect(html).toContain('Start a session in your terminal: <code>claude-grok</code>');
    expect(html).toContain('Copy session command');
    expect(html).not.toContain('grok-internal');
    expect(html).not.toContain('rig up');
    expect(html).not.toContain('>Start<');
  });
  test('a window that has refused turns is drawn full, and a card that needs a person drops its hue and shows its one act', () => {
    const html = render(card({ attention: true, tone: 'quota', state: 'Out of quota until Oct 5, 2:13 PM', line: { kind: 'gauge', name: 'Week', pct: 100, note: 'out until Oct 5, 2:13 PM', full: true }, fix: 'switch' }), 'Switch account');
    expect(html).toContain('track full');
    expect(html).toContain('win grok attn');
    expect(html).toContain('Switch account');
    expect(html).toContain('Out of quota until Oct 5, 2:13 PM');
  });
  test('the quota card shows the retained observation rather than the page-open time', () => {
    const observed = 1_800_000_000;
    const html = render(card({ line: {
      kind: 'gauge', name: '5 hours', pct: 25, note: '', full: false, observedAt: observed,
    } }));
    expect(html).toContain('Observed ' + localZonedInstantText(observed));
  });
  test('a healthy head with nothing to draw has no glass block at all', () => {
    const html = render(card({ line: null, none: 'Pays per token; no window' }));
    expect(html).not.toContain('glass');
    expect(html).toContain('Pays per token; no window');
  });
  test('a card with nothing true to say about a window says nothing', () => {
    expect(render(card({ line: null, none: null }))).not.toContain('window');
  });
  test('a note stands where there is no window', () => {
    expect(render(card({ line: { kind: 'note', text: 'The runtime is not answering on :8099.' }, tone: 'idle', state: 'Runtime off' }))).toContain('The runtime is not answering on :8099.');
  });
});

const acct = (over: Partial<AccountRow> = {}): AccountRow => ({
  kind: 'chatgpt-oauth', label: 'work', single_login: false, credential_path: null, primary: false, selected: false, available: true,
  pinned: false, next_target: false, credential_present: true, windows: [], heads: ['claudex'], ...over,
});
const row = (account: AccountRow, pool: readonly AccountRow[] = [account], order: 'ready' | 'unavailable' | 'pending' | 'single' = 'ready') => {
  const client = new QueryClient();
  if (order !== 'pending') client.setQueryData(['account-order', account.heads[0]], order === 'unavailable'
    ? { unavailable: 'Synthetic command has no selectable account source' }
    : { head: account.heads[0], order: [], effective_order: ['work', 'home'], single_account: order === 'single' });
  return renderToStaticMarkup(
    <QueryClientProvider client={client}>
      <ul>
        <AccountRowView account={account} now={1_800_000_000_000} pooled pool={pool} />
      </ul>
    </QueryClientProvider>,
  );
};

describe('an account row', () => {
  const REFUSED = "'work' is a symbolic link, and splice does not load a linked credential; remove the link and sign in again";
  test('a loadable account offers Switch, and says nothing of a refusal', () => {
    const html = row(acct());
    expect(html).toContain('Switch to this one');
    expect(html).not.toContain('role="alert"');
  });
  test.each(['unavailable', 'pending', 'single'] as const)('a loadable account offers no Switch when selection is %s', order => {
    const account = acct({ can_remove: true, can_rename: true, edit_target: { kind: 'pool', id: 'work' } });
    const html = row(account, [account], order);
    expect(html).not.toContain('Switch to this one');
    expect(html).toContain('Rename');
    expect(html).toContain('Remove');
  });
  test('a refused account prints the daemon\'s sentence and offers no Switch, but keeps Rename and Remove', () => {
    const html = row(acct({ credential_present: false, refusal: REFUSED, can_remove: true, can_rename: true, edit_target: { kind: 'pool', id: 'work' } }));
    expect(html).toContain(REFUSED.replace(/'/g, '&#x27;'));
    expect(html).not.toContain('Switch to this one');
    expect(html).toContain('Rename');
    expect(html).toContain('Remove');
    expect(html).not.toContain('Its login file is gone');
    const denied = row(acct({ can_remove: false, can_rename: false, edit_target: { kind: 'pool', id: 'work' } }));
    expect(denied).not.toContain('>Rename<');
    expect(denied).not.toContain('>Remove<');
    const untargeted = row(acct({ can_remove: true, can_rename: true }));
    expect(untargeted).not.toContain('>Rename<');
    expect(untargeted).not.toContain('>Remove<');
  });
  test('the next marker does not invent a fallback cause while saved priority is unreported', () => {
    const primary = acct({ label: 'primary', primary: true, next_target: true });
    const other = acct({ label: 'spare' });
    const pool = [primary, other];
    expect(row(primary, pool)).toContain('<span class="tag">Next</span>');
    expect(row(primary, pool)).not.toContain('Next because it is the primary account.');
    expect(row(acct({ label: 'pin', pinned: true, next_target: true }), pool)).toContain('Next because it is the pinned account.');
    expect(row(other, pool)).not.toContain('Next because');
  });
  test('a spent primary the pool has stepped past says where turns go, in quiet words', () => {
    const primary = acct({ label: 'primary', primary: true, available: false, windows: [{ seconds: 604800, used_percent: 100, reset_epoch_seconds: 1_800_100_000 }] });
    const work = acct({ label: 'work', selected: true, windows: [{ seconds: 604800, used_percent: 20, reset_epoch_seconds: 1_800_100_000 }] });
    const html = row(primary, [primary, work]);
    expect(html).toContain('primary’s week is used; turns go to work');
    expect(html).not.toContain('spent 100%');
    expect(html).not.toContain('excluded');
    expect(html).not.toContain('role="alert"');
  });
  test('an excluded account prints the provider’s whole reason and offers no Switch', () => {
    const reason = 'Synthetic subscription is excluded until its provider accepts this login again.';
    const html = row(acct({ available: false, auth_excluded_until_epoch_millis: 1_800_000_000_000 + 3_600_000, auth_exclusion_reason: reason }));
    expect(html).toContain(reason);
    expect(html).not.toContain('Switch to this one');
    expect(row(acct({ available: false }))).toContain('Excluded by the pool, with no reason given.');
  });
  test('a stale daemon quota window is marked out of date, while a fresh one reads plainly', () => {
    const window = { seconds: 604800, used_percent: 59, reset_epoch_seconds: 1_800_100_000, observed_at_epoch_seconds: 1_799_900_000 };
    const stale = row(acct({ windows: [{ ...window, current: false }] }));
    expect(stale).toContain('Usage reading is out of date');
    expect(stale).not.toContain('7d 59%');
    const fresh = row(acct({ windows: [{ ...window, current: true }] }));
    expect(fresh).toContain('7d 59%');
    expect(fresh).not.toContain('Usage reading is out of date');
    const expired = row(acct({ windows: [{ ...window, current: true, reset_epoch_seconds: 1_799_999_999 }] }));
    expect(expired).toContain(`Window reset ${localZonedInstantText(1_799_999_999)}`);
    expect(expired).not.toContain('Window reset · Observed');
    expect(expired).not.toContain('7d 59%');
    expect(stale).not.toContain('Window reset');
  });
  test('an account whose login file is gone offers no Switch and says so', () => {
    const html = row(acct({ credential_present: false }));
    expect(html).not.toContain('Switch to this one');
    expect(html).toContain('Its login file is gone');
  });
});

describe('a command switch menu', () => {
  test.each(['ready', 'pending', 'unavailable', 'single'] as const)('it follows the same selectable-source evidence as account rows: %s', order => {
    const head: HeadStatus = {
      key: 'synthetic-command', label: 'Synthetic command', name: 'synthetic-command', port: 1, authKind: 'chatgpt-oauth',
      wantVersion: '1', running: true, healthy: true, version: '1', versionMatch: true, mode: null,
      gate: null, maxInflight: null, health: {} as HeadStatus['health'], pids: [],
    };
    const client = new QueryClient();
    if (order !== 'pending') client.setQueryData(['account-order', head.key], order === 'unavailable'
      ? { unavailable: 'Synthetic unavailable source' }
      : { head: head.key, order: [], effective_order: ['work', 'home'], single_account: order === 'single' });
    const html = renderToStaticMarkup(<QueryClientProvider client={client}><FleetFix fix="switch" head={head} pool={[acct({ heads: [head.key] })]} now={0} /></QueryClientProvider>);
    if (order === 'ready') expect(html).toContain('Switch account');
    else expect(html).not.toContain('Switch account');
  });
});

describe('a command page\'s plan windows', () => {
  test('an aged observation keeps its figure and reset without claiming the deadline already passed', () => {
    const now = Date.UTC(2026, 9, 5, 18, 0);
    const at = now / 1000 + 3600;
    const html = renderToStaticMarkup(<WindowBars windows={[{ window: '5h', pct: 12, resetsAt: at, observedAt: now / 1000 - 3600, stale: true }]} now={now} />);
    expect(html).toContain(`Last reading 12% · resets ${localZonedInstantText(at)} · Not current`);
    expect(html).toContain(`Observed ${localZonedInstantText(now / 1000 - 3600)}`);
    expect(html).not.toContain('has reset');
    expect(html).toContain('width:0%');
  });
  test('a reset reads in the viewer\'s own zone, the clock style Accounts and Usage use', () => {
    const now = Date.UTC(2026, 9, 5, 18, 0) ;
    const at = now / 1000 + 3600;
    const html = renderToStaticMarkup(<WindowBars windows={[{ window: '5h', pct: 41, resetsAt: at, observedAt: null, stale: false }]} now={now} />);
    expect(html).toContain(`resets ${localZonedInstantText(at)}`);
    expect(localZonedInstantText(at)).not.toBe(localInstantText(at));
  });
});
