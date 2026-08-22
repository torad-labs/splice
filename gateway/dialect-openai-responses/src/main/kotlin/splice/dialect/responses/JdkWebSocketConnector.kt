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
        val builder = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(connectTimeoutMs))
            .build()
            .newWebSocketBuilder()
        headers.forEach { (k, v) -> builder.header(k, v) }
        return builder.buildAsync(uri, listener).await()
    }
}
