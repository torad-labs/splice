// NEW: V4-444 — a page read from the END of a transcript, so a session opens at its newest messages.
//
// The forward page walks from byte 0 and a long session is thousands of messages deep; the console opens
// it at the bottom, so this reads backwards in 64 KiB windows (no overlap, no full-file read) until the
// newest `limit` messages are whole, and hands back the byte offset the page before it ends at.
//
// A WINDOW THAT STARTS MID-LINE is cut at its first newline, and a window that starts mid-MESSAGE (an
// assistant reply is one line per block, several lines sharing one message id) never contributes its first
// message: the page needs one message before its kept ones, and takes a reply's whole group or none of it.
// The cursor is a byte offset at a record boundary, so the page before it reads exactly the lines before.
// The byte budget is the forward page's: a window that hits it returns what it has, its first message
// possibly short of its leading blocks.
//
// INDICES are where the message's record starts in the file (offset * PER_RECORD plus its place among the
// messages that record made), the same for every page that shows it, so two pages never repeat one.
// `skipped` counts what this window read past, not a page-exact denominator.
package splice.client.transcript

import splice.sessions.transcript.MAX_TRANSCRIPT_PAGE
import splice.sessions.transcript.TranscriptMessage
import splice.sessions.transcript.TranscriptPage
import java.nio.file.Path

// why: a page from the end reads the file backwards in windows this size, so a short tail never walks the whole file.
private const val BLOCK_BYTES = 64 shl 10

// why: the ceiling a forward page reads before it stops, so one page from the end costs no more than one from the start.
private const val MAX_BACK_BYTES = 16L shl 20

internal class TranscriptBackPage(
    private val opener: TranscriptOpener,
    private val parser: TranscriptLineParser,
    private val redaction: TranscriptRedaction,
) {
    /** The newest [limit] messages among the lines before byte [end], oldest first. */
    fun read(file: Path, sessionId: String, end: Long, limit: Int): TranscriptPage {
        val wanted = limit.coerceIn(1, MAX_TRANSCRIPT_PAGE)
        val tail = BackBuffer(opener, file, end)
        var tryAt = BLOCK_BYTES.toLong()
        var window: Window
        do {
            tail.fill(tryAt)
            tryAt *= 2
            window = assemble(tail.bytes(), tail.start)
        } while (!tail.spent && from(window.messages, wanted) < 1)
        val first = from(window.messages, wanted).coerceAtLeast(0)
        val kept = window.messages.drop(first)
        val earlier = earlier(kept, first, tail.start)
        return TranscriptPage(sessionId, file.toString(), kept, null, window.skipped, earlier)
    }

    /** The cursor of the page before: something lies before the page unless its window reached the file's start
     *  without a message ahead of it. */
    private fun earlier(kept: List<TranscriptMessage>, first: Int, start: Long): String? {
        val fileBefore = start > 0 && kept.isNotEmpty()
        return if (first > 0 || fileBefore) (kept.first().index / PER_RECORD).toString() else null
    }

    /** Where the newest [wanted] messages begin, moved back to the start of the reply group it would split. */
    private fun from(messages: List<TranscriptMessage>, wanted: Int): Int {
        var at = (messages.size - wanted).coerceAtLeast(0)
        while (at > 0 && splitsReply(messages, at)) at -= 1
        return at
    }

    /** Whether the message at [at] continues the reply the one before it began: one message id, several lines. */
    private fun splitsReply(messages: List<TranscriptMessage>, at: Int): Boolean {
        val id = messages[at].messageId ?: return false
        return messages[at - 1].messageId == id
    }

    private fun assemble(bytes: ByteArray, windowStart: Long): Window {
        var pos = 0
        if (windowStart > 0) {
            val newline = bytes.indexOf('\n'.code.toByte())
            if (newline < 0) return Window(emptyList(), emptyMap())
            pos = newline + 1
        }
        val assembly = PageAssembly(0, Int.MAX_VALUE, redaction, byOffset = true)
        while (pos < bytes.size) {
            var stop = pos
            while (stop < bytes.size && bytes[stop] != '\n'.code.toByte()) stop += 1
            assembly.at = windowStart + pos
            assembly.accept(parser.parse(bytes.copyOfRange(pos, stop)))
            pos = stop + 1
        }
        return Window(assembly.finish(), assembly.skipped.toMap())
    }

    private data class Window(val messages: List<TranscriptMessage>, val skipped: Map<String, Int>)
}

/** The file's bytes read backwards, one block at a time, up to [MAX_BACK_BYTES]: [start] is where they begin. */
private class BackBuffer(private val opener: TranscriptOpener, private val file: Path, end: Long) {
    private val chunks = ArrayDeque<ByteArray>()
    private var read = 0L

    var start = end
        private set

    /** Nothing more can be read: the file's start is buffered, or the byte budget is spent. */
    val spent: Boolean get() = start == 0L || read >= MAX_BACK_BYTES

    /** Reads blocks backwards until at least [upTo] bytes are buffered, or nothing more can be read. */
    fun fill(upTo: Long) {
        while (!spent && read < upTo) {
            val count = minOf(start, BLOCK_BYTES.toLong(), MAX_BACK_BYTES - read).toInt()
            start -= count
            val block = opener.open(file, start).use { it.readNBytes(count) }
            chunks.addFirst(block)
            read += block.size
        }
    }

    fun bytes(): ByteArray {
        val bytes = ByteArray(read.toInt())
        var at = 0
        for (chunk in chunks) {
            chunk.copyInto(bytes, at)
            at += chunk.size
        }
        return bytes
    }
}
