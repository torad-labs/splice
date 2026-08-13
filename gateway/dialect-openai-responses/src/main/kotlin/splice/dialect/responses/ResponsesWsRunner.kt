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
import java.security.MessageDigest

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
        // No parseable body, or no isolation identity => ride SSE. The second is not a weaker key
        // but NO key: without it, conversations sharing a first message would share a chain.
        val request = parseRequest(bodyJson)
        val chain = chainKey(meta)
        if (request == null || chain == null) return null
        val headers = handshakeHeaders(creds) + turnHeaders
        val key = connectionKey(chain, meta, headers)
        // Committed at SEND time, read at TERMINAL time: the frame the chaining layer just built
        // determines what the next turn's prefix must be, and only a clean terminal may commit it.
        var pending: PendingCommit? = null
        val flow = transport.round(
            key = key,
            headers = headers,
            wssUrl = wssUrl,
            isTerminal = { it[FIELD_TYPE].str() in ResponsesRoundEnd.ALL },
        ) { conn ->
            // F7: frame + epoch captured atomically. Two calls (frameFor then epochOf) left a
            // window where a concurrent clear bumped the epoch after the frame was built on
            // now-stale context, and the post-bump epoch still matched at commit — resurrecting the
            // state the clear existed to bar.
            val built = session.frameAndEpoch(chain, request, conn.generation)
            pending = PendingCommit(request, conn.generation, built.epoch)
            if (built.frame.chained) log("[ws] ${logKey(key)} chained onto the previous response\n")
            built.frame.json
        }
        // No clear here: the transport declining (busy / connect failure) is a BYPASS, and the head
        // calls roundBypassed for exactly that. Clearing in both places bumped the epoch twice for
        // one logical event and, on the busy path, threw away a concurrent round's valid state
        // before its terminal could even be observed (review of #72).
        if (flow == null) return null
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
        session.completed(
            chain,
            commit.request,
            (event["response"] as? JsonObject)?.get("id").str(),
            commit.generation,
            commit.epoch,
        )
    }

    override fun isFailureTerminal(event: JsonObject): Boolean =
        event[FIELD_TYPE].str() in ResponsesRoundEnd.FAILED

    override fun roundEnded(meta: TurnMeta, ok: Boolean) {
        if (!ok) chainKey(meta)?.let { session.cleared(it) }
    }

    /** A round this overlay did NOT serve still advances the conversation, so the chain must be
     *  dropped (review of #72): the server's chained context stops at the last WS round, and the
     *  delta classifier would then drop the SSE turn's assistant message as "server-held" when it
     *  is nothing of the sort — a silent context loss, not a miss. */
    override fun roundBypassed(meta: TurnMeta) {
        chainKey(meta)?.let { session.cleared(it) }
    }

    private data class PendingCommit(val request: JsonObject, val generation: Long, val epoch: Long)

    /** Session id + first-message hash, or NULL when either is missing.
     *
     *  Null means "do not chain, and do not reuse a socket" — enforced by the callers. Substituting
     *  empty strings (the first cut, caught in review of #72) silently re-opened the exact collision
     *  the two-part key exists to close: with no session id, every conversation whose first message
     *  hashes the same shares one chain, and one conversation's server-side context answers another.
     *  A missing isolation value is not a weaker key, it is NO key. */
    private fun chainKey(meta: TurnMeta): String? {
        val session = meta.sessionId?.takeIf { it.isNotEmpty() } ?: return null
        val conversation = meta.conversationKey?.takeIf { it.isNotEmpty() } ?: return null
        return lengthPrefixed(listOf(session, conversation))
    }

    /** The chain key plus a DIGEST of the handshake header set. Headers must participate in
     *  identity (a turn needing a different set must not ride a socket opened without it), but they
     *  carry the Authorization bearer token, and this key reaches daemon.log on the busy/connect
     *  paths. Hashing keeps identity exact while making it structurally impossible for a credential
     *  to be logged — safer than remembering to redact at every call site (review of #72). */
    private fun connectionKey(chain: String, meta: TurnMeta, headers: Map<String, String>): String =
        lengthPrefixed(listOf(chain, meta.upstreamModel, headerDigest(headers)))

    private fun headerDigest(headers: Map<String, String>): String {
        val canonical = lengthPrefixed(headers.toSortedMap().flatMap { (k, v) -> listOf(k, v) })
        val bytes = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return bytes.take(DIGEST_BYTES).joinToString("") { "%02x".format(it) }
    }

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

        /** 8 bytes of SHA-256: collision-free enough to key a per-head connection pool, and short
         *  enough that the key stays readable. */
        const val DIGEST_BYTES = 8
    }
}
