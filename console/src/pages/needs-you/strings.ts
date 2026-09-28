// Copy of the Needs you page. The copy gate reads this file: `S` holds labels (three words or fewer,
// sentence case), `H` the one-line help a tip or a finding prints, `U` the fragments printed beside
// a figure or a name.
export const S = {
  title: 'Needs you',
  /** The info mark beside the title. */
  about: 'About this list',
  /** The list's own section and its columns. */
  items: 'Open items',
  connect: 'Connect a plan',
  item: 'Item',
  finding: 'Finding',
  page: 'Page',
  fix: 'Fix',
  /** The one quiet answer, printed only when every input was read. */
  nothing: 'Nothing needs you',
  /** No item found, while an input is still unread: not the quiet answer. */
  nothingYet: 'Nothing found yet',
  /** The inputs nobody could read yet, and each one's state. */
  unread: 'Not read',
  input: 'Input',
  why: 'Why',
  lastRead: 'Last read',
  reading: 'Reading',
  failed: 'Failed',
  unserved: 'Not served',
  /** Each input by the object it reads. */
  inputs: {
    heads: 'Plans',
    auth: 'Sign-ins',
    accounts: 'Accounts',
    usage: 'Plan usage',
    sessions: 'Sessions',
    teams: 'Teams',
    doctor: 'Doctor',
    topology: 'Config file',
  },
  /** The page each item belongs to, as the sidebar names it. */
  sources: {
    heads: 'Fleet',
    daemon: 'Splice',
    plans: 'Accounts',
    accounts: 'Accounts',
    turns: 'Turns',
    sessions: 'Sessions',
    teams: 'Teams',
    doctor: 'Doctor',
  },
  daemon: 'Splice',
  nearest: 'Nearest limit',
  singleLogin: 'Single login',
  /** The fixes. */
  start: 'Start',
  restart: 'Restart',
  confirmRestart: 'Confirm restart',
  signIn: 'Sign in',
  openLog: 'Open log',
  fallback: 'If unavailable',
  openFleet: 'Open fleet',
  openTurns: 'Open turns',
  openSessions: 'Open sessions',
  openTeams: 'Open team',
  openAccounts: 'Open accounts',
  openDoctor: 'Open doctor',
  /** The tip on a fix the report masked, in place of its copy key. */
  maskedWhy: 'Why no copy',
} as const;

export const H = {
  about: 'Everything that needs you now, worst first, each with its fix.',
  setup: 'Not set up yet; connect a plan for your first command.',
  nothing: 'Every input answered, and none of them found anything to do.',
  nothingYet: 'An input below has not answered, so this is not all clear.',
  unread: 'An input nobody could read hides what it would show.',
  down: 'Not running.',
  unhealthy: 'Running, but failing its health check.',
  signedOut: 'No login is saved for this plan.',
  keyMissingBare: 'Its API key is not set.',
  loginExpired: 'Its login expired and the refresh is blocked.',
  queueFull: 'Every slot is busy and the queue is full.',
  configChanged: 'The splice.toml file changed since splice started.',
  tracePending: (head: string): string => `Trace for ${head} in splice.toml applies after restart.`,
  checkPending: 'A change in splice.toml applies after restart.',
  accountSignedOut: 'Its login is gone; sign in with a new label.',
  seatEnded: 'Its assigned session ended; assign another in the team.',
  seatUnlisted: 'Its assigned session is not in the session list.',
  quietSince: 'Alive, but never heard from since it registered.',
  masked: 'Part of this line is masked, so it will not run pasted.',
} as const;

export const U = {
  runs: 'Runs',
  wants: 'splice wants',
  notSet: 'not set',
  idle: 'Idle',
  limit: 'limit',
  lastHeard: 'Last heard',
  at: 'at',
  resets: 'resets',
  waiting: 'settings waiting',
  oneWaiting: 'setting waiting',
  read: 'Read',
  seat: 'seat',
  /** Before what Doctor's checks found, on the head item they are about. */
  doctor: 'Doctor:',
} as const;
