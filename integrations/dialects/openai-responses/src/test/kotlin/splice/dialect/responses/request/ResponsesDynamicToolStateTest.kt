package splice.dialect.responses.request

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.failure.Decline
import splice.core.failure.FailureCause
import splice.core.failure.RequestFeature
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.parse.AnthropicParse
import splice.core.turn.ReasoningDisplay
import splice.core.turn.WatchdogBudget
import splice.dialect.responses.ReasoningSettings
import splice.dialect.responses.ResponsesLiteQuirks
import splice.dialect.responses.ResponsesProvider
import splice.dialect.responses.ResponsesQuirks
import splice.upstream.ProviderLocations
import splice.upstream.ProviderName
import splice.upstream.ProviderTuning
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

private object DynamicToolTestAuth : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.ApiKey("test", header = "x-api-key", prefix = "")

    override suspend fun describe(): AuthDescription = AuthDescription(present = true, kind = "test")

    override suspend fun refresh(): Credentials = credentials()
}

private class DynamicToolTestProvider(
    lite: Boolean = false,
) : ResponsesProvider(
    tuning = ProviderTuning(
        name = ProviderName(key = "responses-test", label = "Responses test"),
        catalog = ModelCatalog(
            discoveryPrefix = "claude-test--",
            models = listOf(ModelEntry("test-model", "Test model", contextWindow = 128_000)),
            defaultContextWindow = 128_000,
        ),
        pinnedModel = "test-model",
        auth = DynamicToolTestAuth,
        locations = ProviderLocations(baseUrl = "https://example.invalid/v1"),
        watchdog = WatchdogBudget(5.seconds, 3.seconds, 30.seconds),
    ),
    reasoning = ReasoningSettings(ReasoningDisplay.OFF, false, null, null),
    quirks = ResponsesQuirks(
        providerTag = "test",
        lite = ResponsesLiteQuirks(responsesLiteModelRegex = if (lite) Regex("test-model") else null),
    ),
)

/**
 * Goal: a standard Responses head sees the same tool timeline the Anthropic request declares.
 * A top-level deferred tool stays absent until a source addition, and every declaration carries
 * the schema active at that exact source boundary.
 */
class ResponsesDynamicToolStateTest {
    private val provider = DynamicToolTestProvider()
    private val liteProvider = DynamicToolTestProvider(lite = true)

    @Test
    fun `defer loading keeps a source tool unavailable before any addition`() {
        val request = build(
            tools = listOf(
                tool("eager", "eager_schema"),
                tool("withheld", "withheld_schema", deferred = true),
            ),
            messages = listOf(user("Start.")),
        )

        assertEquals(listOf("eager"), topLevelToolNames(request))
        assertFalse("tool_choice" in request)
        assertFalse(input(request).any { type(it) == "additional_tools" })
    }

    @Test
    fun `a lite executor keeps deferred source tools in its provider-owned catalog`() {
        val request = buildLite(
            tools = listOf(
                tool("eager", "eager_schema"),
                tool("withheld", "withheld_schema", deferred = true),
            ),
            messages = listOf(user("Start.")),
        )
        val header = input(request).first { type(it) == "additional_tools" }
        val namespace = header.getValue("tools").jsonArray.single().jsonObject
        val names = namespace.getValue("tools").jsonArray.map {
            it.jsonObject.getValue("name").jsonPrimitive.content
        }

        assertFalse("tools" in request)
        assertEquals("namespace", type(namespace))
        assertEquals("functions", namespace.getValue("name").jsonPrimitive.content)
        assertEquals(listOf("eager", "withheld"), names)
    }

    @Test
    fun `a lite executor leaves source removal selection to its nested catalog`() {
        val body = AnthropicParse.parseAnthropicBody(
            requestJson(
                tools = listOf(
                    tool("withdrawn", "withdrawn_schema"),
                    tool("retained", "retained_schema"),
                ),
                messages = listOf(user("Start."), system(removal("withdrawn"))),
            ),
        )

        assertNull(liteProvider.requestRefusal(body))
        val request = liteProvider.buildTurn(body, false, "lite-withdrawn").requestBody

        assertFalse("tools" in request)
        assertEquals("auto", request.getValue("tool_choice").jsonPrimitive.content)
    }

