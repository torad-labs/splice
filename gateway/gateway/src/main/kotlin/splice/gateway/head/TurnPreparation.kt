// PORT-OF: splice/gateway/head/HeadServer.kt (prepareTurn, Preparation/Ready/Rejected) @ 1caedd6 —
// invariants unchanged: parse → validate → classify → build as ONE named phase, with a single scan
// of system + last-user text shared by classification and shadow instrumentation, and the
// forwarded-header merge whose operand order (`prepared.extraHeaders + forwardedClientHeaders`)
// is what makes the CALLER's value replace the provider's configured default. Split out (HD-24);
// Preparation WIDENED private nested -> internal, because HeadAdmission dispatches on it.
//
// TWO MORE OUTCOMES (2026-09-05), both "this head answers without an upstream turn": Local — the
// activity side query Claude Code sends every 30 s (ActivityLabel), and Replay — a compaction retry
// whose bytes match a compaction that outlived its first client (CompactionReplay).
package splice.gateway.head

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import splice.core.parse.AnthropicTurnBody
import splice.core.perf.PerfKeys
import splice.core.perf.TurnPerf
import splice.core.wire.AnthropicRequest
import splice.gateway.compact.CompactClassifier
import splice.gateway.wire.FrameRecording
import splice.spi.BuiltTurn
import splice.spi.Provider

internal sealed class Preparation {
    data class Ready(val built: BuiltTurn, val stream: Boolean) : Preparation()
    data class Rejected(val message: String) : Preparation()

    /** Answered by the proxy itself: the activity side query (ActivityLabel). No upstream turn. */
    data class Local(val text: String, val model: String, val sessionId: String?, val stream: Boolean) : Preparation()

    /** A byte-identical retry of a compaction whose first client gave up: served from its recording. */
    data class Replay(val recording: FrameRecording, val key: String, val sessionId: String?) : Preparation()
}

internal class TurnPreparation(
    private val provider: Provider,
    private val deps: HeadDeps,
    private val bodyReader: RequestBodyReader,
    private val bodyParse: AnthropicBodyParse,
    private val clientAuth: ClientAuth,
    private val replay: CompactionReplay = CompactionReplay(),
) {
    private val compactClassifier = CompactClassifier()
    private val activityLabel = ActivityLabel()

    suspend fun prepareTurn(call: ApplicationCall, perf: TurnPerf): Preparation {
        val body = bodyReader.receiveBodyBounded(call, deps.maxRequestBytes)
        perf.mark(PerfKeys.RECV)
        perf.setCount(PerfKeys.REQ_BYTES, body.bytes.toLong())
        val parsing = bodyParse.parse(body.text)
        val parsed = parsing.getOrNull() ?: return rejectedBody(call, body, parsing.exceptionOrNull())
        val unwrappedModel = provider.catalog.unwrap(parsed.typed.model)
        if (!provider.catalog.contains(parsed.typed.model)) {
            return Preparation.Rejected("this head proxies its own models only; got $unwrappedModel")
        }
        val sessionId = call.request.headers[SESSION_HEADER]
        val label = activityLabel.labelFor(parsed.typed)
        return if (label != null) local(label, parsed.typed, sessionId, perf) else build(call, parsed, sessionId, perf)
    }

    // The activity side query never reaches a model: see ActivityLabel for the measurement.
    private fun local(
        label: String,
        request: AnthropicRequest,
        sessionId: String?,
        perf: TurnPerf,
    ): Preparation.Local {
        perf.mark(PerfKeys.PARSE)
        deps.log("[${provider.key}] activity label answered locally: \"$label\" (${who(sessionId)}no upstream turn)\n")
        return Preparation.Local(label, request.model, sessionId, request.stream)
    }

    private fun build(
        call: ApplicationCall,
        parsed: AnthropicTurnBody,
        sessionId: String?,
        perf: TurnPerf,
    ): Preparation {
        // One scan of system + last-user text: classification and shadow instrumentation share it.
        val compactProbe = compactClassifier.classifyCompact(parsed.typed)
        deps.shadow.record(parsed.typed, compactProbe)
        perf.mark(PerfKeys.PARSE)
        val fromProvider = provider.buildTurn(parsed, compactProbe.compact, sessionId)
        // Every dialect's turn names its client session (2026-09-02): only the responses dialect
        // kept the id on its meta, so a chat or passthrough head's abort could not be tied to a
        // session. Stamped here, once, when the provider left it null.
        val prepared = if (fromProvider.meta.sessionId == null && sessionId != null) {
            fromProvider.copy(meta = fromProvider.meta.copy(sessionId = sessionId))
        } else {
            fromProvider
        }
        // Per-turn headers already outrank the provider's own in TurnDriver's merge, so a forwarded
        // value REPLACES a configured default (e.g. the caller's anthropic-version wins over the
        // provider's), and UpstreamClient folds the casing.
        val built = if (deps.forwardClientAuth) {
            prepared.copy(extraHeaders = prepared.extraHeaders + clientAuth.forwardedClientHeaders(call))
        } else {
            prepared
        }
        perf.mark(PerfKeys.BUILD)
        // A compaction retry whose bytes match a compaction that outlived its first client is
        // answered from that recording (TurnStreamer records it, LocalResponses replays it).
        val replayed = if (built.meta.compact && parsed.typed.stream) replayFor(built) else null
        return replayed ?: Preparation.Ready(built, parsed.typed.stream)
    }

    private fun replayFor(built: BuiltTurn): Preparation.Replay? {
        val key = replay.key(built.meta.sessionId, built.requestBody.toString()) ?: return null
        val recording = replay.lookup(key) ?: return null
        val state = if (recording.isComplete) "finished" else "still running"
        deps.log(
            "[${provider.key}] compaction retry matches a detached compaction ($state, " +
                "${who(built.meta.sessionId)}replaying its answer, no upstream turn)\n",
        )
        return Preparation.Replay(recording, key, built.meta.sessionId)
    }

    // The class, never the content (safe-failure-render): the body is the user's transcript. The
    // declared length beside the read one tells a truncated read from a malformed body.
    private fun rejectedBody(call: ApplicationCall, body: ReceivedBody, cause: Throwable?): Preparation.Rejected {
        val declared = call.request.headers[HttpHeaders.ContentLength] ?: "no content-length"
        val why = cause?.let { it::class.simpleName } ?: "no exception"
        deps.log(
            "[${provider.key}] request rejected: invalid request body " +
                "(${body.bytes} bytes read, $declared declared, $why)\n",
        )
        return Preparation.Rejected("invalid request body")
    }

    private fun who(sessionId: String?): String = sessionId?.let { "session ${it.take(TAG_CHARS)}, " } ?: ""
}

private const val SESSION_HEADER = "x-claude-code-session-id"
private const val TAG_CHARS = 8
