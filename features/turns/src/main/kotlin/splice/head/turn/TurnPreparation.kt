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
//
// V4-319: the re-send Claude Code makes of a stream the operator stopped is refused here, before it is
// built, so the stopped work never runs again upstream (LiveTurns' header has the probe behind it).
package splice.head.turn

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.uri
import kotlinx.coroutines.CompletableDeferred
import splice.core.client.AGENT_HEADER
import splice.core.client.SESSION_HEADER
import splice.core.failure.Decline
import splice.core.failure.FailureCause
import splice.core.failure.StoppedBy
import splice.core.parse.AnthropicTurnBody
import splice.core.perf.PerfKeys
import splice.core.perf.TurnPerf
import splice.core.session.ActivityAction
import splice.core.util.Cancellables
import splice.core.wire.AnthropicRequest
import splice.head.ActivityLabel
import splice.head.ActivitySamples
import splice.head.AnthropicBodyParse
import splice.head.BodyRead
import splice.head.ClientAuth
import splice.head.HeadDeps
import splice.head.MessageEdges
import splice.head.ReceivedBody
import splice.head.RefusedRequest
import splice.head.RequestBodyReader
import splice.head.compact.CompactClassifier
import splice.head.compaction.CompactionReplay
import splice.head.compaction.HeldReply
import splice.head.turn.fallback.HeldModelFallback
import splice.head.wire.ClientInbound
import splice.head.wire.FrameRecording
import splice.upstream.BuiltTurn
import splice.upstream.Provider
import splice.upstream.transport.HeaderRedaction

internal sealed class Preparation {
    /** [inbound] is the request as it arrived, kept ONLY for a head whose trace is on (V4-174):
     *  null on every other head, so the body is not held twice for a turn nothing will read.
     *  [messagesHash] is what a stop of this turn marks (V4-319, [MessagesHash]): a streaming turn's
     *  with a session, null otherwise, since only that turn's re-send can be told apart. */
    data class Ready(
        val built: BuiltTurn,
        val stream: Boolean,
        private var inbound: ClientInbound?,
        val messagesHash: String?,
        /** The installed client can only compact after at least one assistant exchange exists. */
        val hasPriorExchange: Boolean,
        /** Whether a session sent this turn itself. A turn Claude Code sends for a subagent is served like any other
         *  and has no say in the model the command's logins are named for (AskedModels, Nov 12, 2026). */
        val own: Boolean = true,
    ) : Preparation() {
        /** Transfers the raw request to its trace without retaining a second post-handoff owner. */
        fun takeInbound(): ClientInbound? = inbound.also { inbound = null }
    }

    /** Refused while it was prepared: [cause] decides the answer (ClientWire at admission) and the line the client
     *  reads (the head's voice, failure-handling slice 2); [request] is what the refusal's log line, perf row and
     *  trace record of it (PreDriveRefusals.refusePrepared). Recorded since 2026-11-09, when a dialect refused every
     *  claudex turn for 67 minutes and the log, the perf file and the trace held nothing of it. */
    data class Rejected(val cause: FailureCause.Splice, val request: RefusedRequest = RefusedRequest()) : Preparation()

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

    /** The non-stream sibling of [Replay]: a collect retry follows the held reply of the non-stream turn (a
     *  compaction or not) that outlived its first client (HeldCollectAnswers) instead of starting it again —
     *  finished holds complete at once, still-driving ones settle when their detached drive ends. [compaction]: what
     *  the held turn was, which the line of a re-send whose turn never finished names. */
    data class Collected(
        val hold: CompletableDeferred<HeldReply?>,
        val key: String,
        val sessionId: String?,
        val model: String,
        val compaction: Boolean,
    ) : Preparation()

    /** Claude Code's stream:false fallback for a STREAMED compaction that lost its client: the same upstream
     *  bytes, so the same compaction. It follows that drive's [recording] and answers it collected into one
     *  body (RecordedAnswer) instead of starting a second read beside it (rig's finding, 2026-10-25). */
    data class CollectedRecording(
        val recording: FrameRecording,
        val key: String,
        val sessionId: String?,
        val model: String,
    ) : Preparation()
}

