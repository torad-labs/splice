import { describe, expect, test } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import { AccountLimits } from '../src/pages/accounts/AccountLimits';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AccountCard } from '../src/pages/accounts/AccountCard';
import type { AccountRow } from '../src/types/accounts';

const reset = Date.parse('2026-10-04T13:00:00Z') / 1000;
const now = Date.parse('2026-10-03T18:00:00Z');
const saved: AccountRow = {
  kind: 'chatgpt-oauth', label: 'synthetic-work', single_login: false,
  credential_path: '/synthetic/auth-work.json', primary: false,
  selected: false, available: false, pinned: false, next_target: false,
  credential_present: false, windows: [], heads: ['synthetic-head'],
};

describe('account limit facts', () => {
  test('a missing saved credential keeps renewal instead of becoming an unnamed new account', () => {
    const html = renderToStaticMarkup(<QueryClientProvider client={new QueryClient()}>
      <AccountCard account={saved} colour="gpt" now={now} />
    </QueryClientProvider>);
    expect(html).toContain('Not signed in');
    expect(html).toContain('Sign in again');
    expect(html).toContain('synthetic-work');
    expect(html).not.toContain('Refresh login');
  });

  test('a full reading does not invent a held account when the daemon says it can serve', () => {
    const html = renderToStaticMarkup(<QueryClientProvider client={new QueryClient()}>
      <AccountCard account={{ ...saved, credential_present: true, held: false,
        windows: [{ seconds: 18_000, used_percent: 100, reset_epoch_seconds: reset }],
      }} colour="gpt" now={now} />
    </QueryClientProvider>);
    expect(html).toContain('100% used');
    expect(html).toContain('Signed in');
    expect(html).not.toContain('Limit reached');
    expect(html).not.toContain('data-full');
  });

  test('a refused credential keeps its reason and cannot offer an impossible renewal', () => {
    const html = renderToStaticMarkup(<QueryClientProvider client={new QueryClient()}>
      <AccountCard account={{ ...saved, refusal: 'Synthetic credential path is a link.' }} colour="gpt" now={now} />
    </QueryClientProvider>);
    expect(html).toContain('Synthetic credential path is a link.');
    expect(html).toMatch(/<button[^>]*disabled=""[^>]*>Sign in again<\/button>/);
  });

  test('an authentication exclusion keeps its full reason and does not look ready to serve', () => {
    const html = renderToStaticMarkup(<QueryClientProvider client={new QueryClient()}>
      <AccountCard account={{ ...saved, credential_present: true,
        auth_excluded_until_epoch_millis: now + 60_000,
        auth_exclusion_reason: 'Synthetic provider refuses this subscription until its login is accepted.',
      }} colour="gpt" now={now} />
    </QueryClientProvider>);
    expect(html).toContain('Temporarily excluded');
    expect(html).toContain('Synthetic provider refuses this subscription until its login is accepted.');
    expect(html).not.toContain('Signed in');
  });

  test('unreported windows never become zero-percent accounts', () => {
    const html = renderToStaticMarkup(<AccountLimits windows={[]} now={now} />);
    expect(html).toContain('5 hours');
    expect(html).toContain('Weekly');
    expect(html).toContain('Not reported');
    expect(html).not.toContain('0% used');
    expect(html).not.toContain('role="img"');
  });

  test('a provider-reported thirty-day window is not labelled weekly', () => {
    const html = renderToStaticMarkup(<AccountLimits windows={[{
      seconds: 2_592_000, used_percent: 72, reset_epoch_seconds: reset,
    }]} now={now} />);
    expect(html).toContain('30 days');
    expect(html).toContain('72% used');
    expect(html).not.toContain('Weekly');
    expect(html).toContain('Oct 4, 8:00 AM CT');
  });

  test('a previous window keeps its reading visibly stale instead of looking like current capacity', () => {
    const html = renderToStaticMarkup(<AccountLimits windows={[{
      seconds: 604_800, used_percent: 100, reset_epoch_seconds: reset, current: false,
    }]} now={now} />);
    expect(html).toContain('100% used');
    expect(html).toContain('Previous window');
    expect(html).toContain('data-stale=""');
  });

  test('separate Claude model limits retain their own model and reset', () => {
    const html = renderToStaticMarkup(<AccountLimits windows={[
      { seconds: 604_800, used_percent: 91, reset_epoch_seconds: reset, model: 'opus' },
      { seconds: 604_800, used_percent: 17, reset_epoch_seconds: reset + 3600, model: 'sonnet' },
    ]} now={now} />);
    expect(html).toContain('Weekly · opus');
    expect(html).toContain('Weekly · sonnet');
    expect(html).toContain('91% used');
    expect(html).toContain('17% used');
    expect(html).toContain('Oct 4, 9:00 AM CT');
  });
});
