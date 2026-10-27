// Walls for mid-stream re-anchoring (eli design 2026-07-24): the poison tear and committed-tool
// rounds MUST refuse continuation (fall back to the honest error terminal); the happy path builds
// a continuation whose input appends reasoning replay + the partial prose + the resume marker.
// The translator side pins the salvage payload: what a failed round carries, and when it must not.
package splice.dialect.responses.reasoning

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.failure.ClientWire
import splice.core.failure.FailureCause
import splice.core.failure.FailureEvidence
import splice.core.failure.Lift
import splice.core.failure.LoginOwner
import splice.core.failure.Moment
import splice.core.failure.Position
import splice.core.failure.Stall
import splice.core.index.WireBlockIndex
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.parse.AnthropicParse
import splice.core.turn.ErrorType
import splice.core.turn.ReasoningDisplay
import splice.core.turn.RoundText
import splice.core.turn.SharedSummaryParts
import splice.core.turn.TurnOutcome
import splice.core.turn.WatchdogBudget
import splice.dialect.responses.ReasoningCapture
import splice.dialect.responses.ReasoningSettings
import splice.dialect.responses.ResponsesLiteQuirks
import splice.dialect.responses.ResponsesProvider
import splice.dialect.responses.ResponsesQuirks
import splice.dialect.responses.StreamTurnContext
import splice.dialect.responses.SummaryHandling
import splice.dialect.responses.WatchdogCaps
import splice.dialect.responses.stream.ResponsesStreamTranslator
import splice.upstream.ProviderLocations
import splice.upstream.ProviderName
import splice.upstream.ProviderTuning
import splice.upstream.ReanchorRound
import splice.upstream.TurnSignals
import splice.upstream.retry.WatchdogFired
import splice.upstream.sse.WireSink
import kotlin.time.Duration.Companion.seconds

private fun previousBody(): JsonObject = Json.parseToJsonElement(
    """{"model":"gpt-5.6-sol","input":[{"role":"user","content":"hi"}],"store":false,"stream":true}""",
).jsonObject

// The rule reads no cause since failure-handling slice 4: which failures are carried on, and how many times, is
// RetryEngine's answer. The cause rides so the failure is whole, and the translator rows below read it.
private fun failureWith(
    cause: FailureCause = FailureCause.Path.Stalled(Stall.IDLE),
    partial: TurnOutcome.PartialRound? = TurnOutcome.PartialRound(text = RoundText(bodyText = "The fix is to")),
) = TurnOutcome.Failure(
    cause = cause,
    at = Position(Moment.MID_CONTENT, streamBegan = true),
    evidence = FailureEvidence("boom"),
    partial = partial,
)

private val controller = ResponsesReanchorPolicy(
    decodeReasoningEnvelope = { env ->
        Json.parseToJsonElement("""{"type":"reasoning","id":"$env"}""").jsonObject
    },
)

private object ReanchorProbeAuth : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.Bearer("tok", "acct")
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "stub")
}

/** V4-339: a claudex-shaped provider on gpt-5.6-sol, served responses-lite when [lite]. */
private class ReanchorProbe(lite: Boolean, state: java.nio.file.Path? = null) : ResponsesProvider(
    tuning = ProviderTuning(
        name = ProviderName(key = "probe", label = "probe"),
        catalog = ModelCatalog(
            discoveryPrefix = "claude-codex",
            models = listOf(ModelEntry(id = "gpt-5.6-sol", label = "sol", contextWindow = 400_000)),
            defaultContextWindow = 400_000,
        ),
        pinnedModel = "gpt-5.6-sol",
        auth = ReanchorProbeAuth,
        locations = ProviderLocations(baseUrl = "https://chatgpt.com/backend-api/codex", stateDir = state),
        watchdog = WatchdogBudget(5.seconds, 3.seconds, 30.seconds),
    ),
    reasoning = ReasoningSettings(ReasoningDisplay.TEXT, false, null, null),
    quirks = ResponsesQuirks(
        providerTag = "claudex",
        lite = ResponsesLiteQuirks(
            responsesLiteModelRegex = Regex("gpt-5\\.6").takeIf { lite },
        ),
    ),
) {
    override fun extraHeaders(creds: Credentials): Map<String, String> = emptyMap()
}

/** The item [provider]'s re-anchor replays for a turn it built, cut after "The fix is to": the path the
 *  head takes (TurnRoundRun asks the provider for the controller with the turn's meta). */
