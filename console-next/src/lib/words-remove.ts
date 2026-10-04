// Every word the remove-an-account dialog prints. One module rather than one per page: the Accounts card and the
// Fleet row open the same dialog, and a second copy of these words would drift from the first.
export const R = {
  remove: 'Remove',
  removing: 'Removing…',
  ask: 'Remove this account?',
  why: 'Its login is deleted from this computer; you can sign in again later.',
  cancel: 'Cancel',
  unsupported: 'This splice version cannot remove an account.',
} as const;
