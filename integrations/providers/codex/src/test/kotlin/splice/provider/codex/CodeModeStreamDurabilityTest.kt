package splice.provider.codex

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
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
import splice.core.turn.RoundHandoffs
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.core.turn.UsageOrigin
import splice.provider.codex.state.CodeModeExpiredHistory
import splice.provider.codex.state.CodeModeKeyLocks
import splice.provider.codex.state.CodeModeRegistryAccess
import splice.provider.codex.state.CodeModeStateJournal
import splice.provider.codex.state.save.CodeModeStateWrite
import splice.provider.codex.stream.CodeModeClientContexts
import splice.provider.codex.stream.CodeModeLiveRound
import splice.provider.codex.stream.CodeModeSourceState
import splice.provider.codex.stream.CodeModeStreamAdmission
import splice.provider.codex.stream.source.CodeModeSourceCapture
import splice.provider.codex.stream.source.CodeModeSourceRecords
import splice.upstream.codemode.CodeModeSourcePart
import splice.upstream.sse.CustomToolSource
import java.io.IOException
import java.lang.ref.Reference
import java.lang.ref.WeakReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.time.Duration.Companion.hours

class CodeModeStreamDurabilityTest : CodeModeStatementStreamSupport() {
    @Test
    @Timeout(20)
    fun `an admitted retained round releases the raw body captured by admission`() {
        val (round, body, manager) = admittedRound()
        try {
            assertTrue(round.ready.isCompleted, "the source is admitted but its round stays retained")
            assertTrue(round.finished?.isActive == true, "the upstream reader must still own its admission factory")
            assertTrue(collected(body), "retained round still reaches its one-use admission request body")
            assertTrue(round.finished?.isActive == true, "collection must not require ending the live reader")
        } finally {
            Reference.reachabilityFence(round)
            Reference.reachabilityFence(manager)
            manager.onHeadStop()
        }
    }

    private fun admittedRound(): RetainedAdmission = runBlocking {
        // Large valid JSON whitespace exercises raw request retention without duplicating prompt data in the ledger.
        val body = " ".repeat(RAW_BODY_PADDING_CHARS) + BASE_REQUEST
        val weak = WeakReference(body)
        val manager = bridge(CodeModeRawBodyFixture.runtime())
        val outcome = manager.interceptor(turn(), disableParallel = false).intercept(
            body,
            StepSink(),
            CodeModeRawBodyFixture.post(outer(source = "await tools.Read({});")),
        ).turn()
        assertTrue(outcome is TurnOutcome.Success && outcome.hasToolUse)
        val key = stateFiles.records().first().getValue("key").jsonPrimitive.content
        val record = manager.registry.recordsFor(key).single()
        val round = checkNotNull(manager.driver.streams.find(record))
        RetainedAdmission(round, weak, manager)
    }

    private data class RetainedAdmission(
        val round: CodeModeLiveRound,
        val body: WeakReference<String>,
        val manager: CodexCodeModeBridge,
    )

    private fun collected(body: WeakReference<String>): Boolean {
        repeat(20) {
            System.gc()
            if (body.get() == null) return true
        }
        return false
    }

    private inner class SourceRound(
        key: String = "source-cost",
        location: CodeModeStateLocation = stateLocation(),
    ) {
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
        private val call = GatewayCustomCall(
            record.origin.outerCallId,
            CODE_MODE_TOOL_NAME,
            "",
            JsonObject(
                mapOf(
                    "type" to JsonPrimitive("custom_tool_call"),
                    "call_id" to JsonPrimitive(record.origin.outerCallId),
                    "name" to JsonPrimitive(CODE_MODE_TOOL_NAME),
                    "input" to JsonPrimitive(""),
                ),
            ),
        )

        suspend fun begin() {
            capture.observe(CustomToolSource.Started(call))
        }

        suspend fun append(text: String) {
            capture.observe(CustomToolSource.Delta(record.origin.outerCallId, text))
        }

        fun finish(text: String) {
            capture.finish(
                TurnOutcome.Success(
                    false,
                    false,
                    Usage(),
                    handoffs = RoundHandoffs(
                        customCalls = listOf(
                            call.copy(input = text, raw = JsonObject(call.raw + ("input" to JsonPrimitive(text)))),
                        ),
                    ),
                ),
            )
        }
    }

    @Test
    @Timeout(20)
    fun `producer deltas stay unexecutable until the whole item is durable`() = runBlocking {
        val state = SourceRound()
        state.begin()
        repeat(200) { state.append("x") }
        assertEquals("", stateFiles.records().single().getValue("source").jsonPrimitive.content)
        state.finish("x".repeat(200))
        assertEquals(CodeModeSourcePart.Complete("x".repeat(200)), state.capture.source.view().read())
        assertEquals("x".repeat(200), stateFiles.records().single().getValue("source").jsonPrimitive.content)
    }

    @Test
    @Timeout(20)
    fun `a refused whole item save keeps source unexecutable until its durable retry`() = runBlocking {
        val state = SourceRound()
        state.begin()
        state.refuseWrites = true
        state.append("await tools.Read({});")
        assertThrows(CodeModePersistenceException::class.java) { state.finish("await tools.Read({});") }
        assertEquals("", stateFiles.records().single().getValue("source").jsonPrimitive.content)
        val waiting = async { state.capture.source.view().read() }
        kotlinx.coroutines.yield()
        assertFalse(waiting.isCompleted, "source rejected by the journal never reaches a worker")
        waiting.cancel()
        state.refuseWrites = false
        state.finish("await tools.Read({});")
        assertEquals(CodeModeSourcePart.Complete("await tools.Read({});"), state.capture.source.view().read())
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

// The original admission regression retained a 9.2 MB request while its response reader stayed live.
private const val RAW_BODY_PADDING_CHARS = 9_200_000
