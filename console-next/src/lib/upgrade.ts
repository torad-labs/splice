// The upgrade rows' rules: what a release box asks for, what a rollback offers, and how a run reads. Pure over the daemon's payloads.
import { U } from './words-upgrade';
import type { UpgradeAsk, UpgradePayload, UpgradeRun } from '../types/doctor';

/** The release box as the request: blank asks for the latest release, anything else goes as typed, trimmed. The daemon's 400 says
 *  when it is not a release number. */
export function askOf(version: string): UpgradeAsk {
  const to = version.trim();
  return to === '' ? {} : { to };
}

/** The previous release on disk, when the daemon looked and found one: a rollback is offered only then. */
export const rollbackTarget = (upgrade: UpgradePayload): string | null => (upgrade.rollback_basis === 'measured' ? upgrade.rollback_target : null);

/** The sentence under the version: what runs, and what the check found. A check that never looked says so and never reads as "nothing newer". */
export function versionLede(upgrade: UpgradePayload): string {
  const head = U.running(upgrade.installed);
  if (upgrade.latest_basis !== 'measured') return head + U.neverLooked;
  if (upgrade.latest === null || upgrade.latest === upgrade.installed) return head + U.nothingNewer;
  return head + U.newest(upgrade.latest);
}

/** The run as the command it is, the way a person would type it. */
export const commandOf = (run: UpgradeRun): string => ['splice', ...run.args].join(' ');
