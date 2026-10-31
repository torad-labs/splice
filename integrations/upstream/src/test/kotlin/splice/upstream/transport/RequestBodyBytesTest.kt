// The bytes the transport keeps for one round body, both ways a body arrives: they equal the
// text's UTF-8 byte for byte, and the declared size matches what is sent.
package splice.upstream.transport

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.core.util.JsonWire
import splice.upstream.RoundBody
import kotlin.random.Random

private const val MESSAGES = 420

/** Escapes, a slash, a control character and number spellings, all in plain ASCII. */
private const val ASCII_ACCENT = """ \"quoted\" back\\slash\n tab\t ctl\u0001 slash\/ """

/** The same, plus Latin-1, the euro sign, CJK and an astral emoji. */
private const val WIDE_ACCENT = """ café € 漢字 🐉 \"quoted\" back\\slash\n tab\t ctl\u0001 slash\/ """

/** How many strings the property generates, and the seed that makes them the same strings on every run. */
private const val GENERATED = 300
private const val SEED = 20_261_025

/** Where the UTF-8 writer's 8 Ki-character chunks meet, counted from the first character it writes. */
private val CHUNK_EDGES = listOf(8_192, 16_384)

/** Where a body's bytes pass from one block of its write to the next: 8 KiB blocks doubling to a 256 KiB cap. */
private val BLOCK_EDGES = listOf(8_192, 24_576, 57_344, 122_880, 253_952, 516_096, 778_240)

/** What the generated strings are made of: ASCII, the characters JSON escapes, Latin-1 and wider two-byte
 *  characters, three-byte characters on both sides of the surrogate range, astral pairs, and lone high and low
 *  surrogates. Two neighbouring lone halves may also meet as a pair, as they would in composed text. */
private val PIECES = listOf(
    "plain words ", "0123456789", "\"", "\\", "\n", "\t", "\r", "\b", "\u000c", "\u0001", "\u001f", "/", "\u007f",
    "é", "ü", "ß", " ", "ÿ", "Ж", "߿",
    "—", "€", "漢字", "ࠀ", "퟿", "", "￿",
    "🐉", "𝄞", "𐀀", "􏿿",
    "\ud800", "\udbff", "\udc00", "\udfff",
)

/** Number spellings the wire keeps as they arrived, ahead of the generated string in every tree. */
private val NUMBERS = Json.parseToJsonElement("[1.0,-0,1e5]")

class RequestBodyBytesTest {

    /** About 0.9 MB of synthetic history with [accent] on every line and number spellings up front. */
    private fun body(accent: String): JsonElement = Json.parseToJsonElement(
        (0 until MESSAGES).joinToString(
            separator = ",",
            prefix = """{"model":"synthetic","n":[1.0,1e5,-0,12345678901234567890,0.1e-7],"input":[""",
            postfix = "]}",
        ) { index ->
            val role = if (index % 2 == 0) "user" else "assistant"
            val line = "Synthetic history line $index for transport bytes. ".repeat(44)
            """{"role":"$role","content":"$line$accent"}"""
        },
    )

    @Test
    fun `the kept bytes are the body's UTF-8, from a tree and from text`() {
        listOf(ASCII_ACCENT, WIDE_ACCENT).forEach { accent ->
            val tree = body(accent)
            val text = JsonWire.string(tree)
            val expected = text.toByteArray(Charsets.UTF_8)

            assertArrayEquals(expected, RequestBody(RoundBody.Tree(tree)).bytes, "tree bytes for: $accent")
            assertArrayEquals(expected, RequestBody(RoundBody.Text(text)).bytes, "text bytes for: $accent")
            assertEquals(expected.size.toLong(), RoundBody.Tree(tree).byteSize())
            assertEquals(expected.size.toLong(), RoundBody.Text(text).byteSize())
        }
    }

    /** String.getBytes writes one '?' for a lone surrogate, and JsonWire.byteSize counts it as one byte.
     *  Text an interceptor composed can carry one, so the kept bytes must agree with both. */
    @Test
    fun `a lone surrogate in text encodes as the JVM's one-byte replacement`() {
        val text = "{\"t\":\"before \uD800 after é\"}"
        val kept = RequestBody(RoundBody.Text(text)).bytes

        assertArrayEquals(text.toByteArray(Charsets.UTF_8), kept)
        assertEquals(kept.size.toLong(), RoundBody.Text(text).byteSize())
    }

