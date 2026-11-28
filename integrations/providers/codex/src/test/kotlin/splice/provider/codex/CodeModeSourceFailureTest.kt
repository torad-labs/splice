package splice.provider.codex

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import splice.core.failure.ClientWire
import splice.core.failure.FailureCause
import splice.core.failure.FailureEvidence
import splice.core.failure.HeadVoice
import splice.core.failure.Lift
import splice.core.failure.Moment
import splice.core.failure.PersistedCause
import splice.core.failure.Position
import splice.core.failure.ProviderWordsFilter
import splice.core.failure.RefusedBy
import splice.core.failure.ServerFault
import splice.core.failure.VoiceFacts
import splice.core.turn.ErrorType
import splice.core.turn.RoundText
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.core.util.LogSink
import splice.core.wire.HttpStatus
import splice.provider.codex.catalog.CodeModePresentation
import splice.upstream.RedirectableRoundPost
import splice.upstream.RoundResult
import splice.upstream.codemode.CodeModeCell
import splice.upstream.codemode.CodeModeRuntime
import splice.upstream.codemode.CodeModeSource
import splice.upstream.codemode.NativeCodeModeCell
import splice.upstream.codemode.NativeCodeModeLifecycle
import splice.upstream.codemode.NativeCodeModeOptions
import splice.upstream.codemode.availability.CodeModeAvailability
import splice.upstream.codemode.availability.CodeModeToolMode
import splice.upstream.sse.CustomToolSource
import splice.upstream.sse.WireSink
import java.io.IOException
import java.time.ZoneId

private const val TEST_MODEL = "test-model"
private const val POLICY_REASON = "The requested script violates usage policy."
private const val MODEL_REASON = "I cannot help with that request."
private const val FORBIDDEN_REASON = "This model action is not permitted."
private const val SERVER_REASON = "Temporary backend failure."
private const val CONTEXT_LIMIT = 200_000L
private const val REPORTED_INPUT = 120L
private const val REPORTED_OUTPUT = 8L

class CodeModeSourceFailureTest : CodeModeBridgeTestSupport() {
    @Test
    fun `a native code-mode exec source refused by content filter preserves the refusal and offers no retry`() =
        runTest {
            val manager = nativeBridge()
            val cause = FailureCause.Provider.Refused(RefusedBy.POLICY, ProviderWordsFilter.of(POLICY_REASON))
            val refusal = TurnOutcome.Failure(
                cause = cause,
                at = Position(Moment.MID_CONTENT, streamBegan = true),
                evidence = FailureEvidence("upstream: content_filter $POLICY_REASON"),
                partial = TurnOutcome.PartialRound(
                    text = RoundText(bodyText = "partial", emittedText = true),
                    usage = Usage(inputTokens = REPORTED_INPUT, outputTokens = REPORTED_OUTPUT),
                ),
            )
            try {
                val outcome = manager.interceptor(fixtureTurn(), disableParallel = false)
                    .intercept(BASE_REQUEST, RecordingSink(), unfinishedPost(refusal))
                    .turn() as TurnOutcome.Failure
                assertEquals(cause, outcome.cause)
                assertEquals(PersistedCause.CONTENT_FILTERED, outcome.cause.persisted)
                assertTrue(outcome.cause.permanent)
                assertTrue(outcome.cause.providerReported)
                assertEquals(POLICY_REASON, (outcome.cause as FailureCause.Provider.Refused).said?.text)
                assertNull(outcome.partial)
                assertEquals(REPORTED_INPUT, outcome.salvagedUsage.inputTokens)
                assertEquals(REPORTED_OUTPUT, outcome.salvagedUsage.outputTokens)
                assertEquals(0L, outcome.salvagedUsage.absorbed.rounds)

                val beforeCommit = ClientWire.answer(outcome.cause, Position(Moment.BEFORE_COMMIT, streamBegan = true))
                assertEquals(HttpStatus.BAD_REQUEST, beforeCommit.status)
                assertEquals(ErrorType.INVALID_REQUEST, beforeCommit.type)
                assertEquals(false, beforeCommit.shouldRetry)
                assertFalse(beforeCommit.clientResends)

                val afterContent = ClientWire.answer(outcome.cause, Position(Moment.MID_CONTENT, streamBegan = true))
                assertEquals(ErrorType.INVALID_REQUEST, afterContent.type)
                assertFalse(afterContent.clientResends)

                val voice = HeadVoice("test-head", null, VoiceFacts(zone = ZoneId.of("UTC"))).anyTurn
                val line = voice.ending(outcome.cause, Position(Moment.BEFORE_COMMIT, streamBegan = true), beforeCommit)
                assertTrue(line.text.contains(POLICY_REASON), line.text)
                assertFalse(line.text.contains("runtime start was interrupted"), line.text)
                assertTrue(logLines.none { it.contains("runtime failed to start") }, logLines.toString())
            } finally {
                manager.onHeadStop()
            }
        }

