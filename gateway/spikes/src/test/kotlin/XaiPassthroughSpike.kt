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
            {"model":"$MODEL","max_tokens":256,"stream":true,
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

        // What the receipt records. Deliberately assertion-light: a spike answers a question, it
        // does not gate CI — the walls that pin the ANSWER land with P6-GROK.
        val starts = events.filter { typeOf(it) == "content_block_start" }
        val deltas = events.filter { typeOf(it) == "content_block_delta" }
        val findings = buildString {
            appendLine("status: ${res.statusCode()}")
            appendLine("events: ${events.size}")
            appendLine("sequence: ${events.mapNotNull(typeOf).distinct().joinToString(" -> ")}")
            appendLine("block types: ${starts.mapNotNull { it["content_block"]?.jsonObject?.get("type")?.jsonPrimitive?.content }}")
            appendLine("content_block_start index values: ${starts.map { it["index"]?.jsonPrimitive?.content }}")
            appendLine("content_block_delta missing index: ${deltas.count { "index" !in it }}/${deltas.size}")
            appendLine("stop_reason: ${events.firstOrNull { typeOf(it) == "message_delta" }?.get("delta")?.jsonObject?.get("stop_reason")?.jsonPrimitive?.content}")
        }
        println(findings)
        File("results").mkdirs()
        File("results/xai-passthrough.raw.txt").writeText(findings)
    }
}
