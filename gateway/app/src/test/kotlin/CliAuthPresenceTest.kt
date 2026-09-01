// DR-70 absence-class arms at CLI assembly (the DR-59 posture): denied access to a credential
// file is NOT logged-out — status/setup/login must treat it as present/configured and say the
// real remedy out loud, never route the operator into a redundant (and possibly destructive)
// re-login. Same law for the installed jar: unreadable is not "not installed".
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.LoginIo
import splice.app.cli.AdminSupport
import splice.app.cli.MgmtKeyRead
import splice.app.cli.RestartCommand
import splice.core.topology.AuthConfig
import splice.core.topology.Dialect
import splice.core.topology.ProviderConfig
import splice.core.util.EnvReader
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class CliAuthPresenceTest {

    private fun <T> withDenied(dir: Path, block: () -> T): T = try {
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("---------"))
        block()
    } finally {
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"))
    }

    private fun capturingStdout(block: () -> Unit): String {
        val savedOut = System.out
        val out = ByteArrayOutputStream()
        try {
            System.setOut(PrintStream(out, true))
            block()
        } finally {
            System.setOut(savedOut)
        }
        return out.toString()
    }

    private fun oauthProvider(file: Path) = ProviderConfig(
        dialect = Dialect.OPENAI_RESPONSES,
        baseUrl = "https://example.invalid",
        auth = AuthConfig(kind = "chatgpt-oauth", file = file.toString()),
    )

    @Test
    fun `a denied credential file reads present with the remedy said - DR-70`(@TempDir tmp: Path) {
        val dir = Files.createDirectories(tmp.resolve("auth"))
        val credential = dir.resolve("auth.json")
        Files.writeString(credential, """{"access_token":"tok"}""")
        var present = false
        val printed = capturingStdout { present = withDenied(dir) { AdminSupport.authPresent(credential.toString()) } }
        assertTrue(present, "an intact credential one chmod away must not read as logged-out")
        assertTrue(printed.contains("fix access, not login"), printed)
        val absent = tmp.resolve("absent.json").toString()
        val absentPrinted = capturingStdout { present = AdminSupport.authPresent(absent) }
        assertFalse(absentPrinted.contains("unreadable"), absentPrinted)
        assertFalse(present, "proven absence is the only not-logged-in")
    }

    @Test
    fun `a denied credential file counts as configured for sign-in planning - DR-70`(@TempDir tmp: Path) {
        val dir = Files.createDirectories(tmp.resolve("auth"))
        val credential = dir.resolve("auth.json")
        Files.writeString(credential, """{"access_token":"tok"}""")
        val none = EnvReader { null }
        val configured = capturingStdout {
            assertTrue(
                withDenied(dir) { LoginIo().credentialConfigured("codex", oauthProvider(credential), none) },
                "unreadable-but-present must count as configured",
            )
        }
        assertTrue(configured.contains("fix access, not login"), configured)
        assertFalse(LoginIo().credentialConfigured("codex", oauthProvider(tmp.resolve("absent.json")), none))
    }

    /** A loopback /health so DaemonProbe.healthVersion answers and stopIfRunning reaches the key
     *  branch at all — without it the verb short-circuits on "nothing is running" and the arm
     *  would pass over code it never entered. */
    private fun runningDaemon(): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/health") { ex ->
            val bytes = """{"version":"test-daemon"}""".toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        return server
    }

    private fun stateEnv(stateDir: Path) = EnvReader { name ->
        if (name == "CLAUDEX_STATE_DIR") stateDir.toString() else null
    }

    // DR-174: `splice restart` printed "mgmt-key not found at <path>" for a key it could not READ,
    // because AdminSupport.mgmtKey collapsed AccessDenied and absence into one null. The two states
    // have opposite remedies — one chmod versus a re-mint the operator cannot even perform while
    // the daemon holds the old key in memory — so the arm asserts the SENTENCE, not just the
    // refusal: both states correctly refuse to stop, and only the wording tells them apart.
    @Test
    fun `an unreadable mgmt key is not a missing one on the restart path - DR-174`(@TempDir tmp: Path) {
        val stateDir = Files.createDirectories(tmp.resolve("state"))
        Files.writeString(stateDir.resolve("mgmt-key"), "the-real-key")
        val server = runningDaemon()
        val port = server.address.port
        var stopped = true
        val printed = try {
            capturingStdout {
                stopped = withDenied(stateDir) {
                    RestartCommand().stopIfRunning(port, emptyList(), stateEnv(stateDir))
                }
            }
        } finally {
            server.stop(0)
        }

        assertFalse(stopped, "a key it cannot read is still not a key it can stop with")
        assertTrue(printed.contains("unreadable"), printed)
        assertTrue(
            printed.contains("nothing needs re-minting"),
            "the operator must be sent to permissions, not to re-create a key that exists: $printed",
        )
        assertFalse(
            printed.contains("not found"),
            "an existing key must never be reported as missing: $printed",
        )
    }

    @Test
    fun `a genuinely absent mgmt key still reports not found - DR-174 control`(@TempDir tmp: Path) {
        val stateDir = Files.createDirectories(tmp.resolve("state"))
        val server = runningDaemon()
        val port = server.address.port
        var stopped = true
        val printed = try {
            capturingStdout { stopped = RestartCommand().stopIfRunning(port, emptyList(), stateEnv(stateDir)) }
        } finally {
            server.stop(0)
        }

        assertFalse(stopped, printed)
        // The control that keeps the fix from becoming "call everything unreadable": proven absence
        // must keep its own, different sentence, or the arm above would pass on a constant string.
        assertTrue(printed.contains("not found"), printed)
        assertFalse(printed.contains("unreadable"), printed)
    }

    @Test
    fun `the three-way mgmt key read keeps absence and denial apart - DR-174`(@TempDir tmp: Path) {
        val stateDir = Files.createDirectories(tmp.resolve("state"))
        val env = stateEnv(stateDir)
        assertTrue(AdminSupport.readMgmtKey(env) is MgmtKeyRead.Absent, "an unminted key is Absent")

        Files.writeString(stateDir.resolve("mgmt-key"), "  the-real-key  ")
        assertEquals(
            "the-real-key",
            (AdminSupport.readMgmtKey(env) as MgmtKeyRead.Present).key,
            "a readable key is Present and trimmed",
        )

        val denied = withDenied(stateDir) { AdminSupport.readMgmtKey(env) }
        assertTrue(denied is MgmtKeyRead.Unreadable, "a key one chmod away is Unreadable, never Absent")
        // Asserted on what the reason CONTAINS, not on the key being absent from it: a filesystem
        // exception carries the path and never the file's bytes, so "the key is not in this string"
        // is a test that cannot fail. What is falsifiable — and what the caller's message needs —
        // is that the reason is a real rendered diagnostic naming the path, not an empty string.
        assertTrue(
            (denied as MgmtKeyRead.Unreadable).reason.contains("mgmt-key"),
            "the reason must name what could not be read: '${denied.reason}'",
        )

        Files.writeString(stateDir.resolve("mgmt-key"), "")
        assertTrue(
            AdminSupport.readMgmtKey(env) is MgmtKeyRead.Absent,
            "a zero-byte key is a half-written mint, not a permissions problem",
        )
    }

    // user.home saved/restored (the AdminSupportTest java.class.path idiom); under a test JVM the
    // class resource is a file: URL, so selfJar() always reaches the installed-copy branch.
    @Test
    fun `an unreadable installed jar is still the installed jar - DR-70`(@TempDir tmp: Path) {
        val savedHome = System.getProperty("user.home")
        try {
            System.setProperty("user.home", tmp.toString())
            val spliceDir = Files.createDirectories(tmp.resolve(".local/share/splice"))
            val jar = spliceDir.resolve("splice.jar")
            Files.writeString(jar, "not really a jar")
            var located: Path? = null
            val printed = capturingStdout { located = withDenied(spliceDir) { AdminSupport.selfJar() } }
            assertEquals(jar, located, "unreadable is not a dev build")
            assertTrue(printed.contains("unreadable"), printed)

            Files.delete(jar)
            assertNull(AdminSupport.selfJar(), "proven absence is the only dev-build fallthrough")
        } finally {
            System.setProperty("user.home", savedHome)
        }
    }
}
