// The words lib/needs.ts prints, ported from the old Needs-you page's strings.ts. `S` holds labels (three
// words or fewer, sentence case), `H` the one-line sentences a finding prints, `U` the fragments printed
// beside a figure or a name, `K` the word a card prints as its state.
import type { NeedKind } from '../types/needs';

export const S = {
  /** The daemon's item is named for the daemon. */
  daemon: 'Splice',
  nearest: 'Nearest limit',
  singleLogin: 'Single login',
  signIn: 'Sign in',
  openLog: 'Open log',
  openFleet: 'Open fleet',
  openUsage: 'Open usage',
  openTurns: 'Open turns',
  openTeams: 'Open team',
  openDoctor: 'Open doctor',
  openSession: 'Open the session',
  switchAccount: 'Switch account',
  seePlan: 'See the plan',
} as const;

export const H = {
  down: 'Not running.',
  unhealthy: 'Running, but failing its health check.',
  signedOut: 'No login is saved for this plan.',
  keyMissingBare: 'Its API key is not set.',
  loginExpired: 'Its login expired and the refresh is blocked.',
  queueFull: 'Every slot is busy and the queue is full.',
  configChanged: 'The splice.toml file changed since splice started.',
  tracePending: (head: string): string => `Trace for ${head} in splice.toml applies after restart.`,
  checkPending: 'A change in splice.toml applies after restart.',
  accountSignedOut: 'Its login is gone; sign in again under this label.',
  seatEnded: 'Its assigned session ended; assign another in the team.',
  seatUnlisted: 'Its assigned session is not in the session list.',
  /** A head whose provider refuses turns, said with the instant the refusal lifts. */
  outOfQuota: (until: string): string => `Out of quota until ${until}`,
} as const;

export const U = {
  runs: 'Runs',
  wants: 'splice wants',
  notSet: 'not set',
  idle: 'Idle',
  limit: 'limit',
  at: 'at',
  resets: 'resets',
  waiting: 'settings waiting',
  oneWaiting: 'setting waiting',
  seat: 'seat',
  /** Before what Doctor's checks found, on the head item they are about. */
  doctor: 'Doctor:',
} as const;

/** The state word each kind of item prints. */
export const K = {
  waiting: 'Waiting on you',
  stuck: 'Stuck',
  quota: 'Out of quota',
  signedOut: 'Signed out',
  failing: 'Failing',
  version: 'Version mismatch',
  queue: 'Queue full',
  restart: 'Restart needed',
  plan: 'Plan near its limit',
  account: 'Account',
  turn: 'Turn stalled',
  seat: 'Team seat',
  doctor: 'Doctor',
} as const satisfies Record<string, NeedKind>;
