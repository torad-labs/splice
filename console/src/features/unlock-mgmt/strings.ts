// Copy of the key gate. The copy gate reads this file: `S` holds labels (three words or fewer,
// sentence case), `H` a sentence printed whole, `U` a fragment printed as it is.
export const S = {
  title: 'Management key required',
  openWith: 'Open with',
  key: 'Key',
  unlock: 'Unlock',
} as const;

export const H = {
  /** What the gate says when the daemon answered the key it holds with a 401. */
  refused: 'That key was refused.',
} as const;

export const U = {
  /** The command that opens the console with its key, printed as a command. */
  command: 'splice dashboard',
} as const;
