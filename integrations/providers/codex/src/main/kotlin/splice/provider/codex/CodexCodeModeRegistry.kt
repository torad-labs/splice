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
import splice.provider.codex.state.CodeModeExpiredHistory
import splice.provider.codex.state.CodeModeKeyLocks
import splice.provider.codex.state.CodeModeNativeChain
import splice.provider.codex.state.CodeModeRegistryAccess
import splice.provider.codex.state.CodeModeStartupAdmissions
import splice.provider.codex.stream.CodeModeSourceRecords
import splice.upstream.codemode.CodeModeCell
import splice.upstream.codemode.CodeModeResult
import java.time.Clock
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
) {
    private val monitor = ReentrantLock()
    private val keyLocks = CodeModeKeyLocks()
    private val access = CodeModeRegistryAccess(monitor, keyLocks)
    private val store = if (writer == null) {
        CodexCodeModeStore(config.state, json, config.log, registryLock = monitor, keyLocks = keyLocks)
    } else {
        CodexCodeModeStore(config.state, json, config.log, writer = writer, registryLock = monitor, keyLocks = keyLocks)
    }
    private val loaded = store.load()
    private val records = loaded.records.map(CodeModeRecordSnapshot::restore).toMutableList()
        .also(CodeModeNativeChain::link)
    private val history = CodeModeExpiredHistory(loaded.expired.toMutableList(), config.retention.records)
    private val retention = CodeModeRecordRetention(config.retention, json, config.log, config.sessionAlive)
    private val cells = mutableMapOf<String, CodeModeCell>()
    val startup = CodeModeStartupAdmissions(access, records, history, store, config.clock)
    val source = CodeModeSourceRecords(access, records, history, store)
    private val admissions = startup.entries
    private val sweeper = CodexCodeModeSweeper(config, records, cells, admissions, history)
    private val timed = CodeModeTimedSweep(
        monitor,
        records,
        history,
        { PeriodicSweep().run() },
        store,
        config,
        sweepInterval,
    )

    /** V4-337: where each code-mode turn starts, before its history is built. */
    val turnStart = CodeModeTurnStart(access, retention, records, history, store, config.clock)

    init {
        monitor.withLock {
            val changed = sweeper.sweep() or retention.trim(records, history, config.clock.millis())
            if (changed) store.save(records, history.entries)
            timed.arm()
        }
    }

    /** ACTIVE owns visible calls before their result; LOST still needs a result or an exact retry. */
    fun owner(
        key: String,
        digest: String,
        resultIds: Set<String>,
        callbackIds: Set<String>,
        excluded: Set<String> = emptySet(),
    ): CodeModeRecord? = PeriodicSweep().withKey(key) {
        val activeIds = if (callbackIds.isEmpty()) resultIds else resultIds + callbackIds
        records.lastOrNull { record ->
            record.key == key && record.phase == CodeModePhase.ACTIVE &&
                CodeModeOwnerMatch.matches(record, digest, activeIds, excluded)
        } ?: records.lastOrNull { record ->
            record.key == key && (record.phase == CodeModePhase.LOST || record.phase == CodeModePhase.STARTING) &&
                CodeModeOwnerMatch.matches(record, digest, resultIds, excluded)
        }
    }

    /** One conversation's records for replay and divergence checks under a single sweep. */
    fun recordsFor(key: String): List<CodeModeRecord> = PeriodicSweep().withKey(key) {
        records.filter { it.key == key }
    }

    fun completed(key: String): List<CodeModeRecord> = PeriodicSweep().withKey(key) {
        records.filter { it.key == key && it.phase == CodeModePhase.COMPLETED }
    }

    fun expiredHistory(key: String, digest: String, ids: Set<String>): Boolean = PeriodicSweep().withKey(key) {
        history.entries.any { marker ->
            marker.key == key && (marker.lastDigest == digest || marker.resultIds.any { it in ids })
        }
    }

    /** Classify foreign and missing result ids from one consistent record snapshot. */
    fun resultOwners(key: String, ids: Set<String>): CodeModeResultOwners = PeriodicSweep().withKey(key) {
        val foreign = records.firstOrNull { record -> record.key != key && record.clientIds().any { it in ids } }
        val known = records.filter { it.key == key }.flatMap(CodeModeRecord::clientIds).toSet()
        val unknown = ids.filter { it.startsWith(CODE_MODE_CLIENT_ID_PREFIX) && it !in known }.toSet()
        CodeModeResultOwners(foreign, unknown)
    }

    fun add(record: CodeModeRecord): Boolean = Admission(record).run()

    /** Admission may evict other keys. Never wait on a second key while holding the first:
     *  release the attempt, wait without the monitor, then re-plan against current state. */
    private inner class Admission(private val record: CodeModeRecord) {
        private var blockedKey: String? = null

        fun run(): Boolean {
            // A failed boot already has durable source; an exact retry reuses its identity.
            startup.resume(record)?.let { return it }
            while (true) {
                val accepted = attempt()
                if (accepted != null) return accepted
                val key = checkNotNull(blockedKey)
                keyLocks.release(key, keyLocks.acquire(key))
            }
        }

        private fun attempt(): Boolean? {
            val own = keyLocks.acquire(record.key)
            val held = mutableMapOf<String, CodeModeKeyLocks.Entry>()
            return try {
                monitor.withLock {
                    if (sweeper.sweep(record.key)) {
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
            val candidate = records.toMutableList()
            val nextHistory = CodeModeExpiredHistory(history.entries.toMutableList(), config.retention.records)
            if (!retention.makeRoom(candidate, nextHistory, record, config.clock.millis())) return null
            candidate += record
            return AdmissionPlan(candidate, nextHistory)
        }
    }

    private inner class AdmissionPlan(
        val candidate: List<CodeModeRecord>,
        val nextHistory: CodeModeExpiredHistory,
    ) {
        private val evicted = (records.map(CodeModeRecord::id) - candidate.map(CodeModeRecord::id).toSet()).toSet()
        private val removedMarkers = (history.entries - nextHistory.entries.toSet()).toSet()
        private val addedMarkers = nextHistory.entries.filter { it !in history.entries }
        val changedKeys = setOf(candidate.last().key) + records.filter { it.id in evicted }.map(CodeModeRecord::key) +
            removedMarkers.map(CodeModeExpiredSnapshot::key)

        fun onlyAdds(key: String): Boolean =
            records.none { it.key == key && it.id in evicted } &&
                removedMarkers.none { it.key == key } && addedMarkers.none { it.key == key }

        fun publishEvictions(key: String) {
            records.filter { it.key == key && it.id in evicted }.forEach { record ->
                record.sourceEnd?.ended()
                record.sourceEnd = null
            }
            records.removeAll { it.key == key && it.id in evicted }
            history.entries.removeAll { it.key == key && it in removedMarkers }
            history.entries.addAll(addedMarkers.filter { it.key == key })
        }

        fun publish(record: CodeModeRecord) {
            publishEvictions(record.key)
            records.add(record)
        }
    }

    fun attach(record: CodeModeRecord, cell: CodeModeCell): Boolean = access.withKey(record.key) {
        val admittedGeneration = admissions.remove(record.id)
        val rejected = admittedGeneration != startup.generation || record !in records || record.error != null
        if (rejected) {
            cell.close()
            if (record in records) {
                record.phase = CodeModePhase.LOST
                record.error = record.error ?: "code-mode runtime stopped during startup; source was not rerun"
                record.updatedAt = config.clock.millis()
                store.save(records, history.entries, dirtyKeys = setOf(record.key), changedRecord = record)
            }
            false
        } else {
            cells[record.id] = cell
            record.phase = CodeModePhase.ACTIVE
            store.save(records, history.entries, dirtyKeys = setOf(record.key), changedRecord = record)
            true
        }
    }

    fun cell(record: CodeModeRecord): CodeModeCell? = access.withKey(record.key) { cells[record.id] }

    /** A known record saves only its conversation; a no-arg call retries a failed whole-head carry. */
    fun save(record: CodeModeRecord? = null) {
        if (record == null) {
            monitor.withLock { store.save(records, history.entries) }
        } else {
            access.withKey(record.key) {
                store.save(records, history.entries, dirtyKeys = setOf(record.key), changedRecord = record)
            }
        }
    }

    fun complete(record: CodeModeRecord, output: String) = access.withKey(record.key) {
        admissions.remove(record.id)
        record.output = output
        record.phase = CodeModePhase.COMPLETED
        record.updatedAt = config.clock.millis()
        cells.remove(record.id)?.close()
        store.save(records, history.entries, dirtyKeys = setOf(record.key), changedRecord = record)
    }

    fun lose(record: CodeModeRecord, message: String, cancellation: CancellationException? = null) =
        access.withKey(record.key) {
            admissions.remove(record.id)
            cells.remove(record.id)?.close()
            record.phase = CodeModePhase.LOST
            record.error = message
            record.updatedAt = config.clock.millis()
            try {
                store.save(records, history.entries, dirtyKeys = setOf(record.key), changedRecord = record)
            } catch (error: CodeModePersistenceException) {
                if (cancellation == null) throw error
                cancellation.addSuppressed(error)
            }
        }

    /** Every live cell closes and its record is lost. The records keep their updatedAt, the time of
     *  their last use: a head stop is not a use (V4-287: a stop 23 hours on kept a record ~47 hours). */
    fun onHeadStop() {
        val keys = monitor.withLock {
            startup.stop()
            records.map(CodeModeRecord::key).distinct()
        }
        keys.forEach { key ->
            access.withKey(key) {
                records.filter { it.key == key && !it.terminal() }
                    .filter { it.phase != CodeModePhase.STARTING }.forEach { record ->
                        cells.remove(record.id)?.close()
                        record.phase = CodeModePhase.LOST
                        record.error = "completed client call ids=${record.results.keys}; source was not rerun"
                    }
                store.save(records, history.entries, dirtyKeys = setOf(key))
            }
        }
    }

    /** See [CodexCodeModeSweeper.evictIdleCell]; the eviction is persisted before the slot is reused. */
    fun evictIdleCell(): CodeModeRecord? {
        val keys = monitor.withLock { records.map(CodeModeRecord::key).distinct() }
        keys.forEach { key ->
            val victim = access.withKey(key) {
                sweeper.evictIdleCell(key)?.also {
                    store.save(records, history.entries, dirtyKeys = setOf(key))
                }
            }
            if (victim != null) return victim
        }
        return null
    }

    /** A retry or this record's client ids, never just another turn on the same conversation key. */
    private object CodeModeOwnerMatch {
        fun matches(
            record: CodeModeRecord,
            digest: String,
            ids: Set<String>,
            excluded: Set<String>,
        ): Boolean = record.id !in excluded &&
            (record.lastDigest == digest || (ids.isNotEmpty() && record.clientIds().any { it in ids }))
    }

    private inner class PeriodicSweep {
        inline fun <T> withKey(key: String, block: () -> T): T = access.withKey(key) {
            // Requests only clean their own key. The timer owns cross-key housekeeping and its I/O.
            val changed = sweeper.sweep(key)
            store.save(records, history.entries, retryOnly = !changed, dirtyKeys = setOf(key))
            block()
        }

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
        val priorDigest = record.lastDigest
        val priorTime = record.updatedAt
        val prior = record.accepted.copy()
        record.lastDigest = digest
        record.updatedAt = config.clock.millis()
        record.accepted.accept(supplied, media)
        val snapshotGeneration = record.saveGeneration + 1
        try {
            store.save(records, history.entries, dirtyKeys = setOf(record.key), changedRecord = record)
        } catch (error: CodeModePersistenceException) {
            // A later snapshot may already contain a newer acceptance or completed cell.
            if (record.saveGeneration == snapshotGeneration) {
                record.lastDigest = priorDigest
                record.updatedAt = priorTime
                record.accepted.restore(prior)
            }
            throw error
        }
    }
}

/** V4-337: the start of a conversation's turn, under the registry's [monitor] and on its own collections.
 *  Only there — the registry's completed() also runs mid-turn, where a record that went could run again. */
internal class CodeModeTurnStart(
    private val access: CodeModeRegistryAccess,
    private val retention: CodeModeRecordRetention,
    private val records: MutableList<CodeModeRecord>,
    private val history: CodeModeExpiredHistory,
    private val store: CodexCodeModeStore,
    private val clock: Clock,
) {
    /** Before [key]'s history is built: a save an earlier turn could not make is made, and
     *  [CodeModeRecordRetention.beginTurn] runs, so a script this turn starts is measured on what stays. */
    fun begin(key: String) = access.withKey(key) {
        val changed = retention.beginTurn(records, history, key, clock.millis())
        store.save(records, history.entries, retryOnly = !changed, dirtyKeys = setOf(key))
    }
}
