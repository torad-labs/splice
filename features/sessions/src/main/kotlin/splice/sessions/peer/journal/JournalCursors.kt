// NEW: Oct 19, 2026 (docs/specs/peer-messaging.md 2): the two cursors beside a peer's journal. read.cursor is the
// last seq the reader acknowledged, written by the daemon; webhook.cursor is the last seq the webhook finished,
// written by the helper. Each file holds one decimal number.
package splice.sessions.peer.journal

import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

private const val PEER_READ_CURSOR_FILE = "read.cursor"
private const val PEER_WEBHOOK_CURSOR_FILE = "webhook.cursor"

internal class JournalCursors(dir: Path, private val files: PeerJournalFiles) {
    private val readFile: Path = dir.resolve(PEER_READ_CURSOR_FILE)
    private val webhookFile: Path = dir.resolve(PEER_WEBHOOK_CURSOR_FILE)

    /** The last seq the reader acknowledged, or 0. */
    fun read(): Long = numberIn(readFile) ?: 0L

    fun advanceRead(to: Long) {
        files.writeNumber(readFile, to)
    }

    /** The last seq the webhook finished, or null when it never pushed or its cursor was reset. */
    fun webhook(): Long? = numberIn(webhookFile)

    fun advanceWebhook(to: Long) {
        files.writeNumber(webhookFile, to)
    }

    /** Forgets the webhook's place; true when there was one. */
    fun resetWebhook(): Boolean = Files.deleteIfExists(webhookFile)

    private fun numberIn(file: Path): Long? = try {
        Files.readString(file).trim().toLongOrNull()
    } catch (_: NoSuchFileException) {
        null
    }
}
