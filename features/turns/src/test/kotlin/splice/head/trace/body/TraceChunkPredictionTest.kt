// NEW: trace cost (Oct 25). The trace chunker guesses each cut from the chunk that followed the one before it last
// time, and confirms each guess by the digest it takes of every chunk anyway, so history the pack already holds is no
// longer Gear-scanned again: on Oct 25, 92.6% of claude-splice's 12 GB of body text went through the scan while 11.6%
// was new. These pin what no guess may change. Every body reads back exactly, and is stored as exactly the chunks the
// plain Gear rule cuts, written here from its definition: over busy days whose sessions push each other's bodies out of
// the four remembered ones, across a resume from a body whose cut a missed guess's rescan found, and under follower
// records that lie.
package splice.head.trace.body

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.memory.HeapReservations
import splice.core.storage.DayBodyBudget
import splice.core.util.LogSink
import splice.core.util.WallClock
import splice.head.syntheticHeapBudget
import splice.head.trace.TraceAsk
import splice.head.trace.TraceRows
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.random.Random

private const val HEAD = "synthetic"
private const val DAY = "$HEAD-2026-10-25.jsonl"
private val BODY_FIELDS = listOf("request" to "body", "response" to "text", "client" to "body", "answer" to "body")

// why: three seeded days of about ten megabytes of body text each, so the property runs in seconds.
private val BUSY_DAY_SEEDS = listOf(25, 26, 27)

// why: how many large bodies the pack remembers (TraceLiteralPrefix); this many unrelated ones push a body out.
private const val REMEMBERED = 4

// why: A0 must be remembered, which takes CHUNK_MAX chars, and span about thirty chunks of dense multi-byte text.
private const val RESCAN_CHARS = 100_000
private const val UNRELATED_CHARS = 70_000

// why: "a few KiB" of suffix, as a conversation's next turn adds.
private const val SUFFIX_CHARS = 2_000
private const val RESCAN_SEED = 1025

// why: a char no generated text holds, so a body cut back there shares exactly the chars before it.
private const val CUT_MARK = "§"

class TraceChunkPredictionTest {
    @Test
    fun `busy days store every body as the plain rule's chunks and read each one back exactly`(@TempDir dir: Path) {
        val oracle = Oracle()
        val mismatches = BUSY_DAY_SEEDS.flatMap { seed ->
            val folder = Files.createDirectory(dir.resolve("day-$seed"))
            val day = folder.resolve(DAY)
            val records = BusyDay(seed).records()
            val bodies = TraceBodies(heap = syntheticHeapBudget(), log = LogSink {})
            val lines = records.map { bodies.encode(it, day) }
            Files.write(day, joined(lines))
            val read = TraceRows(heap = syntheticHeapBudget()).turns(folder, HEAD, TraceAsk(last = records.size))
            val back = read.flatMap { it.attempts + listOfNotNull(it.turn) }
            println("TRACE_PREDICTION_DAY seed=$seed records=${records.size} body_chars=${oracle.chars(records)}")
            oracle.mismatches("day $seed", records, lines.map(::parsed), back)
        }
        assertEquals(emptyList<String>(), mismatches)
    }

    @Test
    fun `a body resumed from one whose cut a rescan found reads back exactly as the plain rule's chunks`(
        @TempDir dir: Path,
    ) {
        val oracle = Oracle()
        val a0 = Prose.dense(Random(RESCAN_SEED), RESCAN_CHARS)
        val edit = rescanEdit(a0, oracle)
        val unrelated = (1..REMEMBERED).map { "u$it" + Prose.dense(Random(RESCAN_SEED + it), UNRELATED_CHARS) }
        // A2 resumes from A1 and reuses every part before A1's last, those at and after the rescanned cut included.
        val a2 = edit.text + Prose.dense(Random(RESCAN_SEED - 1), SUFFIX_CHARS)
        // A3 shares A1's chars up to the middle of the part after that cut, so it resumes right at the cut's mark.
        val a3 = edit.text.substring(0, edit.within) + CUT_MARK + Prose.dense(Random(RESCAN_SEED - 2), SUFFIX_CHARS)
        val records = (listOf(a0) + unrelated + listOf(edit.text, a2, a3)).mapIndexed { at, text -> turn("t$at", text) }
        val day = dir.resolve(DAY)
        val bodies = TraceBodies(heap = syntheticHeapBudget(), log = LogSink {})
        val lines = records.map { bodies.encode(it, day) }
        Files.write(day, joined(lines))
        val read = TraceRows(heap = syntheticHeapBudget()).turns(dir, HEAD, TraceAsk(last = records.size))
        val back = read.mapNotNull { it.turn }
        assertEquals(emptyList<String>(), oracle.mismatches("rescan", records, lines.map(::parsed), back))
    }