private fun replayedProse(provider: ResponsesProvider): JsonObject {
    val built = provider.buildTurn(
        AnthropicParse.parseAnthropicBody(
            """{"model":"gpt-5.6-sol","max_tokens":1000,"messages":[{"role":"user","content":"fix the build"}]}""",
        ),
        compact = false,
        sessionId = null,
    )
    val next = checkNotNull(provider.reanchorPolicy(built.meta))
        .continuationForFailure(ReanchorRound(built.requestBody, failureWith(), attempt = 0))
    return checkNotNull(next).getValue("input").jsonArray.map { it.jsonObject }
        .single { (it["content"] as? JsonPrimitive)?.content == "The fix is to" }
}

private data class PhaseRoundTripSample(
    val texts: List<String>,
    val phases: List<String>,
    val streamed: Boolean,
    val text: String,
)

/** Keeps generated input, head lifecycle, and assertions separate without changing the property. */
private class PhaseRoundTripCases(private val state: java.nio.file.Path) {
    private val random = kotlin.random.Random(430)

    suspend fun verify() {
        repeat(16) { case -> verifyCase(case) }
    }

    private suspend fun verifyCase(case: Int) {
        val directory = state.resolve(case.toString())
        var provider = ReanchorProbe(lite = false, state = directory)
        try {
            val sample = capture(provider, case)
            val resend = resend(sample.text, case)
            repeat(2) { restart ->
                if (restart != 0) {
                    provider.onHeadStop()
                    provider = ReanchorProbe(lite = false, state = directory)
                }
                val context = "case=$case streamed=${sample.streamed} restart=$restart"
                verifyResend(provider, sample, resend, context)
            }
        } finally {
            provider.onHeadStop()
        }
    }

    private suspend fun capture(provider: ReanchorProbe, case: Int): PhaseRoundTripSample {
        val opening = """{"model":"gpt-5.6-sol","messages":[{"role":"user","content":"Check this."}]}"""
        val built = provider.buildTurn(AnthropicParse.parseAnthropicBody(opening), false, "session")
        val texts = List(2) { "phase-$case-${random.nextLong().toString(36)}🙂 " }
        val phases = if (case % 3 == 0) {
            listOf("final_answer", "commentary")
        } else {
            listOf("commentary", "final_answer")
        }
        val messages = texts.mapIndexed { index, text ->
            JsonObject(DeliveryFrames.message("msg_$index", text) + ("phase" to JsonPrimitive(phases[index])))
        }
        val native = listOf(DeliveryFrames.reasoning("rs_phase_$case")) + messages
        val streamed = case % 2 == 0
        val frames = if (streamed) {
            native.mapIndexed { index, item -> DeliveryFrames.done(item, index) }
        } else {
            emptyList()
        }
        val sink = DeliverySink()
        val outcome = provider.streamTranslator(
            built.meta,
            TurnSignals(watchdogFired = { null }, clientGone = { false }),
        ).driveTurn((frames + DeliveryFrames.terminal(*native.toTypedArray())).asFlow(), sink)
        val success = checkNotNull(outcome as? TurnOutcome.Success)
        val text = success.text.bodyText
        assertEquals(texts.joinToString(""), text)
        if (streamed) assertEquals(text, sink.text.toString())
        assertEquals(0, sink.opaqueBlocks)
        return PhaseRoundTripSample(texts, phases, streamed, text)
    }

    private fun resend(text: String, case: Int): splice.core.parse.AnthropicTurnBody {
        val clientHistory = clientHistory(text, case)
        return AnthropicParse.parseAnthropicBody(
            """{"model":"gpt-5.6-sol","messages":[{"role":"user","content":"Check this."},
                $clientHistory,{"role":"user","content":"Continue."}]}""",
        )
    }

    private fun clientHistory(text: String, case: Int): String {
        val candidate = random.nextInt(1, text.length)
        val split = if (text[candidate - 1].isHighSurrogate() && text[candidate].isLowSurrogate()) {
            candidate + 1
        } else {
            candidate
        }
        val pieces = listOf(text.take(split), text.drop(split))
        return when (case % 3) {
            0 -> """{"role":"assistant","content":${JsonPrimitive(text)}}"""
            1 -> """{"role":"assistant","content":[${pieces.joinToString(",") {
                """{"type":"text","text":${JsonPrimitive(it)}}"""
            }}]}"""
            else -> pieces.joinToString(",") { """{"role":"assistant","content":${JsonPrimitive(it)}}""" }
        }
    }

