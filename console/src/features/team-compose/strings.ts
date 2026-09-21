// Labels of the team composer. Lowercase, three words or fewer, no em-dash (the label wall globs
// this file). Validation messages and honest empties are sentences and live beside their rules.
export const S = {
  compose: 'compose team',
  name: 'name',
  goal: 'goal',
  features: 'features',
  repo: 'repo',
  slots: 'role slots',
  role: 'role',
  head: 'head',
  lead: 'lead',
  notLead: 'not lead',
  instructions: 'role instructions',
  session: 'session',
  unbind: 'unbind',
  open: 'open seat',
  addSlot: 'add slot',
  removeSlot: 'remove slot',
  archived: 'archived',
  live: 'live',
  problems: 'before saving',
} as const;
