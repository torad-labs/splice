package splice.app.cli.status.v4394

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.cli.status.StatusReadings
import splice.app.cli.status.StatusTable
import splice.app.control.FailedHeads
import splice.app.control.healthFor
import splice.app.control.readinessFor
import splice.core.terminal.CliPalette
import splice.core.terminal.ColorDepth
import splice.core.topology.AuthConfig
import splice.core.topology.ClaudeWrapperConfig
import splice.core.topology.Dialect
import splice.core.topology.HeadConfig
import splice.core.topology.ProviderConfig
import splice.core.topology.Topology
import splice.core.util.EnvReader
import splice.daemonclient.DaemonProbe
import java.nio.file.Files
import java.nio.file.Path

/** V4-394: at 12:22:39 PM CT /health said ok:false, failedHeads 1 (claude-muse skipped at boot)
 *  while `splice status` printed claude-muse "ready": status judged the row from the credential
 *  and the wrapper alone. A head the daemon failed to build now reads as not running, with the
 *  daemon's own boot reason, and the other heads are unchanged. */
class FailedHeadStatusTest {
    private val reason = "provider muse (auth muse-oauth) cannot use dialect anthropic-passthrough"

    private fun head(command: String, port: Int) = HeadConfig(
        provider = "p",
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
        heads = linkedMapOf("claude-muse" to head("claude-muse", 3101), "claudex" to head("claudex", 3102)),
    )

    /** Both wrappers installed and the key present: by local state alone, both rows are ready. */
    private fun readyEnv(bin: Path): EnvReader {
        listOf("claude-muse", "claudex").forEach { Files.createSymbolicLink(bin.resolve(it), bin.resolve("target")) }
        val map = mapOf("SPLICE_BIN_DIR" to bin.toString(), "TEST_STATUS_KEY" to "sk-present")
        return EnvReader { map[it] }
    }

    @Test
    fun `a head the daemon failed to build reads as not running with its boot reason`(@TempDir bin: Path) {
        val table = StatusTable(CliPalette(ColorDepth.NONE))
        val (_, muse, codex) = table.lines(
            topology,
            readyEnv(bin),
            StatusReadings(failedHeads = mapOf("claude-muse" to reason)),
        )

        assertTrue(muse.contains("not running") && muse.contains(reason), muse)
        assertFalse(muse.contains("ready"), muse)
        assertTrue(codex.trimEnd().endsWith("ready"), codex)
        assertTrue(
            muse.trimStart().first() != codex.trimStart().first(),
            "the glyph must carry the state:\n$muse\n$codex",
        )
    }

    @Test
    fun `health names each failed head with its reason, beside the unchanged count`() {
        val failed = object : FailedHeads {
            override fun invoke(): Int = 1
            override fun reasons(): Map<String, String> = mapOf("claude-muse" to reason)
        }
        val body = healthFor(emptyMap(), readinessFor(emptyMap(), failed, configuredHeads = 1)).json()
        val health = Json.parseToJsonElement(body).jsonObject

        assertEquals("1", health.getValue("failedHeads").jsonPrimitive.content)
        val named = health.getValue("failedHeadReasons").jsonObject.getValue("claude-muse")
        assertEquals(reason, named.jsonPrimitive.content)
        assertEquals(mapOf("claude-muse" to reason), DaemonProbe.parseHealth(body).failedHeadReasons)
    }

    @Test
    fun `a healthy boot carries no reasons object, so older readers see the old shape`() {
        val body = healthFor(emptyMap(), readinessFor(emptyMap(), configuredHeads = 0)).json()

        assertNull(Json.parseToJsonElement(body).jsonObject["failedHeadReasons"], body)
        assertEquals(emptyMap<String, String>(), DaemonProbe.parseHealth(body).failedHeadReasons)
    }
}
