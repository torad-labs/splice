import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { renderToStaticMarkup } from 'react-dom/server';
import { MemoryRouter } from 'react-router';
import { expect, test, vi } from 'vitest';
import { keys } from '../src/api/queries';
import { economicsKey } from '../src/api/usage';
import { localZonedInstantText } from '../src/lib/heads';
import { UsagePage } from '../src/pages/usage/UsagePage';
import type { EconomicsBucket } from '../src/types/economics';
import type { TurnUsageWire } from '../src/types/perf';

test('an empty Usage page explains its client-request scope and excluded Playground sends', () => {
  const client = new QueryClient();
  client.setQueryData([...economicsKey, '/api/economics'], { retention_hours: 24, heads: [] });
  client.setQueryData([...keys.heads, '/api/heads'], { heads: [] });
  const html = renderToStaticMarkup(<QueryClientProvider client={client}><MemoryRouter><UsagePage /></MemoryRouter></QueryClientProvider>);
  expect(html).toContain('This page counts client requests only.');
  expect(html).toContain('Playground sends are not counted on this page.');
});

function hourlyPage(missing: number | undefined, ago = 1, fields: Partial<EconomicsBucket> = {}): string {
  const at = Date.now();
  const hour = Math.floor(at / 3_600_000) * 3_600_000;
  const extra = missing === undefined ? {} : { unreported_usage_turns: missing };
  const bucket: EconomicsBucket = {
    hour: hour - ago * 3_600_000, turns: 5, in_tokens: 1000, cached_tokens: 0, cache_write_tokens: 0,
    out_tokens: 200, req_bytes: 0, upstream_req_bytes: 0, tools_eager: 0, tools_deferred: 0, deferral_turns: 0,
    rate_limited: 0, cost_usd: 2, unpriced_turns: 3, ...extra, ...fields,
  };
  const recent = { ...bucket, hour: hour - 3_600_000, turns: 1, unpriced_turns: 0, unreported_usage_turns: 0 };
  const client = new QueryClient();
  client.setQueryData([...economicsKey, '/api/economics'], { retention_hours: 168, generated_at: at,
    heads: [{ key: 'synthetic', label: 'Synthetic', ceiling_tokens: 10000, buckets: ago === 1 ? [bucket] : [recent, bucket] }] });
  client.setQueryData([...keys.heads, '/api/heads'], { heads: [{ key: 'synthetic', label: 'Synthetic' }] });
  client.setQueryData([...keys.usage, '/api/usage'], { window_hours: 5, warn_pct: 80, warn_tokens_5h: 0, heads: [{ key: 'synthetic', label: 'Synthetic', usage: {
    output_tokens_5h: 0, entries: 0, ratelimit: null, warn: { pct: 0, level: 'ok', source: 'none', reset: null },
    quota: { five_hour: { used_pct: 25, resets_at: at / 1000 + 3600 } },
  } }] });
  const usage: TurnUsageWire = { totals: {
    requests: 4, input_tokens: 700, cached_tokens: 0, output_tokens: 150, cost_usd: 1.25, cache_share: 0,
    unpriced_requests: 0, missing_input_requests: 0, missing_output_requests: 0, missing_cache_requests: 0,
  }, models: [], accounts: [], days: [], sessions: [] };
  client.setQueryData(['usage-requests', 24, Intl.DateTimeFormat().resolvedOptions().timeZone], {
    landed: [], inflight: [], unread: [], truncated: [], matched: 4, matchedBy: { synthetic: 4 },
    usageBy: { synthetic: usage }, window: { since: at - 24 * 3_600_000, until: at },
  });
  return renderToStaticMarkup(<QueryClientProvider client={client}><MemoryRouter><UsagePage /></MemoryRouter></QueryClientProvider>);
}

