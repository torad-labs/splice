// The add-account flow as a pure state machine.
//
// It is a machine rather than a handful of booleans because the flow has a real gap in it that the
// page must not paper over: the daemon hands back a code and a link, the operator finishes the
// login in another window, and the credential only joins the pool at head assembly. So "signed in"
// and "usable" are two different states, and a screen that collapsed them would tell the operator
// an account is ready while the pool still cannot take it.
//
// Pure and DOM-free so the whole flow is tested without a renderer.
import type { LoginStatusPayload } from '@entities/auth';
import { H, S } from './strings';

export type LoginStep =
  /** Nothing started. */
  | 'idle'
  /** The label is accepted, the request is in flight. */
  | 'starting'
  /** The daemon runs the login: starting it, or holding out a code or a link for the operator. */
  | 'awaiting'
  /** The credential is on disk; the daemon is restarting the head so its pool takes the account. */
  | 'landed'
  /** The head restarted with the account in its pool. */
  | 'live'
  /** The daemon refused, or the login failed. */
  | 'failed'
  /** The route is not built yet: the page prints the row instead of a form. */
  | 'pending';

export interface LoginFlowState {
  step: LoginStep;
  /** The label being requested. Kept across a failure so a retry does not retype it. */
  label: string;
  /** The login as the daemon last reported it: the start's answer, then each poll's. */
  status: LoginStatusPayload | null;
  /** The daemon's own words on a failure, or the row id when the route is missing. */
  note: string | null;
}

export const IDLE: LoginFlowState = {
  step: 'idle',
  label: '',
  status: null,
  note: null,
};

export type LoginEvent =
  | { kind: 'label'; value: string }
  | { kind: 'start' }
  /** The start's answer and every poll's: the same view of one login (LoginRoutes.kt). */
  | { kind: 'status'; payload: LoginStatusPayload }
  | { kind: 'pending'; row: string }
  | { kind: 'failed'; note: string }
  | { kind: 'reset' };

/** Whether the flow is holding a label worth starting: a blank label would name the credential
 *  file nothing, so the start action stays closed until there is one. */
export function canStart(state: LoginFlowState): boolean {
  if (state.label.trim() === '') return false;
  return state.step === 'idle' || state.step === 'failed';
}

/** Whether the login is still moving, so the page keeps polling it: until the head is live with
 *  the account, or the login failed. */
export function polling(state: LoginFlowState): boolean {
  return state.status !== null && (state.step === 'awaiting' || state.step === 'landed');
}

export function next(state: LoginFlowState, event: LoginEvent): LoginFlowState {
  switch (event.kind) {
    case 'label':
      return { ...state, label: event.value };
    case 'start':
      if (!canStart(state)) return state;
      return { ...state, step: 'starting', note: null, status: null };
    case 'status': {
      const status = event.payload;
      switch (status.state) {
        case 'failed':
          return { ...state, step: 'failed', status, note: status.failure_reason };
        case 'signed_in':
          return { ...state, step: 'landed', status, note: null };
        case 'live_after_restart':
          return { ...state, step: 'live', status, note: null };
        default:
          return { ...state, step: 'awaiting', status, note: null };
      }
    }
    case 'pending':
      return { ...state, step: 'pending', note: event.row, status: null };
    case 'failed':
      return { ...state, step: 'failed', note: event.note };
    case 'reset':
      return IDLE;
    default:
      return state;
  }
}

/**
 * The honest empty a login route the daemon does not serve renders, as data: why the form is not
 * there, in words. It named the campaign row that would serve the route (`row V4-132`), which told
 * the operator nothing; only a daemon older than this console answers 404 here, since the console
 * ships inside the daemon's jar (console review, 2026-09-24).
 */
export const LOGIN_PENDING_EMPTY = {
  text: S.signInUnavailable,
  source: H.signInUnavailable,
} as const;

/** The line the operator reads back. Kept here rather than in the component so the wording for
 *  each step is testable, and so no step can silently lose its sentence. */
export function stepMessage(state: LoginFlowState): string | null {
  switch (state.step) {
    case 'awaiting':
      if (state.status?.user_code != null) return H.device;
      if (state.status?.browser_url != null) return H.browser;
      return H.waiting;
    case 'landed':
      return H.afterRestart;
    case 'live':
      return H.added;
    case 'failed':
      // the daemon's own words where it sent any: they say what failed
      return state.note ?? H.failed;
    case 'pending':
      return null; // the pending case renders an honest empty, not a sentence
    default:
      return null;
  }
}
