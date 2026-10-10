package splice.sessions.list

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.UserHome
import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader
import splice.sessions.registry.SessionRegistry
import splice.sessions.registry.SessionRoute
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
            routeOf = { pid -> if (pid == 11L) SessionRoute.Head("claudex") else SessionRoute.Unknown },
            pidAlive = { it != 13L },
            clock = { now },
        )
        val out = capture { sessionsCommand().sessions({ null }, registry) { now } }
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

    // V4-293: a session that never went through splice printed "unknown head", like one splice cannot place.
    @Test
    fun `a direct session prints direct and one splice cannot place prints unknown head`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("21.json"), """{"pid":21,"name":"direct-one","status":"idle","updatedAt":$now}""")
        Files.writeString(dir.resolve("22.json"), """{"pid":22,"name":"lost-one","status":"idle","updatedAt":$now}""")
        val registry = SessionRegistry(
            sessionsDir = dir,
            routeOf = { pid -> if (pid == 21L) SessionRoute.Direct else SessionRoute.Unknown },
            pidAlive = { true },
            clock = { now },
        )

        val lines = capture { sessionsCommand().sessions({ null }, registry) { now } }.lines()

        val direct = lines.first { "direct-one" in it }
        assertTrue("direct" in direct.substringAfter("direct-one") && "unknown head" !in direct, direct)
        assertTrue("unknown head" in lines.first { "lost-one" in it }, "$lines")
    }

    @Test
    fun `a stale session prints no send line even with a name and a socket`(@TempDir dir: Path) {
        Files.writeString(
            dir.resolve("21.json"),
            """{"pid":21,"name":"old","updatedAt":${now - 5_000_000},"messagingSocketPath":"/run/x/21.sock"}""",
        )
        val registry = SessionRegistry(
            sessionsDir = dir,
            routeOf = { SessionRoute.Unknown },
            pidAlive = { true },
            clock = { now },
        )
        val out = capture { sessionsCommand().sessions({ null }, registry) { now } }
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
        val registry = SessionRegistry(
            sessionsDir = dir,
            routeOf = { SessionRoute.Unknown },
            pidAlive = { true },
            clock = { now },
        )
        val out = capture { sessionsCommand().sessions({ null }, registry) { now } }
        val injected = listOf("\u001b[31m", "\u0007", "\u001b]0;evil", "\u009b", "\u200e")
        assertTrue(injected.none { it in out }, "no registry control sequence reaches the terminal: $out")
        assertTrue(out.contains("SendMessage(to=\"al[31mpha\\\"x\")"), out)
        val socketLine = "SendMessage(to=\"uds:/run/x/31m\\\"q\\\\32.sock\")  "
        assertTrue(out.contains(socketLine), "the socket is cleaned and escaped inside the syntax: $out")
        assertTrue(out.contains("# uds:/run/x/31m\"q\\32.sock"), "the comment shows the cleaned socket: $out")
    }

    /** V4-324: this send line and the console's copy key (strip.tsx sendCall) are held to ONE fixture
     *  file, so a name that reaches a session from the terminal reaches it from the console too: an
     *  emoji stays whole, a lone surrogate and every control or format character go. */
    @Test
    fun `the send line is the console's call on the shared fixtures`(@TempDir dir: Path) {
        val fixture = checkNotNull(javaClass.getResource("send-targets.json")) { "send-targets.json" }.readText()
        val cases = Json.parseToJsonElement(fixture).jsonObject.getValue("cases").jsonArray.map { it.jsonObject }
        cases.forEachIndexed { at, case ->
            val sessions = Files.createDirectories(dir.resolve("case-$at"))
            val registration = buildJsonObject {
                put("pid", 50 + at)
                put("updatedAt", now)
                case["name"]?.jsonPrimitive?.contentOrNull?.let { put("name", it) }
                case["socket"]?.jsonPrimitive?.contentOrNull?.let { put("messagingSocketPath", it) }
            }
            // Claude Code writes the registry with node's JSON.stringify, which escapes a surrogate that
            // stands alone; one could not be written as UTF-8 at all. Every half is escaped here, and an
            // escaped pair still decodes to its one character.
            val text = registration.toString().map { if (it.isSurrogate()) "\\u%04x".format(it.code) else "$it" }
            Files.writeString(sessions.resolve("${50 + at}.json"), text.joinToString(""))
            val registry = SessionRegistry(sessions, { SessionRoute.Unknown }, pidAlive = { true }, clock = { now })
            val out = capture { sessionsCommand().sessions({ null }, registry) { now } }
            val call = case["call"]?.jsonPrimitive?.contentOrNull
            if (call == null) {
                assertFalse(out.contains("SendMessage("), "case $at gives no call: $out")
            } else {
                assertTrue(out.contains(call), "case $at: $call in $out")
            }
        }
    }

    // The row is this JVM, whose route is its own environment's: a splice launch (SPLICE=1) cannot be
    // placed without the topology, and a direct one is direct whatever the topology says (V4-293).
    @Test
    fun `an unreadable topology places no session on a head and writes nothing`(@TempDir home: Path) {
        val sessions = Files.createDirectories(home.resolve(".claude/sessions"))
        val me = ProcessHandle.current().pid()
        Files.writeString(sessions.resolve("$me.json"), """{"pid":$me,"name":"self","updatedAt":$now}""")
        val bad = home.resolve("bad.toml")
        Files.writeString(bad, "not = [toml")
        val env = EnvReader { name -> bad.toString().takeIf { name == "SPLICE_CONFIG" } }
        val out = UserHome.within(home) {
            capture { sessionsCommand().sessions(env) { now } }
        }
        val expected = if (System.getenv("SPLICE") == "1") "unknown head" else "direct"
        assertTrue(out.lines().any { "self" in it && expected in it }, out)
        assertEquals("not = [toml", Files.readString(bad), "the malformed file is untouched")
        val entries = Files.list(home).use { it.map { p -> p.fileName.toString() }.toList().toSet() }
        assertEquals(setOf(".claude", "bad.toml"), entries, "no starter config was materialized")
    }

    @Test
    fun `a registry directory that cannot be listed is said, never read as no sessions`(@TempDir dir: Path) {
        val file = Files.writeString(dir.resolve("sessions"), "not a directory")
        val registry = SessionRegistry(sessionsDir = file, routeOf = { SessionRoute.Unknown }, clock = { now })
        var ok = true
        val out = capture { sessionsCommand().sessions({ null }, registry) { now }.also { ok = it } }
        assertFalse(ok, "an unreadable registry is not a successful listing")
        assertTrue(out.contains("could not be listed"), out)
        assertFalse(out.contains("no registered sessions"), out)
    }

    /** The verb with nothing injected: the registry under the home's .claude/sessions and the topology are read from
     *  where the environment points, as the installed command reads them. */
    @Test
    fun `with no registry handed in the verb lists the home's sessions and says when the topology is unreadable`(
        @TempDir home: Path,
    ) {
        val registered = Files.createDirectories(home.resolve(".claude").resolve("sessions"))
        val pid = ProcessHandle.current().pid()
        Files.writeString(
            registered.resolve("$pid.json"),
            """{"pid":$pid,"name":"here","status":"busy","updatedAt":${System.currentTimeMillis()},""" +
                """"messagingSocketPath":"/run/here.sock"}""",
        )
        val env = EnvReader { name ->
            when (name) {
                "HOME" -> home.toString()
                "SPLICE_CONFIG" -> home.resolve("absent.toml").toString()
                else -> null
            }
        }
        val diagnostics = mutableListOf<String>()
        val verb = SessionsCommand(TerminalOutput(::println), TerminalOutput { diagnostics += it })

        val out = capture { verb.sessions(env) }

        val row = out.lines().first { it.contains("here") }
        assertTrue(row.contains("live"), "the registered, living session is listed as live: $row")
        assertFalse(row.contains("claudex"), "with no readable topology no session is placed on a head: $row")
        assertTrue(out.contains("SendMessage(to=\"here\")"), out)
        assertTrue(diagnostics.single().contains("topology not readable"), diagnostics.toString())
    }

    /** The verb as app wires it — stdout and stderr — so capture() reads what an operator sees. */
    @Test
    fun `a session row names the account it is on and the pin it is under`(@TempDir dir: Path) {
        Files.writeString(
            dir.resolve("11.json"),
            """{"pid":11,"sessionId":"s-1","name":"alpha","status":"busy","updatedAt":$now,"cwd":"/w/a"}""",
        )
        Files.writeString(
            dir.resolve("12.json"),
            """{"pid":12,"sessionId":"s-2","name":"beta","status":"busy","updatedAt":$now,"cwd":"/w/b"}""",
        )
        val registry = SessionRegistry(
            sessionsDir = dir,
            routeOf = { SessionRoute.Head("claudex") },
            pidAlive = { true },
            clock = { now },
        )
        val command = SessionsCommand(
            TerminalOutput(::println),
            TerminalOutput(System.err::println),
            SessionAccounts { mapOf("s-1" to SessionAccountLine("primary", "work")) },
        )

        val lines = capture { command.sessions({ null }, registry) { now } }.lines()

        val alpha = lines.first { it.contains("alpha") }
        assertTrue(alpha.contains("claudex on primary (pinned to work)"), alpha)
        val beta = lines.first { it.contains("beta") }
        assertFalse(beta.contains("pinned"), beta)
        assertFalse(beta.contains(" on "), beta)
    }

    private fun sessionsCommand() = SessionsCommand(TerminalOutput(::println), TerminalOutput(System.err::println))

    private fun capture(block: () -> Boolean): String {
        val buf = ByteArrayOutputStream()
        val original = System.out
        System.setOut(PrintStream(buf, true))
        try {
            val _ = block()
        } finally {
            System.setOut(original)
        }
        return buf.toString()
    }
}
