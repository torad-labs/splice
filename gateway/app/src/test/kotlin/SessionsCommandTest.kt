import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.cli.SessionsCommand
import splice.core.sessions.SessionRegistry
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

class SessionsCommandTest {

    private val now = 1_789_312_411_660L

    @Test
    fun `live sessions get a send line, gone ones never read as active, unknown heads are named`(@TempDir dir: Path) {
        Files.writeString(
            dir.resolve("11.json"),
            """{"pid":11,"name":"alpha","status":"busy","updatedAt":${now - 120_000},"cwd":"/w/a",""" +
                """"messagingSocketPath":"/run/user/1000/cc-socks/11.sock"}""",
        )
        Files.writeString(dir.resolve("12.json"), """{"pid":12,"status":"idle","updatedAt":${now - 5_000_000}}""")
        Files.writeString(
            dir.resolve("13.json"),
            """{"pid":13,"name":"gamma","status":"busy","updatedAt":$now,"messagingSocketPath":"/run/x/13.sock"}""",
        )
        val registry = SessionRegistry(
            sessionsDir = dir,
            headOf = { pid -> if (pid == 11L) "claudex" else null },
            pidAlive = { it != 13L },
            clock = { now },
        )
        val out = capture { SessionsCommand().sessions({ null }, registry) { now } }
        val lines = out.lines()
        val alpha = lines.first { it.contains("alpha") }
        assertTrue(alpha.contains("claudex") && alpha.contains("live") && alpha.contains("2m ago"), alpha)
        val send = lines.first { it.contains("SendMessage(to=\"alpha\")") }
        assertTrue(send.contains("uds:/run/user/1000/cc-socks/11.sock"), send)
        val bare = lines.first { it.contains("pid 12") }
        assertTrue(bare.contains("unknown head") && bare.contains("stale"), bare)
        assertFalse(lines.any { it.contains("SendMessage(to=\"uds:") }, "a session without a socket gets no address")
        val gamma = lines.first { it.contains("gamma") }
        assertTrue(gamma.contains("gone"), gamma)
        assertFalse(gamma.contains("live"))
        assertFalse(lines.any { it.contains("SendMessage(to=\"gamma\")") }, "gone sessions get no send line")
        assertTrue(out.contains("claude -p"))
    }

    private fun capture(block: () -> Boolean): String {
        val buf = ByteArrayOutputStream()
        val original = System.out
        System.setOut(PrintStream(buf, true))
        try {
            block()
        } finally {
            System.setOut(original)
        }
        return buf.toString()
    }
}
