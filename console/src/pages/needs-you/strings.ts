// Copy of the Needs you page. The copy gate reads this file: `S` holds labels (three words or fewer,
// sentence case), `H` the one-line help a tip or a finding prints, `U` the fragments printed beside
// a figure or a name.
export const S = {
  title: 'Needs you',
  /** The info mark beside the title. */
  about: 'About this list',
  /** The list's own section and its columns. */
  items: 'Open items',
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
    heads: 'Heads',
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
    daemon: 'Daemon',
    plans: 'Accounts',
    accounts: 'Accounts',
    turns: 'Turns',
    sessions: 'Sessions',
    teams: 'Teams',
    doctor: 'Doctor',
  },
  daemon: 'Daemon',
  nearest: 'Nearest limit',
  singleLogin: 'Single login',
  /** The fixes. */
  start: 'Start',
  restart: 'Restart',
  confirmRestart: 'Confirm restart',
  signIn: 'Sign in',
  openLog: 'Open log',
  openFleet: 'Open fleet',
  openTurns: 'Open turns',
  openSessions: 'Open sessions',
  openTeams: 'Open team',
  openAccounts: 'Open accounts',
  openDoctor: 'Open doctor',
} as const;

export const H = {
  about: 'Everything that needs you now, worst first, each with its fix.',
  nothing: 'Every input answered, and none of them found anything to do.',
  nothingYet: 'An input below has not answered, so this is not all clear.',
  unread: 'An input nobody could read hides what it would show.',
  down: 'Not running.',
  unhealthy: 'Running, but failing its health check.',
  signedOut: 'No login on disk for this head.',
  keyMissingBare: 'Its API key is not set.',
  loginExpired: 'Its login expired and the refresh is blocked.',
  queueFull: 'Every slot is busy and the queue is full.',
  configChanged: 'The config file changed since the daemon started.',
  accountSignedOut: 'Its login is gone; sign in again.',
  seatEnded: 'Its bound session ended; bind another in the team.',
  seatUnlisted: 'Its bound session is not in the registry.',
  quietSince: 'Alive, but never heard from since it registered.',
} as const;

export const U = {
  runs: 'Runs',
  wants: 'daemon wants',
  notSet: 'not set',
  idle: 'Idle',
  limit: 'limit',
  lastHeard: 'Last heard',
  at: 'at',
  resets: 'resets',
  waiting: 'settings waiting',
  read: 'Read',
  seat: 'seat',
} as const;
