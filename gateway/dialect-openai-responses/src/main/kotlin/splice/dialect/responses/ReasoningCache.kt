// NEW: (RC-2, reasoning-cache campaign 2026-07-24) gateway-held reasoning continuity. codex-rs
// keeps the model's encrypted reasoning items in its in-process history and re-sends them on
// every tool round-trip (store:false stateless full replay — client.rs:888/:915); splice with
// replay_reasoning=false dropped them, giving gpt-5.6 amnesia at every tool result (repeated
// tool calls, duplicated reasoning — operator report 2026-07-24). This cache holds each round's
// envelopes keyed by its REAL function_call ids so the builder can reinject the plan in-position
// when the tool results come back. Entries are READ, not consumed — a client-retried request
// re-reads the same envelopes (grace by design). Losing a conversation (restart, eviction, idle
// TTL) degrades to the no-injection status quo, never to an error (NEVER-BELOW-STATUS-QUO law).
//
// REWORKED 2026-07-31 (review of #71, round 2): the CONVERSATION is the primary record, not the
// round. The builder injects each round's reasoning at a fixed mid-array position, so any policy
// that removes SOME of a conversation's rounds (per-round TTL, oldest-round eviction, per-round
// stale eviction) shifts the input array mid-prefix and re-bills the remainder — the 342M-token
// drain prompt-cache-drain.md measures. Round-granular policies retrofitted onto a flat map kept
// leaking that state (four confirmed holes: neighbor-pressure misfire of the disable marker, the
// 256-round wipe+disable cliff, unmarked cross-eviction oscillation, per-round stale eviction), so
// the record now IS the conversation: one idle timestamp, one frozen flag, one rounds map.
// Policies fall out: touch is O(1) re-insertion; idle expiry and stale eviction are wholesale by
// construction; bound pressure evicts the least-recently-touched NEIGHBOR whole; and a
// conversation that alone exceeds the bound FREEZES ADMISSION — the offered round (never yet
// injected) is rejected and every admitted round keeps serving, which costs the tail its
// injection instead of busting the whole prefix the way wipe+disable did.
package splice.dialect.responses

import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonObject
import splice.core.util.JsonScalars
import splice.core.util.MonoClock