    @Test
    fun `no follower record moves a cut, fresh or resumed`(@TempDir dir: Path) {
        val oracle = Oracle()
        val bases = LIE_TEXT_CHARS.mapIndexed { at, chars -> Prose.mixed(Random(LIE_SEED + at), chars) }
        val edited = bases.mapIndexed { at, base -> edits(base, Random(LIE_SEED - at)) }
        val texts = bases + edited.flatten()
        val expected = texts.associateWith(oracle::chunks)
        val lies = Lies(texts.map(oracle::literal))
        val index = TracePackIndex(syntheticHeapBudget())
        val budget = DayBodyBudget(minFreeBytes = 0, clock = WallClock { NOW })
        val mismatches = TraceBodyPack(dir.resolve("$DAY.bodies2"), index, budget = budget).use { pack ->
            val encoder = Encoder(pack, index)
            // Every chunk is stored first, since a record is kept only on a chunk the pack holds.
            texts.forEach { encoder.encode(it) }
            (0 until LIE_ROUNDS).flatMap { round ->
                val told = lies.told(round)
                val lie = { told.forEach { it.tell(pack) } }
                val freshMisses = texts.filter { text ->
                    lie()
                    chunksOf(encoder.encode(text).first) != expected[text]
                }
                val resumedMisses = bases.indices.filter { bases[it].length >= CHUNK_MAX }.flatMap { at ->
                    RESUMED_EDITS.map { edited[at][it] }.filter { text ->
                        encoder.remember(bases[at])
                        lie()
                        chunksOf(encoder.resumed(text)) != expected[text]
                    }
                }
                freshMisses.map { "round $round: a ${it.length}-char literal encoded whole" } +
                    resumedMisses.map { "round $round: a ${it.length}-char literal resumed" }
            }
        }
        assertEquals(emptyList<String>(), mismatches)
    }

    private fun turn(id: String, body: String): JsonObject = buildJsonObject {
        put("kind", "turn")
        put("turn", id)
        put("ts", 1_793_000_000_000L)
        put("head", HEAD)
        put("model", "m")
        put("client", buildJsonObject { put("body", body) })
    }
}

/** One chunk as a body reference names it: its raw bytes' SHA-256 in hex and their length. */
private data class Chunk(val hash: String, val bytes: Int)

/** The plain Gear rule, from its definition rather than from TraceChunker. GEAR[b] is the first 8 bytes, big-endian,
 *  of the SHA-256 of the single byte b. Each byte shifts the value left one bit and adds its entry. A cut falls at the
 *  first count at or past CHUNK_MIN whose value has its low CHUNK_TARGET-1 bits clear, or at CHUNK_MAX, and the value
 *  restarts at each cut. What is left after the last cut is the last part. */
private object PlainGear {
    private val gear = LongArray(1 shl Byte.SIZE_BITS) { ByteBuffer.wrap(sha(byteArrayOf(it.toByte()))).long }

    /** Where each part of [bytes] ends, the last at its end. */
    fun cuts(bytes: ByteArray): IntArray {
        val ends = ArrayList<Int>()
        var start = 0
        var value = 0L
        for (at in bytes.indices) {
            value = (value shl 1) + gear[bytes[at].toInt() and BYTE_MASK]
            val count = at + 1 - start
            val boundary = count >= CHUNK_MIN && clear(value)
            if (boundary || count == CHUNK_MAX) {
                ends += at + 1
                start = at + 1
                value = 0L
            }
        }
        if (start < bytes.size) ends += bytes.size
        return ends.toIntArray()
    }

    fun chunks(bytes: ByteArray): List<Chunk> {
        var start = 0
        return cuts(bytes).map { end ->
            Chunk(hex(sha(bytes, start, end - start)), end - start).also { start = end }
        }
    }

