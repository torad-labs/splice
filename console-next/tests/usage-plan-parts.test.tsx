import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { renderToStaticMarkup } from 'react-dom/server';
import { MemoryRouter } from 'react-router';
import { expect, test } from 'vitest';
import { keys } from '../src/api/queries';
import { economicsKey } from '../src/api/usage';
import { localZonedInstantText } from '../src/lib/heads';
import { UsagePage } from '../src/pages/usage/UsagePage';

const now = Math.floor(Date.now() / 1000);
const fiveReset = now + 3600;
const weekReset = now + 86400;

test('the Usage plan shows both readings and resets when the week is the fullest window', () => {
  const client = new QueryClient();
  client.setQueryData([...economicsKey, '/api/economics'], { retention_hours: 24, heads: [{ key: 'synthetic', label: 'Synthetic', ceiling_tokens: null, buckets: [] }] });
  client.setQueryData([...keys.heads, '/api/heads'], { heads: [] });
  client.setQueryData([...keys.usage, '/api/usage'], { window_hours: 5, warn_pct: 80, warn_tokens_5h: 0, heads: [{ key: 'synthetic', label: 'Synthetic', usage: {
    output_tokens_5h: 0, entries: 0, ratelimit: null, warn: { pct: 0, level: 'ok', source: 'none', reset: null },
    quota: { five_hour: { used_pct: 12, resets_at: fiveReset, observed_at: now - 60 }, seven_day: { used_pct: 65, resets_at: weekReset, observed_at: now - 60 } },
  } }] });
  const html = renderToStaticMarkup(<QueryClientProvider client={client}><MemoryRouter><UsagePage /></MemoryRouter></QueryClientProvider>);
  expect(html).toContain(`5 hours · 12% · resets ${localZonedInstantText(fiveReset)}`);
  expect(html).toContain(`Week · 65% · resets ${localZonedInstantText(weekReset)}`);
});
