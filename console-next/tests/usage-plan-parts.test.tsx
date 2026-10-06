import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { renderToStaticMarkup } from 'react-dom/server';
import { MemoryRouter } from 'react-router';
import { expect, test } from 'vitest';
import { keys } from '../src/api/queries';
import { economicsKey } from '../src/api/usage';
import { localZonedInstantText } from '../src/lib/heads';
import { UsagePage } from '../src/pages/usage/UsagePage';

test('an empty Usage page explains its client-request scope and excluded Playground sends', () => {
  const client = new QueryClient();
  client.setQueryData([...economicsKey, '/api/economics'], { retention_hours: 24, heads: [] });
  client.setQueryData([...keys.heads, '/api/heads'], { heads: [] });
  const html = renderToStaticMarkup(<QueryClientProvider client={client}><MemoryRouter><UsagePage /></MemoryRouter></QueryClientProvider>);
  expect(html).toContain('This page counts client requests only.');
  expect(html).toContain('Playground sends are not counted on this page.');
});

const now = Math.floor(Date.now() / 1000);
const weekReset = now + 86400;

test.each([3600, -3600])('the Usage plan keeps both readings and gives a reset offset of %s the correct tense', offset => {
  const fiveReset = now + offset;
  const client = new QueryClient();
  client.setQueryData([...economicsKey, '/api/economics'], { retention_hours: 24, heads: [{ key: 'synthetic', label: 'Synthetic', ceiling_tokens: null, buckets: [] }] });
  client.setQueryData([...keys.heads, '/api/heads'], { heads: [] });
  client.setQueryData([...keys.usage, '/api/usage'], { window_hours: 5, warn_pct: 80, warn_tokens_5h: 0, heads: [{ key: 'synthetic', label: 'Synthetic', usage: {
    output_tokens_5h: 0, entries: 0, ratelimit: null, warn: { pct: 0, level: 'ok', source: 'none', reset: null },
    quota: { five_hour: { used_pct: 12, resets_at: fiveReset, observed_at: now - 60 }, seven_day: { used_pct: 65, resets_at: weekReset, observed_at: now - 60 } },
  } }] });
  const html = renderToStaticMarkup(<QueryClientProvider client={client}><MemoryRouter><UsagePage /></MemoryRouter></QueryClientProvider>);
  expect(html).toContain(offset > 0 ? `Short window · 12% · resets ${localZonedInstantText(fiveReset)}` : `Short window · Last reading 12% · reset ${localZonedInstantText(fiveReset)} · Not current`);
  expect(html).toContain(`Longer window · 65% · resets ${localZonedInstantText(weekReset)}`);
});
