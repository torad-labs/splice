package splice.app.cli.status

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.sessions.SessionRegistry
import splice.core.util.EnvReader
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

    @Test
    fun `a stale session prints no send line even with a name and a socket`(@TempDir dir: Path) {
        Files.writeString(
            dir.resolve("21.json"),
            """{"pid":21,"name":"old","updatedAt":${now - 5_000_000},"messagingSocketPath":"/run/x/21.sock"}""",
        )
        val registry = SessionRegistry(sessionsDir = dir, headOf = { null }, pidAlive = { true }, clock = { now })
        val out = capture { SessionsCommand().sessions({ null }, registry) { now } }
        assertTrue(out.contains("old") && out.contains("stale"), out)
        assertFalse(out.contains("SendMessage("), "only LIVE rows are messageable: $out")
    }

    @Test
    fun `registry text is sanitized and a name is escaped inside the send syntax`(@TempDir dir: Path) {
        val name = "al\u001b[31mpha\"x"
        val json = """{"pid":31,"name":${Json.encodeToString(name)},"status":"bu\u0007sy",""" +
            """"updatedAt":$now,"cwd":"/w/\u001b]0;evil\u0007a"}"""
        Files.writeString(dir.resolve("31.json"), json)
        // A blank name falls back to the socket, which is just as untrusted: C1 (0x9b = CSI), a
        // quote, a backslash and a Unicode format character ride in it.
        val socket = "/run/x/\u009b31m\"q\\\u200e32.sock"
        val blank = """{"pid":32,"name":"","updatedAt":$now,"messagingSocketPath":${Json.encodeToString(socket)}}"""
        Files.writeString(dir.resolve("32.json"), blank)
        val registry = SessionRegistry(sessionsDir = dir, headOf = { null }, pidAlive = { true }, clock = { now })
        val out = capture { SessionsCommand().sessions({ null }, registry) { now } }
        val injected = listOf("\u001b[31m", "\u0007", "\u001b]0;evil", "\u009b", "\u200e")
        assertTrue(injected.none { it in out }, "no registry control sequence reaches the terminal: $out")
        assertTrue(out.contains("SendMessage(to=\"al[31mpha\\\"x\")"), out)
        val socketLine = "SendMessage(to=\"uds:/run/x/31m\\\"q\\\\32.sock\")  "
        assertTrue(out.contains(socketLine), "the socket is cleaned and escaped inside the syntax: $out")
        assertTrue(out.contains("# uds:/run/x/31m\"q\\32.sock"), "the comment shows the cleaned socket: $out")
    }

    @Test
    fun `an unreadable topology leaves heads unknown and writes nothing`(@TempDir home: Path) {
        val sessions = Files.createDirectories(home.resolve(".claude/sessions"))
        val me = ProcessHandle.current().pid()
        Files.writeString(sessions.resolve("$me.json"), """{"pid":$me,"name":"self","updatedAt":$now}""")
        val bad = home.resolve("bad.toml")
        Files.writeString(bad, "not = [toml")
        val prev = System.getProperty("user.home")
        System.setProperty("user.home", home.toString())
        val env = EnvReader { name -> bad.toString().takeIf { name == "SPLICE_CONFIG" } }
        val out = try {
            capture { SessionsCommand().sessions(env) { now } }
        } finally {
            System.setProperty("user.home", prev)
        }
        assertTrue(out.contains("self") && out.contains("unknown head"), out)
        assertEquals("not = [toml", Files.readString(bad), "the malformed file is untouched")
        val entries = Files.list(home).use { it.map { p -> p.fileName.toString() }.toList().toSet() }
        assertEquals(setOf(".claude", "bad.toml"), entries, "no starter config was materialized")
    }

    @Test
    fun `a registry directory that cannot be listed is said, never read as no sessions`(@TempDir dir: Path) {
        val file = Files.writeString(dir.resolve("sessions"), "not a directory")
        val registry = SessionRegistry(sessionsDir = file, headOf = { null }, clock = { now })
        var ok = true
        val out = capture { SessionsCommand().sessions({ null }, registry) { now }.also { ok = it } }
        assertFalse(ok, "an unreadable registry is not a successful listing")
        assertTrue(out.contains("could not be listed"), out)
        assertFalse(out.contains("no registered sessions"), out)
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
