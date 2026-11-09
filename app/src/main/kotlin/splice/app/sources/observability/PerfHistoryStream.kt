package splice.app.sources.observability

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import splice.core.perf.observation.PerfAggregatePoint
import splice.core.perf.observation.PerfAggregateSnapshot
import java.io.IOException
import java.nio.file.Files

internal fun interface PerfHistoryAccept {
    fun accept(snapshot: PerfAggregateSnapshot)
}

/** Keeps the 512 KiB file contract while decoding only one bounded point at a time. */
internal object PerfHistoryStream {
    private val json = Json { ignoreUnknownKeys = true }
    private val metadataFields = setOf("identity", "fromMs", "untilMs", "coverage")

    fun read(entry: PerfHistoryEntry, accept: PerfHistoryAccept) {
        val text = text(entry)
        require(text.isNotEmpty()) { "empty history file" }
        val slices = PerfJsonSlices(text)
        val array = requireNotNull(slices.fields(text.indices)["snapshots"])
        val header = json.decodeFromString<PerfReadFileHeader>(text)
        require(header.schemaVersion == 1 && header.fromMs == entry.fromMs && header.resolutionMs == entry.resolutionMs)
        for (range in slices.elements(array)) snapshot(slices, range, accept)
    }

    private fun text(entry: PerfHistoryEntry): String {
        val bytes = Files.newInputStream(entry.path).use { it.readNBytes(PERF_HISTORY_FILE_BYTES.toInt() + 1) }
        if (bytes.size > PERF_HISTORY_FILE_BYTES) throw IOException("aggregate file size limit")
        return bytes.toString(Charsets.UTF_8).trim()
    }

    private fun snapshot(slices: PerfJsonSlices, range: IntRange, accept: PerfHistoryAccept) {
        val fields = slices.fields(range)
        val points = requireNotNull(fields["points"])
        val metadata = metadata(slices, fields)
        val snapshot = json.decodeFromString<PerfAggregateSnapshot>(metadata)
        if (PerfHistorySize.snapshot(snapshot) > METADATA_BYTES) throw IOException(MEMORY_LIMIT)
        accept.accept(snapshot)
        for (point in slices.elements(points)) {
            val value = json.decodeFromString<PerfAggregatePoint>(slices.value(point, POINT_CHARS))
            if (PerfHistorySize.point(value) > HISTORY_POINT_DECODE_BYTES) throw IOException(MEMORY_LIMIT)
            accept.accept(snapshot.copy(points = listOf(value)))
        }
    }
    private fun metadata(slices: PerfJsonSlices, fields: Map<String, IntRange>): String {
        val selected = fields.filterKeys { it in metadataFields }
        if (selected.values.sumOf { it.last - it.first + 1 } > METADATA_CHARS) throw IOException(MEMORY_LIMIT)
        val metadata = selected.entries.joinToString(",", "{", ",\"points\":[]}") {
            "\"${it.key}\":" + slices.value(it.value, METADATA_CHARS)
        }
        if (metadata.length > METADATA_CHARS) throw IOException(MEMORY_LIMIT)
        return metadata
    }
}

@Serializable
private data class PerfReadFileHeader(val schemaVersion: Int = 1, val resolutionMs: Long, val fromMs: Long)

// Limit non-point JSON fragments to 16 KiB of characters before constructing the metadata document.
private const val METADATA_CHARS = 16 * 1_024

// Cap decoded non-point snapshot metadata at 256 KiB while each point is processed separately.
private const val METADATA_BYTES = 256L * 1_024

// Decode at most 32 KiB of JSON text for one point rather than materializing the complete file tree.
private const val POINT_CHARS = 32 * 1_024

// Reject a decoded point above 512 KiB so one record cannot exhaust the streaming reader's workspace.
private const val HISTORY_POINT_DECODE_BYTES = 512L * 1_024

private const val MEMORY_LIMIT = "query_memory_limit"
