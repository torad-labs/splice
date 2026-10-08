package splice.provider.codex

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.parse.AnthropicParse
import splice.core.parse.AnthropicTurnBody
import splice.core.reasoning.ReasoningReplay
import splice.core.turn.TurnOutcome
import splice.dialect.responses.ResponsesFunctionNamespace
import splice.dialect.responses.ResponsesQuirks
import splice.dialect.responses.buildResponsesTestRequest
import splice.dialect.responses.request.ResponsesCodeModeProjection
import splice.upstream.BuiltTurn
import splice.upstream.codemode.CodeModeStep

class CodexCodeModeInstructionsTest : CodeModeBridgeTestSupport() {
    @Test
    fun `eligible turns append guidance to the original developer item without moving history`() {
        val builder = CodexCodeModeTurnBuilder(
            bridge(ScriptedRuntime(ArrayDeque())),
            media(),
            codeModeOnly = backendCodeModeOnly,
        )
        listOf("Caller instructions  \n", "", "<code_mode_orchestration>caller text</code_mode_orchestration>")
            .forEach { system ->
                val (body, original) = request(system)
                val prepared = builder.prepare(body, false, "session", original)
                val before = original.requestBody.getValue("input").jsonArray
                val after = prepared.requestBody.getValue("input").jsonArray
                val prefix = instructions(original.requestBody)
                assertEquals(before.size, after.size)
                assertEquals(before.drop(2), after.drop(2))
                assertEquals(original.requestBody - "input", prepared.requestBody - "input")
                assertTrue(instructions(prepared.requestBody).startsWith(prefix + "\n\n<code_mode_orchestration>"))
                assertEquals(
                    Regex("<code_mode_orchestration>").findAll(prefix).count() + 1,
                    Regex("<code_mode_orchestration>").findAll(instructions(prepared.requestBody)).count(),
                )
                val batching = "Batch independent searches and reads in one functions.exec"
                assertTrue(instructions(prepared.requestBody).contains(batching))
                assertTrue(instructions(prepared.requestBody).contains("Promise.all"))
                // V4-390: both lists ride grouped in the functions namespace; compare their members.
                val namespace = ResponsesFunctionNamespace()
                val originalTools = namespace.members(before.first().jsonObject.getValue("tools").jsonArray)
                val augmentedTools = namespace.members(after.first().jsonObject.getValue("tools").jsonArray)
                // V4-388: the client tools leave the top level and are declared inside exec's manual.
                val exec = augmentedTools.single().jsonObject
                assertEquals("exec", exec.getValue("name").jsonPrimitive.content)
                originalTools.forEach { tool ->
                    val name = tool.jsonObject.getValue("name").jsonPrimitive.content
                    assertTrue(exec.getValue("description").jsonPrimitive.content.contains("### `$name`"), name)
                }
                val projection = ResponsesCodeModeProjection()
                assertEquals(projection.project(before).logicalItems.size, projection.project(after).logicalItems.size)
                assertEquals(projection.project(before).replayItems, projection.project(after).replayItems)
            }
    }

    @Test
    fun `disabled and excluded turns preserve the exact request bytes`() {
        val (body, original) = request("Caller instructions")
        val disabled = CodexCodeModeTurnBuilder(null, media()).prepare(body, false, "session", original)
        assertSame(original, disabled)
        val builder = CodexCodeModeTurnBuilder(
            bridge(ScriptedRuntime(ArrayDeque())),
            media(),
            codeModeOnly = backendCodeModeOnly,
        )
        // 2026-09-21: a compaction is no longer excluded — it must build the same bytes as a turn, so
        // its prefix stays cached (CodexCodeModeBridgeTest pins the byte identity).
        val compact = builder.prepare(body, true, "session", original)
        assertEquals(builder.prepare(body, false, "session", original).requestBody, compact.requestBody)
        listOf(
            builder.prepare(toollessBody(), false, "session", original),
            builder.prepare(namedChoiceBody(), false, "session", original),
        ).forEach { excluded ->
            assertEquals(original.requestBody.toString(), excluded.requestBody.toString())
            assertTrue(excluded.roundInterceptor == null)
        }
        listOf("gpt-5.5", "gpt-6-astra-preview", "other").forEach { model ->
            val excluded = original.copy(meta = original.meta.copy(upstreamModel = model))
            assertSame(excluded, builder.prepare(body, false, "session", excluded))
        }
        val nonLite = built("gpt-6-astra", lite = false)
        assertSame(nonLite, builder.prepare(body, false, "session", nonLite))
    }

