package splice.provider.codex

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.parse.AnthropicParse
import splice.core.parse.AnthropicTurnBody
import splice.provider.codex.catalog.CodeModeTurnCatalogs
import splice.upstream.BuiltTurn
import splice.upstream.RoundResult
import splice.upstream.codemode.CodeModeCell
import splice.upstream.codemode.CodeModeRuntime
import splice.upstream.codemode.CodeModeStep
import kotlin.random.Random

/** Source availability changes the nested tools, never whether generic Responses selects a runtime. */
class CodeModeDynamicToolsTest : CodeModeBridgeTestSupport() {
    @Test
    fun `a source removal prevents a nested callback even while its initial declaration remains`() = runTest {
        val runtime = ScriptedRuntime(
            ArrayDeque(listOf(CodeModeStep.Calls(listOf(call("removed", "Retired"))), CodeModeStep.Completed("done"))),
        )
        val body = request(
            listOf(definition("Read", "path"), definition("Retired", "old")),
            listOf(change("tool_removal", "Retired")),
        )
        val prepared = builder(runtime).prepare(body, "removed-tool", wire())
        val sink = RecordingSink()
        val upstream = ArrayDeque(listOf(outerOutcome(), completedOutcome()))

        val result = checkNotNull(prepared.roundInterceptor).intercept(prepared.requestBody.toString(), sink) {
            RoundResult.Outcome(upstream.removeFirst())
        }

        assertTrue(sink.tools.isEmpty(), "a withdrawn source tool must not reach the client")
        assertTrue(result.turn() is splice.core.turn.TurnOutcome.Failure, "withdrawal must return an honest failure")
    }

    @Test
    fun `a source history-only definition becomes callable from exec`() = runTest {
        val runtime = ScriptedRuntime(ArrayDeque(listOf(CodeModeStep.Calls(listOf(call("introduced", "Late"))))))
        val body = request(listOf(definition("Read", "path")), listOf(addition("Late", "query")))
        val prepared = builder(runtime).prepare(body, "introduced-tool", wire())
        val sink = RecordingSink()
        val upstream = ArrayDeque(listOf(outerOutcome(), completedOutcome()))

        checkNotNull(prepared.roundInterceptor).intercept(prepared.requestBody.toString(), sink) {
            RoundResult.Outcome(upstream.removeFirst())
        }

        assertEquals(listOf("Late"), sink.tools.map { it.name })
    }

    @Test
    fun `native tools without initial direct declarations retain their executor and callbacks`() = runTest {
        val cases = listOf(
            listOf(definition("Late", "query", deferred = true)) to emptyList(),
            emptyList<JsonObject>() to listOf(addition("Late", "query")),
        )
        cases.forEachIndexed { index, (tools, history) ->
            val runtime = ScriptedRuntime(ArrayDeque(listOf(CodeModeStep.Calls(listOf(call("late-$index", "Late"))))))
            val initial = wire().let {
                val input = JsonArray(it.requestBody.getValue("input").jsonArray.drop(1))
                it.copy(requestBody = JsonObject(it.requestBody + ("input" to input)))
            }
            val prepared = builder(runtime).prepare(request(tools, history), "no-direct-$index", initial)
            val sink = RecordingSink()
            val upstream = ArrayDeque(listOf(outerOutcome(), completedOutcome()))
            checkNotNull(prepared.roundInterceptor).intercept(prepared.requestBody.toString(), sink) {
                RoundResult.Outcome(upstream.removeFirst())
            }
            assertTrue(prepared.meta.tools.codeMode)
            assertEquals(listOf("Late"), sink.tools.map { it.name })
        }
    }

