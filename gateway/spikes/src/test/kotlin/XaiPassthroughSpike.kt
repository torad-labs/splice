// NEW: P0-XAI spike — does api.x.ai /v1/messages stream FAITHFUL Anthropic SSE?
// The answer decides P6-GROK's dialect: faithful => the grok head is near-passthrough (auth +
// model map + usage instrumentation only); unfaithful => port the proven Responses translators
// (server/src/grok/translate-{request,response}.mjs).
//
// CREDENTIAL: grok-oauth, ~/.grok/auth.json (tokens.access_token) — the SAME file the grok head
// itself uses. The ledger's 2026-07-16 PREMISE-BLOCK looked only for XAI_API_KEY and
// ~/.local/share/claude-grok/auth.json and concluded the probe "cannot be grounded". The second
// path DID exist that day (mtime 2026-07-15, token expired), and the oauth file the product
// already ships against was never checked. Skips — never fabricates — when no live token exists.
//
// Receipt: gateway/spikes/results/xai-passthrough.md
// Run: ./gradlew :spikes:test -PrunSpikes --tests 'XaiPassthroughSpike*'
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private const val ENDPOINT = "https://api.x.ai/v1/messages"
private const val MODEL = "grok-4-latest"

private val json = Json { ignoreUnknownKeys = true }

/** The live grok-oauth access token, or null when absent/expired — the spike then SKIPS. */
private fun liveToken(): String? {
    val f = File(System.getProperty("user.home"), ".grok/auth.json")
    if (!f.isFile) return null
    val root = runCatching { json.parseToJsonElement(f.readText()).jsonObject }.getOrNull() ?: return null
    val expires = root["expires"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return null
    val expiresMs = if (expires > 1e11) expires else expires * 1000
    if (expiresMs <= System.currentTimeMillis()) return null
    return root["tokens"]?.jsonObject?.get("access_token")?.jsonPrimitive?.content
}

class XaiPassthroughSpike {

    @Test
    fun `api-x-ai v1 messages streams anthropic sse`() {
        val token = liveToken()
        assumeTrue(token != null, "no live grok-oauth token in ~/.grok/auth.json — spike skipped, not faked")

        val body = """
            {"model":"$MODEL","max_tokens":2048,"stream":true,
             "thinking":{"type":"enabled","budget_tokens":1024},
             "tools":[{"name":"get_weather","description":"Get weather","input_schema":
               {"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}}],
             "messages":[{"role":"user","content":"Use the get_weather tool for Paris, then say DONE."}]}
        """.trimIndent()

        val res = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build().send(
            HttpRequest.newBuilder(URI.create(ENDPOINT))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .header("anthropic-version", "2023-06-01")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        val events = res.body().split("\n\n").mapNotNull { block ->
            block.lineSequence().firstOrNull { it.startsWith("data: ") }
                ?.removePrefix("data: ")
                ?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
        }
        val typeOf = { e: JsonObject -> e["type"]?.jsonPrimitive?.content }

        // THE INVARIANT THE VERDICT RESTS ON (review of #70). The receipt concludes that correct
        // indices can be reconstructed by COUNTING content_block_start events. That is only sound
        // while blocks are strictly sequential: no delta outside an open block, and never two open
        // at once. Filtering starts and deltas cannot see either violation — an interleaved stream
        // produced the identical report and passed. This walks the stream and records the verdict.
        var open = 0
        var maxOpen = 0
        var orphanDeltas = 0
        for (e in events) {
            when (typeOf(e)) {
                "content_block_start" -> { open += 1; maxOpen = maxOf(maxOpen, open) }
                "content_block_stop" -> open -= 1
                "content_block_delta" -> if (open == 0) orphanDeltas += 1
            }
        }
        val sequential = maxOpen <= 1 && orphanDeltas == 0 && open == 0

        val starts = events.filter { typeOf(it) == "content_block_start" }
        val deltas = events.filter { typeOf(it) == "content_block_delta" }

        // A MINIMUM CONTRACT, so a 500 / empty body / malformed SSE fails instead of silently
        // writing a receipt (review of #70). Everything the verdict claims is asserted; what the
        // spike merely OBSERVES (index values, stop_reason) stays in the report.
        assertEquals(200, res.statusCode(), "the spike's verdict assumes a live 200")
        assertTrue(events.isNotEmpty(), "no SSE events parsed — the receipt would be evidence-free")
        assertTrue(events.any { typeOf(it) == "message_stop" }, "the stream must terminate")
        assertTrue(starts.isNotEmpty(), "no content blocks to reason about")
        assertTrue(
            sequential,
            "blocks were NOT strictly sequential (maxOpen=$maxOpen orphanDeltas=$orphanDeltas " +
                "unclosed=$open) — the count-the-starts reconstruction the receipt proposes is unsound",
        )
        assertTrue(
            starts.any { it["content_block"]?.jsonObject?.get("type")?.jsonPrimitive?.content == "thinking" },
            "the receipt claims thinking fidelity, so the run must actually contain a thinking block",
        )
        val findings = buildString {
            appendLine("status: ${res.statusCode()}")
            appendLine("events: ${events.size}")
            appendLine("sequence: ${events.mapNotNull(typeOf).distinct().joinToString(" -> ")}")
            appendLine("block types: ${starts.mapNotNull { it["content_block"]?.jsonObject?.get("type")?.jsonPrimitive?.content }}")
            appendLine("content_block_start index values: ${starts.map { it["index"]?.jsonPrimitive?.content }}")
            appendLine("content_block_delta missing index: ${deltas.count { "index" !in it }}/${deltas.size}")
            appendLine("strictly sequential blocks: $sequential (max concurrently open: $maxOpen, orphan deltas: $orphanDeltas)")
            appendLine("stop_reason: ${events.firstOrNull { typeOf(it) == "message_delta" }?.get("delta")?.jsonObject?.get("stop_reason")?.jsonPrimitive?.content}")
        }
        println(findings)
        File("results").mkdirs()
        File("results/xai-passthrough.raw.txt").writeText(findings)
    }
}
