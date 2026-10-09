package splice.provider.codex

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import splice.core.index.WireBlockIndex
import splice.core.turn.GatewayCustomCall
import splice.core.turn.RoundHandoffs
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.core.turn.UsageOrigin
import splice.provider.codex.state.CodeModeExpiredHistory
import splice.provider.codex.state.CodeModeKeyLocks
import splice.provider.codex.state.CodeModeRegistryAccess
import splice.provider.codex.state.CodeModeStateJournal
import splice.provider.codex.stream.CodeModeClientContexts
import splice.provider.codex.stream.CodeModeLiveRound
import splice.provider.codex.stream.CodeModeSourceCapture
import splice.provider.codex.stream.CodeModeSourceRecords
import splice.provider.codex.stream.CodeModeSourceState
import splice.provider.codex.stream.CodeModeStreamAdmission
import splice.upstream.RedirectableRoundPost
import splice.upstream.RoundResult
import splice.upstream.codemode.CodeModeSourcePart
import splice.upstream.sse.CustomToolSource
import splice.upstream.sse.WireSink
import java.io.IOException
import java.lang.ref.Reference
import java.lang.ref.WeakReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.time.Duration.Companion.hours

class CodeModeStreamDurabilityTest : CodeModeStatementStreamSupport() {
    @Test
    @Timeout(20)
    fun `an admitted retained round releases the raw body captured by admission`() {
        val (round, body) = admittedRound()
        try {
            assertTrue(round.ready.isCompleted, "the source is admitted but its round stays retained")
            assertTrue(collected(body), "retained round still reaches its one-use admission request body")
            assertTrue(round.ready.isCompleted, "collection must not require releasing the retained round")
        } finally {
            Reference.reachabilityFence(round)
        }
    }

    private fun admittedRound(): Pair<CodeModeLiveRound, WeakReference<String>> {
        val body = "synthetic request body ".repeat(400_000)
        val weak = WeakReference(body)
        val config = CodeModeBridgeConfig({ error("runtime is not used by admission") }, stateLocation())
        val registry = CodexCodeModeRegistry(config, Json, 1.hours)
        val record = CodeModeRecords.of("admission-heap", 0).also { it.sourceState = CodeModeSourceState() }
        val round = CodeModeLiveRound(
            config,
            registry,
            CodexCodeModeWire(Json, {}),
            CodeModeStreamAdmission {
                check(body.length == 9_200_000)
                record
            },
            StepSink(),
        )
        runBlocking { round.switching.customToolSource(CustomToolSource.Started(outer(source = ""))) }
        return round to weak
    }

    private fun collected(body: WeakReference<String>): Boolean {
        repeat(20) {
            System.gc()
            if (body.get() == null) return true
        }
        return false
    }