internal class ReasoningCache(
    private val maxEntries: Int = MAX_ENTRIES,
    private val maxTotalBytes: Long = MAX_TOTAL_BYTES,
    private val ttlMs: Long = TTL_MS,
    // Monotonic, not wall clock: both sweeps' takeWhile early-exits are sound only while
    // iteration order matches timestamp order — an NTP step backward would break that invariant
    // and leave an expired record unswept (review 2026-07-24; same reasoning as UpstreamClient).
    private val clock: () -> Long = MonoClock::nowMs,
    /** Daemon log sink for the two one-way transitions worth an operator's eye (freeze, bound
     *  eviction). Defaults to a no-op so tests need not thread it. */
    private val log: (String) -> Unit = {},
) {

    private data class Round(val toolIds: List<String>, val envelopes: List<String>, val bytes: Long, val at: Long)

    /** One conversation: rounds in arrival order, ONE idle timestamp, ONE admission flag. Every
     *  id of a round maps to that round; a lookup by ANY of them yields the round's ordered
     *  envelopes (inject-once stays the BUILDER's duty — this is a plain keyed store). */
    private class Convo {
        val rounds = LinkedHashMap<String, Round>()
        val byToolId = HashMap<String, String>()
        var bytes = 0L
        var at = 0L
        var frozen = false
    }

    // Iteration order = least-recently-TOUCHED first (touch re-inserts; MonoClock keeps `at`
    // monotone with re-insertion order, which sweepLocked's takeWhile depends on).
    private val convos = LinkedHashMap<String, Convo>()

    // The null-key class (first user message with no text to hash — image-first or tool_result-
    // first openers) has no grouping identity, so it keeps the ORIGINAL flat per-round insertion
    // TTL and shares one id namespace, exactly the pre-rework behavior. Documented limitation:
    // that class retains the old mid-conversation-expiry pathology (spike doc, "not fixed").
    private val nullRounds = LinkedHashMap<String, Round>()
    private val nullByToolId = HashMap<String, String>()

    private var roundCount = 0
    private var totalBytes = 0L
    private var seq = 0L
    private val lock = Any()

    fun put(conversationKey: String?, toolIds: List<String>, envelopes: List<String>) {
        if (toolIds.isEmpty() || envelopes.isEmpty()) return
        val bytes = envelopes.sumOf { it.length.toLong() }
        synchronized(lock) {
            sweepLocked()
            if (conversationKey == null) {
                putNullLocked(toolIds, envelopes, bytes)
            } else {
                putConvoLocked(conversationKey, toolIds, envelopes, bytes)
            }
        }
    }

    /** The ordered envelopes for the round of THIS conversation that emitted [toolId], or null
     *  (miss = status quo; another conversation's identical id never resolves — per-conversation
     *  id maps; the null-key class shares one namespace as before). Touching refreshes the WHOLE
     *  conversation: active conversations never partially expire. */
    fun lookup(conversationKey: String?, toolId: String): List<String>? = synchronized(lock) {
        sweepLocked()
        if (conversationKey == null) return nullByToolId[toolId]?.let { nullRounds[it]?.envelopes }
        val convo = touchLocked(conversationKey) ?: return null
        convo.byToolId[toolId]?.let { convo.rounds[it]?.envelopes }
    }

    /** Every round of [conversationKey] as toolId -> envelopes in ONE atomic read with ONE touch.
     *  The builder walks N tool_use blocks per build; N independent lookups can tear across a
     *  concurrent eviction (rounds 1..k injected, k+1.. missing — the forbidden partial shape,
     *  review finding 14) and re-touch the conversation N times (finding 10). A snapshot cannot
     *  tear and costs one lock acquisition per build. */
    fun snapshot(conversationKey: String?): Map<String, List<String>> = synchronized(lock) {
        sweepLocked()
        if (conversationKey == null) {
            return nullByToolId.entries.associate { (id, rk) -> id to nullRounds.getValue(rk).envelopes }
        }
        val convo = touchLocked(conversationKey) ?: return emptyMap()
        convo.byToolId.entries.associate { (id, rk) -> id to convo.rounds.getValue(rk).envelopes }
    }

    /** Upstream rejected [toolId]'s envelopes as stale: drop the WHOLE conversation that carried
     *  it. Per-round eviction (the old RC-4 shape) left the surviving rounds injecting around a
     *  permanent mid-array hole — permanent because active conversations no longer age out. A
     *  wholesale drop is one clean transition to no-injection; the conversation re-caches fresh
     *  rounds from its next turn onward, which appends at the tail and shifts nothing.
     *  Deliberately UNscoped (the amend path has no conversation context): a cross-conversation
     *  call_id collision over-evicts a healthy conversation, which costs a miss, never a wrong
     *  injection. */
    fun evictByToolId(toolId: String) {
        synchronized(lock) {
            convos.filterValues { toolId in it.byToolId }.keys.toList().forEach {
                log("[reasoning-cache] stale-400 evicted conversation ${it.take(KEY_LOG_CHARS)}… whole")
                removeConvoLocked(it)
            }
            nullByToolId[toolId]?.let { removeNullLocked(it) }
        }
    }

    // ── internals ────────────────────────────────────────────────────────────────────────────

    /** Re-insert [key] at the most-recently-touched end with a fresh timestamp, or null if the
     *  conversation is not held. O(1): the whole point of conversation-primary records. */
    private fun touchLocked(key: String): Convo? =
        convos.remove(key)?.also {
            it.at = clock()
            convos[key] = it
        }

    private fun putConvoLocked(key: String, toolIds: List<String>, envelopes: List<String>, bytes: Long) {
        val convo = touchLocked(key) ?: Convo().also {
            it.at = clock()
            convos[key] = it
        }
        if (convo.frozen) return // admission frozen; admitted rounds keep serving
        // Client-retry grace: an id we already hold is a re-capture of the same round. Admitting
        // it again would orphan the old round, which still counts against the bound (review
        // finding 2's accelerator) — refresh (the touch above) and return instead.
        if (toolIds.any { it in convo.byToolId }) return
        val rk = "r${seq++}"
        convo.rounds[rk] = Round(toolIds, envelopes, bytes, convo.at)
        toolIds.forEach { convo.byToolId[it] = rk }
        convo.bytes += bytes
        totalBytes += bytes
        roundCount++
        evictToBoundLocked(writing = key, writer = convo, offered = rk)
    }

    private fun putNullLocked(toolIds: List<String>, envelopes: List<String>, bytes: Long) {
        val rk = "n${seq++}"
        nullRounds[rk] = Round(toolIds, envelopes, bytes, clock())
        toolIds.forEach { nullByToolId[it] = rk }
        totalBytes += bytes
        roundCount++
        evictToBoundLocked(writing = null, writer = null, offered = null)
    }

    /** Restore the bounds. Order: null-class rounds first (the least-guaranteed class; one round
     *  costs one miss), then whole least-recently-touched NEIGHBOR conversations — never the
     *  writer (review finding 1: neighbor pressure must not punish the active conversation).
     *  If the writer alone still exceeds the bound, FREEZE ADMISSION: reject [offered] (never
     *  yet injected, so rejecting it shifts nothing) and keep everything admitted. Admitted
     *  rounds were each admitted under the bound, so rejecting the offered round always restores
     *  the invariant — the loop cannot spin (the false-return guard is unreachable from put()
     *  and exists only to make non-progress impossible by construction). */
    private fun evictToBoundLocked(writing: String?, writer: Convo?, offered: String?) {
        while (roundCount > maxEntries || totalBytes > maxTotalBytes) {
            val oldestNull = nullRounds.keys.firstOrNull()
            val neighbor = if (oldestNull == null) convos.keys.firstOrNull { it != writing } else null
            when {
                oldestNull != null -> removeNullLocked(oldestNull)
                neighbor != null -> evictNeighborLocked(neighbor)
                else -> if (!freezeWriterLocked(writing, writer, offered)) return
            }
        }
    }

    private fun evictNeighborLocked(key: String) {
        log(
            "[reasoning-cache] bound pressure evicted conversation " +
                "${key.take(KEY_LOG_CHARS)}… whole (${convos.getValue(key).rounds.size} rounds)",
        )
        removeConvoLocked(key)
    }

    /** Reject the round just offered and freeze admission for the writer. True when the offered
     *  round was removed (progress guaranteed); false only on the defensive no-writer path. */
    private fun freezeWriterLocked(writing: String?, writer: Convo?, offered: String?): Boolean {
        if (writer == null || offered == null) return false
        val round = writer.rounds.remove(offered) ?: return false
        round.toolIds.forEach { writer.byToolId.remove(it) }
        writer.bytes -= round.bytes
        totalBytes -= round.bytes
        roundCount--
        if (!writer.frozen) {
            writer.frozen = true
            log(
                "[reasoning-cache] conversation ${writing?.take(KEY_LOG_CHARS)}… froze admission at " +
                    "${writer.rounds.size} rounds/${writer.bytes}B (alone over the bound); " +
                    "admitted rounds keep serving",
            )
        }
        return true
    }

    private fun sweepLocked() {
        val cutoff = clock() - ttlMs
        // Null-class: flat per-round INSERTION TTL (never touched, so insertion order = age order).
        nullRounds.entries.takeWhile { it.value.at < cutoff }.map { it.key }.toList()
            .forEach { removeNullLocked(it) }
        // Conversations expire WHOLESALE on idle — half a conversation is the one state this
        // cache must never serve. An active conversation is re-touched every build, so only a
        // genuinely idle one lapses; its frozen flag (if any) dies with it, and a later resume
        // re-caches from its next round onward (tail-append, prefix-stable).
        convos.entries.takeWhile { it.value.at < cutoff }.map { it.key }.toList()
            .forEach { removeConvoLocked(it) }
    }

    private fun removeConvoLocked(key: String) {
        val convo = convos.remove(key) ?: return
        totalBytes -= convo.bytes
        roundCount -= convo.rounds.size
    }

    private fun removeNullLocked(roundKey: String) {
        val round = nullRounds.remove(roundKey) ?: return
        // Remove an id ONLY while it still points at THIS round: the null-key class shares one id
        // namespace, so a newer round re-using a tool id has already overwritten the index, and an
        // unconditional remove would delete the LIVE mapping and lose the newer round's reasoning
        // (review of #72).
        round.toolIds.forEach { toolId -> if (nullByToolId[toolId] == roundKey) nullByToolId.remove(toolId) }
        totalBytes -= round.bytes
        roundCount--
    }
}

