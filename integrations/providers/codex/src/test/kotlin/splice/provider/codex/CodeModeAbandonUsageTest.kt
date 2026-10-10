// NEW: disposing a posted source frees its reader and counts its cut once on the disposing client step.
package splice.provider.codex

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import splice.core.perf.PerfKeys
import splice.core.perf.TurnPerf
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.core.util.ElapsedClock
import splice.core.util.WallClock
import splice.provider.codex.stream.CodeModeStreams
import splice.upstream.PostingTurnRow
import splice.upstream.RedirectableRoundPost
import splice.upstream.RoundResult
import splice.upstream.RowRelease
import splice.upstream.transport.UpstreamEnding
import splice.upstream.transport.UpstreamFailed
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private enum class SourceDisposition { NATIVE, STEERING, SUPERSEDED }

internal class CodeModeAbandonUsageTest : CodeModeStatementStreamSupport() {
    @Test
    @Timeout(20)
    fun `native abandonment cancels its reader and counts the cut on the abandoning step`() = runBlocking {
        disposeAfterSource(SourceDisposition.NATIVE, reportedBeforeDispose = false)
    }

    @Test
    @Timeout(20)
    fun `native abandonment preserves already reported usage without inventing a cut`() = runBlocking {
        disposeAfterSource(SourceDisposition.NATIVE, reportedBeforeDispose = true)
    }

    @Test
    @Timeout(20)
    fun `real steering cancels its reader and counts the cut on the steering step`() = runBlocking {
        disposeAfterSource(SourceDisposition.STEERING, reportedBeforeDispose = false)
    }

    @Test
    @Timeout(20)
    fun `supersession cancels its reader and counts the cut on the superseding step`() = runBlocking {
        disposeAfterSource(SourceDisposition.SUPERSEDED, reportedBeforeDispose = false)
    }

    @Test
    @Timeout(20)
    fun `an upstream refusal after abandonment still counts the cut on the failing client step`() = runBlocking {
        cutWithFailedContinuation(UpstreamFailed("synthetic refusal", status = 400))
    }

    @Test
    @Timeout(20)
    fun `cancellation after abandonment still counts the cut on the cancelled client step`() = runBlocking {
        cutWithFailedContinuation(null)
    }

    /** The continuation's post is refused with [refusal], or cancelled when it is null. */
    private suspend fun cutWithFailedContinuation(refusal: UpstreamEnding?) {
        val runtime = IncrementalRuntime()
        val manager = bridge(runtime)
        val sink = StepSink()
        val source = GatedPost(sink)
        val row = PostingRow(source)
        val user = Json.parseToJsonElement("""{"role":"user","content":"synthetic request"}""")
        val input = Json.parseToJsonElement(BASE_REQUEST).jsonObject.getValue("input").jsonArray + user
        val perf = TurnPerf(clock = ElapsedClock { 0 }, wallClock = WallClock { 0 })
        val refusing = object : RedirectableRoundPost by source {
            override val perf = perf
            override suspend fun into(bodyJson: String, sink: splice.upstream.sse.WireSink): RoundResult =
                refusal?.let { RoundResult.Ended(it) } ?: throw CancellationException("synthetic client cancellation")
        }
        try {
            manager.interceptor(turn(), disableParallel = false)
                .intercept(JsonObject(mapOf("input" to JsonArray(input))).toString(), sink, row.original)
            val callback = withTimeout(1_500) { sink.callback.await() }
            val callbacks = Json.parseToJsonElement(history(listOf(callback))).jsonObject
                .getValue("input").jsonArray.drop(1)
            val items = changedHistory(input, callbacks, SourceDisposition.NATIVE)
            val changed = JsonObject(mapOf("input" to JsonArray(items)))
            val continuation = suspend {
                manager.interceptor(turn(callback.id, "result-0"), disableParallel = false)
                    .intercept(changed.toString(), RecordingSink(), refusing)
            }
            if (refusal == null) {
                assertThrows(CancellationException::class.java) { val _ = runBlocking { continuation() } }
            } else {
                val ended = continuation() as RoundResult.Ended
                assertTrue(ended.ending === refusal, "the upstream refusal propagates unchanged")
            }
            withTimeout(1_500) { source.stopped.await() }
            row.assertOriginal(reported = false)
            val cuts = perf.snapshot().counters[PerfKeys.CUT_SOURCE_ROUNDS]
            assertEquals(1L, cuts, "the failed cutting turn owns the cut")
            assertFalse(source.sent[1].isCompleted)
        } finally {
            manager.onHeadStop()
        }
    }

