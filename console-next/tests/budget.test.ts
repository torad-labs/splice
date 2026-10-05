// The budget entity's pure half: which budget a head has. Ported from console/tests/mcp-doctor.test.ts
// ('a head absent from the payload has no budget'); the rest of that file drives old widgets and stores.
import { describe, expect, test } from 'vitest';
import { budgetFor, hasBudgetPrices } from '../src/lib/budget';
import type { CatalogModel, HeadCatalog } from '../src/types/models';

describe('declared budget prices', () => {
  const model = (rates?: CatalogModel['rates']): CatalogModel => ({
    id: 'synthetic-model', label: 'Synthetic model', description: '', slot: null,
    context_window: 1000, context_window_source: 'synthetic', pinned: true, resolved: true,
    ...(rates === undefined ? {} : { rates }),
  });
  const catalog = (...models: CatalogModel[]): HeadCatalog => ({
    head: 'synthetic-command', provider: 'synthetic', pinned_model: 'synthetic-model', models,
  });

  test('missing catalog, empty catalog, omitted rates and null rates do not count spending', () => {
    expect(hasBudgetPrices(undefined)).toBe(false);
    expect(hasBudgetPrices(catalog())).toBe(false);
    expect(hasBudgetPrices(catalog(model()))).toBe(false);
    expect(hasBudgetPrices(catalog(model(null)))).toBe(false);
  });

  test('declared zero rates are prices, and a partial catalog can count its priced requests', () => {
    expect(hasBudgetPrices(catalog(model({ input: 0, cache_read: 0, output: 0 })))).toBe(true);
    expect(hasBudgetPrices(catalog(model(), model({ input: 1, cache_read: 0, output: 2 })))).toBe(true);
  });
});

describe('which budget a head has', () => {
  test('a head absent from the payload has no budget', () => {
    expect(budgetFor({ budgets: [] }, 'a')).toBeNull();
    expect(budgetFor(null, 'a')).toBeNull();
  });
});
