// NEW: a finished native script outlives an edit of the history around it, the way Codex keeps what it recorded
// (codex-rs core/src/session/turn.rs:1618): the edit falls back to the client's history once, the script stays its
// exec call and output, and a history restored to what GPT was sent extends that post. Retiring native lineage
// directly never takes callback authentication or unclaimed billing. Until 2026-10-23 an edited native item skipped
// the record's history rewrite and retired its lineage; that rule is replaced, and its request-flow tests with it.
package splice.provider.codex

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.provider.codex.state.diagnostics.CodeModeNativeBranch
import splice.provider.codex.state.diagnostics.CodeModeNativeRejection
import splice.provider.codex.stream.CodeModeSourceState
import splice.provider.codex.stream.CodeModeSourceUsage
import splice.upstream.RoundResult
import splice.upstream.codemode.CodeModeStep

internal class CodeModeNativeRetirementTest : CodeModeBridgeTestSupport() {
    @Test
    fun `an edited native search falls back once and GPT is sent its finished script as before`() = runTest {
        val fixture = completedNative()
        try {
            var posted = ""
            fixture.manager.interceptor(turn(), null, disableParallel = false)
                .intercept(nativeRequest(editedNative(fixture.readId)), RecordingSink()) { request ->
                    posted = request
                    RoundResult.Outcome(completedOutcome())
                }
            // Code mode never posts a native search, since exec declares none: the fallback folds to the same post.
            assertEquals(fixture.sent, input(posted), "the finished script left what GPT was sent: $posted")
            assertFalse(fixture.readId in posted, "the finished script's client calls came back: $posted")
            assertEquals(2, Regex("outer-call").findAll(posted).count(), "the script left its exec call and output")
            assertFalse(fixture.record.abandoned(), "an edit never abandons a finished script")
            assertFalse(logLines.any { "abandoned record" in it || "history rewrite skipped" in it }, "$logLines")
            assertEquals(2, fixture.runtime.cell.advances, "the finished source was not rerun")
        } finally {
            fixture.manager.onHeadStop()
        }
    }

    @Test
    fun `a history restored to what GPT was sent after an edit extends that post as it was sent`() = runTest {
        val fixture = completedNative()
        try {
            fixture.manager.interceptor(turn(), null, disableParallel = false)
                .intercept(nativeRequest(editedNative(fixture.readId)), RecordingSink()) {
                    RoundResult.Outcome(completedOutcome())
                }
            val later = Json.parseToJsonElement("""{"role":"user","content":"synthetic later continuation"}""")
            var posted = ""
            fixture.manager.interceptor(turn(), null, disableParallel = false)
                .intercept(nativeRequest(nativeResult(fixture.readId) + later), RecordingSink()) { request ->
                    posted = request
                    RoundResult.Outcome(completedOutcome())
                }
            assertEquals(fixture.sent + later, input(posted), "the restored history rewrote what GPT was sent")
            assertFalse(fixture.record.abandoned(), "a restore never abandons a finished script")
            assertEquals(2, fixture.runtime.cell.advances, "the finished source was not rerun")
        } finally {
            fixture.manager.onHeadStop()
        }
    }

    @Test
    fun `missing callback history does not retire a completed native carrier`() = runTest {
        val fixture = completedNative()
        try {
            fixture.manager.interceptor(turn(), null, disableParallel = false)
                .intercept(nativeRequest(emptyList()), RecordingSink()) { RoundResult.Outcome(completedOutcome()) }
            assertEquals(listOf(fixture.record), fixture.registry.completed(fixture.record.key))
            assertTrue(fixture.record.carry.segments.isNotEmpty())
            assertEquals(null, fixture.record.error)
        } finally {
            fixture.manager.onHeadStop()
        }
    }

    @Test
    fun `unconsumed source billing survives retirement until its durable claim`() = runTest {
        val fixture = completedNative()
        try {
            fixture.registry.changes.save(fixture.record) {
                it.sourceState = CodeModeSourceState(
                    complete = true,
                    usage = CodeModeSourceUsage(10, 2, 0, 0, 0),
                )
            }
            val rejected = CodeModeOmission(
                fixture.record,
                CodeModeNativeBranch.PAYLOAD.reason(),
                CodeModeNativeRejection(true, CodeModeNativeBranch.PAYLOAD),
            )
            fixture.registry.changes.retireNative(listOf(rejected))
            assertEquals(null, fixture.record.error)
            assertEquals(listOf(fixture.record), fixture.registry.completed(fixture.record.key))
            assertEquals(10L, fixture.registry.source.consume(fixture.record)?.inputTokens)
            fixture.registry.changes.retireNative(listOf(rejected))
            assertTrue(fixture.registry.completed(fixture.record.key).isEmpty())
            assertEquals(null, fixture.registry.source.consume(fixture.record))
        } finally {
            fixture.manager.onHeadStop()
        }
    }