    @Test
    fun `generated redefinitions keep declaration order schemas and following calls aligned`() {
        val random = Random(47)

        repeat(24) {
            val revisions = List(random.nextInt(1, 6)) { index -> "revision_${index}_${random.nextInt(100_000)}" }
            val messages = buildList {
                add(user("Start."))
                add(system(reference("withheld")))
                add(assistantToolUse("call_0", "withheld"))
                add(toolResult("call_0"))

                revisions.forEachIndexed { index, revision ->
                    add(system(inlineDefinition("withheld", revision)))
                    add(assistantToolUse("definition_$index", "withheld"))
                    add(toolResult("definition_$index"))
                    add(system(reference("withheld")))
                    add(assistantToolUse("reference_$index", "withheld"))
                    add(toolResult("reference_$index"))
                }
            }
            val request = build(
                tools = listOf(
                    tool("eager", "eager_schema"),
                    tool("withheld", "withheld_schema", deferred = true),
                ),
                messages = messages,
            )
            val expectedSchemas = listOf("withheld_schema") + revisions.flatMap { listOf(it, it) }
            val declarations = declarationTools(request)
            val items = input(request)
            val declarationIndexes = items.indices.filter { type(items[it]) == "additional_tools" }
            val callIndexes = items.indices.filter { type(items[it]) == "function_call" }

            assertEquals(listOf("eager"), topLevelToolNames(request))
            assertEquals(expectedSchemas, declarations.map(::schemaKey))
            assertEquals(expectedSchemas.size, callIndexes.size)
            assertTrue(declarationIndexes.indices.all { declarationIndexes[it] < callIndexes[it] })
            assertEquals(List(expectedSchemas.size) { "withheld" }, functionCallNames(request))
        }
    }

    @Test
    fun `an eager tool remains declared for its historical prefix after redefinition`() {
        val request = build(
            tools = listOf(tool("historical", "original_schema")),
            messages = listOf(
                user("Start."),
                assistantToolUse("before_redefinition", "historical"),
                toolResult("before_redefinition"),
                system(inlineDefinition("historical", "replacement_schema")),
                assistantToolUse("after_redefinition", "historical"),
                toolResult("after_redefinition"),
            ),
        )
        val items = input(request)
        val declarationIndex = items.indexOfFirst { type(it) == "additional_tools" }
        val calls = items.indices.filter { type(items[it]) == "function_call" }

        assertEquals(listOf("historical"), topLevelToolNames(request))
        assertEquals("original_schema", schemaKey(topLevelTool(request, "historical")))
        assertEquals(listOf("replacement_schema"), declarationTools(request).map(::schemaKey))
        assertTrue(calls[0] < declarationIndex)
        assertTrue(declarationIndex < calls[1])
    }

    @Test
    fun `an inline definition introduces a tool outside the initial catalog`() {
        val request = build(
            tools = listOf(tool("eager", "eager_schema")),
            messages = listOf(
                user("Start."),
                system(inlineDefinition("introduced", "introduced_schema")),
                assistantToolUse("introduced_call", "introduced"),
                toolResult("introduced_call"),
            ),
        )
        val items = input(request)
        val declarationIndex = items.indexOfFirst { type(it) == "additional_tools" }
        val callIndex = items.indexOfFirst { type(it) == "function_call" }

        assertEquals(listOf("eager"), topLevelToolNames(request))
        assertEquals(listOf("introduced_schema"), declarationTools(request).map(::schemaKey))
        assertEquals(listOf("introduced"), functionCallNames(request))
        assertTrue(declarationIndex < callIndex)
    }

    @Test
    fun `an inline introduction supplies the schema for its later source reference`() {
        val request = build(
            tools = emptyList(),
            messages = listOf(
                user("Start."),
                system(inlineDefinition("introduced", "introduced_schema")),
                assistantToolUse("introduced_call", "introduced"),
                toolResult("introduced_call"),
                system(reference("introduced")),
                assistantToolUse("reference_call", "introduced"),
                toolResult("reference_call"),
            ),
        )
        val items = input(request)
        val declarationIndexes = items.indices.filter { type(items[it]) == "additional_tools" }
        val callIndexes = items.indices.filter { type(items[it]) == "function_call" }

        assertEquals(listOf("introduced_schema", "introduced_schema"), declarationTools(request).map(::schemaKey))
        assertEquals(listOf("introduced", "introduced"), functionCallNames(request))
        assertTrue(declarationIndexes.indices.all { declarationIndexes[it] < callIndexes[it] })
    }

    @Test
    fun `a flat addition is not mistaken for a documented inline definition`() {
        val request = build(
            tools = emptyList(),
            messages = listOf(
                user("Start."),
                system(
                    """{"type":"tool_addition","tool":{"name":"flat","input_schema":{"type":"object"}}}""",
                ),
                assistant("Acknowledged."),
            ),
        )
        val items = input(request)

        assertFalse(items.any { type(it) == "additional_tools" })
        assertTrue(
            items.any {
                it["role"]?.jsonPrimitive?.content == "system" &&
                    it["content"]?.jsonPrimitive?.content.orEmpty().contains("schema unavailable")
            },
        )
    }

