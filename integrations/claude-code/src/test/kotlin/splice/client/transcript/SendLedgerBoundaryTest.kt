package splice.client.transcript

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

class SendLedgerBoundaryTest {
    @TempDir lateinit var root: Path

    private val file get() = root.resolve("projects/project/$SESSION.jsonl")
    private val counter = CountingOpener()
    private val reader = TranscriptReader(counter)

    private fun found(): Map<String, String> =
        reader.sentTexts(SESSION, listOf(root), setOf("toolu_a", "toolu_b")).texts

    @Test
    fun `a final valid line without newline remains visible and completes correctly on append`() {
        Files.createDirectories(file.parent)
        Files.writeString(file, SyntheticTranscript.handOff("toolu_a", "first"))
        assertEquals(mapOf("toolu_a" to "first"), found())
        counter.drain()
        assertEquals(mapOf("toolu_a" to "first"), found())
        assertEquals(0L, counter.drain())
        Files.writeString(
            file,
            "\n" + SyntheticTranscript.handOff("toolu_b", "second") + "\n",
            StandardOpenOption.APPEND,
        )
        assertEquals(mapOf("toolu_a" to "first", "toolu_b" to "second"), found())
        Files.writeString(file, "\n", StandardOpenOption.APPEND)
        assertEquals(mapOf("toolu_a" to "first", "toolu_b" to "second"), found())
    }

    @Test
    fun `a growing replacement with an identical old prefix is read from the start`() {
        Files.createDirectories(file.parent)
        val first = SyntheticTranscript.handOff("toolu_a", "first") + "\n"
        Files.writeString(file, first)
        found()
        counter.drain()
        val replacement = root.resolve("replacement")
        Files.writeString(replacement, first + SyntheticTranscript.handOff("toolu_b", "second") + "\n")
        Files.move(replacement, file, StandardCopyOption.REPLACE_EXISTING)
        assertEquals(mapOf("toolu_a" to "first", "toolu_b" to "second"), found())
        assertEquals(Files.size(file), counter.drain())
    }

    @Test
    fun `unicode-escaped tool names are indexed with the same redaction as literal names`() {
        Files.createDirectories(file.parent)
        val escaped = SyntheticTranscript.handOff("toolu_a", "token=abcdefgh12345678")
            .replace("SendMessage", "\\u0053endMessage")
        Files.writeString(file, escaped + "\n")
        assertEquals(mapOf("toolu_a" to "token=[redacted]"), found())
    }

    @Test
    fun `a caller asking for no hand-offs does not scan the transcript`() {
        Files.createDirectories(file.parent)
        Files.writeString(file, SyntheticTranscript.handOff("toolu_a", "first") + "\n")
        assertEquals(emptyMap<String, String>(), reader.sentTexts(SESSION, listOf(root), emptySet()).texts)
        assertEquals(0L, counter.drain())
    }

    @Test
    fun `a read failure does not publish a partially scanned index`() {
        Files.createDirectories(file.parent)
        Files.writeString(file, SyntheticTranscript.handOff("toolu_a", "first") + "\n")
        var fail = true
        val flaky = TranscriptReader { path, offset ->
            if (fail) throw java.io.IOException("synthetic open failure")
            counter.open(path, offset)
        }
        val error = org.junit.jupiter.api.assertThrows<java.io.IOException> {
            flaky.sentTexts(SESSION, listOf(root), setOf("toolu_a"))
        }
        assertTrue(error.message!!.contains("synthetic"))
        fail = false
        assertEquals("first", flaky.sentTexts(SESSION, listOf(root), setOf("toolu_a")).texts["toolu_a"])
    }
}
