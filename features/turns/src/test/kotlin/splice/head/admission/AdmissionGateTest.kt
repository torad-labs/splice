package splice.head.admission

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.response.ApplicationSendPipeline
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.AnnotatedElementContext
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.io.TempDirDeletionStrategy
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.ForeignHostLog
import splice.core.auth.RefreshableAuthProvider
import splice.core.budget.BudgetBlock
import splice.core.budget.HeadBudget
import splice.core.memory.HeapBudget
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.parse.AnthropicTurnBody
import splice.core.perf.PerfKeys
import splice.core.perf.TurnPerf
import splice.core.turn.ReasoningDisplay
import splice.core.turn.TurnMeta
import splice.core.turn.WatchdogBudget
import splice.core.util.AsyncFileIo
import splice.dialect.responses.ReasoningSettings
import splice.head.AnthropicBodyParse
import splice.head.ClientAuth
import splice.head.HeadDeps
import splice.head.HeadFileWriteCleanup
import splice.head.RequestBodyRead
import splice.head.RequestBodyReader
import splice.head.TestResponsesProvider
import splice.head.compaction.CompactionReplay
import splice.head.headStores
import splice.head.noQuota
import splice.head.turn.LiveTurns
import splice.head.turn.Preparation
import splice.head.turn.SESSION_HEADER
import splice.head.turn.TurnDriver
import splice.head.turn.TurnPreparation
import splice.head.wire.FrameRecording
import splice.upstream.BuiltTurn
import splice.upstream.InterceptedRoundPost
import splice.upstream.Provider
import splice.upstream.ProviderTuning
import splice.upstream.RoundInterceptor
import splice.upstream.RoundResult
import splice.upstream.TurnEnd
import splice.upstream.failure.SseSpuriousWakeupException
import splice.upstream.memory.JvmHeap
import splice.upstream.retry.InflightGate
import splice.upstream.sse.WireSink
import splice.upstream.transport.UpstreamClient
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

private class AdmissionTestAuth : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.Bearer("token", "account")
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "test")
}

// This file KEEPS its own local builder rather than importing the splice.head one: the two would
// share the name `headDeps` and the import would collide with this declaration. It composes the same
// bundles instead, which is all the fixture does anyway.
private fun headDeps(tmp: Path, mirrorReasoning: Boolean = false) = HeadDeps(
    upstream = UpstreamClient(totalTimeoutMs = 1_000, maxRetries = 1),
    inferenceToken = "test-inference-token",
    operatorToken = "test-operator-token",
    gate = InflightGate({ 1 }),
    liveTurns = LiveTurns(),
    log = {},
    stores = headStores(tmp),
    quotaBundle = noQuota(),
    policy = HeadDeps.HeadPolicy(mirrorReasoning = mirrorReasoning),
    seams = HeadDeps.HeadSeams(),
)

class MaterializationRefusalTest {
    @Test
    fun `bodies that cannot fit return a permanent limit error in both admission modes`(
        @TempDir tmp: Path,
    ) = testApplication {
        val heap = RequestMaterializationGate(heap = HeapBudget(JvmHeap.limitBytes, 13))
        val deps = headDeps(tmp).copy(
            policy = HeadDeps.HeadPolicy(maxRequestBytes = 4),
            seams = HeadDeps.HeadSeams(requestMaterializationGate = heap),
        )
        val admission = AdmissionGate(testProvider, deps, AdmissionWindow(), AdmissionResponses())
        var entered = false
        var released = 0
        application {
            routing {
                post("/probe") {
                    admission.materializeOrRespond(
                        call,
                        Materializing(
                            fastFail = call.request.headers["x-synthetic-fast"] == "true",
                            beforeRefusal = TurnEnd { released++ },
                        ),
                    ) {
                        entered = true
                        "must not materialize"
                    }
                }
            }
        }
        for (fast in listOf(false, true)) {
            for (unknown in listOf(false, true)) {
                val response = client.post("/probe") {
                    header("x-synthetic-fast", fast.toString())
                    if (unknown) {
                        setBody(object : OutgoingContent.ReadChannelContent() {
                            override fun readFrom(): ByteReadChannel = ByteReadChannel("hey")
                        })
                    } else {
                        setBody("hey")
                    }
                }
                val body = response.bodyAsText()
                assertEquals(HttpStatusCode.PayloadTooLarge, response.status, body)
                assertTrue(body.contains("invalid_request_error"), body)
                assertTrue(body.contains("materialization heap limit is 13 bytes"), body)
                assertTrue(body.contains("requires ${if (unknown) 26 else 20} bytes"), body)
                assertFalse(body.contains("retry"), body)
                assertEquals(13L, heap.heap.available.value)
            }
        }
        assertFalse(entered, "a permanent refusal must not decode an uncharged body")
        assertEquals(4, released, "the borrowed candidate is returned before every refusal")
    }
}

