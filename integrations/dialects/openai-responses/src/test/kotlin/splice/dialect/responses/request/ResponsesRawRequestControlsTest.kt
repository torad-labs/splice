package splice.dialect.responses.request

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import splice.core.failure.Decline
import splice.core.parse.AnthropicParse
import splice.core.turn.ReasoningDisplay
import splice.dialect.responses.ResponsesLiteQuirks
import splice.dialect.responses.ResponsesQuirks
import splice.dialect.responses.ResponsesRequestControlQuirks
import splice.dialect.responses.ResponsesServiceTierSupport
import splice.dialect.responses.reasoning.InjectPriorReasoning

class ResponsesRawRequestControlsTest {
    private val quirks = ResponsesQuirks(providerTag = "openai", lite = ResponsesLiteQuirks())

    @Test
    fun `raw sampling controls survive alongside token controls`() {
        val request = build(
            ""","max_tokens":73,"temperature":0.35,"top_p":0.8""",
        )

        assertEquals("0.35", request["temperature"]?.jsonPrimitive?.content)
        assertEquals("0.8", request["top_p"]?.jsonPrimitive?.content)
        assertEquals("73", request["max_output_tokens"]?.jsonPrimitive?.content)
        assertNull(request["max_tokens"])
    }

    @Test
    fun `source service tiers map to their public Responses semantics`() {
        listOf(
            "auto" to "auto",
            "standard_only" to "default",
        ).forEach { (source, expected) ->
            val request = build(""","service_tier":"$source"""")
            assertEquals(expected, request["service_tier"]?.jsonPrimitive?.content, source)
        }
    }

    @Test
    fun `controls without an equivalent Responses meaning are declined`() {
        listOf(
            ""","top_k":40""" to "TOP_K",
            ""","stop_sequences":["END"]""" to "STOP_SEQUENCES",
            ""","speed":"standard"""" to "SPEED",
            ""","speed":"fast"""" to "SPEED",
            ""","service_tier":"priority"""" to "SERVICE_TIER",
        ).forEach { (fields, expected) -> assertEquals(expected, declinedFeature(fields), fields) }
    }

    @Test
    fun `source root controls without a Responses equivalent are declined`() {
        listOf(
            ",\"container\":\"container_123\"" to "CONTAINER",
            ",\"diagnostics\":{\"previous_message_id\":\"msg_123\"}" to "DIAGNOSTICS",
            ",\"inference_geo\":\"us\"" to "INFERENCE_GEO",
            ",\"user_profile_id\":\"profile_123\"" to "USER_PROFILE_ID",
            ",\"workspace_id\":\"workspace_123\"" to "WORKSPACE_ID",
            ",\"cache_control\":{\"type\":\"persistent\"}" to "CACHE_CONTROL",
            ",\"max_tokens\":0" to "CACHE_ONLY_WARMUP",
        ).forEach { (fields, expected) -> assertEquals(expected, declinedFeature(fields), fields) }
    }

    @Test
    fun `valid root cache hints leave automatic Responses caching in control`() {
        listOf(
            """{"type":"ephemeral"}""",
            """{"type":"ephemeral","ttl":"5m"}""",
            """{"type":"ephemeral","ttl":"1h"}""",
        ).forEach { hint ->
            val fields = ""","cache_control":$hint"""
            assertNull(declinedFeature(fields), fields)
            val request = build(fields)
            assertNull(request["cache_control"])
            assertNull(request["prompt_cache_options"])
            assertNull(request["prompt_cache_breakpoint"])
        }
    }

    @Test
    fun `source metadata remains an accepted non-wire hint`() {
        val fields = ""","metadata":{"user_id":"actor-123"}"""

        assertNull(declinedFeature(fields))
        assertNull(build(fields)["metadata"])
    }

    @Test
    fun `an explicit null output format means no output format`() {
        val fields = ""","output_config":{"format":null}"""
        assertNull(declinedFeature(fields))
        assertNull(build(fields)["text"])
    }

    @Test
    fun `providers without an equivalent service tier decline the source control`() {
        val unsupported = quirks.copy(
            request = ResponsesRequestControlQuirks(serviceTier = ResponsesServiceTierSupport.UNSUPPORTED),
        )
        val body = AnthropicParse.parseAnthropicBody(body(""","service_tier":"auto""""))
        val cause = ResponsesOutputSchema.refusal(body.typed, body.raw, unsupported)?.cause
        assertEquals("SERVICE_TIER", (cause?.why as? Decline.Untranslatable)?.what?.name)
    }

    private fun declinedFeature(fields: String): String? {
        val body = AnthropicParse.parseAnthropicBody(body(fields))
        val cause = ResponsesOutputSchema.refusal(body.typed, body.raw)?.cause
        return (cause?.why as? Decline.Untranslatable)?.what?.name
    }

    private fun build(fields: String): JsonObject {
        val parsed = AnthropicParse.parseAnthropicBody(body(fields))
        return ResponsesRequestBuilder(quirks).build(parsed.typed, parsed.raw, options()).req
    }

    private fun body(fields: String): String =
        """{"model":"m","messages":[{"role":"user","content":"hi"}]$fields}"""

    private fun options(): BuildOptions = BuildOptions(
        compact = false,
        models = ModelIds(original = "claude-openai--gpt-4.1", upstream = "gpt-4.1"),
        reasoning = RequestedReasoning(effort = null, summary = null, display = ReasoningDisplay.OFF),
        handoff = ReasoningHandoff(replay = InjectPriorReasoning(false), decode = { null }),
    )
}
