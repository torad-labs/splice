package splice.app.observability

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.util.JsonScalars
import splice.core.util.PerfWorkBudget
import splice.core.util.SecureFile
import splice.usage.perf.query.PerfConfigInterval
import splice.usage.perf.query.PerfConfigProvenance
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** A bounded persisted interval ledger. Missing, truncated and unclean intervals remain unknown to the query layer. */
internal class PerfConfigHistory(private val path: Path) : PerfConfigProvenance, AutoCloseable {
    private val memory = PerfWorkBudget.acquire(HISTORY_BYTES)
    private val values = ArrayList<PerfConfigInterval>()

    init {
        if (memory != null) restore()
    }

    @Synchronized
    fun changed(head: String, at: Long, revision: String?, boot: String) {
        if (memory == null) return
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

    @Synchronized
    fun persist(now: Long) {
        if (memory == null) return
        // Open intervals are persisted only through a proven observation horizon, never beyond a process crash.
        val data = JsonArray(
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
        SecureFile.writeAtomic0600(path, data.toString())
    }

    override fun close() { memory?.close() }

    private fun restore() {
        try {
            if (!Files.exists(path) || Files.size(path) > HISTORY_BYTES) return
            val saved = Json.parseToJsonElement(Files.readString(path)) as? JsonArray ?: return
            if (saved.size > MAX_INTERVALS) return
            values += saved.mapNotNull { (it as? JsonObject)?.let(::interval) }
        } catch (_: IOException) {
            values.clear()
        } catch (_: IllegalArgumentException) {
            values.clear()
        }
    }

    private fun interval(row: JsonObject): PerfConfigInterval? {
        val head = JsonScalars.str(row, "head") ?: return null
        val from = JsonScalars.str(row, "from")?.toLongOrNull()
        val until = JsonScalars.str(row, "until")?.toLongOrNull()
        if (from == null || until == null) return null
        val valid = listOf(head.length <= MAX_HEAD_LENGTH, from >= 0, until >= from).all { it }
        return if (valid) {
            PerfConfigInterval(
                head,
                from,
                until,
                JsonScalars.str(row, "revision"),
                JsonScalars.str(row, "boot"),
            )
        } else {
            null
        }
    }
}

private const val MAX_INTERVALS = 512
private const val MAX_HEAD_LENGTH = 128
private const val HISTORY_BYTES = 256L * 1_024
