// NEW: Oct 26, 2026, after review of the history scan: the cache keeps at most 65,536 rows of history, and a history
// folded to exactly that many keeps its cursor like any smaller one, so each refresh reads only the lines Claude Code
// appended since the last. TranscriptHistoryReplayTest stops one row below the bound, or reaches it with no refresh
// after it, so a cache that dropped its file at 65,536 rows passed every test. Each test here meets the bound and then
// refreshes once more, over bytes that were overwritten after the last refresh read them.
package splice.client.transcript

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.WallClock
import splice.sessions.transcript.SessionFiles
import splice.sessions.transcript.SessionHistoryEntry
import splice.sessions.transcript.SessionHistoryRoot
import splice.sessions.transcript.SessionHistoryScan
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

class TranscriptHistoryRowBoundTest {
    @TempDir lateinit var home: Path

    private var now = 1_000_000L
    private val index = TranscriptHistoryIndex(WallClock { now })

    /** One prompt for each of 65,536 sessions: the first scan folds to exactly the most rows the cache keeps. */
    @Test
    fun `a history folded to exactly the most rows the cache keeps is read on from where it ended`() {
        append(prompts(0 until MOST_KEPT_ROWS))
        assertListed(sessions(0 until MOST_KEPT_ROWS), refresh())

        assertRenamedWithoutReadingAgain(sessions(0 until MOST_KEPT_ROWS))
    }

    /** A history of 65,535 sessions gains its 65,536th: the refresh that replays the kept rows and reads the new line
     *  brings the cache to exactly the most rows it keeps. */
    @Test
    fun `a history that grows to exactly the most rows the cache keeps is read on from where it ended`() {
        append(prompts(0 until MOST_KEPT_ROWS - 1))
        assertListed(sessions(0 until MOST_KEPT_ROWS - 1), refresh())

        overwriteWhatWasRead()
        append(prompts(MOST_KEPT_ROWS - 1 until MOST_KEPT_ROWS))
        assertListed(sessions(0 until MOST_KEPT_ROWS), refresh())

        assertRenamedWithoutReadingAgain(sessions(0 until MOST_KEPT_ROWS))
    }

    /** A newer prompt of an existing session, with a title of its own, keeps the kept rows at the most the cache keeps.
     *  The refresh must list that session renamed, in its old place, and no session Claude Code never wrote: the lines
     *  it read before are overwritten with rows for one, so a refresh that read them again would list it. */
    private fun assertRenamedWithoutReadingAgain(before: List<SessionHistoryEntry>) {
        overwriteWhatWasRead()
        append(row(RENAMED_SESSION, RENAMED_TITLE, RENAMED_AT))

        val renamed = before.mapIndexed { at, entry ->
            if (at == RENAMED_SESSION) session(at, RENAMED_TITLE, RENAMED_AT) else entry
        }
        assertListed(renamed, refresh())
    }

    /** Overwrites each line of the history with a row of the same length for a session Claude Code never wrote,
     *  keeping the file's newlines, its length and its stamp. A refresh has no reason to read these bytes again, and
     *  one that did would list that session. */
    private fun overwriteWhatWasRead() {
        val file = home.resolve(HISTORY_FILE)
        val stamp = Files.getLastModifiedTime(file)
        val lines = Files.readString(file).split('\n').map { line ->
            val room = line.encodeToByteArray().size - READ_AGAIN_ROW.length
            if (room < 0) line else READ_AGAIN_ROW.dropLast(1) + " ".repeat(room) + "}"
        }
        Files.writeString(file, lines.joinToString("\n"))
        Files.setLastModifiedTime(file, stamp)
    }

    private fun refresh(): SessionHistoryScan {
        now += PAST_THE_CACHE_MS
        return index.scan(listOf(SessionHistoryRoot(null, home)))
    }

    private fun append(text: String) {
        Files.writeString(home.resolve(HISTORY_FILE), text, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }

    private fun row(number: Int, title: String, at: Int): String =
        """{"sessionId":"${sessionId(number)}","display":"$title","project":"$PROJECT","timestamp":$at}""" + "\n"

    private fun prompts(numbers: IntRange): String = numbers.joinToString("") { row(it, "prompt $it", it) }

    private fun session(number: Int, title: String, at: Int): SessionHistoryEntry = SessionHistoryEntry(
        sessionId = sessionId(number),
        name = title,
        project = PROJECT,
        head = null,
        updatedAt = at.toLong(),
        files = SessionFiles(hasHistory = true, hasTranscript = false),
    )

    private fun sessions(numbers: IntRange): List<SessionHistoryEntry> = numbers.map { session(it, "prompt $it", it) }

    /** Compares entry by entry, so a miss names the session and does not print both lists of sixty thousand. */
    private fun assertListed(expected: List<SessionHistoryEntry>, scan: SessionHistoryScan) {
        assertEquals(emptyList<String>(), scan.errors)
        assertEquals(expected.size, scan.sessions.size, "sessions listed")
        val wrong = expected.indices.firstOrNull { expected[it] != scan.sessions[it] }
        assertEquals(null, wrong?.let { "session $it: expected ${expected[it]}, listed ${scan.sessions[it]}" })
    }
}

private fun sessionId(number: Int): String = "3f2a9c1e-0000-4000-8000-" + number.toString().padStart(ID_DIGITS, '0')

/** A row for a session Claude Code never wrote, padded with spaces before its brace to fill a line already read. */
private const val READ_AGAIN_ROW = """{"sessionId":"read-again"}"""

private const val HISTORY_FILE = "history.jsonl"
private const val PROJECT = "/work/atlas"
private const val ID_DIGITS = 12

// why: the most rows the history cache keeps across its files, HistoryRowsCache's own bound
private const val MOST_KEPT_ROWS = 65_536

// why: past the scan's 10 s cache, so each refresh reads the files and does not return the last scan
private const val PAST_THE_CACHE_MS = 11_000L

// why: a session well inside the list, so a refresh that read from byte 0 would list it after the one it invented; the
// rename's stamp is newer than every prompt, so the session takes it
private const val RENAMED_SESSION = 40_000
private const val RENAMED_TITLE = "renamed"
private const val RENAMED_AT = 70_000
