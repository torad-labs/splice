package splice.app.sources.observability

import kotlinx.serialization.Serializable

// Expire cursor indexes after thirty minutes so abandoned traversals do not retain managed disk indefinitely.
internal const val PERF_CURSOR_TTL_MS = 30L * 60 * 1_000

// Reserve at most 16 MiB of managed disk for cursor indexes and manifests, separate from evidence retention.
internal const val PERF_CURSOR_DISK_BYTES = 16L * 1_024 * 1_024

// Each index entry stores one four-byte segment number and two eight-byte byte offsets.
internal const val PERF_CURSOR_ENTRY_BYTES = 20

// Freeze at most 256 segment descriptors so cursor manifests have a bounded inventory.
internal const val PERF_CURSOR_MAX_SEGMENTS = 256

// Reject records above 128 KiB before row decoding to bound one cursor step.
internal const val PERF_CURSOR_MAX_LINE_BYTES = 128 * 1_024

// Stop after 512 MiB of cumulative scan work; this is an I/O-work budget, not a heap allocation.
internal const val PERF_CURSOR_WORK_BYTES = 512L * 1_024 * 1_024

// Reserve 4 MiB of shared work memory for cursor construction and page decoding.
internal const val PERF_CURSOR_RESERVE_BYTES = 4L * 1_024 * 1_024

// Bound retained cursor output to 2 MiB so a page cannot consume the full query-work reservation.
internal const val PERF_CURSOR_OUTPUT_BYTES = 2L * 1_024 * 1_024

@Serializable
internal data class PerfCursorManifest(
    val id: String,
    val asOfMs: Long,
    val fromMs: Long,
    val untilMs: Long,
    val filterHash: String,
    val segments: List<PerfCursorSegment>,
    val matching: Long,
    val issues: List<String>,
    val malformed: Long,
    val scannedRows: Long,
    val scannedBytes: Long,
    val schemaVersion: Int = 1,
)

@Serializable
internal data class PerfCursorSegment(
    val id: String,
    val fileName: String,
    val fileKey: String?,
    val end: Long,
    val digest: String,
)

internal data class PerfCursorPosition(val segment: Int, val start: Long, val end: Long)
