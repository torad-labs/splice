// Labels of the world's controls. Three words or fewer, lowercase, no em-dash (the label wall
// globs this file). A caller's own button text is the caller's copy; only the words these controls
// say by themselves live here.
export const S = {
  /** What an armed destructive key prints second. */
  confirm: 'confirm',
  /** What a cocked key's holder edge prints: the edge is the state, and a state needs a word. */
  armed: 'armed',
  cancel: 'cancel',
  /** What a busy key prints beside its own label: a working key is visible, not merely disabled. */
  busy: 'working',
  /** The retry key a Fault may carry. */
  retry: 'retry',
  /** The holder edge's printed word on a fault strip. */
  fault: 'fault',
  /** The single field a fault strip carries: the daemon's own message. */
  message: 'message',
  /** What a Choice's box says while its rack is shut, and while it is open. */
  open: 'open',
  close: 'close',
  /** What a chosen option's holder edge prints: the mark is a line, and a line needs a word. */
  chosen: 'chosen',
} as const;
