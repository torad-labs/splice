// NEW: (RC-2, reasoning-cache campaign 2026-07-24) gateway-held reasoning continuity. codex-rs
// keeps the model's encrypted reasoning items in its in-process history and re-sends them on
// every tool round-trip (store:false stateless full replay — client.rs:888/:915); splice with
// replay_reasoning=false dropped them, giving gpt-5.6 amnesia at every tool result (repeated
// tool calls, duplicated reasoning — operator report 2026-07-24). This cache holds the round's
// envelopes keyed by its REAL function_call ids so the builder can reinject the plan in-position
// when the tool results come back. Entries are READ, not consumed — a client-retried request
// re-reads the same envelopes (grace by design); TTL and bounds do the cleanup. Losing an entry
// (restart, eviction, TTL) degrades to today's no-injection behavior, never to an error
// (NEVER-BELOW-STATUS-QUO law).
package splice.dialect.responses

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonObject
import splice.core.util.str

/** One turn's capture: every id of the round maps to the same shared entry; a lookup by ANY of
 *  them yields the round's ordered envelopes exactly once per request build (inject-once law is
 *  the BUILDER's duty; the cache is a plain keyed store). */
internal class ReasoningCache(
    private val maxEntries: Int = MAX_ENTRIES,
    private val maxTotalBytes: Long = MAX_TOTAL_BYTES,
    private val ttlMs: Long = TTL_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private data class Entry(val toolIds: List<String>, val envelopes: List<String>, val bytes: Long, val at: Long)

    // Insertion-ordered so eviction drops the OLDEST turn first — a deep in-flight tool loop's
    // recent entries survive bound pressure (eli risk 4: mid-loop eviction reproduces the
    // amnesia symptom intermittently; oldest-first plus a TTL far above turn wall-time avoids it).
    private val entries = LinkedHashMap<String, Entry>()
    private val byToolId = HashMap<String, String>()
    private var totalBytes = 0L
    private var seq = 0L
    private val lock = Any()

    fun put(toolIds: List<String>, envelopes: List<String>) {
        if (toolIds.isEmpty() || envelopes.isEmpty()) return
        val bytes = envelopes.sumOf { it.length.toLong() }
        synchronized(lock) {
            sweepLocked()
            val key = "t${seq++}"
            entries[key] = Entry(toolIds, envelopes, bytes, clock())
            toolIds.forEach { byToolId[it] = key }
            totalBytes += bytes
            while (entries.size > maxEntries || totalBytes > maxTotalBytes) {
                val oldest = entries.entries.firstOrNull() ?: break
                removeLocked(oldest.key)
            }
        }
    }

    /** The ordered envelopes for the turn that emitted [toolId], or null (miss = status quo). */
    fun lookup(toolId: String): List<String>? = synchronized(lock) {
        sweepLocked()
        val key = byToolId[toolId] ?: return null
        entries[key]?.envelopes
    }

    /** Drop the turn containing [toolId] — used when upstream rejects its envelopes as stale. */
    fun evictByToolId(toolId: String) {
        synchronized(lock) { byToolId[toolId]?.let { removeLocked(it) } }
    }

    private fun sweepLocked() {
        val cutoff = clock() - ttlMs
        val expired = entries.entries.takeWhile { it.value.at < cutoff }.map { it.key }
        expired.forEach { removeLocked(it) }
    }

    private fun removeLocked(key: String) {
        val e = entries.remove(key) ?: return
        e.toolIds.forEach { byToolId.remove(it) }
        totalBytes -= e.bytes
    }

    internal companion object {
        // A tool round-trip is seconds; the watchdog's whole-turn cap is minutes. 30 min covers
        // the deepest realistic loop with an order of magnitude to spare (eli risk 4).
        const val TTL_MS: Long = 30 * 60 * 1000L
        const val MAX_ENTRIES: Int = 256
        const val MAX_TOTAL_BYTES: Long = 64L * 1024 * 1024
    }
}

/** RC-4: the invalid_encrypted_content recovery — strip every reasoning input item from the
 *  request (degrade to per-item amnesia, never fail the turn on cache contents) and evict the
 *  cache entries for the function_calls it carried so the stale envelopes are not re-offered.
 *  Returns null when the body carries no reasoning items (the amendment is not ours to make).
 *  Decode/encode rides the closed ResponsesRequest DTO (#924) — no field invented or lost. */
internal fun stripStaleReasoning(bodyJson: String, cache: ReasoningCache): String? {
    val previous = kotlinx.serialization.json.Json.parseToJsonElement(bodyJson).jsonObject
    val base = responsesRequestJson.decodeFromJsonElement(ResponsesRequest.serializer(), previous)
    var dropped = 0
    val kept = buildJsonArray {
        base.input.forEach { el ->
            val item = el as? JsonObject
            if (item != null && item["type"].str() == "reasoning") {
                dropped++
            } else {
                add(el)
                if (item != null && item["type"].str() == "function_call") {
                    item["call_id"].str()?.let { cache.evictByToolId(it) }
                }
            }
        }
    }
    if (dropped == 0) return null
    val next = base.copy(input = kept)
    return responsesRequestJson.encodeToJsonElement(ResponsesRequest.serializer(), next).toString()
}
