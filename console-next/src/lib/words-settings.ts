// What the curated settings' choices say. Copy lives in modules like this one, never inline in logic.
export const R = {
  text: { label: 'In the reply', hint: 'As plain text above the answer' },
  thinking: { label: 'As thinking', hint: 'In Claude Code’s own thinking blocks' },
  off: { label: 'Hidden', hint: 'Only the answer' },
} as const;
