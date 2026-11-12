// A trace literal is the JSON string of a body, encoded to the exact bytes a reader decodes.
package splice.head.trace.body

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

class TraceLiteralEncodingTest {
    @Test
    fun `escapes lone surrogates and boundary pairs keep exact encoded bytes`() {
        val text = "x".repeat(4095) + "🧭\"\\\n\r\t\b\u000c\u0000\uD800Ω東京"
        val expected = "\"" + "x".repeat(4095) + "🧭\\\"\\\\\\n\\r\\t\\b\\f\\u0000\\ud800Ω東京\""
        val sink = ByteArrayOutputStream()
        TraceLiteral.encode(text, sink, heap = splice.head.syntheticHeapBudget())
        assertArrayEquals(expected.toByteArray(), sink.toByteArray())
    }
}
