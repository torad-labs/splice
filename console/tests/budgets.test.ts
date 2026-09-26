// V4-308: GET /api/budgets answers {budgets: [], unreadable: '<why>'} when budgets.json does not parse
// (V4-296, BudgetRoutes.kt), and every head then runs with no budget, block and warn alike. The
// console's payload type did not declare the field, so the panel showed an empty list that read as
// "no budgets set" while the operator's blocks were off.
import * as React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, test } from 'vitest';
import type { BudgetsSlice } from '../src/entities/budget';
import { BudgetsPanel } from '../src/features/budgets';
import type { Resource } from '../src/shared/lib';

const h = React.createElement;

const UNREADABLE = 'budgets.json could not be read: Unexpected JSON token at offset 12';

function read(data: BudgetsSlice | null, error: string | null = null): Resource<BudgetsSlice> {
  return { data, error, loading: false, lastUpdated: data === null ? null : 1_790_000_000_000 };
}

const panel = (state: Resource<BudgetsSlice>): string => renderToStaticMarkup(h(BudgetsPanel, { heads: ['claudex'], state }));

describe('the budgets panel says when budgets.json does not parse', () => {
  test('prints the daemon\'s sentence above the list', () => {
    const out = panel(read({ budgets: [], unreadable: UNREADABLE }));
    expect(out).toContain(UNREADABLE);
    expect(out.indexOf(UNREADABLE)).toBeLessThan(out.indexOf('<table'));
  });

  test('keeps the save, the recovery the daemon names', () => {
    const out = panel(read({ budgets: [], unreadable: UNREADABLE }));
    const at = out.indexOf('aria-label="Save claudex"');
    expect(at).toBeGreaterThan(-1);
    const tag = out.slice(out.lastIndexOf('<button', at), out.indexOf('>', at) + 1);
    expect(tag).not.toContain('disabled');
    expect(renderToStaticMarkup(h('button', { disabled: true, 'aria-label': 'x' }))).toContain('disabled');
  });

  test('prints nothing when the file parsed', () => {
    expect(panel(read({ budgets: [], unreadable: null }))).not.toContain('myx-fault');
    expect(panel(read({ budgets: [] }))).not.toContain('myx-fault');
  });

  test('prints a failed read, whose empty list is not "no budgets" either', () => {
    expect(panel(read(null, 'the budget store is not wired into this control plane'))).toContain('the budget store is not wired into this control plane');
  });
});
