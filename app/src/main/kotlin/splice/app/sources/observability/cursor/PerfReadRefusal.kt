package splice.app.sources.observability.cursor

import java.io.IOException

/** Read boundaries expose only this closed issue taxonomy; arbitrary I/O messages stay private. */
internal class PerfReadRefusal(val issue: PerfReadIssue) : IOException(issue.code)

internal enum class PerfReadIssue(val code: String) {
    CURSOR_BUSY("cursor_busy"),
    CURSOR_DEDUP_LIMIT("cursor_dedup_limit"),
    CURSOR_DEDUP_READ_ERROR("cursor_dedup_read_error"),
    CURSOR_DIRECTORY_PERMISSIONS("cursor_directory_permissions"),
    CURSOR_DISK_LIMIT("cursor_disk_limit"),
    CURSOR_FILTER_CHANGED("cursor_filter_changed"),
    CURSOR_INDEX_CHANGED("cursor_index_changed"),
    CURSOR_INDEX_INVALID("cursor_index_invalid"),
    CURSOR_INDEX_LIMIT("cursor_index_limit"),
    CURSOR_INDEX_SHORTENED("cursor_index_shortened"),
    CURSOR_INVENTORY_LIMIT("cursor_inventory_limit"),
    CURSOR_MANIFEST_LIMIT("cursor_manifest_limit"),
    CURSOR_OFFSET_INVALID("cursor_offset_invalid"),
    CURSOR_RECORD_CHANGED("cursor_record_changed"),
    CURSOR_RECORD_MALFORMED("cursor_record_malformed"),
    CURSOR_RECORD_MISSING("cursor_record_missing"),
    CURSOR_SEGMENT_CHANGED("cursor_segment_changed"),
    CURSOR_SEGMENT_EXPIRED("cursor_segment_expired"),
    CURSOR_SEGMENT_LIMIT("cursor_segment_limit"),
    CURSOR_SEGMENT_NOT_REGULAR("cursor_segment_not_regular"),
    CURSOR_SEGMENT_SHORTENED("cursor_segment_shortened"),
    CURSOR_SNAPSHOT_EXPIRED("cursor_snapshot_expired"),
    CURSOR_WORK_LIMIT("cursor_work_limit"),
    EVIDENCE_CONSUMER_LIMIT("evidence_consumer_limit"),
    EVIDENCE_MEMORY_LIMIT("evidence_memory_limit"),
    EVIDENCE_PROOF_SEGMENT_CHANGED("evidence_proof_segment_changed"),
    EVIDENCE_PROOF_SEGMENT_INVALID("evidence_proof_segment_invalid"),
    EVIDENCE_PROOF_SEGMENT_MISSING("evidence_proof_segment_missing"),
    EVIDENCE_SEGMENT_CHANGED("evidence_segment_changed"),
}
