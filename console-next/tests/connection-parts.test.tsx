import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { renderToStaticMarkup } from 'react-dom/server';
import { MemoryRouter } from 'react-router';
import { expect, test, vi } from 'vitest';
import { Shell } from '../src/app/Shell';

vi.mock('../src/lib/theme', () => ({ useTheme: () => 'day', setTheme: vi.fn() }));
vi.mock('../src/app/StalePage', () => ({ useStalePage: () => false, StaleBanner: () => null }));

test('the shell reports a pending daemon check rather than claiming the daemon is running or not answering', () => {
  const html = renderToStaticMarkup(<QueryClientProvider client={new QueryClient()}><MemoryRouter><Shell /></MemoryRouter></QueryClientProvider>);
  expect(html).toContain('Checking daemon');
  expect(html).not.toContain('Daemon running');
  expect(html).not.toContain('Not answering');
});
