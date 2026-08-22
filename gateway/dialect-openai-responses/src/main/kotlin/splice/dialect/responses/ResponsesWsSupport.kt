// NEW: Responses WebSocket runner construction + handshake auth. Split from
// ResponsesProvider.kt so that class is not billed for a Credentials-typed
// handshake (concentration HIGH, 2026-08-19). The builder stays PURE; this
// file owns only the WS overlay that the SSE path never sees.
package splice.dialect.responses

import splice.core.auth.Credentials
import splice.core.util.LogSink
import splice.spi.WsRoundRunner

/** The v2 Responses-WebSocket beta value codex-rs sends (codex-rs/core/src/client.rs:155),
 *  confirmed accepted by the live backend in the WS-0 spike. */
private const val WS_BETA_HEADER = "responses_websockets=2026-02-06"

/** Per-handshake extra headers beyond the WS Authorization set — the same role
 *  Provider.extraHeaders plays on the SSE path. */
internal fun interface WsExtraHeaders {
    operator fun invoke(creds: Credentials): Map<String, String>
}

internal class ResponsesWsSupport(
    private val log: LogSink,
    private val extraHeaders: WsExtraHeaders,
) {
    /** Non-null ONLY when the operator opted in AND this provider's upstream was actually
     *  probed. With the quirk off no WsUpstream is constructed. */
    fun runner(webSocket: Boolean, supportsWebSocket: Boolean, upstreamUrl: String): WsRoundRunner? {
        if (!webSocket || !supportsWebSocket) return null
        return ResponsesWsRunner(
            transport = WsUpstream(log = log),
            session = ResponsesWsSession(),
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
                    // Forward mode is an anthropic-passthrough concept; this WS overlay is
                    // codex-only and never sees it. Emit nothing rather than invent a header.
                    Credentials.ClientForwarded -> emptyMap()
                }
                auth + extraHeaders(creds) + mapOf("OpenAI-Beta" to WS_BETA_HEADER)
            },
            log = log,
        )
    }
}
