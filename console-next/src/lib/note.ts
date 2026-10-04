import { SW } from './words-sessions';
import { isNonClaudeClient } from './sessions';

// A note to a session: what the box may send. The limit is the daemon's (MAX_NOTE_CHARS in SessionNoteRoute.kt); a longer note is refused there.
export const NOTE_LIMIT = 8000;

/** Whether the draft can be sent: something besides whitespace, and within the limit. */
export const noteReady = (draft: string): boolean => draft.trim() !== '' && draft.length <= NOTE_LIMIT;

/** Why a session cannot be sent a note, said before anyone types: the daemon sends one only to a Claude Code version it has checked
 *  (`note_versions` on the sessions payload, oldest first). Null when the session's version is one of them, and when the daemon
 *  did not say which: absence claims nothing, and the daemon still refuses what it will not send. */
export function noteRefusal(version: string | null, admitted: readonly string[] | undefined): string | null {
  const newest = admitted?.[admitted.length - 1];
  if (admitted === undefined || newest === undefined) return null;
  if (version === null) return SW.noteRefusedUnknown(newest);
  if (isNonClaudeClient(version)) return SW.noteRefusedClient(version);
  return admitted.includes(version) ? null : SW.noteRefusedVersion(version, admitted, newest);
}
