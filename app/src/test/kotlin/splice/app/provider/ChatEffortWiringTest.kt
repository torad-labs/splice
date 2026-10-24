package splice.app.provider

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.parse.AnthropicParse
import splice.core.turn.WatchdogBudget
import splice.provider.openai.ApiKeyAuthProvider
import splice.provider.openai.OpenAiChatProvider
import splice.topology.TopologyLoader
import splice.upstream.ProviderLocations
import splice.upstream.ProviderName
import splice.upstream.ProviderTuning
import kotlin.time.Duration.Companion.seconds

class ChatEffortWiringTest {
    @Test
    fun `client max effort survives the configured production chat provider`() {
        val built = provider(configured = true).buildTurn(body("\"output_config\":{\"effort\":\"max\"}"), false, null)
        assertEquals("max", built.requestBody["reasoning_effort"]?.jsonPrimitive?.content)
        assertEquals("max", built.meta.reasoning.effort)
    }

    @Test
    fun `a provider without the vocabulary preserves its exact request bytes`() {
        val provider = provider(configured = false)
        val expected = """{"model":"GLM-5.3-Flash","messages":[{"role":"user","content":"hello"}],""" +
            """"stream":true,"reasoning_effort":"high","reasoning":{"effort":"high"}}"""
        for (effort in listOf("low", "high", "max")) {
            val built = provider.buildTurn(body("\"output_config\":{\"effort\":\"$effort\"}"), false, null)
            assertEquals(expected, built.requestBody.toString(), effort)
        }
    }

    @Test
    fun `the vocabulary maps every requested level and thinking off`() {
        val provider = provider(configured = true)
        val levels = mapOf(
            "none" to "low", "minimal" to "low", "light" to "low", "low" to "low",
            "medium" to "high", "high" to "high", "xhigh" to "max", "max" to "max", "ultra" to "max",
        )
        for ((effort, expected) in levels) {
            assertEffort(provider, "\"output_config\":{\"effort\":\"$effort\"}", expected)
        }
        assertEffort(provider, "", "low", thinking = """{"type":"disabled"}""")
        assertEffort(
            provider,
            "\"output_config\":{\"effort\":\"max\"}",
            "max",
            thinking = """{"type":"disabled"}""",
        )
        assertEffort(
            provider,
            "\"output_config\":{\"effort\":\"unknown\"}",
            "max",
            thinking = """{"type":"disabled"}""",
        )
        assertEffort(provider, "\"output_config\":{\"effort\":\" HIGH \"}", "high")
    }

    @Test
    fun `each raw alias reaches the wire and output config wins the ordered chain`() {
        val provider = provider(configured = true)
        val aliases = listOf(
            "\"output_config\":{\"effort\":\"low\"}",
            "\"effort\":\"low\"",
            "\"reasoning_effort\":\"low\"",
            "\"metadata\":{\"effort\":\"low\"}",
            "\"reasoning\":{\"effort\":\"low\"}",
        )
        for (alias in aliases) assertEffort(provider, alias, "low")
        for (start in aliases.indices) {
            val fields = aliases.drop(start).mapIndexed { at, alias ->
                if (at == 0) alias else alias.replace("low", "max")
            }.joinToString(",")
            assertEffort(provider, fields, "low")
        }
        assertEffort(provider, "\"output_config\":{\"effort\":\"unknown\"},\"effort\":\"low\"", "max")
    }

    @Test
    fun `missing unknown and malformed effort fall back without leaking loose fields`() {
        val provider = provider(configured = true)
        val fields = listOf(
            "",
            "\"output_config\":{\"effort\":\"unknown\"}",
            "\"effort\":42",
            "\"output_config\":[],\"metadata\":false,\"reasoning\":\"bad\"",
            "\"output_config\":{\"effort\":null}",
        )
        for (field in fields) assertEffort(provider, field, "max")
        assertEffort(provider, "\"output_config\":[],\"metadata\":{\"effort\":\"low\"}", "low")
        for (budget in listOf(0, 1, 8000, 32000, 127999)) {
            assertEffort(provider, "", "max", thinking = """{"type":"enabled","budget_tokens":$budget}""")
        }
        val privateBody = body("\"output_config\":{\"effort\":\"low\"},\"private_field\":\"no\"")
        val built = provider.buildTurn(privateBody, false, null)
        assertFalse(built.requestBody.containsKey("output_config"))
        assertFalse(built.requestBody.containsKey("private_field"))
    }