    /** The wire bytes are law 8's, so they hold over generated input rather than a list of examples: whichever way
     *  a body is held, and whether its size or its bytes are asked first, the bytes sent are the JDK's UTF-8 of the
     *  tree's text and the size is their length. The placed strings put pairs and lone halves where the UTF-8
     *  writer's chunks meet, on the text's count and on the string's own, and pairs across every block edge. */
    @Test
    fun `every generated body sends the JDK's UTF-8 of its text, however it is held and whichever is asked first`() {
        val random = Random(SEED)
        repeat(GENERATED) { index ->
            val value = generated(random, lengthOf(index, random))
            assertSendsUtf8(value, "generated #$index of ${value.length} characters")
        }
        val prefix = JsonWire.string(treeOf("")).length - "\"}".length
        (atChunkEdges(prefix) + atBlockEdges(prefix)).forEachIndexed { index, value ->
            assertSendsUtf8(value, "placed #$index of ${value.length} characters")
        }
    }

    /** One fresh body of each kind per question, so no answer is read off an earlier one. */
    private fun assertSendsUtf8(value: String, label: String) {
        val tree = treeOf(value)
        val text = JsonWire.string(tree)
        val expected = text.toByteArray(Charsets.UTF_8)
        val size = expected.size.toLong()

        val fresh = RoundBody.Tree(tree)
        assertArrayEquals(expected, RequestBody(fresh).bytes, "a fresh tree's bytes, $label")
        assertEquals(size, fresh.byteSize(), "a fresh tree's size after its bytes, $label")
        assertEquals(text, fresh.text, "a fresh tree's text after its bytes, $label")

        val sizedFirst = RoundBody.Tree(tree)
        assertEquals(size, sizedFirst.byteSize(), "a tree's size before its bytes, $label")
        assertArrayEquals(expected, RequestBody(sizedFirst).bytes, "a sized tree's bytes, $label")
        assertEquals(size, sizedFirst.byteSize(), "a sized tree's size after its bytes, $label")

        val read = RoundBody.Tree(tree)
        assertEquals(text, read.text, "a tree's text read first, lone surrogates intact, $label")
        assertEquals(size, read.byteSize(), "a read tree's size before its bytes, $label")
        assertArrayEquals(expected, RequestBody(read).bytes, "a read tree's bytes, $label")
        assertEquals(size, read.byteSize(), "a read tree's size after its bytes, $label")

        val composed = RoundBody.Text(text)
        assertArrayEquals(expected, RequestBody(composed).bytes, "a text's bytes, $label")
        assertEquals(size, composed.byteSize(), "a text's size after its bytes, $label")
        assertEquals(size, RoundBody.Text(text).byteSize(), "a text's size before its bytes, $label")
    }

    /** The tree a string rides in, built directly rather than parsed, so a lone surrogate reaches it as it is. */
    private fun treeOf(value: String): JsonObject = JsonObject(mapOf("n" to NUMBERS, "t" to JsonPrimitive(value)))

    /** Most strings short, the empty one first, some long, and the last ten past 600 KiB of characters. */
    private fun lengthOf(index: Int, random: Random): Int = when {
        index == 0 -> 0
        index < 200 -> random.nextInt(0, 2_048)
        index < 260 -> random.nextInt(2_048, 40_000)
        index < 290 -> random.nextInt(40_000, 300_000)
        else -> random.nextInt(614_400, 660_000)
    }

    /** At least [target] characters of pieces drawn by [random]. */
    private fun generated(random: Random, target: Int): String = buildString(target + 2) {
        while (length < target) append(PIECES[random.nextInt(PIECES.size)])
    }

    /** A pair, a lone high half, a lone low half and a high half before a pair, each where a chunk ends: counted
     *  from the text's first character, which the text's writer chunks, and from the string's, which the tree's
     *  writer chunks. [prefix] is the text's length before the string. */
    private fun atChunkEdges(prefix: Int): List<String> = CHUNK_EDGES.flatMap { edge ->
        listOf(edge - prefix, edge).flatMap { end ->
            listOf(
                "a".repeat(end - 1) + "🐉" + "b",
                "a".repeat(end - 1) + "\ud800" + "b",
                "a".repeat(end) + "\udc00" + "b",
                "a".repeat(end - 1) + "\ud800𐀀" + "b",
            )
        }
    }

    /** A four-byte pair that each block edge splits after its first, second and third byte, then a wide character.
     *  The text before the pair is ASCII, so its byte offset is its character offset. */
    private fun atBlockEdges(prefix: Int): List<String> = BLOCK_EDGES.flatMap { edge ->
        (1..3).map { split -> "a".repeat(edge - prefix - split) + "🐉" + "漢" + "z" }
    }
}