    @ParameterizedTest
    @ValueSource(strings = ["filter", "model-refused", "too-long", "forbidden", "server-error", "overloaded"])
    fun `provider refusals and upstream errors on an unfinished native exec source keep their cause`(kind: String) =
        runTest {
            val manager = nativeBridge()
            val cause = causeFor(kind)
            val failure = TurnOutcome.Failure(
                cause = cause,
                at = Position(Moment.MID_CONTENT, streamBegan = true),
                evidence = FailureEvidence("upstream: $kind"),
            )
            try {
                val outcome = manager.interceptor(fixtureTurn(), disableParallel = false)
                    .intercept(BASE_REQUEST, RecordingSink(), unfinishedPost(failure))
                    .turn() as TurnOutcome.Failure
                assertEquals(cause, outcome.cause)
                assertEquals(cause.persisted, outcome.cause.persisted)
                assertEquals(cause.permanent, outcome.cause.permanent)
                assertEquals(cause.providerReported, outcome.cause.providerReported)
                val wire = ClientWire.answer(outcome.cause, Position(Moment.BEFORE_COMMIT, streamBegan = true))
                assertEquals(!cause.permanent, wire.clientResends)
            } finally {
                manager.onHeadStop()
            }
        }

    @Test
    fun `non-native streaming startup preserves a provider content refusal`() = runTest {
        val manager = bridge(WaitingStreamingRuntime())
        val cause = FailureCause.Provider.Refused(RefusedBy.POLICY, ProviderWordsFilter.of(POLICY_REASON))
        val refusal = TurnOutcome.Failure(
            cause = cause,
            at = Position(Moment.MID_CONTENT, streamBegan = true),
            evidence = FailureEvidence("upstream: content_filter $POLICY_REASON"),
        )
        try {
            val outcome = manager.interceptor(fixtureTurn(), disableParallel = false)
                .intercept(BASE_REQUEST, RecordingSink(), unfinishedPost(refusal))
                .turn() as TurnOutcome.Failure
            assertEquals(cause, outcome.cause)
            assertEquals(PersistedCause.CONTENT_FILTERED, outcome.cause.persisted)
            assertTrue(outcome.cause.permanent)
            assertTrue(outcome.cause.providerReported)
            val wire = ClientWire.answer(outcome.cause, Position(Moment.BEFORE_COMMIT, streamBegan = true))
            assertEquals(HttpStatus.BAD_REQUEST, wire.status)
            assertEquals(ErrorType.INVALID_REQUEST, wire.type)
            assertEquals(false, wire.shouldRetry)
        } finally {
            manager.onHeadStop()
        }
    }

    @Test
    fun `unavailable code-mode host still preserves a provider content refusal on an unfinished exec source`() =
        runTest {
            val manager = CodexCodeModeBridge(
                CodeModeBridgeConfig(
                    object : CodeModeRuntimes {
                        override fun availability(): CodeModeAvailability =
                            CodeModeAvailability.Unavailable("code-mode host is disabled")

                        override fun open(): CodeModeRuntime = throw IOException("host disabled")
                    },
                    stateLocation(),
                    log = LogSink { logLines += it },
                ),
            )
            val cause = FailureCause.Provider.Refused(RefusedBy.FILTER)
            val refusal = TurnOutcome.Failure(
                cause = cause,
                at = Position(Moment.MID_CONTENT, streamBegan = true),
                evidence = FailureEvidence("upstream: generation stopped by content filter"),
            )
            val owner = fixtureTurn().copy(
                catalog = fixtureTurn().catalog.copy(
                    policy = CodeModeToolPolicy(
                        presentation = CodeModePresentation(toolMode = CodeModeToolMode.CODE_MODE_ONLY),
                    ),
                ),
            )
            try {
                val outcome = manager.interceptor(owner, disableParallel = false)
                    .intercept(BASE_REQUEST, RecordingSink(), unfinishedPost(refusal))
                    .turn() as TurnOutcome.Failure
                assertEquals(cause, outcome.cause)
                assertEquals(PersistedCause.CONTENT_FILTERED, outcome.cause.persisted)
                assertTrue(outcome.cause.permanent)
                assertFalse(
                    ClientWire.answer(outcome.cause, Position(Moment.BEFORE_COMMIT, streamBegan = true)).clientResends,
                )
            } finally {
                manager.onProviderStop()
            }
        }

