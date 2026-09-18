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
      {/* THE TRACK RENDERS EMPTY (M1-107). This hid the CELL when the view did not list the
          column, which for the FIRST field is the one position where hiding it moves field 1 to
          the next track -- the grid's whole affordance is that field 1 is at the same x on every
          strip, and a column that appears and disappears per VIEW breaks it exactly as a per-row
          one does. Measured on accounts: the first field edge spanned 120px across 12 scanlines,
          34px of it surviving after the four per-ROW sites were converted at the other end. The
          view still decides what it SHOWS; it no longer decides how many cells the row has. */}
      <StripField w={COLUMN_WIDTH} label={S.provider} value={wanted.has('provider') ? account.kind : ''} mono={false} />
      <StripField w={COLUMN_WIDTH} label={S.account} value={wanted.has('account') ? account.label : ''} mono={false} />
      <StripField w={COLUMN_WIDTH} label={S.plan} value={wanted.has('plan') ? (account.plan ?? S.none) : ''} mono={false} />

      {account.windows.map((window) => (
        <StripField
          key={windowFieldId(window)}
          w={WINDOW_WIDTH}
          label={windowFieldLabel(window)}
          value={windowUsedText(window)}
          /* NOT AN ABSENCE PHRASE (M1-69): `unavailable` here is a BASIS member - it tells the reader
         what kind of figure this is, and it is printed beside the figure it qualifies. The census
         counts it because it is quoted. */
      basis={window.used_percent === null ? 'unavailable' : 'measured'}
        />
      ))}
      {account.windows.length === 0 ? (
        <StripField w={WINDOW_WIDTH} label={S.window} value={NOT_REPORTED} mono={false} />
      ) : null}

      {/* THE TRACKS RENDER EMPTY (M1-107), sites seven to eleven. The block above this was a
          THREE-WAY -- excluded OR resets OR NEITHER, at two different widths (16ch and 12ch) --
          so account-strip's declared sum varied by THREE independent runtime facts: which view
          is active, whether the account is struck, and whether a reset exists. THAT IS WHY NO
          CONSTANT CAN HARMONISE THIS RACK: there is no sum to harmonise, only a sum that moves.
          An empty cell in a track is how a ledger carries an optional value; a row that changes
          how many cells it has is not a row in a grid. */}
      <StripField w={COLUMN_WIDTH} label={S.excluded} value={state.struck ? exclusionText(account) : ''} mono={false} />
      <StripField
        w={WINDOW_WIDTH}
        label={S.resets}
        value={state.struck || nearest === null || nearest.reset_epoch_seconds === null
          ? '' : (resetText(nearest.reset_epoch_seconds, nowMs) ?? '')}
        mono={false}
      />
      <StripField
        w={COLUMN_WIDTH}
        label={S.heads}
        value={wanted.has('heads') ? (account.heads.length === 0 ? S.noHeads : account.heads.join(' ')) : ''}
        mono={false}
      />
      <StripField w={COLUMN_WIDTH} label={S.next} value={wanted.has('next') && isNext ? nextRule : ''} mono={false} />
    </Strip>
  );
}
