// NEW: share repeated descriptions in the single lossless perf-row cache, never a second row copy.
package splice.app.sources

/** Preserve every field while sharing only repeated descriptions in the source-owned bounded pool. */
internal class PerfTurnsRetention : PerfLineKeep {
    override fun keep(line: PerfCachedLine, names: PerfFieldNames): PerfCachedLine {
        val row = line.row ?: return line
        var textBytes = 0L
        fun share(value: String?): String? = value?.let {
            val shared = names.share(it)
            if (!names.contains(it)) textBytes += PERF_STRING_OVERHEAD_BYTES + it.length * PERF_CHAR_BYTES
            shared
        }
        // Trace identities are not interned: one-off values cannot crowd repeated descriptions out of the pool.
        listOf(row.turn, row.responseMessageId).forEach {
            if (it != null) textBytes += PERF_STRING_OVERHEAD_BYTES + it.length * PERF_CHAR_BYTES
        }
        return line.copy(
            row = row.copy(
                outcome = requireNotNull(share(row.outcome)),
                cause = share(row.cause),
                model = share(row.model),
                session = share(row.session),
                account = share(row.account),
                sessionId = share(row.sessionId),
            ),
            retainedTextBytes = textBytes,
        )
    }
}