    @Test
    fun `a removal restricts automatic choice without rewriting initial declarations`() {
        val body = AnthropicParse.parseAnthropicBody(
            requestJson(
                tools = listOf(
                    tool("withdrawn", "withdrawn_schema"),
                    tool("retained", "retained_schema"),
                ),
                messages = listOf(
                    user("Start."),
                    system(removal("withdrawn")),
                ),
                toolChoice = """{"type":"auto"}""",
            ),
        )

        assertNull(provider.requestRefusal(body))
        val request = provider.buildTurn(body, compact = false, sessionId = "withdrawn").requestBody
        val choice = request.getValue("tool_choice").jsonObject

        assertEquals(listOf("withdrawn", "retained"), topLevelToolNames(request))
        assertEquals("allowed_tools", choice.getValue("type").jsonPrimitive.content)
        assertEquals("auto", choice.getValue("mode").jsonPrimitive.content)
        assertEquals(listOf("retained"), allowedToolNames(choice))
        assertFalse(input(request).any { type(it) == "remove_tools" })
    }

    @Test
    fun `source availability preserves required named and none choice semantics`() {
        val tools = listOf(
            tool("withdrawn", "withdrawn_schema"),
            tool("retained", "retained_schema"),
        )
        val messages = listOf(user("Start."), system(removal("withdrawn")))

        val required = AnthropicParse.parseAnthropicBody(
            requestJson(tools, messages, toolChoice = """{"type":"required"}"""),
        )
        assertNull(provider.requestRefusal(required))
        val requiredChoice = provider.buildTurn(required, false, "required").requestBody
            .getValue("tool_choice").jsonObject
        assertEquals("allowed_tools", requiredChoice.getValue("type").jsonPrimitive.content)
        assertEquals("required", requiredChoice.getValue("mode").jsonPrimitive.content)
        assertEquals(listOf("retained"), allowedToolNames(requiredChoice))

        val named = AnthropicParse.parseAnthropicBody(
            requestJson(tools, messages, toolChoice = """{"type":"tool","name":"retained"}"""),
        )
        assertNull(provider.requestRefusal(named))
        val namedChoice = provider.buildTurn(named, false, "named").requestBody
            .getValue("tool_choice").jsonObject
        assertEquals("function", namedChoice.getValue("type").jsonPrimitive.content)
        assertEquals("retained", namedChoice.getValue("name").jsonPrimitive.content)

        val none = AnthropicParse.parseAnthropicBody(
            requestJson(tools, messages, toolChoice = """{"type":"none"}"""),
        )
        assertNull(provider.requestRefusal(none))
        assertEquals(
            "none",
            provider.buildTurn(none, false, "none").requestBody.getValue("tool_choice").jsonPrimitive.content,
        )

        val removedNamed = AnthropicParse.parseAnthropicBody(
            requestJson(tools, messages, toolChoice = """{"type":"tool","name":"withdrawn"}"""),
        )
        assertEquals(
            FailureCause.Splice.Declined(Decline.Untranslatable(RequestFeature.TOOL_CHOICE)),
            provider.requestRefusal(removedNamed)?.cause,
        )
    }

    @Test
    fun `a bare loaded reference in a tool result declares a deferred schema at its result boundary`() {
        val request = build(
            tools = listOf(
                tool("eager", "eager_schema"),
                tool("deferred", "deferred_schema", deferred = true),
            ),
            messages = listOf(
                user("Start."),
                assistantToolUse("load", "eager"),
                toolResultWithLoadedTool("load", "deferred"),
                assistantToolUse("deferred_call", "deferred"),
                toolResult("deferred_call"),
            ),
            toolChoice = """{"type":"auto"}""",
        )
        val items = input(request)
        val outputIndex = items.indexOfFirst { type(it) == "function_call_output" }
        val declarationIndex = items.indexOfFirst { type(it) == "additional_tools" }
        val deferredCallIndex = items.indexOfLast { type(it) == "function_call" }
        val choice = request.getValue("tool_choice").jsonObject

        assertEquals(listOf("eager"), topLevelToolNames(request))
        assertEquals(listOf("deferred_schema"), declarationTools(request).map(::schemaKey))
        assertTrue(outputIndex < declarationIndex)
        assertTrue(declarationIndex < deferredCallIndex)
        assertEquals("allowed_tools", choice.getValue("type").jsonPrimitive.content)
        assertEquals(listOf("eager", "deferred"), allowedToolNames(choice))
    }

