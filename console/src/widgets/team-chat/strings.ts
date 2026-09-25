// Every word the team chat prints (docs/design/DESIGN.md section 10). S holds labels: three words or
// fewer, sentence case. H holds help: one sentence of twelve words or fewer. U holds the unit words
// printed beside a figure. tests/copy.test.ts holds all three.
export const S = {
  chat: 'Chat',
  chatWhy: 'About the chat',
  reveal: 'Show message',
  /** The empties, one factual line each. */
  reading: 'Reading the chat',
  unavailable: 'Chat unavailable',
  unreadable: 'Chat unreadable',
  noMessages: 'No messages today',
} as const;

export const H = {
  chat: "Today's hand-offs between members; each text is read on demand.",
  unavailable: 'This splice version does not serve team chat.',
  // A plain `claude` member's own messages never pass through splice, so they never show (README,
  // the Teams paragraph); an empty chat that did not say so read as nobody having talked (Marlin).
  noMessages: "Messages sent through splice show here; plain claude members' sends do not.",
} as const;

export const U = {
  /** Between a message's sender and its recipient, for a screen reader. */
  to: 'to',
} as const;
