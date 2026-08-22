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
import java.nio.file.Path

class KeyCommandTest {

    private fun store(tmp: Path) = KeyStore(tmp.resolve("keys.toml"))

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
