// The models page on the kit: one table of every head's models, the window and both prices drawn
// against their column's largest so heads compare, each head's four tiers as chips, the provider
// view grouping on what the daemon reports, and the opened model reading its own head's windows.
//
// CONTRACTS.md section 4: a .ts test holds no JSX (TS1161), so elements are built with
// createElement and asserted on the markup react-dom/server returns; and a BOARD takes its payload
// as a prop, because a static render only ever sees a zustand store's initial state.
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, test } from 'vitest';
import { slotTiers, windowSourceText } from '../src/entities/model';
import type { HeadCatalog } from '../src/entities/model';
import { ModelsBoard } from '../src/pages/models';
import { fixtureCatalog } from '../src/pages/models/fixtures/models';
import {
  byProvider, columnMax, entriesOf, findModel, headWindows, rateText, tiersFilled, windowFromText,
} from '../src/pages/models/model';
import { H, S } from '../src/pages/models/strings';
import { ABSENT } from '../src/shared/lib';

const h = createElement;
const render = (element: Parameters<typeof renderToStaticMarkup>[0]): string => renderToStaticMarkup(element);

/** The first element, or a named failure: the strict preset forbids a non-null assertion. */
function first<T>(items: readonly T[]): T {
  const value = items[0];
  if (value === undefined) throw new Error('expected at least one element');
  return value;
}

/** The column names of one labelled table, and its body rows without the group title rows. */
function table(markup: string, label: string): { names: string[]; rows: string[]; groups: string[] } {
  const match = new RegExp(`<table[^>]*aria-label="${label}"[^>]*>([\\s\\S]*?)</table>`).exec(markup);
  const [head, body] = (match?.[1] ?? '').split('</thead>');
  const all = (body ?? '').split('<tr').slice(1);
  return {
    names: [...(head ?? '').matchAll(/<th scope="col"[^>]*>([^<]*)</g)].map((m) => m[1] ?? ''),
    rows: all.filter((row) => !row.includes('myx-dt-group')),
    groups: all.filter((row) => row.includes('myx-dt-group')),
  };
}

const models = fixtureCatalog.heads.reduce((sum, head) => sum + head.models.length, 0);

describe('the catalog table', () => {
  const markup = render(h(ModelsBoard, { catalog: fixtureCatalog }));
  const catalog = table(markup, S.models);

  test('every head is a group and every model a row, so a green below is not an empty denominator', () => {
    expect(catalog.groups).toHaveLength(fixtureCatalog.heads.length);
    for (const head of fixtureCatalog.heads) expect(catalog.groups.some((group) => group.includes(`>${head.head}<`))).toBe(true);
    expect(catalog.rows).toHaveLength(models);
  });

  test('names its columns once, in the head row, with one cell per column in every row', () => {
    // The strip rack printed its six names on every strip until M2-33; a table names them once.
    expect(catalog.names).toEqual([S.model, S.tier, S.contextWindow, S.windowFrom, S.input, S.output]);
    for (const row of catalog.rows) expect([...row.matchAll(/<td/g)].length).toBe(catalog.names.length);
  });

  test('a head\'s rows run in the client\'s tier order, then the models no tier selects', () => {
    const claudex = fixtureCatalog.heads.find((head) => head.head === 'claudex');
    if (claudex === undefined) throw new Error('the fixture catalog lost claudex');
    expect(entriesOf(claudex).map((entry) => entry.model.id)).toEqual(['gpt-5.6-sol', 'gpt-5.6-luna', 'gpt-5.5', 'gpt-5.6-mini']);
  });

  test('the window and both prices are bars against their column across every head', () => {
    const max = columnMax(fixtureCatalog.heads.flatMap(entriesOf));
    expect(max).toEqual({ window: 400_000, input: 1.25, output: 10 });
    const sol = catalog.rows.find((row) => row.includes('>gpt-5.6-sol<')) ?? '';
    // the widest window and the dearest prices fill their bars; a cheaper head's bar is shorter
    expect((sol.match(/aria-valuenow="100"/g) ?? []).length).toBe(3);
    const flash = catalog.rows.find((row) => row.includes('>deepseek-flash<')) ?? '';
    expect(flash).toContain('aria-valuenow="32"');
  });

  test('a model with no rate card prints the absence in both price cells, never a zero', () => {
    const mini = catalog.rows.find((row) => row.includes('>gpt-5.6-mini<')) ?? '';
    expect((mini.match(/role="meter"/g) ?? []).length).toBe(1);
    expect((mini.match(new RegExp(`>${ABSENT}<`, 'g')) ?? []).length).toBeGreaterThanOrEqual(3);
    expect(mini).not.toContain('>$0<');
  });

  test('a price prints in cents at least, and a cheap model keeps every digit the daemon sent', () => {
    expect(rateText(0.014)).toBe('$0.014');
    expect(rateText(0.4)).toBe('$0.40');
    expect(rateText(10)).toBe('$10.00');
    expect(rateText(1.25)).toBe('$1.25');
  });

  test('the pinned model carries its badge, once per head that pins one', () => {
    const pinned = fixtureCatalog.heads.filter((head) => head.pinned_model !== '').length;
    expect(catalog.rows.filter((row) => row.includes(`>${S.pinned}<`))).toHaveLength(pinned);
  });
});

