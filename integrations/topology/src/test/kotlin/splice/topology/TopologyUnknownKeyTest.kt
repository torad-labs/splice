// NEW: v0.4.0 compatibility statement (README "Compatibility") — `splice.toml` keys are a contract, and
// this is half of what holds it: the parser refuses a key the schema does not declare, so a key
// dropped or renamed in the schema fails ExampleConfigTest (the committed example uses the keys) instead
// of being ignored on every operator's file. TopologyDiscoveryParseTest's header asserted the refusal in
// prose; nothing tested it.
package splice.topology

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class TopologyUnknownKeyTest {

    private fun provider(extra: String) = TopologyLoader.parse(
        """
        [providers.openrouter]
        dialect = "openai-chat"
        base_url = "https://openrouter.ai/api/v1"
        auth = { kind = "api-key", env = "OPENROUTER_API_KEY" }
        $extra
        """.trimIndent(),
    )

    @Test
    fun `a key the schema does not declare is refused, not ignored`() {
        assertEquals("https://openrouter.ai/api/v1", provider("").providers.getValue("openrouter").baseUrl)
        assertThrows(Exception::class.java) { provider("base_uri = \"https://example.invalid\"") }
    }
}
