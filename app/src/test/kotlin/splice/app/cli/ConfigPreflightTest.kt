package splice.app.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.EnvReader
import java.nio.file.Files
import java.nio.file.Path

private const val SECRET = "OPERATOR_VALUE_MUST_NOT_LEAVE_FILE"

/** The preflight a replacing install asks the NEW jar before it stops the running daemon: this build's boot
 *  findings against the person's own splice.toml, read-only. */
class ConfigPreflightTest {
    private fun run(root: Path, text: String?): Pair<Int, String> {
        val config = root.resolve("splice.toml")
        if (text != null) Files.writeString(config, text.trimIndent() + "\n")
        val lines = mutableListOf<String>()
        val env = EnvReader { name -> if (name == "SPLICE_CONFIG") config.toString() else null }
        return ConfigPreflight(env) { line: String -> lines += line }.check() to lines.joinToString("\n")
    }

    private val demo = """
        [providers.demo]
        dialect = "openai-chat"
        base_url = "https://demo.invalid"
        auth = { kind = "api-key", env = "DEMO_KEY" }
        [providers.demo.extra_headers]
        x_probe = "$SECRET"
        [heads.one]
        provider = "demo"
        port = 3101
        discovery_prefix = "claude-one--"
        pinned_model = "m"
    """

    @Test
    fun `a splice toml with one finding exits 3 and names the key and line, never a value`(@TempDir root: Path) {
        val (code, said) = run(root, demo.replace("provider = \"demo\"", "provider = \"missing\""))

        assertEquals(CONFIG_REFUSED_EXIT, code)
        assertTrue(said.contains("heads.one.provider"), said)
        assertTrue(said.contains("line"), said)
        assertFalse(said.contains(SECRET), "no value reaches the report")
    }

    @Test
    fun `a clean splice toml exits 0 and says nothing`(@TempDir root: Path) {
        val (code, said) = run(root, demo)

        assertEquals(0, code)
        assertEquals("", said)
    }

    @Test
    fun `a dangling symlink for splice toml exits 3, because boot refuses it`(@TempDir root: Path) {
        Files.createSymbolicLink(root.resolve("splice.toml"), root.resolve("dotfiles-gone.toml"))
        val (code, said) = run(root, null)

        assertEquals(CONFIG_REFUSED_EXIT, code)
        assertTrue(said.contains("splice.toml cannot be read"), said)
    }

    @Test
    fun `no splice toml at all exits 0, and the check writes nothing`(@TempDir root: Path) {
        val (code, said) = run(root, null)

        assertEquals(0, code)
        assertEquals("", said)
        assertFalse(Files.exists(root.resolve("splice.toml")), "a preflight never materializes the starter")
    }
}
