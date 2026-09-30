// The names this page owns in the replacement console, each with what the console does with it. The disposition says what a person
// can do here, not what the daemon does with the name. A name carries exactly one page disposition.
import type { Disposition } from '../../coverage/checks';
import type { PageJob } from '../../coverage/jobs';

export const dispositions: readonly Disposition[] = [
  { kind: 'route', name: '/api/heads', disposition: 'read-only' },
  { kind: 'route', name: '/api/status', disposition: 'read-only' },
  { kind: 'route', name: '/api/usage', disposition: 'read-only' },
  { kind: 'route', name: '/health', disposition: 'read-only' },
  { kind: 'route', name: '/api/accounts', disposition: 'read-only' },
  { kind: 'route', name: '/api/auth', disposition: 'read-only' },
  { kind: 'route', name: '/api/auth/{head}/refresh', disposition: 'editable' },
  { kind: 'route', name: '/api/auth/{head}/login', disposition: 'editable' },
  { kind: 'route', name: '/api/auth/{head}/login/{id}', disposition: 'read-only' },
  { kind: 'route', name: '/api/auth/{head}/switch', disposition: 'editable' },
  { kind: 'route', name: '/api/auth/{kind}/accounts/{label}', disposition: 'editable' },
  { kind: 'route', name: '/api/add', disposition: 'editable' },
  { kind: 'route', name: '/api/add/profiles', disposition: 'read-only' },
  { kind: 'route', name: '/api/add/{id}', disposition: 'editable' },
  { kind: 'route', name: '/api/add/{id}/login', disposition: 'editable' },
  { kind: 'route', name: '/api/add/{id}/save', disposition: 'editable' },
  { kind: 'route', name: '/api/add/{id}/verify', disposition: 'editable' },
  { kind: 'route', name: '/api/add-model', disposition: 'editable' },
  { kind: 'route', name: '/api/models', disposition: 'read-only' },
  { kind: 'route', name: '/api/models/upstream', disposition: 'read-only' },
  { kind: 'route', name: '/api/heads/{head}/start', disposition: 'editable' },
  { kind: 'route', name: '/api/heads/{head}/stop', disposition: 'editable' },
  { kind: 'route', name: '/api/heads/{head}/restart', disposition: 'editable' },
  { kind: 'route', name: '/api/heads/{head}/turns/live', disposition: 'read-only' },
  { kind: 'route', name: '/api/heads/{head}/turns/{id}/stop', disposition: 'editable' },
  { kind: 'route', name: '/api/logs/{head}', disposition: 'read-only' },
  { kind: 'verb', name: 'add', disposition: 'editable', action: 'Add a plan', via: '/api/add' },
  { kind: 'verb', name: 'add-model', disposition: 'editable', action: 'Add models to a plan', via: '/api/add-model' },
  { kind: 'verb', name: 'login', disposition: 'editable', action: 'Sign in an account', via: '/api/auth/{head}/login' },
  { kind: 'verb', name: 'logs', disposition: 'read-only', via: '/api/logs/{head}' },
  { kind: 'verb', name: 'models', disposition: 'read-only', action: 'Compare a plan’s models with what its provider publishes', via: '/api/models/upstream' },
  { kind: 'verb', name: 'status', disposition: 'read-only', via: '/api/status' },
];

export const job: PageJob = {
  question: 'Are my plans up, signed in and within their limits?',
  leaves: 'Each plan\'s state, its windows and accounts, and the one act that fixes what is wrong.',
  actions: [
    { name: 'Start, stop or restart a plan' },
    { name: 'Sign in an account' },
    { name: 'Switch, refresh, rename or remove an account' },
    { name: 'Add a plan' },
    { name: 'Add models to a plan' },
    { name: 'Compare a plan’s models with what its provider publishes' },
    { name: 'Read a plan\'s log' },
    { name: 'Stop a running turn' },
    { name: 'Reorder the cards' },
  ],
};
