// Every label this widget prints. Lowercase, three words or fewer, no em-dash (CONTRACTS.md
// section 4, enforced by the label wall). Outcome names are DATA from the daemon, not labels, and
// are printed as the daemon spells them.
export const S = {
  outcomes: 'compact outcomes',
  events: 'compact events',
  detail: 'event detail',
  total: 'total',
  count: 'count',
  when: 'when',
  head: 'head',
  outcome: 'outcome',
  chars: 'chars',
  took: 'took',
  status: 'status',
  error: 'error',
  openEvent: 'open event',
  sample: 'sample data',
  none: 'no events recorded',
  live: 'live',
} as const;
