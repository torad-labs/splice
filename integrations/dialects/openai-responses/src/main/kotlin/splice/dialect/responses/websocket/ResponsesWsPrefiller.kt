package splice.dialect.responses.websocket

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import splice.core.perf.PerfKeys
import splice.core.perf.TurnPerf
import splice.core.turn.TurnMeta
import splice.core.util.JsonScalars
import splice.core.util.LogSink
import splice.dialect.responses.stream.ResponsesRoundEnd
import java.io.IOException

/** Owns the optional no-generation prefix round, separate from normal client-facing WS turns. */
internal class ResponsesWsPrefiller(
    private val enabled: Boolean,
    private val transport: WsUpstream,
    private val session: ResponsesWsSession,
    private val identity: ResponsesWsIdentity,
    private val metadata: ResponsesWsRequestMetadata,
    private val wssUrl: String,
    private val log: LogSink,
) {
    fun context(seed: PrefillSeed): PrefillContext? {
        if (!enabled || session.hasChain(seed.chain)) return null
        val prefillRequest = ResponsesWsPrefill.request(seed.request) ?: return null
        return PrefillContext(seed.chain, seed.key, seed.request, prefillRequest, seed.headers, seed.meta, seed.perf)
    }

    suspend fun run(context: PrefillContext): Boolean {
        var pending: ResponsesWsIdentity.PendingCommit? = null
        val attemptEpoch = session.epochOf(context.chain)
        val clientMetadata = metadata.forTurn(
            context.request,
            context.headers,
            context.meta.upstreamHeaders.snapshot(),
        )
        var accepted = false
        try {
            val round = prefillRound(context, clientMetadata)
            pending = round.commit
            val result = when {
                round.flow == null -> PrefillResult(false, null, "transport_unavailable")
                else -> {
                    context.perf?.add(PerfKeys.WS_PREFILL_ROUNDS, 1)
                    consume(round.flow, context.meta)
                }
            }
            val finalResult = when {
                !result.clean -> result
                pending == null -> PrefillResult(false, null, "prefill_commit_missing")
                else -> {
                    session.completed(context.chain, checkNotNull(pending), checkNotNull(result.responseId))
                    accepted = true
                    result
                }
            }
            if (finalResult.clean) {
                log("[ws] ${who(context.key, context.meta)} prefill complete code=clean_no_output\n")
            } else {
                bypass(context, finalResult.reason)
            }
            return finalResult.clean
        } finally {
            if (!accepted) session.clearedIfCurrent(context.chain, pending?.epoch ?: attemptEpoch)
        }
    }

    /** The key's digest and the client session, the form every socket line names. */
    private fun who(key: String, meta: TurnMeta): String =
        "${identity.logKey(key)} session=${meta.scope.sessionId ?: "-"}"

    private fun bypass(context: PrefillContext, reason: String): Boolean {
        context.perf?.add(PerfKeys.WS_PREFILL_BYPASSES, 1)
        log("[ws] ${who(context.key, context.meta)} prefill bypass code=$reason; the round goes to HTTP\n")
        return false
    }

    private suspend fun prefillRound(context: PrefillContext, clientMetadata: JsonObject?): PrefillRound {
        var commit: ResponsesWsIdentity.PendingCommit? = null
        val flow = transport.round(
            key = context.key,
            handshake = WsHandshake(wssUrl, context.headers),
            isTerminal = { event -> JsonScalars.str(event["type"]) in ResponsesRoundEnd.ALL },
            session = context.meta.scope.sessionId,
        ) { connection ->
            val built = session.frameAndEpoch(
                context.chain,
                context.prefillRequest,
                connection.generation,
                clientMetadata,
            )
            commit = ResponsesWsIdentity.PendingCommit(
                context.prefillRequest,
                connection.generation,
                built.epoch,
            )
            log("[ws] ${who(context.key, context.meta)} prefill send code=reusable_instruction_tool_prefix\n")
            built.frame.json
        }
        return PrefillRound(flow, commit)
    }

    private suspend fun consume(flow: Flow<JsonObject>, meta: TurnMeta): PrefillResult {
        var responseId: String? = null
        var emittedOutput = false
        return try {
            val completed = withTimeoutOrNull(PREFILL_TERMINAL_TIMEOUT_MS) {
                flow.collect { event ->
                    metadata.captureEvent(meta, event)
                    emittedOutput = emittedOutput || ResponsesWsPrefill.isOutputEvent(event)
                    responseId = ResponsesWsPrefill.responseIdIfCleanTerminal(event) ?: responseId
                }
                true
            } ?: false
            result(completed, emittedOutput, responseId, false)
        } catch (_: IOException) {
            log("[ws] prefill transport failed type=IOException\n")
            result(false, emittedOutput, null, true)
        }
    }

    private fun result(
        completed: Boolean,
        emittedOutput: Boolean,
        responseId: String?,
        transportError: Boolean,
    ): PrefillResult = when {
        transportError -> PrefillResult(false, null, "prefill_transport_error")
        !completed -> PrefillResult(false, null, "prefill_terminal_timeout")
        emittedOutput -> PrefillResult(false, null, "unexpected_prefill_output")
        responseId == null -> PrefillResult(false, null, "prefill_not_cleanly_completed")
        else -> PrefillResult(true, responseId, "clean_no_output")
    }

    data class PrefillContext(
        val chain: String,
        val key: String,
        val request: JsonObject,
        val prefillRequest: JsonObject,
        val headers: Map<String, String>,
        val meta: TurnMeta,
        val perf: TurnPerf?,
    )

    data class PrefillSeed(
        val request: JsonObject,
        val chain: String,
        val key: String,
        val headers: Map<String, String>,
        val meta: TurnMeta,
        val perf: TurnPerf?,
    )

    private data class PrefillResult(val clean: Boolean, val responseId: String?, val reason: String)

    private data class PrefillRound(val flow: Flow<JsonObject>?, val commit: ResponsesWsIdentity.PendingCommit?)
}

// why: keep a silent upstream from holding the user's turn indefinitely during internal setup.
private const val PREFILL_TERMINAL_TIMEOUT_MS = 30_000L