    @Test
    fun `history-only schemas stay at their boundary without exposing direct tools outside exec`() {
        val source = Json.parseToJsonElement(
            """{"type":"additional_tools","role":"developer","tools":[""" +
                """{"type":"function","name":"Late","parameters":${schema("query")}}]}""",
        )
        val initial = wire().let {
            val user = Json.parseToJsonElement("""{"role":"user","content":"start"}""")
            val input = JsonArray(it.requestBody.getValue("input").jsonArray.drop(1) + listOf(user, source))
            it.copy(requestBody = JsonObject(it.requestBody + ("input" to input)))
        }
        val body = request(emptyList(), listOf(addition("Late", "query")))
        val prepared = builder(ScriptedRuntime(ArrayDeque())).prepare(body, "history-declaration", initial)
        val input = prepared.requestBody.getValue("input").jsonArray

        assertEquals("additional_tools", input.first().jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("start", input[2].jsonObject["content"]?.jsonPrimitive?.content)
        val historical = input.last().jsonObject.getValue("tools").jsonArray.flatMap { tool ->
            val item = tool.jsonObject
            if (item["type"]?.jsonPrimitive?.content == "namespace") {
                item.getValue("tools").jsonArray.map { it.jsonObject }
            } else {
                listOf(item)
            }
        }
        assertEquals(listOf("exec", "wait"), historical.map { it.getValue("name").jsonPrimitive.content })
        val description = historical.first().getValue("description").jsonPrimitive.content
        assertTrue("query" in description, "the historical schema must stay visible at its original boundary")
    }

    @Test
    fun `native execution replaces direct allowed tools with the caller's outer choice`() {
        val runtime = ScriptedRuntime(ArrayDeque())
        val body = request(listOf(definition("Read", "path")), emptyList())
        val directChoice = Json.parseToJsonElement(
            """{"type":"allowed_tools","mode":"auto","tools":[{"type":"function","name":"Read"}]}""",
        )
        val shared = wire().let { it.copy(requestBody = JsonObject(it.requestBody + ("tool_choice" to directChoice))) }
        val prepared = builder(runtime).prepare(body, "outer-choice", shared)

        assertEquals(JsonPrimitive("auto"), prepared.requestBody["tool_choice"])
        val namespace = prepared.requestBody.getValue("input").jsonArray.first().jsonObject
            .getValue("tools").jsonArray.single().jsonObject
        val outer = namespace.getValue("tools").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content }
        assertEquals(listOf("exec", "wait"), outer)
        assertTrue(prepared.meta.tools.codeMode)
    }

    @Test
    fun `reoffering a revised definition updates the nested schema and runtime description`() = runTest {
        val body = request(
            listOf(definition("Read", "old_path")),
            listOf(change("tool_removal", "Read"), addition("Read", "new_path")),
        )
        val initial = wire(definitionField = "old_path")
        val catalog = catalogs().definitions(body, initial.requestBody).single()
        assertEquals(schema("new_path"), catalog.inputSchema)
        val runtime = CatalogRuntime()
        val prepared = builder(runtime).prepare(body, "revised-schema", initial)
        val upstream = ArrayDeque(listOf(outerOutcome(), completedOutcome()))

        checkNotNull(prepared.roundInterceptor).intercept(prepared.requestBody.toString(), RecordingSink()) {
            RoundResult.Outcome(upstream.removeFirst())
        }

        val description = runtime.descriptions.getValue("Read")
        assertTrue("new_path" in description, description)
        assertFalse("old_path" in description, description)
    }

    @Test
    fun `repeated bare references describe the schema at each historical load boundary`() {
        val revisions = List(12) { "query_revision_$it" }
        val history = revisions.flatMapIndexed { index, field ->
            val id = "toolu_splice_load_$index"
            listOf(
                addition("Late", field),
                """{"role":"assistant","content":[{"type":"tool_use","id":"$id","name":"Read","input":{}}]}""",
                """{"role":"user","content":[{"type":"tool_result","tool_use_id":"$id","content":[""" +
                    """{"type":"tool_reference","tool_name":"Late"}]}]}""",
            )
        }
        val body = request(listOf(definition("Read", "path")), history)
        val results = builder(ScriptedRuntime(ArrayDeque())).toolResults(body)

        assertEquals(revisions.size, results.size)
        results.zip(revisions).forEach { (result, field) ->
            assertTrue("tools.Late" in result.output, result.output)
            assertTrue(field in result.output, result.output)
            assertFalse(revisions.filterNot { it == field }.any { "$it:" in result.output }, result.output)
        }
    }

    @Test
    fun `a source withdrawal does not rewrite the first executor declaration`() {
        val builder = builder(ScriptedRuntime(ArrayDeque()))
        val tools = listOf(definition("Read", "path"), definition("Other", "value"))
        val before = builder.prepare(request(tools, emptyList()), "prefix", wire())
        val after = builder.prepare(request(tools, listOf(change("tool_removal", "Read"))), "prefix", wire())

        assertEquals(
            before.requestBody.getValue("input").jsonArray.first(),
            after.requestBody.getValue("input").jsonArray.first(),
        )
    }

