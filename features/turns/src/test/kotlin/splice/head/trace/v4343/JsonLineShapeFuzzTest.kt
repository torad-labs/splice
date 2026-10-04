// NEW: V4-343 — the gate on JsonLineShape, the second author of "this line is JSON": thousands of lines
// derived from records shaped as the daemon writes them, damaged the ways a disk, a writer or a hand damages
// them, under a fixed seed. A line the shape counts must be one kotlinx parses whole, with the same stamp; a
// line it declines is decoded by kotlinx as every line was, so only that direction can part the two reads.
// Each line is also handed over a few bytes at a time, and must read the same as when it is read whole.
package splice.head.trace.v4343

import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import splice.head.trace.JsonLineShape
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlin.random.Random

// why: the fuzz replays: a red names its case, and the same seed rebuilds the line that made it
private const val SEED = 4343

// why: "several thousand" lines, the gate's floor; each is a few kilobytes, so the cell runs in seconds
private const val LINES = 8000

// why: a tenth of the lines, the least each outcome must get, so a shape that declines every line, or counts
// every line, fails here rather than passing a cell that tested nothing
private const val OUTCOME_FLOOR = LINES / 10

// why: deeper than any call stack a recursive reader would survive, so a nesting case finds one
private const val MAX_DEPTH = 700

// why: past JsonLineShape's MAX_CAPTURE_BYTES (4096), so an oversized stamp is one it must decline
private const val OVERSIZED = 5000

// why: the most bytes one dribbled read hands over, so every token is split across reads somewhere
private const val MAX_DRIBBLE = 7

// why: the most mutations a mixed case chains
private const val MAX_CHAIN = 3

// why: the bytes an escape spans after its backslash at most, a \u and its four hex digits
private const val ESCAPE_REACH = 5

// why: how much of a failing line a message quotes
private const val PREVIEW = 300

// why: the one day the records are stamped on, 2026-09-18 00:00 UTC
private const val DAY = 1_789_689_600_000L

// why: the last control character, which RFC 8259 admits in a string only escaped
private const val LAST_CONTROL = 0x1F

// why: the values a byte takes, read unsigned
private const val BYTE_VALUES = 256

private const val BACKSLASH = '\\'
private val U = "${BACKSLASH}u"
private val LF = '\n'.code.toByte()
private val CR = '\r'.code.toByte()

/** Today's reference: the stamp TraceTail's full decode reads, the whole line through kotlinx. */
@Serializable
private data class FuzzStamp(
    val kind: JsonElement? = null,
    val turn: JsonElement? = null,
    val ts: JsonElement? = null,
    val session: JsonElement? = null,
    val attempt: JsonElement? = null,
    val attempts: JsonElement? = null,
)

class JsonLineShapeFuzzTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val shape = JsonLineShape(FuzzStamp.serializer().descriptor.elementNames.toSet())

    @Test
    fun `a line the shape counts is a line kotlinx parses whole, with the same stamp`() {
        val lines = Random(SEED)
        val reads = Random(SEED + 1)
        val counts = Mutation.entries.associateWith { intArrayOf(0, 0) }
        repeat(LINES) { n ->
            val mutation = Mutation.entries[n % Mutation.entries.size]
            val line = mutation.apply(Bases.all.random(lines), lines)
            val case = "$mutation #$n"
            val whole = shape.members(ByteArrayInputStream(line))
            assertEquals(whole, shape.members(Dribble(line, reads)), "$case reads the same a few bytes at a time")
            if (whole != null) agrees(whole, line, case)
            counts.getValue(mutation)[if (whole != null) 0 else 1] += 1
        }
        val table = counts.entries.joinToString { (mutation, n) -> "$mutation ${n[0]} counted/${n[1]} declined" }
        assertTrue(counts.values.sumOf { it[0] } >= OUTCOME_FLOOR) { "too few lines counted to test: $table" }
        assertTrue(counts.values.sumOf { it[1] } >= OUTCOME_FLOOR) { "too few lines declined to test: $table" }
        // A decline is always safe for the count, since kotlinx reads the line instead, so a fast path that declines
        // what it should pass hides from every check above while the page goes back to 21 s: every record the
        // writer makes is one the shape must vouch for.
        assertEquals(0, counts.getValue(Mutation.NONE)[1]) { "an undamaged record was declined: $table" }
    }

    /** The shape counted [line]: kotlinx must decode it to the same stamp, and parse it whole into one object,
     *  as TraceTail's take does with every record it keeps. */
    private fun agrees(members: Map<String, String>, line: ByteArray, case: String) {
        val text = lenient(line)
        val full = try {
            json.decodeFromString(FuzzStamp.serializer(), text)
        } catch (e: IllegalArgumentException) {
            fail("$case: counted, but kotlinx rejects it: ${text.take(PREVIEW)}", e)
        }
        val tree = try {
            json.parseToJsonElement(text)
        } catch (e: IllegalArgumentException) {
            fail("$case: counted, but kotlinx cannot parse it whole: ${text.take(PREVIEW)}", e)
        }
        assertTrue(tree is JsonObject) { "$case: counted, but it is not one object: ${text.take(PREVIEW)}" }
        val fields = JsonObject(members.mapValues { (_, raw) -> json.parseToJsonElement(raw) })
        assertEquals(full, json.decodeFromJsonElement(FuzzStamp.serializer(), fields), case)
    }

    /** The line as DayLine.text decodes it: a malformed byte reads as U+FFFD. */
    private fun lenient(line: ByteArray): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
        .decode(ByteBuffer.wrap(line))
        .toString()
}

