// NEW: V4-132 — the wiring pin for the console's new accounts port, in the shape
// splice.app.ConsoleWiringPinTest already established: the assignment happens by plain
// property mutation after construction (ConsolePorts.accounts is settable, never a constructor
// arg — the width ratchet's ceiling), so nothing in the type system connects the daemon to it.
// Delete the assignment and every /api/auth/{head}/login, /login/{id}, DELETE/PATCH
// .../accounts/{label} route keeps compiling and keeps answering its named 5xx forever. The
// assertion is on the SOURCE for the same reason ConsoleWiringPinTest's is: the property is
// public and settable from anywhere, so no runtime observation tells "ConsoleWiring set it" apart
// from "something else did".
package splice.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.app.head.HeadCredentialNames
import splice.head.HeadDeps
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class ConsoleWiringAccountsPinTest {

    @Test
    fun `the control plane wires the console's accounts port`() {
        assertTrue(
            consoleWiringSource().contains("srv.ports.accounts = ConsoleAccountsImpl()"),
            "ConsoleWiring must assign `srv.ports.accounts`, or the console's login/accounts " +
                "routes answer their unwired 5xx forever, whatever the daemon's real state is",
        )
    }

    @Test
    fun `the daemon wires current credential account names into every assembled head`() {
        // These assignments happen after construction, so an omitted link would still compile.
        assertTrue(
            source("app/src/main/kotlin/splice/app/Daemon.kt")
                .contains("it.credentialAccountNames = controlPlane.credentialAccountNames"),
            "the daemon must hand its native account resolver to the head factory",
        )
        assertTrue(
            source("app/src/main/kotlin/splice/app/head/HeadServerFactory.kt").contains(
                "credentialAccountNames = HeadCredentialNames(key, credentialAccountNames, sentCredentials),",
            ),
            "each head's account bundle must retain the daemon resolver and report under its own key",
        )
        assertTrue(
            source("app/src/main/kotlin/splice/app/ControlPlane.kt")
                .contains("claudeLoginOwner?.accountForCredential(key)"),
            "the resolver must consult the wired owner at request time, not capture an unwired null",
        )
    }

    /** 2026-10-04: without these links every head's sends go unheard, the console never learns which login carries
     *  a Claude head, and its usage falls back to the command's own folder with nothing failing. */
    @Test
    fun `the daemon wires each head's sent credentials to the native login owner`() {
        assertTrue(
            source("app/src/main/kotlin/splice/app/Daemon.kt")
                .contains("it.sentCredentials = controlPlane.sentCredentials"),
            "the daemon must hand the control plane's sent-credential sink to the head factory",
        )
        assertTrue(
            source("app/src/main/kotlin/splice/app/ControlPlane.kt")
                .contains("claudeLoginOwner?.sent(head, key)"),
            "the sink must reach the wired owner at send time, not capture an unwired null",
        )
    }

    @Test
    fun `a head's credential names report each sent digest under that head's key and name through the daemon`() {
        val heard = mutableListOf<Pair<String, String>>()
        val names = HeadCredentialNames(
            "synthetic-head",
            HeadDeps.CredentialAccountNames { key -> "proved@example.invalid".takeIf { key == "synthetic-digest" } },
            HeadSentCredentials { head, key -> heard += head to key },
        )
        names.sent("synthetic-digest")
        assertEquals(listOf("synthetic-head" to "synthetic-digest"), heard)
        assertEquals("proved@example.invalid", names.forCredential("synthetic-digest"))
        assertEquals(null, names.forCredential("another-digest"))
    }

    private fun consoleWiringSource(): String = source("app/src/main/kotlin/splice/app/ConsoleWiring.kt")

    /** Found by walking up from the working directory: under Gradle the cwd is the module dir and
     *  from an IDE it is the repo root, so neither is assumed. */
    private fun source(relative: String): String {
        var dir: Path? = Paths.get("").toAbsolutePath()
        while (dir != null) {
            val candidate = dir.resolve(relative)
            if (Files.exists(candidate)) return Files.readString(candidate)
            dir = dir.parent
        }
        error("$relative not found above ${Paths.get("").toAbsolutePath()}")
    }
}
