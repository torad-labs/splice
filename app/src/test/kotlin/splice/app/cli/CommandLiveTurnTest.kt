// CommandLiveTurn: the live check runs the installed wrapper for one real turn and demands its answer,
// is bounded by a deadline, and never echoes a child's raw output into the diagnosis.
package splice.app.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.CommandLiveTurn
import splice.core.util.EnvReader
import java.nio.file.Files
import java.nio.file.Path

private const val MODEL = "synthetic-model"

class CommandLiveTurnTest {

    @Test
    fun `the live checker runs the installed command and requires its answer`(@TempDir tmp: Path) {
        val arguments = tmp.resolve("arguments")
        val wrapper = script(tmp, "printf '%s\\n' \"\$@\" > '$arguments'\nprintf 'pong\\n'\n")
        val result = CommandLiveTurn().check(wrapper, "chosen-model")
        assertTrue(result.ok, result.detail)
        val args = Files.readAllLines(arguments)
        assertEquals("chosen-model", args[args.indexOf("--model") + 1], "the check turn names the chosen model")
        assertEquals("Reply with the single word pong.", args[args.indexOf("-p") + 1])
        assertTrue("--no-session-persistence" in args)
        assertTrue("--strict-mcp-config" in args)
        assertEquals("", args[args.indexOf("--tools") + 1])
        val empty = script(tmp, "exit 0\n")
        assertFalse(CommandLiveTurn().check(empty, MODEL).ok, "exit zero without an answer is not a passing check")
        val wrong = script(tmp, "printf 'not pong\\n'\n")
        assertFalse(CommandLiveTurn().check(wrong, MODEL).ok, "merely mentioning the requested word is not an answer")
    }

    @Test
    fun `a failed or stalled live checker returns a diagnosis without raw child output`(@TempDir tmp: Path) {
        val failed = script(tmp, "printf 'synthetic-private-token' >&2\nexit 9\n")
        val result = CommandLiveTurn().check(failed, MODEL)
        assertFalse(result.ok)
        assertTrue(result.detail.contains("exited 9"), result.detail)
        assertFalse(result.detail.contains("synthetic-private-token"))
        val stalled = script(tmp, "exec sleep 10\n")
        val timeout = CommandLiveTurn(timeoutMs = 20).check(stalled, MODEL)
        assertFalse(timeout.ok)
        assertTrue(timeout.detail.contains("time limit"), timeout.detail)
        assertFalse(CommandLiveTurn().check(tmp.resolve("missing"), MODEL).ok)
    }

    @Test
    fun `the live checker finds the command in a custom install directory`(@TempDir tmp: Path) {
        script(tmp, "printf 'pong\\n'\n")
        val env = EnvReader { name ->
            when (name) {
                "HOME", "SPLICE_BIN_DIR" -> tmp.toString()
                else -> null
            }
        }
        val result = CommandLiveTurn().invoke("check-command", MODEL, env)
        assertTrue(result.ok, result.detail)
    }

    @Test
    fun `an inherited child output stream cannot extend the check deadline`(@TempDir tmp: Path) {
        val wrapper = script(tmp, "sleep 2 &\nprintf 'pong\\n'\n")
        val start = System.nanoTime()
        val result = CommandLiveTurn(timeoutMs = 100).check(wrapper, MODEL)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue(elapsedMs < 1000, "inherited output held the check for $elapsedMs ms: ${result.detail}")
    }

    private fun script(tmp: Path, body: String): Path {
        val wrapper = tmp.resolve("check-command")
        Files.writeString(wrapper, "#!/bin/sh\n$body")
        assertTrue(wrapper.toFile().setExecutable(true))
        return wrapper
    }
}
