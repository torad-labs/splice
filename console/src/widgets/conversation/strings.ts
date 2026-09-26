// Copy of the conversation widget. The copy gate reads this file: `S` holds labels (three words or
// fewer, sentence case), `H` the one-line help a tip shows, `U` the fragments beside a figure.
export const S = {
  turns: 'Turns',
  /** The file that answered, so a fallback to the vanilla tree is visible. */
  source: 'Read from',
  pages: 'Pages',
  result: 'Result',
  body: 'Show message',
  loadMore: 'Load more',
  noTranscript: 'No transcript',
  unavailable: 'Transcript unavailable',
  /** Who spoke, one word per role the daemon's reader folds the client's events into. */
  user: 'User',
  assistant: 'Assistant',
  system: 'System',
  tool: 'Tool',
} as const;

export const H = {
  lookedIn: 'Looked in:',
  notWritten: 'Claude Code has not written it yet, or it was removed.',
  pending: 'This splice version does not serve transcripts.',
} as const;

export const U = {
  chars: 'chars',
  turn: 'turn',
} as const;
