// NEW: Oct 25, 2026 (memory and CPU pass) — a refresh over history files that grew lists what a fresh scan of the same
// files lists, and a history too large for the cache to keep still lists every session in the order of its file.
// Oct 25, 2026, after review: a refresh reads only the lines appended since the last one, however many prompts the
// history holds, so blanking out what it already read leaves its listing as the history was written.
package splice.client.transcript

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
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
import kotlin.random.Random

class TranscriptHistoryReplayTest {
    @TempDir lateinit var home: Path

    private var now = 1_000_000L
    private val clock = WallClock { now }
    private val random = Random(SEED)
    private val history = GeneratedHistory(random)

    @Test
    fun `a refresh after the history grows lists what a fresh scan of the same files lists`() = repeat(CASES) { case ->
        val roots = List(random.nextInt(1, MAX_ROOTS + 1)) { SessionHistoryRoot(HEADS.random(random), dir(case, it)) }
        val ids = List(random.nextInt(1, MAX_IDS + 1), ::sessionId)
        roots.forEach { append(it, history.rows(ids, random.nextInt(0, MAX_FIRST_ROWS + 1))) }
        val index = TranscriptHistoryIndex(clock)
        assertListsWhatAFreshScanLists(index, roots, "case $case, first scan")
        repeat(random.nextInt(1, MAX_ROUNDS + 1)) { round ->
            append(roots.random(random), history.rows(ids, random.nextInt(1, MAX_APPENDED_ROWS + 1)))
            assertListsWhatAFreshScanLists(index, roots, "case $case, round $round")
        }
    }

    @Test
    fun `a history of many prompts from a few sessions lists what a fresh scan lists after it grows`() {
        val roots = listOf(SessionHistoryRoot(null, dir(0, 0)))
        val ids = List(BUSY_IDS, ::sessionId)
        append(roots.single(), history.rows(ids, BUSY_ROWS))
        val listed = freshScan(roots).sessions
        assertEquals(BUSY_IDS, listed.size, "every session the rows name is listed")
        assertTrue(listed.all { it.name != null && it.project != null }, "with the title and project its rows gave it")
        val index = TranscriptHistoryIndex(clock)
        assertListsWhatAFreshScanLists(index, roots, "first scan")
        repeat(BUSY_ROUNDS) { round ->
            append(roots.single(), history.rows(ids, random.nextInt(1, MAX_APPENDED_ROWS + 1)))
            assertListsWhatAFreshScanLists(index, roots, "round $round")
        }
    }

    @Test
    fun `a history that grows past what the cache keeps still lists every session`() {
        val root = SessionHistoryRoot(null, dir(0, 0))
        val index = TranscriptHistoryIndex(clock)

        append(root, prompts(0 until KEPT_SESSIONS))
        assertListed(sessions(0 until KEPT_SESSIONS), refresh(index, listOf(root)))

        append(root, prompts(KEPT_SESSIONS until PAST_THE_BOUND))
        assertListed(sessions(0 until PAST_THE_BOUND), refresh(index, listOf(root)))

        append(root, prompts(PAST_THE_BOUND..PAST_THE_BOUND))
        assertListed(sessions(0..PAST_THE_BOUND), refresh(index, listOf(root)))
    }

    /** Each round blanks out every history a refresh has read, then appends to one. The refresh must list what a
     *  fresh scan of the same rows, as Claude Code wrote them, lists. One that read a line again would lose its row. */
    @Test
    fun `a refresh reads only the lines appended since the last one`() = repeat(CASES) { case ->
        val heads = List(random.nextInt(1, MAX_ROOTS + 1)) { HEADS.random(random) }
        val roots = heads.mapIndexed { at, head -> SessionHistoryRoot(head, dir(case, at)) }
        val written = roots.map { SessionHistoryRoot(it.head, it.dir.resolveSibling("as-written-${it.dir.fileName}")) }
        val ids = List(random.nextInt(1, MAX_IDS + 1), ::sessionId)
        roots.indices.forEach { at ->
            val rows = history.rows(ids, random.nextInt(0, MAX_FIRST_ROWS + 1))
            listOf(roots[at], written[at]).forEach { append(it, rows) }
        }
        val index = TranscriptHistoryIndex(clock)
        assertEquals(freshScan(written), refresh(index, roots), "case $case, first scan")
        repeat(random.nextInt(1, MAX_ROUNDS + 1)) { round ->
            roots.forEach(::blank)
            val at = random.nextInt(roots.size)
            val rows = history.rows(ids, random.nextInt(1, MAX_APPENDED_ROWS + 1))
            listOf(roots[at], written[at]).forEach { append(it, rows) }
            assertEquals(freshScan(written), refresh(index, roots), "case $case, round $round")
        }
    }

    /** The history ends at 65,536 sessions, the most rows the cache keeps, and 70,536 prompts, more than it could
     *  keep one by one: 25 sessions are busy with 200 more prompts each. On Oct 25, 2026 ~/.claude/history.jsonl
     *  held about 41,700 prompts for 1,684 sessions, and the cache dropped its cursor past 4,096 rows. */
    @Test
    fun `a refresh reads only the lines appended however many prompts the history holds`() {
        val root = SessionHistoryRoot(null, dir(0, 0))
        val index = TranscriptHistoryIndex(clock)
        val newest = MOST_KEPT_ROWS - 1
        append(root, prompts(0 until newest) + prompts(0 until BUSY_IDS).repeat(BUSY_PROMPTS_EACH))
        assertListed(sessions(0 until newest), refresh(index, listOf(root)))

        blank(root)
        append(root, prompts(newest..newest))

        assertListed(sessions(0..newest), refresh(index, listOf(root)))
    }

