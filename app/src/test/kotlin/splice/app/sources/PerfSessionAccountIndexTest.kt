package splice.app.sources

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.AsyncFileIo
import splice.core.util.JsonlAppendProof
import splice.core.util.JsonlSink
import java.nio.file.Files
import java.nio.file.Path

class PerfSessionAccountIndexTest {
    @TempDir
    lateinit var home: Path

    private fun row(ts: Long, session: String, account: String): String =
        """{"ts":$ts,"outcome":"ok","session_id":"$session","account":"$account"}"""

    @Test
    fun `restart seed selects newest known full session identity despite delayed append order`() {
        val file = home.resolve("perf.jsonl")
        Files.writeString(
            file,
            listOf(
                row(10, "shared-prefix-a", "older"),
                row(30, "shared-prefix-a", "newest"),
                row(20, "shared-prefix-a", "delayed"),
                row(40, "shared-prefix-b", "other"),
                row(50, "shared-prefix-a", "?"),
                row(60, "shared-prefix-a", "claude-code"),
                """{"ts":70,"session_id":"shared-prefix-a"}""",
                """{"ts":"80","session_id":"shared-prefix-a","account":"invalid"}""",
                """{"ts":90,"session":"shared-prefix","account":"short-tag"}""",
            ).joinToString("\n", postfix = "\n"),
        )
        val source = PerfRowsFileSource(file)
        assertEquals("newest", source.sessionAccount("shared-prefix-a"))
        assertEquals("other", source.sessionAccount("shared-prefix-b"))
        assertNull(source.sessionAccount("shared-prefix"))
        assertNull(source.sessionAccount("absent"))
        val decoded = source.parsedLines
        repeat(20) {
            assertEquals("newest", source.sessionAccount("shared-prefix-a"))
            assertNull(source.sessionAccount("absent"))
        }
        assertEquals(decoded, source.parsedLines, "unchanged listings never decode history again")
    }

    @Test
    fun `a requested older session is not reported unknown merely because newer sessions filled the index`() {
        val file = home.resolve("perf.jsonl")
        Files.newBufferedWriter(file).use { writer ->
            repeat(4_097) { i ->
                writer.append(row(i.toLong(), "session-$i", "account-$i")).append('\n')
            }
        }
        assertEquals("account-0", PerfRowsFileSource(file).sessionAccount("session-0"))
    }

    @Test
    fun `equal timestamp ties prefer the later row and the newer generation`() {
        val file = home.resolve("perf.jsonl")
        Files.writeString(file.resolveSibling("perf.jsonl.1"), row(10, "session", "old-generation") + "\n")
        Files.writeString(
            file,
            row(10, "session", "first-current") + "\n" + row(10, "session", "last-current") + "\n",
        )
        val source = PerfRowsFileSource(file)
        assertEquals("last-current", source.sessionAccount("session"))
        JsonlSink.appendLine(file, row(10, "session", "appended-current"))
        assertEquals("appended-current", source.sessionAccount("session"))
    }

    @Test
    fun `product appends index only the new span and retain a newer known identity`() {
        val file = home.resolve("perf.jsonl")
        repeat(200) { JsonlSink.appendLine(file, row(it.toLong(), "session", "initial")) }
        assertTrue(AsyncFileIo.awaitFile(file))
        val source = PerfRowsFileSource(file)
        assertEquals("initial", source.sessionAccount("session"))
        val seeded = source.parsedLines
        JsonlSink.appendLine(file, row(300, "session", "changed"))
        assertTrue(AsyncFileIo.awaitFile(file))
        assertEquals("changed", source.sessionAccount("session"))
        assertEquals(seeded + 1, source.parsedLines, "the product receipt authorizes only the new row")
        JsonlSink.appendLine(file, row(250, "session", "delayed"))
        assertTrue(AsyncFileIo.awaitFile(file))
        assertEquals("changed", source.sessionAccount("session"))
        assertEquals(seeded + 2, source.parsedLines)
    }

    @Test
    fun `same inode repair and rotation cannot lend a removed account or another head's rows`() {
        val file = home.resolve("perf.jsonl")
        Files.writeString(file, row(10, "session", "before") + "\n")
        val source = PerfRowsFileSource(file)
        assertEquals("before", source.sessionAccount("session"))
        val stamp = Files.getLastModifiedTime(file)
        Files.writeString(file, row(10, "session", "after!") + "\n")
        Files.setLastModifiedTime(file, stamp)
        assertEquals("after!", source.sessionAccount("session"), "stamp restoration is not an append proof")
        Files.move(file, file.resolveSibling("perf.jsonl.1"))
        Files.writeString(file, row(20, "session", "rotated") + "\n")
        assertEquals("rotated", source.sessionAccount("session"))
        val other = home.resolve("other-perf.jsonl")
        Files.writeString(other, row(30, "session", "different-head") + "\n")
        assertEquals("different-head", PerfRowsFileSource(other).sessionAccount("session"))
        assertEquals("rotated", source.sessionAccount("session"))
    }