    @Test
    fun `configured compaction inherits byte-identical session effort`() {
        val provider = provider(configured = true)
        for (effort in listOf("low", "high", "max")) {
            val body = body("\"output_config\":{\"effort\":\"$effort\"}")
            val turn = provider.buildTurn(body, false, "session")
            val compact = provider.buildTurn(body, true, "session")
            assertEquals(turn.requestBody.toString(), compact.requestBody.toString())
            assertEquals(effort, compact.meta.reasoning.effort)
        }
    }

    @Test
    fun `thinking disabled omits effort when the vocabulary has no none level`() {
        val vocabulary = vocabularyToml.replace("none = \"low\"\n", "")
        val provider = providerFromToml(baseToml + vocabulary)
        val built = provider.buildTurn(body("", """{"type":"disabled"}"""), false, null)
        assertFalse(built.requestBody.containsKey("reasoning_effort"), built.requestBody.toString())
        assertFalse(built.requestBody.containsKey("reasoning"), built.requestBody.toString())
        assertEquals("n/a", built.meta.reasoning.effort)
        assertEffort(provider, "\"effort\":\"low\"", "low", thinking = """{"type":"disabled"}""")
    }

    @Test
    fun `mixed case configured levels and client levels resolve to the configured value not default`() {
        val vocabulary = vocabularyToml.replace("xhigh = \"max\"", "XHigh = \"high\"")
            .replace("max = \"max\"", "MAX = \"low\"")
        val provider = providerFromToml(baseToml + vocabulary)
        assertEffort(provider, "\"effort\":\"xHIGH\"", "high")
        assertEffort(provider, "\"output_config\":{\"effort\":\"max\"}", "low")
    }

    @Test
    fun `quoted TOML effort names resolve thinking off and nondefault explicit levels`() {
        for (quote in listOf("\"", "'")) {
            val vocabulary = vocabularyToml.replace("none = \"low\"", "${quote}none$quote =\"low\"")
                .replace("xhigh = \"max\"", "${quote}XHigh$quote =\"high\"")
                .replace("low = \"low\"", "${quote}low$quote =\"low\"")
            val provider = providerFromToml(baseToml + vocabulary)
            assertEffort(provider, "", "low", thinking = """{"type":"disabled"}""")
            assertEffort(provider, "\"effort\":\"xHIGH\"", "high")
            assertEffort(provider, "\"output_config\":{\"effort\":\"low\"}", "low")
        }
    }

    @Test
    fun `quoted and bare TOML names cannot evade duplicate or blank validation`() {
        for (levels in listOf(
            "\"low\" = \"high\"\nlow = \"max\"",
            "'LOW' = \"high\"\nlow = \"max\"",
            "\" LOW \" = \"high\"\nlow = \"max\"",
            "\"\" = \"low\"",
            "' ' = \"low\"",
        )) {
            val vocabulary = "[providers.glml53.quirks.chat_effort_vocabulary]\ndefault = \"max\"\n" +
                "[providers.glml53.quirks.chat_effort_vocabulary.levels]\n$levels\n"
            assertThrows<IllegalArgumentException> { providerFromToml(baseToml + vocabulary) }
        }
    }

