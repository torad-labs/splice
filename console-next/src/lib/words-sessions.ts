// What a session card's activity line says. Copy lives in modules like this one, never inline in logic.
/** The daemon lists admitted Claude Code versions oldest first. */
const versionRange = (items: readonly string[]): string => (items.length < 2 ? items.join('') : `${items[0]} to ${items[items.length - 1] ?? ''}`);

export const SW = {
  noteRefusedClient: (client: string): string => `This session uses ${client}. Notes from this console reach Claude Code sessions only.`,
  noteRefusedVersion: (version: string, admitted: readonly string[], newest: string): string =>
    `This session runs Claude Code ${version}, and notes reach only ${versionRange(admitted)}. Relaunch it on Claude Code ${newest}.`,
  noteRefusedUnknown: (newest: string): string =>
    `This session did not say which Claude Code it runs, so a note cannot be sent. Relaunch it on Claude Code ${newest}.`,
  waiting: 'Waiting for your answer',
  compacted: 'Compacted its context',
  waitingFor: (span: string): string => `Waiting for your answer for ${span}`,
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
  workingNote: (span: string): string => `Working ${span}`,
  idleNote: (span: string): string => `Idle ${span}`,
} as const;
