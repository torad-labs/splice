// Ported from the old console's models.test.ts: only the assertions over lib/models (slotTiers,
// windowSourceText). The old test read the tiers off the page fixture catalog (pages/models/fixtures)
// and the window-source words through the page's windowFromText; neither is ported, so the catalog is
// built inline with the same shape (claudex-like: opus pinned, fable undeclared). The rest of that
// file renders ModelsBoard or tests the page's own model and is not ported.
import { describe, expect, test } from 'vitest';
import { headWindows, rateText, slotTiers, windowSourceText } from '../src/lib/models';
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

describe('a price as a person reads it', () => {
  test('a declared rate prints in and out per million tokens; no rate is said to be undeclared, never zero', () => {
    expect(rateText({ input: 3, cache_read: 0.3, output: 15 })).toBe('$3.00 in, $15.00 out per million tokens');
    expect(rateText(null)).toBe('No price declared');
    expect(rateText(undefined)).toBe('No price declared');
    expect(rateText({ input: 0.5, cache_read: 0.05, output: 2 })).toBe('$0.500 in, $2.00 out per million tokens');
  });
});

describe('the windows a plan declares', () => {
  const topology = (heads: unknown, providers: unknown) => ({ path: '/c/splice.toml', topology: { heads, providers }, stale: false });
  test('the plan’s forced window, its provider’s default and the extras and rules come from splice.toml, apart from the catalogue', () => {
    const state = topology({ 'chatgpt-oauth': { context_window: 300_000 } }, { codex: { default_context_window: 272_000, extra_windows: [{}, {}], window_rules: [{}] } });
    expect(headWindows(state, 'chatgpt-oauth', 'codex')).toEqual({ plan: 300_000, provider: 272_000, extra: 2, rules: 1 });
  });
  test('a window not set is null, never zero, and a plan the file does not name reads as nothing', () => {
    const state = topology({ a: { context_window: 0 } }, { codex: {} });
    expect(headWindows(state, 'a', 'codex')).toEqual({ plan: null, provider: null, extra: 0, rules: 0 });
    expect(headWindows(state, 'other', 'codex')).toBeNull();
    expect(headWindows(undefined, 'a', 'codex')).toBeNull();
    expect(headWindows({ pending: true } as never, 'a', 'codex')).toBeNull();
  });
});
