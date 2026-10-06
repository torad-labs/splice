import { renderToStaticMarkup } from 'react-dom/server';
import { MemoryRouter } from 'react-router';
import { expect, test } from 'vitest';
import { UsageValues, requestsFor } from '../src/pages/usage/UsageBreakdown';
import type { UsageBreakdown } from '../src/lib/usage-breakdown';
import { B } from '../src/pages/usage/copy';

const unknown: UsageBreakdown = { id: 'synthetic', key: null, head: 'synthetic', turns: 2, cost: null, input: null, output: null, unpriced: 2, missingInput: 2, missingOutput: 2, gaps: { uncounted: 0, plan: 0, undeclared: 0, unknown: 2 }, cut: 0 };

test('historical requests without identity stay honestly named and link to the matching unknown group', () => {
  const html = renderToStaticMarkup(<MemoryRouter><UsageValues table={false} onTableChange={() => undefined}items={[unknown]} by="account" since={0} until={200} labelOf={key => key} /></MemoryRouter>);
  expect(html).toContain('Account identity not recorded · synthetic');
  expect(html).toContain('2 requests');
  expect(requestsFor(unknown, 'account', 0, 200)).toBe('/requests?since=0&until=200&head=synthetic&unattributed=account');
  expect(B.accountWhy).toContain('Older requests without a recorded account identity stay separate');
  expect(B.accountWhy).toContain('a login place is not an account');
});
