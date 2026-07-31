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

import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonObject
import splice.core.util.MonoClock
import splice.core.util.str

/** One turn's capture: every id of the round maps to the same shared entry; a lookup by ANY of
 *  them yields the round's ordered envelopes exactly once per request build (inject-once law is
 *  the BUILDER's duty; the cache is a plain keyed store). Lookups are scoped by conversationKey
 *  (review 2026-07-24, RC-2's eli-risk-8: one instance serves every concurrent conversation on a
 *  head, so a bare-id key could cross-inject on call_id reuse); eviction stays bare-id, since
 *  over-evicting on a collision only costs a cache miss (status quo), never a wrong injection. */
internal class ReasoningCache(
    private val maxEntries: Int = MAX_ENTRIES,
    private val maxTotalBytes: Long = MAX_TOTAL_BYTES,
    private val ttlMs: Long = TTL_MS,
    // Monotonic, not wall clock: sweepLocked's takeWhile early-exit is sound only while insertion
    // order matches timestamp order — an NTP step backward would break that invariant and leave an
    // expired entry unswept (review 2026-07-24; same reasoning as UpstreamClient's MonoClock).
    private val clock: () -> Long = MonoClock::nowMs,
) {

    private data class Entry(
        val conversationKey: String?,
        val toolIds: List<String>,
        val envelopes: List<String>,
        val bytes: Long,
        val at: Long,
    )

    // Insertion-ordered so eviction drops the OLDEST turn first — a deep in-flight tool loop's
    // recent entries survive bound pressure (eli risk 4: mid-loop eviction reproduces the
    // amnesia symptom intermittently; oldest-first plus a TTL far above turn wall-time avoids it).
    private val entries = LinkedHashMap<String, Entry>()
    private val byScopedToolId = HashMap<String, String>()
    private var totalBytes = 0L
    private var seq = 0L
    private val lock = Any()

    private fun scoped(conversationKey: String?, toolId: String) = "${conversationKey.orEmpty()}\u0000$toolId"

    // Conversations that overflowed the bounds ON THEIR OWN (review of #71). Once a conversation
    // cannot be held WHOLE, holding PART of it is worse than holding none: a partial conversation
    // shifts the input array mid-prefix every time another of its rounds is dropped, which is
    // precisely the state this cache exists to prevent. Wiping it WITHOUT this marker only trades
    // grinding for oscillation — the conversation re-caches, overflows and wipes again, and each
    // wipe makes rounds LOSE reasoning they already had. The marker makes it ONE transition, then
    // stable for the rest of that conversation's life (no-injection is the documented status-quo
    // fallback). Insertion-ordered and swept on the same idle TTL as [entries] so it cannot grow
    // without bound; letting an IDLE one lapse is safe, because a resumed conversation's old rounds
    // stay unreasoned and new rounds only ever ADD reasoning at the tail, which shifts nothing.
    private val disabled = LinkedHashMap<String, Long>()

    fun put(conversationKey: String?, toolIds: List<String>, envelopes: List<String>) {
        if (toolIds.isEmpty() || envelopes.isEmpty()) return
        val bytes = envelopes.sumOf { it.length.toLong() }
        synchronized(lock) {
            sweepLocked()
            if (conversationKey != null && refreshDisabledLocked(conversationKey)) return
            val key = "t${seq++}"
            entries[key] = Entry(conversationKey, toolIds, envelopes, bytes, clock())
            toolIds.forEach { byScopedToolId[scoped(conversationKey, it)] = key }
            totalBytes += bytes
            evictToBoundLocked(writing = conversationKey)
        }
    }

    /** Evict the oldest CONVERSATION wholesale, not just its oldest round. Oldest-first by round is
     *  the worst possible choice for prefix stability: the oldest round's reasoning sits EARLIEST in
     *  the input array, so dropping it invalidates every token after it (see
     *  [touchConversationLocked]). Dropping a whole conversation costs that one its injection but
     *  keeps every surviving conversation prefix-stable. */
    private fun evictToBoundLocked(writing: String?) {
        while (entries.size > maxEntries || totalBytes > maxTotalBytes) {
            val oldest = entries.entries.firstOrNull() ?: break
            val victim = oldest.value.conversationKey
            if (victim == null) {
                // Unscoped entries have no conversation group to evict as a unit.
                removeLocked(oldest.key)
            } else {
                // A conversation that overflows the bound BY ITSELF cannot be held whole, so it is
                // marked non-injectable rather than trimmed round by round (see [disabled]).
                if (victim == writing) disableLocked(victim)
                entries.filterValues { it.conversationKey == victim }.keys.toList()
                    .forEach { removeLocked(it) }
            }
        }
    }

    private fun disableLocked(key: String) {
        disabled.remove(key)
        disabled[key] = clock()
        while (disabled.size > maxEntries) disabled.remove(disabled.keys.first())
    }

    /** True when [key] names a disabled conversation, refreshing its marker so an ACTIVE one STAYS
     *  disabled — re-enabling mid-conversation restarts the overflow/wipe oscillation the marker
     *  exists to end. Re-insertion keeps insertion order == timestamp order for the sweep. */
    private fun refreshDisabledLocked(key: String): Boolean {
        if (!disabled.containsKey(key)) return false
        disabled.remove(key)
        disabled[key] = clock()
        return true
    }

    /** The ordered envelopes for the turn of THIS conversation that emitted [toolId], or null
     *  (miss = status quo; another conversation's identical id never resolves). */
    fun lookup(conversationKey: String?, toolId: String): List<String>? = synchronized(lock) {
        sweepLocked()
        if (conversationKey != null && refreshDisabledLocked(conversationKey)) return null
        val key = byScopedToolId[scoped(conversationKey, toolId)] ?: return null
        val envelopes = entries[key]?.envelopes
        if (envelopes != null) touchConversationLocked(conversationKey)
        envelopes
    }

    /** PREFIX STABILITY (2026-07-30): a conversation's entries live and die TOGETHER.
     *  The builder injects each round's reasoning immediately before that round's FIRST
     *  function_call, so losing ONE round mid-conversation deletes an item from the middle of the
     *  input array and shifts every item after it. Turn N+1 then stops being a prefix-extension of
     *  turn N, and OpenAI — which reuses the longest stable prefix — re-bills the entire remainder.
     *  Measured offline against this builder: evicting the single OLDEST entry of an 8-round
     *  conversation dropped prefix reuse from 100% to 7.7%. Against live telemetry the same
     *  signature accounts for 1,349 turns at =<25% prefix reuse.
     *  Refreshing the WHOLE conversation on any lookup means an active conversation never
     *  partially expires; an idle one expires wholesale, which is a single clean transition to
     *  no-injection (stable thereafter) instead of continuous per-round churn.
     *  Re-inserting in iteration order preserves insertion-order == timestamp-order, the invariant
     *  sweepLocked's takeWhile early-exit depends on. */
    private fun touchConversationLocked(conversationKey: String?) {
        // Unscoped entries (null key = a first user message with no text to hash) share one
        // namespace across conversations and cannot be grouped; leave them on plain insertion TTL.
        if (conversationKey == null) return
        val now = clock()
        entries.entries.filter { it.value.conversationKey == conversationKey }.map { it.key }
            .forEach { k -> entries.remove(k)?.let { entries[k] = it.copy(at = now) } }
    }

    /** Drop every turn containing [toolId] — used when upstream rejects its envelopes as stale.
     *  Deliberately UNscoped (the amend path has no conversation context): a cross-conversation
     *  collision here evicts a healthy entry, which degrades to a miss, never a wrong injection.
     *  O(entries) scan; the path is a rare 400 recovery over ≤[maxEntries] entries. */
    fun evictByToolId(toolId: String) {
        synchronized(lock) {
            entries.filterValues { toolId in it.toolIds }.keys.toList().forEach { removeLocked(it) }
        }
    }

    private fun sweepLocked() {
        val cutoff = clock() - ttlMs
        // Disabled markers lapse on the same idle timer (safe: see [disabled]).
        disabled.entries.takeWhile { it.value < cutoff }.map { it.key }.forEach { disabled.remove(it) }
        val expired = entries.entries.takeWhile { it.value.at < cutoff }.map { it.key }
        if (expired.isEmpty()) return
        // Wholesale by conversation: a half-swept conversation is exactly the prefix-shifting
        // state this cache must never produce (see touchConversationLocked). Collect the doomed
        // keys BEFORE removing, then drop every remaining entry that belongs to one of them.
        val doomed = expired.mapNotNull { entries[it]?.conversationKey }.toSet()
        expired.forEach { removeLocked(it) }
        if (doomed.isNotEmpty()) {
            entries.filterValues { it.conversationKey in doomed }.keys.toList().forEach { removeLocked(it) }
        }
    }

    private fun removeLocked(key: String) {
        val e = entries.remove(key) ?: return
        e.toolIds.forEach { byScopedToolId.remove(scoped(e.conversationKey, it)) }
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

/** The ONE gate for every reasoning-cache touch point (capture, collect, lookup, include-widening):
 *  quirks-enabled AND not a compaction turn. Named so the `!compact` conjunct is a tested seam
 *  instead of four copy-pasted lambda conditions (review 2026-07-24: nothing pinned the conjunct;
 *  a regression dropping it would have let compaction turns read and write the cache unseen). */
internal fun reasoningCacheActive(quirks: ResponsesQuirks, compact: Boolean): Boolean =
    quirks.reasoningCache && !compact

/** RC-4: the invalid_encrypted_content recovery — strip every reasoning input item from the
 *  request (degrade to per-item amnesia, never fail the turn on cache contents) and evict the
 *  cache entries for ONLY the rounds those items belonged to, i.e. the function_calls that
 *  immediately follow each dropped reasoning item up to the next one (review 2026-07-24: evicting
 *  every function_call in the re-sent history wiped every still-fresh round's entry on a single
 *  stale 400, ending cache continuity for the rest of the conversation's life).
 *  Returns null when the body carries no reasoning items (the amendment is not ours to make).
 *  Decode/encode rides the closed ResponsesRequest DTO (#924) — no field invented or lost. */
internal fun stripStaleReasoning(bodyJson: String, cache: ReasoningCache): String? {
    val previous = kotlinx.serialization.json.Json.parseToJsonElement(bodyJson).jsonObject
    val base = responsesRequestJson.decodeFromJsonElement(ResponsesRequest.serializer(), previous)
    val walk = StaleReasoningWalk(cache)
    val kept = buildJsonArray { base.input.forEach { walk.visit(this, it) } }
    if (walk.dropped == 0) return null
    val next = base.copy(input = kept)
    return responsesRequestJson.encodeToJsonElement(ResponsesRequest.serializer(), next).toString()
}

/** The strip's item walk: drop reasoning items, and evict ONLY the rounds they belonged to — a
 *  round is [reasoning, function_call+, …], so the scope is the unbroken run of calls right after
 *  a dropped item; an orphan call later in the transcript keeps its healthy entry. */
private class StaleReasoningWalk(private val cache: ReasoningCache) {
    var dropped = 0
    private var inDroppedRound = false

    fun visit(sink: JsonArrayBuilder, el: JsonElement) {
        val item = el as? JsonObject
        when (item?.get(FIELD_TYPE).str()) {
            "reasoning" -> {
                dropped++
                inDroppedRound = true
            }
            "function_call" -> {
                sink.add(el)
                if (inDroppedRound) item?.get("call_id").str()?.let { cache.evictByToolId(it) }
            }
            else -> {
                sink.add(el)
                inDroppedRound = false
            }
        }
    }

    private companion object {
        const val FIELD_TYPE = "type"
    }
}
