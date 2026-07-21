// NEW: append-only JSONL sink (#924 Phase 3). PerfStats and CompactStats both append one JSON line
// per record and tail-read a bounded trailing window; the append call and the tail-reader were
// duplicated — and readJsonlTail lived in :compact, so :perf reached across a package boundary for
// it (a layer smell). One home, in core.
package splice.core.util

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap

public object JsonlSink {
    private val locks = ConcurrentHashMap<Path, Any>()

    /** Append [line], rotating one generation before [maxBytes] can grow without bound. */
    public fun appendLine(file: Path, line: String, maxBytes: Long = DEFAULT_MAX_BYTES) {
        val normalized = file.toAbsolutePath().normalize()
        synchronized(locks.computeIfAbsent(normalized) { Any() }) {
            val encoded = (line + "\n").toByteArray(StandardCharsets.UTF_8)
            val currentSize = if (Files.exists(file)) Files.size(file) else 0L
            if (currentSize > 0 && currentSize + encoded.size > maxBytes) {
                val rolled = file.resolveSibling("${file.fileName}.1")
                Files.move(file, rolled, StandardCopyOption.REPLACE_EXISTING)
            }
            Files.write(file, encoded, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        }
    }

    /**
     * Read the trailing [maxBytes] of [file] as UTF-8 lines. If the file is larger, the first
     * (possibly partial) line in the window is dropped so every returned line is complete.
     */
    public fun readTail(file: Path, maxBytes: Int): List<String> =
        FileChannel.open(file, StandardOpenOption.READ).use { ch ->
            val size = ch.size()
            if (size <= 0L) return@use emptyList()
            val readFrom = (size - maxBytes.toLong()).coerceAtLeast(0L)
            val len = (size - readFrom).toInt()
            val buf = ByteBuffer.allocate(len)
            ch.position(readFrom)
            while (buf.hasRemaining()) {
                if (ch.read(buf) < 0) break
            }
            buf.flip()
            val text = StandardCharsets.UTF_8.decode(buf).toString()
            // Mid-file start: drop the leading partial line (no newline at all -> nothing complete).
            val complete = if (readFrom > 0L) text.substringAfter('\n', missingDelimiterValue = "") else text
            complete.lineSequence().filter { it.isNotEmpty() }.toList()
        }

    private const val DEFAULT_MAX_BYTES = 64L * 1024 * 1024
}
