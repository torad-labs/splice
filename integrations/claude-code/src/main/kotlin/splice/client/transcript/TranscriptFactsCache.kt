// NEW: Oct 12, 2026 (splice-lead) — what a history scan learned from each transcript, kept while the file is the same
// file. A refresh lists and stats the files and reads again only a new or changed one; a file that is gone is forgotten.
package splice.client.transcript

import splice.core.memory.HeapCategory
import splice.core.memory.HeapObservation
import splice.core.memory.HeapOwnerState
import splice.core.memory.HeapOwners
import splice.core.memory.HeapRelease
import splice.core.memory.HeapReservations
import splice.core.util.FileIdentity
import java.nio.file.Path

// why: every pass walks every transcript, so a cap below the transcripts on disk evicts what the next pass needs and
// every pass reads them all again: 4,096 under 11,449 transcripts reread about 7,000 heads and tails (128 KiB each) per
// project-tags request, the daemon top allocator on Oct 19, 2026. 65,536 holds every machine seen, still bounded.
private const val MAX_TRANSCRIPT_FACTS = 65_536

// why: 32 MiB holds 65,536 facts at their typical 500 bytes; one entry is about 500 bytes, so 4 MiB thrashed at ~8,000.
private const val MAX_TRANSCRIPT_FACT_BYTES = 32L shl 20

// why: Estimate 160 bytes for fixed fact-cache entry overhead; string characters are counted separately.
private const val TRANSCRIPT_FACT_BYTES = 160L

/** What the scan needs from one primary transcript, read from its head and tail. */
internal data class TranscriptFacts(val name: String?, val cwd: String?)

/** A transcript's stamp: a file that is appended to, rewritten or replaced gets a new one. [key] is the file's
 *  device and inode where the platform has them. */
internal data class TranscriptStamp(val size: Long, val modifiedMs: Long, val key: FileIdentity?)

internal class TranscriptFactsCache(private val heap: HeapReservations? = null) {
    internal data class Held(val stamp: TranscriptStamp, val facts: TranscriptFacts) {
        var bytes: Long = 0L
        var observation: HeapObservation? = null
    }

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
            met.remove(file)?.let { bytes -= it.bytes }
            val size = retainedBytes(file, facts)
            if (size > MAX_TRANSCRIPT_FACT_BYTES) return
            while (met.isNotEmpty() && overBudget(size)) {
                val oldest = met.entries.iterator().next()
                bytes -= oldest.value.bytes
                met.remove(oldest.key)
            }
            // Unchanged facts keep their ticket and age instead of accumulating overlapping Cleaner handles.
            met[file] = before[file]?.takeIf { it.stamp == stamp && it.facts === facts }
                ?: Held(stamp, facts).also { it.bytes = size }
            bytes += size
        }

        private fun overBudget(size: Long): Boolean =
            met.size >= MAX_TRANSCRIPT_FACTS || bytes + size > MAX_TRANSCRIPT_FACT_BYTES

        private fun retainedBytes(file: Path, facts: TranscriptFacts): Long =
            TRANSCRIPT_FACT_BYTES + 2L * (file.toString().length + (facts.name?.length ?: 0) + (facts.cwd?.length ?: 0))

        fun commit() {
            held.forEach { (file, item) ->
                if (item !== met[file]) {
                    item.observation?.close(if (file in met) HeapRelease.CLOSED else HeapRelease.EVICTED)
                }
            }
            val source = heap
            if (source != null) {
                met.values.forEach { item ->
                    if (item.observation == null) {
                        val observation = HeapOwners.observe(item, source, item.bytes, HeapCategory.TRANSCRIPT_CACHE)
                        HeapOwners.keep(item.facts, observation.share(HeapOwnerState.AWAITING_COLLECTION))
                        item.observation = observation
                    }
                }
            }
            held = met
        }
    }
}
