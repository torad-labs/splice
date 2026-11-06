package splice.app.sources.observability

import kotlinx.serialization.Serializable

internal const val PERF_CURSOR_TTL_MS = 30L * 60 * 1_000
internal const val PERF_CURSOR_DISK_BYTES = 16L * 1_024 * 1_024
internal const val PERF_CURSOR_ENTRY_BYTES = 20
internal const val PERF_CURSOR_MAX_SEGMENTS = 256
internal const val PERF_CURSOR_MAX_LINE_BYTES = 128 * 1_024
internal const val PERF_CURSOR_WORK_BYTES = 512L * 1_024 * 1_024
internal const val PERF_CURSOR_RESERVE_BYTES = 4L * 1_024 * 1_024
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