class SourceContinuationAdmissionTest {
    @Test
    fun `a result arriving before the prior continuation releases borrows after that release`(
        @TempDir tmp: Path,
    ) = testApplication {
        val deps = headDeps(tmp)
        val original = (deps.gate.acquire() as InflightGate.Admission.Acquired).slot
        val source = original.retainSource("synthetic-session")
        original.release()
        val previous = checkNotNull(deps.gate.resumeSource("synthetic-session"))
        val admission = AdmissionGate(testProvider, deps, AdmissionWindow().apply { open() }, AdmissionResponses())
        application {
            routing {
                post("/probe") {
                    val candidate = checkNotNull(admission.acquireSlotOrRespond(call))
                    try {
                        assertTrue(candidate.resumedSource, "the result must share its existing source permit")
                        call.respondText("synthetic resumed reply")
                    } finally {
                        candidate.release()
                    }
                }
            }
        }
        coroutineScope {
            val result = async {
                client.post("/probe") { header(SESSION_HEADER, "synthetic-session") }
            }
            try {
                withTimeout(3.seconds) { while (deps.gate.snapshot().queued != 1) kotlinx.coroutines.yield() }
                assertFalse(result.isCompleted)
                previous.release()
                assertEquals(HttpStatusCode.OK, withTimeout(3.seconds) { result.await() }.status)
                assertEquals(1L, deps.gate.snapshot().acquired, "no fresh upstream permit was acquired")
                assertEquals(1, deps.gate.snapshot().inflight, "the independent source still owns its permit")
            } finally {
                result.cancelAndJoin()
                previous.release()
                source.release()
            }
        }
    }
}

class AdmissionGateTest {
    private val fileWriterRelease = CountDownLatch(1)
    private var bodyReturned = false
    private var controlledRoot: Path? = null

    @AfterEach
    fun finishFileWrites() {
        bodyReturned = true
        // Omitting the join must leave the controlled writer held until the deletion walk captures its paths.
        controlledRoot?.let { HeadFileWriteCleanup().awaitWrites(it.also { fileWriterRelease.countDown() }) }
    }

    /** Force the queued real perf append into the cleanup walk's directory-deletion window. */
    class LateFileWriteDeletion : TempDirDeletionStrategy {
        override fun delete(
            root: Path,
            elementContext: AnnotatedElementContext,
            extensionContext: ExtensionContext,
        ): TempDirDeletionStrategy.DeletionResult {
            val test = checkNotNull(extensionContext.requiredTestInstance as? AdmissionGateTest)
            check(test.bodyReturned) { "the controlled append must outlive the test body" }
            val paths = Files.walk(root).use { it.sorted(Comparator.reverseOrder()).toList() }
            test.fileWriterRelease.countDown()
            assertTrue(AsyncFileIo.drain(), "the held perf append must finish before the deletion attempts")
            val result = TempDirDeletionStrategy.DeletionResult.builder(root)
            paths.forEach { path ->
                try {
                    Files.delete(path)
                } catch (failure: IOException) {
                    result.addFailure(path, failure)
                }
            }
            // Reap our own synthetic residue, but return every failure to JUnit unchanged.
            if (Files.exists(root)) {
                val reaped = TempDirDeletionStrategy.Standard.INSTANCE.delete(root, elementContext, extensionContext)
                reaped.failures().forEach { result.addFailure(it.path(), it.cause()) }
            }
            return result.build()
        }
    }

    @Test
    fun `head dependencies keep the reasoning mirror locked off`(@TempDir tmp: Path) {
        assertFalse(headDeps(tmp).policy.mirrorReasoning)
        assertThrows(IllegalArgumentException::class.java) { headDeps(tmp, mirrorReasoning = true) }
    }