    /** Overwrites every byte of the root's history with a space, keeping its newlines, its length and its stamp: a
     *  refresh has no reason to read it again, and one that did would find no row in it. */
    private fun blank(root: SessionHistoryRoot) {
        val file = root.dir.resolve("history.jsonl")
        if (!Files.exists(file)) return
        val stamp = Files.getLastModifiedTime(file)
        val bytes = Files.readAllBytes(file)
        val newline = '\n'.code.toByte()
        Files.write(file, ByteArray(bytes.size) { if (bytes[it] == newline) newline else ' '.code.toByte() })
        Files.setLastModifiedTime(file, stamp)
    }

    private fun refresh(index: TranscriptHistoryIndex, roots: List<SessionHistoryRoot>): SessionHistoryScan {
        now += PAST_THE_CACHE_MS
        return index.scan(roots)
    }

    private fun freshScan(roots: List<SessionHistoryRoot>): SessionHistoryScan =
        refresh(TranscriptHistoryIndex(clock), roots)

    private fun assertListsWhatAFreshScanLists(
        index: TranscriptHistoryIndex,
        roots: List<SessionHistoryRoot>,
        at: String,
    ) {
        assertEquals(freshScan(roots), refresh(index, roots), at)
    }

    private fun dir(case: Int, root: Int): Path = home.resolve("case-$case").resolve("root-$root")

    private fun append(root: SessionHistoryRoot, text: String) {
        if (text.isEmpty()) return
        Files.createDirectories(root.dir)
        Files.writeString(root.dir.resolve("history.jsonl"), text, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }

    private fun prompts(numbers: IntRange): String = numbers.joinToString("") { number ->
        """{"sessionId":"${sessionId(number)}","display":"prompt $number","project":"$PROJECT","timestamp":$number}""" +
            "\n"
    }

    private fun sessions(numbers: IntRange): List<SessionHistoryEntry> = numbers.map { number ->
        SessionHistoryEntry(
            sessionId = sessionId(number),
            name = "prompt $number",
            project = PROJECT,
            head = null,
            updatedAt = number.toLong(),
            files = SessionFiles(hasHistory = true, hasTranscript = false),
        )
    }

    /** Compares entry by entry, so a miss names the session and does not print both lists of sixty thousand. */
    private fun assertListed(expected: List<SessionHistoryEntry>, scan: SessionHistoryScan) {
        assertEquals(emptyList<String>(), scan.errors)
        assertEquals(expected.size, scan.sessions.size, "sessions listed")
        val wrong = expected.indices.firstOrNull { expected[it] != scan.sessions[it] }
        assertEquals(null, wrong?.let { "session $it: expected ${expected[it]}, listed ${scan.sessions[it]}" })
    }
}

/** History rows as Claude Code writes them, built by hand: each draws what the real file repeats or leaves out. */
private class GeneratedHistory(private val random: Random) {
    private var serial = 0

    fun rows(ids: List<String>, count: Int): String = (1..count).joinToString("") { row(ids) }

    private fun row(ids: List<String>): String {
        serial++
        if (random.nextInt(JUNK_ONE_IN) == 0) return JUNK.random(random)
        val fields = mutableListOf("\"sessionId\":\"${ids.random(random)}\"")
        if (random.nextInt(NO_TIMESTAMP_ONE_IN) != 0) fields += "\"timestamp\":${random.nextInt(TIMESTAMPS)}"
        display()?.let { fields += "\"display\":\"$it\"" }
        project()?.let { fields += "\"project\":\"$it\"" }
        return fields.joinToString(",", "{", "}\n")
    }

    /** Missing, empty, blank on its first line, one line, or several; each names itself, so a wrong title shows. */
    private fun display(): String? = when (random.nextInt(DISPLAY_KINDS)) {
        0 -> null
        1 -> ""
        2 -> "   \\nbehind a blank line $serial"
        3 -> "prompt $serial"
        else -> "first line $serial\\nsecond line"
    }

    /** Missing, blank, or set; each names itself too. */
    private fun project(): String? = when (random.nextInt(PROJECT_KINDS)) {
        0 -> null
        1 -> "   "
        else -> "/work/project-$serial"
    }
}

private fun sessionId(number: Int): String = "3f2a9c1e-0000-4000-8000-" + number.toString().padStart(ID_DIGITS, '0')

private val HEADS = listOf<String?>(null, "alpha", "beta")
private val JUNK = listOf(
    "{not json\n",
    "{\"display\":\"a prompt that names no session\",\"timestamp\":3}\n",
    "{\"sessionId\":\"not an id\",\"display\":\"a session id that cannot be one\",\"timestamp\":4}\n",
)

private const val PROJECT = "/work/atlas"
private const val ID_DIGITS = 12
private const val SEED = 20261025
private const val CASES = 300
private const val MAX_ROOTS = 3
private const val MAX_IDS = 5
private const val MAX_FIRST_ROWS = 8
private const val MAX_ROUNDS = 4
private const val MAX_APPENDED_ROWS = 6
private const val TIMESTAMPS = 13
private const val NO_TIMESTAMP_ONE_IN = 7
private const val JUNK_ONE_IN = 20
private const val DISPLAY_KINDS = 5
private const val PROJECT_KINDS = 4
private const val BUSY_IDS = 25
private const val BUSY_ROWS = 5_000
private const val BUSY_ROUNDS = 5
private const val KEPT_SESSIONS = 65_000
private const val PAST_THE_BOUND = 66_000
private const val MOST_KEPT_ROWS = 65_536
private const val BUSY_PROMPTS_EACH = 200
private const val PAST_THE_CACHE_MS = 11_000L
