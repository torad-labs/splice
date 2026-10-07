// The build stamps the final inlined document with its content fingerprint. Rereads omit that self-tag
// from the hash, so the baseline names the loaded code, not a later response or a shared version.
const FINGERPRINT_META = /<meta name="splice-page-fingerprint" content="[a-f0-9]{64}">/;

/** The identity carried by this loaded document; an unstamped page is unknown. */
export function loadedFingerprint(): string | null {
  if (typeof document === 'undefined') return null;
  const value = document.querySelector<HTMLMetaElement>('meta[name="splice-page-fingerprint"]')?.content;
  return value !== undefined && /^[a-f0-9]{64}$/.test(value) ? value : null;
}

/** A page's identity: SHA-256 of its text without the build-authored fingerprint tag. */
export async function fingerprint(html: string): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(html.replace(FINGERPRINT_META, '')));
  return [...new Uint8Array(digest)].map((byte) => byte.toString(16).padStart(2, '0')).join('');
}

/** The daemon now serves a different page than this tab opened with. Unknown on either side claims nothing. */
export const pageStale = (opened: string | null, served: string | null): boolean => opened !== null && served !== null && opened !== served;
