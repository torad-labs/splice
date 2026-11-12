// NEW: (RC-2, reasoning-cache campaign 2026-07-24) gateway-held reasoning continuity. codex-rs
// keeps the model's encrypted reasoning items in its in-process history and re-sends them on
// every tool round-trip (store:false stateless full replay — client.rs:888/:915); splice with
// replay_reasoning=false dropped them, giving gpt-5.6 amnesia at every tool result (repeated
// tool calls, duplicated reasoning — operator report 2026-07-24). This cache holds each round's
// envelopes keyed by its REAL function_call ids so the builder can reinject the plan in-position
// when the tool results come back. Entries are READ, not consumed — a client-retried request
// re-reads the same envelopes (grace by design), until the retry stores its own reasoning at that
// history position, which replaces them (2026-11-09; ResponsesTurnSeams). Losing a conversation (restart, eviction, idle
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
//
// REWORKED 2026-11-09: the freeze also comes per conversation. The bounds were head-wide only, so one
// conversation grew by evicting every neighbor whole until it froze alone, and the next put from anyone
// evicted it whole in turn. Now a conversation freezes at its own share (ReasoningConversationBound), and
// head-wide pressure is left for many conversations together.
//
// Policy + RC-4 walk live in ReasoningCachePolicy.kt so this file is the store only
// (concentration, 2026-08-19).
//
// REWORKED 2026-09-26 (V4-334): a keyed conversation lasts until its compaction, as codex-rs keeps every
// reasoning item until compaction (context_manager/history.rs:944-960), and it outlives the process. The
// idle TTL no longer applies to it: a 31-minute pause dropped a live conversation whole, and each restart
// dropped all of them (0/634 pre-restart tool steps carried reasoning after the 2026-09-26 restarts). The
// bounds below stay the only other exit. With a directory (ReasoningCacheFiles) every keyed conversation
// is also on disk, restored before the first read: disk and memory hold the same conversations, except
// one whose file could not be written, which lives in memory only. Writes are queued under the lock and
// applied after it (ReasoningCacheWrites), so no request waits on the disk.
//
// TWO DAEMONS, ONE DIRECTORY (#525, 2026-11-12): at a restart the retiring daemon finishes the answers it was giving
// beside the next one, and records their reasoning in the same files. The next daemon restored those conversations
// before that, so each call that names a conversation first looks at its file ([ReasoningCache.adopt]): one that
// another process wrote since is read again, and what it holds replaces what this process held. Without it the next
// turn of that conversation was sent without the reasoning of the answer the client had just been given.
//
// REWORKED 2026-10-26 (fix 3): "after it" now means on the head's one background writer, never on the thread that
// made the change. That thread is often a session's stream inside its upstream read loop, and it used to apply the
// whole queue itself, every session's records forced to disk one by one. The head's stop drains that writer
// (ResponsesParts.onHeadStop), so a restart still restores every record.
package splice.dialect.responses.reasoning

import splice.core.util.ElapsedClock
import splice.core.util.LogSink
import splice.core.util.MonoClock

