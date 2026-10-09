// NEW: V4-404 — a failed turn's trace has a sentence in words for EVERY outcome tag, not only the ones whose
// connection ended. RED on the pre-fix telemetry: only TurnConnEnd wrote `failure_sentence`, so an
// upstream-failed turn's detail read `error:upstream-failed · 3 retries` and then Timing (Marlin's walk of
// V4-349 on bb54736ea). The tags are enumerated from their SOURCES, never from a hand list: OutcomeTag and
// ErrorType are read as enums, and the open `error:<kind>` strings are read out of this module's own sources,
// so a new tag without a sentence fails BY NAME and cannot be missed by a list nobody updated.
package splice.head.v4404

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.index.WireBlockIndex
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.perf.OutcomeTag
import splice.core.perf.OutcomeTags
import splice.core.perf.TurnPerf
import splice.core.storage.ActivityDays
import splice.core.turn.CONN_RESET_OUTCOME
import splice.core.turn.ErrorType
import splice.core.turn.FailureCause
import splice.core.turn.FailurePhase
import splice.core.turn.ReasoningDisplay
import splice.core.turn.TurnMeta
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.core.turn.WatchdogBudget
import splice.core.util.AsyncFileIo
import splice.core.util.ERR_SNIPPET
import splice.core.util.ElapsedClock
import splice.core.util.LogSink
import splice.core.util.WallClock
import splice.dialect.responses.ReasoningSettings
import splice.head.HeadHealthCounters
import splice.head.TestResponsesProvider
import splice.head.admission.LocalRefusal
import splice.head.admission.admittedSlot
import splice.head.compact.CompactStats
import splice.head.headDeps
import splice.head.perf.PerfStats
import splice.head.pipeline.TurnPipeline
import splice.head.round.RunnerSignals
import splice.head.turn.CancellationSeal
import splice.head.turn.OutcomeSentences
import splice.head.turn.TurnDrive
import splice.head.turn.TurnFailures
import splice.head.turn.TurnFinish
import splice.head.turn.TurnKnownEnd
import splice.head.turn.TurnTelemetry
import splice.head.turn.TurnUsageStamp
import splice.head.usage.OutputClamp
import splice.head.wire.ClientChannel
import splice.head.wire.ClientInbound
import splice.head.wire.ImmediateSseWriter
import splice.head.wire.TurnTerminal
import splice.upstream.Provider
import splice.upstream.ProviderTuning
import splice.upstream.Ticker
import splice.upstream.TurnSignals
import splice.upstream.retry.InflightGate
import splice.upstream.retry.LiveLimit
import splice.upstream.retry.TurnWatchdog
import splice.upstream.transport.UpstreamFailed
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private const val TRACE_DAY_EPOCH_MS = 1_789_725_600_000L
private const val TRACE_FILE = "codex-2026-09-18.jsonl"
private const val SOURCE_ROOT = "features/turns/src/main/kotlin"

/** The tags that are not failures, each with the reason it carries no sentence. Read with [OutcomeSentences]:
 *  a tag on this list must answer null, so an exemption cannot outlive its reason unnoticed. */
private val notFailures: Map<String, String> = mapOf(
    OutcomeTag.OK.wire to "the turn succeeded",
    OutcomeTag.CLIENT_ABORT.wire to "the client closed the connection: nobody is left to read a sentence",
    OutcomeTag.EMPTY_MESSAGE.wire to "a finished answer that happened to be empty; StreamPromote ends it clean",
)

/** Holds a credential so provider construction is honest; the surfaces under test never dial out. */
private class FakeAuth : RefreshableAuthProvider {
    override suspend fun credentials() = Credentials.Bearer("tok", "acct")
    override suspend fun refresh() = credentials()
    override suspend fun describe() = AuthDescription(true, "fake")
}

private class RecordingTerminal : TurnTerminal {
    override var hasEnded: Boolean = false
        private set

