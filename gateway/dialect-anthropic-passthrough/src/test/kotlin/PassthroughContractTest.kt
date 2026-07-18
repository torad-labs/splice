// NEW: request-byte CONTRACT fixture (#924 Phase 1). The golden is the EXACT upstream request the
// PassthroughRequestBuilder emits for a canonical turn; drift in ANY field fails here — not just the
// fields individual scenario tests happen to assert. OFFLINE half of the live-receipt defense:
// checks/e2e/heads-e2e.sh --tier emits a signed receipt
// {provider, model, http_status, sha256(exact request bytes that got 200)}; the receipt-BINDING
// half (a CHANGED golden must match a receipt hash) activates on live traffic. See gateway/CONTRACT.md.
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.core.parse.parseAnthropicBody
import splice.dialect.passthrough.PassthroughQuirks
import splice.dialect.passthrough.PassthroughRequestBuilder
import java.io.File

class PassthroughContractTest {
    @Test
    fun `passthrough canonical request matches the golden bytes`() {
        val anthropic =
            """{"model":"claude-kimi--k3","stream":true,"max_tokens":1024,""" +
                """"system":"You are Splice, a contract fixture.",""" +
                """"messages":[{"role":"user","content":"Ping."}]}"""
        val body = parseAnthropicBody(anthropic)
        val req = PassthroughRequestBuilder(PassthroughQuirks(providerTag = "kimi"))
            .build(body, upstreamModel = "k3", originalModel = "claude-kimi--k3[1m]", compact = false)
            .req
        assertGoldenContract("passthrough-canonical", req) { PassthroughContractTest::class.java }
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
