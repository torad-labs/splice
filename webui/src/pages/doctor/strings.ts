// Every label this page prints. Lowercase, three words or fewer, no em-dash (CONTRACTS.md
// section 4, enforced by the label wall).
//
// The copy gate bans an em-dash in UI TEXT. `copy fix` and friends carry none; the daemon's own
// em-dash separator lives inside the check detail, which is data, not a label.
export const S = {
  title: 'doctor',
  checks: 'checks',
  status: 'status',
  version: 'version',
  installed: 'installed',
  latest: 'latest',
  rollback: 'rollback',
  copy: 'copy fix',
  copied: 'copied',
  fix: 'fix',
  detail: 'check detail',
  playground: 'playground',
  head: 'head',
  prompt: 'prompt',
  send: 'send',
  request: 'request',
  response: 'response',
  clear: 'clear',
  upgrade: 'upgrade',
  restart: 'restart',
  none: 'none',
  unknown: 'unknown',
  noFix: 'no fix offered',
} as const;
