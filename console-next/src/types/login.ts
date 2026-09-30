// The payload contracts of the auth WRITES. The read side (`AuthPayload`, GET /api/auth) is in ./core.
//
//   POST   /api/auth/{head}/switch                 -> SwitchPayload          (SwitchRoute.kt)
//   POST   /api/auth/{head}/login                  -> LoginStatusPayload     (LoginRoutes.kt)
//   GET    /api/auth/{head}/login/{id}             -> LoginStatusPayload     (LoginRoutes.kt)
//   POST   /api/auth/{head}/refresh                -> AuthActionResult       (AuthStatusRoutes)
//   DELETE /api/auth/{kind}/accounts/{label}       -> AccountMutationPayload (AccountEditRoutes.kt)
//   PATCH  /api/auth/{kind}/accounts/{label}       -> AccountMutationPayload (AccountEditRoutes.kt)
//   GET|PUT|DELETE /api/keys[/{ENV}]               -> KeysPayload | KeyState (KeyRoutes.kt)
//
// Each is typed from the route that serves it, never from the plan: the first typing of the login's
// answer had `login_id`, `flow` and a `landed` state, none of them on the wire, so a login never polled.
import type { PendingRoute } from './budget';
import type { AuthActionResult, LoginView } from './core';

/** POST /api/auth/{head}/login answers with the login's STARTING view, and GET
 *  /api/auth/{head}/login/{id} with its view now: one shape, LoginRoutes.loginStatusJson, which
 *  names the head the login is for. */
export interface LoginStatusPayload extends LoginView {
  head: string;
  /** The credential's actual saved label, null before it lands or for the primary. */
  label: string | null;
  /** The prior usage record archived before a labeled renewal, null otherwise. */
  usage_set_aside: string | null;
}

/** POST /api/auth/{head}/switch, as SwitchRoute.kt answers it: `ok`, and the reason when not. The
 *  pin is taken from the NEXT turn: a turn already streaming keeps its account, so the console says
 *  "next turn", never "switched". */
export interface SwitchPayload {
  ok: boolean;
  error?: string;
}

/** DELETE and PATCH on one pooled account, as AccountEditRoutes.kt answers them: `ok`, and a refusal
 *  is `{error}` with its status. Neither touches a credential file, so neither can need a restart. */
export interface AccountMutationPayload {
  ok: boolean;
}

/** The v0.4.0 item that will serve every auth write above (a daemon older than it answers 404). */
export const PENDING_AUTH_WRITES = 'V4-132';

/** What a write answers: the last write's outcome tagged with which write it was, or the pending
 *  route when the daemon does not serve it. */
export type AuthActionOutcome =
  | { action: 'switch'; result: SwitchPayload }
  | { action: 'unpin'; result: SwitchPayload }
  | { action: 'login'; result: LoginStatusPayload }
  | { action: 'login-status'; result: LoginStatusPayload }
  | { action: 'refresh'; result: AuthActionResult }
  | { action: 'relabel'; result: AccountMutationPayload }
  | { action: 'remove'; result: AccountMutationPayload };

export type AuthActionState = AuthActionOutcome | PendingRoute;

// ── the key store (`splice key list|set|unset` over the console, KeyRoutes.kt) ──

/** One head that reads a key, and which link of its read chain supplies it now: `environment`,
 *  `file`, `store` or `missing`, `unknown` from a provider that predates it. A string, not a union: a
 *  link the daemon adds later still prints as itself. */
export interface KeyReader {
  head: string;
  source: string;
}

/** One key by name, never by value: whether splice's store holds it, and who reads it from where.
 *  A stored key a head reads from the environment or its file is shadowed, never applied. */
export interface KeyState {
  name: string;
  stored: boolean;
  heads: KeyReader[];
}

/** GET /api/keys: every key a head reads or the store holds, sorted by name. */
export interface KeysPayload {
  path: string;
  keys: KeyState[];
}
