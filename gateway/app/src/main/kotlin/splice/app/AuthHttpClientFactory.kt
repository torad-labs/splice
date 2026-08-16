// NEW: the ONE auth/refresh/login HTTP client factory (CPU-spin follow-up, 2026-07-18). Every
// short-lived OAuth / device-code / token-refresh POST in :app used HttpClient(CIO); ktor CIO's
// socket writer busy-spins on a non-writable socket (macOS/kqueue) and is what melted the daemon's
// CPU on the streaming path — so these move to the JDK HttpClient engine too, which parks on
// backpressure. One factory so the engine choice and hard deadlines live in a single place, not
// five copies. HTTP/1.1 is pinned for parity with the CIO lineage.
package splice.app

import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.HttpTimeout

private const val AUTH_CONNECT_TIMEOUT_MS = 10_000L
private const val AUTH_REQUEST_TIMEOUT_MS = 30_000L
private const val AUTH_SOCKET_TIMEOUT_MS = 30_000L

/** JDK-HttpClient-backed client for :app's auth/refresh/login POSTs — never ktor CIO (see header).
 *
 *  A constructed collaborator rather than a free function (Kotlin style law, 2026-08-15): the
 *  sanctioned shape for a factory is an instance the caller holds, not a static entry point. */
internal class AuthHttpClientFactory {
    internal fun create(): HttpClient =
        HttpClient(Java) {
            install(HttpTimeout) {
                connectTimeoutMillis = AUTH_CONNECT_TIMEOUT_MS
                requestTimeoutMillis = AUTH_REQUEST_TIMEOUT_MS
                socketTimeoutMillis = AUTH_SOCKET_TIMEOUT_MS
            }
            engine {
                protocolVersion = java.net.http.HttpClient.Version.HTTP_1_1
            }
        }
}
