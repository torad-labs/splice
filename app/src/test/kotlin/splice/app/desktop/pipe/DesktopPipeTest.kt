// desktop-antigravity (decision 5): a stream-json Code tab session in the pipe `splice desktop launch` holds it in. A
// live switch to an Antigravity model ends Claude Code and starts the translator on the same session id, replaying the
// session's `initialize` and then the `set_model`; a switch back starts Claude Code with `--resume`. Every other line
// reaches the program running as it came, and the pipe exits with that program's exit code. The programs here are
// small shell stand-ins that write down what they were started with and every line they read.
package splice.app.desktop.pipe

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.terminal.TerminalOutput
import java.io.ByteArrayInputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport

class DesktopPipeTest(@param:TempDir private val tmp: Path) {
    private val log = tmp.resolve("log.txt")
    private val sessions = tmp.resolve("sessions")
    private val said = mutableListOf<String>()

    /** A stand-in for Claude Code: keeps a session record naming [SESSION], writes each line it reads, ends at EOF. */
    private val claude = script(
        "claude",
        "mkdir -p \"\$SESSIONS\"; printf '{\"sessionId\":\"$SESSION\"}' > \"\$SESSIONS/\$\$.json\"\n" +
            "printf 'claude started %s\\n' \"\$*\" >> \"\$LOG\"\n" +
            "while IFS= read -r line; do printf 'claude read %s\\n' \"\$line\" >> \"\$LOG\"; done\n" +
            "printf 'claude ended\\n' >> \"\$LOG\"; rm -f \"\$SESSIONS/\$\$.json\"; exit 3\n",
    )

    /** A stand-in for `splice desktop acp`: writes each line it reads, ends on a set_model away from Antigravity. */
    private val acp = script(
        "acp",
        "printf 'acp started %s\\n' \"\$*\" >> \"\$LOG\"\n" +
            "while IFS= read -r line; do printf 'acp read %s\\n' \"\$line\" >> \"\$LOG\"\n" +
            "  case \"\$line\" in *set_model*claude-antigravity--*) ;; *set_model*) exit 0 ;; esac\n" +
            "done\n",
    )

    private fun script(name: String, body: String): Path = Files.writeString(tmp.resolve(name), "#!/bin/sh\n$body")

    private fun pipe() = DesktopPipe(
        PipeLegs(
            listOf("sh", acp.toString()),
            LegEnvironment { vars, _ -> vars["LOG"] = log.toString(); vars["SESSIONS"] = sessions.toString() },
            sessions,
        ),
        Dispatchers.IO,
        TerminalOutput { said += it },
        graceMs = GRACE_MS,
    )

    private fun line(model: String) = ClaudeLineReader().of(
        listOf("sh", claude.toString(), "--input-format", "stream-json", "--model", model),
    )

    @Test
    fun `a switch to Antigravity and back changes the program under the session and keeps its id`() = runBlocking {
        val desktop = PipedOutputStream()
        val input = PipedInputStream(desktop, BUFFER)
        val run = async(Dispatchers.IO) { pipe().run(line("claude-opus-5-5"), input) }
        fun say(text: String) = desktop.write("$text\n".toByteArray()).also { desktop.flush() }

        say(INITIALIZE)
        awaitLog("claude read $INITIALIZE")
        say(setModel("r1", "claude-antigravity--neon"))
        say(USER)
        awaitLog("acp read $USER")
        say(setModel("r2", "claude-opus-5-5"))
        awaitLog("claude started", times = 2)
        say(USER)
        awaitLog("claude read $USER")
        desktop.close()

        assertEquals(3, run.await(), "the pipe exits with the code of the program it runs last")
        val lines = Files.readAllLines(log)
        val expected = listOf(
            "claude started --input-format stream-json --model claude-opus-5-5",
            "claude read $INITIALIZE",
            "claude ended",
            "acp started --stands-in-for=$claude --input-format stream-json --model claude-antigravity--neon " +
                "--resume $SESSION",
            "acp read $INITIALIZE",
            "acp read ${setModel("r1", "claude-antigravity--neon")}",
            "acp read $USER",
            "acp read ${setModel("r2", "claude-opus-5-5")}",
            "claude started --input-format stream-json --model claude-opus-5-5 --resume $SESSION",
            "claude read $INITIALIZE",
            "claude read ${setModel("r2", "claude-opus-5-5")}",
            "claude read $USER",
            "claude ended",
        )
        assertEquals(expected, lines)
        assertTrue(said.isEmpty(), said.toString())
    }

    @Test
    fun `a session that starts on Antigravity runs on the translator from its first line`() = runBlocking {
        val input = ByteArrayInputStream("$USER\n".toByteArray())
        assertEquals(0, pipe().run(line("claude-antigravity--neon"), input))
        assertEquals(
            listOf(
                "acp started --stands-in-for=$claude --input-format stream-json --model claude-antigravity--neon",
                "acp read $USER",
            ),
            Files.readAllLines(log),
        )
    }

    @Test
    fun `a program that cannot be started is said, with a shell's exit`() = runBlocking {
        val missing = ClaudeLineReader().of(listOf(tmp.resolve("nowhere").toString(), "--input-format", "stream-json"))
        assertEquals(127, pipe().run(missing, ByteArrayInputStream(ByteArray(0))))
        assertTrue(said.single().contains("could not be started"), said.toString())
    }

    /** Waits, with a deadline and never a sleep for a duration, until [text] is in the log [times] times. */
    private fun awaitLog(text: String, times: Int = 1) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MS)
        while (true) {
            val seen = if (Files.exists(log)) Files.readAllLines(log).count { it.startsWith(text) } else 0
            if (seen >= times) return
            check(System.nanoTime() < deadline) { "never saw '$text' $times times" }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(POLL_MS))
        }
    }
}

private fun setModel(id: String, model: String) =
    """{"type":"control_request","request_id":"$id","request":{"subtype":"set_model","model":"$model"}}"""

private const val SESSION = "0b8c3c1e-5f40-4f1e-9d55-6a0d1c2f3e4a"
private const val INITIALIZE = """{"type":"control_request","request_id":"r0","request":{"subtype":"initialize"}}"""
private const val USER = """{"type":"user","message":{"role":"user","content":"hello"}}"""
private const val GRACE_MS = 5_000L
private const val WAIT_MS = 20_000L
private const val POLL_MS = 20L
private const val BUFFER = 1 shl 16
