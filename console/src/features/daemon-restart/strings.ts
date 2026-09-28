// Every word this feature prints. S: labels, three words or fewer, sentence case.
export const S = {
  /** The resting key. Names the daemon, because on the fleet's head detail it sits beside a HEAD
   *  restart and the two are different actions with different blast radii. */
  restart: 'Restart splice',
  /** The armed key: what the second press actually does, in the daemon's own order. */
  confirm: 'Drain and restart',
  /** The name beside the status badge the daemon answered with. */
  daemon: 'Splice',
} as const;
