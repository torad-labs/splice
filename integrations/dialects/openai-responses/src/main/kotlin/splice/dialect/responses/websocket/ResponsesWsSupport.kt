// NEW: Responses WebSocket runner construction + handshake auth. Split from
// ResponsesProvider.kt so that class is not billed for a Credentials-typed
// handshake (concentration HIGH, 2026-08-19). The builder stays PURE; this
// file owns only the WS overlay that the SSE path never sees.
package splice.dialect.responses.websocket

import splice.core.auth.Credentials
import splice.core.util.LogSink
import splice.upstream.WsRoundRunner

/** Per-handshake extra headers beyond the WS Authorization set — the same role
 *  Provider.extraHeaders plays on the SSE path. */
internal fun interface WsExtraHeaders {
    operator fun invoke(creds: Credentials): Map<String, String>
}

internal class ResponsesWsSupport(
    private val log: LogSink,
    private val extraHeaders: WsExtraHeaders,
    private val policy: ResponsesWebSocketPolicy = ResponsesWebSocketPolicy(),
) {
    /** Non-null ONLY when the operator opted in AND this provider's upstream was actually
     *  probed. With the quirk off no WsUpstream is constructed. */
    fun runner(
        webSocket: Boolean,
        supportsWebSocket: Boolean,
        upstreamUrl: String,
        webSocketPrefill: Boolean = false,
        /** The idle close and the max socket age in milliseconds, from the [defaults] knobs; null keeps a default. */
        lifetime: Pair<Long?, Long?> = null to null,
    ): WsRoundRunner? {
        if (!webSocket || !supportsWebSocket) return null
        val (idleMs, ageMs) = lifetime
        return ResponsesWsRunner(
            transport = WsUpstream(
                log = log,
                lifetime = WsLifetime(
                    maxAgeMs = ageMs ?: MAX_SOCKET_AGE_MS,
                    idleCloseMs = idleMs ?: SOCKET_IDLE_CLOSE_MS,
                ),
            ),
            session = ResponsesWsSession(policy = policy),
            // Same path as upstreamUrl, on the WebSocket scheme (live spike receipt).
            wssUrl = upstreamUrl.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://"),
            // Authorization is added HERE because the SSE path gets it from
            // UpstreamRequest.applyAuth, which the WS path never goes through — without it every
            // handshake 401s and the overlay falls back to SSE forever, i.e. the feature simply
            // cannot work (found while adjudicating the review of #72).
            handshakeHeaders = { creds ->
                val auth = when (creds) {
                    is Credentials.Bearer -> mapOf("Authorization" to "Bearer ${creds.token}")
                    is Credentials.ApiKey -> mapOf(creds.header to "${creds.prefix}${creds.key}")
                    // Forward mode is an anthropic-passthrough concept. Emit nothing rather
                    // than invent a header if a Responses provider receives it.
                    Credentials.ClientForwarded -> emptyMap()
                }
                auth + extraHeaders(creds) + policy.negotiationHeaders
            },
            webSocketPrefill = webSocketPrefill,
            webSocketPolicy = policy,
            log = log,
        )
    }
}
