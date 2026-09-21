// The payload contracts of the four auth WRITES. The read side stays where it was: `AuthPayload`
// on GET /api/auth comes from @shared/api and the existing exports keep working unchanged.
//
//   POST   /api/auth/{head}/switch                 -> SwitchPayload          (PENDING V4-132)
//   POST   /api/auth/{head}/login                  -> LoginStartPayload      (PENDING V4-132)
//   GET    /api/auth/{head}/login/{id}             -> LoginStatusPayload     (PENDING V4-132)
//   DELETE /api/auth/{kind}/accounts/{label}       -> AccountMutationPayload (PENDING V4-132)
//   PATCH  /api/auth/{kind}/accounts/{label}       -> AccountMutationPayload (PENDING V4-132)
//
// Typed from FEATURES.md 6 and 2.6, which is where the daemon-side work is described. Two of these
// routes are new observation seams rather than thin wrappers, and the payloads say so: a login
// device code goes to stdout today (`DeviceLoginFlow.kt:114-124`) and a landed credential only
// joins a pool at head assembly (`ManagedHeadFactory.kt:143`), so "signed in" and "usable" are two
// different states and the console must be able to show the gap.
import type { PendingRoute } from '@shared/api';

export interface SwitchPayload {
  ok: boolean;
  head: string;
  /** The account the NEXT turn takes. Selection is per turn and sticky per session and never
   *  happens inside a turn (FEATURES 2.6), so this is a pin for what comes next — a turn already
   *  streaming is not moved, and the console must not claim to have moved it. */
  to: string;
  /** The account in force before the pin, or null when the head had not selected yet. */
  from: string | null;
  reason: string;
  at_epoch_millis: number;
}

export const LOGIN_FLOWS = ['device', 'browser'] as const;
export type LoginFlow = (typeof LOGIN_FLOWS)[number];

/** POST /api/auth/{head}/login — start a login and get back what the operator needs to finish it.
 *  `flow` is which of the two the daemon chose, not which the console asked for: a device flow
 *  prints a code, a browser flow parks a thread and opens a URL, and only the daemon knows which
 *  is available for that provider. */
export interface LoginStartPayload {
  login_id: string;
  head: string;
  label: string;
  flow: LoginFlow;
  /** Device flow: the code to type at the verification link, and that link. Both absent on a
   *  browser flow, which is why neither carries a default. */
  user_code?: string;
  verification_uri?: string;
  /** Browser flow: the URL for the CONSOLE to open. The daemon has its own browser path
   *  (`OAuthLoginFlow.kt:64-105`); this is the console's, and the two are not interchangeable. */
  browser_url?: string;
  expires_at_epoch_millis?: number;
}

export const LOGIN_STATES = ['pending', 'landed', 'failed'] as const;
export type LoginState = (typeof LOGIN_STATES)[number];

/** GET /api/auth/{head}/login/{id} — poll one login to its end. */
export interface LoginStatusPayload {
  login_id: string;
  head: string;
  label: string;
  state: LoginState;
  /** True once the credential file is on disk but the head has not restarted, so the account is
   *  NOT yet in the pool: a new account joins only at head assembly, and heads sharing a
   *  credential file share the login while heads with their own file need their own (FEATURES 4.5).
   *  The strip stays cocked with "signed in, live after restart" until this clears. */
  restart_required: boolean;
  note?: string;
}

/** DELETE and PATCH on one pooled account. Both are pool-store edits and neither touches a
 *  credential file, so neither can need a restart. */
export interface AccountMutationPayload {
  ok: boolean;
  kind: string;
  label: string;
  /** PATCH only: the new label. A pool is keyed by the credential PATH, so a relabel moves no
   *  file and loses no window data. */
  label_new?: string;
  /** The pool's labels after the change, so the caller re-renders without a second read. */
  accounts?: string[];
  note?: string;
}

/** The v0.4.0 item that will serve every route above (FEATURES.md 6). */
export const PENDING_AUTH_WRITES = 'V4-132';

/** What the auth action store holds: the last write's outcome, tagged with which write it was. */
export type AuthActionOutcome =
  | { action: 'switch'; result: SwitchPayload }
  | { action: 'login'; result: LoginStartPayload }
  | { action: 'login-status'; result: LoginStatusPayload }
  | { action: 'relabel'; result: AccountMutationPayload }
  | { action: 'remove'; result: AccountMutationPayload };

export type AuthActionState = AuthActionOutcome | PendingRoute;
