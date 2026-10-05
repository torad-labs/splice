import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { renderToStaticMarkup } from 'react-dom/server';
import { MemoryRouter } from 'react-router';
import { expect, test } from 'vitest';
import { AccountCard } from '../src/pages/accounts/AccountCard';
import type { AccountRow } from '../src/types/accounts';

const now = Date.parse('2026-10-04T06:00:00Z');
const added: AccountRow = {
  kind: 'kimi-oauth', label: null, display_name: 'Synthetic login', single_login: true,
  credential_path: null, primary: true, selected: null, available: null, pinned: null,
  next_target: null, credential_present: true, windows: [], heads: ['synthetic-command'],
};

test.each([401, 403, 429])('a saved single login shows its newest refused request instead of Signed in: status=%s', status => {
  const row: AccountRow = { ...added, kind: 'kimi-oauth', label: null, single_login: true, available: null,
    last_refusal: { status, at_ms: now - 60_000 } };
  const html = renderToStaticMarkup(<QueryClientProvider client={new QueryClient()}><MemoryRouter>
    <AccountCard account={row} colour="kimi" now={now} />
  </MemoryRouter></QueryClientProvider>);
  expect(html).toContain('The last request was refused with ' + status);
  expect(html).not.toContain('>Signed in<');
  expect(html).not.toContain('>Refresh sign-in<');
  if (status === 401) expect(html).toContain('>Sign in again<');
  if (status === 403) expect(html).toContain('Review command access');
  if (status === 429) {
    expect(html).toContain('Wait for this login’s quota to reset.');
    expect(html).not.toContain('>Sign in again<');
  }
});

test.each([401, 429])('an API-key refusal offers only the remedy its status allows: status=%s', status => {
  const html = renderToStaticMarkup(<QueryClientProvider client={new QueryClient()}><MemoryRouter>
    <AccountCard account={{ ...added, kind: 'api-key', last_refusal: { status, at_ms: now } }} colour="none" now={now} />
  </MemoryRouter></QueryClientProvider>);
  expect(html).not.toContain('>Sign in again<');
  if (status === 401) expect(html).toContain('Review command access');
  else {
    expect(html).toContain('Wait for this login’s quota to reset.');
    expect(html).not.toContain('Review command access');
  }
});

test('a cleared last refusal restores the saved login state without claiming a stale failure', () => {
  const html = renderToStaticMarkup(<QueryClientProvider client={new QueryClient()}>
    <AccountCard account={{ ...added, last_refusal: null }} colour="claude" now={now} />
  </QueryClientProvider>);
  expect(html).toContain('>Signed in<');
  expect(html).not.toContain('The last request was refused');
});

