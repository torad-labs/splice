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
//
// V4-130: every parsed request is also where the console's session facts are observed, because this
// is the one place that holds the typed request and the session header together: SendMessage edges
// (MessageEdges), the locally answered activity label, and a near-miss label query sent upstream.
//
// V4-131: a session bound to a team slot gets the slot's text (SlotInstructions) appended after the
// head's own layers, in APPEND mode whatever the head's system_prompt_mode, resolved per turn so an
// edit applies on the next turn. The turn whose slot text changed is marked in its perf row
// (SLOT_PROMPT_CHANGED): it is the one cold-cache turn an edit costs.
//
// V4-160 (concentration, 2026-09-18): applySystemPrompt and applySlotPrompt moved verbatim to
// TurnPrompts.kt; this file still calls them in the same order.
//
// V4-165 (concentration, 2026-09-19): building the provider's turn (compaction tail, request hash,
// prompt layers) moved verbatim to ProviderTurnBuild.kt, which also owns the guard that gives back
// what a provider's turn holds (BuiltTurn.onEnd) when preparation fails before the drive. A replayed
// compaction is never driven, so its build's hold ends here.
package splice.head.turn

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.uri
import splice.core.parse.AnthropicTurnBody
import splice.core.perf.PerfKeys
import splice.core.perf.TurnPerf
import splice.core.wire.AnthropicRequest
import splice.head.ActivityLabel
import splice.head.AnthropicBodyParse
import splice.head.ClientAuth
import splice.head.HeadDeps
import splice.head.MessageEdges
import splice.head.ReceivedBody
import splice.head.RequestBodyReader
import splice.head.compact.CompactClassifier
import splice.head.compaction.CompactionReplay
import splice.head.wire.ClientInbound
import splice.head.wire.FrameRecording
import splice.upstream.BuiltTurn
import splice.upstream.Provider
import splice.upstream.transport.HeaderRedaction

internal sealed class Preparation {
    /** [inbound] is the request as it arrived, kept ONLY for a head whose trace is on (V4-174):
     *  null on every other head, so the body is not held twice for a turn nothing will read. */
    data class Ready(val built: BuiltTurn, val stream: Boolean, val inbound: ClientInbound?) : Preparation()
    data class Rejected(val message: String) : Preparation()

    /** Answered by the proxy itself: the activity side query (ActivityLabel). No upstream turn. */
    data class Local(val text: String, val model: String, val sessionId: String?, val stream: Boolean) : Preparation()

    /** A byte-identical retry of a compaction whose first client gave up: served from its recording.
     *  [model] is the row id the retry asked for, for the honest error frame a follower is sealed
     *  with when the recording ends without a clean terminal. */
    data class Replay(
        val recording: FrameRecording,
        val key: String,
        val sessionId: String?,
        val model: String,
    ) : Preparation()
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
    private val messageEdges = MessageEdges(deps.seams.events)
    private val providerTurns = ProviderTurnBuild(provider, deps, replay)

    suspend fun prepareTurn(call: ApplicationCall, perf: TurnPerf): Preparation {
        val sessionId = call.request.headers[SESSION_HEADER]?.takeIf(String::isNotBlank)
        deps.seams.clientVersions.observe(sessionId, call.request.headers[HttpHeaders.UserAgent])
        val body = bodyReader.receiveBodyBounded(call, deps.policy.maxRequestBytes)
        perf.mark(PerfKeys.RECV)
        perf.setCount(PerfKeys.REQ_BYTES, body.bytes.toLong())
        val parsing = bodyParse.parse(body.text)
        val parsed = parsing.getOrNull() ?: return rejectedBody(call, body, parsing.exceptionOrNull())
        val inbound = deps.stores.trace?.let { inbound(call, body.text) }
        val unwrappedModel = provider.catalog.unwrap(parsed.typed.model)
        if (!provider.catalog.contains(parsed.typed.model)) {
            return Preparation.Rejected("this head proxies its own models only; got $unwrappedModel")
        }
        messageEdges.observe(sessionId, parsed.typed)
        val label = activityLabel.labelFor(parsed.typed)
        if (label == null) nearMissLabelQuery(parsed.typed, sessionId)
        return if (label != null) {
            local(label, parsed.typed, sessionId, perf)
        } else {
            build(call, parsed, Arrival(sessionId, inbound), perf)
        }
    }