test('pending hourly history names the cold command and qualifies otherwise complete window totals', () => {
  const at = Date.now();
  const client = new QueryClient();
  const totals = { requests: 9, input_tokens: 900, cached_tokens: 0, output_tokens: 90, cost_usd: 1.25,
    cache_share: 0, unpriced_requests: 0, missing_input_requests: 0, missing_output_requests: 0, missing_cache_requests: 0 };
  const counted: TurnUsageWire = {
    totals,
    models: [{ key: 'synthetic-model', ...totals }],
    accounts: [{ key: 'synthetic-account', ...totals }],
    days: [{ key: new Date(at).toISOString().slice(0, 10), ...totals }],
    sessions: [],
  };
  client.setQueryData([...economicsKey, '/api/economics'], {
    retention_hours: 168, generated_at: at, heads: [{
      key: 'synthetic', label: 'Cold command', ceiling_tokens: null, buckets: [], read_pending: true,
      unavailable: "Reading saved request history before showing this command's hourly totals.",
    }],
  });
  client.setQueryData([...keys.heads, '/api/heads'], { heads: [{ key: 'synthetic', label: 'Cold command' }] });
  client.setQueryData(['usage-requests', 24, Intl.DateTimeFormat().resolvedOptions().timeZone], {
    landed: [], inflight: [], unread: [], truncated: [], matched: 9, matchedBy: { synthetic: 9 },
    usageBy: { synthetic: counted }, pendingHeads: [], window: { since: at - 86_400_000, until: at },
  });
  const html = renderToStaticMarkup(<QueryClientProvider client={client}><MemoryRouter><UsagePage /></MemoryRouter></QueryClientProvider>);
  expect(html).toContain('Still loading hourly history for Cold command.');
  expect(html).toContain('<div class="n">At least 9</div>');
  expect(html).toContain('<div class="n">At least 900</div>');
  expect(html).toContain('<div class="n">At least 90</div>');
  expect(html).toContain('At least $1.25');
  expect(html).not.toContain('No requests in');
  expect(html).not.toContain('<div class="n">0</div>');
});

test('an input-only hourly report stays plotted without claiming the turn is excluded', () => {
  const html = hourlyPage(1, 1, { turns: 1, out_tokens: 0, cost_usd: null, unpriced_turns: 1 });
  expect(html).toContain('x="133" y="2" width="4" height="30"');
  expect(html).not.toContain('reported no usage');
  expect(html).not.toContain('not in these hourly totals');
  expect(html).toContain('1 turn has an incomplete or missing usage report, so these totals include only reported usage and are lower bounds.');
  expect(html).toContain('Recorded usage pace is a lower bound because usage was not reported for every turn.');
});

test.each([undefined, 0, 2])('hourly missing-report count %s changes only the hourly history and quota-pace notices', missing => {
  const html = hourlyPage(missing);
  if ((missing ?? 0) > 0) {
    expect(html).toContain('2 turns have incomplete or missing usage reports, so these totals include only reported usage and are lower bounds.');
    expect(html).toContain('Recorded usage pace is a lower bound because usage was not reported for every turn.');
  } else {
    expect(html).not.toContain('incomplete or missing usage report');
    expect(html).not.toContain('Recorded usage pace is a lower bound');
  }
  expect(html).toContain('<div class="n">700</div>');
  expect(html).toContain('<div class="n">150</div>');
  expect(html).toContain('<div class="n">$1.25</div>');
  expect(html).not.toContain('At least');
  expect(html).not.toContain('3 turns');
});

test('a missing report outside the 24-hour history still labels the weekly quota calculation', () => {
  const html = hourlyPage(2, 30);
  expect(html).not.toContain('incomplete or missing usage report');
  expect(html).toContain('Recorded usage pace is a lower bound because usage was not reported for every turn.');
});

test('a report at the excluded hourly boundary does not enter the fixed history notice', () => {
  vi.useFakeTimers();
  vi.setSystemTime(new Date('2026-10-06T18:00:00Z'));
  try {
    const html = hourlyPage(2, 24);
    expect(html).not.toContain('incomplete or missing usage report');
    expect(html).toContain('Recorded usage pace is a lower bound because usage was not reported for every turn.');
  } finally {
    vi.useRealTimers();
  }
});

test('a missing report outside the quota calculation changes neither notice', () => {
  const html = hourlyPage(2, 169);
  expect(html).not.toContain('incomplete or missing usage report');
  expect(html).not.toContain('Recorded usage pace is a lower bound');
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