    @Test
    fun `retryable upstream failure on an unfinished native exec source preserves partial for reanchor`() = runTest {
        val manager = nativeBridge()
        val cause = FailureCause.Provider.ServerError(ServerFault.INTERNAL, ProviderWordsFilter.of(SERVER_REASON))
        val partial = TurnOutcome.PartialRound(
            text = RoundText(bodyText = "Working on it.", emittedText = true),
            usage = Usage(inputTokens = REPORTED_INPUT, outputTokens = REPORTED_OUTPUT),
        )
        val failure = TurnOutcome.Failure(
            cause = cause,
            at = Position(Moment.MID_CONTENT, streamBegan = true),
            evidence = FailureEvidence("upstream: server_error $SERVER_REASON"),
            partial = partial,
        )
        try {
            val outcome = manager.interceptor(fixtureTurn(), disableParallel = false)
                .intercept(BASE_REQUEST, RecordingSink(), unfinishedPost(failure))
                .turn() as TurnOutcome.Failure
            assertEquals(cause, outcome.cause)
            assertEquals(partial, outcome.partial)
            assertEquals(REPORTED_INPUT, outcome.salvagedUsage.inputTokens)
            assertEquals(REPORTED_OUTPUT, outcome.salvagedUsage.outputTokens)
            assertEquals(0L, outcome.salvagedUsage.absorbed.rounds)
        } finally {
            manager.onHeadStop()
        }
    }

    @Test
    fun `an uncertified incomplete success before exec completion stays an internal failure`() = runTest {
        val manager = nativeBridge()
        val incomplete = TurnOutcome.Success(
            hasToolUse = false,
            incomplete = true,
            usage = Usage(inputTokens = REPORTED_INPUT, outputTokens = REPORTED_OUTPUT),
        )
        try {
            val outcome = manager.interceptor(fixtureTurn(), disableParallel = false)
                .intercept(BASE_REQUEST, RecordingSink(), unfinishedPost(incomplete))
                .turn() as TurnOutcome.Failure
            assertEquals(FailureCause.Splice.Internal(), outcome.cause)
            assertFalse(outcome.cause.permanent)
            assertFalse(outcome.cause.providerReported)
        } finally {
            manager.onHeadStop()
        }
    }

    private fun fixtureTurn(): CodexCodeModeBridge.Turn = turn().copy(model = TEST_MODEL)

    private fun nativeBridge(): CodexCodeModeBridge = bridge(UnusedNativeRuntime())

    private fun unfinishedPost(terminal: TurnOutcome): RedirectableRoundPost = object : RedirectableRoundPost {
        override suspend fun invoke(bodyJson: String): RoundResult = error("redirect required")

        override suspend fun into(bodyJson: String, sink: WireSink): RoundResult {
            val started = outer(source = "")
            sink.customToolSource(CustomToolSource.Started(started))
            sink.customToolSource(CustomToolSource.Delta(started.callId, "await tools.Read({});"))
            sink.customToolSource(CustomToolSource.Terminal)
            return RoundResult.Outcome(terminal)
        }
    }

    private fun causeFor(kind: String): FailureCause.Provider = when (kind) {
        "filter" -> FailureCause.Provider.Refused(RefusedBy.FILTER)
        "model-refused" -> FailureCause.Provider.Refused(RefusedBy.MODEL, ProviderWordsFilter.of(MODEL_REASON))
        "too-long" -> FailureCause.Provider.TooLong(limitTokens = CONTEXT_LIMIT, whileAnswering = false)
        "forbidden" -> FailureCause.Provider.Forbidden(ProviderWordsFilter.of(FORBIDDEN_REASON))
        "server-error" -> FailureCause.Provider.ServerError(
            ServerFault.INTERNAL,
            ProviderWordsFilter.of(SERVER_REASON),
        )
        "overloaded" -> FailureCause.Provider.Overloaded(Lift.Unstated, loading = false)
        else -> error("unknown kind: $kind")
    }

    private class UnusedNativeRuntime : NativeCodeModeLifecycle {
        override suspend fun start(
            source: String,
            tools: Set<String>,
            descriptions: Map<String, String>,
        ): CodeModeCell = error("unused")

        override suspend fun startNativeSession(
            sessionKey: String,
            source: String,
            tools: Set<String>,
            descriptions: Map<String, String>,
            options: NativeCodeModeOptions,
        ): NativeCodeModeCell = error("unfinished source must not start a native cell")

        override fun close() = Unit
    }

    private class WaitingStreamingRuntime : CodeModeRuntime {
        override suspend fun start(
            source: String,
            tools: Set<String>,
            descriptions: Map<String, String>,
        ): CodeModeCell = error("streaming start required")

        override suspend fun startStreamingSession(
            sessionKey: String,
            source: CodeModeSource,
            tools: Set<String>,
            descriptions: Map<String, String>,
        ): CodeModeCell {
            CompletableDeferred<Unit>().await()
            error("unreachable")
        }

        override fun close() = Unit
    }
}
