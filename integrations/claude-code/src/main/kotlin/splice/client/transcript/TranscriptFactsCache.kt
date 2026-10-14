// NEW: Oct 12, 2026 (splice-lead) — what a history scan learned from each transcript, kept while the file is the same
// file. A refresh lists and stats the files and reads again only a new or changed one; a file that is gone is forgotten.
package splice.client.transcript

import splice.core.util.FileIdentity
import java.nio.file.Path

private const val MAX_TRANSCRIPT_FACTS = 4_096
private const val MAX_TRANSCRIPT_FACT_BYTES = 4L shl 20
private const val TRANSCRIPT_FACT_BYTES = 160L

/** What the scan needs from one primary transcript, read from its head and tail. */
internal data class TranscriptFacts(val name: String?, val cwd: String?)

/** A transcript's stamp: a file that is appended to, rewritten or replaced gets a new one. [key] is the file's
 *  device and inode where the platform has them. */
internal data class TranscriptStamp(val size: Long, val modifiedMs: Long, val key: FileIdentity?)

internal class TranscriptFactsCache {
    internal data class Held(val stamp: TranscriptStamp, val facts: TranscriptFacts)

    private var held: Map<Path, Held> = emptyMap()

    fun pass(): Pass = Pass(held)

    /** One refresh. [commit] makes what it met the cache, so a file it did not meet is dropped. */
    inner class Pass(private val before: Map<Path, Held>) {
        private val met = LinkedHashMap<Path, Held>()
        private var bytes = 0L

        /** The facts held for [file] while its [stamp] is unchanged, else null: read it and [remember]. */
        fun lookup(file: Path, stamp: TranscriptStamp): TranscriptFacts? =
            before[file]?.takeIf { it.stamp == stamp }?.facts?.also { remember(file, stamp, it) }

        fun remember(file: Path, stamp: TranscriptStamp, facts: TranscriptFacts) {
            met.remove(file)?.let { bytes -= retainedBytes(file, it.facts) }
            val size = retainedBytes(file, facts)
            if (size > MAX_TRANSCRIPT_FACT_BYTES) return
            while (met.isNotEmpty() && overBudget(size)) {
                val oldest = met.entries.iterator().next()
                bytes -= retainedBytes(oldest.key, oldest.value.facts)
                met.remove(oldest.key)
            }
            met[file] = Held(stamp, facts)
            bytes += size
        }

        private fun overBudget(size: Long): Boolean =
            met.size >= MAX_TRANSCRIPT_FACTS || bytes + size > MAX_TRANSCRIPT_FACT_BYTES

        private fun retainedBytes(file: Path, facts: TranscriptFacts): Long =
            TRANSCRIPT_FACT_BYTES + 2L * (file.toString().length + (facts.name?.length ?: 0) + (facts.cwd?.length ?: 0))

        fun commit() {
            held = met
        }
    }
}