    override suspend fun emitError(type: ErrorType, message: String, permanent: Boolean) {
        hasEnded = true
    }

    override suspend fun emitTerminal(hasToolUse: Boolean, incomplete: Boolean, usage: Usage) = Unit
    override fun abandon() = Unit
    override suspend fun openText() = WireBlockIndex(0)
    override suspend fun openThinking() = WireBlockIndex(0)
    override suspend fun openTool(id: String, name: String) = WireBlockIndex(0)
    override suspend fun textDelta(index: WireBlockIndex, text: String) = Unit
    override suspend fun thinkingDelta(index: WireBlockIndex, thinking: String) = Unit
    override suspend fun inputJsonDelta(index: WireBlockIndex, partialJson: String) = Unit
    override suspend fun closeBlock(index: WireBlockIndex) = Unit
    override suspend fun closeAll() = Unit
    override suspend fun addTextBlock(text: String) = Unit
    override suspend fun addRedactedThinking(data: String) = Unit
}

class OutcomeSentenceTest {

    // ── the tag sources ───────────────────────────────────────────────────────────────────────

    /** Every `OutcomeTags.error(<arg>)` call in this module's main sources: the file and the argument text. */
    private fun errorKindCalls(): List<Pair<String, String>> {
        var dir = Path.of("").toAbsolutePath()
        while (!Files.isDirectory(dir.resolve(SOURCE_ROOT)) && dir.parent != null) dir = dir.parent
        val pattern = Regex("""OutcomeTags\.error\(\s*([^)]*?)\s*\)""")
        val comments = Regex("""(?sm)/\*.*?\*/|^\s*//[^\n]*| //[^\n]*""")
        return Files.walk(dir.resolve(SOURCE_ROOT)).use { files ->
            files.filter { it.toString().endsWith(".kt") }.toList().flatMap { file ->
                val code = comments.replace(Files.readString(file), "")
                pattern.findAll(code).map { file.fileName.toString() to it.groupValues[1] }.toList()
            }
        }
    }

    private fun sourceKinds(): List<String> = errorKindCalls().map { (file, arg) ->
        val literal = arg.startsWith("\"") && arg.endsWith("\"")
        assertTrue(literal, "$file builds an error tag from `$arg`: name its kind as a literal")
        arg.trim('"')
    }.distinct()

    /** Every tag a turn of this module can end on, from the tag sources. */
    private fun everyTag(): List<String> =
        OutcomeTag.entries.map { it.wire } +
            ErrorType.entries.map { OutcomeTags.failure(it) } +
            CONN_RESET_OUTCOME +
            sourceKinds().map { OutcomeTags.error(it) }

    // ── the table itself ──────────────────────────────────────────────────────────────────────

    @Test
    fun `every outcome tag has a sentence or a stated reason for none`() {
        val tags = everyTag()
        assertTrue(tags.size > OutcomeTag.entries.size, "the tag sources were not enumerated: $tags")
        for (tag in tags) {
            val sentence = OutcomeSentences.of(tag)
            if (tag in notFailures) {
                assertNull(sentence, "outcome tag $tag is exempt (${notFailures[tag]}) yet has a sentence")
            } else {
                assertNotNull(sentence, "outcome tag $tag has no failure sentence: add it to OutcomeSentences")
            }
        }
    }

    @Test
    fun `every sentence says what to do in words and quotes no path or bytes`() {
        for (tag in everyTag()) {
            val sentence = OutcomeSentences.of(tag) ?: continue
            if (tag == OutcomeTag.RESTARTED.wire) {
                assertEquals("Splice restarted while this request was running. Retry the request.", sentence)
            } else {
                assertTrue(
                    "; " in sentence,
                    "$tag: `$sentence` must say what happened, then after a semicolon what to do",
                )
            }
            assertTrue(sentence.length <= ERR_SNIPPET, "$tag: the sentence is cut at $ERR_SNIPPET characters")
            val quoted = sentence.any { it in "/\\{}<>\"`" } || "://" in sentence || "~" in sentence
            assertTrue(!quoted, "$tag: `$sentence` quotes a path or bytes")
        }
    }

