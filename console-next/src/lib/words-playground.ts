// What the try-a-plan row says. Copy lives in modules like this one, never inline in a component.
export const Y = {
  title: 'Try a plan',
  why: 'One prompt through one plan. Nothing is recorded.',
  plan: 'Plan',
  pick: 'Choose a plan',
  prompt: 'Prompt',
  placeholder: 'Say something short',
  send: 'Send',
  sending: 'Sending…',
  clear: 'Clear',
  sent: 'Sent',
  answer: (status: number): string => `Answered with ${status}`,
  failed: 'That did not run:',
} as const;
