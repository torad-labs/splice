// NEW: closes parked cells — aged out, or evicted at capacity — so a client that never returned
// cannot hold a worker slot for the rest of the day.
//
// V4-287: records expire 24 hours (the config's ttl) after their last use, on a timer. The sweep ran
// only when the registry was built or a code-mode turn touched it, so a head that ran one script and
// went idle kept the record, with the model's reasoning summaries in plaintext, for as long as the
// daemon stayed up. Parking a cell is splice's own act, not a use, so it keeps the record's time.
//
// V4-337: the 24 hours run from the last use of the record's CONVERSATION, and what the head keeps
// past its bounds goes a conversation at a time ([CodeModeRecordRetention]). A record that expired
// alone took every later record of its conversation with it: they place on top of it.
package splice.provider.codex

import kotlinx.serialization.json.Json
import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.provider.codex.state.CodeModeCellRetention
import splice.provider.codex.state.CodeModeExpiredHistory
import splice.provider.codex.state.CodeModeSessionEnd
import splice.provider.codex.stream.CodeModeSourceEnds
import splice.upstream.codemode.CodeModeCell
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration

/** V4-287: the timer every code-mode registry sweeps on, one named daemon thread for all heads. */
internal object CodeModeSweeps {
    // The platform factory, as AsyncFileIo's lane: this timer owns only its name and daemon-ness.
    private val threads = Executors.defaultThreadFactory()
    private val timer = ScheduledThreadPoolExecutor(1) { task ->
        threads.newThread(task).apply {
            name = "splice-code-mode-sweep"
            isDaemon = true
        }
    }.apply { removeOnCancelPolicy = true }

    /** Runs [sweep] every [interval] until the returned future is cancelled. [sweep] must not throw:
     *  an exception ends a periodic task in silence. */
    fun every(interval: Duration, sweep: Runnable): ScheduledFuture<*> {
        val ms = interval.inWholeMilliseconds
        return timer.scheduleWithFixedDelay(sweep, ms, ms, TimeUnit.MILLISECONDS)
    }
}

/** V4-287: one registry's sweep on [CodeModeSweeps]' timer, running while the registry keeps any
 *  record. It holds the registry's [monitor] for each sweep; [save] writes the registry's state. */
internal class CodeModeTimedSweep(
    private val monitor: ReentrantLock,
    private val records: List<CodeModeRecord>,
    private val history: CodeModeExpiredHistory,
    private val save: Runnable,
    private val store: CodexCodeModeStore,
    private val config: CodeModeBridgeConfig,
    private val interval: Duration,
) {
    private var running: ScheduledFuture<*>? = null

    /** A sweep changed the records and could not save them yet; the next sweep saves again. */
    private var unsaved = false

    /** Under [monitor]: starts the sweeps when a record is kept and none run. */
    fun arm() {
        val retained = records.isNotEmpty() || history.entries.isNotEmpty()
        if (running == null && retained) running = CodeModeSweeps.every(interval) { sweep() }
    }

    /** One sweep: what the sweeper changed is saved, a save that fails is logged and made again at the
     *  next sweep, and the sweeps stop once no record or expiry marker is kept. Never throws: a throw would end the
     *  periodic task in silence. */
    private fun sweep() {
        Cancellables.runCatchingBestEffort {
            unsaved = true
            save.run()
            unsaved = false
        }.exceptionOrNull()?.let { failure ->
            config.log(
                "[code-mode] the timed sweep could not save into ${config.state.dir} " +
                    "(${SafeFailureText.render(failure)}); it saves again at the next sweep",
            )
        }
        monitor.withLock {
            val clean = !unsaved && store.pendingKeys.isEmpty()
            val empty = records.isEmpty() && history.entries.isEmpty()
            if (empty && clean) {
                running?.cancel(false)
                running = null
            }
        }
    }
}

