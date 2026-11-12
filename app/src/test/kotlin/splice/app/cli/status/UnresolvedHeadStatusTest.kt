package splice.app.cli.status

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.terminal.CliPalette
import splice.core.terminal.ColorDepth
import splice.core.topology.AuthConfig
import splice.core.topology.ClaudeWrapperConfig
import splice.core.topology.Dialect
import splice.core.topology.HeadConfig
import splice.core.topology.ProviderConfig
import splice.core.topology.Topology
import splice.core.util.EnvReader
import java.nio.file.Files
import java.nio.file.Path

/** With `[heads.grok] provider = "no-such-provider"` the table must not silently drop the head the
 *  heads-to-providers join cannot resolve. Each configured head now has a row, and so does every
 *  head /health names failed, even one the local topology does not know. */
class UnresolvedHeadStatusTest {
    private val reason = "unknown provider 'no-such-provider'"

    private fun head(provider: String, command: String, port: Int) = HeadConfig(
        provider = provider,
        port = port,
        discoveryPrefix = "test--",
        pinnedModel = "test-model",
        claude = ClaudeWrapperConfig(command = command),
    )

    private val topology = Topology(
        providers = mapOf(
            "p" to ProviderConfig(
                dialect = Dialect.OPENAI_CHAT,
                baseUrl = "https://example.invalid",
                auth = AuthConfig(kind = "api-key", env = "TEST_STATUS_KEY"),
            ),
        ),
        heads = linkedMapOf(
            "claudex" to head("p", "claudex", 3102),
            "claude-grok" to head("no-such-provider", "claude-grok", 3104),
        ),
    )

    private fun readyEnv(bin: Path): EnvReader {
        Files.createSymbolicLink(bin.resolve("claudex"), bin.resolve("target"))
        val map = mapOf("SPLICE_BIN_DIR" to bin.toString(), "TEST_STATUS_KEY" to "sk-present")
        return EnvReader { map[it] }
    }

    private val table = StatusTable(CliPalette(ColorDepth.NONE))

    @Test
    fun `a head whose provider is unknown reads not running with the daemon's reason`(@TempDir bin: Path) {
        val lines = table.lines(topology, readyEnv(bin), StatusReadings(failedHeads = mapOf("claude-grok" to reason)))

        assertEquals(3, lines.size, lines.joinToString("\n"))
        val grok = lines.single { it.contains("claude-grok") }
        assertTrue(grok.contains("3104") && grok.contains("not running: $reason"), grok)
        assertTrue(lines.single { it.contains("claudex") }.trimEnd().endsWith("ready"), lines.joinToString("\n"))
    }

    @Test
    fun `an unresolved head still reads not running when the daemon is not up`(@TempDir bin: Path) {
        val grok = table.lines(topology, readyEnv(bin)).single { it.contains("claude-grok") }

        assertTrue(grok.contains("not running: $reason"), grok)
    }

    @Test
    fun `a head health names failed that the local topology does not know gets its own row`(@TempDir bin: Path) {
        val lines = table.lines(
            topology,
            readyEnv(bin),
            StatusReadings(failedHeads = mapOf("claude-ghost" to "port 3999 in use")),
        )

        val ghost = lines.single { it.contains("claude-ghost") }
        assertTrue(ghost.contains("not running: port 3999 in use"), ghost)
        assertEquals(4, lines.size, lines.joinToString("\n"))
    }
}
