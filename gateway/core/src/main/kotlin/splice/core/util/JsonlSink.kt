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
import java.nio.file.StandardOpenOption

public object JsonlSink {
    /** Append [line] (a trailing newline is added) to [file], creating the file if absent. */
    public fun appendLine(file: Path, line: String) {
        Files.writeString(file, line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
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
}
