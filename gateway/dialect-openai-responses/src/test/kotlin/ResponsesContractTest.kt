// NEW: request-byte CONTRACT fixture (#924 Phase 1). The golden is the EXACT upstream request the
// ResponsesRequestBuilder emits for a canonical turn — this is the builder BOTH production incidents
// (stream_options 400, request-body gzip 400) came through, so a whole-request golden that catches
// drift in ANY field is the marquee offline defense. OFFLINE half of the live-receipt binding:
// checks/e2e/heads-e2e.sh --tier emits a signed receipt
// {provider, model, http_status, sha256(exact request bytes that got 200)}; the receipt-BINDING
// half (a CHANGED golden must match a receipt hash, so a blind regenerate can't go green) activates
// on live traffic. See gateway/CONTRACT.md.
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.core.parse.parseAnthropicBody
import splice.core.turn.ReasoningDisplay
import splice.dialect.responses.BuildOptions
import splice.dialect.responses.InjectPriorReasoning
import splice.dialect.responses.RequestEncryptedReasoning
import splice.dialect.responses.ResponsesQuirks
import splice.dialect.responses.ResponsesRequestBuilder
import java.io.File

class ResponsesContractTest {
    @Test
    fun `responses canonical request matches the golden bytes`() {
        val anthropic =
            """{"model":"claude-codex--gpt-5.6-sol","stream":true,"max_tokens":1024,""" +
                """"system":"You are Splice, a contract fixture.",""" +
                """"messages":[{"role":"user","content":"Ping."}]}"""
        val parsed = parseAnthropicBody(anthropic)
        val opts = BuildOptions(
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
        val req = ResponsesRequestBuilder(ResponsesQuirks(providerTag = "claudex"))
            .build(parsed.typed, parsed.raw, opts)
            .req
        assertGoldenContract("responses-canonical", req) { ResponsesContractTest::class.java }
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
