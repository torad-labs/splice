package splice.provider.codex

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.turn.TurnOutcome
import splice.provider.codex.state.CodeModeHistoryAnchor
import splice.provider.codex.state.CodeModeReplayAnchors
import splice.provider.codex.state.CodeModeStateDelta
import splice.provider.codex.state.CodeModeStateJournal
import splice.upstream.RoundResult
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeStep
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND

class CodexCodeModeRecoveryTest : CodeModeBridgeTestSupport() {
    @Test
    fun `failed result save retries without losing results or emitting empty tool use`() = runTest {
        val runtime = scripted(
            CodeModeStep.Calls(listOf(call("read", "Read"))),
            CodeModeStep.Completed("done"),
        )
        val manager = bridge(runtime)
        val first = RecordingSink()
        manager.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, first) {
            RoundResult.Outcome(outerOutcome())
        }
        val id = first.tools.single().id
        stateFiles.block()
        val failedSink = RecordingSink()
        val failed = manager.interceptor(turn(id, "A"), disableParallel = false)
            .intercept(requestWithResult(id, "A"), failedSink) { error("must not post") }.turn()
        assertTrue(failed is TurnOutcome.Failure)
        assertTrue((failed as TurnOutcome.Failure).message.contains("could not be saved"))
        assertTrue(failedSink.tools.isEmpty(), "a failed batch cannot expose new client calls")
        assertEquals(2, runtime.cell.advances, "the completed step is captured before its batch commit")
        stateFiles.unblock()
        val retrySink = RecordingSink()
        val retried = manager.interceptor(turn(id, "A"), disableParallel = false)
            .intercept(requestWithResult(id, "A"), retrySink) { RoundResult.Outcome(completedOutcome()) }.turn()
        assertTrue(retried is TurnOutcome.Success)
        assertFalse((retried as TurnOutcome.Success).hasToolUse)
        assertTrue(retrySink.tools.isEmpty())
        assertEquals(2, runtime.cell.advances)
        assertEquals(listOf(CodeModeResult("read", "A")), runtime.cell.results.last())
        assertEquals(1, runtime.starts)
    }

    @Test
    fun `failed sequential acceptance retries exposure then delivers both results once`() = runTest {
        val runtime = scripted(
            CodeModeStep.Calls(listOf(call("read", "Read"), call("edit", "Edit"))),
            CodeModeStep.Completed("done"),
        )
        val manager = bridge(runtime)
        val first = RecordingSink()
        manager.interceptor(turn(), disableParallel = true).intercept(BASE_REQUEST, first) {
            RoundResult.Outcome(outerOutcome())
        }
        val firstId = first.tools.single().id
        stateFiles.block()
        val failed = manager.interceptor(turn(firstId, "A"), disableParallel = true)
            .intercept(requestWithResult(firstId, "A"), RecordingSink()) { error("must not post") }.turn()
        assertTrue(failed is TurnOutcome.Failure)
        stateFiles.unblock()
        val next = RecordingSink()
        manager.interceptor(turn(firstId, "A"), disableParallel = true)
            .intercept(requestWithResult(firstId, "A"), next) { error("must not post") }
        val secondId = next.tools.single().id
        assertEquals("Edit", next.tools.single().name)
        val results = listOf(CodeModeResult(firstId, "A"), CodeModeResult(secondId, "B"))
        manager.interceptor(turn(results = results), disableParallel = true)
            .intercept(requestWithTwoResults(firstId, secondId), RecordingSink()) {
                RoundResult.Outcome(completedOutcome())
            }
        assertEquals(listOf(CodeModeResult("read", "A"), CodeModeResult("edit", "B")), runtime.cell.results.last())
        assertEquals(2, runtime.cell.advances)
        assertEquals(1, runtime.starts)
    }

    @Test
    fun `passive sibling interruption preserves exact tool error evidence`() = runTest {
        val runtime = scripted(
            CodeModeStep.Calls(listOf(call("read", "Read"))),
            CodeModeStep.Calls(listOf(call("edit", "Edit"))),
        )
        val manager = bridge(runtime)
        val sink = RecordingSink()
        manager.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, sink) {
            RoundResult.Outcome(outerOutcome())
        }
        val id = sink.tools.single().id
        val value = "evidence with \"quotes\"\nand Unicode é"
        val body = siblingBody(id, value)
        var upstream = ""
        val returned = turn(results = listOf(CodeModeResult(id, value, true)))
        val result = manager.interceptor(returned, disableParallel = false)
            .intercept(body, RecordingSink()) {
                upstream = it
                RoundResult.Outcome(completedOutcome())
            }.turn()
        assertTrue(result is TurnOutcome.Success)
        assertEvidence(upstream, id, value)
        assertTrue("new user instruction" in upstream)
        assertEquals(1, runtime.starts)
    }

    @Test
    fun `lost worker continuation preserves newly returned evidence without rerunning source`() = runTest {
        val runtime = scripted(CodeModeStep.Calls(listOf(call("read", "Read"))))
        val manager = bridge(runtime)
        val sink = RecordingSink()
        manager.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, sink) {
            RoundResult.Outcome(outerOutcome())
        }
        val id = sink.tools.single().id
        manager.onHeadStop()
        val replacement = ScriptedRuntime(ArrayDeque())
        val restored = bridge(replacement)
        val value = "completed before restart"
        var upstream = ""
        val returned = turn(results = listOf(CodeModeResult(id, value, true)))
        val result = restored.interceptor(returned, disableParallel = false)
            .intercept(siblingBody(id, value), RecordingSink()) {
                upstream = it
                RoundResult.Outcome(completedOutcome())
            }.turn()
        assertTrue(result is TurnOutcome.Success)
        assertEvidence(upstream, id, value)
        assertTrue("new user instruction" in upstream)
        assertEquals(0, replacement.starts)
    }

    @Test
    fun `future journal fields load through the real bridge without dropping any record or file`() {
        val (file, records) = seedJournal()
        addFutureFields(file)
        val manager = bridge(scripted())
        try {
            assertTrue(Files.exists(file), "a newer writer's fields must never cause conversation deletion")
            assertEquals(records.map { it.snapshot() }, retained(manager).map { it.snapshot() })
        } finally {
            manager.onProviderStop()
        }
    }

    @Test
    fun `a torn journal with future fields recovers every committed record before appending`() {
        val (file, records) = seedJournal()
        addFutureFields(file)
        Files.writeString(file, """{"key":"synthetic","records":[""", APPEND)
        val changed = records.first().snapshot().copy(source = "synthetic recovered source")
        val delta = CodeModeStateDelta("synthetic", listOf(changed), emptySet(), emptyList())

        CodeModeStateJournal.write(file, Json.encodeToString(delta))

        val manager = bridge(scripted())
        try {
            assertTrue(Files.exists(file))
            val restored = retained(manager).map { it.snapshot() }
            assertEquals(records.size, restored.size)
            assertEquals(changed.source, restored.single { it.id == changed.id }.source)
            assertEquals(records.drop(1).map { it.snapshot() }, restored.filter { it.id != changed.id })
        } finally {
            manager.onProviderStop()
        }
    }

    @Test
    fun `malformed JSON remains invalid journal state on the real bridge`() {
        val (file, _) = seedJournal()
        Files.writeString(file, "{not valid JSON}\n")
        val manager = bridge(scripted())
        try {
            assertTrue(retained(manager).isEmpty())
            assertFalse(Files.exists(file), "unknown-key tolerance must not make malformed state valid")
        } finally {
            manager.onProviderStop()
        }
    }

    @Test
    fun `a journal missing a required record field remains invalid on the real bridge`() {
        val (file, _) = seedJournal()
        val root = Json.parseToJsonElement(Files.readString(file)).jsonObject
        val records = root.getValue("records").jsonArray.map { JsonObject(it.jsonObject - "source") }
        Files.writeString(file, JsonObject(root + ("records" to JsonArray(records))).toString() + "\n")
        val manager = bridge(scripted())
        try {
            assertTrue(retained(manager).isEmpty())
            assertFalse(Files.exists(file), "missing required fields must still reject the conversation")
        } finally {
            manager.onProviderStop()
        }
    }

    private fun seedJournal(): Pair<Path, List<CodeModeRecord>> {
        val records = (1..3).map { at ->
            CodeModeRecords.of("synthetic", at, 1_000L).apply {
                replayAnchors = CodeModeReplayAnchors(CodeModeHistoryAnchor(null, 0), emptyMap())
            }
        }
        val store = CodexCodeModeStore(stateLocation(), Json { encodeDefaults = true }, {})
        store.load()
        store.save(records, emptyList())
        return stateFiles.files().single() to records
    }

    private fun addFutureFields(file: Path) {
        val root = Json.parseToJsonElement(Files.readString(file)).jsonObject
        val future = "future_field" to JsonPrimitive("synthetic newer-writer metadata")
        val records = root.getValue("records").jsonArray.map { item ->
            val record = item.jsonObject
            val anchors = record.getValue("replayAnchors").jsonObject
            JsonObject(record + future + ("replayAnchors" to JsonObject(anchors + future)))
        }
        Files.writeString(file, JsonObject(root + future + ("records" to JsonArray(records))).toString() + "\n")
    }

    private fun retained(manager: CodexCodeModeBridge): List<CodeModeRecord> {
        return manager.registry.recordsFor("synthetic")
    }

    private fun scripted(vararg steps: CodeModeStep) = ScriptedRuntime(ArrayDeque(steps.toList()))

    private fun assertEvidence(body: String, id: String, value: String) {
        val items = Json.parseToJsonElement(body).jsonObject.getValue("input").jsonArray
        assertFalse(items.any { it.jsonObject["type"] == JsonPrimitive("function_call_output") })
        val output = items.single { it.jsonObject["type"] == JsonPrimitive("custom_tool_call_output") }
            .jsonObject.getValue("output").jsonPrimitive.content
        val evidence = terminatedEvidence(output)
        assertEquals("interrupted", evidence.getValue("status").jsonPrimitive.content)
        assertEquals("false", evidence.getValue("sourceRerun").jsonPrimitive.content)
        val result = evidence.getValue("results").jsonArray.single().jsonObject
        assertEquals(id, result.getValue("id").jsonPrimitive.content)
        assertEquals(value, result.getValue("output").jsonPrimitive.content)
        assertEquals("true", result.getValue("isError").jsonPrimitive.content)
    }

    private fun siblingBody(id: String, value: String): String {
        val raw = Json.parseToJsonElement(requestWithSiblingBeforeResult(id, "new user instruction")).jsonObject
        val input = raw.getValue("input").jsonArray.map { element ->
            val item = element.jsonObject
            if (item["type"] == JsonPrimitive("function_call_output")) {
                JsonObject(item + ("output" to JsonPrimitive(value)))
            } else {
                item
            }
        }
        return JsonObject(raw + ("input" to JsonArray(input))).toString()
    }
}