/** Records shaped as the daemon writes them (keys, order and value types read off claudex's store on
 *  2026-09-26), a record whose named members hold every other type, and a foreign line. */
private object Bases {
    /** A request body as the writer stores one: a JSON document, escaped into a string by kotlinx. */
    private val body = JsonPrimitive(
        buildJsonObject {
            put("model", "gpt-6-sol")
            putJsonArray("input") {
                addJsonObject {
                    put("role", "user")
                    put("content", "café \"q\" 😀\ttab / \\ " + Char(1))
                }
            }
            put("n", -0.5e3)
        }.toString(),
    ).toString()

    /** A response as a stream carries it, colour codes and a control character included, which the writer escapes
     *  in the record itself as \u001b and \u0001: escapes in a string the shape passes rather than keeps. */
    private val stream = JsonPrimitive(
        "event: done\ndata: {\"ok\":true}\n\n" + Char(0x1b) + "[32mok" + Char(0x1b) + "[0m " + Char(1),
    ).toString()

    private fun attempt(i: Int): String =
        """{"kind":"attempt","turn":"t-$i","ts":${DAY + i},"head":"claudex","session":"0f0eef86-$i",""" +
            """"model":"gpt-6-sol","clientModel":"claude-opus-5-5","compact":false,"round":1,""" +
            """"attempt":${i % 3 + 1},"transport":"http","request":{"headers":{"content-type":"application/json",""" +
            """"x-ids":["a",1,true,null]},"body":$body,"truncated":false},""" +
            """"response":{"text":$stream,"truncated":true},"durationMs":${40 + i}}"""

    private fun turn(i: Int): String =
        """{"kind":"turn","turn":"t-$i","ts":${DAY + i + 1},"head":"claudex","session":"0f0eef86-$i",""" +
            """"model":"gpt-6-sol","clientModel":"claude-opus-5-5","compact":true,"client":{"method":"POST",""" +
            """"path":"/v1/messages","headers":{"anthropic-version":"2023-06-01"},"body":$body,"truncated":false},""" +
            """"answer":{"status":200,"stream":true,"body":$stream,"truncated":false},"outcome":"ok","rounds":1,""" +
            """"attempts":${i % 4},"perf":{"marks":{"total":120,"first":1.5E-2},"counters":{}}}"""

    private fun odd(i: Int): String =
        """{ "kind" : "turn" , "turn":"t-$i ${U}00e9$BACKSLASH$BACKSLASH${U}D83D${U}DE00", "ts":"${DAY + i}",""" +
            """"session":null,"attempts":0,"attempt":-1.25e2,"extra":[[],{},[{"a":[1,2,{"b":null}]}]],"t":true }"""

    private fun foreign(i: Int): String = """{"kind":"frame","turn":"t-$i","ts":${DAY + i},"frame":{"n":$i}}"""

    val all: List<ByteArray> = (0 until 8).flatMap { i -> listOf(attempt(i), turn(i), odd(i), foreign(i)) }
        .map { it.toByteArray() }
}

/** The damage a case does to its base line. */
private enum class Mutation(val apply: (ByteArray, Random) -> ByteArray) {
    NONE({ base, _ -> base }),
    TRUNCATE(Damage::truncate),
    FLIP(Damage::flip),
    CONTROL(Damage::control),
    SURROGATE(Damage::surrogate),
    DUPLICATE(Damage::duplicate),
    NEST(Damage::nest),
    OVERSIZE(Damage::oversize),
    ESCAPE(Damage::escape),
    MIXED(Damage::mixed),
}

