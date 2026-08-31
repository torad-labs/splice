// DR-70 absence-class arms at CLI assembly (the DR-59 posture): denied access to a credential
// file is NOT logged-out — status/setup/login must treat it as present/configured and say the
// real remedy out loud, never route the operator into a redundant (and possibly destructive)
// re-login. Same law for the installed jar: unreadable is not "not installed".
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.LoginIo
import splice.app.cli.AdminSupport
import splice.core.topology.AuthConfig
import splice.core.topology.Dialect
import splice.core.topology.ProviderConfig
import splice.core.util.EnvReader
import java.io.ByteArrayOutputStream
import java.io.PrintStream
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
