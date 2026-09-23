// The one place GET /api/accounts' wire shape (AccountsWire) meets the page model (AccountsPayload).
import type { AccountRow, AccountWindow, AccountWire, AccountsPayload, AccountsWire } from './types';

/** The length of each slot the daemon files a provider's windows into. The daemon names the slots by
 *  their length (QuotaSlots) and sends the provider's own length beside each; this is only the
 *  fallback for a slot whose provider sent a figure with no length. */
const FIVE_HOUR_SECONDS = 18000;
const SEVEN_DAY_SECONDS = 604800;

/** One slot as a window, or null when the provider reported nothing for it: an absent slot is an
 *  absence, and the page says `unknown` for an account with no windows rather than inventing 0. */
function slot(
  usedPercent: number | null,
  resetEpochSeconds: number | null,
  windowSeconds: number | null,
  fallbackSeconds: number,
): AccountWindow | null {
  if (usedPercent === null && resetEpochSeconds === null && windowSeconds === null) return null;
  return { seconds: windowSeconds ?? fallbackSeconds, used_percent: usedPercent, reset_epoch_seconds: resetEpochSeconds };
}

function accountFromWire(wire: AccountWire): AccountRow {
  const windows = [
    slot(wire.five_hour_used_percent, wire.five_hour_reset_epoch_seconds, wire.five_hour_window_seconds, FIVE_HOUR_SECONDS),
    slot(wire.seven_day_used_percent, wire.seven_day_reset_epoch_seconds, wire.seven_day_window_seconds, SEVEN_DAY_SECONDS),
  ].filter((window): window is AccountWindow => window !== null);
  return {
    kind: wire.kind,
    label: wire.label,
    single_login: wire.single_login,
    credential_path: wire.credential_path,
    plan: wire.plan,
    primary: wire.primary,
    selected: wire.selected,
    available: wire.available,
    pinned: wire.pinned,
    next_target: wire.next_target,
    credential_present: wire.credential_present,
    auth_excluded_until_epoch_millis: wire.auth_excluded_until_epoch_millis,
    auth_exclusion_reason: wire.auth_exclusion_reason,
    windows,
    heads: wire.heads,
  };
}

export function accountsFromWire(wire: AccountsWire): AccountsPayload {
  return { accounts: wire.accounts.map(accountFromWire) };
}
