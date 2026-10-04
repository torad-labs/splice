import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { renderToStaticMarkup } from 'react-dom/server';
import { expect, test } from 'vitest';
import { budgetsKey } from '../src/api/usage';
import { AccountBudget } from '../src/pages/accounts/AccountBudget';
import { FailoverOrder } from '../src/pages/accounts/FailoverOrder';
import type { BudgetsPayload } from '../src/types/budget';

function budget(data: BudgetsPayload): string {
  const client = new QueryClient();
  client.setQueryData(budgetsKey, data);
  return renderToStaticMarkup(<QueryClientProvider client={client}><AccountBudget head="synthetic-command" /></QueryClientProvider>);
}

test('an unsupported account source shows the plain state, never daemon internals', () => {
  const client = new QueryClient();
  client.setQueryData(['account-order', 'synthetic-command'], { unavailable: "head 'synthetic-command' has no selectable account source" });
  const html = renderToStaticMarkup(<QueryClientProvider client={client}><FailoverOrder head="synthetic-command" accounts={[]} /></QueryClientProvider>);
  expect(html).toContain('Account ordering is unavailable for this command.');
  expect(html).not.toContain('selectable account source');
  expect(html).not.toContain('head');
});

test('one proven account in two login places explains why its order cannot change', () => {
  const client = new QueryClient();
  client.setQueryData(['account-order', 'synthetic-command'], {
    head: 'synthetic-command', order: [], effective_order: [], single_account: true,
  });
  const html = renderToStaticMarkup(<QueryClientProvider client={client}><FailoverOrder head="synthetic-command" accounts={[]} /></QueryClientProvider>);
  expect(html).toContain('This command uses one account. There is no account order to change.');
  expect(html).not.toContain('drag-handle');
});

test('no cap explains how to set one without unknown used or remaining amounts', () => {
  const html = budget({ budgets: [] });
  expect(html).toContain('No daily cap is set.');
  expect(html).toContain('Set daily cap');
  expect(html).not.toContain('Budget used');
  expect(html).not.toContain('Left in budget');
});

test('a cap without priced spend does not invent zero or a remaining balance', () => {
  const html = budget({ budgets: [{ head: 'synthetic-command', daily_usd: 10, action: 'warn', used_usd: null, remaining_usd: null }] });
  expect(html).toContain('$10');
  expect(html).toContain('Spending is not reported');
  expect(html).not.toContain('Budget used');
  expect(html).not.toContain('Left in budget');
  expect(html).not.toContain('$0');
});

test('a priced command shows the daemon measured budget values', () => {
  const html = budget({ budgets: [{ head: 'synthetic-command', daily_usd: 10, action: 'warn', used_usd: 3, remaining_usd: 7 }] });
  expect(html).toContain('Budget used: $3');
  expect(html).toContain('Left in budget: $7');
  expect(html).toContain('Shared by all accounts on this command.');
});