    @Test
    fun `missing native evidence and partial ancestry cannot make a permanent retirement`() = runTest {
        val fixture = completedNative()
        try {
            val branches = listOf(
                CodeModeNativeBranch.ABSENT,
                CodeModeNativeBranch.COUNT,
                CodeModeNativeBranch.UNEXPECTED,
            )
            for (branch in branches) {
                val omission = CodeModeOmission(fixture.record, branch.reason(), CodeModeNativeRejection(null, branch))
                fixture.registry.changes.retireNative(listOf(omission))
                assertEquals(null, fixture.record.error)
                assertEquals(listOf(fixture.record), fixture.registry.completed(fixture.record.key))
                assertTrue(fixture.record.carry.segments.isNotEmpty())
            }
        } finally {
            fixture.manager.onHeadStop()
        }
    }

    @Test
    fun `completed abandoned source carriers remain visible until their terminal bill is claimed`() = runTest {
        val fixture = completedNative()
        try {
            fixture.registry.changes.save(fixture.record) {
                it.error = CODE_MODE_ABANDONED
                it.sourceState = CodeModeSourceState(
                    complete = true,
                    usage = CodeModeSourceUsage(10, 2, 0, 0, 0),
                )
            }
            assertTrue(
                fixture.record in fixture.registry.completed(fixture.record.key),
                "a completed abandon carrier remains visible for terminal billing",
            )
            assertEquals(10L, fixture.registry.source.consume(fixture.record)?.inputTokens)
            assertEquals(null, fixture.registry.source.consume(fixture.record))
        } finally {
            fixture.manager.onHeadStop()
        }
    }

    private data class CompletedNative(
        val runtime: ScriptedRuntime,
        val manager: CodexCodeModeBridge,
        val registry: CodexCodeModeRegistry,
        val readId: String,
        val record: CodeModeRecord,
        val sent: List<JsonElement>,
    )

    /** A script that read once across a native search and finished, and the input GPT was sent with its output. */
    private suspend fun completedNative(): CompletedNative {
        val runtime = ScriptedRuntime(
            ArrayDeque(
                listOf(CodeModeStep.Calls(listOf(call("synthetic-read", "Read"))), CodeModeStep.Completed("done")),
            ),
        )
        val manager = bridge(runtime)
        val sink = RecordingSink()
        manager.interceptor(turn(), null, disableParallel = false)
            .intercept(baselineWithNativeSearch(), sink) { RoundResult.Outcome(outerOutcome()) }
        val readId = sink.tools.single().id
        var sent = emptyList<JsonElement>()
        manager.interceptor(turn(readId, "A"), null, disableParallel = false)
            .intercept(nativeRequest(nativeResult(readId)), RecordingSink()) {
                sent = input(it)
                RoundResult.Outcome(completedOutcome())
            }
        val registry = manager.registry
        val key = stateFiles.records().single().getValue("key").jsonPrimitive.content
        val record = registry.recordsFor(key).single()
        assertEquals(CodeModePhase.COMPLETED, record.phase, "the control must reach completed native state")
        assertTrue(record.carry.segments.isNotEmpty())
        assertTrue(sent.isNotEmpty(), "the script's output was never posted")
        return CompletedNative(runtime, manager, registry, readId, record, sent)
    }

    /** The finished script's client history with its native search changed, as an edit leaves it. */
    private fun editedNative(readId: String): List<JsonElement> = nativeResult(readId).map { item ->
        if (item.isSearchCall()) {
            JsonObject(item.jsonObject + ("arguments" to JsonPrimitive("synthetic changed native arguments")))
        } else {
            item
        }
    }

    private fun JsonElement.isSearchCall() = jsonObject["type"]?.jsonPrimitive?.content == "tool_search_call"

    private fun input(body: String) = Json.parseToJsonElement(body).jsonObject.getValue("input").jsonArray.toList()

    private fun ownedCallbacks(readId: String): List<JsonElement> =
        Json.parseToJsonElement(requestWithResult(readId, "A")).jsonObject.getValue("input").jsonArray.drop(1)

    private fun nativeResult(readId: String): List<JsonElement> =
        Json.parseToJsonElement(baselineWithNativeSearch()).jsonObject.getValue("input").jsonArray +
            ownedCallbacks(readId)

    private fun nativeRequest(input: List<JsonElement>): String =
        JsonObject(
            mapOf(
                "input" to JsonArray(input),
                "prompt_cache_key" to JsonPrimitive("splice-synthetic-native-retirement"),
            ),
        ).toString()

    private fun baselineWithNativeSearch(): String =
        """
        {"input":[
          {"role":"developer","content":"s"},
          {"role":"user","content":"start"},
          {"type":"tool_search_call","call_id":"known-search","arguments":{"query":"release"}},
          {"type":"tool_search_output","call_id":"known-search","tools":[
            {"type":"function","name":"mcp__release__lookup"}
          ]}
        ]}
        """.trimIndent()
}
