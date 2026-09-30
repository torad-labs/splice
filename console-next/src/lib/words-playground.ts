// What the try-a-plan row says. Copy lives in modules like this one, never inline in a component.
export const Y = {
  title: 'Try a command',
  why: 'One prompt through one command. Nothing is recorded.',
  plan: 'Command',
  pick: 'Choose a command',
  prompt: 'Prompt',
  placeholder: 'Say something short',
  send: 'Send',
  sending: 'Sending…',
  clear: 'Clear',
  sent: 'Sent',
  answer: (status: number): string => `Answered with ${status}`,
  failed: 'That did not run:',
} as const;