    @Test
    fun `a legacy typed tag does not invent provider origin`() {
        for (type in ErrorType.entries) {
            val sentence = checkNotNull(OutcomeSentences.of(OutcomeTags.failure(type)))
            assertTrue("does not say whether splice or the provider" in sentence, sentence)
        }
    }

    @Test
    fun `local and provider invalid requests keep their origin in the trace and local terminal`(
        @TempDir tmp: Path,
    ) = runBlocking {
        val localSentence = "splice could not complete this session's code-mode step; " +
            "start a new session, and if it repeats read the daemon log"
        val providerSentence = "the provider rejected the request as invalid, so resending it unchanged fails the same way; " +
            "change the request before retrying"
        for (reported in listOf(false, true)) {
            val rig = Rig("origin-$reported", tmp)
            val deps = headDeps(tmp.resolve("stores-$reported"))
            var explained: String? = null
            val terminal = object : TurnTerminal by RecordingTerminal() {
                override suspend fun emitExplained(message: String, usage: Usage) {
                    explained = message
                }
            }
            val drive = rig.drive().copy(emitter = terminal)
            val failure = TurnOutcome.Failure(
                message = "code-mode runtime failed: IllegalStateException at SyntheticCell.kt:83; " +
                    "accepted results=0; source was not rerun",
                cause = if (reported) FailureCause.UPSTREAM_STATUS_4XX else FailureCause.CODE_MODE_PROTOCOL,
                phase = FailurePhase.TERMINAL,
                providerReported = reported,
                deterministic = !reported,
                permanent = true,
            )
            assertEquals(ErrorType.INVALID_REQUEST, failure.type)
            val finish = TurnFinish(
                ElapsedClock { 5L },
                rig.log,
                TurnUsageStamp(deps.stores.usageStore, rig.log, rig.telemetry),
                HeadHealthCounters(),
                rig.telemetry,
            )
            try {
                finish.finishTurn(drive, failure)
            } finally {
                drive.slot.release()
            }
            assertEquals(if (reported) providerSentence else localSentence, sentenceOf(rig.turnRecord()))
            if (!reported) {
                assertTrue(checkNotNull(explained).startsWith(localSentence + ".\n\n"), explained)
                assertTrue("[SPLICE-INVALID-REQUEST]" in checkNotNull(explained))
                assertTrue(failure.message in checkNotNull(explained))
            }
        }
    }

    @Test
    fun `a translator parsed provider invalid request retains provider origin`(@TempDir tmp: Path) = runBlocking {
        val rig = Rig("parsed-provider", tmp)
        val event = Json.parseToJsonElement(
            """{"type":"response.failed","response":{"error":{"code":"request_too_large","message":"prompt is too long"}}}""",
        ).jsonObject
        val outcome = provider().streamTranslator(
            rig.meta,
            TurnSignals(watchdogFired = { null }, clientGone = { false }),
        ).driveTurn(flowOf(event), RecordingTerminal())
        val failure = outcome as? TurnOutcome.Failure ?: error("provider error must fail")
        assertTrue(failure.providerReported)
        assertEquals(ErrorType.INVALID_REQUEST, failure.type)
        assertTrue(OutcomeSentences.of(failure).startsWith("the provider rejected"))
    }

    @Test
    fun `every local cause fits its whole human action before a clipped card diagnostic`() {
        for (cause in FailureCause.entries) {
            val failure = TurnOutcome.Failure("synthetic", cause, FailurePhase.TERMINAL)
            val sentence = OutcomeSentences.of(failure)
            assertTrue(sentence.startsWith("splice "), "$cause: $sentence")
            assertTrue("; " in sentence, "$cause: $sentence")
            assertTrue(sentence.length < 150, "$cause: the card would clip its action")
        }
    }

