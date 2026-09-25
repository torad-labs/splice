// Every word this widget prints (docs/design/DESIGN.md section 10): labels, three words or fewer,
// sentence case. The series names double as the legend keys, so a chart never carries a name the
// legend does not print. A chart's title leaves the window out: the page's window control says it.
export const S = {
  hour1: '1 hour',
  hour24: '24 hours',
  day7: '7 days',
  tokens: 'Tokens per hour',
  cost: 'Cost per hour',
  /** Request size as the client sent it and as splice forwarded it to the provider. */
  bytes: 'Request size',
  /** Tool definitions sent with every request, against those loaded only when asked for. */
  tools: 'Tool loading',
  limited: 'Rate limited turns',
  limitedCount: 'Rate limited',
  fresh: 'Fresh input',
  cached: 'Cache read',
  write: 'Cache write',
  out: 'Output',
  request: 'From client',
  upstream: 'To provider',
  eager: 'Sent up front',
  deferred: 'On demand',
  /** The cost legend's one key: the window's dollars, an estimate at the head's rate card, so the key
   *  does not claim them as spent (Marlin, 2026-09-25). */
  spent: 'Estimated cost',
  noRates: 'No prices set',
} as const;