    // V4-441: there is no built-in list. The backend's marks (backendCodeModeOnly, asked each turn) cover
    // Sol in both families, and TOML `code_mode_models` adds to them: it is also the only way to name a model
    // the backend hides from its list (codex-auto-review).
    @Test
    fun `the backend's marks cover Sol in both families and TOML models add to them`() {
        val (body, original) = request("Caller")
        val marked = CodexCodeModeTurnBuilder(
            bridge(ScriptedRuntime(ArrayDeque())),
            media(),
            codeModeOnly = backendCodeModeOnly,
        )
        listOf(
            "gpt-6-astra",
            "gpt-6-sol",
            "gpt-6-luna",
            "gpt-5.6-sol",
            "gpt-5.6-terra",
            "gpt-5.6-luna",
            "GPT-5.6-Sol[1m]",
        ).forEach { model ->
            val eligible = original.copy(meta = original.meta.copy(upstreamModel = model))
            val prepared = marked.prepare(body, false, "session", eligible)
            assertTrue(instructions(prepared.requestBody).contains("functions.exec"), model)
        }
        val hidden = original.copy(meta = original.meta.copy(upstreamModel = "codex-auto-review"))
        assertSame(hidden, marked.prepare(body, false, "session", hidden), "the backend hides it: no mark to read")
        val listed = CodexCodeModeTurnBuilder(
            bridge(ScriptedRuntime(ArrayDeque())),
            media(),
            models = listOf(" gpt-5.6-terra ", "codex-auto-review"),
        )
        listOf("gpt-5.6-terra", "codex-auto-review").forEach { model ->
            val named = original.copy(meta = original.meta.copy(upstreamModel = model))
            val prepared = listed.prepare(body, false, "session", named)
            assertTrue(instructions(prepared.requestBody).contains("functions.exec"), model)
        }
        val astra = original.copy(meta = original.meta.copy(upstreamModel = "gpt-6-astra"))
        assertSame(astra, listed.prepare(body, false, "session", astra), "no mark and not named: direct tools")
    }

    @Test
    fun `client parallel disable selects sequential guidance not the lite backend flag`() {
        val builder = CodexCodeModeTurnBuilder(
            bridge(ScriptedRuntime(ArrayDeque())),
            media(),
            codeModeOnly = backendCodeModeOnly,
        )
        val (ordinaryBody, ordinary) = request("Caller")
        assertEquals("false", ordinary.requestBody.getValue("parallel_tool_calls").jsonPrimitive.content)
        val concurrent = builder.prepare(ordinaryBody, false, "session", ordinary)
        assertTrue(instructions(concurrent.requestBody).contains("Promise.all"))

        val (serialBody, serial) = request("Caller", disableParallel = true)
        val sequential = builder.prepare(serialBody, false, "session", serial)
        assertFalse(instructions(sequential.requestBody).contains("Promise.all"))
        assertTrue(instructions(sequential.requestBody).contains("client has disabled parallel tool use"))
        assertTrue(instructions(sequential.requestBody).contains("sequential await"))
    }

    @Test
    fun `rebuilding an eligible request never accumulates guidance or mutates caller instructions`() {
        val builder = CodexCodeModeTurnBuilder(
            bridge(ScriptedRuntime(ArrayDeque())),
            media(),
            codeModeOnly = backendCodeModeOnly,
        )
        val (body, original) = request("Caller")
        val snapshot = original.requestBody.toString()
        val first = builder.prepare(body, false, "session", original).requestBody
        repeat(3) {
            assertEquals(first, builder.prepare(body, false, "session", original).requestBody)
            assertEquals(snapshot, original.requestBody.toString())
        }
        assertEquals(1, Regex("<code_mode_orchestration>").findAll(instructions(first)).count())
    }

