// WALLS for M4-08: the next-target mark is the daemon's own `next_target`, read per pool, and the
// printed selector order names the pin the daemon walks first.
//
// The page used to run its own selector once over EVERY account on the page and then mark each
// strip whose label matched the one answer. So one pool's answer was stamped on every pool that had
// an account of the same label, the pin was never consulted, and a pool whose answer differed got
// no mark at all. The daemon already answers per pool (AccountsRoute writes next_target from each
// pool's nextTargetLabel, AccountPool.kt:163-186); the page now reads that flag.
//
// A `.ts` test cannot hold JSX (TS1161), so elements are built with React.createElement and
// asserted against renderToStaticMarkup's string.
import * as React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, test } from 'vitest';
import { SELECTOR_ORDER_TEXT, nextRuleOf } from '../src/entities/account';
import type { AccountRow, AccountWindow } from '../src/entities/account';
import { AccountsBoard } from '../src/pages/accounts';

const h = React.createElement;
const DAY_7 = 604800;
const NOW = 1_800_000_000_000;

function sevenDay(used: number): AccountWindow {
  return { seconds: DAY_7, used_percent: used, reset_epoch_seconds: null };
}

function account(over: Partial<AccountRow>): AccountRow {
  return {
    kind: 'chatgpt-oauth',
    label: 'acct',
    single_login: false,
    credential_path: null,
    primary: false,
    selected: false,
    available: true,
    pinned: false,
    next_target: false,
    credential_present: true,
    windows: [],
    heads: [],
    ...over,
  };
}

// Two pools of one kind, as GET /api/accounts sends them: each head rides its own pool, every row of
// a pool carries that pool's head, and the daemon flags ONE next target per pool. Both pools hold an
// account labelled `primary`, which is what a mark matched by label across the page stamps twice.
//   codex-a: the operator pinned `work`, so the daemon walks the pin first and takes it over an
//            available primary with more room.
//   codex-b: the primary is excluded, so the daemon takes the lowest seven-day account, `spare`.
const POOLS: AccountRow[] = [
  account({ label: 'primary', primary: true, heads: ['codex-a'], windows: [sevenDay(10)] }),
  account({ label: 'work', pinned: true, next_target: true, heads: ['codex-a'], windows: [sevenDay(60)] }),
  account({ label: 'primary', primary: true, available: false, heads: ['codex-b'], windows: [sevenDay(100)] }),
  account({ label: 'spare', next_target: true, heads: ['codex-b'], windows: [sevenDay(30)] }),
];

/** The `next` cell of every strip on the board, in document order. */
function nextCells(markup: string): string[] {
  const cell = /<span class="myx-sfield-label">next<\/span><span class="[^"]*"><span class="myx-sfield-text">([^<]*)<\/span>/g;
  return [...markup.matchAll(cell)].map((match) => match[1] ?? '');
}

function board(accounts: AccountRow[]): string {
  return renderToStaticMarkup(h(AccountsBoard, { payload: { accounts }, nowMs: NOW }));
}

describe('each pool carries its own next target, the daemon\'s', () => {
  test('two pools, each marked where its own next_target is, and nowhere else', () => {
    // The default view is one bay per kind, in payload order: codex-a's two strips, then codex-b's.
    expect(nextCells(board(POOLS))).toEqual(['', 'pinned', '', 'lowest 7-day used']);
  });

  test('the derivation the board reads names each pool\'s own rule', () => {
    expect(POOLS.map((row) => nextRuleOf(row, POOLS))).toEqual([null, 'pinned', null, 'lowest 7-day used']);
  });

  test('an account the daemon did not flag carries no mark, whatever the console would have chosen', () => {
    // The same pools with no flag anywhere: nothing is available to take, as far as the daemon says.
    const unflagged = POOLS.map((row) => ({ ...row, next_target: false }));
    expect(nextCells(board(unflagged))).toEqual(['', '', '', '']);
  });
});

describe('the selector order is printed as the daemon walks it', () => {
  test('the pin first, then primary, then the previous account, then the lowest seven-day', () => {
    expect(SELECTOR_ORDER_TEXT).toBe('pinned then primary then sticky then lowest 7-day used');
    expect(board(POOLS)).toContain(SELECTOR_ORDER_TEXT);
  });
});
