// The upgrade form's rules, apart from the page so a test reads them without a browser.
import type { UpgradeAsk, UpgradePayload, UpgradeRun, UpgradeRunState } from '@entities/doctor';
import type { Tone } from '@shared/ui';

/** The release box as the request: blank asks for the latest release, and anything else goes as
 *  typed, trimmed; the daemon's 400 says when it is not a release number. */
export function askOf(version: string): UpgradeAsk {
  const to = version.trim();
  return to === '' ? {} : { to };
}

/** The previous release on disk, when the daemon looked and found one: a rollback is offered only
 *  then. Unavailable is not "none", but it is not a release to name on the key either. */
export function rollbackTarget(upgrade: UpgradePayload | null): string | null {
  return upgrade !== null && upgrade.rollback_basis === 'measured' ? upgrade.rollback_target : null;
}

/** The run as the command it is, the way the operator would type it. */
export function commandOf(run: UpgradeRun): string {
  return ['splice', ...run.args].join(' ');
}

export const RUN_TONE: Readonly<Record<UpgradeRunState, Tone>> = {
  running: 'accent',
  succeeded: 'ok',
  failed: 'danger',
  lost: 'warn',
};
