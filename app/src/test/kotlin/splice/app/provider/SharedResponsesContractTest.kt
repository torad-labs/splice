package splice.app.provider

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.TokenUrlRefreshCall
import splice.core.auth.Credentials
import splice.core.auth.RefreshAttempt
import splice.core.config.ConfigService
import splice.core.config.StatePaths
import splice.core.model.DiscoveredModel
import splice.core.model.HeadDiscoveredModels
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.model.ModelRuntimeMetadata
import splice.core.parse.AnthropicParse
import splice.core.topology.AuthConfig
import splice.core.topology.Dialect
import splice.core.topology.HeadConfig
import splice.core.topology.ProviderConfig
import splice.core.turn.WatchdogBudget
import java.nio.file.Path
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

// Goal: every Responses head retains typed tool results, without acquiring Codex's executor or routing policy.
class SharedResponsesContractTest {
    @Test
    fun `non-Codex heads keep mixed tool result content on the matching function result`(@TempDir tmp: Path) = runTest {
        for (provider in listOf("openai", "xai", "custom")) {
            val wired = assembly(tmp.resolve(provider), backgroundScope).buildProvider(context(tmp, provider))
            try {
                val built = wired.provider.buildTurn(AnthropicParse.parseAnthropicBody(mixedResult), false, "shared")
                val input = built.requestBody.getValue("input").jsonArray.map { it.jsonObject }
                val result = input.single { it["type"]?.jsonPrimitive?.content == "function_call_output" }
                assertEquals("call_record", result["call_id"]?.jsonPrimitive?.content, provider)
                val output = result["output"]
                assertTrue(output is JsonArray, "$provider retains typed output instead of flattening it: $result")
                val content = requireNotNull(output as? JsonArray).map { it.jsonObject }
                assertEquals(listOf("input_text", "input_image", "input_text"), content.map(::type), provider)
                assertEquals("before image", content[0]["text"]?.jsonPrimitive?.content, provider)
                assertEquals(
                    "https://example.invalid/record.png",
                    content[1]["image_url"]?.jsonPrimitive?.content,
                    provider,
                )
                assertEquals("after image", content[2]["text"]?.jsonPrimitive?.content, provider)
            } finally {
                wired.provider.onHeadStop()
            }
        }
    }

    @Test
    fun `Responses dialect URLs provider names and GPT model names do not opt into the executor`(@TempDir tmp: Path) =
        runTest {
            for (provider in listOf("openai", "codex", "xai", "custom")) {
                for (url in listOf("https://api.openai.com/v1", "https://chatgpt.com/backend-api/codex")) {
                    val ctx = context(tmp, provider).let { it.copy(providerCfg = it.providerCfg.copy(baseUrl = url)) }
                    val wired = assembly(tmp.resolve(provider), backgroundScope).buildProvider(ctx)
                    try {
                        val built = wired.provider.buildTurn(
                            AnthropicParse.parseAnthropicBody(mixedResult),
                            false,
                            "shared",
                        )
                        val body = built.requestBody
                        val tools = body.getValue("tools").jsonArray.map { it.jsonObject }
                        assertEquals(listOf("lookup_record"), tools.map { it["name"]?.jsonPrimitive?.content })
                        assertEquals(listOf("function"), tools.map(::type))
                        assertNull(built.roundInterceptor)
                        assertNull(wired.provider.foldPolicy(built.meta))
                        assertFalse("client_metadata" in body)
                        val headers = wired.provider.extraHeaders(Credentials.ApiKey("synthetic"))
                        assertFalse((headers.keys + built.extraHeaders.keys).any { it.startsWith("x-codex", true) })
                        assertFalse("ChatGPT-Account-ID" in headers)
                    } finally {
                        wired.provider.onHeadStop()
                    }
                }
            }
        }

    @Test
    fun `source document citations are refused instead of silently promising unsupported annotations`(
        @TempDir tmp: Path,
    ) =
        runTest {
            val request = AnthropicParse.parseAnthropicBody(
                """
                {"model":"claude-shared--gpt-6.1-sol","max_tokens":128,"messages":[
                  {"role":"user","content":[{"type":"document","citations":{"enabled":true},
                    "source":{"type":"text","media_type":"text/plain","data":"Reference notes"}}]}
                ]}
                """.trimIndent(),
            )
            val wired = assembly(tmp, backgroundScope).buildProvider(context(tmp, "custom"))
            try {
                assertTrue(
                    wired.provider.requestRefusal(request) != null,
                    "the source-specific citation contract must not disappear during generic Responses conversion",
                )
            } finally {
                wired.provider.onHeadStop()
            }
        }