// The ReasoningCache bounds, at file scope because Kotlin main sources carry no `companion` blocks.
// The TTL is an IDLE timer for keyed conversations (each build re-touches), an insertion TTL for the
// null-key class. 30 min of genuine inactivity, with an order of magnitude over any realistic
// tool-loop gap (eli risk 4).
private const val TTL_MS: Long = 30 * 60 * 1000L

// Total ROUNDS across all conversations on the head (one entry per tool round).
private const val MAX_ENTRIES: Int = 256
private const val MAX_TOTAL_BYTES: Long = 64L * 1024 * 1024

// "splice-" + 7 hash chars: identifiable in logs, not noisy.
private const val KEY_LOG_CHARS: Int = 14

/**
 * The reasoning cache's two policy seams, as a type rather than the file-level functions they used
 * to be (Kotlin main sources carry no top-level functions). Stateless; both members keep their old
 * name and argument list, so a call site only gained a receiver.
 */
internal class ReasoningCachePolicy {

    /** The ONE gate for every reasoning-cache touch point (capture, collect, lookup,
     *  include-widening): quirks-enabled AND not a compaction turn. Named so the `!compact`
     *  conjunct is a tested seam instead of four copy-pasted lambda conditions (review 2026-07-24:
     *  nothing pinned the conjunct; a regression dropping it would have let compaction turns read
     *  and write the cache unseen). */
    fun reasoningCacheActive(quirks: ResponsesQuirks, compact: Boolean): Boolean =
        quirks.reasoningCache && !compact

