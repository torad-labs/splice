// NEW: Oct 7 CT — a transcript read one row at a time. The resume rewrite read each transcript whole, as
// text, several copies at once: a 715 MB transcript failed the launch it was resuming, because the JDK's
// UTF-8 encode of text past about 715M characters throws NegativeArraySizeException. A reader that hands
// out one row and forgets it holds the longest row, never the file.
//
// Beside TranscriptLineReader on purpose, and not a reuse of it: that reader drops the bytes of a row over
// TRANSCRIPT_LINE_BYTES, a contract its page and index readers share. This one keeps every row verbatim
// and returns the digest of the bytes it read, which the rewrite needs to prove nothing changed under it.
package splice.client.transcript

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

private const val NEWLINE: Byte = '\n'.code.toByte()

// why: bytes read per call, 64 KiB: a transcript of any size streams through one buffer this big, never whole
private const val ROW_SCAN_BLOCK_BYTES = 1 shl 16

// why: a row's first buffer, 4 KiB: a page, so an ordinary transcript row never has to grow it
private const val FIRST_ROW_BYTES = 1 shl 12

/** One row's bytes, valid only inside the callback that receives it: the buffer is reused for the next row. */
internal class TranscriptRow {
    var bytes: ByteArray = ByteArray(FIRST_ROW_BYTES)
        private set
    var length: Int = 0
        private set

    /** The row as text. Malformed UTF-8 throws, as reading the whole file as text did. */
    @Throws(CharacterCodingException::class)
    fun text(): String = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes, 0, length)).toString()

    fun append(source: ByteArray, from: Int, count: Int) {
        if (length + count > bytes.size) bytes = bytes.copyOf(maxOf(bytes.size * 2, length + count))
        System.arraycopy(source, from, bytes, length, count)
        length += count
    }

    fun clear() {
        length = 0
    }
}

/** Where a pass over a transcript hands each row, in order. The row is valid only inside the call. No index comes
 *  with it: a counter of rows wraps past 2^31, and a caller that frames rows by it would drop a separator there. */
internal fun interface RowReader {
    operator fun invoke(row: TranscriptRow)
}

/** A transcript's rows exactly as Claude Code wrote them: the bytes between newlines, in order, the one
 *  after the last newline included even when empty, so joining them with '\n' gives the file back. */
internal object TranscriptLines {

    /** Hands each row to [onRow] and returns the SHA-256 of every byte read. */
    fun read(file: Path, onRow: RowReader): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val row = TranscriptRow()
        Files.newInputStream(file).use { input ->
            chunks(input) { chunk, count ->
                digest.update(chunk, 0, count)
                var start = 0
                for (at in 0 until count) {
                    if (chunk[at] == NEWLINE) {
                        row.append(chunk, start, at - start)
                        onRow(row)
                        row.clear()
                        start = at + 1
                    }
                }
                row.append(chunk, start, count - start)
            }
        }
        onRow(row)
        return digest.digest()
    }

    /** The SHA-256 of [file]'s bytes, read in chunks. */
    fun digest(file: Path): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input -> chunks(input) { chunk, count -> digest.update(chunk, 0, count) } }
        return digest.digest()
    }

    private inline fun chunks(input: InputStream, onChunk: (ByteArray, Int) -> Unit) {
        val chunk = ByteArray(ROW_SCAN_BLOCK_BYTES)
        while (true) {
            val count = input.read(chunk)
            if (count < 0) return
            onChunk(chunk, count)
        }
    }
}