/** Runs under the registry's monitor; it mutates the registry's own collections in place. */
internal class CodexCodeModeSweeper(
    private val config: CodeModeBridgeConfig,
    private val records: MutableList<CodeModeRecord>,
    private val cells: MutableMap<String, CodeModeCell>,
    private val admissions: MutableMap<String, Long>,
    private val history: CodeModeExpiredHistory,
    private val closeSession: CodeModeSessionEnd,
    private val retainedCells: CodeModeCellRetention,
) {
    /** Expires records and reaps dead or over-age unknown parked cells; true when anything changed. */
    fun sweep(key: String? = null, protectedKey: String? = null): Boolean =
        expireRecords(key) or retainedCells.sweep(key, protectedKey) or expireMarkers(key)

    /** Without an explicit count bound, old expiry evidence is bounded by the same configured lifetime. */
    private fun expireMarkers(key: String?): Boolean {
        val cutoff = config.clock.millis() - config.ttl.inWholeMilliseconds
        return history.entries.removeAll { (key == null || it.key == key) && it.expiredAt < cutoff }
    }

    private fun expireRecords(key: String?): Boolean {
        val cutoff = config.clock.millis() - config.ttl.inWholeMilliseconds
        val lastUse = records.groupBy(CodeModeRecord::key)
            .mapValues { (_, kept) -> kept.maxOf(CodeModeRecord::updatedAt) }
        val protected = records.filter(retainedCells::running).map(CodeModeRecord::key).toSet()
        val stale = records.filter {
            (key == null || it.key == key) && it.key !in protected && lastUse.getValue(it.key) < cutoff
        }
        if (stale.isEmpty()) return false
        stale.forEach { record ->
            admissions.remove(record.id)
            CodeModeSourceEnds.defer(record.sourceEnd)
            record.sourceEnd = null
            cells.remove(record.id)?.close()
            history.remember(record, config.clock.millis())
        }
        records.removeAll(stale.toSet())
        stale.map(CodeModeRecord::key).distinct().forEach(closeSession::invoke)
        return true
    }
}

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
) {

    /** At the start of conversation [key]'s turn: its finished records go together when it reached
     *  [CodeModeRetention.perConversation] records or alone holds more than [CodeModeRetention.bytes],
     *  and none of its records is running (a running script's baseline sits on them). True when any went. */
    fun beginTurn(
        records: MutableList<CodeModeRecord>,
        expired: CodeModeExpiredHistory,
        key: String,
        now: Long,
    ): Boolean {
        val own = records.filter { it.key == key }
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
            if (overOwnBound(records, key)) beginTurn(records, expired, key, now)
        }
        val gone = Gone(records, expired, now)
        while (exceedsHeadCount(records.size)) {
            gone.leastRecent(null, "the head held ${records.size} records") ?: break
        }
        var held = records.sumOf(::bytesOf)
        while (held > bounds.bytes) held -= gone.leastRecent(null, "the head's records held $held bytes") ?: break
        return records.size != before
    }

    private fun exceedsHeadCount(count: Int): Boolean = bounds.records?.let { count > it } == true

    private fun overOwnBound(records: List<CodeModeRecord>, key: String): Boolean {
        val own = records.filter { it.key == key }
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
        fun leastRecent(except: String?, why: String): Long? {
            val idle = records.groupBy(CodeModeRecord::key)
                .filter { (key, kept) -> key != except && kept.all(CodeModeRecord::terminal) }
                .filterValues { kept -> kept.none { it.sessionId?.let(sessionAlive::invoke) == true } }
                .minWithOrNull(
                    compareBy<Map.Entry<String, List<CodeModeRecord>>>(
                        { (_, kept) -> kept.any { it.sessionId?.let(sessionAlive::invoke) != false } },
                        { (_, kept) -> kept.maxOf(CodeModeRecord::updatedAt) },
                    ),
                )
                ?: return null
            return finished(idle.key, "least recently used, and $why")
        }

        /** Every finished record of conversation [key], together. The bytes freed, or null when it has none. */
        fun finished(key: String, why: String): Long? {
            val going = records.filter { it.key == key && it.terminal() }
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

private const val RECORD_ID_LOG_CHARS: Int = 8