    private fun verifyResend(
        provider: ReanchorProbe,
        sample: PhaseRoundTripSample,
        resend: splice.core.parse.AnthropicTurnBody,
        context: String,
    ) {
        val input = input(provider, resend)
        assertEquals(sample.phases.zip(sample.texts), phaseText(input), context)
        assertFalse(input.any { it["type"]?.jsonPrimitive?.content == "reasoning" }, "replay stays off")
        val unrelated = input(provider, resend, "another-session")
        assertEquals(listOf("final_answer" to sample.text), phaseText(unrelated))
        val changed = "!" + sample.text.drop(1)
        val branch = AnthropicParse.parseAnthropicBody(
            """{"model":"gpt-5.6-sol","messages":[{"role":"user","content":"Check this."},
                {"role":"assistant","content":${JsonPrimitive(changed)}},
                {"role":"user","content":"Continue."}]}""",
        )
        val branched = input(provider, branch)
        assertEquals(listOf("final_answer" to changed), phaseText(branched), "changed history is a miss")
    }

    private fun input(
        provider: ReanchorProbe,
        body: splice.core.parse.AnthropicTurnBody,
        session: String = "session",
    ): List<JsonObject> =
        provider.buildTurn(body, false, session).requestBody.getValue("input").jsonArray.map { it.jsonObject }

    private fun phaseText(items: List<JsonObject>): List<Pair<String?, String>> = buildList {
        for (item in items.filter { it["role"]?.jsonPrimitive?.content == "assistant" }) {
            val phase = item["phase"]?.jsonPrimitive?.content
            val text = item.getValue("content").jsonPrimitive.content
            if (isNotEmpty() && last().first == phase) {
                val previous = removeAt(lastIndex)
                add(phase to previous.second + text)
            } else {
                add(phase to text)
            }
        }
    }
}

class ResponsesReanchorPolicyTest {

    // Standard HTTP turns retain model-owned phases although Anthropic history has no phase field.
    @Test
    fun `HTTP phase provenance survives a phase-less client resend and cache restart`(
        @org.junit.jupiter.api.io.TempDir state: java.nio.file.Path,
    ) = runTest {
        PhaseRoundTripCases(state).verify()
    }

    @Test
    fun `native re-anchor retains final_answer announced before an interrupted text delta`() = runTest {
        val provider = ReanchorProbe(lite = false)
        try {
            val built = provider.buildTurn(
                AnthropicParse.parseAnthropicBody(
                    """{"model":"gpt-5.6-sol","messages":[{"role":"user","content":"Continue."}]}""",
                ),
                false,
                "session",
            )
            val outcome = provider.streamTranslator(
                built.meta,
                TurnSignals(watchdogFired = { null }, clientGone = { false }),
            ).driveTurn(
                listOf(
                    ev(
                        """{"type":"response.output_item.added","output_index":0,
                            "item":{"type":"message","id":"msg_final","role":"assistant",
                                "phase":"final_answer","content":[]}}""",
                    ),
                    ev(
                        """{"type":"response.output_text.delta","output_index":0,
                            "item_id":"msg_final","delta":"The fix is to"}""",
                    ),
                    failedEv(),
                ).asFlow(),
                DeliverySink(),
            )
            val next = checkNotNull(provider.reanchorPolicy(built.meta)).continuationForFailure(
                ReanchorRound(built.requestBody, outcome as TurnOutcome.Failure, 0),
            )
            val added = checkNotNull(next).getValue("input").jsonArray
                .drop(built.requestBody.getValue("input").jsonArray.size).map { it.jsonObject }
            assertEquals("The fix is to", added.first().getValue("content").jsonPrimitive.content)
            assertEquals("final_answer", added.first()["phase"]?.jsonPrimitive?.content)
            assertEquals("commentary", added.last()["phase"]?.jsonPrimitive?.content)
        } finally {
            provider.onHeadStop()
        }
    }

    @Test
    fun `a lite recovery posts ordered native reasoning and unfinished prose before its marker`() = runTest {
        val provider = ReanchorProbe(lite = true)
        val built = provider.buildTurn(
            AnthropicParse.parseAnthropicBody(
                """{"model":"gpt-5.6-sol","messages":[{"role":"user","content":"hi"}]}""",
            ),
            compact = false,
            sessionId = "session",
        )
        val frames = listOf(
            ev(
                """{"type":"response.output_item.done","output_index":0,""" +
                    """"item":{"type":"reasoning","id":"rs_first","encrypted_content":"cipher-first"}}""",
            ),
            ev("""{"type":"response.output_text.delta","output_index":1,"delta":"A"}"""),
            ev(
                """{"type":"response.output_item.done","output_index":1,""" +
                    """"item":{"type":"message","id":"msg_a","role":"assistant",""" +
                    """"content":[{"type":"output_text","text":"A"}]}}""",
            ),
            ev(
                """{"type":"response.output_item.done","output_index":2,""" +
                    """"item":{"type":"reasoning","id":"rs_second","encrypted_content":"cipher-second"}}""",
            ),
            ev("""{"type":"response.output_text.delta","output_index":3,"delta":"B"}"""),
            failedEv(),
        )
        val failure = provider.streamTranslator(
            built.meta,
            TurnSignals(watchdogFired = { null }, clientGone = { false }),
        )
            .driveTurn(frames.asFlow(), NullSink()) as TurnOutcome.Failure
        val next = checkNotNull(provider.reanchorPolicy(built.meta))
            .continuationForFailure(ReanchorRound(built.requestBody, failure, 0))
        val added = checkNotNull(next).getValue("input").jsonArray.takeLast(5).map { it.jsonObject }

        assertEquals(
            listOf("rs_first", "A", "rs_second", "B"),
            added.take(4).map { it["id"]?.jsonPrimitive?.content ?: it.getValue("content").jsonPrimitive.content },
        )
        assertTrue(listOf(added[1], added[3], added[4]).all { it["phase"]?.jsonPrimitive?.content == "commentary" })
        assertTrue(added.last().getValue("content").jsonPrimitive.content.contains("interrupted"))
    }

