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
  test('a window that has refused turns is drawn full, and a card that needs a person drops its hue and shows its one act', () => {
    const html = render(card({ attention: true, tone: 'quota', state: 'Out of quota until Oct 5, 2:13 PM', line: { kind: 'gauge', name: 'Week', pct: 100, note: 'out until Oct 5, 2:13 PM', full: true }, fix: 'switch' }), 'Switch account');
    expect(html).toContain('track full');
    expect(html).toContain('win grok attn');
    expect(html).toContain('Switch account');
    expect(html).toContain('Out of quota until Oct 5, 2:13 PM');
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
const row = (account: AccountRow, pool: readonly AccountRow[] = [account]) =>
  renderToStaticMarkup(
    <QueryClientProvider client={new QueryClient()}>
      <ul>
        <AccountRowView account={account} now={1_800_000_000_000} pooled pool={pool} />
      </ul>
    </QueryClientProvider>,
  );

describe('an account row', () => {
  const REFUSED = "'work' is a symbolic link, and splice does not load a linked credential; remove the link and sign in again";
  test('a loadable account offers Switch, and says nothing of a refusal', () => {
    const html = row(acct());
    expect(html).toContain('Switch to this one');
    expect(html).not.toContain('role="alert"');
  });
  test('a refused account prints the daemon\'s sentence and offers no Switch, but keeps Rename and Remove', () => {
    const html = row(acct({ credential_present: false, refusal: REFUSED }));
    expect(html).toContain(REFUSED.replace(/'/g, '&#x27;'));
    expect(html).not.toContain('Switch to this one');
    expect(html).toContain('Rename');
    expect(html).toContain('Remove');
    expect(html).not.toContain('Its login file is gone');
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
  test('an account whose login file is gone offers no Switch and says so', () => {
    const html = row(acct({ credential_present: false }));
    expect(html).not.toContain('Switch to this one');
    expect(html).toContain('Its login file is gone');
  });
});
