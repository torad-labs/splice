// Design-capture fixture. Loaded ONLY when import.meta.env.DEV is true and the address carries
// `?fixture=demo` (CONTRACTS.md section 4); the shipped dist contains none of these bytes, and a
// rendered fixture is labelled `sample data` in its bay.
//
// The shape of the data is the point of the row, so the sample exercises every branch the page has
// to render honestly rather than showing a tidy pool: a provider that reports a 30-day window, one
// that reports no plan, an account within the last tenth of its window, an exhausted one, an
// excluded one with a reason, one that reports no window at all, and one login riding two heads.
import type { AccountRow } from '@entities/account';

const HOUR_5 = 18000;
const DAY_7 = 604800;
const DAY_30 = 2592000;

/** A fixed clock, so the capture's reset lines do not drift between runs. */
const FIXTURE_NOW_MS = 1_800_000_000_000;

const DEMO_ACCOUNTS: readonly AccountRow[] = [
  {
    kind: 'chatgpt-oauth',
    label: 'acct-primary',
    plan: 'plus',
    primary: true,
    selected: true,
    available: true,
    credential_present: true,
    windows: [
      { seconds: HOUR_5, used_percent: 74, reset_epoch_seconds: 1_800_000_460 },
      { seconds: DAY_7, used_percent: 31, reset_epoch_seconds: 1_800_200_000 },
    ],
    heads: ['claudex', 'codex-b'],
    last_switch: { from: 'acct-work', to: 'acct-primary', reason: 'primary available', at_epoch_millis: 1_799_999_000_000 },
  },
  {
    kind: 'chatgpt-oauth',
    label: 'acct-work',
    plan: 'team',
    primary: false,
    selected: false,
    available: true,
    credential_present: true,
    windows: [
      { seconds: HOUR_5, used_percent: 93, reset_epoch_seconds: 1_800_000_900 },
      { seconds: DAY_7, used_percent: 12, reset_epoch_seconds: 1_800_400_000 },
    ],
    heads: ['claudex'],
  },
  {
    kind: 'grok-oauth',
    label: 'grok-personal',
    primary: true,
    selected: false,
    available: true,
    credential_present: true,
    // Grok reports a 30-day period and no plan: the page labels the window from the number it was
    // given and prints no plan rather than an empty one.
    windows: [{ seconds: DAY_30, used_percent: 100, reset_epoch_seconds: 1_802_000_000 }],
    heads: ['grokhead'],
  },
  {
    kind: 'kimi-oauth',
    label: 'acct-excluded',
    plan: 'pro',
    primary: false,
    selected: false,
    available: false,
    credential_present: true,
    auth_excluded_until_epoch_millis: 1_800_100_000_000,
    auth_exclusion_reason: 'rate limited, cooling down',
    windows: [{ seconds: DAY_7, used_percent: 58, reset_epoch_seconds: 1_800_300_000 }],
    heads: [],
  },
  {
    kind: 'muse-oauth',
    label: 'acct-unreported',
    primary: true,
    selected: false,
    available: true,
    credential_present: true,
    // The provider reported no window at all: the strip prints the honest empty, never a zero.
    windows: [],
    heads: ['musehead'],
  },
];

/**
 * The fixture's rows, or null.
 *
 * The DEV guard lives HERE, in the module that holds the data, and not in the caller. That is what
 * makes "the shipped dist contains no fixture bytes" true rather than aspirational: a production
 * build replaces `import.meta.env.DEV` with `false`, the guard's body becomes unreachable, and the
 * bundler then drops the data and this module's import of it entirely. A guard in the caller would
 * leave the rows in the bundle, reachable by nothing and shipped to everyone.
 */
export function fixtureAccounts(name: string | null): readonly AccountRow[] | null {
  if (!import.meta.env.DEV || name === null) return null;
  // The fixture's own FILE name, the same rule every other page follows (CONTRACTS.md section 4):
  // `demo` was this file's name for one page and no other, and a name that exists on one page and
  // not another is what made a capture render live data while looking like a working capture.
  return name === 'accounts' ? DEMO_ACCOUNTS : null;
}

/** The fixture's fixed clock, on the same rule as the rows above. */
export function fixtureNow(name: string | null): number | null {
  // Gated on the same name the rows are: a fixture clock for a name this page does not carry would
  // be fixture behaviour the capture marker does not claim (law 23).
  if (!import.meta.env.DEV || name !== 'accounts') return null;
  return FIXTURE_NOW_MS;
}
