// NEW: (ws-transport WS-3, 2026-08-01) the Responses side of the WS seam — everything
// :gateway is forbidden to know (module law: :gateway may name only :core and :provider-spi).
// It owns the round-terminal vocabulary, the chaining frame, the connection identity, and the
// commit/clear of chaining state.
//
// THREE THINGS HERE EXIST BECAUSE ADVERSARIAL REVIEW FOUND THEM, each closing a silent-corruption
// class rather than an error:
//
//  1. CONNECTION IDENTITY IS NOT stablePromptCacheKey. That key is a hash of the FIRST USER
//     MESSAGE'S TEXT ONLY (ResponsesRequestBuilder.kt:752), so two conversations that open with
//     the same words are ONE key — by design, not by collision. Used as the chain key it would
//     hand conversation X's previous_response_id to conversation Y as its anchor, and the delta
//     classifier cannot catch it: the very thing that makes them share a key (identical first
//     item) is what makes Y's input look like a legitimate prefix-extension of X's. The session
//     id is mixed in, so distinct sessions can never share a chain.
//  2. PER-TURN HEADERS PARTICIPATE IN IDENTITY. A WebSocket's handshake headers are fixed for the
//     socket's life, but the head's per-turn set is not — the responses-lite marker keys off
//     `!meta.compact`, so a compact turn on the same conversation wants it OFF on a socket opened
//     with it ON. Reusing that socket silently sends a lite-SHAPED body without its lite marker.
//     Folding the header set into the key means a changed set opens a new connection instead.
//  3. THE KEY ENCODING IS LENGTH-PREFIXED, not separator-joined. Any separator can appear inside a
//     header value, and a joined key would then alias two different header sets onto one
//     connection. Length prefixes are injective for every possible String with no reserved
//     character at all.
package splice.dialect.responses

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.json.JsonObject
import splice.core.auth.Credentials
import splice.core.turn.TurnMeta
import splice.core.util.runCatchingCancellable
import splice.core.util.str
import splice.spi.WsRoundRunner
import splice.spi.WsUpstream

/** ws-transport WS-3 overlay, NULLABLE like its siblings — absent TOML keeps the provider default
 *  (false). A non-nullable field would stomp the provider default; that is exactly how
 *  supportsSummary became an unreachable dead lever. Lives HERE, not beside its sibling overlays,
 *  because ResponsesRequestBuilder.kt is at detekt's TooManyFunctions ceiling and this knob is
 *  WS-specific anyway. */
public fun ResponsesQuirks.withWebSocketToml(webSocket: Boolean?): ResponsesQuirks =
    copy(webSocket = webSocket ?: this.webSocket)

/** Round-terminal vocabulary, mirrored from ResponsesStreamTranslator.kt:352-353. Both sets end a
 *  round: the flow must complete on EITHER, or the connection never returns to the pool and the
 *  round hangs — strictly worse than an error. Failure terminals additionally let the head bail to
 *  SSE while the client has still seen nothing. */
internal object ResponsesRoundEnd {
    val SUCCESS = setOf("response.completed", "response.done", "response.incomplete")
    val FAILED = setOf("response.failed", "response.error", "error")
    val ALL = SUCCESS + FAILED
}

internal class ResponsesWsRunner(
    private val transport: WsUpstream,
    private val session: ResponsesWsSession,
    private val wssUrl: String,
    private val handshakeHeaders: (Credentials) -> Map<String, String>,
    private val log: (String) -> Unit = {},
) : WsRoundRunner {

    override suspend fun attempt(
        bodyJson: String,
        meta: TurnMeta,
        turnHeaders: Map<String, String>,
        creds: Credentials,
    ): Flow<JsonObject>? {
        val request = parseRequest(bodyJson) ?: return null
        val headers = handshakeHeaders(creds) + turnHeaders
        val key = connectionKey(meta, headers)
        val chain = chainKey(meta)
        // Committed at SEND time, read at TERMINAL time: the frame the chaining layer just built
        // determines what the next turn's prefix must be, and only a clean terminal may commit it.
        var pending: PendingCommit? = null
        val flow = transport.round(
            key = key,
            headers = headers,
            wssUrl = wssUrl,
            isTerminal = { it[FIELD_TYPE].str() in ResponsesRoundEnd.ALL },
        ) { conn ->
            val frame = session.frameFor(chain, request, conn.generation)
            pending = PendingCommit(request, conn.generation)
            if (frame.chained) log("[ws] ${logKey(key)} chained onto the previous response\n")
            frame.json
        }
        if (flow == null) {
            // The round never reached the wire; the chain state must not survive as an anchor for
            // a turn that will now be served over SSE with the full history.
            session.cleared(chain)
            return null
        }
        // Terminal observation lives HERE, not in the caller: the runner is the only party that
        // knows which events are terminal AND owns the chaining state they commit.
        return flow.onEach { event -> observeTerminal(chain, pending, event) }
    }

    private fun observeTerminal(chain: String, pending: PendingCommit?, event: JsonObject) {
        val type = event[FIELD_TYPE].str()
        if (type !in ResponsesRoundEnd.ALL) return
        val commit = pending
        if (type !in ResponsesRoundEnd.SUCCESS || commit == null) {
            session.cleared(chain)
            return
        }
        session.completed(chain, commit.request, (event["response"] as? JsonObject)?.get("id").str(), commit.generation)
    }

    override fun isFailureTerminal(event: JsonObject): Boolean =
        event[FIELD_TYPE].str() in ResponsesRoundEnd.FAILED

    override fun roundEnded(meta: TurnMeta, ok: Boolean) {
        if (!ok) session.cleared(chainKey(meta))
    }

    private data class PendingCommit(val request: JsonObject, val generation: Long)

    /** Session id + first-message hash. NEITHER alone is sufficient: the hash alone fuses two
     *  conversations that open with identical text (finding 1 above), and the session id alone is
     *  absent on clients that do not send one. */
    private fun chainKey(meta: TurnMeta): String =
        lengthPrefixed(listOf(meta.sessionId.orEmpty(), meta.conversationKey.orEmpty()))

    /** The chain key plus the handshake-relevant header set (finding 2): a turn whose headers
     *  differ must not ride a socket opened without them. */
    private fun connectionKey(meta: TurnMeta, headers: Map<String, String>): String =
        lengthPrefixed(
            listOf(chainKey(meta), meta.upstreamModel) +
                headers.toSortedMap().flatMap { (k, v) -> listOf(k, v) },
        )

    /** Injective for every String, with no reserved character (finding 3). */
    private fun lengthPrefixed(parts: List<String>): String =
        parts.joinToString("") { "${it.length}:$it" }

    /** The operator-facing form: keys are long and carry raw client text, so daemon.log gets a
     *  short stable digest instead of the key itself. */
    private fun logKey(key: String): String = "ws-" + Integer.toHexString(key.hashCode())

    /** A body we cannot parse is a body we must not chain: fall back to SSE, which sends the
     *  original bytes untouched. */
    private fun parseRequest(bodyJson: String): JsonObject? =
        runCatchingCancellable { responsesRequestJson.parseToJsonElement(bodyJson) as? JsonObject }
            .onFailure { log("[ws] unparseable request body — round rides SSE: ${it::class.simpleName}\n") }
            .getOrNull()

    private companion object {
        const val FIELD_TYPE = "type"
    }
}
