// Ported from the old console's models.test.ts: only the assertions over lib/models (slotTiers,
// windowSourceText). The old test read the tiers off the page fixture catalog (pages/models/fixtures)
// and the window-source words through the page's windowFromText; neither is ported, so the catalog is
// built inline with the same shape (claudex-like: opus pinned, fable undeclared). The rest of that
// file renders ModelsBoard or tests the page's own model and is not ported.
import { describe, expect, test } from 'vitest';
import { slotTiers, windowSourceText } from '../src/lib/models';
import type { CatalogModel, HeadCatalog } from '../src/types/models';

function model(over: Partial<CatalogModel> & { id: string }): CatalogModel {
  return {
    label: over.id,
    description: '',
    slot: null,
    context_window: 400_000,
    context_window_source: 'model',
    pinned: false,
    resolved: true,
    ...over,
  };
}

const catalog: HeadCatalog = {
  head: 'claudex',
  provider: 'chatgpt-oauth',
  pinned_model: 'gpt-5.6-sol',
  models: [
    model({ id: 'gpt-5.6-sol', slot: 'opus', pinned: true }),
    model({ id: 'gpt-5.6-luna', slot: 'sonnet' }),
    model({ id: 'gpt-5.5', slot: 'haiku' }),
    model({ id: 'gpt-5.6-mini' }),
  ],
};

describe('the tiers', () => {
  test('the tiers come from the daemon vocabulary, and an unfilled tier is a value and not a gap', () => {
    const tiers = slotTiers(catalog);
    expect(tiers.map((tier) => tier.slot)).toEqual(['opus', 'sonnet', 'haiku', 'fable']);
    expect(tiers[3]?.model).toBeNull();
    expect(tiers[0]?.model?.pinned).toBe(true);
  });

  test('one id under two slots keeps both rows', () => {
    const twice: HeadCatalog = { ...catalog, models: [model({ id: 'x', slot: 'opus' }), model({ id: 'x', slot: 'fable' })] };
    expect(slotTiers(twice).map((tier) => tier.model?.id ?? null)).toEqual(['x', null, null, 'x']);
  });
});

describe('the words', () => {
  test('a window source the map does not know prints as the daemon sent it, dashes spaced', () => {
    expect(windowSourceText('some-new-label')).toBe('some new label');
    expect(windowSourceText('extra-window')).toBe('extra window');
    expect(windowSourceText('default')).toBe('provider default');
  });
});
