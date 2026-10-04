// NEW: retains bounded code-mode records, live cells, expiry markers, and stop generations.
//
// V4-287: while it keeps any record, the registry sweeps every [sweepInterval] on a timer
// ([CodeModeTimedSweep]), so a record goes within one interval of its ttl whether or not another turn comes. The timer
// stops once no record is kept, and the next record, or a daemon start that finds records on disk,
// starts it again. A head stop does not stop it: the bridge and its records outlive the stop
// (Provider.onHeadStop), and so does the promise that the records go.
//
// V4-337: what it keeps is decided per conversation ([CodeModeRecordRetention]). A new script made room
// by taking the head's oldest finished record, whatever conversation it belonged to, so on a busy head
// a live conversation lost its first records within the hour, and every later record of it with them.
package splice.provider.codex

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import splice.core.memory.HeapBudget
import splice.provider.codex.state.CodeModeCellRetention
import splice.provider.codex.state.CodeModeExpiredHistory
import splice.provider.codex.state.CodeModeHeap
import splice.provider.codex.state.CodeModeKeyLocks
import splice.provider.codex.state.CodeModeNativeChain
import splice.provider.codex.state.CodeModeRecordChanges
import splice.provider.codex.state.CodeModeRegistryAccess
import splice.provider.codex.state.CodeModeSessionEnd
import splice.provider.codex.state.CodeModeStartupAdmissions
import splice.provider.codex.state.CodeModeTurnStart
import splice.provider.codex.state.query.CodeModeRecordQueries
import splice.provider.codex.stream.CodeModeClientContexts
import splice.provider.codex.stream.CodeModeSourceEnds
import splice.provider.codex.stream.CodeModeSourceRecords
import splice.upstream.codemode.CodeModeCell
import splice.upstream.codemode.CodeModeResult
import splice.upstream.memory.JvmHeap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration

internal data class CodeModeResultOwners(
    val foreign: CodeModeRecord?,
    val unknown: Set<String>,
)

