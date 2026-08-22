// NEW: the ONE PKCE pair (review of PR 99). :provider-codex and :provider-grok each declared their
// own `data class Pkce(verifier, challenge)` — name-for-name and field-for-field identical, because
// PKCE (RFC 7636) has no vendor-specific content: the verifier is random bytes and the challenge is
// its S256 digest, both base64url. Two copies of a shared construct is how the two drift; :core is
// where both already meet (each provider depends on it), so the pair lives here and each provider
// keeps only its own generator — the parts that ARE vendor-specific (verifier byte length, the
// authorize-URL param set) stay in CodexOAuth/GrokOAuth where they differ.
package splice.core.auth

/** A PKCE (RFC 7636) verifier and its S256 challenge — both base64url, no padding. Produced by a
 *  provider's own `makePkce`; the verifier is replayed on the code exchange, the challenge on the
 *  authorize URL. Carries no vendor content, so there is exactly one of these. */
public data class Pkce(val verifier: String, val challenge: String)
