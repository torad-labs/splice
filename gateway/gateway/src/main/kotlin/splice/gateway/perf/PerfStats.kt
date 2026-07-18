// NEW: the per-turn perf JSONL sink + reader (bottleneck instrument, pairs with core TurnPerf).
// One row per finished turn: {ts, model, outcome, compact, <marks>, <counters>}. Append is
// synchronous best-effort — I/O failure must never kill a turn (same doctrine as CompactStats).
// Reads are TAIL-BOUNDED (readJsonlTail) so the control-plane aggregation never heap-loads an
// unbounded history; the file is additive state (a new `<head>-perf.jsonl` beside the HUD
// contract files, not part of the frozen name set).
package splice.gateway.perf

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import splice.core.perf.PerfSnapshot
import splice.core.util.runCatchingCancellable
import splice.gateway.compact.readJsonlTail
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** The string facts a perf row carries beside the numeric snapshot. */
public data class PerfRowMeta(
    val model: String,
    val outcome: String,
    val compact: Boolean,
)

public class PerfStats(private val file: Path, private val clock: () -> Long = System::currentTimeMillis) {

    private val json = Json { ignoreUnknownKeys = true }

    // append is best-effort by design: I/O failure must not kill the turn (runCatchingCancellable
    // captures IOException etc. yet still lets coroutine cancellation propagate).
    public fun record(meta: PerfRowMeta, snap: PerfSnapshot) {
        runCatchingCancellable {
            Files.createDirectories(file.parent)
            val row = buildJsonObject {
                put("ts", clock())
                put("model", meta.model)
                put("outcome", meta.outcome)
                put("compact", meta.compact)
                snap.marks.forEach { (k, v) -> put(k, v) }
                snap.counters.forEach { (k, v) -> put(k, v) }
            }
            Files.writeString(
                file,
                row.toString() + "\n",
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
            )
        }
    }

    /** Numeric fields of the last [tailN] rows, newest last — the aggregation input. */
    // read is best-effort by design: a missing/corrupt file yields empty; a bad line is skipped.
    public fun tailNumeric(tailN: Int = DEFAULT_TAIL): List<Map<String, Long>> {
        if (!Files.exists(file)) return emptyList()
        val rows = runCatchingCancellable {
            readJsonlTail(file, READ_TAIL_BYTES).mapNotNull { line ->
                runCatchingCancellable { json.parseToJsonElement(line).jsonObject }.getOrNull()
            }
        }.getOrDefault(emptyList())
        return rows.takeLast(tailN).map { it.numericFields() }
    }

    private companion object {
        const val DEFAULT_TAIL = 200

        // ~256 KiB of trailing JSONL bounds parse cost regardless of file age.
        const val READ_TAIL_BYTES = 256 * 1024

        fun JsonObject.numericFields(): Map<String, Long> = buildMap {
            this@numericFields.forEach { (k, v) ->
                (v as? JsonPrimitive)?.longOrNull?.let { put(k, it) }
            }
        }
    }
}
