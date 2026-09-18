// One account as a printed strip: the holder edge carries the attention state, the field grid
// carries the numbers. Composed only from @shared/ui primitives, so the strip geometry, the
// palette and the state gestures are the hero's and not this page's.
//
// The window fields are rendered from the account's OWN windows rather than from a fixed
// five-hour/weekly pair. That is the point of the row: a provider that reports a 30-day period
// gets a field reading 30d, and a window the provider does not report gets
// "not reported by provider" instead of a zero.
import {
  NOT_REPORTED,
  accountState,
  exclusionText,
  nearestWindow,
  resetText,
  windowLengthText,
  windowUsedText,
} from '@entities/account';
import type { AccountRow, AccountWindow } from '@entities/account';
import { Strip, StripField } from '@shared/ui';
import { S } from './strings';
import './account-strip.css';

/** The columns a saved view may name. The window columns are deliberately NOT in this set: they
 *  depend on what the provider reported, so a saved view cannot enumerate them and they always
 *  render. */
export const ACCOUNT_COLUMNS = ['provider', 'account', 'plan', 'heads', 'next'] as const;
export type AccountColumn = (typeof ACCOUNT_COLUMNS)[number];

const COLUMN_WIDTH = 16;
const WINDOW_WIDTH = 12;

/** The field id for one window. Stable, and unique per account: one account may carry two windows
 *  of the same length under different model scopes. */
export function windowFieldId(window: AccountWindow): string {
  return window.model === undefined
    ? `window:${window.seconds}`
    : `window:${window.seconds}:${window.model}`;
}

/** A window's field label: its reported length, prefixed by the model where one applies. */
export function windowFieldLabel(window: AccountWindow): string {
  const length = windowLengthText(window.seconds);
  return window.model === undefined ? length : `${window.model} ${length}`;
}

export function AccountStrip({ account, isNext, nextRule, columns, nowMs, selected, onOpen }: {
  account: AccountRow;
  /** True when the selector takes this account next. */
  isNext: boolean;
  /** Why the selector takes it: the rule's own words, printed as text so the mark is explained
   *  rather than merely shown. */
  nextRule: string;
  /** The columns the active view names, in its order. */
  columns: readonly string[];
  nowMs: number;
  selected?: boolean;
  onOpen?: () => void;
}) {
  const state = accountState(account, nowMs);
  const nearest = nearestWindow(account);
  const wanted = new Set(columns);

  return (
    <Strip
      edge={state.edge}
      edgeLabel={state.label}
      cocked={state.cocked}
      struck={state.struck}
      selected={selected ?? false}
      {...(onOpen === undefined ? {} : { onOpen })}
      ariaLabel={`${account.kind} ${account.label}`}
    >
      {wanted.has('provider') ? (
        <StripField w={COLUMN_WIDTH} label={S.provider} value={account.kind} mono={false} />
      ) : null}
      {wanted.has('account') ? (
        <StripField w={COLUMN_WIDTH} label={S.account} value={account.label} mono={false} />
      ) : null}
      {wanted.has('plan') ? (
        <StripField w={COLUMN_WIDTH} label={S.plan} value={account.plan ?? S.none} mono={false} />
      ) : null}

      {account.windows.map((window) => (
        <StripField
          key={windowFieldId(window)}
          w={WINDOW_WIDTH}
          label={windowFieldLabel(window)}
          value={windowUsedText(window)}
          basis={window.used_percent === null ? 'unavailable' : 'measured'}
        />
      ))}
      {account.windows.length === 0 ? (
        <StripField w={WINDOW_WIDTH} label={S.window} value={NOT_REPORTED} mono={false} />
      ) : null}

      {state.struck ? (
        <StripField w={COLUMN_WIDTH} label={S.excluded} value={exclusionText(account)} mono={false} />
      ) : nearest !== null && nearest.reset_epoch_seconds !== null ? (
        <StripField
          w={WINDOW_WIDTH}
          label={S.resets}
          value={resetText(nearest.reset_epoch_seconds, nowMs) ?? ''}
          mono={false}
        />
      ) : null}

      {wanted.has('heads') ? (
        <StripField
          w={COLUMN_WIDTH}
          label={S.heads}
          value={account.heads.length === 0 ? S.noHeads : account.heads.join(' ')}
          mono={false}
        />
      ) : null}
      {wanted.has('next') ? (
        <StripField w={COLUMN_WIDTH} label={S.next} value={isNext ? nextRule : ''} mono={false} />
      ) : null}
    </Strip>
  );
}
