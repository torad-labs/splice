// What the session surfaces say to a person. Copy lives in modules like this one, never inline in a component.
export const R = {
  restart: 'Restart splice',
  confirm: 'Drain and restart',
  cancel: 'Cancel',
  restarting: 'Restarting splice…',
  warns: 'Turns in flight finish first, then splice restarts and this page reconnects.',
  draining: 'Splice is draining and will restart; this page reconnects once it is back.',
  failed: 'That did not work:',
} as const;

export const S = {
  openSession: 'Open the session',
  stopTurn: 'Stop the turn',
  stopping: 'Stopping…',
  stopFailed: 'The turn did not stop:',
  copyResume: 'Copy resume command',
  copied: 'Copied',
  copyFailed: 'Could not copy:',
  pickHead: 'Resume on which head?',
  otherPlan: 'Resume on another command',
  noHeads: 'No heads to resume on.',
  unnamed: 'Untitled session',
} as const;