    @Test
    fun `a lying request channel is a retryable timeout rather than a malformed request`(
        @TempDir tmp: Path,
    ) = testApplication {
        val provider = testProvider
        val deps = headDeps(tmp)
        val responses = AdmissionResponses()
        val admission = AdmissionGate(provider, deps, AdmissionWindow(), responses)
        val reader = RequestBodyReader(
            deps.policy.requestReadTimeoutMs,
            RequestBodyRead { _, _ -> throw SseSpuriousWakeupException(1024) },
        )

        application {
            routing {
                post("/probe") {
                    admission.materializeOrRespond(call) {
                        reader.receiveBodyBounded(call, deps.policy.maxRequestBytes)
                    }
                }
            }
        }

        val response = client.post("/probe")
        // 408, not 400 (DR-20): a torn CLIENT body is a retryable connection event, and 400 told
        // Claude Code its request itself was malformed — a non-retryable class for it.
        assertEquals(HttpStatusCode.RequestTimeout, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("invalid_request_error"), body)
        assertTrue(body.contains("request body stream interrupted"), body)
    }

    @Test
    fun `declared bodies reserve their size unknown bodies reserve the cap and oversized bodies keep 413`(
        @TempDir tmp: Path,
    ) = testApplication {
        val materialization = RequestMaterializationGate(heap = HeapBudget(JvmHeap.limitBytes, 26))
        val deps = headDeps(tmp).copy(
            policy = HeadDeps.HeadPolicy(maxRequestBytes = 4),
            seams = HeadDeps.HeadSeams(requestMaterializationGate = materialization),
        )
        val admission = AdmissionGate(testProvider, deps, AdmissionWindow(), AdmissionResponses())
        val lengths = mutableListOf<String?>()
        application {
            routing {
                post("/probe") {
                    lengths += call.request.headers[HttpHeaders.ContentLength]
                    val entered = admission.materializeOrRespond(call, Materializing(fastFail = true)) { "admitted" }
                    if (entered != null) call.respondText(entered)
                }
            }
        }

        coroutineScope {
            val acquired = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val holder = async {
                materialization.withLease(1) {
                    acquired.complete(Unit)
                    release.await()
                }
            }
            acquired.await()
            try {
                val small = client.post("/probe") { setBody("hi") }
                assertEquals(HttpStatusCode.OK, small.status)
                val unknown = client.post("/probe") {
                    setBody(object : OutgoingContent.ReadChannelContent() {
                        override fun readFrom(): ByteReadChannel = ByteReadChannel("hi")
                    })
                }
                assertEquals(529, unknown.status.value, unknown.bodyAsText())
                val oversized = client.post("/probe") { setBody("hello") }
                assertEquals(HttpStatusCode.PayloadTooLarge, oversized.status)
                assertEquals(listOf("2", null, "5"), lengths, "the fixture must really omit Content-Length")
            } finally {
                release.complete(Unit)
                holder.await()
            }
        }
    }

    @Test
    fun `local preparation releases its request before any response is published`(
        @TempDir tmp: Path,
    ) = testApplication {
        val heap = RequestMaterializationGate(heap = HeapBudget(JvmHeap.limitBytes, 7))
        val deps = headDeps(tmp)
        val admission = AdmissionGate(testProvider, deps, AdmissionWindow(), AdmissionResponses())
        val preparations = listOf(
            Preparation.Rejected("synthetic rejection"),
            Preparation.Local("synthetic answer", "synthetic-model", null, false),
            Preparation.Replay(FrameRecording(), "synthetic-key", null, "synthetic-model"),
        )
        val releasedAtReply = mutableListOf<Boolean>()
        var next = 0
        application {
            sendPipeline.intercept(ApplicationSendPipeline.Before) {
                releasedAtReply += heap.tryWithLease(1) { true } == true
            }
            routing {
                post("/probe") {
                    val slot = (deps.gate.acquire() as InflightGate.Admission.Acquired).slot
                    val admitted = AdmittedTurn(slot, 0L, TurnPerf())
                    try {
                        val prepared = checkNotNull(
                            heap.withLease(1, MaterializationOwner { admitted.materializedEnd = it }) {
                                preparations[next++]
                            },
                        )
                        assertEquals(null, heap.tryWithLease(1) { "precondition: the local loan is held" })
                        assertTrue(admitted.settle(call, prepared, admission))
                        call.respondText("synthetic local reply")
                    } finally {
                        admitted.close()
                    }
                }
            }
        }
        repeat(preparations.size) { assertEquals(HttpStatusCode.OK, client.post("/probe").status) }
        assertEquals(
            List(preparations.size) { true },
            releasedAtReply,
            "every local reply must release before publication",
        )
        assertEquals(0, deps.gate.snapshot().inflight)
    }

