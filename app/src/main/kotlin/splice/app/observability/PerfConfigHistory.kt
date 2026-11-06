package splice.app.observability

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.util.JsonScalars
import splice.core.util.JsonWire
import splice.core.util.PerfWorkBudget
import splice.core.util.SecureFile
import splice.usage.perf.query.PerfConfigComparisonScope
import splice.usage.perf.query.PerfConfigInterval
import splice.usage.perf.query.PerfConfigProvenance
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** A bounded persisted interval ledger. Missing, truncated and unclean intervals remain unknown to the query layer. */
internal class PerfConfigHistory(private val path: Path) : PerfConfigProvenance, AutoCloseable {
    private val memory = PerfWorkBudget.acquire(HISTORY_BYTES)
    private val values = ArrayList<PerfConfigInterval>()
    val writes = PerfWriteStatus("effective_config_history")

    init {
        if (memory != null) restore()
    }

    @Synchronized
    fun changed(head: String, at: Long, revision: String?, boot: String) {
        if (memory == null) return
        if (!validText(head, revision, boot)) return
        val prior = values.indexOfLast { it.head == head && it.untilMs == null }
        if (prior >= 0) {
            val old = values[prior]
            if (old.bootId == boot && old.revision == revision) return
            values[prior] = old.copy(untilMs = at.coerceAtLeast(old.fromMs))
        }
        values += PerfConfigInterval(head, at, null, revision, boot)
        while (values.size > MAX_INTERVALS) values.removeAt(0)
    }

    @Synchronized
    override fun intervals(fromMs: Long, untilMs: Long): List<PerfConfigInterval> =
        values.filter { it.fromMs < untilMs && (it.untilMs ?: Long.MAX_VALUE) > fromMs }

    override fun comparisonScope(): PerfConfigComparisonScope = PerfConfigComparisonScope.EFFECTIVE_KNOB_POLICY

    @Synchronized
    fun persist(now: Long) {
        if (memory == null) { writes.reject(); return }
        val work = PerfWorkBudget.acquire(ENCODE_BYTES)
        if (work == null) { writes.reject(); return }
        work.use {
            writes.begin()
            var success = false
            try {
                val data = document(now)
                require(JsonWire.byteSize(data) <= MAX_FILE_BYTES) { "configuration_history_limit" }
                SecureFile.writeAtomic0600(path, data.toString())
                success = true
            } finally {
                writes.end(success)
            }
        }
    }

    private fun document(now: Long): JsonArray =
        // Open intervals are persisted only through a proven observation horizon, never beyond a process crash.
        JsonArray(
            values.map { interval ->
                buildJsonObject {
                    put("head", interval.head)
                    put("from", interval.fromMs)
                    put("until", interval.untilMs ?: now)
                    put("revision", interval.revision?.let(::JsonPrimitive) ?: JsonNull)
                    put("boot", interval.bootId?.let(::JsonPrimitive) ?: JsonNull)
                }
            },
        )

    @Synchronized
    override fun close() { values.clear(); memory?.close() }

    private fun restore() {
        val work = PerfWorkBudget.acquire(DECODE_BYTES) ?: return
        work.use { restoreHeld() }
    }

    private fun restoreHeld() {
        try {
            if (!Files.exists(path)) return
            val bytes = Files.newInputStream(path).use { it.readNBytes(MAX_FILE_BYTES + 1) }
            require(bytes.size <= MAX_FILE_BYTES) { "configuration_history_limit" }
            val text = bytes.decodeToString(throwOnInvalidSequence = true)
            JsonWire.requireRequestNesting(text)
            val saved = Json.parseToJsonElement(text) as? JsonArray ?: return
            require(saved.size <= MAX_INTERVALS) { "configuration_interval_limit" }
            values += saved.mapNotNull { (it as? JsonObject)?.let(::interval) }
        } catch (_: IOException) {
            values.clear()
        } catch (_: IllegalArgumentException) {
            values.clear()
        }
    }

    private fun interval(row: JsonObject): PerfConfigInterval? {
        val head = JsonScalars.str(row, "head")
        val boot = JsonScalars.str(row, "boot")
        if (head == null || boot == null) return null
        val from = JsonScalars.str(row, "from")?.toLongOrNull()
        val until = JsonScalars.str(row, "until")?.toLongOrNull()
        if (from == null || until == null) return null
        val revision = JsonScalars.str(row, "revision")
        val valid = listOf(validText(head, revision, boot), from >= 0, until >= from).all { it }
        return if (valid) {
            PerfConfigInterval(
                head,
                from,
                until,
                revision,
                boot,
            )
        } else {
            null
        }
    }

    private fun validText(head: String, revision: String?, boot: String): Boolean =
        listOf(ID_TEXT.matches(head), ID_TEXT.matches(boot), revision == null || REVISION.matches(revision)).all { it }
}

private const val MAX_INTERVALS = 128
private const val HISTORY_BYTES = 256L * 1_024
private const val MAX_FILE_BYTES = 64 * 1_024
private const val ENCODE_BYTES = 512L * 1_024
private const val DECODE_BYTES = 4L * 1_024 * 1_024
private val ID_TEXT = Regex("[A-Za-z0-9_.-]{1,128}")
private val REVISION = Regex("[0-9a-f]{64}")
