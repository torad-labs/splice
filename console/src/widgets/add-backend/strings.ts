// Every word this widget prints. S: labels, three words or fewer, sentence case. H: help and the
// line an answer reads back, one sentence of twelve words or fewer.
export const S = {
  title: 'Add backend',
  profile: 'Profile',
  name: 'Head name',
  baseUrl: 'Base URL',
  command: 'Command',
  models: 'Models',
  modelId: 'Model id',
  window: 'Context window',
  addModel: 'Add model',
  open: 'Continue',
  head: 'Head',
  signsIn: 'Signs in',
  credential: 'Credential',
  signIn: 'Sign in',
  checks: 'Checks',
  runChecks: 'Run checks',
  liveCheck: 'Live check',
  save: 'Save backend',
  saveArmed: 'Save and restart',
  discard: 'Discard',
  written: 'Written to',
  wrapper: 'Wrapper',
  restart: 'Restart',
  linked: 'Linked',
  present: 'Present',
  missing: 'Missing',
  noKey: 'No key needed',
  passed: 'Passed',
  failed: 'Failed',
  aboutChecks: 'About the checks',
  aboutProfile: 'About this profile',
} as const;

/** How a head proves who it is, as a fact's value. */
export const SIGN_IN_BY: Readonly<Record<string, string>> = {
  login: 'Browser login',
  key: 'API key',
  none: 'Claude login',
};

export const H = {
  forwarded: 'Your Claude login is forwarded at launch; nothing to sign in.',
  localNoKey: 'No key is needed for this local runtime.',
  key: (name: string): string => `Store the key it reads from ${name}; the checks read it next.`,
  login: 'Sign in in the browser; this form follows the login.',
  checks: 'The checks splice add runs before it writes the head.',
  live: 'A live check sends one short turn through the new head.',
  draining: 'The daemon is restarting; the new head appears once it is back.',
  launch: 'Type this command in your terminal after the restart.',
  waiting: (count: number): string => `The restart waits for ${count} compaction(s) to finish.`,
  loading: 'Reading the profiles splice can add.',
  unavailable: 'This plan is unavailable in this splice build.',
} as const;