    /** Whether the value over the 64 bytes before [end] has the low bits a cut needs clear. */
    fun clearAt(bytes: ByteArray, end: Int): Boolean {
        var value = 0L
        for (at in end - Long.SIZE_BITS until end) value = (value shl 1) + gear[bytes[at].toInt() and BYTE_MASK]
        return clear(value)
    }

    private fun clear(value: Long): Boolean = (value and (CHUNK_TARGET - 1).toLong()) == 0L
}

private const val BYTE_MASK = 0xff

/** The plain rule's chunks of each body's literal bytes, held against what the trace stored and read back. */
private class Oracle(private val heap: HeapReservations = syntheticHeapBudget()) {
    /** The bytes TraceLiteral writes for [text], quotes included: what the chunker is fed. */
    fun literal(text: String): ByteArray =
        ByteArrayOutputStream().also { TraceLiteral.encode(text, it, heap) }.toByteArray()

    fun chunks(text: String): List<Chunk> = PlainGear.chunks(literal(text))

    fun chars(records: List<JsonObject>): Long =
        records.sumOf { record -> BODY_FIELDS.sumOf { (section, field) -> body(record, section, field)?.length ?: 0 } }
            .toLong()

    /** Each body of [records] that [stored] does not keep as the plain rule's chunks, or [back] does not read back. */
    fun mismatches(
        label: String,
        records: List<JsonObject>,
        stored: List<JsonObject>,
        back: List<JsonObject>,
    ): List<String> {
        if (back.size != records.size) return listOf("$label: ${back.size} records read back of ${records.size}")
        return records.indices.flatMap { at ->
            BODY_FIELDS.mapNotNull { (section, field) ->
                body(records[at], section, field)?.let { text ->
                    val reference = (stored[at][section] as? JsonObject)?.get(field) as? JsonObject
                    when {
                        body(back[at], section, field) != text -> "$label record $at: $section reads back other text"
                        reference == null -> "$label record $at: $section stored no reference"
                        parts(reference) != chunks(text) -> "$label record $at: $section is not the plain rule's chunks"
                        else -> null
                    }
                }
            }
        }
    }

    private fun parts(reference: JsonObject): List<Chunk> = chunksOf(reference.getValue("parts").jsonArray)
}

/** The chunks a literal's [parts] name, in order. */
private fun chunksOf(parts: JsonArray): List<Chunk> = parts.map { part ->
    val fields = part.jsonObject
    Chunk(fields.getValue("hash").jsonPrimitive.content, fields.getValue("bytes").jsonPrimitive.int)
}

