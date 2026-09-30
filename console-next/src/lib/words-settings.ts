// What the curated settings' choices say. Copy lives in modules like this one, never inline in logic.
export const R = {
  text: { label: 'In the reply', hint: 'As plain text above the answer' },
  thinking: { label: 'As thinking', hint: 'In Claude Code’s own thinking blocks' },
  off: { label: 'Hidden', hint: 'Only the answer' },
} as const;

/** The planner's reasons for leaving a tool server unshared (McpSharing.kt), in words. It words them for the log; a person reads these. */
export const TOOL_REASON = {
  network: 'It is a network server that already serves many sessions, so splice has nothing to share.',
  noCommand: 'It names no program to run.',
  ownFolder: 'It runs in a folder of its own, so one shared copy cannot serve every session.',
  ownEnvironment: 'It reads a setting from each session’s own environment, so one shared copy cannot serve every session.',
  tiedTo: (place: string): string => `It is tied to one project’s folder (${place}), so one shared copy cannot serve every session.`,
  malformed: 'Its entry in Claude Code’s settings is not a valid tool server, so splice cannot run it.',
} as const;

/** The planner's reason in words. The reasons are the closed list in McpSharing.kt; one it adds later prints as it was sent. */
export function toolReasonText(reason: string): string {
  if (/^transport '.*' already serves many clients$/.test(reason)) return TOOL_REASON.network;
  if (reason === 'no command') return TOOL_REASON.noCommand;
  if (reason === 'has a cwd (session-scoped)') return TOOL_REASON.ownFolder;
  if (reason.startsWith('a value expands ${VAR}')) return TOOL_REASON.ownEnvironment;
  const place = /^(?:names the relative path|names the directory) '(.*)' \(project-scoped\)$/.exec(reason)?.[1]
    ?? /^'(.*)' names a location relative to the client \(project-scoped\)$/.exec(reason)?.[1];
  if (place !== undefined) return TOOL_REASON.tiedTo(place);
  if (reason === 'entry is not an object' || reason.startsWith('malformed ')) return TOOL_REASON.malformed;
  return reason;
}
