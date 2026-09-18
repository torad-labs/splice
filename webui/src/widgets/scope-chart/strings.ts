// Every label this widget prints. Lowercase, three words or fewer, no em-dash (CONTRACTS.md
// section 4, enforced by the label wall). The series names double as the legend keys, so a chart
// never carries a name the legend does not print.
export const S = {
  hour1: '1 hour',
  hour24: '24 hours',
  day7: '7 days',
  tokens: 'tokens by hour',
  cost: 'cost by hour',
  bytes: 'wire bytes',
  tools: 'tool partition',
  limited: 'rate limited turns',
  limitedCount: 'rate limited',
  fresh: 'fresh input',
  cached: 'cache read',
  write: 'cache write',
  out: 'output',
  request: 'request bytes',
  upstream: 'upstream bytes',
  eager: 'eager tools',
  deferred: 'deferred tools',
  turns: 'turns',
  noRates: 'no rates declared',
} as const;