    private class PostingRow(generated: RedirectableRoundPost) {
        val released = CompletableDeferred<Usage?>()
        private val releases = AtomicInteger()
        val original = object : RedirectableRoundPost by generated {
            override val postingRow = PostingTurnRow {
                RowRelease {
                    releases.incrementAndGet()
                    released.complete(it)
                }
            }
        }

        suspend fun assertOriginal(reported: Boolean) {
            val usage = withTimeout(1_500) { released.await() }
            if (reported) {
                assertEquals(7L, checkNotNull(usage).outputTokens)
                assertEquals(0, usage.cutRounds)
            } else {
                assertNull(usage, "the posting step neither reported tokens nor cut its source")
            }
            assertEquals(1, releases.get(), "the original row releases exactly once")
        }
    }

    private suspend fun disposeAfterSource(disposition: SourceDisposition, reportedBeforeDispose: Boolean) {
        val runtime = IncrementalRuntime()
        val manager = bridge(runtime)
        val sink = StepSink()
        val source = GatedPost(sink)
        val row = PostingRow(source)
        val user = Json.parseToJsonElement("""{"role":"user","content":"synthetic request"}""")
        val input = Json.parseToJsonElement(BASE_REQUEST).jsonObject.getValue("input").jsonArray + user
        try {
            manager.interceptor(turn(), disableParallel = false)
                .intercept(JsonObject(mapOf("input" to JsonArray(input))).toString(), sink, row.original)
            val callback = withTimeout(1_500) { sink.callback.await() }
            if (reportedBeforeDispose) {
                finishSource(source)
                withTimeout(1_500) { row.released.await() }
            }
            val callbackItems = Json.parseToJsonElement(history(listOf(callback))).jsonObject
                .getValue("input").jsonArray.drop(1)
            val changed = JsonObject(mapOf("input" to JsonArray(changedHistory(input, callbackItems, disposition))))
            val answering = if (disposition == SourceDisposition.SUPERSEDED) turn() else turn(callback.id, "result-0")
            val next = withTimeout(1_500) {
                manager.interceptor(answering, disableParallel = false)
                    .intercept(changed.toString(), RecordingSink(), source)
            }.turn() as TurnOutcome.Success
            withTimeout(1_500) { source.stopped.await() }
            assertDisposition(disposition)
            row.assertOriginal(reportedBeforeDispose)
            assertCut(next, if (reportedBeforeDispose) 0 else 1)
            if (!reportedBeforeDispose) assertFalse(source.sent[1].isCompleted, "no unread source may be generated")
            assertEquals(1, runtime.starts)
            assertEquals(1, runtime.delivered.size, "no remaining statement may execute")
            assertEquals(2, source.posts)
            val retry = manager.interceptor(answering, disableParallel = false)
                .intercept(changed.toString(), RecordingSink(), source).turn() as TurnOutcome.Success
            assertCut(retry, 0)
        } finally {
            manager.onHeadStop()
        }
    }

    private fun assertCut(outcome: TurnOutcome.Success, cuts: Long) {
        assertEquals(cuts, outcome.usage.cutRounds)
        assertEquals(150L, outcome.usage.inputTokens)
        assertEquals(5L, outcome.usage.outputTokens, "the disposing step bills only its own response")
        assertEquals(0L, outcome.usage.absorbed.outputTokens, "unreported source tokens are never invented")
    }

    private fun assertDisposition(disposition: SourceDisposition) {
        val expected = when (disposition) {
            SourceDisposition.NATIVE -> "[code-mode] abandoned record"
            SourceDisposition.STEERING -> "interrupted extra=STEERING"
            SourceDisposition.SUPERSEDED -> "parked program was superseded"
        }
        assertTrue(
            logLines.any { expected in it && (disposition != SourceDisposition.NATIVE || "native" in it) },
            "the intended disposition must be exercised",
        )
    }

    private fun changedHistory(
        input: List<JsonElement>,
        callbacks: List<JsonElement>,
        disposition: SourceDisposition,
    ): List<JsonElement> {
        val newUser = Json.parseToJsonElement("""{"role":"user","content":"new synthetic direction"}""")
        return when (disposition) {
            SourceDisposition.NATIVE -> listOf(
                input.first(),
                Json.parseToJsonElement(
                    """{"type":"reasoning","id":"synthetic-unexpected","encrypted_content":"synthetic"}""",
                ),
                input.last(),
            ) + callbacks
            SourceDisposition.STEERING -> input + callbacks + newUser
            SourceDisposition.SUPERSEDED -> input + newUser
        }
    }

