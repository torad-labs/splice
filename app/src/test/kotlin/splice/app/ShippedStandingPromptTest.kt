// Marcos, Oct 28: "ship Splice by default with a pre defined configured append system prompt, that the user can
// change if they'd like ... that covers the gaps that make codex better at certain behaviors or even where we can
// improve some GPT failures we can identify from transcripts, like for example, stopping before the work is fully
// done, or overchecking, over testing and over reading." Claude Code's own prompt stays: the shipped text is appended.
//
// The goal, as a person running a head sees it: a head on an OpenAI Responses provider that sets no prompt of its own
// gets splice's shipped instructions appended to Claude Code's; a prompt the person sets replaces the shipped one; an
// empty system_prompt turns it off; a head on any other dialect gets nothing it did not get before.
package splice.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.head.ShippedStandingPrompts
import splice.core.prompt.SystemPromptMode
import splice.core.topology.Topology
import splice.topology.TopologyLoader
import java.nio.file.Path

class ShippedStandingPromptTest {

    @TempDir
    lateinit var tmp: Path

    @Test
    fun `a Responses head that sets no prompt gets splice's shipped instructions appended to Claude Code's`() {
        val resolved = resolve(topology(""), "gpt")

        assertEquals(SystemPromptMode.APPEND, resolved?.mode, "Claude Code's prompt stays; the shipped text is added")
        val text = resolved?.text.orEmpty()
        assertTrue(text.startsWith("# Working in Claude Code through splice"), text.take(200))
        assertTrue(
            "ends the moment you send a message with no tool call" in text,
            "it covers stopping before the work is done",
        )
        assertTrue("offset and limit" in text, "it covers reading more than the work needs")
        assertTrue("Don't poll" in text, "it covers checking again what has not changed")
    }

    @Test
    fun `a prompt the person sets replaces the shipped one`() {
        val resolved = resolve(topology("system_prompt = \"Keep every path absolute.\""), "gpt")

        assertEquals("Keep every path absolute.", resolved?.text)
        assertEquals("head:gpt append", resolved?.source)
    }

    @Test
    fun `an empty system_prompt turns the shipped instructions off`() {
        assertNull(resolve(topology("system_prompt = \"\""), "gpt"), "empty is the person saying no standing prompt")
    }

    @Test
    fun `a head on another dialect gets nothing it did not get before`() {
        assertNull(resolve(topology(""), "chat"), "the shipped text is the OpenAI Responses adapter's")
    }

    private fun resolve(topology: Topology, key: String) = topology.heads.getValue(key).let { head ->
        head.systemPromptFor(key, tmp, ShippedStandingPrompts().forProvider(topology.providers.getValue(head.provider)))
            .resolve()
    }

    private fun topology(gptPrompt: String): Topology = TopologyLoader.parse(
        """
        |[providers.openai]
        |dialect = "openai-responses"
        |base_url = "https://api.openai.example/v1"
        |auth = { kind = "api-key", env = "SYNTHETIC_OPENAI_KEY" }
        |[providers.local]
        |dialect = "openai-chat"
        |base_url = "http://127.0.0.1:11434/v1"
        |auth = { kind = "api-key", env = "SYNTHETIC_LOCAL_KEY" }
        |[heads.gpt]
        |provider = "openai"
        |port = 31401
        |discovery_prefix = "claude-gpt--"
        |pinned_model = "gpt-synthetic"
        |$gptPrompt
        |[heads.chat]
        |provider = "local"
        |port = 31402
        |discovery_prefix = "claude-chat--"
        |pinned_model = "chat-synthetic"
        """.trimMargin(),
    )
}
