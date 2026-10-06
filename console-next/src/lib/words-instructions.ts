// What the plan-instructions editor says. Copy lives in modules like this one, never inline in a component.
export const INSTRUCTION_NOTE = {
  none: 'Claude Code’s own instructions stand.',
  append: 'splice adds this after Claude Code’s own instructions.',
  replace: 'Nonempty text replaces Claude Code’s own instructions.',
  strip: 'Paragraphs that match these lines are taken out of Claude Code’s own instructions.',
} as const;

export const I = {
  title: 'System prompt for a command',
  why: 'Text splice puts in front of a command’s sessions, and how it meets Claude Code’s own instructions. It applies after splice restarts, at each session’s next turn.',
  plan: 'Command',
  how: 'How it meets Claude Code’s own',
  modes: [['append', 'Add after'], ['replace', 'Replace'], ['strip', 'Take out']] as const,
  from: 'Where it is written',
  sources: [['inline', 'Here'], ['file', 'In a file']] as const,
  text: 'Instructions',
  strip: 'Lines to take out',
  file: 'File path',
  add: 'Add instructions',
  remove: 'Remove instructions',
  save: 'Save',
  saving: 'Saving…',
  saved: 'Saved. It applies after splice restarts.',
  failed: 'That did not save:',
  preview: 'What splice will use',
  reading: 'Reading the file, without saving your changes.',
  chars: (n: number): string => `${n.toLocaleString('en-US')} ${n === 1 ? 'character' : 'characters'}`,
  firstLines: 'Only the start is shown.',
  replaceEffect: 'A nonempty replacement removes Claude Code’s operating instructions, including its guidance on tools. An empty source leaves them unchanged.',
  depends: 'The full result also depends on the session’s project and Claude Code’s own instructions.',
  noPlans: 'No commands are set up.',
  unavailable: 'This splice cannot edit its configuration file.',
} as const;
