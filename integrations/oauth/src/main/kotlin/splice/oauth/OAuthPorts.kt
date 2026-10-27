// NEW: the OAuth flows' own ports, moved out of app/AppPorts.kt with the flows (LAYOUT-01). Named by
// role, never by shape: two request builders and one response parser share `(String) -> String`, and
// sharing a type would let a credential-shaped string be posted upstream or a form body be written
// to auth.json.
package splice.oauth

/**
 * Builds the x-www-form-urlencoded token-exchange body for a real authorization code.
 *
 * Per-provider because the grant bodies genuinely differ (codex and grok disagree on which of
 * `client_id`, `code_verifier` and `redirect_uri` they require), and the ENCODING happens inside —
 * an unencoded `code` reaching the wire is an auth failure the operator sees as an opaque 400.
 */
public fun interface ExchangeForm {
    public operator fun invoke(code: String): String
}

/**
 * Turns a token-endpoint SUCCESS body into the exact `auth.json` content to persist.
 *
 * ONE role under two spellings until now: [OAuthLoginFlow]'s browser-redirect spec and
 * [DeviceLoginFlow]'s device-code spec each declared their own `toAuthJson: (String) -> String`,
 * and they are the same contract — the two flows differ in how the grant is OBTAINED, never in what
 * a provider's credential file looks like afterwards. Unified here, and both specs now name it.
 *
 * The result is written through `SecureFile.writeAtomic0600`, so what comes out of one of these is
 * a credential at rest.
 */
public fun interface AuthJsonFromResponse {
    public operator fun invoke(responseBody: String): String
}