    @Test
    fun `a typed local finish preserves a more specific recorded sentence`(@TempDir tmp: Path) = runBlocking {
        val rig = Rig("specific-origin", tmp)
        val deps = headDeps(tmp.resolve("stores"))
        val drive = rig.drive()
        val sentence = "splice could not save this step; free space and start a new session"
        rig.trace.failureSentence(sentence)
        try {
            TurnFinish(
                ElapsedClock { 5L },
                rig.log,
                TurnUsageStamp(deps.stores.usageStore, rig.log, rig.telemetry),
                HeadHealthCounters(),
                rig.telemetry,
            ).finishTurn(
                drive,
                TurnOutcome.Failure("synthetic", FailureCause.CODE_MODE_PROTOCOL, FailurePhase.TERMINAL),
            )
        } finally {
            drive.slot.release()
        }
        assertEquals(sentence, sentenceOf(rig.turnRecord()))
    }

    @Test
    fun `provider content refusals keep the cause and provider words without calling them an outage`() {
        val cyber = TurnOutcome.Failure(
            "upstream: cyber_policy this request was flagged. Try rephrasing.",
            FailureCause.CONTENT_FILTERED,
            FailurePhase.MID_OUTPUT,
            providerReported = true,
            permanent = true,
        )
        assertEquals(
            "OpenAI refused the request under its cybersecurity check. This request was flagged. Try rephrasing.",
            OutcomeSentences.of(cyber),
        )
        val generic = cyber.copy(message = "upstream: generation stopped by content filter")
        val sentence = OutcomeSentences.of(generic)
        assertTrue("content check" in sentence, sentence)
        assertTrue("cybersecurity" !in sentence, sentence)
        for (failure in listOf(cyber, generic, cyber.copy(cause = FailureCause.MODEL_REFUSED))) {
            val words = OutcomeSentences.of(failure)
            assertTrue("failed on its side" !in words && "retry in a moment" !in words, words)
        }
        assertTrue(OutcomeSentences.of(cyber.copy(providerReported = false)).startsWith("splice "))
    }

    // ── the trace it closes ───────────────────────────────────────────────────────────────────

    private inner class Rig(private val tag: String, private val tmp: Path) {
        val log = LogSink { }
        val perfFile: Path = tmp.resolve("perf-$tag.jsonl")
        val traceDir: Path = tmp.resolve("trace-$tag")
        val telemetry = TurnTelemetry("codex", PerfStats(perfFile), log, ElapsedClock { 5L })
        val meta = TurnMeta(
            compact = false,
            showReasoning = ReasoningDisplay.TEXT,
            stream = true,
            originalModel = "claude-codex--gpt-5.6-sol",
            upstreamModel = "gpt-5.6-sol",
            clientMaxTokens = 100,
            effort = "high",
            summary = "detailed",
            budgetTokens = null,
        )
        val trace = splice.head.syntheticTraceStore(
            ActivityDays(
                traceDir,
                "codex",
                retentionDays = 7,
                clock = WallClock { TRACE_DAY_EPOCH_MS },
                ownerOnly = true,
            ),
            head = "codex",
            maxBodyChars = 4096,
            now = WallClock { TRACE_DAY_EPOCH_MS },
        ).begin(meta, ClientInbound("POST", "/v1/messages", emptyMap(), "synthetic request"))

        suspend fun drive(): TurnDrive = TurnDrive(
            requestBody = buildJsonObject { },
            meta = meta,
            emitter = RecordingTerminal(),
            watchdog = TurnWatchdog(WatchdogBudget(10.seconds, 10.seconds, 30.seconds)),
            slot = InflightGate(LiveLimit { 1 }).admittedSlot(),
            pipeline = TurnPipeline(
                CompactStats(perfFile.resolveSibling("compact-$tag.jsonl")),
                log = log,
                clampOutput = OutputClamp { it },
            ),
            t0 = 0,
            trace = trace,
            perf = TurnPerf(),
            turnHeaders = emptyMap(),
            signals = RunnerSignals(),
            channel = ClientChannel(
                ImmediateSseWriter(writeRaw = { _ -> }, flushRaw = {}),
                Mutex(),
                AtomicBoolean(false),
            ),
            toolSearch = null,
        )

        /** The turn record the trace wrote, once the async writer has drained. */
        fun turnRecord() = run {
            assertTrue(AsyncFileIo.drain())
            Json.parseToJsonElement(Files.readAllLines(traceDir.resolve(TRACE_FILE)).last()).jsonObject
        }
    }