    @Test
    fun `a changed partial tail does not preserve its formerly valid identity`() {
        val file = home.resolve("perf.jsonl")
        Files.writeString(file, row(10, "session", "complete") + "\n" + row(20, "session", "partial"))
        val source = PerfRowsFileSource(file)
        assertEquals("partial", source.sessionAccount("session"))
        Files.writeString(file, "garbage", java.nio.file.StandardOpenOption.APPEND)
        assertEquals("complete", source.sessionAccount("session"))
    }

    @Test
    fun `targeted listings decode history once per batch and no bytes for an identical listing`() {
        val file = home.resolve("perf.jsonl")
        val raw = (0..199).map { row(it.toLong(), "session-$it", "account-$it") }
        Files.writeString(file, raw.joinToString("\n", postfix = "\n"))
        val source = PerfRowsFileSource(file)
        val ids = setOf("session-0", "session-1", "session-199", "absent")
        val first = source.sessionAccounts(ids)
        assertEquals(
            mapOf("session-0" to "account-0", "session-1" to "account-1", "session-199" to "account-199"),
            first.accounts,
        )
        assertEquals(200L, source.parsedLines, "one scan for the entire batch, not one scan per requested session")
        val decoded = source.parsedLines
        repeat(10) { assertEquals(first, source.sessionAccounts(ids)) }
        assertEquals(decoded, source.parsedLines)
    }

    @Test
    fun `bounded newest-first seed distinguishes unread older history from complete absence`() {
        val archived = home.resolve("old.jsonl")
        val current = home.resolve("perf.jsonl")
        Files.writeString(archived, row(10, "older", "old-account") + "\n")
        Files.writeString(current, row(20, "recent", "recent-account") + "\n")
        val decoder = PerfRowDecode(PerfFieldNames(1024))
        var decodedBytes = 0L
        val decode = PerfLineDecode { raw ->
            decodedBytes += raw.toByteArray(Charsets.UTF_8).size
            val parsed = decoder.decode(raw).getOrThrow()
            PerfCachedLine(parsed.row, parsed.numericBytes, null, false, false, null, false)
        }
        val index = PerfSessionAccountIndex(scanBytes = Files.size(current), scanGenerations = 1)
        val ids = setOf("older", "recent", "absent")
        val snapshot = index.accounts(ids, listOf(archived), listOf(current), decode)
        assertEquals(mapOf("recent" to "recent-account"), snapshot.accounts)
        assertEquals(false, snapshot.complete)
        assertTrue(decodedBytes <= Files.size(current), "the admission ceiling is decoded source bytes")
        val before = decodedBytes
        assertEquals(snapshot, index.accounts(ids, listOf(archived), listOf(current), decode))
        assertEquals(before, decodedBytes, "even unresolved older sessions do not trigger a second seed")
        val full = PerfSessionAccountIndex().accounts(setOf("absent"), listOf(archived), listOf(current), decode)
        assertTrue(full.complete)
        assertTrue(full.accounts.isEmpty())
    }

    @Test
    fun `a clipped unchanged generation is reusable when the filesystem exposes no change time`() {
        val file = home.resolve("perf.jsonl")
        val first = row(10, "older", "old-account") + "\n"
        val second = row(20, "recent", "recent-account") + "\n"
        Files.writeString(file, first + second)
        val reader = PerfAccountRows(second.toByteArray(Charsets.UTF_8).size.toLong())
        val decoder = PerfRowDecode(PerfFieldNames(1024))
        val decode = PerfLineDecode { raw ->
            val parsed = decoder.decode(raw).getOrThrow()
            PerfCachedLine(parsed.row, parsed.numericBytes, null, false, false, null, false)
        }
        val version = JsonlAppendProof.version(file)
        val scanned = reader.scan(
            PerfAccountRows.Generation(file, version),
            null,
            first.toByteArray(Charsets.UTF_8).size.toLong(),
            decode,
        )
        val noChangeTime = scanned.copy(version = version.copy(changed = null))
        assertTrue(reader.appendable(noChangeTime, noChangeTime), "unchanged clipped history needs no JSON reseed")
        Files.writeString(file, first + second.replace("recent-account", "mutant-account"))
        assertEquals(
            false,
            reader.appendable(noChangeTime, noChangeTime),
            "a same-sized suffix repair still needs a byte proof when stamps cannot prove it",
        )
    }
}