    @Test
    @Timeout(20)
    fun `the bridge recovers completed raw usage after its durable claim fails without rerunning source`() =
        runBlocking {
            val runtime = IncrementalRuntime()
            val manager = bridge(runtime)
            val sink = StepSink()
            val post = GatedPost(sink)
            try {
                manager.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, sink, post)
                val first = sink.callback.await()
                post.gates.drop(1).forEach { it.complete(Unit) }
                post.complete.complete(Unit)
                withTimeout(1_500) { post.stopped.await() }
                val field = CodexCodeModeBridge::class.java.getDeclaredField("registry").apply { isAccessible = true }
                val registry = field.get(manager) as CodexCodeModeRegistry
                val key = stateFiles.records().single().getValue("key").jsonPrimitive.content
                val record = registry.recordsFor(key).single()
                // The mock post's finally precedes capture.finish; only stored terminal usage certifies this precondition.
                withTimeout(1_500) {
                    while (registry.recordsFor(key).single().sourceState?.usage == null) yield()
                }
                registry.complete(record, "done")
                stateFiles.block()
                try {
                    assertThrows(CodeModePersistenceException::class.java) { registry.source.consume(record) }
                    assertFalse(record.sourceState?.consumed == true)
                } finally {
                    stateFiles.unblock()
                }
                val recovered = manager.interceptor(turn(first.id, "result-0"), disableParallel = false)
                    .intercept(history(listOf(first)), StepSink(), post).turn() as TurnOutcome.Success
                assertBilling(recovered.usage)
                assertTrue(record.sourceState?.consumed == true)
                assertFinalIdentity(post, recovered)
                val repeated = manager.interceptor(turn(first.id, "result-0"), disableParallel = false)
                    .intercept(history(listOf(first)), StepSink(), post).turn() as TurnOutcome.Success
                assertEquals(5L, repeated.usage.outputTokens, "terminal usage is claimed once")
                assertEquals(1, runtime.starts)
                assertEquals(3, post.posts, "only the initial source and two ordinary continuations were posted")
            } finally {
                manager.onHeadStop()
            }
        }

    @Test
    @Timeout(20)
    fun `a failed durable usage claim returns a zero usage step after its raw round reported tokens`() = runBlocking {
        val runtime = IncrementalRuntime()
        val manager = bridge(runtime)
        val calls = StepSink()
        val rawUsage = CompletableDeferred<Usage>()
        var blocked = false
        val sink = object : WireSink by calls {
            override suspend fun closeBlock(index: WireBlockIndex) {
                calls.closeBlock(index)
                if (calls.sealed && !blocked) {
                    stateFiles.block()
                    blocked = true
                }
            }
        }
        val upstream = GatedPost(calls).apply { complete.complete(Unit) }
        val observed = object : RedirectableRoundPost by upstream {
            override suspend fun into(bodyJson: String, sink: WireSink): RoundResult {
                val raw = upstream.into(bodyJson, sink)
                rawUsage.complete(((raw as RoundResult.Outcome).outcome as TurnOutcome.Success).usage)
                return raw
            }
        }
        try {
            val pending = async {
                manager.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, sink, observed)
            }
            withTimeout(1_500) { runtime.firstRead.await() }
            assertTrue(runtime.firstReads.single() is CodeModeSourcePart.Delta)
            upstream.gates.drop(1).forEach { it.complete(Unit) }
            val outcome = pending.await().turn() as TurnOutcome.Success
            assertTrue(calls.callback.isCompleted, "the script's client call already left")
            assertTrue(blocked, "refusal starts only after durable client-call issuance")
            assertEquals(100L, rawUsage.await().inputTokens, "the posting round's independent witness survives")
            assertEquals(7L, rawUsage.await().outputTokens)
            assertEquals(0L, outcome.usage.inputTokens, "a refused durable claim returns the local machine step")
            assertEquals(0L, outcome.usage.outputTokens)
            assertEquals(1, upstream.posts)
            assertEquals(1, runtime.starts, "the source is never rerun to recover its bill")
        } finally {
            if (blocked) stateFiles.unblock()
            manager.onHeadStop()
        }
    }

    private inner class SourceRound(
        key: String = "source-cost",
        location: CodeModeStateLocation = stateLocation(),
    ) {
        var forcedWrites = 0
        var refuseWrites = false
        val record = CodeModeRecords.of(key, 0).also {
            it.origin.source = ""
            it.sourceState = CodeModeSourceState()
        }
        private val config = CodeModeBridgeConfig({ error("no worker is needed") }, location)
        private val registry = CodexCodeModeRegistry(
            config,
            Json,
            1.hours,
            writer = CodeModeStateWrite { path, text ->
                if (refuseWrites) throw IOException("synthetic refused source write")
                CodeModeStateJournal.write(path, text)
                forcedWrites++
            },
        )
        val capture = CodeModeSourceCapture(
            config,
            registry,
            CodexCodeModeWire(Json, {}),
            CodeModeStreamAdmission {
                check(registry.add(record))
                record
            },
        )
        private val call = GatewayCustomCall(record.origin.outerCallId, CODE_MODE_TOOL_NAME, "", record.origin.outer)

        suspend fun begin() {
            capture.observe(CustomToolSource.Started(call))
        }

        suspend fun append(text: String) {
            capture.observe(CustomToolSource.Delta(record.origin.outerCallId, text))
        }

        fun finish(text: String) {
            capture.finish(TurnOutcome.Success(
                false,
                false,
                Usage(),
                handoffs = RoundHandoffs(customCalls = listOf(call.copy(input = text))),
            ))
        }

        fun clientBoundary() {
            registry.changes.save(record) {}
        }
    }

    @Test
    fun `two hundred buffered producer deltas remain staged until a client boundary`() = runBlocking {
        val state = SourceRound()
        state.begin()
        repeat(200) { state.append("x") }
        val reader = state.capture.source.view()
        assertEquals(CodeModeSourcePart.Delta("x".repeat(200)), reader.read())
        assertEquals("", stateFiles.records().single().getValue("source").jsonPrimitive.content)
        state.finish("x".repeat(200))
        assertEquals(CodeModeSourcePart.Complete(), reader.read())
        assertEquals(CodeModeSourcePart.Complete("x".repeat(200)), state.capture.source.view().read())
        assertEquals(1, state.forcedWrites, "only the no-rerun admission is durable before visibility")
        state.clientBoundary()
        assertEquals("x".repeat(200), stateFiles.records().single().getValue("source").jsonPrimitive.content)
        assertEquals(2, state.forcedWrites, "one admission and one complete conversation batch")
    }

    @Test
    fun `source read granularity does not change the client-boundary batch count`() = runBlocking {
        val statement = listOf("await ", "tools.", "Read(", "{}", ");", "\n")
        val batched = SourceRound("statement-schedule")
        batched.begin()
        val reader = batched.capture.source.view()
        repeat(20) {
            statement.forEach { fragment -> batched.append(fragment) }
            assertEquals(CodeModeSourcePart.Delta(statement.joinToString("")), reader.read())
            batched.clientBoundary()
        }
        batched.finish(statement.joinToString("").repeat(20))
        assertEquals(CodeModeSourcePart.Complete(), reader.read())
        assertEquals(21, batched.forcedWrites, "admission and twenty client-visible batches")

        val location = stateLocation()
        val eagerLocation = CodeModeStateLocation(
            location.dir.resolveSibling("eager-schedule"),
            location.legacyFile.resolveSibling("eager-schedule.json"),
        )
        val eager = SourceRound("eager-schedule", eagerLocation)
        eager.begin()
        val eagerReader = eager.capture.source.view()
        repeat(200) { index ->
            eager.append("x")
            assertEquals(CodeModeSourcePart.Delta("x"), eagerReader.read())
            if ((index + 1) % 10 == 0) eager.clientBoundary()
        }
        eager.finish("x".repeat(200))
        assertEquals(CodeModeSourcePart.Complete(), eagerReader.read())
        assertEquals(21, eager.forcedWrites, "extra source reads never add durability boundaries")
    }

    @Test
    fun `a refused client boundary keeps staged source out of durable client state`() = runBlocking {
        val state = SourceRound()
        state.begin()
        state.refuseWrites = true
        state.append("await tools.Read({});")
        val reader = state.capture.source.view()
        assertEquals(CodeModeSourcePart.Delta("await tools.Read({});"), reader.read())
        assertThrows(CodeModePersistenceException::class.java) { state.clientBoundary() }
        assertEquals("", stateFiles.records().single().getValue("source").jsonPrimitive.content)
        state.refuseWrites = false
        state.clientBoundary()
        assertEquals("await tools.Read({});", stateFiles.records().single().getValue("source").jsonPrimitive.content)
    }

    private inner class SourceState {
        val record = CodeModeRecords.of("source-test", 0).also {
            it.phase = CodeModePhase.STARTING
            it.sourceState = CodeModeSourceState()
        }
        private val records = listOf(record)
        private val history = CodeModeExpiredHistory(mutableListOf(), null)
        private val store = CodexCodeModeStore(stateLocation(), Json, {})
        val contexts = CodeModeClientContexts { null }
        val source = CodeModeSourceRecords(
            CodeModeRegistryAccess(ReentrantLock(), CodeModeKeyLocks()),
            records,
            history,
            store,
            contexts,
        )
        val terminal = GatewayCustomCall(
            record.origin.outerCallId,
            CODE_MODE_TOOL_NAME,
            record.origin.source + ";suffix",
            JsonObject(mapOf("id" to JsonPrimitive("completed-item"))),
        )
        val continuity = CodeModeContinuity(
            listOf(JsonPrimitive("logical-terminal")),
            emptyList(),
        )

        init {
            store.save(records, history.entries)
        }
    }

    @Test
    fun `a source round that finishes after its client turn is its conversation's newest context`() {
        val state = SourceState()
        state.source.finish(state.record, state.terminal, state.continuity, Usage(1_400, 12, 1_100))
        val step = TurnOutcome.Success(true, false, Usage(origin = UsageOrigin(localStep = true)))
        val reported = state.contexts.report(state.record.key, step) as TurnOutcome.Success
        val observed = splice.core.turn.UsageField.entries.toSet() - splice.core.turn.UsageField.OUTPUT
        val expected = Usage(inputTokens = 1_400, cachedTokens = 1_100, reported = observed)
        assertEquals(expected, reported.usage.origin.clientContext)
    }

    @Test
    fun `terminal source remains unclaimed when its durable billing boundary fails`() {
        val state = SourceState()
        stateFiles.block()
        state.source.finish(state.record, state.terminal, state.continuity, Usage(outputTokens = 7))
        assertTrue(state.record.sourceState?.complete == true)
        assertThrows(CodeModePersistenceException::class.java) { state.source.consume(state.record) }
        assertFalse(state.record.sourceState?.consumed == true)
        stateFiles.unblock()
        assertFalse(stateFiles.records().single()["sourceState"].toString().contains("\"complete\":true"))
        assertEquals(7L, state.source.consume(state.record)?.outputTokens)
        assertTrue(stateFiles.records().single()["sourceState"].toString().contains("\"consumed\":true"))
    }

    @Test
    fun `source persistence preserves absent buckets through finish consume and restart`() {
        val state = SourceState()
        val usage = Usage(outputTokens = 7, reported = setOf(splice.core.turn.UsageField.OUTPUT))
        state.source.finish(state.record, state.terminal, state.continuity, usage)
        assertEquals(usage.reported, state.record.sourceState?.usage?.value()?.reported)
        assertEquals(usage.reported, state.source.consume(state.record)?.reported)
        val stored = stateFiles.records().single().getValue("sourceState")
        val restored = Json.decodeFromJsonElement(CodeModeSourceState.serializer(), stored)
        assertEquals(usage.reported, restored.usage?.value()?.reported)
    }

    @Test
    fun `a failed usage claim can retry once and restored usage retains its raw billing receipt`() {
        val state = SourceState()
        state.source.finish(
            state.record,
            state.terminal,
            state.continuity,
            Usage(
                inputTokens = 100,
                outputTokens = 7,
                reasoningTokens = 3,
                origin = UsageOrigin(recordedOutputTokens = 7),
            ),
        )
        stateFiles.block()
        assertThrows(CodeModePersistenceException::class.java) { state.source.consume(state.record) }
        assertFalse(state.record.sourceState?.consumed == true)
        stateFiles.unblock()
        val usage = checkNotNull(state.source.consume(state.record))
        assertEquals(7L, usage.outputTokens)
        assertEquals(7L, usage.origin.recordedOutputTokens)
        assertEquals(0L, usage.unrecordedOutputTokens)
        assertNull(state.source.consume(state.record))
        val restored = state.record.snapshot().restore()
        assertEquals(state.record.sourceState, restored.sourceState)
        assertEquals(usage, restored.sourceState?.usage?.value())
    }
}
