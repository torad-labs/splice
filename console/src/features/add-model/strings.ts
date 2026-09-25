// Every word this feature prints. S: labels, three words or fewer, sentence case. H: help, one
// sentence of twelve words or fewer.
export const S = {
  head: 'Head',
  /** The offered models, by name for a screen reader. */
  offered: 'Offered models',
  /** A model's switch: off, it is left out; on, it is in the add. */
  pick: 'Pick',
  picked: 'Picked',
  pickModel: (id: string): string => `Add ${id}`,
  /** The resting key counts what the second press adds. */
  add: (count: number): string => (count === 1 ? 'Add 1 model' : `Add ${count} models`),
  /** The armed key: the add writes splice.toml and restarts the daemon. */
  addArmed: 'Add and restart',
  noHeads: 'No OpenRouter head',
  allOffered: 'Nothing to add',
  added: 'Added',
  written: 'Written to',
  restart: 'Restart',
} as const;

export const H = {
  noHeads: 'Models are added to an OpenRouter head; add one from Fleet first.',
  allOffered: 'Every catalogue model is on this head already.',
  draining: 'The daemon is restarting; the models appear once it is back.',
  waiting: (count: number): string => `The restart waits for ${count} compaction(s) to finish.`,
} as const;