describe('the tiers', () => {
  test('each head\'s four tiers are chips, green where a model fills one and grey where none does', () => {
    const markup = render(h(ModelsBoard, { catalog: fixtureCatalog }));
    const claudex = first(table(markup, S.models).groups.filter((group) => group.includes('>claudex<')));
    for (const tier of ['Opus', 'Sonnet', 'Haiku']) expect(claudex).toMatch(new RegExp(`myx-badge-ok[^"]*"[^>]*>(<[^>]*>)*${tier}<`));
    expect(claudex).toMatch(/myx-badge-neutral[^"]*"[^>]*>(<[^>]*>)*Fable</);
  });

  test('the tiers come from the daemon vocabulary, and an unfilled tier is a value and not a gap', () => {
    const tiers = slotTiers(first(fixtureCatalog.heads));
    expect(tiers.map((tier) => tier.slot)).toEqual(['opus', 'sonnet', 'haiku', 'fable']);
    expect(tiers[3]?.model).toBeNull();
    expect(tiers[0]?.model?.pinned).toBe(true);
  });

  test('the figure counts filled tiers of every tier every head could fill', () => {
    expect(tiersFilled(fixtureCatalog.heads)).toEqual({ filled: 5, total: 12 });
    expect(render(h(ModelsBoard, { catalog: fixtureCatalog }))).toContain('>of 12<');
  });
});

describe('the words', () => {
  test('the window source reads as words, whatever label the daemon sends', () => {
    // ModelsRoute.kt WINDOW_FROM_*, plus `head` from splice-lead's S15 change.
    expect(['model', 'head', 'rule', 'extra-window', 'default', 'unknown'].map(windowFromText)).toEqual([
      'Model catalog', 'Head setting', 'Prefix rule', 'Extra window', 'Provider default', 'Unknown',
    ]);
    expect(windowFromText('some-new-label')).toBe(windowSourceText('some-new-label'));
    expect(render(h(ModelsBoard, { catalog: fixtureCatalog }))).not.toContain('extra-window');
  });

  test('a catalog this daemon does not serve says so without a row id, and nothing else is drawn', () => {
    const markup = render(h(ModelsBoard, { catalog: { pending: 'V4-127' } }));
    expect(markup).toContain(`>${S.catalogPending}<`);
    expect(markup).toContain(H.pending);
    expect(markup).not.toContain('V4-127');
    expect(markup).not.toContain('<table');
    expect(markup).not.toContain('myx-stat');
  });

  test('no heads at all is one line, not a page of empty tiles', () => {
    const markup = render(h(ModelsBoard, { catalog: { heads: [] } }));
    expect(markup).toContain(`>${S.noHeads}<`);
    expect(markup).not.toContain('myx-stat');
  });

  test('a fixture-fed board carries the capture marker with the fixture name', () => {
    expect(render(h(ModelsBoard, { catalog: fixtureCatalog, sample: 'models' }))).toContain('data-sample="models"');
    expect(render(h(ModelsBoard, { catalog: fixtureCatalog }))).not.toContain('data-sample');
  });
});

describe('grouping and the opened model', () => {
  test('the by-provider view groups on the reported provider and never guesses one', () => {
    const groups = byProvider(fixtureCatalog.heads);
    expect(groups.map((group) => group.provider)).toEqual(['api-key', 'chatgpt-oauth', 'kimi-oauth']);
    // An empty provider is grouped under the honest label, never under the head key.
    const withoutProvider: HeadCatalog = { ...first(fixtureCatalog.heads), provider: '' };
    expect(byProvider([withoutProvider])[0]?.provider).toBe(S.providerUnknown);
  });

  test('a head\'s windows come from its topology, joined on the head and provider keys', () => {
    const head = first(fixtureCatalog.heads);
    const topology = {
      heads: { [head.head]: { provider: head.provider, context_window: 400_000 } },
      providers: { [head.provider]: { default_context_window: 200_000, extra_windows: [{ id: 'x', context_window: 1 }], window_rules: [] } },
    };
    expect(headWindows(topology, head)).toEqual({ headWindow: 400_000, defaultWindow: 200_000, extraWindows: 1, windowRules: 0 });
    // A window the topology does not set is an absence, never the daemon's internal zero.
    const bare = { heads: { [head.head]: {} }, providers: { [head.provider]: { default_context_window: 0 } } };
    expect(headWindows(bare, head)).toEqual({ headWindow: null, defaultWindow: null, extraWindows: 0, windowRules: 0 });
    expect(headWindows(null, head)).toBeNull();
    expect(headWindows({ heads: {}, providers: {} }, head)).toBeNull();
  });

  test('the opened model is found under the head it was opened on, and a pending payload finds nothing', () => {
    expect(findModel(fixtureCatalog, { head: 'claude-kimi', id: 'kimi-k2.5' })?.head.head).toBe('claude-kimi');
    expect(findModel(fixtureCatalog, { head: 'claude-kimi', id: 'nope' })).toBeNull();
    expect(findModel(fixtureCatalog, { head: 'no-such-head', id: 'kimi-k2.5' })).toBeNull();
    expect(findModel({ pending: 'V4-127' }, { head: 'claude-kimi', id: 'kimi-k2.5' })).toBeNull();
  });

  test('two heads serving one model id each open their own, so the detail reads the right head windows', () => {
    const kimi = fixtureCatalog.heads.find((head) => head.head === 'claude-kimi');
    if (kimi === undefined) throw new Error('the fixture catalog lost claude-kimi');
    const twin = { ...kimi, head: 'claude-kimi-twin' };
    const catalog = { heads: [kimi, twin] };
    expect(findModel(catalog, { head: 'claude-kimi-twin', id: 'kimi-k2.5' })?.head.head).toBe('claude-kimi-twin');
    expect(findModel(catalog, { head: 'claude-kimi', id: 'kimi-k2.5' })?.head.head).toBe('claude-kimi');
    // and their rows carry distinct keys, so opening one never selects the other
    expect(entriesOf(twin)[0]?.key).not.toBe(entriesOf(kimi)[0]?.key);
  });
});
