// NEW: V4-343 — the trace count reads each record's stamp and never decodes its body, and counts exactly what
// the full decode counted. The store holds turns the daemon's own TraceStore wrote, then every kind of line
// the two reads could part on: records torn after their leading fields (a disk-full cut of a 2-3 MB record
// almost always lands in its body), a torn character, foreign kinds, keys reordered, escaped or repeated,
// malformed and lenient values, stray bytes, and a last line with no newline, whole or torn. The reference
// is today's decode, copied here; the listings are pinned by their digest on today's tree.
package splice.head.trace.v4343

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.perf.PerfSnapshot
import splice.core.storage.ActivityDays
import splice.core.storage.DayFiles
import splice.core.turn.ReasoningDisplay
import splice.core.turn.TurnMeta
import splice.core.util.AsyncFileIo
import splice.core.util.JsonScalars
import splice.core.util.WallClock
import splice.head.trace.TraceAsk
import splice.head.trace.TraceRows
import splice.head.trace.TracedTurn
import splice.head.wire.ClientInbound
import splice.head.wire.TraceKinds
import splice.head.wire.TraceStore
import splice.head.wire.TurnIdMint
import splice.head.wire.TurnTrace
import splice.upstream.sse.WireAttempt
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

private const val HEAD = "openrouter"
private const val DAY_ONE = 1_789_725_600_000L // 2026-09-18T10:00Z
private const val DAY_MS = 86_400_000L
private const val SECOND = 1_000L

// why: a request body of a few kilobytes, so a record cut at three fifths is cut inside its body, as a
// disk-full append of a 2-3 MB record is
private const val BODY_CHARS = 4096

// why: more turns than the store holds, so the listing is every turn on disk
private const val LIST_ALL = 100

// why: the newest-turns read (`--last 3 --json`), which stops once it holds them
private const val NEWEST = 3

// One backslash, spelled out so the key written with a \u escape for its "i" reaches the file escaped.
private const val BACKSLASH = '\\'

// Today's listings of the store below, as SHA-256 over their records, on_disk and skipped lines: taken with
// V4-338's reader (61af56339, installed at 9fcad1da8), which decoded every line whole.
private const val TODAY_ALL = "af05e31ac84606910ede5b17c37b0be7373c0fa44dee46432648c5c01fc96f1c"
private const val TODAY_NEWEST = "6a487613e7f1afd431200e0988de6db77bd6bdd6bdec846b06daf8d763d4ab9a"

/** The fields today's reader decoded off every line, whole: a line is a record when this decodes. */
@Serializable
private data class FullStamp(
    val kind: JsonElement? = null,
    val turn: JsonElement? = null,
    val ts: JsonElement? = null,
    val session: JsonElement? = null,
    val attempt: JsonElement? = null,
    val attempts: JsonElement? = null,
)

class TraceCensusTest {

    private var now = DAY_ONE
    private val ids = ArrayDeque<String>()
    private val rows = TraceRows()
    private val json = Json { ignoreUnknownKeys = true }

    private fun traceStore(dir: Path) = splice.head.syntheticTraceStore(
        ActivityDays(dir, HEAD, 30, WallClock { now }, true),
        HEAD,
        maxBodyChars = 1 shl 20,
        now = WallClock { now },
        ids = TurnIdMint { ids.removeFirst() },
    )

    private fun meta() = TurnMeta(
        compact = false,
        showReasoning = ReasoningDisplay.TEXT,
        stream = true,
        originalModel = "claude-openrouter--m1",
        upstreamModel = "m1",
        clientMaxTokens = 8000,
        effort = "medium",
        summary = null,
        budgetTokens = null,
        sessionId = "alpha-session",
    )

    private fun TraceStore.turn(id: String): TurnTrace {
        ids += id
        return begin(meta(), ClientInbound("POST", "/v1/messages", emptyMap(), """{"turn":"$id"}"""))
    }

    /** One upstream send of this turn, a second after the last record, its body a few kilobytes. */
    private fun TurnTrace.send(n: Int = 1) {
        now += SECOND
        val body = """{"input":"${"x".repeat(BODY_CHARS)}"}"""
        val url = "https://openrouter.ai/api/v1"
        attempted(WireAttempt(n, url, emptyMap(), body, null, 200, emptyMap(), null, null, 40))
    }

    private fun TurnTrace.end() {
        now += SECOND
        finish("ok", PerfSnapshot(mapOf("total" to 120L), emptyMap()))
    }

