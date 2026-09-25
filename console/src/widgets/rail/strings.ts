// Copy of the sidebar. The copy gate reads this file: every value is a label (three words or
// fewer, sentence case).
export const S = {
  /** The sidebar's accessible name. */
  nav: 'Pages',
  /** The button that opens the palette; the shortcut prints beside it. */
  jump: 'Jump to',
  /** The theme switch, named for what it switches to. */
  toDark: 'Dark theme',
  toLight: 'Light theme',
} as const;

export const U = {
  /** The palette's shortcut, printed beside the jump button. */
  jumpKey: '⌘K',
} as const;