    /** What the request arrived with, beyond its parsed body: the client's session, and the request
     *  itself when the head's trace will want it (V4-174) — one value, so the build path's
     *  signatures did not grow a parameter each. */
    private data class Arrival(val sessionId: String?, val inbound: ClientInbound?)

    /** V4-174: the client's request for the trace — method, path, headers with every credential-
     *  class value redacted (HeaderRedaction), and the exact body. */
    private fun inbound(call: ApplicationCall, body: String): ClientInbound = ClientInbound(
        method = call.request.httpMethod.value,
        path = call.request.uri,
        headers = HeaderRedaction.redact(call.request.headers.entries().associate { (k, v) -> k to v.joinToString() }),
        body = body,
    )

    /** A reworded activity side query rides upstream as an ordinary turn; the console counts it. */
    private fun nearMissLabelQuery(request: AnthropicRequest, sessionId: String?) {
        if (activityLabel.looksLikeSideQuery(request)) deps.seams.events.labelQueryUpstream(sessionId)
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
        deps.seams.events.activityLabel(sessionId, label)
        return Preparation.Local(label, request.model, sessionId, request.stream)
    }

    private fun build(
        call: ApplicationCall,
        parsed: AnthropicTurnBody,
        arrival: Arrival,
        perf: TurnPerf,
    ): Preparation {
        // One scan of system + last-user text: classification and shadow instrumentation share it.
        val compactProbe = compactClassifier.classifyCompact(parsed.typed)
        deps.stores.shadow.record(parsed.typed, compactProbe)
        perf.mark(PerfKeys.PARSE)
        val fromProvider = providerTurns.build(parsed, compactProbe.compact, arrival.sessionId, perf)
        return providerTurns.endingOnFailure(fromProvider) { handedOn(call, parsed, arrival, perf, fromProvider) }
    }

    private fun handedOn(
        call: ApplicationCall,
        parsed: AnthropicTurnBody,
        arrival: Arrival,
        perf: TurnPerf,
        fromProvider: BuiltTurn,
    ): Preparation {
        val sessionId = arrival.sessionId
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
        val built = if (deps.policy.forwardClientAuth) {
            prepared.copy(extraHeaders = prepared.extraHeaders + clientAuth.forwardedClientHeaders(call))
        } else {
            prepared
        }
        perf.mark(PerfKeys.BUILD)
        // A compaction retry whose bytes match a compaction that outlived its first client is
        // answered from that recording (TurnStreamer records it, LocalResponses replays it).
        val replayed = if (built.meta.compact) compactionReplay(built, parsed.typed.stream) else null
        // V4-165: a replayed turn is never driven, so what its build holds ends here, not at a drive.
        replayed?.let { built.onEnd?.ended() }
        return replayed ?: Preparation.Ready(built, parsed.typed.stream, arrival.inbound)
    }

    /** Stream-only, both halves: the detached drive lives in TurnStreamer.stream() and CollectTurn
     *  has no recording, so a non-stream compaction is served attached, as every turn was before
     *  2026-09-05. Claude Code's auto-compaction streams; the log line is the tell if that changes. */
    private fun compactionReplay(built: BuiltTurn, stream: Boolean): Preparation.Replay? {
        if (stream) return replayFor(built)
        deps.log(
            "[${provider.key}] non-stream compaction (${who(built.meta.sessionId)}served attached: " +
                "no detached drive, no replay)\n",
        )
        return null
    }

    private fun replayFor(built: BuiltTurn): Preparation.Replay? {
        val key = replay.key(built.meta, built.requestBody.toString()) ?: return null
        val recording = replay.lookup(key) ?: return null
        val state = if (recording.isComplete) "finished" else "still running"
        deps.log(
            "[${provider.key}] compaction retry matches a detached compaction ($state, " +
                "${who(built.meta.sessionId)}replaying its answer, no upstream turn)\n",
        )
        return Preparation.Replay(recording, key, built.meta.sessionId, built.meta.originalModel)
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

    // V4-100: SESSION_TAG_CHARS is the ONE session-tag width (declared in TurnDrive.kt, same
    // package). This file's own `TAG_CHARS = 8` was the same number under a second name, so the log
    // line here and the one TurnTelemetry writes could disagree about how much of a session id is
    // enough to identify it — a reader comparing the two would see two different tags for one turn.
    private fun who(sessionId: String?): String = sessionId?.let { "session ${it.take(SESSION_TAG_CHARS)}, " } ?: ""
}

private const val SESSION_HEADER = "x-claude-code-session-id"