/** Each damage keeps the line free of terminators, since a line never holds one. */
private object Damage {
    private val lone = listOf(
        "${U}d800".toByteArray(),
        "${U}DFFF".toByteArray(),
        byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte()),
    )
    private val twice = listOf(""""turn":"dup",""", """"kind":"turn",""", """"head":"again",""", """"ts":1,""")

    /** A strict prefix, as a disk-full append leaves one. */
    fun truncate(base: ByteArray, random: Random): ByteArray = base.copyOf(random.nextInt(maxOf(base.size, 1)))

    fun flip(base: ByteArray, random: Random): ByteArray =
        if (base.isEmpty()) base else base.copyOf().also { it[random.nextInt(it.size)] = anyByte(random) }

    fun control(base: ByteArray, random: Random): ByteArray =
        Edits.insert(base, random.nextInt(base.size + 1), byteArrayOf(controlByte(random)))

    /** A lone surrogate, escaped high or low, or as the raw bytes of one (CESU-8, which UTF-8 forbids), into
     *  the turn id or anywhere. */
    fun surrogate(base: ByteArray, random: Random): ByteArray {
        val named = if (random.nextBoolean()) Edits.after(base, "\"turn\":\"") else -1
        return Edits.insert(base, if (named >= 0) named else random.nextInt(base.size + 1), lone.random(random))
    }

    /** A member written twice: after an object's opening brace, the line's own (a top-level twin) or any other. */
    fun duplicate(base: ByteArray, random: Random): ByteArray {
        val opens = base.indices.filter { base[it] == '{'.code.toByte() }
        if (opens.isEmpty()) return base
        return Edits.insert(base, opens.random(random) + 1, twice.random(random).toByteArray())
    }

    /** A value nested deep, arrays or objects, balanced or one short: a new member, or around the stamp. */
    fun nest(base: ByteArray, random: Random): ByteArray {
        val depth = random.nextInt(1, MAX_DEPTH)
        val arrays = random.nextBoolean()
        val open = (if (arrays) "[" else """{"a":""").repeat(depth)
        val close = (if (arrays) "]" else "}").repeat(depth - random.nextInt(2))
        val at = if (random.nextBoolean()) Edits.after(base, "\"ts\":") else -1
        if (at < 0) return Edits.insert(base, minOf(1, base.size), "\"deep\":${open}1$close,".toByteArray())
        val wrapped = Edits.insert(base, Edits.valueEnd(base, at), close.toByteArray())
        return Edits.insert(wrapped, at, open.toByteArray())
    }

    /** A named member past what the shape keeps: a long id, a long number, a long run of escapes. */
    fun oversize(base: ByteArray, random: Random): ByteArray {
        val value = when (random.nextInt(3)) {
            0 -> "\"" + "t".repeat(OVERSIZED) + "\""
            1 -> "1".repeat(OVERSIZED)
            else -> "\"" + "${U}00e9".repeat(OVERSIZED / U.length) + "\""
        }
        return Edits.replaced(base, listOf("turn", "ts", "session").random(random), value)
    }

    /** An escape broken where the string skip passes whole ones: its letter or a hex digit replaced, or the
     *  bytes after its backslash cut short. */
    fun escape(base: ByteArray, random: Random): ByteArray {
        val slashes = base.indices.filter { base[it] == BACKSLASH.code.toByte() }
        val at = if (slashes.isEmpty()) base.size else slashes.random(random) + 1 + random.nextInt(ESCAPE_REACH)
        if (at >= base.size) return base
        if (random.nextBoolean()) return base.copyOf().also { it[at] = anyByte(random) }
        val resume = minOf(base.size, at + 1 + random.nextInt(ESCAPE_REACH))
        return base.copyOfRange(0, at) + base.copyOfRange(resume, base.size)
    }

    fun mixed(base: ByteArray, random: Random): ByteArray {
        var line = base
        val single = Mutation.entries.filter { it != Mutation.MIXED }
        repeat(random.nextInt(2, MAX_CHAIN + 1)) { line = single.random(random).apply(line, random) }
        return line
    }

    private fun anyByte(random: Random): Byte =
        generateSequence { random.nextInt(BYTE_VALUES).toByte() }.first { it != LF && it != CR }

    private fun controlByte(random: Random): Byte =
        generateSequence { random.nextInt(LAST_CONTROL + 1).toByte() }.first { it != LF && it != CR }
}

/** Byte edits of a line, found by its text (ISO-8859-1 reads one char per byte, so offsets agree). */
private object Edits {
    fun insert(base: ByteArray, at: Int, bytes: ByteArray): ByteArray =
        base.copyOfRange(0, at) + bytes + base.copyOfRange(at, base.size)

    /** The offset just past the first [text] in [base], or -1. */
    fun after(base: ByteArray, text: String): Int {
        val at = String(base, Charsets.ISO_8859_1).indexOf(text)
        return if (at < 0) -1 else at + text.length
    }

    /** Where the scalar value starting at [at] ends: the next comma or closing brace. */
    fun valueEnd(base: ByteArray, at: Int): Int =
        (at until base.size).firstOrNull { base[it] == ','.code.toByte() || base[it] == '}'.code.toByte() }
            ?: base.size

    /** [base] with member [name]'s value replaced by [value], or the member added first when it has none. */
    fun replaced(base: ByteArray, name: String, value: String): ByteArray {
        val at = after(base, "\"$name\":")
        if (at < 0) return insert(base, minOf(1, base.size), "\"$name\":$value,".toByteArray())
        return base.copyOfRange(0, at) + value.toByteArray() + base.copyOfRange(valueEnd(base, at), base.size)
    }
}

/** [bytes] handed over a few at a time, so every token of a line is split across reads somewhere. */
private class Dribble(private val bytes: ByteArray, private val random: Random) : InputStream() {
    private var at = 0

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toUByte().toInt()
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (at >= bytes.size) return -1
        val n = minOf(len, bytes.size - at, 1 + random.nextInt(MAX_DRIBBLE))
        bytes.copyInto(b, off, at, at + n)
        at += n
        return n
    }
}