    private suspend fun finishSource(post: GatedPost) {
        post.gates[1].complete(Unit)
        post.gates[2].complete(Unit)
        post.complete.complete(Unit)
        withTimeout(1_500) { post.stopped.await() }
    }
}

/** An autonomous retirement between client steps must not drop an already posted source's bill. */
internal class CodeModeAutonomousCutBillingTest : CodeModeStatementStreamSupport() {
    @Test
    @Timeout(20)
    fun `capacity retirement bills its held row before later native abandonment`() = runBlocking {
        retireSource("capacity")
    }

    @Test
    @Timeout(20)
    fun `dead session retirement bills its held row before later native abandonment`() = runBlocking {
        retireSource("dead")
    }

    @Test
    @Timeout(20)
    fun `unknown idle session retirement bills its held row before later native abandonment`() = runBlocking {
        retireSource("idle")
    }

    @Test
    @Timeout(20)
    fun `record expiry bills its held row even after the record and lease leave the registry`() = runBlocking {
        retireSource("expired")
    }

    @Test
    @Timeout(20)
    fun `an earlier client cut stays client owned when its source lease retires`() = runBlocking {
        retireSource("client")
    }

    @Test
    @Timeout(20)
    fun `declaring client ownership without cutting an active reader settles its held row once`() = runBlocking {
        val manager = bridge(IncrementalRuntime())
        val sink = StepSink()
        val source = GatedPost(sink)
        val held = HeldPosting(source)
        try {
            manager.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, sink, held)
            withTimeout(1_500) { sink.callback.await() }
            val registry = field(manager, "registry") as CodexCodeModeRegistry
            val key = stateFiles.records().single().getValue("key").jsonPrimitive.content
            val record = registry.recordsFor(key).single()
            val round = (field(manager, "driver") as CodexCodeModeDriver).streams.find(record)
            val lease = checkNotNull(record.sourceEnd)
            lease.claimClient()
            assertFalse(checkNotNull(round).cut.take(), "declaring ownership does not cut a reader")
            val reader = field(round, "finished") as kotlinx.coroutines.Deferred<*>
            reader.cancel(CancellationException("synthetic source ended without a client cutting its reader"))
            withTimeout(1_500) { reader.join() }
            withTimeout(1_500) { source.stopped.await() }
            assertRetirementBill(withTimeout(1_500) { held.released.await() }, clientCuts = 0L, client = false)
            lease.ended()
            assertFalse(round.cut.take(), "a retired ended reader was never cut by this declaration")
            assertEquals(1, held.releases.get(), "an ended source releases its held posting row exactly once")
        } finally {
            manager.onHeadStop()
        }
    }

    private class HeldPosting(source: RedirectableRoundPost) : RedirectableRoundPost by source {
        val released = CompletableDeferred<Usage?>()
        val releases = AtomicInteger()
        override val postingRow = PostingTurnRow {
            RowRelease {
                releases.incrementAndGet()
                released.complete(it)
            }
        }
    }

    private suspend fun retireSource(mode: String) {
        val clock = MutableClock(1_000)
        val manager = bridge(IncrementalRuntime(), ttl = if (mode == "expired") 1.seconds else 24.hours, clock = clock)
        val sink = StepSink()
        val source = GatedPost(sink)
        val held = HeldPosting(source)
        val user = Json.parseToJsonElement("""{"role":"user","content":"synthetic request"}""")
        val input = Json.parseToJsonElement(BASE_REQUEST).jsonObject.getValue("input").jsonArray + user
        try {
            val first = manager.interceptor(turn(), disableParallel = false)
                .intercept(JsonObject(mapOf("input" to JsonArray(input))).toString(), sink, held).turn()
                as TurnOutcome.Success
            val callback = withTimeout(1_500) { sink.callback.await() }
            assertEquals(0L, first.usage.inputTokens)
            assertEquals(0L, first.usage.outputTokens)
            assertEquals(0L, first.usage.cutRounds)
            val clientCuts = retire(manager, mode, clock)
            withTimeout(1_500) { source.stopped.await() }
            val settled = withTimeout(1_500) { held.released.await() }
            assertRetirementBill(settled, clientCuts, mode == "client")
            assertEquals(1, held.releases.get())
            assertFalse(source.sent[1].isCompleted, "retirement cannot generate more source")
            val callbackItems = Json.parseToJsonElement(history(listOf(callback))).jsonObject
                .getValue("input").jsonArray.drop(1)
            val nativeEdit = Json.parseToJsonElement(
                """{"type":"reasoning","id":"synthetic-unexpected","encrypted_content":"synthetic"}""",
            )
            val changed = JsonObject(
                mapOf(
                    "input" to JsonArray(listOf(input.first(), nativeEdit, input.last()) + callbackItems),
                ),
            )
            if (mode == "expired") {
                assertTrue(stateFiles.records().isEmpty(), "the expired source record cannot be recreated")
            } else {
                assertContinuation(manager, source, callback, changed)
                assertTrue(logLines.any { "[code-mode] abandoned record" in it && "native" in it })
            }
        } finally {
            manager.onHeadStop()
        }
    }

    private fun retire(manager: CodexCodeModeBridge, mode: String, clock: MutableClock): Long = when (mode) {
        "capacity" -> {
            reapIdleCell(manager)
            0L
        }
        "dead", "idle" -> {
            if (mode == "dead") deadSessions += "session-a" else clock.now += 31.minutes.inWholeMilliseconds
            val timed = (field(manager, "registry") as CodexCodeModeRegistry).timed
            timed.javaClass.getDeclaredMethod("sweep").apply { isAccessible = true }.invoke(timed)
            0L
        }
        "expired" -> {
            clock.now += 2_000
            sweepOwnHistory(manager)
            0L
        }
        "client" -> {
            val driver = field(manager, "driver") as CodexCodeModeDriver
            val registry = field(manager, "registry") as CodexCodeModeRegistry
            val streams: CodeModeStreams = driver.streams
            val key = stateFiles.records().single().getValue("key").jsonPrimitive.content
            val record = registry.recordsFor(key).single()
            val watched = streams.watchCuts(key)
            checkNotNull(streams.find(record)).cancel()
            checkNotNull(record.sourceEnd).ended()
            val cutting = streams.billCuts(watched, emptyList(), completedOutcome()) as TurnOutcome.Success
            assertEquals(0L, streams.takeCuts(watched, emptyList()), "the client cut is claimed once")
            cutting.usage.cutRounds
        }
        else -> error("unknown synthetic retirement")
    }

    private fun <O : Any> field(owner: O, name: String): Any =
        owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)

    private fun assertRetirementBill(settled: Usage?, clientCuts: Long, client: Boolean) {
        assertEquals(1L, (settled?.cutRounds ?: 0L) + clientCuts, "every unreported source has one accounting owner")
        if (client) {
            assertNull(settled, "a client's intentional cut does not bill the posting row")
            assertEquals(1L, clientCuts)
        } else {
            assertEquals(1L, checkNotNull(settled).cutRounds)
            assertEquals(0L, settled.inputTokens, "unreported source tokens are not invented")
            assertEquals(0L, settled.outputTokens)
            assertEquals(0L, clientCuts)
        }
    }

    private suspend fun assertContinuation(
        manager: CodexCodeModeBridge,
        source: GatedPost,
        callback: SeenTool,
        changed: JsonObject,
    ) {
        repeat(2) {
            val next = manager.interceptor(turn(callback.id, "result-0"), disableParallel = false)
                .intercept(changed.toString(), RecordingSink(), source).turn() as TurnOutcome.Success
            assertEquals(150L, next.usage.inputTokens, "the continuation bills only its own round")
            assertEquals(5L, next.usage.outputTokens)
            assertEquals(0L, next.usage.absorbed.rounds)
            assertEquals(0L, next.usage.cutRounds, "a retired source is never recounted by a later request")
        }
    }
}

