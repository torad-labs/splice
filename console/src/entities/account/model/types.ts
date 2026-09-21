// The payload contract of the accounts entity. Every field is named after the daemon code that
// will serve it, never after a screen:
//   GET /api/accounts -> AccountsPayload   (PENDING V4-132, typed from FEATURES.md 6 + 2.6 + 4.5)
//
// The one deliberate break from the daemon's own projection is the window shape. Today
// `HeadAccountPools.kt:57-72` drops the window LENGTH and the console sees a 5h/7d pair it can
// only assume; FEATURES 4.5 records the operator's reason for caring ("one of the most manual
// work I do" is hunting a browser login with room) and 2.6 records that Grok reports a 30-day
// period. A window therefore carries `seconds` and the console labels it from that number. It is
// never labelled "weekly" because nothing said weekly.
import type { PendingRoute } from '@shared/api';

/** One window of an account, at the length the PROVIDER reported. */
export interface AccountWindow {
  /** The reported length in seconds: 18000 (5h) for ChatGPT's primary window, 604800 (7d) for its
   *  secondary, 2592000 (30d) for Grok (GrokQuotaProbe.kt:41-52). Never assumed from position. */
  seconds: number;
  /** The provider's used figure, or null when it reported the window but no usage. Null renders as
   *  "not reported by provider" — a 0 here would read as an empty account, which is the opposite
   *  of the truth. */
  used_percent: number | null;
  /** Epoch SECONDS (not ms) as the providers send it, or null where none is reported. */
  reset_epoch_seconds: number | null;
  /** Claude only: the model this window is scoped to, from the statusline `rate_limits` payload
   *  (`seven_day_opus`, `seven_day_sonnet`, `model_scoped`). Absent on every probed kind, whose
   *  snapshot holds exactly two windows filed by length (Quota.kt:17-34). */
  model?: string;
}

/** The last selection change on a head (FEATURES 2.6). */
export interface AccountSwitch {
  from: string;
  to: string;
  reason: string;
  at_epoch_millis: number;
}

/**
 * One pooled account. Joined on the credential PATH, so one login riding several heads is one row:
 * pools are keyed by the head's primary credential file and two heads share a pool only when they
 * share that file (OAuthAccountFiles.kt:178-186).
 */
export interface AccountRow {
  /** The OAuth kind that pools it: chatgpt-oauth, grok-oauth, kimi-oauth or muse-oauth. The Claude
   *  head is `client`, builds no pool at all (HeadAccountPools.kt:27,54) and reaches this page
   *  from the auth view instead — the payload must not invent a row for it. */
  kind: string;
  label: string;
  /** The provider's plan name where it reports one. Grok and Muse report none, so absent means
   *  "no plan reported", never an empty string. */
  plan?: string | null;
  primary: boolean;
  selected: boolean;
  /** The pool's own verdict for the next selection. An excluded or exhausted account is false. */
  available: boolean;
  credential_present: boolean;
  /** Epoch ms the exclusion lifts; null or absent when the account is not excluded. */
  auth_excluded_until_epoch_millis?: number | null;
  /** The daemon's own sentence for the exclusion, printed as-is. */
  auth_exclusion_reason?: string | null;
  /** Every window the provider reported, at its reported length. Empty, never missing: an account
   *  whose provider reports nothing shows its empty, it does not vanish. */
  windows: AccountWindow[];
  /** Heads riding this login. Two heads appear here only when they share a credential file. */
  heads: string[];
  /** Sessions currently on this login, where the route can attribute them. */
  sessions?: string[];
  last_switch?: AccountSwitch | null;
}

export interface AccountsPayload {
  accounts: AccountRow[];
}

/** The v0.4.0 item that will serve GET /api/accounts (FEATURES.md 6). */
export const PENDING_ACCOUNTS = 'V4-132';

/** What the accounts store holds. `PendingRoute` is shared/api's, not this slice's: an unbuilt
 *  route resolves to the honest empty naming the work item, and no mocked row ever reaches a page
 *  (CONTRACTS.md 8). A type-only import keeps the HTTP client itself out of the model layer. */
export type AccountsState = AccountsPayload | PendingRoute;
