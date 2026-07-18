// NEW: credential shapes for the provider SPI (plan). Secrets never leave these types
// unmasked — introspection surfaces (/mgmt/auth, /api/auth) consume AuthDescription only.
package splice.core.auth

public sealed class Credentials {
    public data class Bearer(
        val token: String,
        val accountId: String? = null,
    ) : Credentials()

    public data class ApiKey(
        val key: String,
        val header: String = "Authorization",
        val prefix: String = "Bearer ",
    ) : Credentials()
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
}
