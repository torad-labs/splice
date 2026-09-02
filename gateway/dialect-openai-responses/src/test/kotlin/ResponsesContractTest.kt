// NEW: request-byte CONTRACT fixture (#924 Phase 1). The golden is the EXACT upstream request the
// ResponsesRequestBuilder emits for a canonical turn — this is the builder BOTH production incidents
// (stream_options 400, request-body gzip 400) came through, so a whole-request golden that catches
// drift in ANY field is the marquee offline defense. The planned live-receipt binding is DORMANT:
// heads-e2e receipts are contract_bound:false because no head-side upstream-byte tap exists yet.
// gateway/CONTRACT.md names that missing tap and the hash check still required before a changed
// golden can be bound to a live 200.
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import splice.core.parse.AnthropicParse
import splice.core.turn.ReasoningDisplay
import splice.dialect.responses.BuildOptions
import splice.dialect.responses.InjectPriorReasoning
import splice.dialect.responses.RequestEncryptedReasoning
import splice.dialect.responses.ResponsesQuirks
import splice.dialect.responses.ResponsesRequestBuilder
import splice.dialect.responses.ToolDeferralPolicy
import java.io.File

// The codex quirk profile, mirrored from CodexProvider.defaultQuirks() (provider-codex DEPENDS ON
// this module, never the reverse — the dialect can never import a concrete provider).
private fun codexProfileQuirks(toolSurface: ToolDeferralPolicy? = null) = ResponsesQuirks(
    providerTag = "claudex",
    emitEmptyLiteInstructions = true,
    summaryDelivery = "sequential_cutoff",
    forceStrictFalse = true,
    normalizeToolSchemas = true,
    toolSurface = toolSurface,
)

class ResponsesContractTest {
    private val canonicalAnthropic =
        """{"model":"claude-codex--gpt-5.6-sol","stream":true,"max_tokens":1024,""" +
            """"system":"You are Splice, a contract fixture.",""" +
            """"messages":[{"role":"user","content":"Ping."}]}"""

    private fun canonicalOpts() = BuildOptions(
        compact = false,
        originalModel = "claude-codex--gpt-5.6-sol",
        upstreamModel = "gpt-5.6-sol",
        configEffort = "high",
        configSummary = "detailed",
        showReasoning = ReasoningDisplay.TEXT,
        replayReasoning = InjectPriorReasoning(false),
        includeEncryptedReasoning = RequestEncryptedReasoning(true),
        sessionId = "contract-fixture",
        decodeReasoningEnvelope = { null },
    )

    @Test
    fun `responses canonical request matches the golden bytes`() {
        // This golden's green IS the default-off proof: neither emitStrict, toolSurface, nor the
        // codex-only empty instructions field moves the provider-neutral request.
        val parsed = AnthropicParse.parseAnthropicBody(canonicalAnthropic)
        val req = ResponsesRequestBuilder(ResponsesQuirks(providerTag = "claudex"))
            .build(parsed.typed, parsed.raw, canonicalOpts())
            .req
        assertFalse("instructions" in req, "empty top-level instructions is a codex-only wire quirk")
        assertGoldenContract("responses-canonical", req) { ResponsesContractTest::class.java }
    }

    @Test
    fun `responses codex profile pins strict false on every function tool`() {
        // The second tool arrives with its OWN "strict":true (review 2026-07-25, comment 1): the
        // golden must still pin "strict":false for it, or a forceStrictFalse regression that lets
        // a tool's own strict value pass through (ToolSurface.kt functionToolObject) would stay
        // green — the prior fixture only ever exercised a tool with no strict field at all.
        val toolBody = """{"model":"claude-codex--gpt-5.6-sol","stream":true,"max_tokens":1024,""" +
            """"system":"You are Splice, a contract fixture.",""" +
            """"tools":[{"name":"Read","input_schema":{"type":"object"}},""" +
            """{"name":"Bash","input_schema":{"type":"object"},"strict":true}],""" +
            """"messages":[{"role":"user","content":"Ping."}]}"""
        val parsed = AnthropicParse.parseAnthropicBody(toolBody)
        val req = ResponsesRequestBuilder(codexProfileQuirks())
            .build(parsed.typed, parsed.raw, canonicalOpts())
            .req
        assertEquals("", req["instructions"]?.jsonPrimitive?.content, "codex lite keeps its serde-parity field")
        assertGoldenContract("responses-codex-profile", req) { ResponsesContractTest::class.java }
    }

    @Test
    fun `responses tool search pins the eager array, absent deferred tools, and the tool_search object`() {
        val mcpTools = (1..12).joinToString(",") {
            """{"name":"mcp__exa__tool_$it","input_schema":{"type":"object"}}"""
        }
        val toolBody = """{"model":"claude-codex--gpt-5.6-sol","stream":true,"max_tokens":1024,""" +
            """"system":"You are Splice, a contract fixture.",""" +
            """"tools":[{"name":"Read","input_schema":{"type":"object"}},$mcpTools],""" +
            """"messages":[{"role":"user","content":"Ping."}]}"""
        val parsed = AnthropicParse.parseAnthropicBody(toolBody)
        val quirks = codexProfileQuirks(toolSurface = ToolDeferralPolicy(minDeferred = 4))
        val req = ResponsesRequestBuilder(quirks)
            .build(parsed.typed, parsed.raw, canonicalOpts())
            .req
        assertGoldenContract("responses-tool-search", req) { ResponsesContractTest::class.java }
    }
}

// Per-module inline (test scaffolding): a shared helper would need a cross-module testFixtures dep.
// Record-on-missing: writes the golden to src/test/resources on first run and FAILS ("recorded"),
// so a fixture is never silently green; the next run reads it from the classpath and enforces.
private val CONTRACT_JSON = Json { prettyPrint = true }

internal fun assertGoldenContract(name: String, actual: JsonObject, owner: () -> Class<*>) {
    val pretty = CONTRACT_JSON.encodeToString(JsonObject.serializer(), actual)
    val res = owner().getResource("/contract/$name.json")
    if (res == null) {
        File("src/test/resources/contract/$name.json").apply {
            parentFile.mkdirs()
            writeText(pretty + "\n")
        }
        throw AssertionError("RECORDED golden contract/$name.json — review the bytes, then re-run to enforce.")
    }
    assertEquals(
        res.readText().trim(),
        pretty.trim(),
        "request-byte contract drift for '$name' — a builder change altered the upstream request. " +
            "If intended, regenerate the golden and (Phase 1 live half) re-bind it to a heads-e2e receipt. See gateway/CONTRACT.md.",
    )
}
