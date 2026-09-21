// NEW: credential shapes for the provider SPI (plan). Secrets never leave these types
// unmasked — introspection surfaces (/mgmt/auth, /api/auth) consume AuthDescription only.
package splice.core.auth

public sealed class Credentials {
    public data class Bearer(
        val token: String,
        val accountId: String? = null,
    ) : Credentials() {
        /** This file's first line is the invariant: secrets never leave these types unmasked. The
         *  GENERATED toString breaks it — one `"...: $creds"` in a log line, an exception or a test
         *  assertion puts the bearer token in a file. accountId is not a secret and stays readable. */
        override fun toString(): String = "Bearer(token=<redacted>, accountId=$accountId)"
    }

    public data class ApiKey(
        val key: String,
        val header: String = "Authorization",
        val prefix: String = "Bearer ",
    ) : Credentials() {
        /** As [Bearer]: the key is the secret, the header and prefix are wire shape and stay readable. */
        override fun toString(): String = "ApiKey(key=<redacted>, header=$header, prefix=$prefix)"
    }

    /**
     * FORWARD MODE: the head holds no credential of its own — the CALLER's own auth headers ride
     * to the upstream untouched (campaign claude-head: a claude head serves Claude Code against
     * api.anthropic.com on the client's native login, so splice never reads, stores or refreshes
     * an Anthropic credential).
     *
     * Deliberately a SEALED VARIANT rather than a sentinel string or a null: every consumer of
     * [Credentials] matches exhaustively, so adding it turned each auth-writing site into a
     * compile error that had to be answered — which is exactly how a "forward mode" must land.
     * It carries no secret because there is none to carry.
     */
    public data object ClientForwarded : Credentials()
}

/** Masked, wire-safe view of an auth state (never a secret). */
public data class AuthDescription(
    val present: Boolean,
    val kind: String,
    val fields: Map<String, String> = emptyMap(),
)

/** Provider auth SPI: resolve credentials (cached) + masked introspection. */
public interface AuthProvider {
    public suspend fun credentials(): Credentials?

    public suspend fun describe(): AuthDescription
}

/** An AuthProvider that can refresh its credentials (single-flight at the impl). */
public interface RefreshableAuthProvider : AuthProvider {
    public suspend fun refresh(): Credentials?

    /**
     * Provider-specific veto for failures that resemble expired credentials at the HTTP layer
     * but are actually permanent entitlement errors. The transport owns the common predicate;
     * providers only narrow it.
     */
    public fun allowRefreshAfterFailure(status: Int, body: String): Boolean = true

    /**
     * V4-73: does this vendor report a QUOTA EXHAUSTION behind a status the HTTP layer does not
     * name as one? xAI answers a spent account with 403 `personal-team-blocked:spending-limit`, and
     * the operator's law is that credits exhaustion is RETRIED until the credits are back — not
     * surfaced as a terminal invalid_request that bypasses the cooldown, the pool and every client
     * retry. One vendor-neutral question, asked at the transport's single failure site; the phrases
     * that answer it live in the vendor module (grok's entitlement list), never here.
     *
     *  Default false: a provider that has not declared the shape keeps today's behaviour exactly,
     *  which is what makes this safe to add to a port every provider implements.
     */
    public fun isQuotaExhausted(status: Int, body: String): Boolean = false
}
