// NEW: V4-232: the row `splice setup` writes for a local runtime presents its model to the client as a
// Claude model it knows (client_model), and the file it writes parses back through the real loader
// with that key on the row, so a fresh runtime head prints no `[claude-code:unrecognized_model]` line.
package campaign.v4232

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.configuration.add.AddChecks
import splice.configuration.add.AddCommand
import splice.configuration.add.AddHttp
import splice.configuration.add.AddHttpReply
import splice.configuration.add.AddPorts
import splice.configuration.add.RuntimeHead
import splice.configuration.add.RuntimeHeadAdd
import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader
import splice.topology.TopologyLoader
import java.nio.file.Files
import java.nio.file.Path

private const val BASE = "http://127.0.0.1:8099/v1"

class RuntimeRowPresentedTest {
    private val lines = mutableListOf<String>()

    private val llamaServer = AddHttp { method, url, _, _ ->
        when ("$method $url") {
            "GET $BASE" -> AddHttpReply(404, "{}")
            "GET $BASE/models" -> AddHttpReply(200, """{"data":[{"id":"/rig/heads/bonsai-2-27b/model.gguf"}]}""")
            else -> null
        }
    }

    private fun adder(): RuntimeHeadAdd {
        val out = TerminalOutput { lines += it }
        val ports = AddPorts(
            login = { _, _, _ -> error("a runtime head must never start a sign-in") },
            install = { _, _ -> true },
            restart = { true },
            daemonUp = { true },
            prompt = { _, default -> default },
        )
        return RuntimeHeadAdd(out, AddCommand(out, out, AddChecks(out, llamaServer), ports))
    }

    @Test
    fun `a fresh runtime row is presented to the client as claude-sonnet-4-6, and parses back so`(@TempDir home: Path) {
        val env = EnvReader { name -> if (name == "SPLICE_CONFIG") home.resolve("splice.toml").toString() else null }
        TopologyLoader.loadOrMaterialize(TopologyLoader.configPath(env))
        val head = RuntimeHead(
            key = "bonsai",
            describedBy = "rig describe bonsai-2-27b",
            baseUrl = BASE,
            modelId = "bonsai-2-27b",
            modelLabel = "Bonsai 2 27B",
            contextWindow = 245_760L,
            emitReasoningEffort = false,
            slotAffinity = true,
            anyModelId = true,
        )

        assertTrue(runBlocking { adder().add(head, env) }, lines.joinToString("\n"))
        val text = Files.readString(TopologyLoader.configPath(env))
        val model = TopologyLoader.parse(text).providers.getValue("bonsai").models.single()

        assertEquals("bonsai-2-27b", model.id, "the wire id stays the runtime's")
        assertEquals("claude-sonnet-4-6", model.clientModel)
        assertEquals(245_760L, model.contextWindow)
        assertTrue("client_model = \"claude-sonnet-4-6\"" in text, text)
    }
}
