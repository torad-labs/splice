// NEW: retains bounded code-mode records, live cells, expiry markers, and stop generations.
package splice.provider.codex

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import splice.spi.CodeModeCell
import splice.spi.CodeModeResult

internal class CodexCodeModeRegistry(
    private val config: CodeModeBridgeConfig,
    json: Json,
) {
    private val monitor = Any()
    private val store = CodexCodeModeStore(config.stateFile, json)
    private val loaded = store.load()
    private val records = loaded.records.map(CodeModeRecordSnapshot::restore).toMutableList()
    private val history = CodeModeExpiredHistory(loaded.expired.toMutableList(), config.maxRecords)
    private val cells = mutableMapOf<String, CodeModeCell>()
    private val admissions = mutableMapOf<String, Long>()
    private var generation = 0L

    init {
        synchronized(monitor) {
            val changed = expireRecords() or history.trim(records, config.clock.millis())
            if (changed) store.save(records, history.entries)
        }
    }

    fun owner(key: String, digest: String, ids: Set<String>): CodeModeRecord? = synchronized(monitor) {
        if (expireRecords()) store.save(records, history.entries)
        records.lastOrNull { record ->
            record.key == key && record.phase == CodeModePhase.ACTIVE
        } ?: records.lastOrNull { record ->
            record.key == key && record.phase == CodeModePhase.LOST &&
                (
                    record.lastDigest == digest ||
                        record.clientIds().any { it in ids }
                    )
        }
    }

    fun completed(key: String): List<CodeModeRecord> = synchronized(monitor) {
        if (expireRecords()) store.save(records, history.entries)
        records.filter { it.key == key && it.phase == CodeModePhase.COMPLETED }
    }

    fun expiredHistory(key: String, digest: String, ids: Set<String>): Boolean = synchronized(monitor) {
        if (expireRecords()) store.save(records, history.entries)
        history.entries.any { marker ->
            marker.key == key && (marker.lastDigest == digest || marker.resultIds.any { it in ids })
        }
    }

    fun foreignResultOwner(key: String, ids: Set<String>): CodeModeRecord? = synchronized(monitor) {
        if (expireRecords()) store.save(records, history.entries)
        records.firstOrNull { record -> record.key != key && record.clientIds().any { it in ids } }
    }

    fun unknownBridgeResults(key: String, ids: Set<String>): Set<String> = synchronized(monitor) {
        if (expireRecords()) store.save(records, history.entries)
        val known = records.filter { it.key == key }.flatMap(CodeModeRecord::clientIds).toSet()
        ids.filter { it.startsWith(CODE_MODE_CLIENT_ID_PREFIX) && it !in known }.toSet()
    }

    fun add(record: CodeModeRecord): Boolean = synchronized(monitor) {
        if (expireRecords()) store.save(records, history.entries)
        val candidate = records.toMutableList()
        val candidateHistory = CodeModeExpiredHistory(history.entries.toMutableList(), config.maxRecords)
        while (candidate.size >= config.maxRecords) {
            val index = candidate.indexOfFirst(CodeModeRecord::terminal)
            if (index < 0) return@synchronized false
            candidateHistory.remember(candidate.removeAt(index), config.clock.millis())
        }
        candidate += record
        // Admission and evictions are reversible until execution: publish only after durable save.
        store.save(candidate, candidateHistory.entries)
        records.clear()
        records.addAll(candidate)
        history.entries.clear()
        history.entries.addAll(candidateHistory.entries)
        admissions[record.id] = generation
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

    fun save(retryOnly: Boolean = false) = synchronized(monitor) {
        store.save(records, history.entries, retryOnly)
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

    fun onHeadStop() = synchronized(monitor) {
        generation++
        val now = config.clock.millis()
        records.filterNot(CodeModeRecord::terminal).forEach { record ->
            cells.remove(record.id)?.close()
            record.phase = CodeModePhase.LOST
            record.error = "completed client call ids=${record.results.keys}; source was not rerun"
            record.updatedAt = now
        }
        cells.values.forEach(CodeModeCell::close)
        cells.clear()
        store.save(records, history.entries)
    }

    private fun expireRecords(): Boolean {
        val cutoff = config.clock.millis() - config.ttl.inWholeMilliseconds
        val stale = records.filter { it.updatedAt < cutoff }
        if (stale.isEmpty()) return false
        stale.forEach { record ->
            admissions.remove(record.id)
            cells.remove(record.id)?.close()
            history.remember(record, config.clock.millis())
        }
        records.removeAll(stale.toSet())
        return true
    }

    fun acceptResults(record: CodeModeRecord, digest: String, supplied: Map<String, CodeModeResult>) =
        synchronized(monitor) {
            val priorDigest = record.lastDigest
            val priorTime = record.updatedAt
            val priorResults = record.results.toMap()
            record.lastDigest = digest
            record.updatedAt = config.clock.millis()
            record.results.putAll(supplied)
            try {
                store.save(records, history.entries)
            } catch (error: CodeModePersistenceException) {
                // Only this pre-advance transition is reversible. Never roll back a running cell.
                record.lastDigest = priorDigest
                record.updatedAt = priorTime
                record.results.clear()
                record.results.putAll(priorResults)
                throw error
            }
        }
}
