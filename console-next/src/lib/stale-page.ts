// Whether the page in this tab is still the one the daemon serves. The console is one inlined HTML the daemon reads from its jar,
// so a daemon upgraded under an open tab serves new code while the tab keeps running the old: the tab asks once when it opens and
// once after the daemon boots again, and the two answers are compared by content, never by a version the two builds may share.

/** A page's identity: the SHA-256 of its text. */
export async function fingerprint(html: string): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(html));
  return [...new Uint8Array(digest)].map((byte) => byte.toString(16).padStart(2, '0')).join('');
}

/** The daemon now serves a different page than this tab opened with. Unknown on either side claims nothing. */
export const pageStale = (opened: string | null, served: string | null): boolean => opened !== null && served !== null && opened !== served;
