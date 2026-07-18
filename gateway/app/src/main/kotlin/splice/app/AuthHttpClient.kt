// NEW: the ONE auth/refresh/login HTTP client factory (CPU-spin follow-up, 2026-07-18). Every
// short-lived OAuth / device-code / token-refresh POST in :app used HttpClient(CIO); ktor CIO's
// socket writer busy-spins on a non-writable socket (macOS/kqueue) and is what melted the daemon's
// CPU on the streaming path — so these move to the JDK HttpClient engine too, which parks on
// backpressure. One factory so the engine choice lives in a single place, not five copies.
// HTTP/1.1 pinned for parity with the CIO lineage. These calls are low-volume and short-lived, so
// no pool/timeout tuning is carried over (matching the prior bare HttpClient(CIO)).
package splice.app

import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java

/** JDK-HttpClient-backed client for :app's auth/refresh/login POSTs — never ktor CIO (see header). */
internal fun authHttpClient(): HttpClient =
    HttpClient(Java) {
        engine {
            protocolVersion = java.net.http.HttpClient.Version.HTTP_1_1
        }
    }