    private fun TraceStore.done(id: String, sends: Int = 1) {
        val trace = turn(id)
        (1..sends).forEach { trace.send(it) }
        trace.end()
    }

    private fun drained() = assertEquals(true, AsyncFileIo.drain(), "the file lane drained")

    private fun dayOne(dir: Path): Path = dir.resolve("$HEAD-2026-09-18.jsonl")

    /** The first three fifths of [id]'s attempt [n]: its stamp whole, its body cut, as a full disk leaves it. */
    private fun cut(lines: List<String>, id: String, n: Int): String {
        val record = lines.filter { "\"turn\":\"$id\"" in it }
            .single { "\"kind\":\"attempt\"" in it && "\"attempt\":$n," in it }
        return record.take(record.length * 3 / 5)
    }

    /** Day one's file rewritten: [keep] of its lines, then [appended], each healed onto a line of its own as
     *  JsonlSink heals a torn tail. */
    private fun rewrite(dir: Path, keep: List<String>, appended: List<ByteArray>) {
        val out = ByteArrayOutputStream()
        keep.forEach { out.write("$it\n".toByteArray()) }
        appended.forEach { out.write(it + '\n'.code.toByte()) }
        Files.write(dayOne(dir), out.toByteArray())
    }

    /** Five turns the daemon wrote (t3 still open, t5 left with only a torn attempt), then every kind of line
     *  the two reads could part on, then day two: a rolled half whose one record has no newline, and a live
     *  file ending in a torn tail not healed yet. */
    private fun store(dir: Path): Path {
        now = DAY_ONE
        ids.clear()
        val store = traceStore(dir)
        store.done("t1")
        store.done("t2", sends = 2)
        store.turn("t3").send()
        store.done("t4")
        store.done("t5")
        drained()
        val lines = Files.readAllLines(dayOne(dir))
        rewrite(dir, keep = lines.filterNot { "\"turn\":\"t5\"" in it }, appended = parting(lines))
        dayTwo(dir)
        return dir
    }

    /** Every kind of line the two reads could part on, newest in the store: t2 and t5 cut in their bodies, a cut
     *  character, foreign kinds, keys reordered, escaped and repeated, malformed and lenient values, stray bytes. */
    private fun parting(lines: List<String>): List<ByteArray> {
        val t = DAY_ONE + 100 * SECOND
        val body = "x".repeat(BODY_CHARS)
        return listOf(
            cut(lines, "t2", 1).toByteArray(),
            cut(lines, "t5", 1).toByteArray(),
            byteArrayOf(0xE2.toByte(), 0x82.toByte()),
            """{"kind":"frame","turn":"t6","ts":$t,"session":"alpha-session"}""".toByteArray(),
            (
                """{"request":{"body":"$body"},"session":"alpha-session","attempt":1,""" +
                    """"ts":$t,"turn":"t7","kind":"attempt"}"""
                ).toByteArray(),
            ("{\"k" + BACKSLASH + "u0069nd\":\"attempt\",\"turn\":\"t8\",\"ts\":$t,\"attempt\":1}").toByteArray(),
            """{"kind":"frame","turn":"t9","ts":$t,"kind":"attempt","attempt":1}""".toByteArray(),
            """{"kind":"attempt","turn":"t10","ts":$t,"x":}""".toByteArray(),
            """{"kind":"attempt","turn":"t11","ts":$t} tail""".toByteArray(),
            """{"kind":"attempt","turn":"t12","ts":$t,"x":abc}""".toByteArray(),
            "{\"kind\":\"attempt\",\"turn\":\"t13\",\"ts\":$t,\"note\":\"a\tb\"}".toByteArray(),
            """{"kind":"attempt","turn":{"id":"t14"},"ts":$t}""".toByteArray(),
            "[1,2]".toByteArray(),
            "\"x\"".toByteArray(),
            ByteArray(0),
            "  {\"kind\":\"attempt\",\"turn\":\"t15\",\"ts\":$t,\"attempt\":1}\t ".toByteArray(),
            """{"kind":"attempt","turn":"t16","ts":$t,"note":"""".toByteArray() + byteArrayOf(0xFF.toByte()) +
                "\"}".toByteArray(),
            """{"kind":"attempt","turn":17,"ts":$t,"attempt":1}""".toByteArray(),
            """{"kind":"attempt","turn":null,"ts":$t}""".toByteArray(),
            """{"kind":"attempt","turn":"t18","ts":"$t","attempt":"1","note":"\ud800"}""".toByteArray(),
            """{"kind":"attempt","turn":"t19","ts":$t,"x":01}""".toByteArray(),
            """{"kind":"attempt","turn":"t20","ts":$t,"x":[1,{"a":[]}],"y":-0.5e+3,"z":true,"w":false,"v":null}"""
                .toByteArray(),
        )
    }

