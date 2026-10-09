// V4-427: GET /api/sessions/{id}/edges re-read the sender's whole transcript on every request (52 s and 17 s
// on two live sessions). The hand-off texts are found once and held against the file's size, mtime and tail:
// a repeat call reads nothing, a grown file is read only past what was read, a shrunk or rewritten one from
// the start, and the texts stay redacted as team chat reads them.
package splice.client.transcript

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.sessions.transcript.SentTexts
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileTime

// why: about 2 KB a line, so this is a transcript of some 40 MB, the size a long session's grows to.
private const val BIG_LINES = 20_000

private const val SMALL_LINES = 4_000
private const val COLD_BUDGET_MS = 3_000L
private const val WARM_BUDGET_MS = 1_000L

class SendLedgerTest {
    @TempDir
    lateinit var home: Path

    private val counter = CountingOpener()
    private val reader = TranscriptReader(counter)
    private val roots get() = listOf(home.resolve(".claude"))
    private val file get() = home.resolve(".claude/projects/-w/$SESSION.jsonl")

    private fun texts(vararg ids: String): SentTexts = reader.sentTexts(SESSION, roots, ids.toSet())

    private fun timed(block: () -> SentTexts): Pair<SentTexts, Long> {
        val start = System.nanoTime()
        val result = block()
        return result to (System.nanoTime() - start) / 1_000_000
    }

    private val handOffs = mapOf(
        100 to SyntheticTranscript.handOff("toolu_h1", "first, token=abcdefgh12345678"),
        1_500 to SyntheticTranscript.handOff("toolu_h2", "second"),
        3_990 to SyntheticTranscript.handOff("toolu_h3", "third"),
    )

    @Test
    fun `a repeat call on a transcript of tens of MB reads no bytes and answers under a second`() {
        SyntheticTranscript.write(file, BIG_LINES, handOffs)
        assertTrue(Files.size(file) > 30L shl 20, "the fixture is tens of MB")

        val (first, coldMs) = timed { texts("toolu_h1", "toolu_h2", "toolu_h3") }
        assertEquals(
            mapOf("toolu_h1" to "first, token=[redacted]", "toolu_h2" to "second", "toolu_h3" to "third"),
            first.texts,
        )
        assertTrue(coldMs < COLD_BUDGET_MS, "the first read of ${Files.size(file)} bytes took $coldMs ms")
        counter.drain()

        val (second, warmMs) = timed { texts("toolu_h1", "toolu_h2", "toolu_h3") }
        assertEquals(first, second)
        assertEquals(0L, counter.drain(), "a warm call read transcript bytes")
        assertTrue(warmMs < WARM_BUDGET_MS, "the repeat call took $warmMs ms")
    }

    @Test
    fun `other ids on the same transcript are answered from what is held, and an id it lacks is missing`() {
        SyntheticTranscript.write(file, SMALL_LINES, handOffs)
        texts("toolu_h1")
        counter.drain()

        val other = texts("toolu_h3", "toolu_absent")
        assertEquals(mapOf("toolu_h3" to "third"), other.texts)
        assertEquals(setOf("toolu_absent"), other.missing)
        assertEquals(file.toString(), other.path)
        assertEquals(0L, counter.drain())
    }

    @Test
    fun `a hand-off appended to the file shows on the next call without the prefix being read again`() {
        SyntheticTranscript.write(file, BIG_LINES, handOffs)
        texts("toolu_h1")
        counter.drain()

        val added = SyntheticTranscript.handOff("toolu_h4", "fourth") + "\n"
        Files.writeString(file, added, StandardOpenOption.APPEND)
        val after = texts("toolu_h1", "toolu_h4")

        assertEquals(mapOf("toolu_h1" to "first, token=[redacted]", "toolu_h4" to "fourth"), after.texts)
        val read = counter.drain()
        assertTrue(read <= added.length + TAIL_GUARD_BYTES, "read $read bytes for an append of ${added.length}")
    }

    @Test
    fun `a shrunk file is read again from the start, and a hand-off it no longer holds is not served`() {
        SyntheticTranscript.write(file, SMALL_LINES, handOffs)
        texts("toolu_h3")
        counter.drain()

        SyntheticTranscript.write(file, 1_000, handOffs.filterKeys { it < 1_000 })
        val after = texts("toolu_h1", "toolu_h2", "toolu_h3")

        assertEquals(setOf("toolu_h1"), after.texts.keys)
        assertEquals(setOf("toolu_h2", "toolu_h3"), after.missing)
        assertEquals(Files.size(file), counter.drain())
    }

    @Test
    fun `a rewritten file that is longer than before is read again from the start`() {
        SyntheticTranscript.write(file, SMALL_LINES, handOffs)
        texts("toolu_h1")
        counter.drain()

        SyntheticTranscript.write(file, SMALL_LINES + 500, mapOf(50 to SyntheticTranscript.handOff("toolu_h1", "new")))
        val after = texts("toolu_h1", "toolu_h2")

        assertEquals(mapOf("toolu_h1" to "new"), after.texts)
        val read = counter.drain()
        assertTrue(read in Files.size(file)..Files.size(file) + TAIL_GUARD_BYTES)
    }

    @Test
    fun `a call whose line is still being written shows once the line is whole, and only once`() {
        SyntheticTranscript.write(file, 200, emptyMap())
        val line = SyntheticTranscript.handOff("toolu_h5", "fifth")
        Files.writeString(file, line.take(40), StandardOpenOption.APPEND)
        assertEquals(emptySet<String>(), texts("toolu_h5").texts.keys)

        Files.writeString(file, line.drop(40) + "\n", StandardOpenOption.APPEND)
        assertEquals(mapOf("toolu_h5" to "fifth"), texts("toolu_h5").texts)
    }

    @Test
    fun `a same-size file whose mtime changed is read from the start`() {
        SyntheticTranscript.write(file, SMALL_LINES, handOffs)
        texts("toolu_h1")
        counter.drain()

        Files.setLastModifiedTime(file, FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis() + 5_000))
        assertEquals("second", texts("toolu_h2").texts["toolu_h2"])
        assertEquals(Files.size(file), counter.drain())
    }

    @Test
    fun `no transcript in any tree reports every id missing with the dirs searched`() {
        val none = texts("toolu_h1")
        assertEquals(
            SentTexts(null, emptyMap(), setOf("toolu_h1"), roots.map { it.resolve("projects").toString() }),
            none,
        )
    }

    private companion object {
        // why: the guard re-reads this many bytes before the held offset to prove the prefix is the same file.
        const val TAIL_GUARD_BYTES = 4_096
    }
}
