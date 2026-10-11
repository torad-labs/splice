// The edge store never returns a stale row: appends, rotation, torn tails, expiry and replaced files all read back true.
package splice.sessions.activity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.memory.HeapReservations
import splice.core.storage.ActivityDays
import splice.core.storage.DayFiles
import splice.core.util.AsyncFileIo
import splice.core.util.WallClock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND

private const val CACHE_DAY = 1_789_725_600_000L

class MessageEdgeStoreCacheTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `append and rotation preserve oldest occurrence and parse only new lines`() {
        val codec = CountingCodec()
        val store = store(codec)
        val path = dir.resolve("edges-2026-09-18.jsonl")
        assertTrue(AsyncFileIo.drain())
        Files.writeString(path, row("a") + row("b") + row("a", "retry") + "foreign\n")
        assertEquals(listOf("a", "b"), store.edges().map { it.id })
        assertEquals("recipient", store.edges().first().to)
        assertEquals(4, codec.parses)
        Files.move(path, path.resolveSibling("${path.fileName}.1"))
        Files.writeString(path, row("a", "restart") + row("c"))
        assertEquals(listOf("a", "b", "c"), store.edges().map { it.id })
        assertEquals("recipient", store.edges().first().to)
        assertEquals(6, codec.parses, "the moved inode is not parsed again")
        Files.writeString(path, row("d"), APPEND)
        assertEquals(listOf("a", "b", "c", "d"), store.edges().map { it.id })
        assertEquals(7, codec.parses)
        val restarted = store(CountingCodec())
        assertEquals(store.edges(), restarted.edges())
    }

    @Test
    fun `unchanged torn tails are reused and newline settlement parses nothing again`() {
        val codec = CountingCodec()
        val store = store(codec)
        val path = dir.resolve("edges-2026-09-18.jsonl")
        assertTrue(AsyncFileIo.drain())
        Files.writeString(path, row("a").trimEnd())
        assertEquals(listOf("a"), store.edges().map { it.id })
        assertEquals(store.edges(), store.edges())
        assertEquals(1, codec.parses)
        Files.writeString(path, "\n", APPEND)
        assertEquals(listOf("a"), store.edges().map { it.id })
        assertEquals(1, codec.parses)
        Files.writeString(path, "{", APPEND)
        assertEquals(listOf("a"), store.edges().map { it.id })
        assertEquals(2, codec.parses)
        Files.writeString(path, "\n" + row("b").trimEnd() + "\r", APPEND)
        assertEquals(listOf("a", "b"), store.edges().map { it.id })
        assertEquals(3, codec.parses, "settling a foreign tail must not parse it again")
        Files.writeString(path, "\n", APPEND)
        assertEquals(listOf("a", "b"), store.edges().map { it.id })
        assertEquals(3, codec.parses, "a CRLF joining the tail changes no metadata")
    }

    @Test
    fun `expiry before sweep and deletion remove cached edges`() {
        var now = CACHE_DAY
        val codec = CountingCodec()
        val store = store(codec, clock = WallClock { now })
        store.record(MessageEdge("sender", "recipient", now, "a"))
        assertTrue(AsyncFileIo.drain())
        assertEquals(1, store.edges().size)
        now += 2 * 86_400_000L
        assertEquals(emptyList<MessageEdge>(), store.edges())
        assertEquals(1, codec.parses)
        store.record(MessageEdge("sender", "recipient", now, "b"))
        assertTrue(AsyncFileIo.drain())
        assertEquals(listOf("b"), store.edges().map { it.id })
        store.deleteKept()
        assertEquals(emptyList<MessageEdge>(), store.edges())
    }

    @Test
    fun `same-sized replacement and truncation never return cached rows`() {
        val codec = CountingCodec()
        val store = store(codec)
        val path = dir.resolve("edges-2026-09-18.jsonl")
        assertTrue(AsyncFileIo.drain())
        Files.writeString(path, row("a") + row("b"))
        assertEquals(listOf("a", "b"), store.edges().map { it.id })
        val replacement = dir.resolve("replacement")
        Files.writeString(replacement, row("c") + row("d"))
        Files.move(replacement, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        assertEquals(listOf("c", "d"), store.edges().map { it.id })
        Files.writeString(path, row("e"))
        assertEquals(listOf("e"), store.edges().map { it.id })
        assertEquals(5, codec.parses)
    }

    @Test
    fun `a span reads and keeps only the days written inside it, and the whole window comes back whole`() {
        val store = store(CountingCodec(), clock = WallClock { CACHE_DAY + 86_400_000L })
        assertTrue(AsyncFileIo.drain())
        val older = dir.resolve("edges-2026-09-18.jsonl")
        val newer = dir.resolve("edges-2026-09-19.jsonl")
        Files.writeString(older, row("old"))
        Files.writeString(newer, row("new"))
        Files.setLastModifiedTime(older, java.nio.file.attribute.FileTime.fromMillis(CACHE_DAY))
        Files.setLastModifiedTime(newer, java.nio.file.attribute.FileTime.fromMillis(CACHE_DAY + 86_400_000L))
        assertEquals(listOf("new"), store.edges(CACHE_DAY + 1).map { it.id })
        assertEquals(listOf("old", "new"), store.edges().map { it.id })
        assertEquals(listOf("new"), store.edges(CACHE_DAY + 1).map { it.id }, "narrowing again drops the older day")
    }

    private fun row(id: String, to: String = "recipient"): String =
        """{"from":"sender","to":"$to","at":$CACHE_DAY,"id":"$id"}""" + "\n"

    private fun store(
        codec: CountingCodec,
        clock: WallClock = WallClock { CACHE_DAY },
        heap: HeapReservations? = null,
        maxBytes: Long = EDGE_CACHE_BYTES,
    ): MessageEdgeStore = MessageEdgeStore(
        ActivityDays(dir, EDGES_PREFIX, 2, clock),
        DayFiles(dir, EDGES_PREFIX),
        2,
        true,
        codec,
        heap,
        maxBytes,
    )

    private class CountingCodec : MessageEdgeDecode {
        private val codec = MessageEdgeCodec()
        var parses = 0
        override fun parse(line: String): MessageEdge? {
            parses++
            return codec.parse(line)
        }
    }
}
