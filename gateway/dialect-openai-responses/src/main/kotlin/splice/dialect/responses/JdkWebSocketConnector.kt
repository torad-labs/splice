// PORT-OF: WsUpstream.kt @ 81ff23c — invariants: none beyond the JDK handshake itself; a fresh
// instance per default-argument evaluation is free (no state, HttpClient built per call).
package splice.dialect.responses

import kotlinx.coroutines.future.await
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.time.Duration

/**
 * The real JDK handshake, behind a type because Kotlin main sources carry no `companion` blocks and
 * this is the DEFAULT VALUE of [WsUpstream.connector] — a default argument is evaluated before the
 * instance exists, so it cannot call an instance member ("Cannot access '<this>' before the instance
 * has been initialized"). A fresh instance per default-argument evaluation is free: the type holds
 * no state, and the HttpClient it builds was already per-call.
 */
internal class JdkWebSocketConnector {

    internal suspend fun jdkConnect(
        uri: URI,
        headers: Map<String, String>,
        listener: WebSocket.Listener,
        connectTimeoutMs: Long,
    ): WebSocket {
        // DR-185: BOTH timeouts, because they bound different halves and only the first was set.
        // HttpClient.connectTimeout covers the TCP connect; the HTTP/1.1 Upgrade exchange that
        // follows it is bounded by WebSocket.Builder.connectTimeout, which was never called — so a
        // peer that ACCEPTED the socket and then said nothing left buildAsync's future pending with
        // nothing thrown. Measured on this JDK (21.0.11) against a server that accepts and stalls:
        // with the HttpClient timeout alone the future was still pending after 6s; with the builder
        // timeout it failed with HttpTimeoutException at 1503ms against a 1500ms budget.
        //
        // Same window and same consequence as DR-182's unbounded send: pool.acquire never returns,
        // so the round cannot decline to SSE, and the only remaining bound is the whole-turn
        // totalCap — a black-holed handshake burned the entire upstream timeout and failed the turn
        // instead of degrading in ten seconds. [connectTimeoutMs] was always the right budget; it
        // was going to the wrong builder.
        val builder = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(connectTimeoutMs))
            .build()
            .newWebSocketBuilder()
            .connectTimeout(Duration.ofMillis(connectTimeoutMs))
        headers.forEach { (k, v) -> builder.header(k, v) }
        return builder.buildAsync(uri, listener).await()
    }
}
