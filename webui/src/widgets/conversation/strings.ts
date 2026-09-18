// Labels of the conversation widget. Three words or fewer, lowercase, no
// em-dash (the label wall globs this file). The pending empty and the
// transcript path are not labels and live in the component.
export const S = {
  turn: 'turn',
  at: 'at',
  tool: 'tool',
  result: 'result',
  size: 'size',
  body: 'show body',
  loadMore: 'load more',
  /** The file that answered, so a fallback to the vanilla tree is visible. */
  source: 'read from',
  pages: 'pages',
} as const;