internal class TurnPreparation(
    /** The same dialect owns preparation and credential admission. */
    internal val provider: Provider,
    private val deps: HeadDeps,
    private val bodyReader: RequestBodyReader,
    private val bodyParse: AnthropicBodyParse,
    private val clientAuth: ClientAuth,
    private val replay: CompactionReplay = CompactionReplay(),
) {
    private val compactClassifier = CompactClassifier()
    private val activityLabel = ActivityLabel()
    private val activitySamples = ActivitySamples(deps.seams.clock)
    private val messageEdges = MessageEdges(deps.seams.events)
    private val providerTurns = ProviderTurnBuild(provider, deps, replay)

    /** A session's own fallback model while the one it asks for is held on every login (Nov 13, 2026). */
    private val heldFallback = HeldModelFallback(deps.quotaBundle, provider.catalog, deps.log)

    /** Fix 4 of the Oct 27 Responses fixes: which earlier request of the same Claude Code turn each request
     *  continues, so the turn state its backend returned rides every request of that turn (ToolLoopTurns). */
    private val toolLoop = ToolLoopTurns()

    /** [Materialized.TooLarge] rather than a [Preparation] case: the 413 belongs to the admission
     *  gate, which writes it while the lease is still held, exactly where the thrown refusal used
     *  to unwind to. A Preparation case would render it after the lease returned, which is a
     *  different order on the wire. */
    suspend fun prepareTurn(call: ApplicationCall, perf: TurnPerf): Materialized<Preparation> {
        val headerSession = call.request.headers[SESSION_HEADER]?.takeIf(String::isNotBlank)
        deps.seams.clientVersions.observe(headerSession, call.request.headers[HttpHeaders.UserAgent])
        val body = when (val read = bodyReader.receiveBodyBounded(call, deps.policy.maxRequestBytes())) {
            is BodyRead.TooLarge -> return Materialized.TooLarge(read.limit)
            is BodyRead.Received -> read.body
        }
        return Materialized.Done(preparedFrom(call, perf, headerSession, body))
    }

    private suspend fun preparedFrom(
        call: ApplicationCall,
        perf: TurnPerf,
        headerSession: String?,
        body: ReceivedBody,
    ): Preparation {
        perf.mark(PerfKeys.RECV)
        perf.setCount(PerfKeys.REQ_BYTES, body.bytes.toLong())
        // Read once as the request arrives: a switch flipped now applies to the next request, never to this one. Taken
        // before the parse, so a body refused as unparseable is in the trace that records its refusal.
        val inbound = deps.stores.captures.trace?.takeIf { it.recording.on }?.let { inbound(call, body.text) }
        val parsing = bodyParse.parse(body.text)
        val parsed = parsing.getOrNull() ?: return RefusedRequest(sessionId = headerSession, inbound = inbound).badBody(
            body.bytes,
            call.request.headers[HttpHeaders.ContentLength] ?: "no content-length",
            parsing.exceptionOrNull(),
        )
        // The request names its session in metadata.user_id; the header carries the same id and stands in when the body
        // does not (verified on Claude Code 2.1.257, spec 7 identification).
        val asked = RefusedRequest(parsed.typed.model, parsed.sessionId ?: headerSession, parsed.typed.stream, inbound)
        // The pre-drive boundary (2026-11-09 audit): everything from here to a Ready turn runs provider and prompt code
        // (refusal tables, buildTurn, the prompt layers, the tool loop), and a defect thrown there used to answer an
        // empty 500 with no log line, no perf row and no trace. It is now a refusal like any other, recorded as
        // unexpected. Total, like the turn's own boundary (TurnFailures) and the control plane's (ControlGuard):
        // cancellation and an Error still propagate.
        return Cancellables.runCatchingBestEffort { preparedParsed(call, perf, parsed, asked) }
            .getOrElse(asked::unprepared)
    }

    private fun preparedParsed(
        call: ApplicationCall,
        perf: TurnPerf,
        received: AnthropicTurnBody,
        named: RefusedRequest,
    ): Preparation {
        // Before anything reads the model: a refusal past here names the model the turn would have gone upstream as.
        val parsed = heldFallback.applied(received, named.sessionId)
        val asked = named.copy(model = parsed.typed.model)
        val sessionId = asked.sessionId
        val inbound = asked.inbound
        refusalOf(parsed, asked)?.let { return it }
        messageEdges.observe(sessionId, parsed.typed)
        messageEdges.answered(sessionId, parsed.typed)
        val action = activityLabel.labelFor(parsed.typed)
        if (action == null) nearMissLabelQuery(parsed.typed, sessionId)
        return if (action != null) {
            local(action, parsed.typed, sessionId, perf)
        } else {
            sampleActivity(parsed.typed, sessionId)
            build(call, parsed, Arrival(sessionId, inbound), perf)
        }
    }

    /** The requests refused before they are built: a model this head does not proxy, (V4-319) the one stream=false
     *  re-send of a turn the operator stopped, which LiveTurns tells by its session and messages, and whatever the
     *  provider's dialect cannot carry. The re-send is answered here and never built or sent upstream; the 400
     *  ends the client's turn with no further request (the probe on Claude Code 2.1.283). Each carries [asked], so
     *  its refusal is recorded (PreDriveRefusals.refusePrepared). */
    private fun refusalOf(parsed: AnthropicTurnBody, asked: RefusedRequest): Preparation.Rejected? = when {
        !provider.catalog.contains(parsed.typed.model) -> {
            // The line names the model the head's roster lacks: an unmapped tier's own model, not the tier's alias.
            val unserved = provider.catalog.refusals.unserved(parsed.typed.model)
            Preparation.Rejected(FailureCause.Splice.Declined(Decline.UnknownModel(unserved)), asked)
        }
        !parsed.typed.stream && deps.traffic.liveTurns.refusesResend(asked.sessionId, parsed.raw) ->
            Preparation.Rejected(
                FailureCause.Splice.Stopped(StoppedBy.CONSOLE),
                asked.copy(detail = "re-send of a stopped turn refused (no upstream turn)"),
            )
        else -> provider.requestRefusal(parsed)?.let { refused ->
            Preparation.Rejected(refused.cause, refused.detail?.let { asked.copy(detail = it) } ?: asked)
        }
    }

    /** V4-265: a working session's own turn is its activity sample, at the side query's pace; see
     *  ActivityLabel for why the client's query alone left a team's Activity empty. */
    private fun sampleActivity(request: AnthropicRequest, sessionId: String?) {
        if (sessionId == null) return
        val action = activityLabel.sampleOf(request) ?: return
        if (activitySamples.due(sessionId)) deps.seams.events.activityLabel(sessionId, action)
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
        action: ActivityAction,
        request: AnthropicRequest,
        sessionId: String?,
        perf: TurnPerf,
    ): Preparation.Local {
        perf.mark(PerfKeys.PARSE)
        val said = "activity label answered locally: \"${action.label}\" (${who(sessionId)}no upstream turn)"
        deps.log("[${provider.key}] $said\n")
        sessionId?.let(activitySamples::sampled)
        deps.seams.events.activityLabel(sessionId, action)
        return Preparation.Local(action.label, request.model, sessionId, request.stream)
    }

    private fun build(
        call: ApplicationCall,
        received: AnthropicTurnBody,
        arrival: Arrival,
        perf: TurnPerf,
    ): Preparation {
        // The subagent travels with the body, so a provider that keys a conversation can tell two subagents of one
        // session apart even when they open with identical words (code mode's turn key, Oct 27).
        val parsed = received.copy(agentId = call.request.headers[AGENT_HEADER]?.takeIf(String::isNotBlank))
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
        // session. Stamped here, once, when the provider left it null, with the messages' hash a
        // session's row carries (V4-319's MessagesHash: without a session no re-send can be told apart).
        val messagesHash = sessionId?.let { MessagesHash.of(parsed.raw) }
        val scope = fromProvider.meta.scope.let {
            it.copy(sessionId = it.sessionId ?: sessionId, messagesHash = messagesHash)
        }
        val prepared = fromProvider.copy(meta = fromProvider.meta.copy(scope = scope))
        toolLoop.join(prepared.meta, parsed, parsed.agentId)
        // Per-turn headers already outrank the provider's own in TurnDriver's merge, so a forwarded
        // value REPLACES a configured default (e.g. the caller's anthropic-version wins over the
        // provider's), and UpstreamClient folds the casing.
        val built = if (deps.policy.forwardClientAuth) {
            prepared.copy(extraHeaders = prepared.extraHeaders + clientAuth.forwardedClientHeaders(call))
        } else {
            prepared
        }
        perf.mark(PerfKeys.BUILD)
        // A compaction retry whose bytes match a compaction that outlived its first client is answered from
        // what that drive left (TurnStreamer records it, LocalResponses serves it), on both transports: a
        // streaming retry replays the recording's frames; a collect retry follows a collect compaction's held
        // answer, or else collects a streamed one's recording, which is Claude Code's stream:false fallback
        // (rig's findings of 2026-10-24 and 2026-10-25: hack-program cold-prefilled on every interruption).
        // A non-stream turn of any kind is held the same way since 2026-11-09 (CollectTurn): a Claude Code subagent
        // re-sent one stream:false request 228 times in 25 hours, each re-send restarting its five minutes from zero.
        val replayed = heldFor(built, parsed.typed.stream)
        // V4-165: a replayed turn is never driven, so what its build holds ends here, not at a drive.
        replayed?.let { built.onEnd?.ended() }
        return replayed ?: Preparation.Ready(
            built,
            parsed.typed.stream,
            arrival.inbound,
            // V4-319: what a stop of this turn marks. A collect is never listed, so only a stream carries it there.
            messagesHash.takeIf { parsed.typed.stream },
            hasPriorExchange = parsed.typed.messages.any { it.role == "assistant" },
            own = parsed.agentId == null,
        )
    }

    /** A streamed compaction's recording, or a non-stream turn's held reply (a compaction's or not). */
    private fun heldFor(built: BuiltTurn, stream: Boolean): Preparation? = when {
        !stream -> collectedFor(built)
        built.meta.compact -> replayFor(built)
        else -> null
    }

    private fun replayFor(built: BuiltTurn): Preparation.Replay? {
        val key = replay.key(built.meta, built.requestBody) ?: return null
        val recording = replay.lookup(key) ?: return null
        val state = if (recording.isComplete) "finished" else "still running"
        deps.log(
            "[${provider.key}] compaction retry matches a detached compaction ($state, " +
                "${who(built.meta.scope.sessionId)}replaying its answer, no upstream turn)\n",
        )
        return Preparation.Replay(recording, key, built.meta.scope.sessionId, built.meta.route.originalModel)
    }

    /** The collect sibling of [replayFor]: what the answer ledger holds under the same retry key, and else
     *  the recording of a streamed compaction the same key names, which is what Claude Code's fallback retries. */
    private fun collectedFor(built: BuiltTurn): Preparation? {
        val key = replay.key(built.meta, built.requestBody) ?: return null
        val session = built.meta.scope.sessionId
        val model = built.meta.route.originalModel
        val hold = replay.answers.find(key)
        val recording = if (hold == null) replay.lookup(key) else null
        return when {
            hold != null -> {
                val state = if (hold.isCompleted) "held" else "still driving"
                val what = if (built.meta.compact) "compaction" else "non-stream"
                deps.log(
                    "[${provider.key}] collect $what retry matches a detached one ($state, " +
                        "${who(session)}following its held answer, no upstream turn)\n",
                )
                Preparation.Collected(hold, key, session, model, built.meta.compact)
            }
            recording != null -> {
                val state = if (recording.isComplete) "finished" else "still running"
                deps.log(
                    "[${provider.key}] collect compaction retry matches a detached streaming compaction ($state, " +
                        "${who(session)}collecting its recording into one body, no upstream turn)\n",
                )
                Preparation.CollectedRecording(recording, key, session, model)
            }
            else -> null
        }
    }

    // V4-100: SESSION_TAG_CHARS is the ONE session-tag width (declared in TurnDrive.kt, same
    // package). This file's own `TAG_CHARS = 8` was the same number under a second name, so the log
    // line here and the one TurnTelemetry writes could disagree about how much of a session id is
    // enough to identify it — a reader comparing the two would see two different tags for one turn.
    private fun who(sessionId: String?): String = sessionId?.let { "session ${it.take(SESSION_TAG_CHARS)}, " } ?: ""
}
