package splice.provider.codex.state.retention

import splice.provider.codex.CodeModeBridgeConfig
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.state.CodeModeCellRetention
import splice.provider.codex.state.CodeModeExpiredHistory
import splice.provider.codex.state.CodeModeSessionEnd
import splice.upstream.codemode.CodeModeCell

/** Runs under the registry's monitor; it mutates the registry's own collections in place. */
internal class CodexCodeModeSweeper(
    private val config: CodeModeBridgeConfig,
    private val records: MutableList<CodeModeRecord>,
    private val cells: MutableMap<String, CodeModeCell>,
    private val admissions: MutableMap<String, Long>,
    private val history: CodeModeExpiredHistory,
    private val closeSession: CodeModeSessionEnd,
    private val retainedCells: CodeModeCellRetention,
) {
    private val expiry = CodeModeRecordExpiry(
        config,
        records,
        cells,
        admissions,
        history,
        closeSession,
        retainedCells,
    )

    /** Expires records and reaps dead or over-age unknown parked cells; true when anything changed. */
    fun sweep(key: String? = null, protectedKey: String? = null): Boolean =
        expiry.expire(key) or retainedCells.sweep(key, protectedKey) or expireMarkers(key)

    /** The keys neither an admission nor a sweep may retire: one being admitted, or one with a running cell. */
    fun busyKeys(): Set<String> = records.filter {
        it.id in admissions || retainedCells.running(
            it,
            splice.provider.codex.state.CodeModeCellProtection.SESSION_OWNED,
        )
    }
        .mapTo(mutableSetOf(), CodeModeRecord::key)

    /** Without an explicit count bound, old expiry evidence is bounded by the same configured lifetime. */
    private fun expireMarkers(key: String?): Boolean {
        val cutoff = config.clock.millis() - config.retention.ttl.inWholeMilliseconds
        // why: every query runs this; the age test goes first because almost no marker is old enough, and it costs
        // a long comparison where the key test costs a string comparison per marker.
        return history.entries.removeAll { it.expiredAt < cutoff && (key == null || it.key == key) }
    }
}