    // V4-339: the partial prose the client already saw was said mid-turn, so by V4-335's rule it is
    // commentary on a lite turn; replayed bare, it read to the model as the turn's final answer.
    @Test
    fun `a lite turn replays the cut turn's partial prose as commentary`() {
        val prose = replayedProse(ReanchorProbe(lite = true))

        assertEquals("commentary", prose["phase"]?.jsonPrimitive?.content, "replayed as $prose")
        assertEquals(listOf("role", "phase", "content"), prose.keys.toList())
    }

    @Test
    fun `a standard Responses turn replays partial prose as commentary without Lite`() {
        val prose = replayedProse(ReanchorProbe(lite = false))

        assertEquals(listOf("role", "phase", "content"), prose.keys.toList(), "$prose")
        assertEquals("assistant", prose.getValue("role").jsonPrimitive.content)
        assertEquals("commentary", prose.getValue("phase").jsonPrimitive.content)
    }

    @Test
    fun `re-anchor preserves an original phase when native message history supplies it`() {
        val provider = ReanchorProbe(lite = false)
        val built = provider.buildTurn(
            AnthropicParse.parseAnthropicBody(
                """{"model":"gpt-5.6-sol","messages":[{"role":"user","content":"hi"}]}""",
            ),
            compact = false,
            sessionId = "session",
        )
        val native = Json.parseToJsonElement(
            """{"type":"message","id":"msg_1","role":"assistant","phase":"final_answer",
                "content":[{"type":"output_text","text":"The fix is to continue"}]}""",
        ).jsonObject
        val partial = TurnOutcome.PartialRound(
            text = RoundText(bodyText = "The fix is to"),
            nativePrefix = listOf(native),
        )
        val next = checkNotNull(provider.reanchorPolicy(built.meta))
            .continuationForFailure(ReanchorRound(built.requestBody, failureWith(partial = partial), 0))
        val prose = checkNotNull(next).getValue("input").jsonArray
            .map { it.jsonObject }
            .single { it["content"]?.jsonPrimitive?.content == "The fix is to" }

        assertEquals("final_answer", prose["phase"]?.jsonPrimitive?.content)
    }

    // codex-rs retries a retryable stream 5 times (DEFAULT_STREAM_MAX_RETRIES) before it switches transport. Since
    // failure-handling slice 4 those five are RetryEngine's MAX_STREAM_RECOVERIES, on the turn's one ledger and shared
    // by every recovery of the turn, so the rule counts nothing: a sixth round is still one it could carry on.
    @Test
    fun `the rule counts no attempts - the five continuations are the engine's`() {
        val empty = failureWith(partial = TurnOutcome.PartialRound())
        assertNotNull(controller.continuationForFailure(ReanchorRound(previousBody(), failureWith(), attempt = 5)))
        assertEquals(previousBody(), controller.continuationForFailure(ReanchorRound(previousBody(), empty, 5)))
    }

