// NEW: Oct 12, 2026 — a session with no name is called by the first line of the first real message its person sent.
package splice.client.transcript

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

private const val META = """{"type":"user","isMeta":true,"message":{"role":"user","content":"caveat nobody typed"}}"""
private const val RESULT =
    """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t1","content":"ok"}]}}"""

private fun person(text: String) = """{"type":"user","message":{"role":"user","content":${jsonString(text)}}}"""

private fun jsonString(text: String) = kotlinx.serialization.json.JsonPrimitive(text).toString()

class TranscriptTitleTest {
    @TempDir
    lateinit var tmp: Path

    private val fixture by lazy { ActivityTranscript(tmp) }

    private fun title(): String? = fixture.reader.title(ACTIVITY_ID, listOf(tmp))

    @Test
    fun `the title is the first line of the first message the person typed`() {
        fixture.write(listOf(META, person("fix the billing rounding\nit loses a cent on refunds"), person("later")))
        assertEquals("fix the billing rounding", title())
    }

    @Test
    fun `a note nobody typed and a tool result are not the first message`() {
        fixture.write(listOf(META, RESULT, person("  rename the users table  ")))
        assertEquals("rename the users table", title())
    }

    @Test
    fun `a long first line is cut to about eighty characters with an ellipsis`() {
        fixture.write(listOf(person("word ".repeat(40))))
        val cut = title().orEmpty()
        assertEquals(80, cut.length)
        assertEquals('…', cut.last())
    }

    @Test
    fun `no message of the person's is no title, and one that arrives later is found`() {
        fixture.write(listOf(META, RESULT))
        assertNull(title())
        fixture.append(person("now there is one"))
        assertEquals("now there is one", title())
    }

    @Test
    fun `an unchanged poll and a grown file read no bytes once the title is found`() {
        fixture.write(listOf(person("first ask")))
        assertEquals("first ask", title())
        val cold = fixture.bytes
        assertEquals("first ask", title())
        fixture.append(person("second ask"))
        assertEquals("first ask", title())
        assertEquals(cold, fixture.bytes, "a found title is not read again")
    }
}
