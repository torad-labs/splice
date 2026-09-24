// Every label this widget prints. Lowercase, three words or fewer, no em-dash (CONTRACTS.md
// section 4, enforced by the label wall). The series names double as the legend keys, so a chart
// never carries a name the legend does not print.
export const S = {
  hour1: '1 hour',
  hour24: '24 hours',
  day7: '7 days',
  tokens: 'tokens per hour',
  cost: 'cost per hour',
  /** Request size as the client sent it and as splice forwarded it to the provider. */
  bytes: 'request size',
  /** Tool definitions sent with every request, against those loaded only when asked for. */
  tools: 'tool loading',
  limited: 'rate limited turns',
  limitedCount: 'rate limited',
  fresh: 'fresh input',
  cached: 'cache read',
  write: 'cache write',
  out: 'output',
  request: 'from client',
  upstream: 'to provider',
  eager: 'sent up front',
  deferred: 'on demand',
  turns: 'turns',
  noRates: 'no prices set',
} as const;
