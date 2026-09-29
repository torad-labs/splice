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
import splice.upstream.codemode.CodeModeCell
import splice.upstream.codemode.CodeModeResult
import java.time.Clock
import kotlin.time.Duration

internal class CodexCodeModeRegistry(
    private val config: CodeModeBridgeConfig,
    json: Json,
    private val sweepInterval: Duration,
) {
    private val monitor = Any()
    private val store = CodexCodeModeStore(config.state, json, config.log)
    private val loaded = store.load()
    private val records = loaded.records.map(CodeModeRecordSnapshot::restore).toMutableList()
    private val history = CodeModeExpiredHistory(loaded.expired.toMutableList(), config.retention.records)
    private val retention = CodeModeRecordRetention(config.retention, json, config.log)
    private val cells = mutableMapOf<String, CodeModeCell>()
    private val admissions = mutableMapOf<String, Long>()
    private val sweeper = CodexCodeModeSweeper(config, records, cells, admissions, history)
    private var generation = 0L
    private val timed = CodeModeTimedSweep(
        monitor,
        sweeper,
        records,
        { store.save(records, history.entries) },
        config,
        sweepInterval,
    )

    /** V4-337: where each code-mode turn starts, before its history is built. */
    val turnStart = CodeModeTurnStart(monitor, retention, records, history, store, config.clock)

    init {
        synchronized(monitor) {
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
    ): CodeModeRecord? = synchronized(monitor) {
        if (sweeper.sweep()) store.save(records, history.entries)
        val activeIds = if (callbackIds.isEmpty()) resultIds else resultIds + callbackIds
        records.lastOrNull { record ->
            record.key == key && record.phase == CodeModePhase.ACTIVE &&
                CodeModeOwnerMatch.matches(record, digest, activeIds)
        } ?: records.lastOrNull { record ->
            record.key == key && record.phase == CodeModePhase.LOST &&
                CodeModeOwnerMatch.matches(record, digest, resultIds)
        }
    }

    fun completed(key: String): List<CodeModeRecord> = synchronized(monitor) {
        if (sweeper.sweep()) store.save(records, history.entries)
        records.filter { it.key == key && it.phase == CodeModePhase.COMPLETED }
    }

    fun expiredHistory(key: String, digest: String, ids: Set<String>): Boolean = synchronized(monitor) {
        if (sweeper.sweep()) store.save(records, history.entries)
        history.entries.any { marker ->
            marker.key == key && (marker.lastDigest == digest || marker.resultIds.any { it in ids })
        }
    }

    fun foreignResultOwner(key: String, ids: Set<String>): CodeModeRecord? = synchronized(monitor) {
        if (sweeper.sweep()) store.save(records, history.entries)
        records.firstOrNull { record -> record.key != key && record.clientIds().any { it in ids } }
    }

    fun unknownBridgeResults(key: String, ids: Set<String>): Set<String> = synchronized(monitor) {
        if (sweeper.sweep()) store.save(records, history.entries)
        val known = records.filter { it.key == key }.flatMap(CodeModeRecord::clientIds).toSet()
        ids.filter { it.startsWith(CODE_MODE_CLIENT_ID_PREFIX) && it !in known }.toSet()
    }

    fun add(record: CodeModeRecord): Boolean = synchronized(monitor) {
        if (sweeper.sweep()) store.save(records, history.entries)
        val candidate = records.toMutableList()
        val candidateHistory = CodeModeExpiredHistory(history.entries.toMutableList(), config.retention.records)
        if (!retention.makeRoom(candidate, candidateHistory, record, config.clock.millis())) return@synchronized false
        candidate += record
        // Admission and evictions are reversible until execution: publish only after durable save.
        store.save(candidate, candidateHistory.entries)
        records.clear()
        records.addAll(candidate)
        history.entries.clear()
        history.entries.addAll(candidateHistory.entries)
        admissions[record.id] = generation
        timed.arm()
        true
    }

    fun attach(record: CodeModeRecord, cell: CodeModeCell): Boolean = synchronized(monitor) {
        val admittedGeneration = admissions.remove(record.id)
        val rejected = admittedGeneration != generation || record !in records || record.error != null
        if (rejected) {
            cell.close()
            if (record in records) {
                record.phase = CodeModePhase.LOST
                record.error = record.error ?: "code-mode runtime stopped during startup; source was not rerun"
                record.updatedAt = config.clock.millis()
                store.save(records, history.entries)
            }
            false
        } else {
            cells[record.id] = cell
            record.phase = CodeModePhase.ACTIVE
            store.save(records, history.entries)
            true
        }
    }

    fun cell(record: CodeModeRecord): CodeModeCell? = synchronized(monitor) { cells[record.id] }

    fun save() = synchronized(monitor) {
        store.save(records, history.entries)
    }

    fun complete(record: CodeModeRecord, output: String) = synchronized(monitor) {
        admissions.remove(record.id)
        record.output = output
        record.phase = CodeModePhase.COMPLETED
        record.updatedAt = config.clock.millis()
        cells.remove(record.id)?.close()
        store.save(records, history.entries)
    }

    fun lose(record: CodeModeRecord, message: String, cancellation: CancellationException? = null) =
        synchronized(monitor) {
            admissions.remove(record.id)
            cells.remove(record.id)?.close()
            record.phase = CodeModePhase.LOST
            record.error = message
            record.updatedAt = config.clock.millis()
            try {
                store.save(records, history.entries)
            } catch (error: CodeModePersistenceException) {
                if (cancellation == null) throw error
                cancellation.addSuppressed(error)
            }
        }

    /** Every live cell closes and its record is lost. The records keep their updatedAt, the time of
     *  their last use: a head stop is not a use (V4-287: a stop 23 hours on kept a record ~47 hours). */
    fun onHeadStop() = synchronized(monitor) {
        generation++
        records.filterNot(CodeModeRecord::terminal).forEach { record ->
            cells.remove(record.id)?.close()
            record.phase = CodeModePhase.LOST
            record.error = "completed client call ids=${record.results.keys}; source was not rerun"
        }
        cells.values.forEach(CodeModeCell::close)
        cells.clear()
        store.save(records, history.entries)
    }

    /** See [CodexCodeModeSweeper.evictIdleCell]; the eviction is persisted before the slot is reused. */
    fun evictIdleCell(): CodeModeRecord? = synchronized(monitor) {
        sweeper.evictIdleCell()?.also { store.save(records, history.entries) }
    }

    /** V4-179: [media] holds the follow-up items rendered for each supplied result; see [CodeModeAccepted.accept]. */
    fun acceptResults(
        record: CodeModeRecord,
        digest: String,
        supplied: Map<String, CodeModeResult>,
        media: Map<String, List<JsonElement>> = emptyMap(),
    ) = synchronized(monitor) {
        val priorDigest = record.lastDigest
        val priorTime = record.updatedAt
        val prior = record.accepted.copy()
        record.lastDigest = digest
        record.updatedAt = config.clock.millis()
        record.accepted.accept(supplied, media)
        try {
            store.save(records, history.entries)
        } catch (error: CodeModePersistenceException) {
            // Only this pre-advance transition is reversible. Never roll back a running cell.
            record.lastDigest = priorDigest
            record.updatedAt = priorTime
            record.accepted.restore(prior)
            throw error
        }
    }
}

/** A retry or one of this record's client ids, never just another turn with the same conversation key. */
private object CodeModeOwnerMatch {
    fun matches(record: CodeModeRecord, digest: String, ids: Set<String>): Boolean =
        record.lastDigest == digest || (ids.isNotEmpty() && record.clientIds().any { it in ids })
}

/** V4-337: the start of a conversation's turn, under the registry's [monitor] and on its own collections.
 *  Only there — the registry's completed() also runs mid-turn, where a record that went could run again. */
internal class CodeModeTurnStart(
    private val monitor: Any,
    private val retention: CodeModeRecordRetention,
    private val records: MutableList<CodeModeRecord>,
    private val history: CodeModeExpiredHistory,
    private val store: CodexCodeModeStore,
    private val clock: Clock,
) {
    /** Before [key]'s history is built: a save an earlier turn could not make is made, and
     *  [CodeModeRecordRetention.beginTurn] runs, so a script this turn starts is measured on what stays. */
    fun begin(key: String) = synchronized(monitor) {
        val changed = retention.beginTurn(records, history, key, clock.millis())
        store.save(records, history.entries, retryOnly = !changed)
    }
}
