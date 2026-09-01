// NEW: (ws-transport WS-3, 2026-08-01) the Responses side of the WS seam — everything
// :gateway is forbidden to know (module law: :gateway may name only :core and :provider-spi).
// It owns the round-terminal vocabulary, the chaining frame, the connection identity, and the
// commit/clear of chaining state.
//
// THREE THINGS HERE EXIST BECAUSE ADVERSARIAL REVIEW FOUND THEM, each closing a silent-corruption
// class rather than an error:
//
//  1. CONNECTION IDENTITY IS NOT stablePromptCacheKey. That key is a hash of the FIRST USER
//     MESSAGE'S TEXT ONLY (ResponsesRequestBuilder.kt, ResponsesStableIds), so two conversations
//     that open with the same words are ONE key — by design, not by collision. Used as the chain
//     key it would hand conversation X's previous_response_id to conversation Y as its anchor, and
//     the delta classifier cannot catch it: the very thing that makes them share a key (identical
//     first item) is what makes Y's input look like a legitimate prefix-extension of X's. The
//     session id is mixed in, so distinct sessions can never share a chain.
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

import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.json.JsonObject
import splice.core.auth.Credentials
import splice.core.turn.TurnMeta
import splice.core.util.JsonScalars
import splice.core.util.LogSink
import splice.spi.WsRound
import splice.spi.WsRoundAbort
import splice.spi.WsRoundRunner

// ResponsesRoundEnd + HandshakeHeaders live in ResponsesRoundEnd.kt (concentration, 2026-08-19).
// Identity + terminal observation live in ResponsesWsIdentity.kt (concentration, 2026-08-19).

internal class ResponsesWsRunner(
    private val transport: WsUpstream,
    private val session: ResponsesWsSession,
    private val wssUrl: String,
    private val handshakeHeaders: HandshakeHeaders,
    private val log: LogSink = LogSink {},
) : WsRoundRunner {

    private val identity = ResponsesWsIdentity(session, log)

    override suspend fun attempt(
        bodyJson: String,
        meta: TurnMeta,
        turnHeaders: Map<String, String>,
        creds: Credentials,
    ): WsRound? {
        // No parseable body, or no isolation identity => ride SSE. The second is not a weaker key
        // but NO key: without it, conversations sharing a first message would share a chain.
        val request = identity.parseRequest(bodyJson)
        val chain = identity.chainKey(meta)
        if (request == null || chain == null) return null
        val headers = handshakeHeaders(creds) + turnHeaders
        val key = identity.connectionKey(chain, meta, headers)
        // Committed at SEND time, read at TERMINAL time: the frame the chaining layer just built
        // determines what the next turn's prefix must be, and only a clean terminal may commit it.
        var pending: ResponsesWsIdentity.PendingCommit? = null
        // The round's abort, closed over the ONE connection this attempt got and the LEASE it held
        // when it got it. No registry and no key: see WsRound's doc for why a chain-keyed lookup
        // aborts the wrong socket, and the lease for why "still my round" is not the same question
        // as "not finished yet". Default no-op covers the paths that never reach a connection.
        var abort = WsRoundAbort { }
        val flow = transport.round(
            key = key,
            headers = headers,
            wssUrl = wssUrl,
            isTerminal = { JsonScalars.str(it[FIELD_TYPE]) in ResponsesRoundEnd.ALL },
        ) { conn ->
            // Armed HERE because this is the only place the transport hands the connection out and
            // the head never sees one (module law).
            val lease = conn.lease.get()
            abort = WsRoundAbort { if (conn.lease.get() == lease) conn.kill() }
            // F7: frame + epoch captured atomically. Two calls (frameFor then epochOf) left a
            // window where a concurrent clear bumped the epoch after the frame was built on
            // now-stale context, and the post-bump epoch still matched at commit — resurrecting the
            // state the clear existed to bar.
            val built = session.frameAndEpoch(chain, request, conn.generation)
            pending = ResponsesWsIdentity.PendingCommit(request, conn.generation, built.epoch)
            if (built.frame.chained) log("[ws] ${identity.logKey(key)} chained onto the previous response\n")
            built.frame.json
        }
        // No clear here: the transport declining (busy / connect failure) is a BYPASS, and the head
        // calls roundBypassed for exactly that. Clearing in both places bumped the epoch twice for
        // one logical event and, on the busy path, threw away a concurrent round's valid state
        // before its terminal could even be observed (review of #72).
        if (flow == null) return null
        // Terminal observation lives HERE, not in the caller: the runner is the only party that
        // knows which events are terminal AND owns the chaining state they commit.
        return WsRound(
            events = flow.onEach { event -> identity.observeTerminal(chain, pending, event) },
            abort = abort,
        )
    }

    override fun isFailureTerminal(event: JsonObject): Boolean =
        JsonScalars.str(event[FIELD_TYPE]) in ResponsesRoundEnd.FAILED

    override fun roundEnded(meta: TurnMeta, ok: Boolean) {
        if (!ok) identity.chainKey(meta)?.let { session.cleared(it) }
    }

    /** A round this overlay did NOT serve still advances the conversation, so the chain must be
     *  dropped (review of #72): the server's chained context stops at the last WS round, and the
     *  delta classifier would then drop the SSE turn's assistant message as "server-held" when it
     *  is nothing of the sort — a silent context loss, not a miss. */
    override fun roundBypassed(meta: TurnMeta) {
        identity.chainKey(meta)?.let { session.cleared(it) }
    }
}

private const val FIELD_TYPE = "type"