    @Test
    fun `overloaded mid-text failure yields replay + partial prose + resume marker`() {
        val partial = TurnOutcome.PartialRound(
            text = RoundText(bodyText = "The fix is to"),
            reasoningEnvelopes = listOf("e1"),
        )
        val next = controller.continuationForFailure(
            ReanchorRound(previousBody(), failureWith(partial = partial), attempt = 0),
        )
        assertNotNull(next)
        val input = next!!["input"]!!.jsonArray
        // user + decoded replay + assistant partial + marker
        assertEquals(4, input.size)
        assertEquals("e1", input[1].jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("The fix is to", input[2].jsonObject["content"]!!.jsonPrimitive.content)
        val marker = input[3].jsonObject
        assertEquals("commentary", marker["phase"]!!.jsonPrimitive.content)
        assertTrue(marker["content"]!!.jsonPrimitive.content.contains("interrupted"))
    }

    @Test
    fun `a poison tool tear refuses continuation`() {
        val partial = TurnOutcome.PartialRound(text = RoundText(bodyText = "x"), hasToolUse = true, toolTearOpen = true)
        assertNull(
            controller.continuationForFailure(
                ReanchorRound(previousBody(), failureWith(partial = partial), 0),
            ),
        )
    }

    @Test
    fun `a committed tool_use refuses continuation - no orphan function_call, no double dispatch`() {
        val partial = TurnOutcome.PartialRound(
            text = RoundText(bodyText = "x"),
            hasToolUse = true,
        )
        assertNull(
            controller.continuationForFailure(
                ReanchorRound(previousBody(), failureWith(partial = partial), 0),
            ),
        )
    }

    @Test
    fun `the rule reads no failure type - a rejection is the engine's to end`() {
        // A rejection, a refused login and a context too long are never carried on: RetryEngine ends them or renews
        // the login before anything is sent (its Rejected, AuthRejected and TooLong rows). The rule says only that the
        // round could be carried on from what arrived, so it answers each of them as it answers a stall.
        val stalled = controller.continuationForFailure(ReanchorRound(previousBody(), failureWith(), 0))
        val causes = listOf(
            FailureCause.Provider.Rejected(staleReasoning = false),
            FailureCause.Provider.AuthRejected(LoginOwner.SPLICE),
            FailureCause.Provider.TooLong(limitTokens = null, whileAnswering = false),
        )
        for (cause in causes) {
            val next = controller.continuationForFailure(ReanchorRound(previousBody(), failureWith(cause = cause), 0))
            assertEquals(stalled, next, "cause $cause")
        }
    }

    @Test
    fun `envelope-only salvage continues - reasoning replay needs no prose`() {
        val partial = TurnOutcome.PartialRound(text = RoundText(bodyText = ""), reasoningEnvelopes = listOf("e1"))
        val next = controller.continuationForFailure(
            ReanchorRound(previousBody(), failureWith(partial = partial), 0),
        )
        assertNotNull(next)
        val input = next!!["input"]!!.jsonArray
        // user + decoded replay + marker; NO assistant-text item for the empty prose
        assertEquals(3, input.size)
    }

    @Test
    fun `an api_error failure is retryable too`() {
        val next = controller.continuationForFailure(
            ReanchorRound(previousBody(), failureWith(cause = FailureCause.Provider.Unrecognized(false)), 0),
        )
        assertNotNull(next)
    }

    @Test
    fun `a mid-stream rate limit continues, because the re-POST is where a real 429 becomes readable`() {
        // V4-58, the Responses twin of V4-57 — premise re-proven on THIS dialect, not ported. A rate
        // limit met after the first frame arrives as an SSE rate_limit_error inside a 200, and the
        // client's recovery trigger is HTTP status 429, so a turn that dead-ends here has no
        // automatic recovery. Since failure-handling slice 4 the wait before the continuation is
        // RetryEngine's burst schedule (2, 4, 8 and 15 s, then the turn ends), taken through the
        // turn's waits; this rule only builds the request.
        val next = controller.continuationForFailure(
            ReanchorRound(previousBody(), failureWith(FailureCause.Provider.Limited(Lift.Unstated, null)), 0),
        )
        assertNotNull(next, "a mid-stream rate limit must earn a continuation on this dialect too")
    }

    @Test
    fun `a thinking-only partial restarts from scratch - nothing replayable, no marker`() {
        val partial = TurnOutcome.PartialRound(
            text = RoundText(thinkingText = "deep partial reasoning already streamed"),
        )
        val next = controller.continuationForFailure(
            ReanchorRound(previousBody(), failureWith(partial = partial), 0),
        )
        // Verbatim whole-request retry (codex-rs responses_retry.rs parity) — NOT a marker
        // continuation: thinking cannot seed a resume, so the round re-runs clean.
        assertEquals(previousBody(), next)
    }

    @Test
    fun `an empty partial restarts from scratch - the whole-request retry`() {
        val next = controller.continuationForFailure(
            ReanchorRound(previousBody(), failureWith(partial = TurnOutcome.PartialRound()), 0),
        )
        assertEquals(previousBody(), next)
    }

    @Test
    fun `clean-slate restart refuses a tool round - double-dispatch risk unchanged`() {
        val partial = TurnOutcome.PartialRound(hasToolUse = true)
        assertNull(
            controller.continuationForFailure(
                ReanchorRound(previousBody(), failureWith(partial = partial), 0),
            ),
        )
    }

    @Test
    fun `a failure without a partial refuses continuation`() {
        assertNull(
            controller.continuationForFailure(
                ReanchorRound(previousBody(), failureWith(partial = null), 0),
            ),
        )
    }

    // 2026-09-02: a compaction's partial is usage-only (ResponsesOutcomePayload keeps the buffered
    // text out of it — nothing reached the client), so the only continuation it can ever take is
    // the verbatim whole-request restart: no marker, no replayed prose. This is the codex-rs
    // RemoteCompactionV2 retry (responses_retry.rs), which until now compaction alone did not get.
    @Test
    fun `a torn compaction restarts verbatim - its usage-only partial has nothing to replay`() {
        val usageOnly = TurnOutcome.PartialRound(usage = splice.core.turn.Usage(inputTokens = 180_000))
        val body = previousBody()
        val next = controller.continuationForFailure(
            ReanchorRound(body, failureWith(partial = usageOnly), attempt = 0),
        )
        assertEquals(body, next, "the whole request again, byte for byte — never a marker continuation")
    }
}

// ── translator salvage payload ──────────────────────────────────────────────────────────────

private class NullSink : WireSink {
    private var next = 0
    override suspend fun openText(): WireBlockIndex = WireBlockIndex(next++)
    override suspend fun openThinking(): WireBlockIndex = WireBlockIndex(next++)
    override suspend fun openTool(id: String, name: String): WireBlockIndex = WireBlockIndex(next++)
    override suspend fun textDelta(index: WireBlockIndex, text: String) = Unit
    override suspend fun thinkingDelta(index: WireBlockIndex, thinking: String) = Unit
    override suspend fun inputJsonDelta(index: WireBlockIndex, partialJson: String) = Unit
    override suspend fun closeBlock(index: WireBlockIndex) = Unit
    override suspend fun closeAll() = Unit
    override suspend fun addTextBlock(text: String) = Unit
    override suspend fun addRedactedThinking(data: String) = Unit
}

private fun reanchorCtx(fired: WatchdogFired? = null, collect: Boolean = true) = StreamTurnContext(
    compact = false,
    emitEncryptedReasoning = EmitEncryptedReasoning(false),
    encodeReasoningEnvelope = { item ->
        item["id"]?.let { "env:$it" }?.takeIf { item["encrypted_content"] != null }
    },
    signals = TurnSignals(
        clientGone = { false },
        watchdogFired = { fired },
    ),
    caps = WatchdogCaps(
        streamIdleMs = 180_000,
        upstreamTimeoutMs = 900_000,
    ),
    summary = SummaryHandling(
        // re-anchor salvage is asserted per round here; a fresh instance is the turn's state
        partsShared = SharedSummaryParts(),
    ),
    reasoningCapture = ReasoningCapture(
        collectEnvelopes = collect,
    ),
)

private fun ev(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

private fun toolAddedEv(): JsonObject = ev(
    """{"type":"response.output_item.added","output_index":0,""" +
        """"item":{"type":"function_call","call_id":"t1","name":"run"}}""",
)

private fun failedEv(): JsonObject = ev(
    """{"type":"response.failed","response":{"error":{"code":"server_error","message":"overloaded"}}}""",
)

private fun reasoningDoneEv(): JsonObject = ev(
    """{"type":"response.output_item.done","output_index":0,""" +
        """"item":{"type":"reasoning","id":"r1","encrypted_content":"blob"}}""",
)

class ResponsesReanchorPartialTest {

    @Test
    fun `a truncated stream after text carries the partial prose`() = runTest {
        val outcome = ResponsesStreamTranslator(reanchorCtx()).driveTurn(
            listOf(
                ev("""{"type":"response.output_text.delta","output_index":0,"delta":"The fix is to"}"""),
            ).asFlow(),
            NullSink(),
        )
        val failure = outcome as TurnOutcome.Failure
        assertEquals(ErrorType.OVERLOADED, ClientWire.ownType(failure.cause))
        val partial = failure.partial
        assertNotNull(partial)
        assertEquals("The fix is to", partial!!.text.bodyText)
        assertTrue(partial.text.emittedText)
        assertFalse(partial.toolTearOpen)
        assertFalse(partial.hasToolUse)
    }

    @Test
    fun `a provider-reported failure event carries the partial too`() = runTest {
        val outcome = ResponsesStreamTranslator(reanchorCtx()).driveTurn(
            listOf(
                ev("""{"type":"response.output_text.delta","output_index":0,"delta":"half an ans"}"""),
                failedEv(),
            ).asFlow(),
            NullSink(),
        )
        val failure = outcome as TurnOutcome.Failure
        assertTrue(failure.cause.providerReported)
        assertEquals("half an ans", failure.partial?.text?.bodyText)
    }

    @Test
    fun `a deterministic upstream rejection is never re-POSTed`() = runTest {
        val outcome = ResponsesStreamTranslator(reanchorCtx()).driveTurn(
            listOf(
                ev(
                    """{"type":"response.failed","response":{"error":{""" +
                        """"code":"invalid_parameter","message":"request rejected by policy"}}}""",
                ),
            ).asFlow(),
            NullSink(),
        )
        val failure = outcome as TurnOutcome.Failure
        // Failure-handling slice 3: the head's reader reads an in-band invalid_parameter as its HTTP twin, a request
        // the provider rejected (OpenAiReplies), where the shared classifier called it an api_error.
        assertEquals(ErrorType.INVALID_REQUEST, ClientWire.ownType(failure.cause))
        assertTrue(failure.cause.providerReported)
        assertNull(failure.partial)
        assertNull(controller.continuationForFailure(ReanchorRound(previousBody(), failure, attempt = 0)))
    }

    // DR-10 redo (codex end-to-end counterexample): the arm above passed even pre-fix because its
    // message carries no transient wording. The SAME deterministic code whose message says
    // "unavailable ... try again" used to flip transient — free text overruled provenance and the
    // whole context re-POSTed. The code rides classify() separately now; wording buys no replay.
    @Test
    fun `a deterministic code with transient-sounding text still never re-POSTs`() = runTest {
        val outcome = ResponsesStreamTranslator(reanchorCtx()).driveTurn(
            listOf(
                ev(
                    """{"type":"response.failed","response":{"error":{"code":"invalid_parameter",""" +
                        """"message":"The selected model is unavailable in your region, try again"}}}""",
                ),
            ).asFlow(),
            NullSink(),
        )
        val failure = outcome as TurnOutcome.Failure
        assertTrue(failure.cause.providerReported)
        assertNull(failure.partial, "a deterministic failure must not carry a re-POSTable partial")
        assertNull(controller.continuationForFailure(ReanchorRound(previousBody(), failure, attempt = 0)))
    }

    // DR-10 sweep follow-up: the FLAT error event has no error envelope — the reducer's payload
    // pick lands on the event itself, whose `type` is the SSE event discriminator ("error"), not
    // vendor provenance. Riding it into classify() as a structured code made every code-less flat
    // error deterministic, withholding the retry its own message wording grants.
    @Test
    fun `a flat error event's discriminator is not vendor provenance`() = runTest {
        val outcome = ResponsesStreamTranslator(reanchorCtx()).driveTurn(
            listOf(
                ev("""{"type":"response.output_text.delta","output_index":0,"delta":"half an ans"}"""),
                ev("""{"type":"error","code":null,"message":"The server had an error, please try again"}"""),
            ).asFlow(),
            NullSink(),
        )
        val failure = outcome as TurnOutcome.Failure
        assertTrue(failure.cause.providerReported)
        assertEquals("half an ans", failure.partial?.text?.bodyText, "transient flat error must keep its salvage")
        val continuation = controller.continuationForFailure(ReanchorRound(previousBody(), failure, attempt = 0))
        assertNotNull(continuation, "a transient flat error must earn a re-POST")
        assertTrue(continuation.toString().contains("half an ans"), "the marker continuation carries the salvage")
    }

    @Test
    fun `a transient server failure remains eligible for a clean restart`() = runTest {
        val outcome = ResponsesStreamTranslator(reanchorCtx()).driveTurn(
            listOf(
                ev(
                    """{"type":"response.failed","response":{"error":{""" +
                        """"code":"server_error","message":"temporarily unavailable"}}}""",
                ),
            ).asFlow(),
            NullSink(),
        )
        val failure = outcome as TurnOutcome.Failure
        assertNotNull(failure.partial)
        assertEquals(
            previousBody(),
            controller.continuationForFailure(ReanchorRound(previousBody(), failure, attempt = 0)),
        )
    }

    @Test
    fun `a content-filtered turn is deterministic and is never re-POSTed`() = runTest {
        val outcome = ResponsesStreamTranslator(reanchorCtx()).driveTurn(
            listOf(
                ev("""{"type":"response.output_text.delta","output_index":0,"delta":"blocked"}"""),
                ev(
                    """{"type":"response.incomplete","response":{"status":"incomplete",""" +
                        """"incomplete_details":{"reason":"content_filter"}}}""",
                ),
            ).asFlow(),
            NullSink(),
        )
        val failure = outcome as TurnOutcome.Failure
        assertEquals(ErrorType.INVALID_REQUEST, ClientWire.ownType(failure.cause))
        assertNull(failure.partial)
        assertNull(controller.continuationForFailure(ReanchorRound(previousBody(), failure, attempt = 0)))
    }

    @Test
    fun `a tool block torn mid-args marks the poison tear`() = runTest {
        val outcome = ResponsesStreamTranslator(reanchorCtx()).driveTurn(
            listOf(
                toolAddedEv(),
                ev("""{"type":"response.function_call_arguments.delta","output_index":0,"delta":"{\"x\":"}"""),
            ).asFlow(),
            NullSink(),
        )
        val partial = (outcome as TurnOutcome.Failure).partial
        assertNotNull(partial)
        assertTrue(partial!!.toolTearOpen)
    }

    @Test
    fun `a cleanly-closed tool is committed, not a tear`() = runTest {
        val outcome = ResponsesStreamTranslator(reanchorCtx()).driveTurn(
            listOf(
                toolAddedEv(),
                ev("""{"type":"response.function_call_arguments.done","output_index":0,"arguments":"{\"x\":1}"}"""),
            ).asFlow(),
            NullSink(),
        )
        val partial = (outcome as TurnOutcome.Failure).partial
        assertNotNull(partial)
        assertFalse(partial!!.toolTearOpen)
        assertTrue(partial.hasToolUse)
    }

    @Test
    fun `a failed event's usage block is harvested into the partial`() = runTest {
        val outcome = ResponsesStreamTranslator(reanchorCtx()).driveTurn(
            listOf(
                ev("""{"type":"response.output_text.delta","output_index":0,"delta":"some text"}"""),
                ev(
                    """{"type":"response.failed","response":{""" +
                        """"error":{"code":"server_error","message":"overloaded"},""" +
                        """"usage":{"input_tokens":100,"output_tokens":42,""" +
                        """"output_tokens_details":{"reasoning_tokens":7}}}}""",
                ),
            ).asFlow(),
            NullSink(),
        )
        val partial = (outcome as TurnOutcome.Failure).partial
        assertEquals(42, partial?.usage?.outputTokens)
    }

    // DR-7 REVERSES the arm that stood here ("a watchdog fire carries no partial - its cancellation
    // owns the turn"). That sentence described a watchdog that cancelled the whole TURN; it now
    // reaps a single ROUND, so an idle fire is a stall report about a round that still has real
    // salvage — and withholding it threw away text the client had already been shown.
    @Test
    fun `an idle watchdog fire carries its partial - the round is reaped, not the turn`() = runTest {
        val outcome = ResponsesStreamTranslator(
            reanchorCtx(fired = WatchdogFired.Idle(idleMs = 1, sawClientFrame = true, limitMs = 1)),
        ).driveTurn(
            listOf(
                ev("""{"type":"response.output_text.delta","output_index":0,"delta":"text"}"""),
            ).asFlow(),
            NullSink(),
        )
        val failure = outcome as TurnOutcome.Failure
        assertEquals("text", failure.partial?.text?.bodyText, "an idle stall must keep what the round produced")
        assertNotNull(
            controller.continuationForFailure(ReanchorRound(previousBody(), failure, attempt = 0)),
            "and that salvage must be enough to earn the continuation",
        )
    }

    // The half that did NOT reverse, and the reason the split is not cosmetic: totalCap is the
    // whole-turn wall. Handing it a partial would let the turn continue past the one budget that
    // exists to stop it, which is the opposite of what its own name says.
    @Test
    fun `a totalCap watchdog fire carries no partial - the whole-turn wall is not continuable`() = runTest {
        val outcome = ResponsesStreamTranslator(
            reanchorCtx(fired = WatchdogFired.TotalCap(elapsedMs = 1)),
        ).driveTurn(
            listOf(
                ev("""{"type":"response.output_text.delta","output_index":0,"delta":"text"}"""),
            ).asFlow(),
            NullSink(),
        )
        val failure = outcome as TurnOutcome.Failure
        assertNull(failure.partial, "the whole-turn cap must not hand out salvage")
        assertNull(
            controller.continuationForFailure(ReanchorRound(previousBody(), failure, attempt = 0)),
            "and with no salvage there is nothing to continue from",
        )
    }

    @Test
    fun `reasoning envelopes ride the failed round for replay`() = runTest {
        val outcome = ResponsesStreamTranslator(reanchorCtx()).driveTurn(
            listOf(
                ev("""{"type":"response.output_item.added","output_index":0,"item":{"type":"reasoning"}}"""),
                reasoningDoneEv(),
            ).asFlow(),
            NullSink(),
        )
        val partial = (outcome as TurnOutcome.Failure).partial
        assertEquals(listOf("env:\"r1\""), partial?.reasoningEnvelopes)
    }
}
