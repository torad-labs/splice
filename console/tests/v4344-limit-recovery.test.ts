// V4-344: a limit notice must lead to a truthful alternative and then to a resumable session.
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, test } from 'vitest';
import { NOT_REREAD } from '../src/entities/account';
import type { AccountRow } from '../src/entities/account';
import type { UsagePayload } from '../src/shared/api';
import { AccountsBoard } from '../src/pages/accounts';
import { statOf, tableOf } from './lib/markup';

const NOW = 1_800_000_000_000;
const HOUR = 3_600;

const accounts: AccountRow[] = [
  {
    kind: 'chatgpt-oauth', label: 'spare', single_login: false, credential_path: null,
    primary: false, selected: false, available: true, pinned: false, next_target: true,
    credential_present: true, heads: ['claudex'],
    windows: [{ seconds: 5 * HOUR, used_percent: 20, reset_epoch_seconds: NOW / 1000 + 30 * 60 }],
  },
];
const usage: UsagePayload = {
  window_hours: 5, warn_pct: 80, warn_tokens_5h: 0,
  heads: [{
    key: 'claude-splice', label: 'Claude', usage: {
      output_tokens_5h: 0, entries: 0, ratelimit: null,
      warn: { level: 'ok', pct: 0, source: 'none', reset: null },
      quota: { five_hour: { used_pct: 98, resets_at: NOW / 1000 + 4 * HOUR } },
    },
  }],
};

function board(): string {
  return renderToStaticMarkup(createElement(AccountsBoard, {
    payload: { accounts }, usage, nowMs: NOW,
    headRows: [{ head: 'claude-splice', kind: 'client', present: true, masked: null, note: null }],
  }));
}

describe('limit recovery on Accounts', () => {
  test('the reset beside the nearest limit belongs to that limit, not a different plan', () => {
    const html = board();
    expect(statOf(html, 'Nearest limit')?.value).toBe('98%');
    expect(statOf(html, 'Limit resets')?.value).toBe('in 4h 0m');
  });

  test('an unmeasured Grok credit window explains its unknown state without treating it as empty', () => {
    const grok: AccountRow = {
      ...accounts[0] as AccountRow,
      kind: 'grok-oauth', label: 'grok', heads: ['claude-grok'], windows: [],
    };
    const html = renderToStaticMarkup(createElement(AccountsBoard, { payload: { accounts: [grok] }, nowMs: NOW }));
    expect(html).toContain('Grok billing has not returned a current credit reading.');
    expect(tableOf(html, 'Accounts').cells[0]?.slice(3, 5)).toEqual(['–', '–']);
  });

  test('a Claude window that reset is not presented as fresh quota', () => {
    const stale: UsagePayload = {
      ...usage,
      heads: usage.heads.map((entry) => ({
        ...entry,
        usage: entry.usage === null ? null : {
          ...entry.usage,
          quota: { five_hour: { used_pct: 99, resets_at: NOW / 1000 - 1 } },
        },
      })),
    };
    const html = renderToStaticMarkup(createElement(AccountsBoard, {
      payload: { accounts }, usage: stale, nowMs: NOW,
      headRows: [{ head: 'claude-splice', kind: 'client', present: true, masked: null, note: null }],
    }));
    const claude = tableOf(html, 'Claude logins');
    expect(claude.cells[0]).toContain(NOT_REREAD);
    expect(claude.rows[0]).not.toContain('aria-valuenow="99"');
  });

  test('Claude and the available plan have comparable readings and a path to sessions', () => {
    const html = board();
    const claude = tableOf(html, 'Claude logins');
    expect(claude.names).toContain('5h');
    expect(claude.cells[0]).toContain('98%in 4h 0m');
    const pooled = tableOf(html, 'Accounts');
    expect(pooled.cells[0]).toContain('20%30m 0s');
    expect(html).toContain('href="#/sessions"');
  });
});