    @Test
    fun `a scoped vocabulary applies only to matching upstream models`() {
        val vocabulary = vocabularyToml.replace(
            "default = \"max\"",
            "default = \"max\"\nmodel_pattern = \"^GLM-5[.]3-Flash\"",
        )
        val provider = providerFromToml(baseToml + vocabulary)
        assertEffort(provider, "\"effort\":\"low\"", "low")
        for ((model, expected) in listOf("meta-llama/Llama-4-Maverick" to "high", "grok-4.6" to "xhigh")) {
            val built = provider.buildTurn(body("\"effort\":\"max\"", model = model), false, null)
            assertEquals(expected, built.requestBody["reasoning_effort"]?.jsonPrimitive?.content, model)
        }
    }

    @Test
    fun `the emission off quirk still suppresses both effort wire fields`() {
        val disabled = "[providers.glml53.quirks]\nreasoning_effort = false\n"
        val provider = providerFromToml(baseToml + disabled + vocabularyToml)
        val built = provider.buildTurn(body("\"effort\":\"low\""), false, null)
        assertFalse(built.requestBody.containsKey("reasoning_effort"))
        assertFalse(built.requestBody.containsKey("reasoning"))
        assertEquals("low", built.meta.reasoning.effort)
    }

    private fun assertEffort(
        provider: OpenAiChatProvider,
        fields: String,
        expected: String,
        thinking: String = """{"type":"enabled","budget_tokens":127999}""",
    ) {
        val built = provider.buildTurn(body(fields, thinking), false, null)
        assertEquals(expected, built.requestBody["reasoning_effort"]?.jsonPrimitive?.content, fields)
        assertEquals(expected, built.requestBody["reasoning"]?.jsonObject?.get("effort")?.jsonPrimitive?.content)
        assertEquals(expected, built.meta.reasoning.effort)
    }

    private fun body(
        fields: String,
        thinking: String = """{"type":"enabled","budget_tokens":127999}""",
        model: String = "GLM-5.3-Flash",
    ) = AnthropicParse.parseAnthropicBody(
        """{"model":"$model","thinking":$thinking,"messages":[{"role":"user","content":"hello"}]""" +
            (if (fields.isEmpty()) "" else ",$fields") + "}",
    )

    private fun provider(configured: Boolean): OpenAiChatProvider =
        providerFromToml(baseToml + if (configured) vocabularyToml else "")

    private fun providerFromToml(toml: String): OpenAiChatProvider {
        val config = TopologyLoader.parse(toml).providers.getValue("glml53")
        return OpenAiChatProvider(
            ProviderTuning(
                name = ProviderName(key = "glml53", label = "GLM"),
                catalog = ModelCatalog(
                    discoveryPrefix = "claude-glml53--",
                    models = listOf(
                        ModelEntry("GLM-5.3-Flash", "GLM", contextWindow = 200_000),
                        ModelEntry("meta-llama/Llama-4-Maverick", "Llama", contextWindow = 200_000),
                        ModelEntry("grok-4.6", "Grok", contextWindow = 200_000),
                    ),
                    defaultContextWindow = 200_000,
                ),
                pinnedModel = "GLM-5.3-Flash",
                auth = ApiKeyAuthProvider("TEST_GLM_KEY", envReader = { null }),
                locations = ProviderLocations(baseUrl = config.baseUrl),
                watchdog = WatchdogBudget(5.seconds, 3.seconds, 30.seconds),
            ),
            QuirksOverlay().chatQuirks(config, "glml53", "GLM"),
        )
    }

    private val baseToml = """
        [providers.glml53]
        dialect = "openai-chat"
        base_url = "https://example.invalid/v1"
        [providers.glml53.auth]
        kind = "api-key"
    """.trimIndent() + "\n"

    private val vocabularyToml = """
        [providers.glml53.quirks.chat_effort_vocabulary]
        default = "max"
        [providers.glml53.quirks.chat_effort_vocabulary.levels]
        none = "low"
        minimal = "low"
        light = "low"
        low = "low"
        medium = "high"
        high = "high"
        xhigh = "max"
        max = "max"
        ultra = "max"
    """.trimIndent() + "\n"
}
