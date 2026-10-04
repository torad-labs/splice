// NEW: the OAuth flows' own ports, moved out of app/AppPorts.kt with the flows (LAYOUT-01). Named by
// role, never by shape: two request builders and one response parser share `(String) -> String`, and
// sharing a type would let a credential-shaped string be posted upstream or a form body be written
// to auth.json.
package splice.oauth

import io.ktor.client.statement.HttpResponse

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

/**
 * The refresh POST itself — one HTTP attempt, no retry policy and no classification.
 *
 * Deliberately the bare hop: `RefreshRetry` owns how many times it runs and how long it waits
 * between runs, and a `call` that retried internally would multiply against that budget invisibly.
 * A throw out of it is treated as a RETRY, not a permanent failure, so a network blip must surface
 * as an exception here rather than as a synthesized failure response.
 */
internal fun interface RefreshPost {
    suspend operator fun invoke(): HttpResponse
}

/**
 * Reads one refresh response and decides whether the loop is DONE or should try again.
 *
 * The decision, not the transport — which is what makes an unrecognized status retryable in one
 * provider and terminal in another without either of them re-implementing the backoff curve. Like
 * [RefreshPost], a throw out of it means retry.
 */
internal fun interface RefreshClassify<T> {
    suspend operator fun invoke(response: HttpResponse): RefreshStep<T>
}
