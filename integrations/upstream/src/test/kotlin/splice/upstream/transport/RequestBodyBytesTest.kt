// The bytes the transport keeps for one round body, both ways a body arrives: they equal the
// text's UTF-8 byte for byte, and the declared size matches what is sent.
package splice.upstream.transport

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.core.util.JsonWire
import splice.upstream.RoundBody

private const val MESSAGES = 420

/** Escapes, a slash, a control character and number spellings, all in plain ASCII. */
private const val ASCII_ACCENT = """ \"quoted\" back\\slash\n tab\t ctl\u0001 slash\/ """

/** The same, plus Latin-1, the euro sign, CJK and an astral emoji. */
private const val WIDE_ACCENT = """ café € 漢字 🐉 \"quoted\" back\\slash\n tab\t ctl\u0001 slash\/ """

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
}
