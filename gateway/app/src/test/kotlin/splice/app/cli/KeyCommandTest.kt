// NEW: `splice key set|list|unset` over an injected hermetic KeyStore. The masked console path
// is untestable here (System.console is null under surefire) — --value and --stdin are the
// scripted routes, and the console-null branch must point at them.
package splice.app.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.KeyStore
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class KeyCommandTest {

    private fun store(tmp: Path) = KeyStore(tmp.resolve("keys.toml"))

    // DR-40 gap 2 (codex): in a CLI process nothing installs DaemonLog, so a store built with the
    // old default sink swallowed the UNREADABLE warning and `splice key list` reported "no keys
    // stored" against a corrupt store. The CLI default now warns through cliStoreSink() -> stderr;
    // this drives `key list` with that same production sink and only the PATH swapped for hermeticity.
    @Test
    fun `key list surfaces an unreadable store on stderr, not a silent empty - DR-40`(@TempDir tmp: Path) {
        val externalDir = Files.createDirectories(tmp.resolve("external"))
        val path = externalDir.resolve("keys.toml")
        KeyStore(path).write("OPENROUTER_API_KEY", "sk-a")
        Files.setPosixFilePermissions(externalDir, PosixFilePermissions.fromString("---------"))
        val stderr = ByteArrayOutputStream()
        val realErr = System.err
        System.setErr(PrintStream(stderr, true))
        try {
            val ok = KeyCommand().key(listOf("list"), KeyStore(path, log = KeyCommand().cliStoreSink()))
            assertTrue(ok, "list still succeeds — degraded display, loud diagnosis")
        } finally {
            System.setErr(realErr)
            Files.setPosixFilePermissions(externalDir, PosixFilePermissions.fromString("rwx------"))
        }
        assertTrue(
            stderr.toString().contains("UNREADABLE"),
            "the CLI must surface the corrupt-vs-empty warning on stderr, got '${stderr.toString().trim()}'",
        )
    }

    @Test
    fun `set --value stores and list shows the name`(@TempDir tmp: Path) {
        val s = store(tmp)
        assertTrue(KeyCommand().key(listOf("set", "OPENROUTER_API_KEY", "--value", "sk-or-abc"), s))
        assertEquals("sk-or-abc", s.read("OPENROUTER_API_KEY"))
        assertTrue(KeyCommand().key(listOf("list"), s))
    }

    @Test
    fun `set without value and without console fails with guidance`(@TempDir tmp: Path) {
        val s = store(tmp)
        assertFalse(KeyCommand().key(listOf("set", "OPENROUTER_API_KEY"), s))
        assertEquals(null, s.read("OPENROUTER_API_KEY"))
    }

    @Test
    fun `set rejects invalid env names`(@TempDir tmp: Path) {
        val s = store(tmp)
        assertFalse(KeyCommand().key(listOf("set", "not-a-name", "--value", "x"), s))
        assertTrue(s.names().isEmpty())
    }

    @Test
    fun `unset removes and reports`(@TempDir tmp: Path) {
        val s = store(tmp)
        KeyCommand().key(listOf("set", "OPENROUTER_API_KEY", "--value", "sk-or-abc"), s)
        assertTrue(KeyCommand().key(listOf("unset", "OPENROUTER_API_KEY"), s))
        assertEquals(null, s.read("OPENROUTER_API_KEY"))
    }

    @Test
    fun `unknown subcommand is a usage error`(@TempDir tmp: Path) {
        assertFalse(KeyCommand().key(listOf("frobnicate"), store(tmp)))
    }
}