/** Capacity retirement after the last borrower releases cannot outrun the posting row's first registration. */
internal class CodeModeFirstClaimBillingTest : CodeModeStatementStreamSupport() {
    @Test
    @Timeout(20)
    fun `retirement before billFinished settles the first posting row exactly once`() = runBlocking {
        retireBeforeClaim(FirstClaimEnding.RETURN)
    }

    @Test
    @Timeout(20)
    fun `retirement still settles the posting row when no first claim ever returns`() = runBlocking {
        retireBeforeClaim(FirstClaimEnding.CANCEL)
    }

    @Test
    @Timeout(20)
    fun `a successful terminal claimed before settlement releases its early hold without inventing a cut`() =
        runBlocking {
            val manager = bridge(IncrementalRuntime())
            val sink = StepSink()
            val source = GatedPost(sink)
            val posting = FirstPosting(source)
            val staged = CountDownLatch(1)
            val settle = CountDownLatch(1)
            try {
                manager.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, sink, posting)
                val registry = field(manager, "registry") as CodexCodeModeRegistry
                val key = stateFiles.records().single().getValue("key").jsonPrimitive.content
                val record = registry.recordsFor(key).single()
                val round = checkNotNull((field(manager, "driver") as CodexCodeModeDriver).streams.find(record))
                val barrier = Runnable {
                    staged.countDown()
                    check(settle.await(5, TimeUnit.SECONDS)) { "synthetic terminal settlement was not released" }
                }
                round.javaClass.getDeclaredField("beforeSettle").apply { isAccessible = true }.set(round, barrier)
                source.gates[1].complete(Unit)
                source.gates[2].complete(Unit)
                source.complete.complete(Unit)
                assertTrue(
                    staged.await(1_500, TimeUnit.MILLISECONDS),
                    "the real terminal must be staged before its claim",
                )
                round.billing.prepare(completedOutcome())
                val usage = checkNotNull(round.billing.claim(record, completedOutcome()))
                assertEquals(100L, usage.inputTokens)
                assertEquals(7L, usage.outputTokens)
                assertEquals(0L, usage.cutRounds)
                settle.countDown()
                withTimeout(1_500) { round.outcome() }
                assertNull(
                    withTimeout(1_500) { posting.released.await() },
                    "claimed successful usage cannot invent a cut",
                )
                assertEquals(1, posting.held.get(), "an early terminal cannot register the same posting row twice")
                assertEquals(1, posting.releases.get())
            } finally {
                settle.countDown()
                manager.onHeadStop()
            }
        }

    private enum class FirstClaimEnding { RETURN, CANCEL }

    private class FirstPosting(source: RedirectableRoundPost) : RedirectableRoundPost by source {
        val held = AtomicInteger()
        val releases = AtomicInteger()
        val released = CompletableDeferred<Usage?>()
        override val postingRow = PostingTurnRow {
            held.incrementAndGet()
            RowRelease {
                releases.incrementAndGet()
                released.complete(it)
            }
        }
    }

    private suspend fun retireBeforeClaim(ending: FirstClaimEnding) {
        val sink = StepSink()
        val source = GatedPost(sink)
        val posting = FirstPosting(source)
        val armed = AtomicBoolean(true)
        val evicted = CompletableDeferred<Unit>()
        var manager: CodexCodeModeBridge? = null
        val config = CodeModeBridgeConfig(
            runtimes = { IncrementalRuntime() },
            state = stateLocation(),
            cellLease = CodeModeCellLease(
                sessionAlive = CodeModeSessionAlive {
                    manager?.let { owner -> retireIdle(owner, armed, evicted, ending) }
                    null
                },
            ),
        )
        val bridge = CodexCodeModeBridge(config).also { manager = it }
        try {
            if (ending == FirstClaimEnding.CANCEL) {
                assertThrows(CancellationException::class.java) {
                    runBlocking {
                        bridge.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, sink, posting)
                    }
                }
            } else {
                val first = bridge.interceptor(turn(), disableParallel = false)
                    .intercept(BASE_REQUEST, sink, posting).turn() as TurnOutcome.Success
                assertEquals(0L, first.usage.cutRounds, "retirement is not the first client's cut")
            }
            withTimeout(1_500) { evicted.await() }
            withTimeout(1_500) { source.stopped.await() }
            assertEquals(1, posting.held.get(), "retirement cannot outrun the first posting-row registration")
            val bill = withTimeout(1_500) { posting.released.await() }
            assertEquals(1L, checkNotNull(bill).cutRounds)
            assertEquals(0L, bill.inputTokens)
            assertEquals(0L, bill.outputTokens)
            assertEquals(1, posting.releases.get(), "the retired source settles its row exactly once")
            assertFalse(armed.get(), "the actual eviction happened after the last borrower released")
        } finally {
            bridge.onHeadStop()
        }
    }

    private fun retireIdle(
        manager: CodexCodeModeBridge,
        armed: AtomicBoolean,
        evicted: CompletableDeferred<Unit>,
        ending: FirstClaimEnding,
    ) {
        val registry = field(manager, "registry") as CodexCodeModeRegistry
        val record = (field(registry, "records") as List<*>).singleOrNull() as? CodeModeRecord
        if (!idle(record) || !armed.compareAndSet(true, false)) return
        // release() samples liveness only after publishing zero borrowers and idle time.
        // Do not await reader cleanup while this callback still holds the registry key.
        checkNotNull(registry.evictIdleCell())
        evicted.complete(Unit)
        if (ending == FirstClaimEnding.CANCEL) throw CancellationException("synthetic pre-claim end")
    }

    private fun idle(record: CodeModeRecord?): Boolean =
        record?.phase == CodeModePhase.ACTIVE && record.cellBorrowers == 0 && record.cellIdleSince != null

    private fun <O : Any> field(owner: O, name: String): Any =
        owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
}
