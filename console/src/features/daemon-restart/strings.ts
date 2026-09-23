// Every label this feature prints. Lowercase, three words or fewer, no em-dash (CONTRACTS.md
// section 4, enforced by the label wall).
export const S = {
  /** The resting key. Names the daemon, because on the fleet's head detail it sits beside a HEAD
   *  restart and the two are different actions with different blast radii. */
  restart: 'restart daemon',
  /** The armed key: what the second press actually does, in the daemon's own order. */
  confirm: 'drain and restart',
  /** Printed before the status word the daemon answered with. */
  daemon: 'daemon',
} as const;