/** A body's text, or null where the record holds none or holds a reference instead. */
private fun body(record: JsonObject, section: String, field: String): String? =
    ((record[section] as? JsonObject)?.get(field) as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun parsed(line: ByteArray): JsonObject = Json.parseToJsonElement(line.decodeToString()).jsonObject

private fun joined(lines: List<ByteArray>): ByteArray =
    ByteArrayOutputStream().also { out -> lines.forEach(out::write) }.toByteArray()

private fun sha(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size): ByteArray =
    MessageDigest.getInstance("SHA-256").apply { update(bytes, offset, length) }.digest()

private fun hex(digest: ByteArray): String = HexFormat.of().formatHex(digest)

/** A1 for the rescan test, and a char [within] the part that starts at the cut a rescan of A1 finds. */
private data class RescanEdit(val text: String, val within: Int)

// why: A0's edited chunk must hold the edit, a cut at least CHUNK_MIN in and RESCAN_MARGIN of the guess after the cut.
private const val EDITED_CHUNK_BYTES = 12 * 1024

// why: the edit starts a batch into its chunk, so the parts before it keep their bytes.
private const val EDIT_SKIP_BYTES = 1024

// why: four of the literal writer's 1 KiB batches: when the guess is settled, the origin the writer named last lies
// past the rescanned cut, which is the case a rescan cut must not take that origin for.
private const val RESCAN_MARGIN = 4 * 1024
private const val EDIT_ATTEMPTS = 64

/** A0 with the text of one middle chunk rewritten, chosen with the oracle so that A1's plain chunking cuts strictly
 *  inside the span that chunk covered, and at least RESCAN_MARGIN before its end. The guess that A0's chunk follows
 *  the one before it then misses, and the rescan of the guessed span finds that cut. */
private fun rescanEdit(a0: String, oracle: Oracle): RescanEdit {
    val cuts = oracle.literal(a0).let(PlainGear::cuts)
    val starts = starts(a0)
    val middle = (cuts.size / 3 until cuts.size * 2 / 3).filter { cuts[it] - cuts[it - 1] >= EDITED_CHUNK_BYTES }
    for (attempt in 0 until EDIT_ATTEMPTS) {
        val start = cuts[middle[attempt % middle.size] - 1]
        val end = cuts[middle[attempt % middle.size]]
        val from = whole(a0, first(starts, start + EDIT_SKIP_BYTES))
        val until = whole(a0, first(starts, end - RESCAN_MARGIN))
        val a1 = a0.substring(0, from) + Prose.dense(Random(attempt), until - from) + a0.substring(until)
        val after = oracle.literal(a1).let(PlainGear::cuts)
        val cut = after.firstOrNull { it > start } ?: continue
        if (start in after && cut <= end - RESCAN_MARGIN) {
            val next = after.first { it > cut }
            return RescanEdit(a1, whole(a1, first(starts(a1), (cut + next) / 2)))
        }
    }
    error("no edit of A0 makes the plain rule cut inside a guessed span")
}

/** Where each char's bytes start in the literal TraceLiteral writes for [text], after its opening quote. */
private fun starts(text: String): IntArray {
    val starts = IntArray(text.length + 1)
    starts[0] = 1
    for (at in text.indices) starts[at + 1] = starts[at] + TraceLiteral.width(text, at)
    return starts
}

/** The first char whose bytes start at or past [byte]. */
private fun first(starts: IntArray, byte: Int): Int = starts.indexOfFirst { it >= byte }

/** [at], moved off the second half of a surrogate pair, so a cut or an edit there keeps the pair whole. */
private fun whole(text: String, at: Int): Int {
    val low = at in 1 until text.length && text[at].isLowSurrogate()
    return if (low && text[at - 1].isHighSurrogate()) at + 1 else at
}

/** Seeded text mixing what bodies carry: ASCII words, characters JSON escapes, two- and three-byte UTF-8, surrogate
 *  pairs and lone surrogates. */
private object Prose {
    private val words = listOf(
        "trace", "chunk", "body", "splice", "gear", "cut", "pack", "index", "turn", "history", "message", "tool",
        "result", "the", "a", "of", "to", "and", "conversation", "assistant", "user", "system", "file", "line",
    )
    private val escaped = listOf("\"", "\\", "\n", "\t", "\r", "\u0001", "\u001f", "\b")
    private val twoByte = listOf("café", "λόγος", "жизнь", "ñandú", "ø", "ß")
    private val threeByte = listOf("東京", "日本語", "₂₀₂₆", "→", "…", "한국어")
    private val pairs = listOf("😀", "🧭", "𝄞", "🚀", "🦀")
    private val lone = listOf(Char(0xD83D).toString(), Char(0xDE00).toString(), Char(0xDBFF).toString())

    /** Mostly words, the way a conversation reads. */
    fun mixed(random: Random, chars: Int): String = text(random, chars, MIXED_WEIGHTS)

    /** Mostly multi-byte chars and surrogate pairs. */
    fun dense(random: Random, chars: Int): String = text(random, chars, DENSE_WEIGHTS)

    private fun text(random: Random, chars: Int, weights: IntArray): String = buildString {
        val pools = listOf(words, escaped, twoByte, threeByte, pairs, lone)
        while (length < chars) {
            var roll = random.nextInt(weights.sum())
            var pool = 0
            while (roll >= weights[pool]) roll -= weights[pool++]
            append(pools[pool][random.nextInt(pools[pool].size)])
            if (random.nextInt(SPACE_ODDS) == 0) append(' ')
        }
    }
}

// why: the share of words, escaped chars, two-byte, three-byte, pairs and lone halves in each kind of text.
private val MIXED_WEIGHTS = intArrayOf(70, 6, 8, 8, 6, 2)
private val DENSE_WEIGHTS = intArrayOf(10, 4, 20, 30, 32, 4)
private const val SPACE_ODDS = 3

/** A head's busy day as Claude Code sends it. Eight or more conversations are interleaved at random, so by a
 *  conversation's next turn the pack's four remembered bodies have usually moved on. Each turn repeats its
 *  conversation's whole history, in the client's layout with the cache marker on the newest message and in the
 *  upstream's layout. Now and then a turn rewrites a message deep in its history, or repeats a turn exactly. A few
 *  conversations carry most of the day, so their bodies grow several CHUNK_MAX long, while new ones stay under
 *  64 KiB. */
private class BusyDay(seed: Int) {
    private val random = Random(seed)
    private val sessions = BUSY_SESSIONS + random.nextInt(BUSY_SESSIONS_SPREAD)
    private val system = Prose.mixed(random, SYSTEM_CHARS)
    private val tools = Prose.mixed(random, TOOLS_CHARS)
    private val ephemeral = buildJsonObject { put("type", "ephemeral") }

    fun records(): List<JsonObject> {
        val histories = List(sessions) { mutableListOf<JsonObject>() }
        val last = arrayOfNulls<List<JsonObject>>(sessions)
        return (0 until BUSY_TURNS).flatMap { turn ->
            // The lower of two draws, so the first conversations are the busiest.
            val session = minOf(random.nextInt(sessions), random.nextInt(sessions))
            val again = last[session]?.takeIf { random.nextInt(PERCENT) < REPEAT_PERCENT }
            val records = again?.map { JsonObject(it + ("turn" to JsonPrimitive("t$turn"))) }
                ?: step(histories[session], "t$turn")
            records.also { last[session] = it }
        }
    }

    private fun step(history: MutableList<JsonObject>, id: String): List<JsonObject> {
        if (history.size > REWRITE_AFTER && random.nextInt(PERCENT) < REWRITE_PERCENT) {
            val at = random.nextInt(history.size / 2)
            history[at] = message(checkNotNull(body(history[at], "role")), Prose.mixed(random, RESULT_CHARS))
        }
        history += message("assistant", Prose.mixed(random, REPLY_CHARS + random.nextInt(REPLY_CHARS)))
        val read = random.nextInt(PERCENT) < LARGE_READ_PERCENT
        history += message("user", Prose.mixed(random, if (read) LARGE_READ_CHARS else random.nextInt(RESULT_CHARS)))
        val request = "request" to section("body", upstream(history))
        val client = "client" to section("body", client(history))
        return listOf(
            record("attempt", id, request, "response" to section("text", answer())),
            record("turn", id, client, "answer" to section("body", answer())),
        )
    }

    private fun section(field: String, text: String): JsonObject = buildJsonObject { put(field, text) }

    private fun answer(): String = Prose.mixed(random, 1 + random.nextInt(ANSWER_CHARS))

    private fun body(message: JsonObject, field: String): String? = (message[field] as? JsonPrimitive)?.content

    private fun client(history: List<JsonObject>): String = buildJsonObject {
        put("model", "m")
        val marked = history.mapIndexed { at, m ->
            if (at == history.lastIndex) JsonObject(m + ("cache_control" to ephemeral)) else m
        }
        put("messages", JsonArray(marked))
        put("system", system)
        put("tools", tools)
    }.toString()

    private fun upstream(history: List<JsonObject>): String = buildJsonObject {
        put("instructions", system)
        val input = history.map { m ->
            buildJsonObject {
                put("type", "message")
                put("content", checkNotNull(m["content"]))
                put("role", checkNotNull(m["role"]))
            }
        }
        put("input", JsonArray(input))
        put("tools", tools)
    }.toString()

    private fun record(kind: String, id: String, vararg sections: Pair<String, JsonObject>): JsonObject =
        buildJsonObject {
            put("kind", kind)
            put("turn", id)
            put("ts", 1_793_000_000_000L)
            put("head", HEAD)
            put("model", "m")
            sections.forEach { (name, content) -> put(name, content) }
        }

    private fun message(role: String, text: String): JsonObject = buildJsonObject {
        put("role", role)
        put("content", text)
    }
}

// why: eight to ten conversations, twice what the four remembered bodies serve.
private const val BUSY_SESSIONS = 8
private const val BUSY_SESSIONS_SPREAD = 3
private const val BUSY_TURNS = 64
private const val PERCENT = 100
private const val REPEAT_PERCENT = 6
private const val REWRITE_PERCENT = 8
private const val REWRITE_AFTER = 6
private const val LARGE_READ_PERCENT = 8

// why: near what one Claude Code tool round adds, after a system prompt and tool list every request repeats; the
// first turns' bodies stay under 64 KiB, and a busy conversation's grow past three CHUNK_MAX.
private const val SYSTEM_CHARS = 6_000
private const val TOOLS_CHARS = 14_000
private const val REPLY_CHARS = 700
private const val RESULT_CHARS = 9_000
private const val LARGE_READ_CHARS = 36_000
private const val ANSWER_CHARS = 3_000

/** Encodes literals into one pack as TraceBodies.literal does, without its cache of whole texts, so that each one runs
 *  through the chunker: whole, or resumed from the parts and skip the pack's resume returns. */
private class Encoder(private val pack: TraceBodyPack, private val index: TracePackIndex) {
    private val heap = syntheticHeapBudget()

    /** [text]'s parts and where they were cut, encoded whole or from [resume]. */
    fun encode(text: String, resume: TraceLiteralResume? = null): Pair<JsonArray, TraceCuts> {
        val chunker = TraceChunker(pack, heap, resume?.parts.orEmpty(), resume?.skip ?: 0)
        TraceLiteral.encode(text, chunker, heap, resume?.from ?: 0, chunker.cuts)
        return chunker.finish() to chunker.cuts
    }

    /** [text]'s parts, resumed from the one literal the pack remembers. */
    fun resumed(text: String): JsonArray = encode(text, checkNotNull(pack.resume(text))).first

    /** Encodes [text] whole and holds it as the one literal the pack remembers, as a conversation's last body is. */
    fun remember(text: String) {
        index.recent.clear()
        val (parts, cuts) = encode(text)
        index.recent.remember(text, parts, cuts)
    }
}

/** Four bodies a conversation's next turn makes of [base]: grown, with text inserted in the middle, with early text
 *  replaced, and cut back with new text after. */
private fun edits(base: String, random: Random): List<String> {
    val middle = whole(base, base.length / 2)
    val early = whole(base, base.length / 8)
    val back = whole(base, base.length * 3 / 4)
    val fresh = { Prose.mixed(random, SUFFIX_CHARS) }
    return listOf(
        base + fresh(),
        base.substring(0, middle) + fresh() + base.substring(middle),
        base.substring(0, early) + fresh() + base.substring(whole(base, early + SUFFIX_CHARS)),
        base.substring(0, back) + CUT_MARK + fresh(),
    )
}

// why: the edits a resume serves, by their place in edits(): grown, an insertion in the middle, and cut back.
private val RESUMED_EDITS = listOf(0, 1, 3)

// why: one literal under CHUNK_MAX chars, which is never remembered, and three past it, up to a few dozen chunks.
private val LIE_TEXT_CHARS = listOf(9_000, 40_000, 80_000, 160_000)
private const val LIE_SEED = 2510

// why: 2026-10-25T12:00Z, the day the pack's file is named for.
private const val NOW = 1_792_929_600_000L

// why: four lies about a chunk's real next one and three about another chunk. Every other chunk lies in a round and
// the rest tell the truth, so over LIE_ROUNDS rounds each chunk tells each lie once.
private const val SUCCESSOR_LIES = 4
private const val STRANGER_LIES = 3
private const val LIE_KINDS = SUCCESSOR_LIES + STRANGER_LIES
private const val LIE_ROUNDS = 2 * LIE_KINDS

// why: how far a told length may lie off the real one.
private const val LIE_OFF_BYTES = 512
private const val SHA_BYTES = 32

/** Follower records about every chunk of [literals] as the plain rule cuts them, half of them lies. A record the
 *  chunker writes names a chunk the rule cut from a cut point, whose bytes hold no cut before their end; these keep
 *  that and get the rest wrong. A chunk's record is read only when the chunk is cut outside a rescan, so in each
 *  round every other chunk tells the truth about the chunk that first followed it. That one is then cut where the
 *  truth says or by the plain rule, unless an earlier lie's rescan reached it, and its own record is read. It lies,
 *  by turns: the next chunk's digest under a length below CHUNK_MIN, or up to LIE_OFF_BYTES off; the digest of a
 *  prefix of the next chunk at least CHUNK_MIN long, where the rule does not cut; that of a prefix shorter than
 *  CHUNK_MIN, ending where Gear's low bits are clear; a real chunk wrongly paired with it; the last part of a literal;
 *  or a random digest. */
private class Lies(literals: List<ByteArray>) {
    private val texts = literals.map(::spans)
    private val all = texts.flatten()
    private val lasts = texts.map { it.last() }

    // Each chunk once, in the order the literals first hold it, with the chunk that first followed it.
    private val chunks = LinkedHashMap<String, Pair<Span, Span?>>().also { first ->
        texts.forEach { spans ->
            spans.forEachIndexed { at, span -> first.putIfAbsent(span.hash, span to spans.getOrNull(at + 1)) }
        }
    }

    /** Round [round]'s records. A lie is the kind after the one the lying chunk two before it tells. So the long
     *  prefix lie, which only the end check refuses, comes after a lie about a length, and the short prefix lie, which
     *  only the range check refuses, after the long one. Neither of those buffers to the end of the chunk after the
     *  truthful one, so that chunk is cut outside a rescan and its record is read. */
    fun told(round: Int): List<Lie> = chunks.values.mapIndexedNotNull { serial, (span, next) ->
        val random = Random(round * chunks.size + serial)
        val kind = (serial / 2 + round) % LIE_KINDS
        val record = when {
            serial % 2 != round / LIE_KINDS -> next?.named()
            next != null && kind < SUCCESSOR_LIES -> successor(kind, next, random)
            else -> stranger(kind, random)
        }
        record?.let { (digest, length) -> Lie(span.hash, digest, length) }
    }

    private fun successor(kind: Int, next: Span, random: Random): Pair<ByteArray, Int> = when (kind) {
        0 -> next.digest to 1 + random.nextInt(CHUNK_MIN - 1)
        1 -> next.digest to off(next.length, random)
        2 -> next.inside(random) ?: next.below
        else -> next.below
    }

    private fun stranger(kind: Int, random: Random): Pair<ByteArray, Int> = when (kind % STRANGER_LIES) {
        0 -> all[random.nextInt(all.size)].named()
        1 -> lasts[random.nextInt(lasts.size)].named()
        else -> random.nextBytes(SHA_BYTES) to CHUNK_MIN + random.nextInt(CHUNK_MAX - CHUNK_MIN + 1)
    }
}

/** A record that the chunk named [hash] was followed by [length] bytes digesting to [digest]. */
private class Lie(private val hash: String, private val digest: ByteArray, private val length: Int) {
    fun tell(pack: TraceBodyPack) {
        pack.follow(hash, digest, length)
    }
}

/** One chunk of [literal] as the plain rule cuts it, [length] bytes from [start]. */
private class Span(private val literal: ByteArray, private val start: Int, val length: Int) {
    val digest: ByteArray = sha(literal, start, length)
    val hash: String = hex(digest)

    /** The digest of a prefix shorter than CHUNK_MIN, ending where Gear's low bits are clear when one does. */
    val below: Pair<ByteArray, Int> by lazy {
        val chunk = literal.copyOfRange(start, start + length)
        val longest = minOf(length, CHUNK_MIN) - 1
        val prefix = (longest downTo Long.SIZE_BITS).firstOrNull { PlainGear.clearAt(chunk, it) } ?: maxOf(1, longest)
        sha(literal, start, prefix) to prefix
    }

    fun named(): Pair<ByteArray, Int> = digest to length

    /** The digest of a prefix at least CHUNK_MIN long, where the rule did not cut, or null when the chunk has none. */
    fun inside(random: Random): Pair<ByteArray, Int>? = (length - CHUNK_MIN).takeIf { it > 0 }?.let { room ->
        val prefix = CHUNK_MIN + random.nextInt(room)
        sha(literal, start, prefix) to prefix
    }
}

/** [literal]'s chunks as the plain rule cuts it. */
private fun spans(literal: ByteArray): List<Span> {
    var start = 0
    return PlainGear.cuts(literal).map { end -> Span(literal, start, end - start).also { start = end } }
}

/** [length] moved up to LIE_OFF_BYTES, the way that keeps it a length a chunk can have. */
private fun off(length: Int, random: Random): Int {
    val by = 1 + random.nextInt(LIE_OFF_BYTES)
    return if (length + by <= CHUNK_MAX) length + by else length - by
}
