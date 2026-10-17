// NEW: Oct 11, 2026 — the whole rows behind a window's compact ones, read back from the lines they were kept from.
package splice.app.sources

import splice.app.sources.dense.PerfLineSpot
import splice.usage.perf.PerfRow
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Collections
import java.util.IdentityHashMap

/** [rows] and [spots] stand in step: the spot of each is where its line lies. A line that no longer reads as the row
 *  it was kept for means the file moved under the read, which the caller answers by reading again. */
internal class PerfWholeRows(
    private val rows: List<PerfRow>,
    private val spots: List<PerfLineSpot?>,
    private val decode: PerfLineDecode,
) {
    fun of(wanted: List<PerfRow>): List<PerfRow> {
        val asked = Collections.newSetFromMap(IdentityHashMap<PerfRow, Boolean>()).apply { addAll(wanted) }
        val found = IdentityHashMap<PerfRow, PerfRow>()
        val channels = HashMap<Path, FileChannel>()
        try {
            rows.forEachIndexed { index, row ->
                val spot = spots[index]
                if (spot != null && row in asked) found[row] = read(channels, spot, row)
            }
        } catch (_: IOException) {
            throw PerfProjectionChanged()
        } finally {
            channels.values.forEach(FileChannel::close)
        }
        return wanted.map { found[it] ?: it }
    }

    private fun read(channels: MutableMap<Path, FileChannel>, spot: PerfLineSpot, kept: PerfRow): PerfRow {
        val path = spot.generation.path
        val channel = channels.getOrPut(path) { FileChannel.open(path, StandardOpenOption.READ) }
        val line = PerfLineReader(channel, spot.start, spot.end).next() ?: throw PerfProjectionChanged()
        val row = decode.decode(line).row
        if (row == null || row.ts != kept.ts) throw PerfProjectionChanged()
        return row
    }
}