    @Test
    fun `generated source timelines preserve the native catalog including unwithdrawn deferred tools`() = runTest {
        val random = Random(260127)
        repeat(32) { trial ->
            val known = linkedMapOf("Read" to "path", "Deferred" to "hidden")
            val available = known.keys.toMutableSet()
            val history = mutableListOf<String>()
            repeat(random.nextInt(1, 15)) { revision ->
                val name = listOf("Read", "Deferred", "Late", "Other").random(random)
                when (random.nextInt(3)) {
                    0 -> {
                        val field = "revision_${trial}_$revision"
                        known[name] = field
                        available.add(name)
                        history += addition(name, field)
                    }
                    1 -> if (name in known) {
                        available.remove(name)
                        history += change("tool_removal", name)
                    }
                    2 -> if (name in known) {
                        available.add(name)
                        history += change("tool_addition", name)
                    }
                }
            }
            val body = request(
                listOf(definition("Read", "path"), definition("Deferred", "hidden", deferred = true)),
                history,
            )
            val expected = known.filterKeys { it in available }.mapValues { schema(it.value) }
            val actual = catalogs().definitions(body, wire().requestBody)
                .associate { it.codeModeName to it.inputSchema }
            assertEquals(expected, actual, "trial=$trial history=$history")
            val runtime = CatalogRuntime()
            val prepared = builder(runtime).prepare(body, "generated-$trial", wire())
            val upstream = ArrayDeque(listOf(outerOutcome(), completedOutcome()))
            checkNotNull(prepared.roundInterceptor).intercept(prepared.requestBody.toString(), RecordingSink()) {
                RoundResult.Outcome(upstream.removeFirst())
            }
            assertEquals(available, runtime.tools, "trial=$trial")
            assertEquals(available, runtime.descriptions.keys, "trial=$trial")
        }
    }

    private fun builder(runtime: CodeModeRuntime) =
        CodexCodeModeTurnBuilder(bridge(runtime), media(), codeModeOnly = backendCodeModeOnly)

    private fun catalogs() = CodeModeTurnCatalogs(CodexCodeModeSettings(), NoCodeModeOriginalImageModels)

    private fun request(tools: List<JsonObject>, changes: List<String>): AnthropicTurnBody =
        AnthropicParse.parseAnthropicBody(
            """{"model":"gpt-6-astra","max_tokens":128,"tools":[${tools.joinToString()}],""" +
                """"messages":[{"role":"user","content":"start"}${changes.joinToString("") { "," + it }}]}""",
        )

    private fun wire(definitionField: String = "path"): BuiltTurn {
        val built = built("gpt-6-astra", lite = true)
        val input = built.requestBody.getValue("input").jsonArray
        val read = buildJsonObject {
            put("type", "function")
            put("name", "Read")
            put("description", definitionField)
            put("parameters", schema(definitionField))
        }
        val first = JsonObject(input.first().jsonObject + ("tools" to JsonArray(listOf(read))))
        return built.copy(
            requestBody = JsonObject(built.requestBody + ("input" to JsonArray(listOf(first) + input.drop(1)))),
        )
    }

    private fun definition(name: String, field: String, deferred: Boolean = false): JsonObject = buildJsonObject {
        put("name", name)
        put("description", field)
        put("input_schema", schema(field))
        if (deferred) put("defer_loading", true)
    }

    private fun schema(field: String): JsonObject = Json.parseToJsonElement(
        """{"type":"object","properties":{"$field":{"type":"string"}},"required":["$field"]}""",
    ).jsonObject

    private fun addition(name: String, field: String): String =
        """{"role":"system","content":[{"type":"tool_addition","tool":{"type":"tool_definition",""" +
            """"definition":${definition(name, field)}}}]}"""

    private fun change(type: String, name: String): String =
        """{"role":"system","content":[{"type":"$type","tool":{"type":"tool_reference","name":"$name"}}]}"""

    private class CatalogRuntime : CodeModeRuntime {
        var tools: Set<String> = emptySet()
        var descriptions: Map<String, String> = emptyMap()

        override suspend fun start(
            source: String,
            tools: Set<String>,
            descriptions: Map<String, String>,
        ): CodeModeCell {
            this.tools = tools
            this.descriptions = descriptions
            return ScriptedCell(ArrayDeque(listOf(CodeModeStep.Completed("done"))))
        }

        override fun close() = Unit
    }
}