internal class ReasoningCache(
    private val maxEntries: Int = MAX_ENTRIES,
    private val maxTotalBytes: Long = REASONING_BOUND_BYTES,
    private val ttlMs: Long = TTL_MS,
    // Monotonic, not wall clock: the null-key sweep's takeWhile early-exit is sound only while
    // iteration order matches timestamp order — an NTP step backward would break that invariant
    // and leave an expired record unswept (review 2026-07-24; same reasoning as UpstreamClient).
    private val clock: ElapsedClock = ElapsedClock(MonoClock::nowMs),
    /** Daemon log sink for the one-way transitions worth an operator's eye (freeze, bound
     *  eviction, compaction, a file that did not read back). Defaults to a no-op so tests need not
     *  thread it. */
    private val log: LogSink = LogSink {},
    /** V4-334: the lane that writes keyed conversations to disk, so they outlive the process; one without files
     *  keeps them in memory only. Since fix 3 (2026-10-26) it writes behind the turns, and its owner drains it when
     *  the head stops (ResponsesParts.onHeadStop). */
    private val writes: ReasoningCacheWrites = ReasoningCacheWrites(null),
    /** One conversation's share of the head's bounds (2026-11-09): past it the conversation freezes on its own,
     *  without taking a byte from its neighbors. */
    private val conversationBound: ReasoningConversationBound = ReasoningConversationBound(),
) : ReasoningCacheEvictions {

    // Iteration order = least-recently-TOUCHED first (touch re-inserts). Bound pressure evicts from
    // the front.
    private val convos = TouchOrdered()

    // The null-key class (first user message with no text to hash — image-first or tool_result-
    // first openers) has no grouping identity, so it keeps the ORIGINAL flat per-round insertion
    // TTL and shares one id namespace, exactly the pre-rework behavior. Documented limitation:
    // that class retains the old mid-conversation-expiry pathology (spike doc, "not fixed"), and with
    // no conversation to name a file after, it is never written to disk.
    private val nullRounds = LinkedHashMap<String, ReasoningCacheRound>()
    private val nullByToolId = HashMap<String, String>()

    private var roundCount = 0
    private var totalBytes = 0L
    private var seq = 0L
    private val lock = Any()
    private val positions = ReasoningCachePositions()
    private val proofs = ReasoningCacheProofs()
    private val persistence = ReasoningRoundPersistence(writes, positions, proofs, log)

    // What the last process left on disk, published once before any read or write sees the maps: a
    // restarted daemon's first request must find it. The directory is read under the lazy's own lock,
    // never under `lock`; conversations are published least recently written first, and the bounds
    // apply to them as to any other.
    private val restored: Lazy<Unit> = lazy {
        val stored = writes.restore()
        synchronized(lock) {
            stored.forEach { holdLocked(it.key, it) }
            evictToBoundLocked(writing = null, writer = null, offered = null)
        }
        writes.wake()
    }

    /** #525: [key]'s file was written by another process since this one last read or wrote it, so what the file
     *  holds replaces what this cache holds of the conversation. Every call that names a conversation comes through
     *  here first. It costs one look at the file; the read happens only when the look says another process wrote.
     *  That read runs under the cache's lock, the one place the lock is held across the disk, and only while no
     *  write of this process's own is on its way to that file: such a write is queued under the same lock, so a
     *  round admitted here is either in the file that is read or still to come, in which case the look says nothing
     *  and the call after it lands looks again. */
    private fun adopt(key: String?) {
        if (key == null || !writes.writtenElsewhere(key)) return
        synchronized(lock) {
            if (!writes.writtenElsewhere(key)) return
            val stored = writes.reread(key)
            holdLocked(key, stored)
            evictToBoundLocked(writing = null, writer = null, offered = null)
            log(
                "[reasoning-cache] conversation ${key.take(KEY_LOG_CHARS)}… was written by another daemon: read " +
                    "again, ${stored?.rounds?.size ?: 0} record(s)",
            )
        }
        writes.wake()
    }

    /** [replacesPosition]: [toolIds] is one history-position record, the first its turn stores at that position. What
     *  other turns left there goes first (2026-11-09): a turn reaches a position another turn already stored at only
     *  when the client re-sent a request whose earlier attempt it never kept, so that reasoning follows nothing in
     *  the history. A turn's records reach this put only once the client holds what they led into, or a round of the
     *  turn reached its terminal (ResponsesTurnSeams), so an attempt cut before either stored nothing to replace. */
    fun put(
        conversationKey: String?,
        toolIds: List<String>,
        envelopes: List<String>,
        replacesPosition: Boolean = false,
    ) {
        if (toolIds.isEmpty() || envelopes.isEmpty()) return
        restored.value
        adopt(conversationKey)
        val bytes = envelopes.sumOf { it.length.toLong() } + toolIds.sumOf(positions::metadataBytes)
        synchronized(lock) {
            sweepLocked()
            if (conversationKey == null) {
                val rk = "n${seq++}"
                nullRounds[rk] = ReasoningCacheRound(toolIds, envelopes, bytes, clock())
                toolIds.forEach { nullByToolId[it] = rk }
                totalBytes += bytes
                roundCount++
                evictToBoundLocked(writing = null, writer = null, offered = null)
            } else {
                // Ahead of the freeze: the replaced records sit where this turn's own history reached, so nothing
                // before them moves in any request, and a conversation they froze stops sending them.
                if (replacesPosition) {
                    convos[conversationKey]?.let { persistence.replaceAt(conversationKey, it, toolIds) }?.let {
                        totalBytes -= it.bytes
                        roundCount -= it.records
                    }
                }
                putConvoLocked(conversationKey, toolIds, envelopes, bytes)
            }
        }
        writes.wake()
    }

    /** The ordered envelopes for the round of THIS conversation that emitted [toolId], or null
     *  (miss = status quo; another conversation's identical id never resolves — per-conversation
     *  id maps; the null-key class shares one namespace as before). Touching refreshes the WHOLE
     *  conversation: active conversations never partially expire. */
    fun lookup(conversationKey: String?, toolId: String): List<String>? {
        restored.value
        adopt(conversationKey)
        return synchronized(lock) {
            sweepLocked()
            if (conversationKey == null) return nullByToolId[toolId]?.let { nullRounds[it]?.envelopes }
            val convo = convos.touch(conversationKey) ?: return null
            convo.byToolId[toolId]?.let { convo.rounds[it]?.envelopes }
        }
    }

    /** Every round of [conversationKey] as toolId -> envelopes in ONE atomic read with ONE touch.
     *  The builder walks N tool_use blocks per build; N independent lookups can tear across a
     *  concurrent eviction (rounds 1..k injected, k+1.. missing — the forbidden partial shape,
     *  review finding 14) and re-touch the conversation N times (finding 10). A snapshot cannot
     *  tear and costs one lock acquisition per build. */
    fun snapshot(
        conversationKey: String?,
        legacyConversationKey: String? = conversationKey,
    ): Map<String, List<String>> = view(conversationKey, legacyConversationKey).rounds

    fun view(conversationKey: String?, legacyConversationKey: String? = conversationKey): ReasoningCacheView {
        restored.value
        listOfNotNull(legacyConversationKey, conversationKey).distinct().forEach(::adopt)
        return synchronized(lock) {
            sweepLocked()
            if (conversationKey == null) {
                return ReasoningCacheView(
                    nullRounds.values.flatMap { round -> round.toolIds.map { it to round.envelopes } }.toMap(),
                )
            }
            // Arrival order also orders private history-position indexes sharing a visible boundary.
            val rounds = listOfNotNull(legacyConversationKey, conversationKey).distinct().flatMap { key ->
                convos.touch(key)?.rounds?.values.orEmpty()
            }
            ReasoningCacheView(
                rounds.flatMap { round -> round.toolIds.map { it to round.envelopes } }.toMap(),
                convos[conversationKey]?.resultProofs?.toMap().orEmpty(),
            )
        }
    }

    /** Upstream rejected [toolId]'s envelopes as stale: drop the WHOLE conversation that carried
     *  it. Per-round eviction (the old RC-4 shape) left the surviving rounds injecting around a
     *  permanent mid-array hole — permanent because active conversations no longer age out. A
     *  wholesale drop is one clean transition to no-injection; the conversation re-caches fresh
     *  rounds from its next turn onward, which appends at the tail and shifts nothing.
     *  Deliberately UNscoped (the amend path has no conversation context): a cross-conversation
     *  call_id collision over-evicts a healthy conversation, which costs a miss, never a wrong
     *  injection. */

    /** Stale encrypted items also occur before prose or by themselves, without any following tool id.
     * Scan once per failed request and drop every affected conversation whole, including its file. */
    override fun evictRejectedRounds(match: CachedReasoningMatch) {
        restored.value
        synchronized(lock) {
            convos.filterValues { convo -> convo.rounds.values.any { match(it) } }.keys.toList().forEach {
                log("[reasoning-cache] stale-400 evicted conversation ${it.take(KEY_LOG_CHARS)}… whole")
                holdLocked(it, null)
                writes.drop(it)
            }
            nullRounds.filterValues { match(it) }.keys.toList().forEach(::removeNullLocked)
        }
        writes.wake()
    }

    /** V4-334: [conversationKey] compacted, so its reasoning is done — the client carries on under a
     *  new opening, and codex-rs drops its reasoning items at the same point. Dropped whole, file
     *  included. The null-key class has no conversation to end. */
    fun dropConversation(conversationKey: String?) {
        if (conversationKey == null) return
        restored.value
        synchronized(lock) {
            val convo = convos[conversationKey] ?: return@synchronized
            log(
                "[reasoning-cache] conversation ${conversationKey.take(KEY_LOG_CHARS)}… compacted: " +
                    "dropped whole (${convo.rounds.size} rounds)",
            )
            holdLocked(conversationKey, null)
            writes.drop(conversationKey)
        }
        writes.wake()
    }

    /** An unchanged legacy transcript proved this old digest; move its items to the stable grammar. */
    fun updateHistory(conversationKey: String, update: ReasoningHistoryUpdate) {
        if (update.aliases.isEmpty() && update.proofs.isEmpty()) return
        restored.value
        adopt(conversationKey)
        synchronized(lock) {
            val conversation = convos[conversationKey] ?: ReasoningCacheConvo()
            val moved = positions.promote(conversation, update.aliases)
            val admitted = proofs.merge(conversation, update.proofs, maxTotalBytes - totalBytes)
            if (moved || admitted.changed) {
                convos[conversationKey] = conversation
                totalBytes += admitted.bytes
                if (moved) {
                    totalBytes += persistence.rewrite(conversationKey, conversation)
                } else if (conversation.rounds.isNotEmpty() && admitted.changed) {
                    // Proof-only evidence remains bounded in memory until the first admitted reasoning item.
                    writes.append(conversationKey, emptyList(), emptyList(), admitted.delta)
                }
            }
        }
        writes.wake()
    }

    // ── internals ────────────────────────────────────────────────────────────────────────────

    private fun putConvoLocked(key: String, toolIds: List<String>, envelopes: List<String>, bytes: Long) {
        val convo = convos.touch(key) ?: ReasoningCacheConvo().also { convos[key] = it }
        val updated = positions.update(convo, toolIds, conversationBound.available(convo, maxTotalBytes - totalBytes))
        if (updated != null) {
            totalBytes += updated.bytes
            if (updated.changed) totalBytes += persistence.rewrite(key, convo)
            return
        }
        if (convo.frozen) return // admission frozen; admitted rounds keep serving
        // Client-retry grace: an id we already hold is a re-capture of the same round. Admitting
        // it again would orphan the old round, which still counts against the bound (review
        // finding 2's accelerator) — refresh (the touch above) and return instead.
        if (toolIds.any { it in convo.byToolId }) return
        totalBytes += persistence.reclaimOrderMetadata(
            key,
            convo,
            bytes,
            conversationBound.available(convo, maxTotalBytes - totalBytes),
        )
        val offered = positions.admission(
            ReasoningCacheRound(toolIds, envelopes, bytes, clock()),
            conversationBound.available(convo, maxTotalBytes - totalBytes),
        )
        val rk = "r${seq++}"
        convo.rounds[rk] = offered
        offered.toolIds.forEach { convo.byToolId[it] = rk }
        convo.bytes += offered.bytes
        totalBytes += offered.bytes
        roundCount++
        evictToBoundLocked(writing = key, writer = convo, offered = rk)
        // Written only once it survived the bound: a round the freeze rejected was never served.
        if (rk in convo.rounds) {
            val admitted = convo.rounds.getValue(rk)
            positions.admitted(convo, rk, admitted)
            persistence.enqueue(key, convo, admitted)
        }
    }

    /** Restore the bounds. Order: null-class rounds first (the least-guaranteed class; one round
     *  costs one miss), then whole least-recently-touched NEIGHBOR conversations — never the
     *  writer (review finding 1: neighbor pressure must not punish the active conversation).
     *  If the writer alone still exceeds the bound, FREEZE ADMISSION: reject [offered] (never
     *  yet injected, so rejecting it shifts nothing) and keep everything admitted. Admitted
     *  rounds were each admitted under the bound, so rejecting the offered round always restores
     *  the invariant — the loop cannot spin (the false-return guard is unreachable from put()
     *  and exists only to make non-progress impossible by construction). */
    private fun evictToBoundLocked(writing: String?, writer: ReasoningCacheConvo?, offered: String?) {
        // Over its own share the writer freezes before it can press on any neighbor: on 2026-11-08/09 one
        // conversation took the whole head-wide bound six times and evicted 35 others whole on the way up.
        // Rejecting the offered round restores the state before this put, which was within the head's bounds.
        if (conversationBound.exceeded(writer)) {
            freezeWriterLocked(writing, writer, offered, OVER_SHARE)
            return
        }
        while (roundCount > maxEntries || totalBytes > maxTotalBytes) {
            val oldestNull = nullRounds.keys.firstOrNull()
            val neighbor = if (oldestNull == null) convos.keys.firstOrNull { it != writing } else null
            when {
                oldestNull != null -> removeNullLocked(oldestNull)
                neighbor != null -> {
                    log("[reasoning-cache] bound pressure evicted conversation ${neighbor.take(KEY_LOG_CHARS)}… whole")
                    holdLocked(neighbor, null)
                    writes.drop(neighbor)
                }
                else -> if (!freezeWriterLocked(writing, writer, offered, ALONE_OVER_BOUND)) return
            }
        }
    }

    /** Reject the round just offered ([offered] null: none, as on restore) and freeze admission for
     *  the writer. True when the offered round was removed (progress guaranteed); false on the
     *  defensive no-writer path. The offered round was never injected, so removing it shifts nothing. */
    private fun freezeWriterLocked(
        writing: String?,
        writer: ReasoningCacheConvo?,
        offered: String?,
        why: String,
    ): Boolean {
        if (writing == null || writer == null) return false
        if (offered != null) {
            val round = writer.rounds.remove(offered) ?: return false
            round.toolIds.forEach { writer.byToolId.remove(it) }
            writer.bytes -= round.bytes
            totalBytes -= round.bytes
            roundCount--
        }
        if (!writer.frozen) {
            writer.frozen = true
            log(
                "[reasoning-cache] conversation ${writing.take(KEY_LOG_CHARS)}… froze admission at " +
                    "${writer.rounds.size} rounds/${writer.bytes}B ($why); admitted rounds keep serving",
            )
        }
        return offered != null
    }

    private fun sweepLocked() {
        val cutoff = clock() - ttlMs
        // Null-class: flat per-round INSERTION TTL (never touched, so insertion order = age order).
        nullRounds.entries.takeWhile { it.value.at < cutoff }.map { it.key }.toList()
            .forEach { removeNullLocked(it) }
        // Keyed conversations do not expire on idle (V4-334): a paused session came back to no
        // reasoning at all. They end at their compaction (dropConversation), a stale 400, or bound
        // pressure, each wholesale.
    }

    /** What this cache holds of [key] becomes [stored], the conversation as the disk holds it; null, or one that
     *  recorded no round, holds nothing of it. The file is the caller's to keep or remove. */
    private fun holdLocked(key: String, stored: StoredConversation?) {
        convos.remove(key)?.let {
            totalBytes -= it.bytes
            roundCount -= it.rounds.size
        }
        val rebuilt = stored?.let { persistence.rebuilt(it, seq, clock()) } ?: return
        seq += rebuilt.convo.rounds.size
        roundCount += rebuilt.convo.rounds.size
        totalBytes += rebuilt.convo.bytes
        convos[key] = rebuilt.convo
        if (rebuilt.renormalized) totalBytes += persistence.rewrite(key, rebuilt.convo)
        // A record written under an older, wider bound keeps serving what it holds and admits nothing more.
        if (conversationBound.exceeded(rebuilt.convo)) {
            freezeWriterLocked(key, rebuilt.convo, offered = null, why = RESTORED_OVER_SHARE)
        }
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

/** The first reasoning record makes pending proofs durable; later records append without rewriting history. */
private class ReasoningRoundPersistence(
    private val writes: ReasoningCacheWrites,
    private val positions: ReasoningCachePositions,
    private val proofs: ReasoningCacheProofs,
    private val log: LogSink,
) {
    fun enqueue(key: String, conversation: ReasoningCacheConvo, round: ReasoningCacheRound) {
        val firstWithProofs = conversation.rounds.size == 1 && conversation.resultProofs.isNotEmpty()
        if (firstWithProofs) {
            writes.append(key, emptyList(), emptyList(), conversation.resultProofs.toMap())
        }
        writes.append(key, round.toolIds, round.envelopes)
    }

    fun reclaimOrderMetadata(
        key: String,
        conversation: ReasoningCacheConvo,
        offeredBytes: Long,
        availableBytes: Long,
    ): Long {
        if (offeredBytes <= availableBytes) return 0L
        val released = positions.releaseMetadata(conversation)
        return released + if (released != 0L) rewrite(key, conversation) else 0L
    }

    /** Drop what other turns stored at [toolIds]' history position, file included, before it is admitted. This turn
     *  reached that position through its own request or its own output, so the client kept nothing those turns
     *  produced from there: their reasoning would ride upstream in front of history it never led to. The bytes
     *  returned are what the cache's bound gets back, the rewrite's own change included. */
    fun replaceAt(key: String, conversation: ReasoningCacheConvo, toolIds: List<String>): ReplacedRecords {
        val index = toolIds.singleOrNull() ?: return ReplacedRecords(0, 0L)
        val replaced = positions.replaceAt(conversation, index)
        if (replaced.records == 0) return replaced
        val rewritten = rewrite(key, conversation)
        log(
            "[reasoning-cache] conversation ${key.take(KEY_LOG_CHARS)}… re-sent at one history position: " +
                "replaced ${replaced.records} records (${replaced.bytes}B) an earlier attempt left there",
        )
        return replaced.copy(bytes = replaced.bytes - rewritten)
    }

    fun rewrite(key: String, conversation: ReasoningCacheConvo): Long {
        val released = proofs.compact(conversation)
        writes.replace(key, positions.stored(conversation))
        return released
    }

    /** [stored] rebuilt as the cache holds a conversation, its rounds keyed from [firstSeq] on and stamped [at]. Null
     *  for one that recorded no round: nothing of it is held, and its file is removed. */
    fun rebuilt(stored: StoredConversation, firstSeq: Long, at: Long): RebuiltConvo? {
        val recorded = stored.rounds.filter { it.ids.isNotEmpty() }
        if (recorded.isEmpty()) {
            writes.drop(stored.key)
            return null
        }
        val convo = ReasoningCacheConvo()
        val normalized = positions.normalized(recorded)
        normalized.forEachIndexed { index, round ->
            val rk = "r${firstSeq + index}"
            val bytes = round.envelopes.sumOf { it.length.toLong() } + round.ids.sumOf(positions::metadataBytes)
            convo.rounds[rk] = ReasoningCacheRound(round.ids, round.envelopes, bytes, at)
            round.ids.forEach { convo.byToolId[it] = rk }
            positions.admitted(convo, rk, convo.rounds.getValue(rk))
            convo.bytes += bytes
        }
        proofs.restore(convo, stored.rounds)
        return RebuiltConvo(convo, renormalized = normalized != recorded)
    }
}

/** The keyed conversations, least recently touched first. */
private class TouchOrdered : LinkedHashMap<String, ReasoningCacheConvo>() {
    /** Re-insert [key] at the most-recently-touched end, or null if the conversation is not held.
     *  O(1): the whole point of conversation-primary records. */
    fun touch(key: String): ReasoningCacheConvo? = remove(key)?.also { this[key] = it }
}

/** A conversation rebuilt from its file; [renormalized] when its records changed on the way and are to be written
 *  again. */
private data class RebuiltConvo(val convo: ReasoningCacheConvo, val renormalized: Boolean)

// The ReasoningCache bounds, at file scope because Kotlin main sources carry no `companion` blocks.
// The TTL is the null-key class's insertion TTL, and nothing else's since V4-334: keyed conversations
// end at compaction or under the bounds, never on a clock.
private const val TTL_MS: Long = 30 * 60 * 1000L

// The head's bounds, across all conversations. Bytes are the real bound: 64 MiB of envelopes. Every
// eviction rebuilds a conversation with ZERO reasoning injection — the tool-amnesia respiral (repeated
// identical tool calls) RC exists to prevent (live soak 2026-08-26, when a 256-round bound evicted
// active conversations every few minutes). codex-rs itself retains every reasoning item until context
// compaction.
private const val MAX_TOTAL_MIB: Int = 64
internal const val REASONING_BOUND_BYTES: Long = MAX_TOTAL_MIB * 1024L * 1024L

// A round is one stored reasoning item (a positioned record) or one tool round, and its envelope alone is
// about 1.9 KB even with no readable summary (2026-11-09: 6716 live rows, median 2915 B). The ROUND bound is
// the byte bound over that floor, so bytes bind first for every real round and the count only stops a flood
// of rounds smaller than any reasoning item. It was a fixed 8192, which bound at ~22 MB, a third of the bytes.
private const val ROUND_BYTES_FLOOR: Int = 2048
internal const val MAX_ENTRIES: Int = MAX_TOTAL_MIB * 1024 * 1024 / ROUND_BYTES_FLOOR

// The freeze log's reasons.
private const val ALONE_OVER_BOUND = "alone over the bound"
private const val OVER_SHARE = "over its share of the head's bound"
private const val RESTORED_OVER_SHARE = "restored over its share"

// "splice-" + 7 hash chars: identifiable in logs, not noisy. Internal: ReasoningCacheFiles names keys too.
internal const val KEY_LOG_CHARS: Int = 14
