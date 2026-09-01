// WALL (prompt-cache prefix stability, 2026-07-30): end-to-end proof that consecutive turns of one
// conversation keep turn N's input as a strict PREFIX of turn N+1's, driven by the REAL
// ReasoningCache rather than a hand-written stub.
//
// WHY THIS EXISTS: OpenAI prompt caching reuses the longest stable prefix. The builder injects each
// round's reasoning immediately before that round's FIRST function_call (ResponsesRequestBuilder
// appendToolUse), so losing ONE round's cache entry deletes an item from the MIDDLE of the input
// array and shifts every item after it — turn N+1 stops extending turn N and the whole remainder is
// re-billed as fresh input. The per-turn view ("a miss degrades to no-injection") is true and hid
// this for two months; the cross-turn view is what bills.
//
// Measured on live claudex telemetry before the fix: 342,473,969 tokens of known-identical prefix
// re-sent across 6,909 continuation turns; only 7.4% of turns reused the full prefix they had
// already paid for.
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.core.parse.AnthropicParse
import splice.core.turn.ReasoningDisplayParser
import splice.dialect.responses.BuildOptions
import splice.dialect.responses.InjectPriorReasoning
import splice.dialect.responses.ReasoningCache
import splice.dialect.responses.RequestEncryptedReasoning
import splice.dialect.responses.ResponsesQuirks
import splice.dialect.responses.ResponsesRequestBuilder
import splice.dialect.responses.ResponsesStableIds

private val stableIds = ResponsesStableIds()

private val CODEX = ResponsesQuirks(providerTag = "claudex")

private fun opts(lookup: (String) -> List<String>?) = BuildOptions(
    compact = false,
    originalModel = "claude-codex--gpt-5.6-sol",
    upstreamModel = "gpt-5.6-sol",
    configEffort = null,
    configSummary = null,
    showReasoning = ReasoningDisplayParser.from("text"),
    replayReasoning = InjectPriorReasoning(false),
    includeEncryptedReasoning = RequestEncryptedReasoning(true),
    decodeReasoningEnvelope = { data ->
        buildJsonObject {
            put("type", JsonPrimitive("reasoning"))
            put("encrypted_content", JsonPrimitive(data))
        }
    },
    reasoningLookup = lookup,
)

/** A conversation of [rounds] completed tool round-trips, Anthropic-shaped. */
private fun conversation(rounds: Int): String {
    val msgs = mutableListOf("""{"role":"user","content":"start the task"}""")
    for (i in 1..rounds) {
        msgs += """{"role":"assistant","content":[{"type":"tool_use","id":"call_$i","name":"read","input":{"path":"f$i.kt"}}]}"""
        msgs += """{"role":"user","content":[{"type":"tool_result","tool_use_id":"call_$i","content":"contents of f$i"}]}"""
    }
    return """{"model":"m","messages":[${msgs.joinToString(",")}]}"""
}

private fun inputItems(json: String, lookup: (String) -> List<String>?): List<String> {
    val parsed = AnthropicParse.parseAnthropicBody(json)
    val req: JsonObject = ResponsesRequestBuilder(CODEX).build(parsed.typed, parsed.raw, opts(lookup)).req
    return req["input"]!!.jsonArray.map { it.toString() }
}

/** Index of the first element where [b] stops matching [a]; a.size when a is a clean prefix. */
private fun firstDivergence(a: List<String>, b: List<String>): Int {
    val n = minOf(a.size, b.size)
    for (i in 0 until n) if (a[i] != b[i]) return i
    return n
}

class PrefixStabilityDiagnostic {

    @Test
    fun `a conversation outliving the TTL keeps every turn a prefix-extension of the last`() {
        var now = 0L
        // 100ms TTL against ~90s of simulated conversation: the ratio a multi-hour Claude Code
        // session has against the real 30-minute TTL, compressed.
        val cache = ReasoningCache(ttlMs = 100, clock = { now })
        val convKey = stableIds.stablePromptCacheKey(AnthropicParse.parseAnthropicBody(conversation(1)).typed)
        val lookup: (String) -> List<String>? = { id -> cache.lookup(convKey, id) }

        var previous = inputItems(conversation(0), lookup)
        for (round in 1..12) {
            // The stream translator captures the round's reasoning as the model emits it.
            cache.put(convKey, listOf("call_$round"), listOf("envelope-for-call_$round"))
            now += 60 // each turn takes 60ms -> every entry is older than the TTL within two turns
            val current = inputItems(conversation(round), lookup)

            val d = firstDivergence(previous, current)
            assertEquals(
                previous.size,
                d,
                "turn $round rewrote the prefix at index $d of ${previous.size} " +
                    "(${"%.1f".format(100.0 * d / maxOf(previous.size, 1))}% reused) — every token " +
                    "from there on is re-billed.\n  was: ${previous.getOrNull(d)?.take(120)}\n  " +
                    "now: ${current.getOrNull(d)?.take(120)}",
            )
            // NON-VACUITY (review of #71 round 2): prefix-extension alone also passes when the
            // cache injects NOTHING (a wiped/frozen/miswired cache still appends cleanly). Every
            // round's reasoning must actually be present, or this wall cannot see the failure
            // class it exists for.
            val injected = current.count { it.contains("\"type\":\"reasoning\"") }
            assertEquals(round, injected, "turn $round injected $injected reasoning items, expected $round")
            previous = current
        }
    }

    @Test
    fun `the builder is prefix-sensitive - losing one OLD round shifts the array`() {
        // Pins the MECHANISM (independent of cache policy): this is why the cache must never serve
        // a partially-expired conversation. If this ever stops holding, the builder has changed
        // shape and the cache-side contract above can be revisited.
        val rounds = 8
        val allCached: (String) -> List<String>? = { id -> listOf("envelope-for-$id") }
        val turnN = inputItems(conversation(rounds), allCached)
        val oldestGone: (String) -> List<String>? = { id ->
            if (id == "call_1") null else listOf("envelope-for-$id")
        }
        val turnN1 = inputItems(conversation(rounds + 1), oldestGone)

        val d = firstDivergence(turnN, turnN1)
        assertEquals(2, d, "losing the oldest round must shift the array at its position, not the tail")
        assertEquals(
            turnN.size,
            firstDivergence(turnN, inputItems(conversation(rounds + 1), allCached)),
            "control: with nothing lost the prefix is fully reused",
        )
    }
}
