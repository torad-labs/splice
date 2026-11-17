// NEW: a capability's text on the anthropic passthrough wire (docs/specs/harness.md, decision 5): what the provider
// receives.
package splice.dialect.anthropic

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class PassthroughFirstUserTextTest {
    private val first = PassthroughFirstUserText()

    private fun request(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `the text is its own block after the client's blocks, which keep their bytes and cache marks`() {
        val sent = request(
            """{"messages":[{"role":"user","content":[{"type":"text","text":"Add a flag.",""" +
                """"cache_control":{"type":"ephemeral"}}]},{"role":"assistant","content":"Done."}]}""",
        )

        assertEquals(
            """{"messages":[{"role":"user","content":[{"type":"text","text":"Add a flag.",""" +
                """"cache_control":{"type":"ephemeral"}},{"type":"text","text":"Found: a.ts"}]},""" +
                """{"role":"assistant","content":"Done."}]}""",
            first.append(sent, "Found: a.ts").toString(),
        )
    }

    @Test
    fun `a bare string is that string's one text block, then the text`() {
        val sent = request("""{"messages":[{"role":"user","content":"Add a flag."}]}""")

        assertEquals(
            """{"messages":[{"role":"user","content":[{"type":"text","text":"Add a flag."},""" +
                """{"type":"text","text":"Found: a.ts"}]}]}""",
            first.append(sent, "Found: a.ts").toString(),
        )
    }

    @Test
    fun `a request with no user message goes as it came`() {
        val sent = request("""{"messages":[]}""")

        assertSame(sent, first.append(sent, "Found: a.ts"))
    }
}
