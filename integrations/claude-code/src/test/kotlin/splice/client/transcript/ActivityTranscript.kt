// NEW: V4-444 — synthetic transcripts and a byte-counted positioned opener.
package splice.client.transcript

import java.io.InputStream
import java.nio.channels.Channels
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

internal const val ACTIVITY_ID = "synthetic-activity"

internal class ActivityTranscript(val root: Path) {
    val file: Path = root.resolve("projects/synthetic/$ACTIVITY_ID.jsonl")
    var bytes: Long = 0
        private set
    val offsets = mutableListOf<Long>()
    val reader = TranscriptReader(TranscriptOpener(::open))

    fun write(lines: List<String>) {
        Files.createDirectories(file.parent)
        Files.writeString(file, lines.joinToString("\n", postfix = "\n"))
    }

    fun append(line: String) {
        Files.writeString(file, line + "\n", StandardOpenOption.APPEND)
    }

    private fun open(path: Path, offset: Long): InputStream {
        offsets += offset
        val input = Channels.newInputStream(Files.newByteChannel(path, StandardOpenOption.READ).position(offset))
        return object : InputStream() {
            override fun read(): Int = input.read().also { if (it >= 0) bytes += 1 }
            override fun read(buffer: ByteArray, from: Int, length: Int): Int =
                input.read(buffer, from, length).also { if (it > 0) bytes += it }
            override fun close() = input.close()
        }
    }
}