    @Test
    fun `a cached bare loaded reference keeps the source schema at its own boundary`() {
        val tools = listOf(
            tool("loader", "loader_schema"),
            tool("withheld", "initial_schema", deferred = true),
        )
        val oldRequest = build(
            tools = tools,
            messages = listOf(
                user("Start."),
                system(inlineDefinition("withheld", "old_schema")),
                assistantToolUse("loader", "loader"),
                toolResultWithLoadedTool("loader", "withheld"),
            ),
        )
        val revisedRequest = build(
            tools = tools,
            messages = listOf(
                user("Start."),
                system(inlineDefinition("withheld", "revised_schema")),
                assistantToolUse("loader", "loader"),
                toolResultWithLoadedTool("loader", "withheld"),
            ),
        )

        assertEquals(listOf("old_schema", "old_schema"), declarationTools(oldRequest).map(::schemaKey))
        assertEquals(listOf("revised_schema", "revised_schema"), declarationTools(revisedRequest).map(::schemaKey))
    }

    @Test
    fun `a re-offered tool restores its default choice after a withdrawal`() {
        val body = AnthropicParse.parseAnthropicBody(
            requestJson(
                tools = listOf(tool("historical", "original_schema")),
                messages = listOf(
                    user("Start."),
                    system(reference("historical")),
                    assistantToolUse("before_removal", "historical"),
                    toolResult("before_removal"),
                    system(removal("historical")),
                    assistant("It is unavailable."),
                    user("Offer it again."),
                    system(reference("historical")),
                    assistantToolUse("after_reoffer", "historical"),
                    toolResult("after_reoffer"),
                ),
                toolChoice = """{"type":"auto"}""",
            ),
        )

        assertNull(provider.requestRefusal(body))
        val request = provider.buildTurn(body, false, "reoffered").requestBody

        assertEquals("auto", request.getValue("tool_choice").jsonPrimitive.content)
        assertEquals(listOf("original_schema", "original_schema"), declarationTools(request).map(::schemaKey))
        assertEquals(listOf("historical", "historical"), functionCallNames(request))
    }

    @Test
    fun `generated source timelines preserve schemas and current automatic availability`() {
        val random = Random(211)

        repeat(24) {
            val eagerRevision = "eager_revision_${random.nextInt(100_000)}"
            val introducedRevision = "introduced_revision_${random.nextInt(100_000)}"
            val introducedRedefinition = "introduced_redefinition_${random.nextInt(100_000)}"
            val reofferByDefinition = random.nextBoolean()
            val eagerAddition = if (reofferByDefinition) {
                inlineDefinition("eager", eagerRevision)
            } else {
                reference("eager")
            }
            val eagerSchema = if (reofferByDefinition) eagerRevision else "eager_schema"
            val messages = sourceTimeline(eagerAddition, introducedRevision, introducedRedefinition)
            val request = build(
                tools = listOf(
                    tool("eager", "eager_schema"),
                    tool("withheld", "withheld_schema", deferred = true),
                ),
                messages = messages,
                toolChoice = """{"type":"auto"}""",
            )
            val items = input(request)
            val declarationIndexes = items.indices.filter { type(items[it]) == "additional_tools" }
            val callIndexes = items.indices.filter { type(items[it]) == "function_call" }
            val choice = request.getValue("tool_choice").jsonObject

            assertEquals(listOf("eager"), topLevelToolNames(request))
            assertEquals(
                listOf("withheld_schema", eagerSchema, introducedRevision, "withheld_schema", introducedRedefinition),
                declarationTools(request).map(::schemaKey),
            )
            assertEquals(
                listOf("eager", "withheld", "eager", "introduced", "withheld", "introduced"),
                functionCallNames(request),
            )
            assertTrue(callIndexes[0] < declarationIndexes[0])
            assertTrue(declarationIndexes.indices.drop(1).all { declarationIndexes[it] < callIndexes[it + 1] })
            assertEquals("allowed_tools", choice.getValue("type").jsonPrimitive.content)
            assertEquals("auto", choice.getValue("mode").jsonPrimitive.content)
            assertEquals(listOf("eager", "withheld", "introduced"), allowedToolNames(choice))
        }
    }

    private fun build(
        tools: List<String>,
        messages: List<String>,
        toolChoice: String? = null,
    ): JsonObject =
        provider.buildTurn(
            AnthropicParse.parseAnthropicBody(requestJson(tools, messages, toolChoice)),
            compact = false,
            sessionId = "dynamic-tools",
        ).requestBody

