// Accounts as table rows, wherever an account is listed: the accounts page's pools and a fleet
// head's own pool. One set of columns, one window cell and one state badge, so the two pages cannot
// disagree about the same account: a window is a meter with its share and its reset, a stale reading
// says so, and an unreported window prints the absence and never a zero.
import type { ReactNode } from 'react';
import { exclusionText, nextRuleOf, NOT_REREAD, slotWindows } from '@entities/account';
import type { AccountRow, AccountWindow } from '@entities/account';
import { HeadMark } from '@entities/control-status';
import { familyName } from '@entities/heads';
import { ABSENT, timeAgo } from '@shared/lib';
import { Badge, KeyValue, Meter } from '@shared/ui';
import type { Column, Tone } from '@shared/ui';
import { accountName, stateOf, TONE, usedTone, windowFigure, windowName } from './model';
import { S, U } from './strings';
import './account-table.css';

export {
  accountName, countdown, MARK, stateOf, stateParts, TONE, usedTone, windowFigure, windowName,
} from './model';
export type { AccountStateKey, WindowFigure } from './model';
export { S as ACCOUNT_WORDS } from './strings';

/** The columns a caller may name. The state and window columns are not in this set: they are what
 *  an account row is for, so no view can hide them. */
export const ACCOUNT_FIELDS = ['provider', 'account', 'plan', 'heads', 'next'] as const;

/** What an account is, for a row key: its kind and pool label, else the credential file a single
 *  login is joined on, else the heads riding it. */
export function accountKey(account: AccountRow): string {
  return `${account.kind}:${account.label ?? account.credential_path ?? account.heads.join(',')}`;
}

/** A row's tint: danger when spent or signed out, warn when near its limit; the state cell says which. */
export function accountTone(account: AccountRow, nowMs: number): Tone | null {
  const state = stateOf(account, nowMs);
  return state === 'spent' || state === 'signedOut' ? 'danger' : state === 'warn' ? 'warn' : null;
}

export function AccountStateBadge({ account, nowMs, quiet = false }: { account: AccountRow; nowMs: number; quiet?: boolean }) {
  const state = stateOf(account, nowMs);
  return <Badge tone={TONE[state]} quiet={quiet}>{S.stateName[state]}</Badge>;
}

/** One window as a meter with its used share, the reset countdown beside it, and the window's own
 *  length where it is not the slot's (Grok reports 30d, a Claude window names its model). */
export function WindowCell({ window, slot, nowMs, label }: { window: AccountWindow | null; slot: string; nowMs: number; label: string }) {
  const figure = windowFigure(window, nowMs);
  if (figure.kind === 'none') return <>{ABSENT}</>;
  if (figure.kind === 'stale') return <span className="myx-at-stale"><Badge tone="neutral" quiet>{NOT_REREAD}</Badge></span>;
  const name = window === null ? slot : windowName(window);
  return (
    <span className="myx-at-win">
      <Meter
        value={figure.percent / 100}
        tone={usedTone(figure.percent)}
        label={`${label} ${name}`}
        figure={`${Math.round(figure.percent)}${U.used}`}
      />
      <span className="myx-at-win-note">{[name === slot ? null : name, figure.resets].filter((part) => part !== null).join(' ')}</span>
    </span>
  );
}

/** Why the daemon takes this account next, as a badge, or nothing when it does not. The daemon's
 *  own flag, per pool (M4-08), and the rule inside that pool that explains it: `accounts` is the
 *  list the account's pool is read from. */
export function NextRule({ account, accounts }: { account: AccountRow; accounts: readonly AccountRow[] }) {
  const rule = nextRuleOf(account, accounts);
  return rule === null ? null : <Badge tone="accent">{S.ruleName[rule]}</Badge>;
}

/**
 * The columns for a list of accounts. `fields` names the optional ones; `grouped` drops the column
 * the rows are already grouped by; `accounts` is the whole list, because the next-target rule is read
 * against the pool the account sits in. `compact` is a narrow list (a fleet head's pool): the state
 * badge rides the name cell instead of a column of its own, and the windows keep a fixed width.
 */
export function accountColumns({ fields, grouped, nowMs, accounts, compact = false }: {
  fields: readonly string[];
  grouped: string | null;
  nowMs: number;
  accounts: readonly AccountRow[];
  compact?: boolean;
}): Column<AccountRow>[] {
  const wanted = new Set(fields);
  const windowWidth = compact ? 'calc(9 * var(--u))' : '20%';
  const columns: (Column<AccountRow> | null)[] = [
    {
      key: 'account',
      label: S.account,
      ...(compact ? {} : { width: '18%' }),
      primary: true,
      cell: (account) => (
        <span className="myx-at-name">
          {wanted.has('account') ? accountName(account) : null}
          {compact ? <AccountStateBadge account={account} nowMs={nowMs} quiet /> : null}
          {account.primary ? <Badge tone="neutral" quiet>{S.primary}</Badge> : null}
          {account.pinned === true ? <Badge tone="accent" quiet>{S.pinned}</Badge> : null}
        </span>
      ),
    },
    wanted.has('provider') && grouped !== 'provider'
      ? { key: 'provider', label: S.provider, width: '11%', cell: (account) => familyName(account.kind) }
      : null,
    wanted.has('plan') ? { key: 'plan', label: S.plan, width: '8%', cell: (account) => account.plan ?? ABSENT } : null,
    compact ? null : { key: 'state', label: S.state, width: '11%', cell: (account) => <AccountStateBadge account={account} nowMs={nowMs} quiet /> },
    { key: 'short', label: S.short, width: windowWidth, wrap: true, cell: (account) => <WindowCell window={slotWindows(account).short} slot={S.short} nowMs={nowMs} label={accountName(account)} /> },
    { key: 'long', label: S.long, width: windowWidth, wrap: true, cell: (account) => <WindowCell window={slotWindows(account).long} slot={S.long} nowMs={nowMs} label={accountName(account)} /> },
    wanted.has('heads') && grouped !== 'head'
      ? {
        key: 'heads',
        label: S.heads,
        cell: (account) => (account.heads.length === 0 ? ABSENT : (
          <span className="myx-at-heads">{account.heads.map((head) => <HeadMark key={head} head={head} />)}</span>
        )),
      }
      : null,
    wanted.has('next')
      ? {
        key: 'next',
        label: S.next,
        width: '12%',
        cell: (account) => <NextRule account={account} accounts={accounts} />,
      }
      : null,
  ];
  return columns.filter((column): column is Column<AccountRow> => column !== null);
}

/** Every fact an opened account carries: its provider and plan, every window it reported, when the
 *  windows were read, and the pool's reason where it refuses the account. */
export function AccountFacts({ account, nowMs }: { account: AccountRow; nowMs: number }) {
  const rows: [string, ReactNode][] = [
    [S.provider, familyName(account.kind)],
    [S.plan, account.plan ?? ABSENT],
    [S.heads, account.heads.length === 0 ? ABSENT : account.heads.join(' ')],
    ...account.windows.map((window): [string, ReactNode] => [
      windowName(window),
      <WindowCell key={windowName(window)} window={window} slot={windowName(window)} nowMs={nowMs} label={accountName(account)} />,
    ]),
    [S.windowsRead, account.observed_at_epoch_seconds === null || account.observed_at_epoch_seconds === undefined
      ? ABSENT : timeAgo(account.observed_at_epoch_seconds * 1000, nowMs)],
    ...(stateOf(account, nowMs) === 'excluded' ? [[S.reason, exclusionText(account)] as [string, ReactNode]] : []),
  ];
  return <KeyValue rows={rows} />;
}