    @Test
    fun `fresh admission refusal releases the request before capacity or stopping replies`(
        @TempDir tmp: Path,
    ) = testApplication {
        val heap = RequestMaterializationGate(heap = HeapBudget(JvmHeap.limitBytes, 7))
        val gate = InflightGate({ 1 }, maxQueued = { 1 })
        val deps = headDeps(tmp).copy(gate = gate)
        val window = AdmissionWindow()
        val admission = AdmissionGate(testProvider, deps, window, AdmissionResponses())
        val releasedAtReply = mutableListOf<Boolean>()
        var request: AdmittedTurn? = null
        application {
            sendPipeline.intercept(ApplicationSendPipeline.Before) {
                releasedAtReply += heap.tryWithLease(1) { true } == true
            }
            routing {
                post("/probe") {
                    val admitted = checkNotNull(request)
                    try {
                        val prepared = checkNotNull(
                            heap.withLease(1, MaterializationOwner { admitted.materializedEnd = it }) {
                                ready()
                            },
                        )
                        assertEquals(null, heap.tryWithLease(1) { "precondition: the candidate loan is held" })
                        assertFalse(admitted.settle(call, prepared, admission))
                    } finally {
                        admitted.close()
                    }
                }
            }
        }
        coroutineScope {
            for (stopping in listOf(false, true)) {
                val (candidate, source) = retainedCandidate(gate, window, stopping)
                request = candidate
                val waiting = if (stopping) null else async(start = CoroutineStart.UNDISPATCHED) { gate.acquire() }
                try {
                    assertEquals(529, client.post("/probe").status.value)
                } finally {
                    waiting?.cancelAndJoin()
                    source.release()
                }
            }
        }
        assertEquals(listOf(true, true), releasedAtReply, "both refusal arms must release before publishing")
        assertEquals(0, gate.snapshot().inflight)
    }

    @Test
    fun `a failed heap release still returns the candidate permit and preserves its throwable`() =
        kotlinx.coroutines.runBlocking {
            val gate = InflightGate({ 1 })
            val slot = (gate.acquire() as InflightGate.Admission.Acquired).slot
            val admitted = AdmittedTurn(slot, 0L, TurnPerf())
            val failure = OutOfMemoryError("synthetic materialization release failure")
            val later = IllegalStateException("synthetic permit release failure")
            slot.onRelease(TurnEnd { throw later })
            admitted.materializedEnd = TurnEnd { throw failure }
            try {
                val actual = assertThrows(OutOfMemoryError::class.java) {
                    kotlinx.coroutines.runBlocking { admitted.close() }
                }
                assertEquals(0, gate.snapshot().inflight, "a failed heap callback must not strand its permit")
                assertSame(failure, generateSequence(actual as Throwable) { it.cause }.last())
                assertEquals(
                    listOf(later),
                    failure.suppressed.toList(),
                    "later cleanup cannot replace the first failure",
                )
            } finally {
                slot.release()
            }
        }

    @Test
    fun `release preserves cancellation after returning the permit and suppressing later failure`() =
        kotlinx.coroutines.runBlocking {
            val gate = InflightGate({ 1 })
            val slot = (gate.acquire() as InflightGate.Admission.Acquired).slot
            val admitted = AdmittedTurn(slot, 0L, TurnPerf())
            val cancellation = CancellationException("synthetic release cancellation")
            val later = IllegalStateException("synthetic permit callback failure")
            admitted.materializedEnd = TurnEnd { throw cancellation }
            slot.onRelease(TurnEnd { throw later })
            val actual = assertThrows(CancellationException::class.java) { admitted.release() }
            assertSame(cancellation, actual)
            assertEquals(listOf(later), actual.suppressed.toList())
            assertEquals(0, gate.snapshot().inflight)
        }

