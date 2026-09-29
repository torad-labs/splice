// V4-423: an account splice cannot serve (a refused credential link, or no credential file) offers no Switch and no
// Refresh. Marlin's walk of V4-410 (ef86845f3): the panel still offered both on the refused row, a switch answered
// ok and pinned it with "Takes effect on the next turn", and a refresh logged five NoSuchFileException lines per
// click. The row keeps what still helps: its reason (the account table's), the sign-in path, Relabel, Remove, and
// Unpin for an account a switch pinned before the daemon refused them.
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, test } from 'vitest';
import type { AccountRow } from '../src/entities/account';
import { AccountActions } from '../src/features/account-login';
import { servesTurns } from '../src/pages/accounts/model';

const REFUSAL = "'linked' is a symbolic link, and splice does not load a linked credential; remove the link and sign in again, or sign in under a different label";

function account(over: Partial<AccountRow>): AccountRow {
  return {
    kind: 'chatgpt-oauth',
    label: 'work',
    single_login: false,
    credential_path: '/pool/work.json',
    primary: false,
    selected: false,
    available: true,
    pinned: false,
    next_target: false,
    credential_present: true,
    windows: [],
    heads: ['claudex'],
    ...over,
  };
}

function actions(props: { serves?: boolean; pinned?: boolean; heads?: string[] }): string {
  return renderToStaticMarkup(createElement(AccountActions, { kind: 'chatgpt-oauth', label: 'work', heads: props.heads ?? ['claudex'], ...props }));
}

describe('which accounts the panel offers a turn on', () => {
  test('one with a credential it loads serves', () => {
    expect(servesTurns(account({}))).toBe(true);
  });

  test('a refused link does not, and neither does an account whose credential file is gone', () => {
    expect(servesTurns(account({ label: 'linked', credential_present: false, refusal: REFUSAL }))).toBe(false);
    expect(servesTurns(account({ credential_present: false, refusal: null }))).toBe(false);
  });

  test('a refusal with a credential present still does not serve, and a blank refusal is no refusal', () => {
    expect(servesTurns(account({ credential_present: true, refusal: REFUSAL }))).toBe(false);
    expect(servesTurns(account({ credential_present: true, refusal: '  ' }))).toBe(true);
  });
});

describe('the actions on a row', () => {
  test('a serving row keeps Switch and Refresh for every head, as before', () => {
    const markup = actions({ heads: ['claudex', 'claudex-second'] });
    for (const head of ['claudex', 'claudex-second']) {
      expect(markup).toContain(`Switch ${head}`);
      expect(markup).toContain(`Refresh ${head}`);
    }
    expect(actions({ serves: true })).toContain('Switch claudex');
  });

  test('a row that cannot serve drops Switch and Refresh for every head and keeps Relabel and Remove', () => {
    const markup = actions({ serves: false, heads: ['claudex', 'claudex-second'] });
    expect(markup).not.toContain('Switch');
    expect(markup).not.toContain('Refresh');
    expect(markup).toContain('Relabel');
    expect(markup).toContain('Remove');
  });

  test('a dead account an earlier switch pinned can still be unpinned, and is offered nothing else on its heads', () => {
    const pinned = actions({ serves: false, pinned: true });
    expect(pinned).toContain('Unpin claudex');
    expect(pinned).not.toContain('Switch');
    expect(pinned).not.toContain('Refresh');
    expect(actions({ serves: false, pinned: false })).not.toContain('Unpin');
  });
});