    /** Day two: a rolled half whose one record has no newline, and a live file ending in a torn tail not healed. */
    private fun dayTwo(dir: Path) {
        val dayTwo = DAY_ONE + DAY_MS
        val live = dir.resolve("$HEAD-2026-09-19.jsonl")
        Files.writeString(
            live.resolveSibling("${live.fileName}.1"),
            """{"kind":"attempt","turn":"t21","ts":$dayTwo,"attempt":1}""",
        )
        Files.writeString(
            live,
            "{\"kind\":\"attempt\",\"turn\":\"t22\",\"ts\":${dayTwo + SECOND},\"attempt\":1}\n" +
                "{\"kind\":\"attempt\",\"turn\":\"t23\",\"ts\":${dayTwo + 2 * SECOND},\"att",
        )
    }

    /** Today's count: every line of every day decoded whole; a line that does not decode, or names no turn
     *  under a kind the reader places, is skipped. Its on_disk, then its skipped lines. */
    private fun fullCount(dir: Path): Pair<Int, Int> {
        val placed = HashSet<String>()
        var skipped = 0
        DayFiles(dir, HEAD).lines().forEach { line ->
            val stamp = try {
                json.decodeFromString(FullStamp.serializer(), line)
            } catch (_: IllegalArgumentException) {
                null
            }
            val kind = JsonScalars.str(stamp?.kind)
            val id = JsonScalars.str(stamp?.turn)?.takeIf { kind == TraceKinds.TURN || kind == TraceKinds.ATTEMPT }
            if (id == null) skipped += 1 else placed += id
        }
        return placed.size to skipped
    }

    private fun digest(turns: List<TracedTurn>, count: String): String {
        val text = buildString {
            append(count).append('\n')
            turns.forEach { turn ->
                append(turn.id).append('\n')
                turn.attempts.forEach { append(it).append('\n') }
                append(turn.turn).append('\n')
            }
        }
        return MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun listing(dir: Path): String {
        val read = rows.read(dir, HEAD, TraceAsk(last = LIST_ALL))
        return digest(read.turns, "on_disk=${read.onDisk} skipped=${read.skippedLines}")
    }

    @Test
    fun `the count is the full decode's on every kind of line the two reads could part on`(@TempDir tmp: Path) {
        val dir = store(tmp)

        val read = rows.read(dir, HEAD, TraceAsk(last = LIST_ALL))

        assertEquals(fullCount(dir), read.onDisk to read.skippedLines, "on_disk and skipped lines")
    }

    @Test
    fun `a record torn after its leading fields is skipped, and a turn only it named is not on disk`(
        @TempDir tmp: Path,
    ) {
        now = DAY_ONE
        val store = traceStore(tmp)
        store.done("whole")
        store.done("lost")
        drained()
        val lines = Files.readAllLines(dayOne(tmp))
        rewrite(
            tmp,
            keep = lines.filterNot { "\"turn\":\"lost\"" in it },
            appended = listOf(cut(lines, "whole", 1).toByteArray(), cut(lines, "lost", 1).toByteArray()),
        )

        val read = rows.read(tmp, HEAD, TraceAsk(last = LIST_ALL))

        assertEquals(listOf("whole"), read.turns.map(TracedTurn::id), "the lost turn's torn record made no turn")
        assertEquals(1, read.onDisk, "a turn is on disk when a record of it can be listed")
        assertEquals(2, read.skippedLines, "each torn record is a skipped line")
        assertEquals(1, read.turns.single().attempts.size, "the torn copy added no attempt to its turn")
    }

    @Test
    fun `the listed turns and the count are today's, byte for byte`(@TempDir tmp: Path) {
        val first = listing(store(tmp.resolve("a")))
        assertEquals(first, listing(store(tmp.resolve("b"))), "the store is the same every time it is built")

        assertEquals(TODAY_ALL, first)
    }

    @Test
    fun `the newest turns are today's, byte for byte`(@TempDir tmp: Path) {
        val newest = { dir: Path -> digest(rows.turns(dir, HEAD, TraceAsk(last = NEWEST)), "newest $NEWEST") }
        val first = newest(store(tmp.resolve("a")))
        assertEquals(first, newest(store(tmp.resolve("b"))), "the store is the same every time it is built")

        assertEquals(TODAY_NEWEST, first)
    }
}
