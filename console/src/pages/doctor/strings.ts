// Every word this page prints. S: labels, three words or fewer, sentence case. H: help, one
// sentence of twelve words or fewer, shown on hover or focus.
//
// A check's id, its finding and its fix are the daemon's words, printed as data; the daemon's own
// em-dash separator never reaches the page (checkFix and checkFinding split on it).
export const S = {
  title: 'Doctor',
  about: 'About doctor',
  sample: 'Sample data',
  attentionFirst: 'Attention first',
  bySection: 'By section',
  checks: 'Checks',
  check: 'Check',
  state: 'State',
  fix: 'Fix',
  needAttention: 'Need attention',
  version: 'Version',
  installed: 'Installed',
  latest: 'Latest',
  rollback: 'Rollback',
  lastChecked: 'Last checked',
  claudeCode: 'Claude Code',
  /** The report's own facts, as served: the payload's field name beside its value. */
  report: 'Report',
  aboutReport: 'About the report',
  detail: 'Check detail',
  openCheck: 'Open check',
  close: 'Close',
  section: 'Section',
  appliesTo: 'Applies to',
  finding: 'Finding',
  copyFix: 'Copy fix',
  openLog: 'Open log',
  noFix: 'No fix offered',
  playground: 'Playground',
  aboutPlayground: 'About the playground',
  head: 'Head',
  prompt: 'Prompt',
  send: 'Send',
  clear: 'Clear',
  request: 'Request',
  response: 'Response',
  pickHead: 'Choose a head',
  refused: 'Report refused',
  unavailable: 'Doctor unavailable',
  noChecks: 'No checks',
  /** A measured nothing: the check looked and found no newer release, or no previous one. */
  none: 'None',
  /** A check's status as its badge prints it. */
  statusName: {
    ok: 'OK',
    info: 'Info',
    warn: 'Warn',
    fail: 'Fail',
  },
  /** How the installed release stands against the newest one. The unknown one names what is
   *  unknown: a bare `Unknown` beside the installed version read as the version being unknown. */
  verdictName: {
    current: 'Current',
    behind: 'Behind',
    unknown: 'Latest unknown',
  },
} as const;

export const H = {
  about: 'Checks on this install, each with its evidence and its fix.',
  noReport: 'This splice version does not serve the doctor report.',
  noChecks: 'The daemon sent an empty report; run splice doctor to compare.',
  report: 'The fields the daemon served, under their own names.',
  playground: 'One prompt through one head; neither side is recorded.',
  prompt: 'One prompt, sent once and not recorded.',
} as const;