    @Test
    fun `body limit and read failures release the borrowed candidate before publishing`(
        @TempDir tmp: Path,
    ) = testApplication {
        val heap = RequestMaterializationGate(heap = HeapBudget(JvmHeap.limitBytes, 7))
        val deps = headDeps(tmp).copy(
            policy = HeadDeps.HeadPolicy(maxRequestBytes = 4),
            seams = HeadDeps.HeadSeams(requestMaterializationGate = heap),
        )
        val initial = (deps.gate.acquire() as InflightGate.Admission.Acquired).slot
        val source = initial.retainSource("synthetic-session")
        initial.release()
        var next = 0
        val reader = RequestBodyReader(
            1_000,
            RequestBodyRead { _, _ ->
                when (next) {
                    // Five bytes into a cap of four: the reader's running-total arm answers
                    // TooLarge before it appends, which is what the thrown refusal used to be.
                    1 -> 5
                    2 -> throw SseSpuriousWakeupException(1)
                    else -> withTimeout(1) { CompletableDeferred<Nothing>().await() }
                }
            },
        )
        val handler = handler(testProvider, deps, reader)
        val releasedAtReply = mutableListOf<Pair<Boolean, Boolean>>()
        application {
            sendPipeline.intercept(ApplicationSendPipeline.Before) {
                releasedAtReply += availableAtReply(deps, heap)
            }
            routing { post("/probe") { handler.handleMessages(call) } }
        }
        try {
            for (expected in listOf(413, 413, 408, 408)) {
                val response = client.post("/probe") {
                    header(HttpHeaders.Authorization, "Bearer test-inference-token")
                    header(SESSION_HEADER, "synthetic-session")
                    setBody(if (next == 0) "12345" else "x")
                }
                assertEquals(expected, response.status.value, response.bodyAsText())
                next++
            }
            assertEquals(List(4) { true to true }, releasedAtReply, "refusal must settle both local claims")
            assertEquals(1, deps.gate.snapshot().inflight, "the independent source remains alive")
        } finally {
            source.release()
        }
    }

    @Test
    fun `a ready budget refusal returns its heap without waiting for the live source`(
        @TempDir(deletionStrategy = LateFileWriteDeletion::class) tmp: Path,
    ) = testApplication {
        controlledRoot = tmp
        assertTrue(AsyncFileIo.submit { check(fileWriterRelease.await(30, TimeUnit.SECONDS)) })
        val body = """{"model":"claude-codex--gpt-5.6-sol","max_tokens":64,
            "messages":[{"role":"user","content":"synthetic continuation"}]}"""
        val required = splice.core.memory.HeapWeights.request(body.toByteArray().size.toLong())
        val heap = RequestMaterializationGate(heap = HeapBudget(JvmHeap.limitBytes, required))
        val budget = object : HeadBudget {
            override fun admit(): BudgetBlock = BudgetBlock("synthetic budget refusal", "synthetic limit")
            override fun spent(atMs: Long, model: String, counters: Map<String, Long>) = Unit
        }
        val deps = headDeps(tmp).copy(
            quotaBundle = noQuota().copy(budget = budget),
            seams = HeadDeps.HeadSeams(requestMaterializationGate = heap),
        )
        val initial = (deps.gate.acquire() as InflightGate.Admission.Acquired).slot
        val source = initial.retainSource("synthetic-session")
        initial.release()
        val handler = handler(continuationProvider(), deps)
        val releasedAtReply = mutableListOf<Pair<Boolean, Boolean>>()
        application {
            sendPipeline.intercept(ApplicationSendPipeline.Before) {
                releasedAtReply += availableAtReply(deps, heap)
            }
            routing { post("/probe") { handler.handleMessages(call) } }
        }
        try {
            val response = client.post("/probe") {
                header(HttpHeaders.Authorization, "Bearer test-inference-token")
                header(SESSION_HEADER, "synthetic-session")
                setBody(body)
            }
            assertEquals(HttpStatusCode.Forbidden, response.status, response.bodyAsText())
            assertEquals(listOf(true to true), releasedAtReply, "a local quota refusal must not retain a source loan")
            assertEquals(1, deps.gate.snapshot().inflight, "quota refusal must not end the independent source")
        } finally {
            source.release()
        }
    }

