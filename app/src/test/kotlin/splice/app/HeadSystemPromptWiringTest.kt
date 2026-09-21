// V4-36: the operator's side of the config — what a [heads.KEY] entry actually resolves to,
// through the real topology parser. The amendment's default-mode rule lives here: an operator who
// writes system_prompt and names NO mode gets append, and only an explicit "replace" substitutes
// the client's own system field.
package splice.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.daemon.TopologyLoader
import splice.core.prompt.SystemPromptMode
import java.nio.file.Files
import java.nio.file.Path

class HeadSystemPromptWiringTest {

    @TempDir
    lateinit var tmp: Path

    @Test
    fun `a head that names no mode gets append`() {
        val head = heads(
            """
            [heads.claudex]
            provider = "codex"
            port = 8801
            discovery_prefix = "claude-codex--"
            pinned_model = "gpt-5.6-sol"
            system_prompt = "Never touch files outside the repo."
            """.trimIndent(),
        ).getValue("claudex")

        assertNull(head.systemPromptMode, "the operator named no mode, so nothing is decoded")

        val resolved = head.systemPromptFor("claudex", tmp).resolve()

        assertEquals(SystemPromptMode.APPEND, resolved?.mode)
        assertEquals("Never touch files outside the repo.", resolved?.text)
        assertEquals("head:claudex append", resolved?.source)
    }

    @Test
    fun `an explicit replace is carried, and named in the source`() {
        val head = heads(
            """
            [heads.claudex]
            provider = "codex"
            port = 8801
            discovery_prefix = "claude-codex--"
            pinned_model = "gpt-5.6-sol"
            system_prompt = "You are a bare model."
            system_prompt_mode = "replace"
            """.trimIndent(),
        ).getValue("claudex")

        val resolved = head.systemPromptFor("claudex", tmp).resolve()

        assertEquals(SystemPromptMode.REPLACE, resolved?.mode)
        assertEquals("head:claudex replace", resolved?.source)
    }

    @Test
    fun `a head file is read relative to the topology directory`() {
        Files.writeString(tmp.resolve("standing.md"), "Retain every path.")
        val head = heads(
            """
            [heads.claudex]
            provider = "codex"
            port = 8801
            discovery_prefix = "claude-codex--"
            pinned_model = "gpt-5.6-sol"
            system_prompt_file = "standing.md"
            """.trimIndent(),
        ).getValue("claudex")

        val resolved = head.systemPromptFor("claudex", tmp).resolve()

        assertEquals("Retain every path.", resolved?.text)
        assertTrue(resolved?.source?.endsWith("file:${tmp.resolve("standing.md")}") == true, resolved?.source)
    }

    @Test
    fun `a head that configures nothing resolves nothing`() {
        val head = heads(
            """
            [heads.claudex]
            provider = "codex"
            port = 8801
            discovery_prefix = "claude-codex--"
            pinned_model = "gpt-5.6-sol"
            """.trimIndent(),
        ).getValue("claudex")

        assertNull(head.systemPromptFor("claudex", tmp).resolve())
    }

    @Test
    fun `naming both the inline text and a file is refused at load`() {
        val head = heads(
            """
            [heads.claudex]
            provider = "codex"
            port = 8801
            discovery_prefix = "claude-codex--"
            pinned_model = "gpt-5.6-sol"
            system_prompt = "inline"
            system_prompt_file = "standing.md"
            """.trimIndent(),
        ).getValue("claudex")

        val failure = assertThrows(IllegalArgumentException::class.java) {
            head.systemPromptFor("claudex", tmp)
        }

        assertTrue(failure.message?.contains("both") == true, failure.message)
    }

    private fun heads(body: String) = TopologyLoader.parse(body).heads
}
