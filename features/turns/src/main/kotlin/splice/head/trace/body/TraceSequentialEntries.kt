package splice.head.trace.body

import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Path

/** A streaming reader retains one entry boundary, rescanning headers when a reference reuses an earlier chunk. */
internal class TraceSequentialEntries(
    private val channel: FileChannel,
    private val format: TracePackFormat,
    private val file: Path,
) {
    private var scanned = TRACE_PACK_START_BYTES.toLong()
    private var boundary: Long? = null

    fun contains(offset: Long): Boolean {
        if (boundary == offset) return true
        if (offset < scanned) scanned = TRACE_PACK_START_BYTES.toLong()
        while (scanned < offset) {
            val payload = scanned + format.headerBytes
            val header = format.entry(channel, payload)
                ?: throw IOException("invalid trace body chunk header at byte $scanned: $file")
            scanned = payload + header.stored
            boundary = payload
        }
        return boundary == offset
    }
}