    /** RC-4: the invalid_encrypted_content recovery — strip every reasoning input item from the
     *  request (degrade to per-item amnesia, never fail the turn on cache contents) and evict the
     *  cache for the rounds those items belonged to, i.e. the function_calls that immediately follow
     *  each dropped reasoning item up to the next one. Eviction is conversation-wholesale
     *  (2026-07-31, review of #71 round 2): the old per-round eviction left the surviving rounds
     *  injecting around a permanent hole, shifting the prefix on every later build.
     *  Returns null when the body carries no reasoning items (the amendment is not ours to make).
     *  Decode/encode rides the closed ResponsesRequest DTO (#924) — no field invented or lost. */
    fun stripStaleReasoning(bodyJson: String, cache: ReasoningCache): String? {
        val previous = kotlinx.serialization.json.Json.parseToJsonElement(bodyJson).jsonObject
        val base = responsesRequestJson.decodeFromJsonElement(ResponsesRequest.serializer(), previous)
        val walk = StaleReasoningWalk(cache)
        val kept = buildJsonArray { base.input.forEach { walk.visit(this, it) } }
        if (walk.dropped == 0) return null
        val next = base.copy(input = kept)
        return responsesRequestJson.encodeToJsonElement(ResponsesRequest.serializer(), next).toString()
    }
}

/** The strip's item walk: drop reasoning items, and evict the rounds they belonged to — a round
 *  is [reasoning, function_call+, …], so the scope is the unbroken run of calls right after a
 *  dropped item. (The cache widens each eviction to the whole conversation; see evictByToolId.) */
private class StaleReasoningWalk(private val cache: ReasoningCache) {
    var dropped = 0
    private var inDroppedRound = false

    fun visit(sink: JsonArrayBuilder, el: JsonElement) {
        val item = el as? JsonObject
        when (JsonScalars.str(item?.get(WALK_FIELD_TYPE))) {
            "reasoning" -> {
                dropped++
                inDroppedRound = true
            }
            "function_call" -> {
                sink.add(el)
                if (inDroppedRound) JsonScalars.str(item?.get("call_id"))?.let { cache.evictByToolId(it) }
            }
            else -> {
                sink.add(el)
                inDroppedRound = false
            }
        }
    }
}

private const val WALK_FIELD_TYPE = "type"
