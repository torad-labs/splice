package splice.dialect.responses.request

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
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
    fun `raw sampling controls survive alongside token and effort controls`() {
        val request = build(
            ""","max_tokens":73,"temperature":0.35,"top_p":0.8,"output_config":{"effort":"low"}""",
        )

        assertEquals("0.35", request["temperature"]?.jsonPrimitive?.content)
        assertEquals("0.8", request["top_p"]?.jsonPrimitive?.content)
        assertEquals("73", request["max_output_tokens"]?.jsonPrimitive?.content)
        assertNull(request["max_tokens"])
        assertEquals("low", request["reasoning"]?.jsonObject?.get("effort")?.jsonPrimitive?.content)
    }

    @Test
    fun `a mid-conversation system effort controls the current Responses turn`() {
        val parsed = AnthropicParse.parseAnthropicBody(
            """{"model":"m","output_config":{"effort":"high"},"messages":[
                {"role":"user","content":"before"},
                {"role":"assistant","content":"done"},
                {"role":"system","content":[],"output_config":{"effort":"low"}},
                {"role":"user","content":"after"}]}""",
        )

        val request = ResponsesRequestBuilder(quirks).build(parsed.typed, parsed.raw, options()).req

        assertEquals("low", request["reasoning"]?.jsonObject?.get("effort")?.jsonPrimitive?.content)
    }

    @Test
    fun `an explicit request effort keeps its established precedence over a system effort change`() {
        val parsed = AnthropicParse.parseAnthropicBody(
            """{"model":"m","effort":"max","output_config":{"effort":"high"},"messages":[
                {"role":"user","content":"before"},
                {"role":"assistant","content":"done"},
                {"role":"system","content":[],"output_config":{"effort":"low"}},
                {"role":"user","content":"after"}]}""",
        )

        val request = ResponsesRequestBuilder(quirks).build(parsed.typed, parsed.raw, options()).req

        assertEquals("max", request["reasoning"]?.jsonObject?.get("effort")?.jsonPrimitive?.content)
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
    fun `client diagnostics are dropped and the turn is sent`() {
        val fields = ""","diagnostics":{"previous_message_id":"msg_123"}"""

        assertNull(declinedFeature(fields))
        assertNull(build(fields)["diagnostics"])
    }

    @Test
    fun `every field Claude Code 2_1_321 sends is admitted, and the two no model reads stay off the wire`() {
        val fields = ""","system":"s","tools":[],"metadata":{"user_id":"u"},"max_tokens":128000,"stream":true,""" +
            """"thinking":{"type":"adaptive"},"output_config":{"effort":"high"},""" +
            """"context_management":{"edits":[{"type":"clear_thinking_20251015","keep":"all"}]},""" +
            """"diagnostics":{"previous_message_id":"msg_1"}"""

        assertNull(declinedFeature(fields))
        val request = build(fields)
        assertNull(request["context_management"])
        assertNull(request["diagnostics"])
    }

    @Test
    fun `a field no rule classifies is refused and named, since it may be a control`() {
        listOf(
            ""","mcp_servers":[{"type":"url","url":"https://example.invalid","name":"x"}]""" to "mcp_servers",
            ""","sampling_seed":7""" to "sampling_seed",
        ).forEach { (fields, name) ->
            val refusal = refusal(fields)
            assertEquals("UNKNOWN_FIELD", (refusal?.cause?.why as? Decline.Untranslatable)?.what?.name, fields)
            assertTrue(refusal?.detail.orEmpty().contains("`$name`"), "${refusal?.detail}")
        }
    }

    @Test
    fun `a field whose name says it is the client's record is dropped, as is a null field`() {
        listOf(
            ""","client_telemetry":{"tick":1}""",
            ""","request_metadata":{"a":"b"}""",
            ""","debug_trace":"on"""",
            ""","sampling_seed":null""",
        ).forEach { fields ->
            assertNull(refusal(fields), fields)
            val sent = build(fields).keys
            assertTrue(sent.none { it in setOf("client_telemetry", "request_metadata", "debug_trace") }, "$sent")
        }
    }

    @Test
    fun `a head says once per field what it drops from the wire`() {
        val lines = mutableListOf<String>()
        val said = splice.dialect.responses.admission.ResponsesDroppedFields(splice.core.util.LogSink { lines += it })
        val raw = AnthropicParse.parseAnthropicBody(body(""","diagnostics":{},"client_telemetry":{}""")).raw

        repeat(3) { said.say("claudex", raw) }

        assertEquals(2, lines.size, "$lines")
        listOf("diagnostics", "client_telemetry").forEachIndexed { i, field ->
            assertTrue(lines[i].startsWith("[claudex] drops the request field `$field` from the wire: "), lines[i])
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

    private fun refusal(fields: String): splice.upstream.RequestRefusal? {
        val body = AnthropicParse.parseAnthropicBody(body(fields))
        return ResponsesOutputSchema.refusal(body.typed, body.raw)
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
