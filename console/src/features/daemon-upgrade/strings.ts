// Every word this feature prints. S: labels, three words or fewer, sentence case. H: help, one
// sentence of twelve words or fewer.
import type { UpgradeRunState } from '@entities/doctor';

export const S = {
  /** The release box: blank is the latest release, as `splice upgrade` with no `--to` is. */
  version: 'Version',
  latest: 'Latest',
  upgrade: 'Upgrade',
  /** The armed key names the release the second press asks for. */
  upgradeTo: (release: string): string => `Upgrade to ${release}`,
  rollback: 'Roll back',
  backTo: (release: string): string => `Back to ${release}`,
  /** The run's region and its output's log, by name for a screen reader. */
  run: 'Upgrade run',
  output: 'Run output',
  state: { running: 'Running', succeeded: 'Succeeded', failed: 'Failed', lost: 'Lost' } as Readonly<Record<UpgradeRunState, string>>,
  exit: (code: number): string => `Exit ${code}`,
} as const;

export const H = {
  away: 'The daemon is restarting; the run is read again once it answers.',
  reload: 'Reload the page to load the console the new release serves.',
  quiet: 'The run has printed nothing yet.',
  lost: 'The shell running it is gone, and it left no exit code.',
} as const;