    @Test
    fun `source attribution changes only the public safety identifier across Responses heads`(@TempDir tmp: Path) =
        runTest {
            val random = Random(261027)
            val alphabet = "abczAZ019-_:é\"\\"
            val identifiers = listOf<String?>(null) + List(48) {
                buildString {
                    repeat(random.nextInt(1, 97)) { append(alphabet[random.nextInt(alphabet.length)]) }
                }
            }
            val source = AnthropicParse.parseAnthropicBody(mixedResult)
            for (provider in listOf("openai", "codex", "xai", "custom")) {
                val wired = assembly(tmp.resolve(provider), backgroundScope).buildProvider(context(tmp, provider))
                try {
                    val baseline = wired.provider.buildTurn(source, false, "attribution").requestBody
                    assertFalse("safety_identifier" in baseline, "attribution is not inferred from session identity")
                    for (identifier in identifiers) {
                        val metadata = JsonObject(mapOf("user_id" to JsonPrimitive(identifier)))
                        val raw = JsonObject(source.raw + ("metadata" to metadata))
                        val request = AnthropicParse.parseAnthropicBody(raw.toString())
                        assertNull(wired.provider.requestRefusal(request), provider)
                        val built = wired.provider.buildTurn(request, false, "attribution")
                        val expected = identifier?.let {
                            JsonObject(baseline + ("safety_identifier" to JsonPrimitive(it)))
                        } ?: baseline
                        assertEquals(expected, built.requestBody, provider)
                        assertNull(built.roundInterceptor, "attribution must not select execution for $provider")
                    }
                } finally {
                    wired.provider.onHeadStop()
                }
            }
        }

    @Test
    fun `an empty stop list does not reject an ordinary Responses request`(@TempDir tmp: Path) = runTest {
        val request = AnthropicParse.parseAnthropicBody(
            """
            {"model":"claude-shared--gpt-6.1-sol","max_tokens":128,"stop_sequences":[],
             "messages":[{"role":"user","content":"Hello"}]}
            """.trimIndent(),
        )
        val wired = assembly(tmp, backgroundScope).buildProvider(context(tmp, "openai"))
        try {
            assertNull(wired.provider.requestRefusal(request), "an empty list requests no stopping constraint")
            val body = wired.provider.buildTurn(request, false, "empty-stop").requestBody
            assertFalse("stop_sequences" in body)
        } finally {
            wired.provider.onHeadStop()
        }
    }

