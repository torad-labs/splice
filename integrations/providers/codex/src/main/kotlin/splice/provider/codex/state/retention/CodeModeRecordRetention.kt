package splice.provider.codex.state.retention

import kotlinx.serialization.json.Json
import splice.core.util.LogSink
import splice.provider.codex.CodeModePhase
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodeModeRecordSnapshot
import splice.provider.codex.CodeModeRetention
import splice.provider.codex.state.CodeModeExpiredHistory
import splice.provider.codex.state.CodeModeSessionEnd
import splice.provider.codex.state.collection.CodeModeRecordKeys
import splice.provider.codex.state.lease.CodeModeSessionAlive

/** V4-337: which records the head keeps within [bounds], a conversation at a time. Runs under the
 *  registry's monitor; each record that goes is remembered in the expired history, as an expired one is.
 *
 *  A conversation's finished records go TOGETHER, never its oldest alone: every later record, and a script
 *  started on top of them, was measured on the oldest's canonical items, so without it none of them places
 *  again (canonicalize omits each, every turn; a running one is abandoned). They therefore go before the
 *  history a new script is measured on is built: at the start of that conversation's turn ([beginTurn])
 *  when it alone reached a bound, and for any OTHER conversation at an admission ([makeRoom]). Its scripts
 *  so far then stay in its history as the ordinary tool calls the client already saw, and a script it runs
 *  afterwards is measured on that history and places as any other. */