    private fun provider(): Provider = TestResponsesProvider(
        tuning = ProviderTuning(
            key = "codex",
            label = "claudex",
            catalog = ModelCatalog(
                discoveryPrefix = "claude-codex--",
                models = listOf(ModelEntry("gpt-5.6-sol", "Sol", contextWindow = 272_000)),
                defaultContextWindow = 272_000,
            ),
            pinnedModel = "gpt-5.6-sol",
            auth = FakeAuth(),
            baseUrl = "http://127.0.0.1:1",
            watchdog = WatchdogBudget(10.seconds, 10.seconds, 30.seconds),
            loginCommand = "claudex login",
        ),
        reasoning = ReasoningSettings(ReasoningDisplay.TEXT, false, "high", "detailed"),
    )

    private fun sentenceOf(record: kotlinx.serialization.json.JsonObject): String? =
        record["failure_sentence"]?.jsonPrimitive?.content

    @Test
    fun `every outcome tag closes its turn record with its sentence`(@TempDir tmp: Path) = runBlocking {
        for ((at, tag) in everyTag().withIndex()) {
            val rig = Rig("tag-$at", tmp)
            val drive = rig.drive()
            try {
                rig.telemetry.recordPerf(drive, tag)
            } finally {
                drive.slot.release()
            }
            val record = rig.turnRecord()
            assertEquals(tag, record.getValue("outcome").jsonPrimitive.content)
            val said = sentenceOf(record)
            assertEquals(OutcomeSentences.of(tag), said, "outcome tag $tag: the turn record's failure_sentence")
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `progress timeout records the same splice sentence as its client terminal`(
        stream: Boolean,
        @TempDir tmp: Path,
    ) = runBlocking {
        val rig = Rig("progress-timeout", tmp)
        var now = 0L
        val dog = TurnWatchdog(
            WatchdogBudget(10.seconds, 10.seconds, 600.milliseconds),
            clock = ElapsedClock { now },
            ticker = Ticker { interval ->
                now += interval
                true
            },
        )
        val target = Job()
        val cap = dog.launchTotalCap(this, target)
        withTimeout(1_000) { target.join() }
        cap.cancel()
        var wireMessage: String? = null
        var wireType: ErrorType? = null
        val terminal = object : TurnTerminal by RecordingTerminal() {
            override suspend fun emitError(type: ErrorType, message: String, permanent: Boolean) {
                wireType = type
                wireMessage = message
            }
        }
        val drive = rig.drive().copy(watchdog = dog, emitter = terminal)
        val deps = headDeps(tmp.resolve("stores"))
        val seal = CancellationSeal(
            provider(),
            rig.log,
            rig.telemetry,
            HeadHealthCounters(),
            TurnUsageStamp(deps.stores.usageStore, rig.log, rig.telemetry),
        )
        try {
            seal.seal(drive, stream, CancellationException("synthetic progress timeout"))
        } finally {
            drive.slot.release()
        }
        val expected = "[SPLICE-OVERLOADED] splice progress timeout expired after 600ms " +
            "without upstream progress; retry"
        assertEquals(expected, sentenceOf(rig.turnRecord()))
        assertEquals(if (stream) expected else null, wireMessage)
        assertEquals(if (stream) ErrorType.OVERLOADED else null, wireType)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `shutdown cut remains a local failure even after its socket closes`(
        stream: Boolean,
        @TempDir tmp: Path,
    ) = runBlocking {
        val rig = Rig("restart-$stream", tmp)
        val deps = headDeps(tmp.resolve("stores"))
        val seal = CancellationSeal(
            provider(),
            rig.log,
            rig.telemetry,
            HeadHealthCounters(),
            TurnUsageStamp(deps.stores.usageStore, rig.log, rig.telemetry),
        )
        var wireType: ErrorType? = null
        val terminal = object : TurnTerminal by RecordingTerminal() {
            override suspend fun emitError(type: ErrorType, message: String, permanent: Boolean) {
                wireType = type
                throw java.io.IOException("synthetic server socket closed")
            }
        }
        val drive = rig.drive().copy(emitter = terminal)
        drive.channel.clientGone.set(true)
        try {
            seal.seal(drive, stream, splice.head.turn.HeadRestart())
        } finally {
            drive.slot.release()
        }
        val record = rig.turnRecord()
        assertEquals(OutcomeTag.RESTARTED.wire, record.getValue("outcome").jsonPrimitive.content)
        assertEquals("Splice restarted while this request was running. Retry the request.", sentenceOf(record))
        assertTrue(OutcomeTags.isFailed(OutcomeTag.RESTARTED.wire))
        assertTrue(!OutcomeTags.isStopped(OutcomeTag.RESTARTED.wire))
        assertEquals(if (stream) ErrorType.OVERLOADED else null, wireType)
    }

    @Test
    fun `a local refusal closes its trace with its sentence`(@TempDir tmp: Path) = runBlocking {
        val refusals = listOf(OutcomeTag.RATE_LIMITED, OutcomeTag.ALL_ACCOUNTS_EXHAUSTED, OutcomeTag.BUDGET_BLOCKED)
        for (tag in refusals) {
            val rig = Rig("refusal-${tag.name}", tmp)
            rig.telemetry.recordLocalRefusal(rig.meta, TurnPerf(), 0, LocalRefusal(tag.wire, "detail", rig.trace))
            val record = rig.turnRecord()
            assertEquals(tag.wire, record.getValue("outcome").jsonPrimitive.content)
            val said = sentenceOf(record)
            assertEquals(OutcomeSentences.of(tag.wire), said, "local refusal ${tag.wire}: failure_sentence")
            assertNotNull(said, "local refusal ${tag.wire} left the turn without words")
        }
    }

    @Test
    fun `an upstream-failed turn after three retries reads in words, Marlin's walk`(@TempDir tmp: Path) = runBlocking {
        val rig = Rig("upstream-failed", tmp)
        val provider = provider()
        val knownEnd = TurnKnownEnd(provider, rig.log, rig.telemetry, TurnFailures(provider), HeadHealthCounters())
        val drive = rig.drive()
        try {
            val failure = UpstreamFailed("""{"error":{"message":"boom"}}""", status = 502, layers = 3)
            knownEnd.emitFailed(drive, failure)
        } finally {
            drive.slot.release()
        }
        val record = rig.turnRecord()
        assertEquals("error:upstream-failed", record.getValue("outcome").jsonPrimitive.content)
        val sentence = sentenceOf(record)
        assertNotNull(sentence, "the upstream-failed turn's trace has no sentence")
        assertEquals(OutcomeSentences.of(OutcomeTag.UPSTREAM_FAILED.wire), sentence)
    }

    @Test
    fun `a sentence the ending's own surface spoke is kept, not replaced by the table's`(@TempDir tmp: Path) =
        runBlocking {
            val rig = Rig("spoken", tmp)
            val drive = rig.drive()
            try {
                rig.trace.failureSentence("the connection to 127.0.0.1:1 closed mid-request; retry")
                rig.telemetry.recordPerf(drive, CONN_RESET_OUTCOME)
            } finally {
                drive.slot.release()
            }
            assertEquals(
                "the connection to 127.0.0.1:1 closed mid-request; retry",
                sentenceOf(rig.turnRecord()),
            )
        }
}
