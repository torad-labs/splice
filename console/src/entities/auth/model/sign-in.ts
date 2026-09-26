// Whether a head's login works, from what the daemon knows of it (GET /api/auth). One reading for every
// page that prints a login, so the accounts table, the fleet and Needs you never disagree.
import type { CredentialVerdict } from '@shared/api';

/** Signed in, signed out, or not known yet: a client head's forwarded login is unverified until
 *  upstream answered one of its turns, and that is not the same as signed in. */
export type SignInState = 'signedIn' | 'signedOut' | 'unverified';

export interface SignIn {
  state: SignInState;
  /** Epoch ms of the upstream answer the state rests on, for a client head's verdict; else null. */
  at: number | null;
}

/** The wire kind of a head whose login is Claude Code's own, forwarded (CLIENT_AUTH_KIND). */
const CLIENT = 'client';

/**
 * A head's login. The daemon's verdict decides when it gave one; a held credential is its `present`.
 * A client head with no verdict comes from a daemon older than V4-220 6b, whose ClientAuthProvider
 * said present for every client head, so its `present` is not evidence and the login is unverified
 * (Marlin's HOLD, condition 2: the Claude head always read "Signed in").
 */
export function signInOf(auth: { kind: string; present: boolean; verdict?: CredentialVerdict | undefined }): SignIn {
  const verdict = auth.verdict;
  if (verdict?.state === 'rejected') return { state: 'signedOut', at: verdict.at_epoch_ms };
  if (verdict?.state === 'accepted') return { state: 'signedIn', at: verdict.at_epoch_ms };
  if (verdict?.state === 'unverified' || (verdict === undefined && auth.kind === CLIENT)) return { state: 'unverified', at: null };
  return { state: auth.present ? 'signedIn' : 'signedOut', at: null };
}

/** Whether a login is Claude Code's own, which only its own /login can sign in again. */
export const isClientLogin = (kind: string): boolean => kind === CLIENT;
