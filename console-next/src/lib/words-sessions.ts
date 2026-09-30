// What a session card's activity line says. Copy lives in modules like this one, never inline in logic.
export const SW = {
  waiting: 'Waiting for your answer',
  waitingFor: (span: string): string => `Waiting for your answer for ${span}`,
  stuck: 'Quiet for a while',
  stuckFor: (span: string): string => `Quiet for ${span}`,
  toolQuiet: (span: string): string => `Running a tool, quiet for ${span}`,
  working: 'Working',
  workingFor: (span: string): string => `Working for ${span}`,
  idle: 'Waiting for your next message',
  idleFor: (span: string): string => `Idle for ${span}, waiting for your next message`,
  gone: 'The session ended',
} as const;
