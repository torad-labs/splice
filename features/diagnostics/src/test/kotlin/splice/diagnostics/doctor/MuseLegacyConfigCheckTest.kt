package splice.diagnostics.doctor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.topology.TopologyLoader
import java.nio.file.Path

class MuseLegacyConfigCheckTest {
    @Test
    fun `doctor names each stale Muse declaration once and says how to migrate`(@TempDir root: Path) {
        val rows = warnings(root, "anthropic-passthrough", "https://api.meta.ai")
        assertEquals(listOf("muse-dialect:muse", "muse-base-url:muse"), rows.map { it.name })
        assertTrue(rows.all { it.status == CheckStatus.WARN })
        assertTrue(rows.first().detail.contains("stale"))
        assertTrue(requireNotNull(rows.first().fix).contains("dialect = \"openai-responses\""))
        assertTrue(rows.last().detail.contains("stale"))
        assertTrue(requireNotNull(rows.last().fix).contains("append /v1 to [providers.muse].base_url"))
    }

    @Test
    fun `doctor stays quiet as each Muse declaration is fixed`(@TempDir root: Path) {
        assertEquals(
            listOf("muse-base-url:muse"),
            warnings(root, "openai-responses", "https://api.meta.ai").map { it.name },
        )
        assertEquals(
            listOf("muse-dialect:muse"),
            warnings(root, "anthropic-passthrough", "https://api.meta.ai/v1").map { it.name },
        )
        assertEquals(
            emptyList<String>(),
            warnings(root, "openai-responses", "https://api.meta.ai/v1").map { it.name },
        )
    }

    private fun warnings(root: Path, dialect: String, baseUrl: String) = DoctorTestPorts.configChecks()
        .configurationChecks(
            DoctorTopology.Parsed(
                TopologyLoader.parse(
                    """
                    [providers.muse]
                    dialect = "$dialect"
                    base_url = "$baseUrl"
                    auth = { kind = "muse-oauth" }
                    [[providers.muse.models]]
                    id = "muse-spark-1.3[1m]"
                    context_window = 1000000
                    [heads.claude-muse]
                    provider = "muse"
                    port = 31393
                    discovery_prefix = "claude-muse--"
                    pinned_model = "muse-spark-1.3[1m]"
                    """.trimIndent(),
                ),
            ),
            root.resolve("splice.toml"),
        ).filter { it.name.startsWith("muse-") }
}
