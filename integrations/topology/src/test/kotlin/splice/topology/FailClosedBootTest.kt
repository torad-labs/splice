// Fail-closed boot: a splice.toml with findings refuses to boot and lists ALL of them at once, each naming a key
// path and a line and never a value. A file that is fixed boots.
package splice.topology

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.SafeFailureText
import splice.core.util.TopologyRefusal
import java.nio.file.Files
import java.nio.file.Path

private const val SECRET = "OPERATOR_VALUE_MUST_NOT_LEAVE_FILE"

class FailClosedBootTest {
    private fun config(root: Path, text: String): Path =
        root.resolve("splice.toml").also { Files.writeString(it, text.trimIndent() + "\n") }

    private val provider = """
        [providers.demo]
        dialect = "openai-chat"
        base_url = "https://demo.invalid"
        auth = { kind = "api-key", env = "DEMO_KEY" }
        [providers.demo.extra_headers]
        x_probe = "$SECRET"
    """

    @Test
    fun `two findings in one file are both listed with their lines and none of the values`(@TempDir root: Path) {
        val path = config(
            root,
            provider + """
            [heads.one]
            provider = "missing"
            port = 3101
            discovery_prefix = "claude-one--"
            pinned_model = "m"
            [heads.two]
            provider = "demo"
            port = 3101
            discovery_prefix = "claude-two--"
            pinned_model = "m"
            """,
        )

        val refusal = assertThrows(TopologyRefusal::class.java) { TopologyLoader.loadForBoot(path) }

        val said = SafeFailureText.render(refusal)
        assertTrue(said.contains("heads.one.provider (line 9)"), said)
        assertTrue(said.contains("heads.one.port (line 10)") && said.contains("heads.two.port (line 15)"), said)
        assertFalse(said.contains(SECRET), "no value reaches the refusal")
        assertTrue(refusal.findings.size >= 3, refusal.findings.toString())
    }

    @Test
    fun `a structural finding and a topology finding are listed together`(@TempDir root: Path) {
        val path = config(
            root,
            provider + """
            [heads.one]
            provider = "missing"
            port = 3101
            discovery_prefix = "claude-one--"
            pinned_model = "m"
            [heads.one]
            port = 3102
            """,
        )

        val refusal = assertThrows(TopologyRefusal::class.java) { TopologyLoader.loadForBoot(path) }

        val said = refusal.message.orEmpty()
        assertTrue(said.contains("is defined twice"), said)
        assertTrue(said.contains("heads.one.provider"), said)
    }

    @Test
    fun `a fixed file boots`(@TempDir root: Path) {
        val path = config(
            root,
            provider + """
            [heads.one]
            provider = "demo"
            port = 3101
            discovery_prefix = "claude-one--"
            pinned_model = "m"
            """,
        )

        val loaded = TopologyLoader.loadForBoot(path)

        assertEquals(setOf("one"), loaded.topology.heads.keys)
    }

    @Test
    fun `every wrong type and unknown key is listed at once with its line and no value`(@TempDir root: Path) {
        val path = config(
            root,
            provider.replace("x_probe = \"$SECRET\"", "x_probe = { nested = \"$SECRET\" }") + """
            [heads.one]
            provider = "demo"
            port = true
            discovery_prefix = "claude-one--"
            pinned_model = "m"
            bogus_key = 1
            """,
        )

        val refusal = assertThrows(TopologyRefusal::class.java) { TopologyLoader.loadForBoot(path) }

        val said = refusal.message.orEmpty()
        assertTrue(said.contains("providers.demo.extra_headers.x_probe (line 6)"), said)
        assertTrue(said.contains("heads.one.port (line 10)"), said)
        assertTrue(said.contains("heads.one.bogus_key (line 13): is not a splice.toml setting"), said)
        assertFalse(said.contains(SECRET), "no value reaches the refusal")
    }
}
