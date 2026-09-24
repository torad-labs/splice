// The add-account flow as a pure state machine.
//
// It is a machine rather than a handful of booleans because the flow has a real gap in it that the
// page must not paper over: the daemon hands back a code and a link, the operator finishes the
// login in another window, and the credential only joins the pool at head assembly. So "signed in"
// and "usable" are two different states, and a screen that collapsed them would tell the operator
// an account is ready while the pool still cannot take it.
//
// Pure and DOM-free so the whole flow is tested without a renderer.
import type { LoginStartPayload, LoginStatusPayload } from '@entities/auth';

export type LoginStep =
  /** Nothing started. */
  | 'idle'
  /** The label is accepted, the request is in flight. */
  | 'starting'
  /** The daemon returned a code or a link and the operator is finishing it elsewhere. */
  | 'awaiting'
  /** The credential landed. It may still need a head restart before the pool takes it. */
  | 'landed'
  /** The daemon refused, or the login failed. */
  | 'failed'
  /** The route is not built yet: the page prints the row instead of a form. */
  | 'pending';

export interface LoginFlowState {
  step: LoginStep;
  /** The label being requested. Kept across a failure so a retry does not retype it. */
  label: string;
  /** What the daemon returned for the operator to finish the login with. */
  start: LoginStartPayload | null;
  /** The latest status, once one has arrived. */
  status: LoginStatusPayload | null;
  /** The daemon's own words on a failure, or the row id when the route is missing. */
  note: string | null;
}

export const IDLE: LoginFlowState = {
  step: 'idle',
  label: '',
  start: null,
  status: null,
  note: null,
};

export type LoginEvent =
  | { kind: 'label'; value: string }
  | { kind: 'start' }
  | { kind: 'started'; payload: LoginStartPayload }
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

export function next(state: LoginFlowState, event: LoginEvent): LoginFlowState {
  switch (event.kind) {
    case 'label':
      return { ...state, label: event.value };
    case 'start':
      if (!canStart(state)) return state;
      return { ...state, step: 'starting', note: null, start: null, status: null };
    case 'started':
      return { ...state, step: 'awaiting', start: event.payload, note: null };
    case 'status': {
      const { state: reported } = event.payload;
      if (reported === 'landed') return { ...state, step: 'landed', status: event.payload, note: null };
      if (reported === 'failed') {
        return { ...state, step: 'failed', status: event.payload, note: event.payload.note ?? null };
      }
      return { ...state, step: 'awaiting', status: event.payload };
    }
    case 'pending':
      return { ...state, step: 'pending', note: event.row, start: null, status: null };
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
  text: 'sign-in unavailable',
  source: 'this splice version does not serve it; run splice login <head> instead',
} as const;

/** The line the operator reads back. Kept here rather than in the component so the wording for
 *  each step is testable, and so no step can silently lose its sentence. */
export function stepMessage(state: LoginFlowState): string | null {
  switch (state.step) {
    case 'awaiting': {
      const restart = state.status?.restart_required === true;
      if (state.start?.flow === 'device') return 'code printed, finish in the browser';
      return restart ? 'signed in, live after restart' : 'waiting for the credential';
    }
    case 'landed':
      return state.status?.restart_required === true
        ? 'signed in, live after restart'
        : 'account added';
    case 'failed':
      return state.note ?? 'login failed';
    case 'pending':
      return null; // the pending case renders an honest empty, not a sentence
    default:
      return null;
  }
}