    @Test
    fun `a non-Codex head withdraws a tool without rewriting its initial declarations`(@TempDir tmp: Path) = runTest {
        val request = AnthropicParse.parseAnthropicBody(
            """
            {"model":"claude-shared--gpt-6.1-sol","max_tokens":128,"tools":[
              {"name":"lookup_record","input_schema":{"type":"object"}},
              {"name":"record_status","input_schema":{"type":"object"}}
            ],"messages":[
              {"role":"user","content":"Use the available record tools"},
              {"role":"system","content":[{"type":"tool_removal",
                "tool":{"type":"tool_reference","name":"lookup_record"}}]}
            ]}
            """.trimIndent(),
        )
        val wired = assembly(tmp, backgroundScope).buildProvider(context(tmp, "custom"))
        try {
            assertNull(wired.provider.requestRefusal(request), "a supported public restriction must not be declined")
            val built = wired.provider.buildTurn(request, false, "withdrawn")
            val body = built.requestBody
            assertEquals(
                listOf("lookup_record", "record_status"),
                body.getValue("tools").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content },
            )
            val choice = body.getValue("tool_choice").jsonObject
            assertEquals("allowed_tools", choice.getValue("type").jsonPrimitive.content)
            assertEquals("auto", choice.getValue("mode").jsonPrimitive.content)
            assertEquals(
                listOf("record_status"),
                choice.getValue("tools").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content },
            )
            assertFalse(
                body.getValue("input").jsonArray.any {
                    it.jsonObject["type"]?.jsonPrimitive?.content == "remove_tools"
                },
            )
            assertNull(built.roundInterceptor)
            assertFalse("client_metadata" in body)
        } finally {
            wired.provider.onHeadStop()
        }
    }

    @Test
    fun `programmatic-only source tools are refused instead of activating a generic executor`(@TempDir tmp: Path) =
        runTest {
            val request = AnthropicParse.parseAnthropicBody(
                """
                {"model":"claude-shared--gpt-6.1-sol","max_tokens":128,"tools":[
                  {"name":"lookup_record","input_schema":{"type":"object"},
                   "allowed_callers":["code_execution_20250825"]}
                ],"messages":[{"role":"user","content":"Look up a record"}]}
                """.trimIndent(),
            )
            for (provider in listOf("openai", "codex", "xai", "custom")) {
                val wired = assembly(tmp.resolve(provider), backgroundScope).buildProvider(context(tmp, provider))
                try {
                    assertTrue(
                        wired.provider.requestRefusal(request) != null,
                        "$provider must not offer a direct function or invent an executor for a non-direct source tool",
                    )
                } finally {
                    wired.provider.onHeadStop()
                }
            }
        }

    @Test
    fun `a non-Codex head keeps a mid-conversation system instruction at its original boundary`(@TempDir tmp: Path) =
        runTest {
            val wired = assembly(tmp, backgroundScope).buildProvider(context(tmp, "custom"))
            try {
                val request = AnthropicParse.parseAnthropicBody(
                    """
                    {"model":"claude-shared--gpt-6.1-sol","max_tokens":128,"system":"Base policy","messages":[
                      {"role":"user","content":"Start"},
                      {"role":"assistant","content":"First answer"},
                      {"role":"user","content":"Continue"},
                      {"role":"system","content":"Scoped instruction"},
                      {"role":"assistant","content":"Second answer"},
                      {"role":"user","content":"Summarize"}
                    ]}
                    """.trimIndent(),
                )
                val body = wired.provider.buildTurn(request, false, "ordered").requestBody
                val input = body.getValue("input").jsonArray.map { it.jsonObject }
                assertEquals(
                    listOf("user", "assistant", "user", "system", "assistant", "user"),
                    input.map { it.getValue("role").jsonPrimitive.content },
                )
                val content = input[3].getValue("content")
                val instruction = if (content is JsonArray) {
                    content.joinToString("") { it.jsonObject.getValue("text").jsonPrimitive.content }
                } else {
                    content.jsonPrimitive.content
                }
                assertEquals("Scoped instruction", instruction)
                assertEquals("Base policy", body.getValue("instructions").jsonPrimitive.content)
            } finally {
                wired.provider.onHeadStop()
            }
        }

    @Test
    fun `a non-Codex head declares an inline tool definition at its original message boundary`(@TempDir tmp: Path) =
        runTest {
            val wired = assembly(tmp, backgroundScope).buildProvider(context(tmp, "custom"))
            try {
                val request = AnthropicParse.parseAnthropicBody(
                    """
                    {"model":"claude-shared--gpt-6.1-sol","max_tokens":128,
                     "tools":[{"name":"lookup_record","input_schema":{"type":"object","properties":{}}}],
                     "messages":[
                       {"role":"user","content":"Start"},
                       {"role":"system","content":[{
                         "type":"tool_addition",
                         "tool":{"type":"tool_definition","definition":{
                           "name":"db_query","description":"Query the database",
                           "input_schema":{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}
                         }}
                       }]},
                       {"role":"user","content":"Run the database query"}
                     ]}
                    """.trimIndent(),
                )
                val body = wired.provider.buildTurn(request, false, "dynamic").requestBody
                val input = body.getValue("input").jsonArray.map { it.jsonObject }
                assertEquals("user", input[0]["role"]?.jsonPrimitive?.content)
                assertEquals(
                    "additional_tools",
                    input[1]["type"]?.jsonPrimitive?.content,
                    "dynamic tool item: ${input[1]}",
                )
                assertEquals("developer", input[1]["role"]?.jsonPrimitive?.content)
                val tool = input[1].getValue("tools").jsonArray.single().jsonObject
                assertEquals("db_query", tool["name"]?.jsonPrimitive?.content)
                assertEquals(
                    "string",
                    tool.getValue("parameters").jsonObject.getValue("properties").jsonObject
                        .getValue("query").jsonObject["type"]?.jsonPrimitive?.content,
                )
                assertEquals(
                    listOf("lookup_record"),
                    body.getValue("tools").jsonArray.map { it.jsonObject["name"]?.jsonPrimitive?.content },
                )
            } finally {
                wired.provider.onHeadStop()
            }
        }

    @Test
    fun `ordinary Responses heads preserve non-strict function schemas instead of opting into normalization`(
        @TempDir tmp: Path,
    ) = runTest {
        val wired = assembly(tmp, backgroundScope).buildProvider(context(tmp, "openai"))
        try {
            for (strictField in listOf("", ",\"strict\":false")) {
                val request = AnthropicParse.parseAnthropicBody(
                    """
                    {"model":"claude-shared--gpt-6.1-sol","max_tokens":128,
                     "tools":[{"name":"lookup_record",
                       "input_schema":{"type":"object","properties":{"optional_query":{"type":"string"}}}$strictField
                     }],
                     "messages":[{"role":"user","content":"Look up a record"}]}
                    """.trimIndent(),
                )
                val body = wired.provider.buildTurn(request, false, "non-strict").requestBody
                val tool = body.getValue("tools").jsonArray.single().jsonObject
                assertEquals("false", tool["strict"]?.jsonPrimitive?.content)
                assertFalse("required" in tool.getValue("parameters").jsonObject)
            }
        } finally {
            wired.provider.onHeadStop()
        }
    }

    private fun type(item: JsonObject): String = item.getValue("type").jsonPrimitive.content

    private fun assembly(root: Path, scope: CoroutineScope): ProviderAssembly = ProviderAssembly(
        statePaths = StatePaths(baseOverride = root),
        probeScope = scope,
        log = {},
        refreshCall = TokenUrlRefreshCall { _, _ -> RefreshAttempt.Denied("test-denied") },
    )

    private fun context(root: Path, provider: String): ProviderBuild {
        val state = StatePaths(baseOverride = root.resolve(provider))
        val config = ConfigService(state, envReader = { null })
        return ProviderBuild(
            key = provider,
            head = HeadConfig(
                provider = provider,
                port = 0,
                discoveryPrefix = "claude-shared--",
                pinnedModel = "gpt-6.1-sol",
            ),
            providerCfg = ProviderConfig(
                dialect = Dialect.OPENAI_RESPONSES,
                baseUrl = "https://example.invalid/v1",
                auth = AuthConfig(kind = "api-key"),
            ),
            catalog = ModelCatalog(
                discoveryPrefix = "claude-shared--",
                models = listOf(ModelEntry(id = "gpt-6.1-sol", contextWindow = 200_000)),
                defaultContextWindow = 200_000,
            ),
            faultPlan = UpstreamFaultPlan(
                watchdog = WatchdogBudget(60.seconds, 60.seconds, 600.seconds),
                loginCommand = "test login",
            ),
            cfg = config.getConfig(provider),
            roster = PublishedRoster(
                discovered = HeadDiscoveredModels {
                    listOf(
                        DiscoveredModel(
                            "gpt-6.1-sol",
                            runtimeMetadata = ModelRuntimeMetadata(toolMode = "code_mode_only"),
                        ),
                    )
                },
            ),
        )
    }

    private val mixedResult = """
        {
          "model":"claude-shared--gpt-6.1-sol","max_tokens":128,
          "tools":[{"name":"lookup_record","description":"Look up a record","input_schema":{"type":"object","properties":{}}}],
          "messages":[
            {"role":"user","content":"Look up the record."},
            {"role":"assistant","content":[{"type":"tool_use","id":"call_record","name":"lookup_record","input":{}}]},
            {"role":"user","content":[{"type":"tool_result","tool_use_id":"call_record","content":[
              {"type":"text","text":"before image"},
              {"type":"image","source":{"type":"url","url":"https://example.invalid/record.png"}},
              {"type":"text","text":"after image"}
            ]}]}
          ]
        }
    """.trimIndent()
}
