// NEW: V4-424 — `splice doctor` gives the fix boot gives for a splice.toml type error (Marlin's walk of
// ef86845f3: boot said to quote the value, doctor pointed at a source-tree path and said to delete the
// config), and a syntax error's advice names no source path and never says to delete the file.
package splice.diagnostics.v4424

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.testing.TestPorts
import splice.diagnostics.doctor.DoctorTestPorts
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

private val TYPED_ERROR = """
    [providers.ex]
    dialect = "openai-chat"
    base_url = "https://api.example.com/v1"
    auth = { kind = "api-key", env = "EX_KEY" }

    [[providers.ex.models]]
    id = "m1"
    context_window = 128000

    [heads.ex]
    provider = "ex"
    port = 8801
    pinned_model = "m1"

    [heads.ex.overrides]
    trace = true
""".trimIndent() + "\n"

class DoctorTypeFixTest {
    @TempDir
    lateinit var tmp: Path

    @Test
    fun `a type error reads the key, the line, the expected type and boot's own fix`() {
        val out = doctor(TYPED_ERROR)
        assertTrue(out.contains("heads.ex.overrides.trace at line 16 expects quoted string"), out)
        assertTrue(out.contains("put the value in double quotes, as in trace = \"...\""), out)
        assertNoSourcePathOrDelete(out)
    }

    @Test
    fun `a syntax error names the sample a release user can reach, not a source path or deleting the file`() {
        val out = doctor("[daemon\ncontrol_port = nope")
        assertTrue(out.contains("does not parse"), out)
        assertTrue(out.contains("splice.example.toml, is linked from splice's README"), out)
        assertNoSourcePathOrDelete(out)
    }

    private fun assertNoSourcePathOrDelete(out: String) {
        assertFalse(out.contains("app/src/main/resources"), out)
        assertFalse(out.contains("delete it"), out)
    }

    /** Doctor over [toml] in a hermetic home: its own config dir, state dir and control port, so no
     *  daemon on this machine is asked. */
    private fun doctor(toml: String): String {
        val configDir = Files.createDirectories(tmp.resolve("config").resolve("splice"))
        Files.writeString(configDir.resolve("splice.toml"), toml)
        val bin = Files.createDirectories(tmp.resolve("bin"))
        val env = mapOf(
            "XDG_CONFIG_HOME" to tmp.resolve("config").toString(),
            "SPLICE_BIN_DIR" to bin.toString(),
            "SPLICE_SHARE_DIR" to Files.createDirectories(tmp.resolve("share")).toString(),
            "PATH" to bin.toString(),
            "CLAUDEX_STATE_DIR" to Files.createDirectories(tmp.resolve("state")).toString(),
            "SPLICE_CONTROL_PORT" to TestPorts.reserve().toString(),
        )
        val buffer = ByteArrayOutputStream()
        val original = System.out
        System.setOut(PrintStream(buffer, true, Charsets.UTF_8))
        try {
            DoctorTestPorts.doctor().doctor { env[it] }
        } finally {
            System.setOut(original)
        }
        return buffer.toString(Charsets.UTF_8)
    }
}
