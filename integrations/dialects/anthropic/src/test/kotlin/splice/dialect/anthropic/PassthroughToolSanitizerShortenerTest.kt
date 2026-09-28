// NEW: preserve the Anthropic passthrough declaration's 64-character Muse cap after V4-351's shared shortener move.
package splice.dialect.anthropic

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.upstream.ToolNameShortener

class PassthroughToolSanitizerShortenerTest {
    @Test
    fun `the tool sanitizer rewrites the declaration the upstream validates`() {
        val name = "mcp__plugin_desktop-commander_desktop-commander__read_process_output"
        val shortener = ToolNameShortener(64)
        val sanitizer = PassthroughToolSanitizer(
            PassthroughQuirks(providerTag = "muse", toolNameCap = 64),
            PassthroughCacheControl(false),
            shortener,
        )
        val tools = buildJsonArray {
            add(
                buildJsonObject {
                    put("name", name)
                    put("description", "")
                },
            )
        }
        val wireName = (sanitizer.sanitizeTools(tools)[0] as JsonObject)["name"]!!.jsonPrimitive.content
        assertEquals(64, wireName.length)
        assertEquals(name, shortener.restore(wireName))
    }
}
