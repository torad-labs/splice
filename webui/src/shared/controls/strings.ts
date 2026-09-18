// Labels of the world's controls. Three words or fewer, lowercase, no em-dash (the label wall
// globs this file). A caller's own button text is the caller's copy; only the words these controls
// say by themselves live here.
export const S = {
  /** What an armed destructive key prints second. */
  confirm: 'confirm',
  cancel: 'cancel',
  /** The retry key a Fault may carry. */
  retry: 'retry',
  /** The holder edge's printed word on a fault strip. */
  fault: 'fault',
  /** The single field a fault strip carries: the daemon's own message. */
  message: 'message',
} as const;