internal class CodeModeRecordRetention(
    private val bounds: CodeModeRetention,
    private val json: Json,
    private val log: LogSink,
    private val sessionAlive: CodeModeSessionAlive = CodeModeSessionAlive { null },
    private val closeSession: CodeModeSessionEnd = CodeModeSessionEnd {},
) {

    /** At the start of conversation [key]'s turn: its finished records go together when it reached
     *  [CodeModeRetention.perConversation] records or alone holds more than [CodeModeRetention.bytes],
     *  and none of its records is running (a running script's baseline sits on them). Records that stay
     *  are used by this turn ([used]). True when any went. */
    fun beginTurn(
        records: MutableList<CodeModeRecord>,
        expired: CodeModeExpiredHistory,
        key: String,
        now: Long,
    ): Boolean = letGo(records, expired, key, now).also { used(records, key, now) }

    /** A turn replays its conversation's records whether or not it runs a script, so their 24 hours run from
     *  the turn: its newest finished record takes the turn's time. The turn writes nothing for it, because a
     *  large conversation's full save can refuse the turn; the head's stop saves it with every conversation. */
    private fun used(records: List<CodeModeRecord>, key: String, now: Long) {
        val finished = CodeModeRecordKeys.of(records, key).filter(CodeModeRecord::terminal)
        finished.maxByOrNull { it.progress.updatedAt }?.let { newest ->
            newest.progress.updatedAt = maxOf(newest.progress.updatedAt, now)
        }
    }

    private fun letGo(
        records: MutableList<CodeModeRecord>,
        expired: CodeModeExpiredHistory,
        key: String,
        now: Long,
    ): Boolean {
        val own = CodeModeRecordKeys.of(records, key)
        if (own.isEmpty() || !own.all(CodeModeRecord::terminal)) return false
        val why = when {
            own.any { it.sessionId?.let(sessionAlive::invoke) == true } -> null
            bounds.perConversation?.let { own.size >= it } == true -> "it reached ${bounds.perConversation} records"
            own.sumOf(::bytesOf) > bounds.bytes -> "it alone holds more than ${bounds.bytes} bytes"
            else -> null
        }
        return why?.let { Gone(records, expired, now).finished(key, it) } != null
    }

    /** Makes room by reclaiming finished conversations within the byte budget. An explicit count
     *  override can also reclaim this conversation or refuse admission; defaults never refuse by count. */
    fun makeRoom(
        records: MutableList<CodeModeRecord>,
        expired: CodeModeExpiredHistory,
        record: CodeModeRecord,
        now: Long,
    ): Boolean {
        val gone = Gone(records, expired, now)
        val full = "the head holds ${bounds.records} records"
        while (bounds.records?.let { records.size >= it } == true) {
            gone.leastRecent(record.key, full) ?: gone.finished(record.key, "$full, none of them idle") ?: return false
        }
        var held = records.sumOf(::bytesOf) + bytesOf(record)
        while (held > bounds.bytes) {
            held -= gone.leastRecent(record.key, "the head's records passed ${bounds.bytes} bytes") ?: break
        }
        return true
    }

    /** At the registry's start, where nothing runs: each conversation past its own bound goes whole, then
     *  the least recently used ones until the head is within its bounds. True when any went. */
    fun trim(records: MutableList<CodeModeRecord>, expired: CodeModeExpiredHistory, now: Long): Boolean {
        val before = records.size
        // A conversation exactly at its per-conversation bound still has one valid retry to
        // serve after restart. New turns call beginTurn after the retry lookup; an OVER-bound
        // conversation is trimmed here as before, even with no new client turn.
        records.map(CodeModeRecord::key).distinct().forEach { key ->
            if (overOwnBound(records, key)) letGo(records, expired, key, now)
        }
        val gone = Gone(records, expired, now)
        while (exceedsHeadCount(records.size)) {
            gone.leastRecent(null, "the head held ${records.size} records") ?: break
        }
        var held = records.sumOf(::bytesOf)
        while (held > bounds.bytes) held -= gone.leastRecent(null, "the head's records held $held bytes") ?: break
        return records.size != before
    }

    /** On the timed sweep, where scripts may be running: the least recently used idle conversation outside
     *  [busy] while the head is past its bounds, or null when it is within them or none qualifies. Before this
     *  the bounds held only at an admission and at the start, so a head past them stayed past them until the
     *  next script, however long that took (Oct 22: 94.6 MB over the bound from a boot to its first script). */
    fun overBound(records: List<CodeModeRecord>, busy: Set<String>): String? =
        if (exceedsHeadCount(records.size) || records.sumOf(::bytesOf) > bounds.bytes) {
            idle(records, busy + lost(records))
        } else {
            null
        }

    /** The conversations holding a LOST record. A LOST record still needs a result or an exact retry
     *  (CodexCodeModeRegistry.owner), so the timed sweep leaves its conversation to its expiry and never to the
     *  bound: round 11 of #247 found the sweep letting it go, since a LOST record carries an error and so reads as
     *  finished. An admission, which needs the room now, may still let it go. */
    private fun lost(records: List<CodeModeRecord>): Set<String> =
        records.filter { it.phase == CodeModePhase.LOST }.mapTo(HashSet(), CodeModeRecord::key)

    /** Under [key]'s lock: [key]'s finished records go together when the head is still past its bounds and
     *  [key] is still idle and outside [busy]. True when any went. */
    fun letGoOver(
        records: MutableList<CodeModeRecord>,
        expired: CodeModeExpiredHistory,
        key: String,
        busy: Set<String>,
        now: Long,
    ): Boolean {
        val own = CodeModeRecordKeys.of(records, key)
        val stays = key in busy || !idle(own) || own.any { it.phase == CodeModePhase.LOST || mayBill(it) }
        if (stays || overBound(records, busy) == null) return false
        Gone(records, expired, now).finished(key, "the head is past its bounds at the timed sweep") ?: return false
        // Every lease left here guards a round that already ended, so ending it cancels nothing, and no lease is left
        // held by a record the head let go (round 12 of #247).
        own.forEach(CodeModeRecord::endSource)
        // The session its scripts ran in goes too, or its engine and host lease stay held after the head let it go
        // (round 11 of #247).
        closeSession(key)
        return true
    }

    /** Whether [record]'s round may still bill a row. While its reader runs, the row its posting step wrote may be owed
     *  the round's usage (CodeModeStreams.billFinished), and ending the lease would cancel the round (round 13 of
     *  #247). Once it settled, its usage may wait in the record for the turn that takes its outcome. Otherwise the
     *  lease guards nothing, and keeping its conversation to expiry held it past the bounds for 24 hours (round 14). */
    private fun mayBill(record: CodeModeRecord): Boolean {
        val lease = record.sourceEnd ?: return false
        return lease.reading || record.sourceState?.let { it.usage != null && !it.consumed } == true
    }

    /** The least recently used conversation outside [except] whose records are all finished and whose
     *  session is not known alive; one whose session may be alive goes after every one known gone. */
    private fun idle(records: List<CodeModeRecord>, except: Set<String>): String? =
        records.groupBy(CodeModeRecord::key)
            .filter { (key, kept) -> key !in except && idle(kept) }
            .minWithOrNull(
                compareBy<Map.Entry<String, List<CodeModeRecord>>>(
                    { (_, kept) -> kept.any { it.sessionId?.let(sessionAlive::invoke) != false } },
                    { (_, kept) -> kept.maxOf { it.progress.updatedAt } },
                ),
            )
            ?.key

    private fun idle(kept: List<CodeModeRecord>): Boolean = kept.isNotEmpty() &&
        kept.all(CodeModeRecord::terminal) && kept.none { it.sessionId?.let(sessionAlive::invoke) == true }

    private fun exceedsHeadCount(count: Int): Boolean = bounds.records?.let { count > it } == true

    private fun overOwnBound(records: List<CodeModeRecord>, key: String): Boolean {
        val own = CodeModeRecordKeys.of(records, key)
        val countFull = bounds.perConversation?.let { own.size > it } == true
        return countFull || own.sumOf(::bytesOf) > bounds.bytes
    }

    /** Lets records go from [records] at [now], each remembered in [expired]. */
    private inner class Gone(
        private val records: MutableList<CodeModeRecord>,
        private val expired: CodeModeExpiredHistory,
        private val now: Long,
    ) {
        /** The least recently used conversation other than [except] whose records are all finished, whole.
         *  The bytes freed, or null when there is none. */
        fun leastRecent(except: String?, why: String): Long? =
            idle(records, setOfNotNull(except))?.let { finished(it, "least recently used, and $why") }

        /** Every finished record of conversation [key], together. The bytes freed, or null when it has none. */
        fun finished(key: String, why: String): Long? {
            val going = CodeModeRecordKeys.of(records, key).filter(CodeModeRecord::terminal)
            if (going.isEmpty()) return null
            records.removeAll(going.toSet())
            going.forEach { expired.remember(it, now) }
            val freed = going.sumOf(::bytesOf)
            log(
                "[code-mode] conversation ${key.take(RECORD_ID_LOG_CHARS)}: ${going.size} record(s), $freed bytes, " +
                    "let go ($why); its scripts so far stay in its history as ordinary tool calls",
            )
            return freed
        }
    }

    private fun bytesOf(record: CodeModeRecord): Long = record.retainedBytes
        ?: json.encodeToString(CodeModeRecordSnapshot.serializer(), record.snapshot())
            .toByteArray(Charsets.UTF_8).size.toLong().also { record.retainedBytes = it }
}

// why: enough leading characters of a conversation key to tell retention-log lines apart
// without quoting the whole digest next to user file bytes.
private const val RECORD_ID_LOG_CHARS: Int = 8