    @Test
    fun `a local activity query does not relabel the retained source it borrowed`(
        @TempDir tmp: Path,
    ) = testApplication {
        val deps = headDeps(tmp)
        val initial = (deps.gate.acquire() as InflightGate.Admission.Acquired).slot
        initial.describe("synthetic-source-model", compact = true, "source-tag")
        val source = initial.retainSource("synthetic-session")
        initial.release()
        val handler = handler(testProvider, deps)
        application { routing { post("/probe") { handler.handleMessages(call) } } }
        try {
            val response = client.post("/probe") {
                header(HttpHeaders.Authorization, "Bearer test-inference-token")
                header(SESSION_HEADER, "synthetic-session")
                setBody(
                    """{"model":"claude-codex--gpt-5.6-sol","max_tokens":16,"messages":[{
                        "role":"user","content":"Describe your most recent action in 3-5 words using present tense (-ing)."
                    }]}""",
                )
            }
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            val row = deps.gate.snapshot().live.single()
            assertEquals("source-tag synthetic-source-model", row.label, "local replies never own the source row")
            assertTrue(row.compact, "a local query must not change the source's compact flag")
            assertEquals(1, deps.gate.snapshot().inflight)
        } finally {
            source.release()
        }
    }

    private suspend fun availableAtReply(deps: HeadDeps, heap: RequestMaterializationGate): Pair<Boolean, Boolean> {
        val loanFree = heap.tryWithLease(1) { true } == true
        val candidate = deps.gate.resumeSource("synthetic-session")
        val candidateFree = candidate != null
        candidate?.release()
        return loanFree to candidateFree
    }

    private fun continuationProvider(): Provider {
        val delegate = testProvider
        return object : Provider by delegate {
            override fun buildTurn(body: AnthropicTurnBody, compact: Boolean, sessionId: String?): BuiltTurn =
                delegate.buildTurn(body, compact, sessionId).copy(
                    roundInterceptor = object : RoundInterceptor {
                        override fun resumesSource(): Boolean = true
                        override suspend fun intercept(
                            bodyJson: String,
                            sink: WireSink,
                            postRound: InterceptedRoundPost,
                        ): RoundResult = error("a refused request must not drive its source")
                    },
                )
        }
    }

    /** Borrow the source permit while leaving its independent reader owned by the test. */
    private suspend fun retainedCandidate(
        gate: InflightGate,
        window: AdmissionWindow,
        stopping: Boolean,
    ): Pair<AdmittedTurn, InflightGate.Slot.Lease> {
        val initial = (gate.acquire() as InflightGate.Admission.Acquired).slot
        val source = initial.retainSource("synthetic-session")
        initial.release()
        val candidate = checkNotNull(gate.resumeSource("synthetic-session"))
        if (stopping) {
            source.release()
            window.close()
        } else {
            window.open()
        }
        return AdmittedTurn(candidate, 0L, TurnPerf()) to source
    }
}

private fun ready(): Preparation.Ready = Preparation.Ready(
    built = BuiltTurn(
        JsonObject(emptyMap()),
        TurnMeta(false, ReasoningDisplay.OFF, false, "synthetic-model", "synthetic-model", 100, "high", null, null),
    ),
    stream = false,
    inbound = null,
    messagesHash = null,
    hasPriorExchange = false,
)

/** The head under test, wired the way HeadServer wires it: file-scoped so the cap-refusal class
 *  below uses the same assembly as AdmissionGateTest and neither owns a second one. */
private fun handler(
    provider: Provider,
    deps: HeadDeps,
    reader: RequestBodyReader = RequestBodyReader(1_000),
): HeadAdmission {
    val responses = AdmissionResponses()
    val clientAuth = ClientAuth(deps, responses, ForeignHostLog("synthetic head", deps.log))
    val window = AdmissionWindow().apply { open() }
    return HeadAdmission(
        deps,
        AdmissionGate(provider, deps, window, responses),
        AdmissionTelemetry(deps.gate, deps.seams.clock),
        TurnPreparation(provider, deps, reader, AnthropicBodyParse(), clientAuth),
        responses,
        TurnDriver(provider, deps, CompactionReplay()),
    )
}

private val testProvider: TestResponsesProvider = TestResponsesProvider(
    tuning = ProviderTuning(
        key = "codex",
        label = "claudex",
        catalog = ModelCatalog(
            discoveryPrefix = "claude-codex--",
            models = listOf(ModelEntry("gpt-5.6-sol", "Sol", contextWindow = 272_000)),
            defaultContextWindow = 272_000,
        ),
        pinnedModel = "gpt-5.6-sol",
        auth = AdmissionTestAuth(),
        baseUrl = "http://127.0.0.1",
        watchdog = WatchdogBudget(5.seconds, 3.seconds, 30.seconds),
    ),
    reasoning = ReasoningSettings(ReasoningDisplay.TEXT, false, "high", "detailed"),
)

