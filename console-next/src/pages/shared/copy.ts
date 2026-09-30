// What the session surfaces say to a person. Copy lives in modules like this one, never inline in a component.
export const S = {
  openSession: 'Open the session',
  stopTurn: 'Stop the turn',
  stopping: 'Stopping…',
  stopFailed: 'The turn did not stop:',
  copyResume: 'Copy resume command',
  copied: 'Copied',
  copyFailed: 'Could not copy:',
  pickHead: 'Resume on which head?',
  noHeads: 'No heads to resume on.',
  unnamed: 'Untitled session',
} as const;