    @Test
    fun `dependent callback resumptions keep exactly one guidance section and the baseline intact`() = runTest {
        val runtime = ScriptedRuntime(
            ArrayDeque(
                listOf(
                    CodeModeStep.Calls(listOf(call("runtime-read", "Read"))),
                    CodeModeStep.Calls(listOf(call("runtime-edit", "Edit"))),
                    CodeModeStep.Completed("done"),
                ),
            ),
        )
        val builder = CodexCodeModeTurnBuilder(bridge(runtime), media(), codeModeOnly = backendCodeModeOnly)
        val (initialBody, initial) = request("Caller")
        val first = builder.prepare(initialBody, false, "session", initial)
        val readSink = RecordingSink()
        checkNotNull(first.roundInterceptor).intercept(first.requestBody.toString(), readSink) { outerOutcome() }
        val readId = readSink.tools.single().id
        val readMessages = INITIAL_MESSAGES + callback(readId, "Read", "A")
        val (readBody, readBuilt) = request("Caller", messages = readMessages)
        val second = builder.prepare(readBody, false, "session", readBuilt)
        val editSink = RecordingSink()
        val waiting = checkNotNull(second.roundInterceptor).intercept(second.requestBody.toString(), editSink) {
            error("a dependent local resumption must not call the model")
        }
        assertTrue((waiting as TurnOutcome.Success).hasToolUse)
        val editId = editSink.tools.single().id
        val (editBody, editBuilt) = request("Caller", messages = readMessages + callback(editId, "Edit", "B"))
        val third = builder.prepare(editBody, false, "session", editBuilt)
        var finalBody = ""
        val result = checkNotNull(third.roundInterceptor).intercept(third.requestBody.toString(), RecordingSink()) {
            finalBody = it
            completedOutcome()
        }
        assertTrue(result is TurnOutcome.Success)
        val finalRequest = Json.parseToJsonElement(finalBody).jsonObject
        assertEquals(instructions(first.requestBody), instructions(finalRequest))
        assertEquals(1, Regex("<code_mode_orchestration>").findAll(instructions(finalRequest)).count())
        assertFalse(readId in finalBody)
        assertFalse(editId in finalBody)
        assertEquals(1, runtime.starts)
        assertEquals(3, runtime.cell.advances)
    }

    private fun request(
        system: String,
        disableParallel: Boolean = false,
        messages: String = INITIAL_MESSAGES,
    ): Pair<AnthropicTurnBody, BuiltTurn> {
        val raw = buildJsonObject {
            put("model", "gpt-6-astra")
            put("system", system)
            put("tools", Json.parseToJsonElement(TOOLS))
            put("messages", Json.parseToJsonElement("[$messages]"))
            if (disableParallel) {
                put(
                    "tool_choice",
                    buildJsonObject {
                        put("type", "auto")
                        put("disable_parallel_tool_use", true)
                    },
                )
            }
        }
        val body = AnthropicParse.parseAnthropicBody(raw.toString())
        val request = buildResponsesTestRequest(
            ResponsesQuirks(
                providerTag = "test",
                emitEmptyLiteInstructions = false,
                responsesLiteModelRegex = Regex("gpt-5\\.6|gpt-6", RegexOption.IGNORE_CASE),
            ),
            body,
            model = "gpt-6-astra",
            decode = { ReasoningReplay.decodeReasoningEnvelope(it) },
        )
        return body to built("gpt-6-astra", lite = true).copy(requestBody = request)
    }

    private fun instructions(request: JsonObject): String = request.getValue("input").jsonArray[1]
        .jsonObject.getValue("content").jsonPrimitive.content

    private fun callback(id: String, name: String, value: String): String =
        """,{"role":"assistant","content":[{"type":"tool_use","id":"$id","name":"$name","input":{}}]},
            {"role":"user","content":[{"type":"tool_result","tool_use_id":"$id","content":"$value"}]}"""

    private companion object {
        const val INITIAL_MESSAGES = """{"role":"user","content":"start"}"""
        const val TOOLS = """[{"name":"Read","input_schema":{"type":"object"}},{"name":"Edit","input_schema":{"type":"object"}}]"""
    }
}
