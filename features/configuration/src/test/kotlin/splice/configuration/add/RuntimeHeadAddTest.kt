// NEW: the runtime-described head (`splice setup`'s local-model step) through `splice add`'s own
// machinery, against a hermetic SPLICE_CONFIG and a fake network: the row it writes parses through
// the real loader with the quirks and window rig described, the placeholder key is planted only when
// absent, and a refused add leaves splice.toml and keys.toml as it found them.
package splice.configuration.add

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.KeyStore
import splice.core.config.KeyStorePath
import splice.core.terminal.TerminalOutput
import splice.core.topology.Dialect
import splice.core.util.EnvReader
import splice.topology.TopologyLoader
import java.nio.file.Files
import java.nio.file.Path

private const val BASE = "http://127.0.0.1:8099/v1"
private const val KEY_ENV = "BONSAI_API_KEY"

class RuntimeHeadAddTest {

    private val lines = mutableListOf<String>()
    private var restarts = 0

    private fun env(home: Path) = EnvReader { name ->
        if (name == "SPLICE_CONFIG") home.resolve("splice.toml").toString() else null
    }

    /** llama-server lists the FILE it serves, not the id a row sends — the case any_model_id is for. */
    private val llamaServer = AddHttp { method, url, _, _ ->
        when ("$method $url") {
            "GET $BASE" -> AddHttpReply(404, "{}")
            "GET $BASE/models" -> AddHttpReply(200, """{"data":[{"id":"/rig/heads/bonsai-2-27b/model.gguf"}]}""")
            else -> null
        }
    }

    private fun adder(http: AddHttp = llamaServer, daemonUp: Boolean = true): RuntimeHeadAdd {
        val out = TerminalOutput { lines += it }
        val ports = AddPorts(
            login = { _, _, _ -> error("a runtime head must never start a sign-in") },
            install = { _, _ -> true },
            restart = {
                restarts += 1
                true
            },
            daemonUp = { daemonUp },
            prompt = { _, default -> default },
        )
        return RuntimeHeadAdd(out, AddCommand(out, out, AddChecks(out, http), ports))
    }

    private fun head(anyModelId: Boolean = true) = RuntimeHead(
        key = "bonsai",
        describedBy = "rig describe bonsai-2-27b",
        baseUrl = BASE,
        modelId = "bonsai-2-27b",
        modelLabel = "Bonsai 2 27B",
        contextWindow = 245_760L,
        emitReasoningEffort = false,
        slotAffinity = true,
        anyModelId = anyModelId,
    )

    private fun starter(env: EnvReader): String {
        val path = TopologyLoader.configPath(env)
        TopologyLoader.loadOrMaterialize(path)
        return Files.readString(path)
    }

    private fun keys(env: EnvReader) = KeyStore(KeyStorePath.defaultPath(env))

    @Test
    fun `the described head lands as one parseable row with rig's quirks and window`(@TempDir home: Path) {
        val env = env(home)
        val before = starter(env)
        assertTrue(runBlocking { adder().add(head(), env) }, lines.joinToString("\n"))
        val text = Files.readString(TopologyLoader.configPath(env))
        assertTrue(text.startsWith(before.trimEnd('\n')), "the starter is a prefix of the new file")
        val topology = TopologyLoader.parse(text)
        val provider = topology.providers.getValue("bonsai")
        assertEquals(Dialect.OPENAI_CHAT, provider.dialect)
        assertEquals(BASE, provider.baseUrl)
        assertEquals("api-key", provider.auth.kind)
        assertEquals(KEY_ENV, provider.auth.env)
        assertEquals(false, provider.quirks.reasoningEffort, "reasoning_effort means EMIT; the server rejects it")
        assertEquals(true, provider.quirks.slotAffinity)
        val model = provider.models.single()
        assertEquals("bonsai-2-27b", model.id)
        assertEquals("Bonsai 2 27B", model.label)
        assertEquals(245_760L, model.contextWindow)
        val row = topology.heads.getValue("bonsai")
        assertEquals("bonsai", row.provider)
        assertEquals("bonsai-2-27b", row.pinnedModel)
        assertEquals("claude-bonsai", row.claude.command)
        assertEquals(245_760L, row.contextWindow)
        assertTrue("# Added by `splice setup`." in text, text)
        assertEquals(1, restarts, "the daemon was up, so the add restarted it once")
        assertTrue(lines.any { "trusted: the server answers any model id" in it }, lines.joinToString("\n"))
    }

    @Test
    fun `the placeholder key is planted when absent`(@TempDir home: Path) {
        val env = env(home)
        starter(env)
        assertTrue(runBlocking { adder().add(head(), env) })
        assertEquals("local-runtime-no-auth", keys(env).read(KEY_ENV))
    }

    @Test
    fun `an operator's own key is left alone`(@TempDir home: Path) {
        val env = env(home)
        starter(env)
        keys(env).write(KEY_ENV, "operator-value")
        assertTrue(runBlocking { adder().add(head(), env) })
        assertEquals("operator-value", keys(env).read(KEY_ENV))
    }

    @Test
    fun `a refused add writes nothing and takes its placeholder back`(@TempDir home: Path) {
        val env = env(home)
        val before = starter(env)
        // An authoritative list that does not name the id: the models check refuses the row.
        assertFalse(runBlocking { adder().add(head(anyModelId = false), env) })
        assertEquals(before, Files.readString(TopologyLoader.configPath(env)))
        assertNull(keys(env).read(KEY_ENV), "the placeholder this call planted is gone")
        assertEquals(0, restarts)
    }

    @Test
    fun `a failed restart keeps the placeholder the saved row needs`(@TempDir home: Path) {
        val env = env(home)
        starter(env)
        val out = TerminalOutput { lines += it }
        val ports = AddPorts(
            login = { _, _, _ -> error("no sign-in") },
            install = { _, _ -> true },
            restart = { false },
            daemonUp = { true },
            prompt = { _, default -> default },
        )
        val add = RuntimeHeadAdd(out, AddCommand(out, out, AddChecks(out, llamaServer), ports))
        assertFalse(runBlocking { add.add(head(), env) }, "a restart that failed is AddCommand's false")
        assertTrue("bonsai" in TopologyLoader.parse(Files.readString(TopologyLoader.configPath(env))).providers)
        assertEquals("local-runtime-no-auth", keys(env).read(KEY_ENV))
    }
}
