// The payload contracts of the auth WRITES. The read side stays where it was: `AuthPayload` on GET
// /api/auth comes from @shared/api and the existing exports keep working unchanged.
//
//   POST   /api/auth/{head}/switch                 -> SwitchPayload          (SwitchRoute.kt)
//   POST   /api/auth/{head}/login                  -> LoginStatusPayload     (LoginRoutes.kt)
//   GET    /api/auth/{head}/login/{id}             -> LoginStatusPayload     (LoginRoutes.kt)
//   DELETE /api/auth/{kind}/accounts/{label}       -> AccountMutationPayload (AccountEditRoutes.kt)
//   PATCH  /api/auth/{kind}/accounts/{label}       -> AccountMutationPayload (AccountEditRoutes.kt)
//
// Each is typed from the route that serves it. They were first typed from FEATURES.md 6 and 2.6,
// the plan, and three of the five were shapes the daemon never sent: the switch's (corrected
// before), the login's (`login_id`, `flow` and a `landed` state, none on the wire, so a login never
// polled and never finished) and the account edit's (`kind` and `label` on an answer that is `ok`).
import type { LoginView, PendingRoute } from '@shared/api';

/** POST /api/auth/{head}/switch, as SwitchRoute.kt answers it: `ok`, and the reason when not. The
 *  console typed a richer payload (head, to, from, reason, time) that the daemon never sent. The
 *  pin is taken from the NEXT turn: a turn already streaming keeps its account (FEATURES 2.6), so
 *  the console says "next turn", never "switched". */
export interface SwitchPayload {
  ok: boolean;
  error?: string;
}

/** POST /api/auth/{head}/login answers with the login's STARTING view, and GET
 *  /api/auth/{head}/login/{id} with its view now: one shape, LoginRoutes.loginStatusJson, which
 *  names the head the login is for. */
export interface LoginStatusPayload extends LoginView {
  head: string;
}

/** DELETE and PATCH on one pooled account, as AccountEditRoutes.kt answers them: `ok`, and a
 *  refusal is `{error}` with its status. Both are pool-store edits and neither touches a credential
 *  file, so neither can need a restart. */
export interface AccountMutationPayload {
  ok: boolean;
}

/** The v0.4.0 item that will serve every route above (FEATURES.md 6). */
export const PENDING_AUTH_WRITES = 'V4-132';

/** What the auth action store holds: the last write's outcome, tagged with which write it was. */
export type AuthActionOutcome =
  | { action: 'switch'; result: SwitchPayload }
  | { action: 'unpin'; result: SwitchPayload }
  | { action: 'login'; result: LoginStatusPayload }
  | { action: 'login-status'; result: LoginStatusPayload }
  | { action: 'relabel'; result: AccountMutationPayload }
  | { action: 'remove'; result: AccountMutationPayload };

export type AuthActionState = AuthActionOutcome | PendingRoute;
