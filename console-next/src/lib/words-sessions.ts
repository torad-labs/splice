// What a session card's activity line says. Copy lives in modules like this one, never inline in logic.
/** A list of words as a sentence says it: "a", "a and b", "a, b and c". */
const spoken = (items: readonly string[]): string => (items.length < 2 ? items.join('') : `${items.slice(0, -1).join(', ')} and ${items[items.length - 1] ?? ''}`);

export const SW = {
  noteRefusedVersion: (version: string, admitted: readonly string[], newest: string): string =>
    `This session runs Claude Code ${version}, and notes reach only ${spoken(admitted)}. Relaunch it on Claude Code ${newest}.`,
  noteRefusedUnknown: (newest: string): string =>
    `This session did not say which Claude Code it runs, so a note cannot be sent. Relaunch it on Claude Code ${newest}.`,
  waiting: 'Waiting for your answer',
  compacted: 'Compacted its context',
  waitingFor: (span: string): string => `Waiting for your answer for ${span}`,
  stuck: 'Quiet for a while',
  stuckFor: (span: string): string => `Quiet for ${span}`,
  toolQuiet: (span: string): string => `Running a tool, quiet for ${span}`,
  working: 'Working',
  workingFor: (span: string): string => `Working for ${span}`,
  idle: 'Waiting for your next message',
  idleFor: (span: string): string => `Idle for ${span}, waiting for your next message`,
  gone: 'The session ended',
  you: 'You',
  inRepoOn: (repo: string, day: string): string => `${repo} · ${day}`,
  aSessionIn: (repo: string): string => `a session in ${repo}`,
  aSession: 'a session',
  anEndedSession: 'an ended session',
  waitingNote: (span: string): string => `Waiting ${span}`,
  stuckNote: (span: string): string => `Quiet ${span}`,
  workingNote: (span: string): string => `Working ${span}`,
  idleNote: (span: string): string => `Idle ${span}`,
} as const;
