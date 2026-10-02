package splice.provider.codex

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import splice.core.turn.GatewayCustomCall
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.provider.codex.state.CodeModeExpiredHistory
import splice.provider.codex.state.CodeModeKeyLocks
import splice.provider.codex.state.CodeModeRegistryAccess
import splice.provider.codex.stream.CodeModeSourceRecords
import splice.provider.codex.stream.CodeModeSourceState
import java.util.concurrent.locks.ReentrantLock

class CodeModeStreamDurabilityTest : CodeModeStatementStreamSupport() {
    @Test
    @Timeout(20)
    fun `the bridge recovers completed raw usage after its durable claim fails without rerunning source`() = runBlocking {
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
            registry.complete(record, "done")
            stateFiles.block()
            try {
                assertThrows(CodeModePersistenceException::class.java) { registry.source.consume(record) }
                assertFalse(record.sourceState?.consumed == true)
            } finally {
                stateFiles.unblock()
            }
            val recovered = manager.interceptor(turn(first.id, "result-0"), disableParallel = false)
                .intercept(history(listOf(first)), StepSink(), post) as TurnOutcome.Success
            assertBilling(recovered.usage)
            assertTrue(record.sourceState?.consumed == true)
            assertFinalIdentity(post, recovered)
            val repeated = manager.interceptor(turn(first.id, "result-0"), disableParallel = false)
                .intercept(history(listOf(first)), StepSink(), post) as TurnOutcome.Success
            assertEquals(5L, repeated.usage.outputTokens, "terminal usage is claimed once")
            assertEquals(1, runtime.starts)
            assertEquals(3, post.posts, "only the initial source and two ordinary continuations were posted")
        } finally {
            manager.onHeadStop()
        }
    }

    private inner class SourceState {
        val record = CodeModeRecords.of("source-test", 0).also {
            it.phase = CodeModePhase.STARTING
            it.sourceState = CodeModeSourceState()
        }
        private val records = listOf(record)
        private val history = CodeModeExpiredHistory(mutableListOf(), null)
        private val store = CodexCodeModeStore(stateLocation(), Json, {})
        val source = CodeModeSourceRecords(
            CodeModeRegistryAccess(ReentrantLock(), CodeModeKeyLocks()),
            records,
            history,
            store,
        )
        val terminal = GatewayCustomCall(
            record.outerCallId,
            CODE_MODE_TOOL_NAME,
            record.source + ";suffix",
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
    fun `a failed terminal save restores every in-memory field and never marks the source complete`() {
        val state = SourceState()
        val before = state.record.snapshot()
        stateFiles.block()
        assertThrows(CodeModePersistenceException::class.java) {
            state.source.finish(state.record, state.terminal, state.continuity, Usage(outputTokens = 7))
        }
        stateFiles.unblock()
        assertEquals(before.outer, state.record.outer)
        assertEquals(before.source, state.record.source)
        assertEquals(before.continuity, state.record.continuity)
        assertEquals(before.continuityReplay, state.record.continuityReplay)
        assertEquals(before.sourceState, state.record.sourceState)
        assertFalse(state.record.sourceState?.complete == true)
        assertFalse(stateFiles.records().single()["sourceState"].toString().contains("\"complete\":true"))
    }

    @Test
    fun `a failed usage claim can retry once and restored usage retains its raw billing receipt`() {
        val state = SourceState()
        state.source.finish(
            state.record,
            state.terminal,
            state.continuity,
            Usage(inputTokens = 100, outputTokens = 7, reasoningTokens = 3, recordedOutputTokens = 7),
        )
        stateFiles.block()
        assertThrows(CodeModePersistenceException::class.java) { state.source.consume(state.record) }
        assertFalse(state.record.sourceState?.consumed == true)
        stateFiles.unblock()
        val usage = checkNotNull(state.source.consume(state.record))
        assertEquals(7L, usage.outputTokens)
        assertEquals(7L, usage.recordedOutputTokens)
        assertEquals(0L, usage.unrecordedOutputTokens)
        assertNull(state.source.consume(state.record))
        val restored = state.record.snapshot().restore()
        assertEquals(state.record.sourceState, restored.sourceState)
        assertEquals(usage, restored.sourceState?.usage?.value())
    }
}
