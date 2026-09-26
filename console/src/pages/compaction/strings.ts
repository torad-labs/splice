// Every word this page prints. S: labels, three words or fewer, sentence case. H: help, one
// sentence of twelve words or fewer, shown on hover or focus. U: a unit beside a figure.
export const S = {
  title: 'Compaction',
  sample: 'Sample data',
  aboutModel: 'About the model',
  week: 'Last 7 days',
  counted: 'Counted',
  failed: 'Failed',
  took: 'Time',
  summary: 'Summary',
  outcomes: 'Outcomes',
  outcome: 'Outcome',
  share: 'Share',
  count: 'Count',
  heads: 'Heads',
  head: 'Head',
  compactions: 'Compactions',
  rules: 'Rules',
  recent: 'Recent',
  when: 'When',
  instructions: 'Instructions',
  error: 'Error',
  detail: 'Compaction',
  open: 'Open compaction',
  close: 'Close',
  noRules: 'No rules',
  none: 'No compactions yet',
  clientDefault: 'Client default',
  /** The daemon's outcome names in words. The set stays open: a name missing here prints as the
   *  daemon spells it (model.ts outcomeText). */
  outcomeName: {
    model_text: 'Summary written',
    model_thinking: 'From reasoning',
    model_text_weak: 'Weak summary',
    tooled_no_text: 'Tool call',
    empty_model: 'Empty reply',
    stream_error: 'Stream failed',
    upstream_error: 'Provider error',
  },
} as const;

export const H = {
  model: "Compaction runs on the session's own model and effort.",
  noRules: "Claude Code's own instructions apply until a [compaction] rule exists.",
  none: "A compaction lands here when a session's context fills.",
} as const;

export const U = {
  ms: 'ms',
  chars: 'chars',
} as const;
