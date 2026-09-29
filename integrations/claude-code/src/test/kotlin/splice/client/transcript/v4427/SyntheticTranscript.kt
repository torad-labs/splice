// V4-427: a synthetic Claude Code transcript of tens of MB with a handful of SendMessage hand-offs, and an
// opener that counts the bytes a scan reads. Nothing here is a real session: ids, names and texts are made up.
package splice.client.transcript.v4427

import splice.client.transcript.TranscriptOpener
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong

internal const val SESSION = "aaaaaaaa-0000-4000-8000-000000000427"

private const val FILLER_CHARS = 1_900

/** Counts the bytes read (never the bytes skipped) from every stream it opens. */
internal class CountingOpener : TranscriptOpener {
    private val read = AtomicLong()

    /** Bytes read since the last call. */
    fun drain(): Long = read.getAndSet(0)

    override fun open(file: Path, offset: Long): InputStream = object : FilterInputStream(
        java.nio.channels.Channels.newInputStream(Files.newByteChannel(file).position(offset)),
    ) {
        override fun read(): Int = super.read().also { if (it >= 0) read.incrementAndGet() }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            super.read(buffer, offset, length).also { if (it > 0) read.addAndGet(it.toLong()) }
    }
}

internal object SyntheticTranscript {
    private val pad = "x".repeat(FILLER_CHARS)

    /** One assistant line carrying a single tool call. */
    fun call(tool: String, id: String, input: String): String =
        """{"type":"assistant","message":{"id":"m_$id","role":"assistant",""" +
            """"content":[{"type":"tool_use","id":"$id","name":"$tool","input":$input}]}}"""

    fun handOff(id: String, text: String): String = call("SendMessage", id, """{"to":"peer","message":"$text"}""")

    private fun filler(index: Int): String =
        """{"type":"user","message":{"role":"user","content":"line $index $pad"}}"""

    /** [lines] filler lines of about 2 KB each, with each of [at] replaced by the hand-off line at that index. */
    fun write(file: Path, lines: Int, at: Map<Int, String>) {
        Files.createDirectories(file.parent)
        Files.newBufferedWriter(file).use { out ->
            repeat(lines) {
                out.write(at[it] ?: filler(it))
                out.write("\n")
            }
        }
    }
}
