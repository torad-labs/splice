// A note to a session: what the box may send. The limit is the daemon's (MAX_NOTE_CHARS in SessionNoteRoute.kt); a longer note is refused there.
export const NOTE_LIMIT = 8000;

/** Whether the draft can be sent: something besides whitespace, and within the limit. */
export const noteReady = (draft: string): boolean => draft.trim() !== '' && draft.length <= NOTE_LIMIT;
