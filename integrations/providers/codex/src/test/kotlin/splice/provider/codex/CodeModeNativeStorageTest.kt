// NEW: native replay payload grows with history, not with the number of scripts measured on that history.
package splice.provider.codex

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.reasoning.ReasoningReplay
import splice.provider.codex.state.CodeModeNativeChain
import splice.provider.codex.stream.CodeModeSourceState
import splice.upstream.RoundResult
import splice.upstream.codemode.CodeModeStep
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class CodeModeNativeStorageTest : CodeModeBridgeTestSupport() {
    private val retained = "x".repeat(256 * 1024)
    private val opening by lazy {
        """{"input":[{"role":"developer","content":"s"},{"role":"user","content":"start"},""" +
            """{"type":"reasoning","id":"native-root","summary":[],"encrypted_content":"$retained"}]}"""
    }

    @Test
    fun `twenty scripts persist a native payload once rather than twenty copies`() = runTest {
        val runtime = QueuedRuntime(ArrayDeque((1..20).map { ArrayDeque(listOf(CodeModeStep.Completed("done"))) }))
        val manager = manager(runtime)
        var posts = 0
        manager.interceptor(turn(), null, disableParallel = false).intercept(opening, RecordingSink()) {
            posts++
            RoundResult.Outcome(
                if (posts <= 20) {
                    outerOutcome("outer-$posts").run {
                        copy(handoffs = handoffs.copy(reasoningEnvelopes = listOf(reasoning(posts))))
                    }
                } else {
                    completedOutcome()
                },
            )
        }
        val records = stateFiles.records()
        assertEquals(20, records.size)
        val nativeBytes = records.sumOf { it["nativeSegments"]?.toString()?.toByteArray()?.size ?: 0 }
        assertTrue(nativeBytes < retained.length + 16 * 1024, "native snapshots occupy $nativeBytes bytes")
    }

    @Test
    fun `a restored chain replays each inherited native and script continuity exactly once`() = runTest {
        val runtime = QueuedRuntime(ArrayDeque((1..4).map { ArrayDeque(listOf(CodeModeStep.Completed("done"))) }))
        var posts = 0
        manager(runtime).interceptor(turn(), null, disableParallel = false).intercept(opening, RecordingSink()) {
            posts++
            RoundResult.Outcome(
                if (posts <= 4) {
                    outerOutcome("outer-$posts").run {
                        copy(handoffs = handoffs.copy(reasoningEnvelopes = listOf(reasoning(posts))))
                    }
                } else {
                    completedOutcome()
                },
            )
        }
        val restored = manager(QueuedRuntime(ArrayDeque()))
        var posted = ""
        restored.interceptor(turn(), null, disableParallel = false)
            .intercept(
                """{"input":[{"role":"developer","content":"s"},""" +
                    """{"role":"user","content":"start"},{"role":"user","content":"next"}]}""",
                RecordingSink(),
            ) {
                posted = it
                RoundResult.Outcome(completedOutcome())
            }
        val items = Json.parseToJsonElement(posted).jsonObject.getValue("input") as JsonArray
        val reasoningIds = items.map { it.jsonObject }.filter { it["type"]?.toString() == "\"reasoning\"" }
            .map { it.getValue("id").toString() }
        assertEquals(listOf("native-root", "step-1", "step-2", "step-3", "step-4").map { "\"$it\"" }, reasoningIds)
        assertTrue(logLines.none { "history rewrite skipped" in it }, logLines.toString())
    }

    @Test
    fun `removing a parent from the durable checkpoint preserves inherited natives after restart`() = runTest {
        val runtime = QueuedRuntime(ArrayDeque((1..2).map { ArrayDeque(listOf(CodeModeStep.Completed("done"))) }))
        val manager = manager(runtime)
        var posts = 0
        manager.interceptor(turn(), disableParallel = false).intercept(opening, RecordingSink()) {
            posts++
            RoundResult.Outcome(if (posts <= 2) outerOutcome("outer-$posts") else completedOutcome())
        }
        val store = CodexCodeModeStore(stateLocation(), Json, {})
        val records = store.load().records.map { it.restore() }
        CodeModeNativeChain.link(records)
        val child = records.last()
        val sourceState = CodeModeSourceState(complete = true, consumed = true)
        child.sourceState = sourceState
        store.save(listOf(child), emptyList(), dirtyKeys = setOf(child.key))
        assertEquals(1, stateFiles.records().size)
        assertEquals(sourceState, store.load().records.single().sourceState, "promotion preserves source settlement")
        val restored = manager(QueuedRuntime(ArrayDeque()))
        val history = """{"input":[{"role":"user","content":"start"},{"role":"user","content":"next"}]}"""
        var posted = ""
        restored.interceptor(turn(), disableParallel = false).intercept(history, RecordingSink()) {
            posted = it
            RoundResult.Outcome(completedOutcome())
        }
        val items = Json.parseToJsonElement(posted).jsonObject.getValue("input") as JsonArray
        assertEquals(1, items.count { it.jsonObject["id"]?.toString() == "\"native-root\"" })
    }

    private fun manager(
        runtime: QueuedRuntime,
        clock: Clock = Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC),
    ) = CodexCodeModeBridge(
        CodeModeBridgeConfig(
            { runtime },
            stateLocation(),
            clock = clock,
            log = { logLines += it },
        ),
    )

    private fun reasoning(n: Int): String = checkNotNull(
        ReasoningReplay.encodeReasoningEnvelope(
            buildJsonObject {
                put("type", "reasoning")
                put("id", "step-$n")
                put("encrypted_content", "synthetic-$n")
            },
        ),
    )
}
