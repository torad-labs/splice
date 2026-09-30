// What a message's bookkeeping is called where it shows as one quiet line. Copy lives in modules like this one.
export const MSG = {
  ran: 'Ran',
  output: 'Output',
  task: 'Background task',
  note: 'System note',
  peer: 'another session',
  sentTo: (name: string): string => `to ${name}`,
} as const;
