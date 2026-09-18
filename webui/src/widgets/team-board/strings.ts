// Labels of the board. These are the strip field names the comp prints, in the
// comp's own words: the values beside them are data, these are the vocabulary.
// Three words or fewer, lowercase, no em-dash (the label wall globs this file).
export const S = {
  /** team header strip */
  team: 'team',
  goal: 'goal',
  repo: 'repo',
  slots: 'slots',
  leadDriving: 'lead driving',
  /** session strip, line one */
  name: 'name',
  role: 'role',
  model: 'model',
  account: 'account',
  window: 'window',
  lastTurn: 'last turn',
  state: 'state',
  /** session strip, line two */
  head: 'head',
  sessionId: 'session id',
  created: 'created',
  uptime: 'uptime',
  turns: 'turns',
  tokensIn: 'tokens in',
  tokensOut: 'tokens out',
  costEst: 'cost est',
  /** session strip, line three */
  contextLeft: 'context left',
  scratchpad: 'scratchpad',
  workspace: 'workspace',
  branch: 'branch',
  base: 'base',
  diff: 'diff',
  checks: 'checks',
  /** the message strips */
  time: 'time',
  from: 'from',
  arrow: '→',
  to: 'to',
  packet: 'packet',
  message: 'message',
  /** the activity strips */
  member: 'member',
  activity: 'activity',
  detail: 'detail',
  /** printed on the bay labels and the footer. The two bay labels are the comp's
   *  sentences and their first words are the labels; the rest of each sentence
   *  is not a label and lives in the component (CONTRACTS.md section 4). */
  headLabel: 'head:',
  chatLabel: 'team chat',
  activityLabel: 'activity',
  teamId: 'team id:',
  teamCreated: 'created:',
  teamUpdated: 'updated:',
  /** the board's accessible name */
  board: 'team board',
} as const;
