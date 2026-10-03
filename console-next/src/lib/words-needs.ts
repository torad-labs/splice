// The words lib/needs.ts prints, ported from the old Needs-you page's strings.ts. `S` holds labels (three
// words or fewer, sentence case), `H` the one-line sentences a finding prints, `U` the fragments printed
// beside a figure or a name, `K` the word a card prints as its state.
import type { NeedKind } from '../types/needs';

export const S = {
  /** The daemon's item is named for the daemon. */
  daemon: 'splice',
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
  seePlan: 'See the command',
} as const;

export const H = {
  down: 'Not running.',
  unhealthy: 'Running, but failing its health check.',
  signedOut: 'No login is saved for this command.',
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
  at: 'at',
  resets: 'resets',
  waiting: 'settings waiting',
  oneWaiting: 'setting waiting',
  seat: 'seat',
  /** Before what Doctor's checks found, on the head item they are about. */
  doctor: 'Doctor:',
} as const;

/** What a head's runtime checks found, in the page's words: the daemon's counters say `2 provider / 0 local error(s)`. */
export const DF = {
  shimStale: 'splice’s launcher does not match this version. Reinstall it.',
  events: (head: string, provider: number, local: number): string =>
    `${head}: ${provider} provider / ${local} local diagnostic error events since the restart. Rate-limit turn counts are unavailable.`,
  recent: (head: string, failed: number, total: number, ago: string, outcome: string): string =>
    `${head}: ${failed} of its last ${total} ${total === 1 ? 'turn' : 'turns'} failed; the latest, ${ago}, was ${outcome}`,
} as const;

/** The state word each kind of item prints. */
export const K = {
  waiting: 'Waiting on you',
  quota: 'Out of quota',
  signedOut: 'Signed out',
  keyMissing: 'Key missing',
  failing: 'Failing',
  version: 'Version mismatch',
  queue: 'Queue full',
  restart: 'Restart needed',
  plan: 'Command near its limit',
  account: 'Account',
  seat: 'Team seat',
  doctor: 'Doctor',
} as const satisfies Record<string, NeedKind>;

/** What a doctor check is titled, by the daemon's id (`section/name`, the name sometimes `:detail`). A family collapsed
 *  to one row is titled by its id up to the colon. An id nobody has titled prints as its name, never with the path. */
const CHECK_TITLES: Readonly<Record<string, string>> = {
  'daemon/heads': 'Commands starting',
  'daemon/turn path': 'Turn path',
  'daemon/daemon': 'Daemon',
  'daemon/topology': 'Running commands',
  'daemon/mgmt-key': 'Management key',
  'installation/wrapper': 'Launcher',
  'installation/jar': 'Installed jar',
  'installation/PATH': 'Search path',
  'configuration/topology': 'Command file',
  'configuration/system-prompt': 'System prompt',
  'configuration/project-prompt': 'Project prompt',
  'configuration/wire-tap': 'Kept request bodies',
  'configuration/local': 'Local runtime',
  'accounts/accounts': 'Accounts',
};

const capital = (name: string): string => `${name.charAt(0).toUpperCase()}${name.slice(1)}`;

export function checkTitle(id: string): string {
  const titled = CHECK_TITLES[id];
  if (titled !== undefined) return titled;
  const colon = id.indexOf(':');
  if (colon !== -1) {
    const family = CHECK_TITLES[id.slice(0, colon)];
    return family === undefined ? capital(id.slice(id.indexOf('/') + 1)).replace(':', ' · ') : `${family} · ${id.slice(colon + 1)}`;
  }
  const slash = id.indexOf('/');
  const section = id.slice(0, slash);
  const name = id.slice(slash + 1);
  if (slash === -1) return capital(id);
  const head = /^head (.+?)(?: (errors|turns))?$/.exec(name);
  if (section === 'runtime' && head?.[2] !== undefined) return `${head[1]} ${head[2]}`;
  if (section === 'daemon' && head !== null && head[2] === undefined) return `${head[1]} port`;
  if (section === 'auth') return `${name} sign-in`;
  if (section === 'prerequisites') return name;
  return capital(name);
}
