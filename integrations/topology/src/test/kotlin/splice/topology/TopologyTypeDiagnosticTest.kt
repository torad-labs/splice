// A malformed topology must identify the key, line and required shape without repeating
// operator-owned values. The daemon's actual failure renderer must carry the safe diagnosis too.
package splice.topology

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.SafeFailureText
import java.nio.file.Files
import java.nio.file.Path

private const val MARKER = "OPERATOR_VALUE_MUST_NOT_LEAVE_FILE"

class TopologyTypeDiagnosticTest {
    private fun failure(root: Path, text: String): Exception {
        val config = root.resolve("splice.toml")
        Files.writeString(config, text.trimIndent() + "\n")
        return assertThrows(Exception::class.java) { TopologyLoader.loadOrMaterialize(config) }
    }

    @Test
    fun `a boolean in string overrides names its full key and line`(@TempDir root: Path) {
        val error = failure(
            root,
            """
            [heads.x]
            provider = "p"
            port = 3101
            discovery_prefix = "claude-x--"
            pinned_model = "m"
            [heads.x.overrides]
            trace = true
            """,
        )

        val rendered = SafeFailureText.render(error)
        assertTrue(rendered.contains("heads.x.overrides.trace"), rendered)
        assertTrue(rendered.contains("line 7"), rendered)
        assertTrue(rendered.contains("quoted string"), rendered)
    }

    @Test
    fun `a table in extra headers names the key but never its value`(@TempDir root: Path) {
        val error = failure(
            root,
            """
            [providers.demo]
            dialect = "openai-chat"
            base_url = "https://demo.invalid"
            auth = { kind = "api-key", env = "DEMO_KEY" }
            [providers.demo.extra_headers]
            x_probe = { nested = "$MARKER" }
            """,
        )

        val rendered = SafeFailureText.render(error)
        assertTrue(rendered.contains("providers.demo.extra_headers.x_probe"), rendered)
        assertTrue(rendered.contains("line 6"), rendered)
        assertTrue(rendered.contains("quoted string"), rendered)
        assertFalse(rendered.contains(MARKER), "a header value never reaches daemon.log")
        assertFalse(error.message.orEmpty().contains(MARKER), "even a direct caller receives no raw parser text")
    }

    @Test
    fun `a quoted provider table still names its exact nested key`(@TempDir root: Path) {
        val error = failure(
            root,
            """
            [providers."demo.part"]
            dialect = "openai-chat"
            base_url = "https://demo.invalid"
            auth = { kind = "api-key", env = "DEMO_KEY" }
            [providers."demo.part".extra_headers]
            x_probe = { nested = "$MARKER" }
            """,
        )

        val rendered = SafeFailureText.render(error)
        assertTrue(rendered.contains("providers.\"demo.part\".extra_headers.x_probe"), rendered)
        assertTrue(rendered.contains("line 6"), rendered)
        assertTrue(rendered.contains("quoted string"), rendered)
        assertFalse(rendered.contains(MARKER))
    }

    @Test
    fun `a quoted header key is identified without its table value`(@TempDir root: Path) {
        val error = failure(
            root,
            """
            [providers.demo]
            dialect = "openai-chat"
            base_url = "https://demo.invalid"
            auth = { kind = "api-key", env = "DEMO_KEY" }
            [providers.demo.extra_headers]
            "x-probe" = { nested = "$MARKER" }
            """,
        )

        val rendered = SafeFailureText.render(error)
        assertTrue(rendered.contains("providers.demo.extra_headers.\"x-probe\""), rendered)
        assertTrue(rendered.contains("line 6"), rendered)
        assertTrue(rendered.contains("quoted string"), rendered)
        assertFalse(rendered.contains(MARKER))
    }

    @Test
    fun `an inline table in extra headers names the nested key without its value`(@TempDir root: Path) {
        val error = failure(
            root,
            """
            [providers.demo]
            dialect = "openai-chat"
            base_url = "https://demo.invalid"
            auth = { kind = "api-key", env = "DEMO_KEY" }
            extra_headers = { x_probe = { nested = "$MARKER" } }
            """,
        )

        val rendered = SafeFailureText.render(error)
        assertTrue(rendered.contains("providers.demo.extra_headers.x_probe"), rendered)
        assertTrue(rendered.contains("line 5"), rendered)
        assertTrue(rendered.contains("quoted string"), rendered)
        assertFalse(rendered.contains(MARKER))
    }

    @Test
    fun `a string in numeric port names the expected integer without echoing it`(@TempDir root: Path) {
        val error = failure(
            root,
            """
            [heads.x]
            provider = "p"
            port = "$MARKER"
            discovery_prefix = "claude-x--"
            pinned_model = "m"
            """,
        )

        val rendered = SafeFailureText.render(error)
        assertTrue(rendered.contains("heads.x.port"), rendered)
        assertTrue(rendered.contains("line 3"), rendered)
        assertTrue(rendered.contains("integer"), rendered)
        assertFalse(rendered.contains(MARKER))
        assertFalse(error.message.orEmpty().contains(MARKER))
    }
}
