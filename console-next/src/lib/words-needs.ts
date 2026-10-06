// Live Accounts warnings and the shared doctor-fix vocabulary.
import type { AccountWindow } from '../types/accounts';
import { windowSpan } from './accounts';
import { localZonedInstantText } from './heads';
import { H as LOGIN } from './words-login';

export const S = {
  openLog: 'Open log',
  openDoctor: 'Open Health',
} as const;

export const H = {
  nativeSpare: (name: string, pct: number, window: AccountWindow, renew: readonly string[]): string => {
    const span = window.length_known === false ? windowSpan(window) : window.seconds === 604800 ? 'weekly' : window.seconds === 18000 ? 'five-hour' : windowSpan(window);
    const model = window.model === undefined ? '' : ` ${window.model}`;
    const reset = window.reset_epoch_seconds === null ? '. Its reset has not been reported.' : `, which resets ${localZonedInstantText(window.reset_epoch_seconds)}.`;
    return `${name} is at ${Math.round(pct)}% of its ${span}${model} limit${reset} No other login can take over. ${renew.length === 0 ? 'Check the other logins on Accounts before the limit.' : renew.map(LOGIN.signInAgain).join(' ')}`;
  },
} as const;

/** A head's runtime findings. Error counts describe errors, not failed turns. */
export const DF = {
  shimStale: 'splice’s launcher does not match this version. Reinstall it.',
  events: (head: string, provider: number, local: number): string => {
    const count = provider === 0 ? local : provider;
    const errors = count === 1 ? 'error' : 'errors';
    const where = provider === 0 ? `${local} ${errors} inside splice`
      : local === 0 ? `${provider} ${errors} at the provider`
        : `${provider} ${errors} at the provider and ${local} inside splice`;
    return `${head}: ${where} since the restart`;
  },
  recent: (head: string, failed: number, total: number, ago: string, outcome: string): string =>
    `${head}: ${failed} of its last ${total} ${total === 1 ? 'turn' : 'turns'} failed; the latest, ${ago}, was ${outcome}`,
} as const;

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