class AdmissionTimingTest {
    @Test
    fun `fresh admission queue adds to the initial wait without charging preparation`(
        @TempDir tmp: Path,
    ) = testApplication {
        var now = 10L
        val deps = headDeps(tmp)
        val window = AdmissionWindow().apply { open() }
        val admission = AdmissionGate(testProvider, deps, window, AdmissionResponses())
        val telemetry = AdmissionTelemetry(deps.gate) { now }
        val perf = telemetry.begin(0L)
        application {
            routing {
                post("/probe") {
                    val initial = (deps.gate.acquire() as InflightGate.Admission.Acquired).slot
                    val source = initial.retainSource("synthetic-session")
                    initial.release()
                    val candidate = checkNotNull(deps.gate.resumeSource("synthetic-session"))
                    telemetry.markAdmitted(perf, 3L)
                    val admitted = AdmittedTurn(candidate, 3L, perf)
                    try {
                        val prepared = telemetry.prepare(perf, now) {
                            now = 100L
                            ready()
                        }
                        coroutineScope {
                            val settling = async(start = CoroutineStart.UNDISPATCHED) {
                                admitted.settle(call, prepared, admission)
                            }
                            assertEquals(1, deps.gate.snapshot().queued, "a fresh permit must really queue")
                            now = 400L
                            source.release()
                            assertTrue(settling.await())
                        }
                        call.respondText("synthetic admitted reply")
                    } finally {
                        admitted.close()
                        source.release()
                    }
                }
            }
        }
        assertEquals(HttpStatusCode.OK, client.post("/probe").status)
        assertEquals(307L, perf.snapshot().counters[PerfKeys.ADMIT_WAIT_MS])
        assertEquals(90L, perf.snapshot().counters[PerfKeys.PREP_MS])
        assertEquals(0L, perf.snapshot().marks[PerfKeys.GATE], "the legacy gate origin stays fixed")
        perf.firstClientByte()
        assertEquals(400L, perf.snapshot().counters[PerfKeys.ARRIVAL_TO_FIRST_CLIENT_BYTE_MS])
        assertEquals(0, deps.gate.snapshot().inflight)
    }
}

/**
 * V4-440 turned the request-body cap from a thrown RequestBodyTooLarge into a [Materialized] case.
 * The one thing that must not move is what the client sees, and the cap has two arms that reach the
 * wire from different places: the declared Content-Length, refused before the reader is called, and
 * the running total, refused a frame into the read. This holds them to ONE refusal.
 */
class RequestBodyCapRefusalTest {
    @Test
    fun `both cap arms answer the client one 413 with the same bytes`(@TempDir tmp: Path) = testApplication {
        val deps = headDeps(tmp).copy(policy = HeadDeps.HeadPolicy(maxRequestBytes = 4))
        var next = 0
        val reader = RequestBodyReader(
            1_000,
            RequestBodyRead { _, _ ->
                // Five bytes into a cap of four on the second post; the third never answers at all.
                if (next == 1) 5 else withTimeout(1) { CompletableDeferred<Nothing>().await() }
            },
        )
        val handler = handler(testProvider, deps, reader)
        application { routing { post("/probe") { handler.handleMessages(call) } } }

        // "12345" declares 5 against the cap of 4, so it is refused before the reader is called.
        // "x" declares 1 and is refused by the running total the delegate reports mid-read.
        val replies = listOf("12345", "x", "x").map { body ->
            val response = client.post("/probe") {
                header(HttpHeaders.Authorization, "Bearer test-inference-token")
                setBody(body)
            }
            next++
            response.status.value to response.bodyAsText()
        }

        val (declaredStatus, declaredBody) = replies[0]
        val (runningStatus, runningBody) = replies[1]
        val (timeoutStatus, timeoutBody) = replies[2]
        assertEquals(413, declaredStatus, declaredBody)
        assertEquals(413, runningStatus, runningBody)
        assertEquals(declaredBody, runningBody, "the two cap arms must be indistinguishable to the client")
        assertTrue(declaredBody.contains("exceeds 4 bytes"), declaredBody)
        // The control: a DIFFERENT refusal does differ, so the equality above is a check that can
        // fail and not a tautology that would hold however the two arms were spelled.
        assertEquals(408, timeoutStatus, timeoutBody)
        assertNotEquals(declaredBody, timeoutBody, "a different refusal must differ, or the check above cannot fail")
    }
}
