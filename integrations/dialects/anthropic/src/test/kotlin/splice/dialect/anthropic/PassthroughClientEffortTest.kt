// Marcos, Oct 28: "claude code picks should also be considered, for all heads and all adapters we do. Always.", and on
// Kimi's fixed max: "We should never make configuration that might be expensive for the user without them knowing or
// without giving them an oiption to change." A Kimi turn sends the effort Claude Code's request names (its /effort,
// its --effort, which a launch sets to the head's configured effort), on Kimi's own rungs (low, high, max), never a
// rung above the one asked for. A later message's output_config changes it for the turn, as on every other head.
package splice.dialect.anthropic

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.core.parse.AnthropicParse

class PassthroughClientEffortTest {

    /** The output_config effort a Kimi turn sends when Claude Code's request carries [extra]. */
    private fun sent(extra: String, configEffort: String? = null): String? {
        val json = """{"model":"m","messages":[{"role":"user","content":"hi"}],
            "thinking":{"type":"adaptive"}$extra}"""
        val built = PassthroughRequestBuilder(KimiProfileFixture().kimi("kimi"), configEffort)
            .build(AnthropicParse.parseAnthropicBody(json), upstreamModel = "k3", originalModel = "k3", compact = false)
        return built.req["output_config"]?.jsonObject?.get("effort")?.jsonPrimitive?.content
    }

    private fun asked(effort: String) = ""","output_config":{"effort":"$effort"}"""

    @Test
    fun `a Kimi turn sends Claude Code's effort on Kimi's rungs, never above the one asked`() {
        val rungs = mapOf("low" to "low", "medium" to "low", "high" to "high", "xhigh" to "high", "max" to "max")
        for ((effort, rung) in rungs) assertEquals(rung, sent(asked(effort)), effort)
    }

    @Test
    fun `Claude Code's effort rides over the configured one`() {
        assertEquals("low", sent(asked("low"), configEffort = "max"))
    }

    @Test
    fun `the latest message's output_config changes the turn's effort`() {
        val changed = ""","messages":[{"role":"user","content":"hi"},
            {"role":"user","content":"x","output_config":{"effort":"low"}}],"output_config":{"effort":"max"}"""
        val json = """{"model":"m","thinking":{"type":"adaptive"}$changed}"""
        val built = PassthroughRequestBuilder(KimiProfileFixture().kimi("kimi"), null)
            .build(AnthropicParse.parseAnthropicBody(json), upstreamModel = "k3", originalModel = "k3", compact = false)
        assertEquals("low", built.req["output_config"]?.jsonObject?.get("effort")?.jsonPrimitive?.content)
    }
}