internal class CodexCodeModeRegistry(
    private val config: CodeModeBridgeConfig,
    json: Json,
    private val sweepInterval: Duration,
    writer: CodeModeStateWrite? = null,
    private val closeSession: CodeModeSessionEnd = CodeModeSessionEnd {},
    private val heap: HeapBudget = JvmHeap.budget,
) {
    private val monitor = ReentrantLock()
    private val keyLocks = CodeModeKeyLocks()
    private val access = CodeModeRegistryAccess(monitor, keyLocks)
    private val store = if (writer == null) {
        CodexCodeModeStore(config.state, json, config.log, registryLock = monitor, keyLocks = keyLocks, heap = heap)
    } else {
        CodexCodeModeStore(
            config.state,
            json,
            config.log,
            writer = writer,
            registryLock = monitor,
            keyLocks = keyLocks,
            heap = heap,
        )
    }
    private val records: MutableList<CodeModeRecord>
    private val history: CodeModeExpiredHistory

    init {
        // The boot snapshots must not pin payloads after the live record expires or is purged.
        val loaded = store.load()
        records = loaded.records.map { it.restore().also { record -> CodeModeHeap.own(record, heap) } }
            .toMutableList()
            .also(CodeModeNativeChain::link)
        history = CodeModeExpiredHistory(loaded.expired.toMutableList(), config.retention.records, heap)
    }
    private val retention = CodeModeRecordRetention(config.retention, json, config.log, config.sessionAlive)
    private val cells = mutableMapOf<String, CodeModeCell>()
    val startup = CodeModeStartupAdmissions(access, records, history, store, config.clock, cells)

    /** Each conversation's last measured context; after a restart, the newest round its records kept. */
    val contexts = CodeModeClientContexts { key ->
        recordsFor(key).lastOrNull { it.sourceState?.usage != null }?.sourceState?.usage?.value()
    }
    val source = CodeModeSourceRecords(access, records, history, store, contexts)
    val changes = CodeModeRecordChanges(access, records, history, store, cells, startup, config)
    private val admissions = startup.entries
    val retainedCells = CodeModeCellRetention(config, access, records, cells, admissions, closeSession)
    private val sweeper = CodexCodeModeSweeper(config, records, cells, admissions, history, closeSession, retainedCells)
    private val queries = CodeModeRecordQueries(access, records, history) { key ->
        val changed = sweeper.sweep(key, key)
        store.save(records, history.entries, retryOnly = !changed, dirtyKeys = setOf(key))
    }
    val timed = CodeModeTimedSweep(
        monitor,
        records,
        history,
        { PeriodicSweep().run() },
        store,
        config,
        sweepInterval,
    )

    /** V4-337: where each code-mode turn starts, before its history is built. */
    val turnStart = CodeModeTurnStart(access, retention, records, history, store, config.clock, retainedCells)

    init {
        monitor.withLock {
            val changed = sweeper.sweep() or retention.trim(records, history, config.clock.millis())
            if (changed) store.save(records, history.entries)
            timed.arm()
        }
    }

    /** ACTIVE owns visible calls before their result; LOST still needs a result or exact retry. */
    fun owner(
        key: String,
        digest: String,
        resultIds: Set<String>,
        callbackIds: Set<String>,
        excluded: Set<String> = emptySet(),
    ): CodeModeRecord? = queries.owner(key, digest, resultIds, callbackIds, excluded)

    fun recordsFor(key: String): List<CodeModeRecord> = queries.recordsFor(key)

    fun completed(key: String): List<CodeModeRecord> = queries.completed(key)

    fun expiredHistory(key: String, digest: String, ids: Set<String>): Boolean =
        queries.expiredHistory(key, digest, ids)

    fun resultOwners(key: String, ids: Set<String>): CodeModeResultOwners = queries.resultOwners(key, ids)

    fun add(record: CodeModeRecord): Boolean = Admission(record).run()

    /** Admission may evict other keys. Never wait on a second key while holding the first:
     *  release the attempt, wait without the monitor, then re-plan against current state. */
    private inner class Admission(private val record: CodeModeRecord) {
        private var blockedKey: String? = null

        fun run(): Boolean {
            CodeModeHeap.own(record, heap)
            // A failed boot already has durable source; an exact retry reuses its identity.
            startup.resume(record)?.let { return it }
            while (true) {
                val accepted = attempt()
                if (accepted != null) return accepted
                val key = checkNotNull(blockedKey)
                keyLocks.release(key, keyLocks.acquire(key))
            }
        }

        private fun attempt(): Boolean? = CodeModeSourceEnds.unlocked {
            val own = keyLocks.acquire(record.key)
            val held = mutableMapOf<String, CodeModeKeyLocks.Entry>()
            try {
                monitor.withLock {
                    if (sweeper.sweep(record.key, record.key)) {
                        store.save(records, history.entries, dirtyKeys = setOf(record.key))
                    }
                    val plan = plan() ?: return@withLock false
                    plan.changedKeys.filter { it != record.key }.forEach { key ->
                        val entry = keyLocks.tryAcquire(key)
                        if (entry == null) {
                            blockedKey = key
                            return@withLock null
                        }
                        held[key] = entry
                    }
                    val admittedGeneration = startup.generation
                    plan.retireCells(record.key)
                    // Commit evictions first. A failure there must not leave an unexecuted admission on disk.
                    plan.changedKeys.filter { it != record.key }.forEach { key ->
                        store.save(plan.candidate, plan.nextHistory.entries, dirtyKeys = setOf(key))
                        plan.publishEvictions(key)
                    }
                    store.save(
                        plan.candidate,
                        plan.nextHistory.entries,
                        dirtyKeys = setOf(record.key),
                        changedRecord = record.takeIf { plan.onlyAdds(it.key) },
                    )
                    plan.publish(record)
                    admissions[record.id] = admittedGeneration
                    timed.arm()
                    true
                }
            } finally {
                held.forEach { (key, entry) -> keyLocks.release(key, entry) }
                keyLocks.release(record.key, own)
            }
        }

        private fun plan(): AdmissionPlan? {
            // A session id is shared by subagents. Only equal conversation identities prove supersession.
            // Busy keys cannot be retired, even if a completed sibling record also names their engine.
            val busyKeys = records.filter { it.id in admissions || retainedCells.running(it) }
                .mapTo(mutableSetOf(), CodeModeRecord::key)
            val previous = records.filter {
                it.key != record.key && it.key !in busyKeys &&
                    record.conversationId != null && it.conversationId == record.conversationId
            }
            val candidate = records.toMutableList()
            val nextHistory = CodeModeExpiredHistory(history.entries.toMutableList(), config.retention.records, heap)
            if (!retention.makeRoom(candidate, nextHistory, record, config.clock.millis())) return null
            candidate += record
            return AdmissionPlan(candidate, nextHistory, previous)
        }
    }

    private inner class AdmissionPlan(
        val candidate: List<CodeModeRecord>,
        val nextHistory: CodeModeExpiredHistory,
        private val previous: List<CodeModeRecord>,
    ) {
        private val parked = previous.filter { it.phase == CodeModePhase.ACTIVE }
        private val evicted = (records.map(CodeModeRecord::id) - candidate.map(CodeModeRecord::id).toSet()).toSet()
        private val removedMarkers = (history.entries - nextHistory.entries.toSet()).toSet()
        private val addedMarkers = nextHistory.entries.filter { it !in history.entries }
        val changedKeys = setOf(candidate.last().key) + records.filter { it.id in evicted }.map(CodeModeRecord::key) +
            removedMarkers.map(CodeModeExpiredSnapshot::key) + previous.map(CodeModeRecord::key)

        fun retireCells(key: String) {
            parked.forEach {
                retainedCells.park(it, "code-mode parked program replaced by a newer program in its session")
            }
            (previous.map(CodeModeRecord::key).toSet() - key - parked.map(CodeModeRecord::key).toSet())
                .forEach(closeSession::invoke)
        }

        fun onlyAdds(key: String): Boolean =
            parked.none { it.key == key } && records.none { it.key == key && it.id in evicted } &&
                removedMarkers.none { it.key == key } && addedMarkers.none { it.key == key }

        fun publishEvictions(key: String) {
            records.filter { it.key == key && it.id in evicted }.forEach { record ->
                CodeModeSourceEnds.defer(record.sourceEnd)
                record.sourceEnd = null
            }
            val removed = records.removeAll { it.key == key && it.id in evicted }
            if (removed && records.none { it.key == key }) closeSession(key)
            history.entries.removeAll { it.key == key && it in removedMarkers }
            history.entries.addAll(addedMarkers.filter { it.key == key })
        }

        fun publish(record: CodeModeRecord) {
            publishEvictions(record.key)
            records.add(record)
        }
    }

    fun attach(record: CodeModeRecord, cell: CodeModeCell): Boolean = startup.attach(record, cell)

    fun cell(record: CodeModeRecord): CodeModeCell? = access.withKey(record.key) { cells[record.id] }

    /** Retries a failed whole-head carry; a known record's conversation saves through [changes]. */
    fun save() {
        monitor.withLock { store.save(records, history.entries) }
    }

    fun complete(record: CodeModeRecord, output: String) = changes.complete(record, output)

    fun lose(record: CodeModeRecord, message: String, cancellation: CancellationException? = null) =
        changes.lose(record, message, cancellation)

    fun onHeadStop() = changes.onHeadStop()

    /** Global idle order is snapshotted, then each candidate is rechecked and saved under its own key. */
    fun evictIdleCell(): CodeModeRecord? {
        val keys = monitor.withLock { retainedCells.capacityCandidates().map(CodeModeRecord::key).distinct() }
        keys.forEach { key ->
            val victim = access.withKey(key) {
                retainedCells.evict(key)?.also {
                    store.save(records, history.entries, dirtyKeys = setOf(key))
                }
            }
            if (victim != null) return victim
        }
        return null
    }

    private inner class PeriodicSweep {
        fun run() {
            val keys = monitor.withLock {
                records.map(CodeModeRecord::key).toSet() +
                    history.entries.map(CodeModeExpiredSnapshot::key) + store.pendingKeys
            }
            keys.forEach { key ->
                access.tryKey(key) {
                    val changed = sweeper.sweep(key)
                    store.save(records, history.entries, retryOnly = !changed, dirtyKeys = setOf(key))
                }
            }
        }
    }

    /** V4-179: [media] holds the follow-up items rendered for each supplied result; see [CodeModeAccepted.accept]. */
    fun acceptResults(
        record: CodeModeRecord,
        digest: String,
        supplied: Map<String, CodeModeResult>,
        media: Map<String, List<JsonElement>> = emptyMap(),
    ) = access.withKey(record.key) {
        // The prior issuance is already durable. New results and the worker's next step commit together.
        CodeModeHeap.grow(record, record.accepted.heapGrowth(supplied, media))
        record.lastDigest = digest
        record.updatedAt = config.clock.millis()
        record.accepted.accept(supplied, media)
    }
}