    private fun buildLite(tools: List<String>, messages: List<String>): JsonObject =
        liteProvider.buildTurn(
            AnthropicParse.parseAnthropicBody(requestJson(tools, messages)),
            compact = false,
            sessionId = "dynamic-tools",
        ).requestBody
}

private fun sourceTimeline(
    eagerAddition: String,
    introducedRevision: String,
    introducedRedefinition: String,
): List<String> = listOf(
    user("Start."),
    assistantToolUse("loader", "eager"),
    toolResultWithLoadedTool("loader", "withheld"),
    assistantToolUse("withheld_initial", "withheld"),
    toolResult("withheld_initial"),
    system(removal("eager")),
    system(eagerAddition),
    assistantToolUse("eager_reoffer", "eager"),
    toolResult("eager_reoffer"),
    system(inlineDefinition("introduced", introducedRevision)),
    assistantToolUse("introduced_initial", "introduced"),
    toolResult("introduced_initial"),
    system(removal("withheld")),
    system(reference("withheld")),
    assistantToolUse("withheld_reoffer", "withheld"),
    toolResult("withheld_reoffer"),
    system(inlineDefinition("introduced", introducedRedefinition)),
    assistantToolUse("introduced_redefinition", "introduced"),
    toolResult("introduced_redefinition"),
)

private fun input(request: JsonObject): List<JsonObject> =
    request.getValue("input").jsonArray.map { it.jsonObject }

private fun topLevelToolNames(request: JsonObject): List<String> =
    request["tools"]?.jsonArray.orEmpty().map { it.jsonObject.getValue("name").jsonPrimitive.content }

private fun allowedToolNames(choice: JsonObject): List<String> =
    choice.getValue("tools").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content }

private fun topLevelTool(request: JsonObject, name: String): JsonObject =
    request.getValue("tools").jsonArray
        .single { it.jsonObject.getValue("name").jsonPrimitive.content == name }
        .jsonObject

private fun declarationTools(request: JsonObject): List<JsonObject> =
    input(request)
        .filter { type(it) == "additional_tools" }
        .map { it.getValue("tools").jsonArray.single().jsonObject }

private fun functionCallNames(request: JsonObject): List<String> =
    input(request)
        .filter { type(it) == "function_call" }
        .map { it.getValue("name").jsonPrimitive.content }

private fun schemaKey(tool: JsonObject): String =
    tool.getValue("parameters").jsonObject.getValue("properties").jsonObject.keys.single()

private fun type(item: JsonObject): String? = item["type"]?.jsonPrimitive?.content

private fun requestJson(
    tools: List<String>,
    messages: List<String>,
    toolChoice: String? = null,
): String {
    val choice = toolChoice?.let { "\"tool_choice\":$it," }.orEmpty()
    return """
        {"model":"claude-test--test-model","max_tokens":256,"tools":[${tools.joinToString()}],
         $choice"messages":[${messages.joinToString()}]}
    """.trimIndent()
}

private fun tool(name: String, schemaKey: String, deferred: Boolean = false): String =
    """
        {"name":"$name","description":"$schemaKey","input_schema":{
          "type":"object","properties":{"$schemaKey":{"type":"string"}},"required":["$schemaKey"]
        }${if (deferred) ",\"defer_loading\":true" else ""}}
    """.trimIndent()

private fun user(text: String): String = """{"role":"user","content":"$text"}"""

private fun assistant(text: String): String = """{"role":"assistant","content":"$text"}"""

private fun system(block: String): String = """{"role":"system","content":[$block]}"""

private fun reference(name: String): String =
    """{"type":"tool_addition","tool":{"type":"tool_reference","name":"$name"}}"""

private fun removal(name: String): String =
    """{"type":"tool_removal","tool":{"type":"tool_reference","name":"$name"}}"""

private fun inlineDefinition(name: String, schemaKey: String): String =
    """{"type":"tool_addition","tool":{"type":"tool_definition","definition":${tool(name, schemaKey)}}}"""

private fun assistantToolUse(id: String, name: String): String =
    """{"role":"assistant","content":[{"type":"tool_use","id":"$id","name":"$name","input":{}}]}"""

private fun toolResult(id: String): String =
    """{"role":"user","content":[{"type":"tool_result","tool_use_id":"$id","content":"ok"}]}"""

private fun toolResultWithLoadedTool(id: String, name: String): String =
    """
        {"role":"user","content":[{"type":"tool_result","tool_use_id":"$id","content":[
          {"type":"tool_reference","tool_name":"$name"}
        ]}]}
    """.trimIndent()
