import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.prompt.HeadSystemPrompt
import splice.core.prompt.SystemPromptFileRead
import splice.core.prompt.SystemPromptMode
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

class HeadSystemPromptTest {

    @TempDir
    lateinit var tmp: Path

    @Test
    fun `an absent config resolves nothing`() {
        assertNull(HeadSystemPrompt().resolve())
    }

    @Test
    fun `an empty inline prompt resolves nothing rather than an empty prompt`() {
        assertNull(HeadSystemPrompt(text = "", source = "head:codex").resolve())
    }

    @Test
    fun `inline text resolves append by default, with its head source`() {
        val resolved = HeadSystemPrompt(text = "Be terse.", source = "head:codex").resolve()

        assertEquals("Be terse.", resolved?.text)
        assertEquals(SystemPromptMode.APPEND, resolved?.mode)
        assertEquals("head:codex append", resolved?.source)
    }

    @Test
    fun `replace mode is carried to the seam and named in the source`() {
        val resolved = HeadSystemPrompt(
            text = "You are a bare model.",
            mode = SystemPromptMode.REPLACE,
            source = "head:codex",
        ).resolve()

        assertEquals(SystemPromptMode.REPLACE, resolved?.mode)
        assertEquals("head:codex replace", resolved?.source)
    }

    @Test
    fun `a prompt file is read at load and named in the source`() {
        Files.createDirectories(tmp.resolve("prompts"))
        val file = tmp.resolve("prompts/standing.md")
        Files.writeString(file, "Retain every path.")

        val resolved = HeadSystemPrompt(
            file = "prompts/standing.md",
            configDir = tmp,
            source = "head:kimi",
        ).resolve()

        assertEquals("Retain every path.", resolved?.text)
        assertTrue(resolved?.source?.endsWith("file:$file") == true, resolved?.source)
    }

    @Test
    fun `setting both inline text and a file is a config error, never silent precedence`() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            HeadSystemPrompt(text = "inline", file = "standing.md", source = "head:codex")
        }

        assertTrue(failure.message?.contains("both") == true, failure.message)
    }

    @Test
    fun `a missing file is a config error naming the path, never an empty prompt`() {
        val missing = tmp.resolve("absent.md")

        val failure = assertThrows(IllegalArgumentException::class.java) {
            HeadSystemPrompt(file = missing.toString(), configDir = tmp, source = "head:codex")
        }

        assertTrue(failure.message?.contains(missing.toString()) == true, failure.message)
    }

    @Test
    fun `an unreadable file is a config error, never an empty prompt`() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            HeadSystemPrompt(
                file = "standing.md",
                configDir = tmp,
                source = "head:codex",
                readFile = SystemPromptFileRead { throw IOException("permission denied") },
            )
        }

        assertTrue(failure.message?.contains("head:codex") == true, failure.message)
    }
}
