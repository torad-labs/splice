package splice.app.desktop.harness

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.desktop.DaemonDesktop
import splice.core.config.StatePaths
import splice.core.topology.TopologyParse
import splice.core.topology.TopologyWriteResult
import splice.core.topology.TopologyWriter
import splice.core.util.LogSink
import splice.provider.antigravity.AgyModels
import splice.provider.antigravity.AgyRead
import splice.topology.ConfigFindings
import splice.topology.ConfigRead
import splice.topology.TopologyLoader
import java.nio.file.Files
import java.nio.file.Path

class HarnessCatalogContractTest {
    @TempDir
    lateinit var tmp: Path

    @Test
    fun `a legacy config and existing console choice publish the version one launcher contract`() {
        val source = """
            [daemon]
            antigravity = false

            [providers.codex]
            dialect = "openai-responses"
            base_url = "https://example.com"
            auth = { kind = "chatgpt-oauth" }

            [providers.grok]
            dialect = "openai-chat"
            base_url = "https://example.com"
            auth = { kind = "api-key" }
        """.trimIndent()
        val paths = paths()
        Files.createDirectories(paths.stateDir)
        Files.writeString(
            paths.desktopHarnessesFile,
            """{"version":1,"families":[{"family":"openai","harness":"codex","enabled":true,"set_by":"console"}]}""",
        )
        val harnesses = DaemonDesktop(boot(source).topology, paths, LogSink {}).harnesses
        assertFalse(harnesses.boot())
        harnesses.publish()
        val expected = checkNotNull(javaClass.getResource("/contracts/desktop-harnesses-v1.json")).readText()
        val published = Files.readString(paths.desktopHarnessesFile)
        assertEquals(Json.parseToJsonElement(expected), Json.parseToJsonElement(published))
    }

    @Test
    fun `a TOML-only ACP harness can start an agent without becoming a provider default`() = runBlocking {
        val agent = tmp.resolve("fixture acp")
        Files.writeString(agent, ACP_AGENT.trimIndent() + "\n")
        assertTrue(agent.toFile().setExecutable(true, true))
        val topology = boot(custom(agent)).topology
        val argv = checkNotNull(topology.harnessCatalog.acpCommand("example-agent"))
        val read = AgyModels(LogSink {}, this, Dispatchers.IO, argv).read()
        val roster = assertInstanceOf(AgyRead.Offered::class.java, read)
        assertEquals(listOf("gemini-fixture"), roster.catalog.models.map { it.id })
        assertEquals(12_345L, roster.catalog.models.single().contextWindow)

        val paths = paths()
        val harnesses = DaemonDesktop(topology, paths, LogSink {}).harnesses
        harnesses.publish()
        val saved = Files.readString(paths.desktopHarnessesFile)
        val resolved = Json.parseToJsonElement(saved).jsonObject
        val entries = resolved.getValue("harnesses").jsonArray.map { it.jsonObject }
        assertTrue(entries.any { it["id"].toString() == "\"example-agent\"" })
        assertEquals("\"claude-code\"", resolved["default_harness"].toString())
        assertFalse("example-agent" in resolved.getValue("families").toString())
        assertFalse(agent.toString() in saved, "the resolved wire file does not publish argv")
    }

    @Test
    fun `missing and non executable ACP commands fail to start, including dangling links`() = runBlocking {
        val missing = tmp.resolve("missing")
        val unreadable = Files.writeString(tmp.resolve("not-executable"), "#!/bin/sh\nexit 0\n")
        val dangling = Files.createSymbolicLink(tmp.resolve("dangling"), missing)
        for (executable in listOf(missing, unreadable, dangling)) {
            val topology = boot(custom(executable)).topology
            val argv = checkNotNull(topology.harnessCatalog.acpCommand("example-agent"))
            val result = AgyModels(LogSink {}, this, Dispatchers.IO, argv).read()
            val absent = assertInstanceOf(AgyRead.Absent::class.java, result, executable.fileName.toString())
            assertTrue(absent.reason.contains("could not be started"))
        }
    }

    @Test
    fun `boot reports invalid harness ids, protocols and argv without echoing arguments`() {
        val source = """
            [harnesses."INVALID ID"]
            name = ""
            protocol = "not-acp"
            argv = ["relative-executable", "do-not-echo"]

            [harnesses.codex]
            name = "Replacement"
            protocol = "acp"
            argv = []
        """.trimIndent()
        val refused = assertInstanceOf(ConfigRead.Refused::class.java, ConfigFindings.read(source, tmp))
        val paths = refused.findings.map { it.path }.toSet()
        assertEquals(
            setOf(
                "harnesses.INVALID ID",
                "harnesses.INVALID ID.name",
                "harnesses.INVALID ID.protocol",
                "harnesses.INVALID ID.argv",
                "harnesses.codex",
                "harnesses.codex.argv",
            ),
            paths,
        )
        assertFalse("do-not-echo" in refused.findings.joinToString())
    }

    @Test
    fun `boot lists every bad peer protocol and fallback setting without disclosing values`() {
        val source = listOf(
            badPeerDeclaration("bad-protocol", "unknown", """deliver_cmd = ["relative", "private-argument"]"""),
            badPeerDeclaration("native-mode", "native-cc-socks"),
            badPeerDeclaration("codex-mode", "codex-app-server"),
            badPeerDeclaration("no-command", "command"),
            badPeerDeclaration("no-url", "webhook"),
            badPeerDeclaration("remote-url", "webhook", """webhook_url = "http://private-url.invalid/collect""""),
            badPeerDeclaration("credential-url", "webhook", """webhook_url = "http://private-credential@localhost/""""),
        ).joinToString("\n")
        val refused = assertInstanceOf(ConfigRead.Refused::class.java, ConfigFindings.read(source, tmp))
        assertEquals(
            setOf(
                "harnesses.bad-protocol.peer.protocol",
                "harnesses.bad-protocol.peer.deliver_cmd",
                "harnesses.native-mode.peer.protocol",
                "harnesses.codex-mode.peer.protocol",
                "harnesses.no-command.peer.deliver_cmd",
                "harnesses.no-url.peer.webhook_url",
                "harnesses.remote-url.peer.webhook_url",
                "harnesses.credential-url.peer.webhook_url",
            ),
            refused.findings.map { it.path }.toSet(),
        )
        assertFalse("private-" in refused.findings.joinToString())
    }

    @Test
    fun `the topology writer cannot install or replace a harness command`() {
        val config = tmp.resolve("splice.toml")
        val original = custom(tmp.resolve("trusted"))
        Files.writeString(config, original)
        val writer = TopologyWriter(config, tmp.resolve("backups"), TopologyParse(TopologyLoader::parse))
        val proposed = boot(custom(tmp.resolve("replacement"))).topology
        val write = writer.write(proposed, writer.current())
        val refused = assertInstanceOf(TopologyWriteResult.Refused::class.java, write)
        assertEquals("harnesses", refused.findings.single().path)
        assertEquals(original, Files.readString(config))

        val held = boot(original).topology
        val unrelated = held.copy(daemon = held.daemon.copy(antigravity = false))
        assertInstanceOf(TopologyWriteResult.Written::class.java, writer.write(unrelated, writer.current()))
        assertTrue(Files.readString(config).contains(original))
    }

    private fun badPeerDeclaration(id: String, protocol: String, options: String = ""): String =
        """
        [harnesses.$id]
        name = "Example harness"
        protocol = "acp"
        argv = ["/bin/false"]

        [harnesses.$id.peer]
        protocol = "$protocol"
        $options
        """.trimIndent()

    private fun paths(): StatePaths = StatePaths(baseOverride = tmp.resolve("state"), homeDir = tmp.resolve("home"))

    private fun boot(source: String): ConfigRead.Ready {
        val result = ConfigFindings.read(source, tmp)
        return assertInstanceOf(ConfigRead.Ready::class.java, result, (result as? ConfigRead.Refused).toString())
    }

    private fun custom(agent: Path): String = """
        [harnesses.example-agent]
        name = "Example agent"
        description = "A local ACP test agent"
        protocol = "acp"
        argv = [${Json.encodeToString(agent.toString())}, "argument with spaces"]
    """.trimIndent()
}

private const val ACP_AGENT = """
#!/bin/sh
[ "$1" = "argument with spaces" ] || exit 41
IFS= read -r request || exit 42
case "${'$'}request" in *'"method":"initialize"'*) ;; *) exit 43;; esac
echo '{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":1}}'
IFS= read -r request || exit 44
case "${'$'}request" in *'"method":"session/new"'*) ;; *) exit 45;; esac
printf '%s%s%s\n' \
  '{"jsonrpc":"2.0","id":2,"result":{"sessionId":"fixture",' \
  '"configOptions":[{"id":"model","currentValue":"gemini-fixture",' \
  '"options":[{"value":"gemini-fixture","name":"Fixture"}]}]}}'
printf '%s%s\n' \
  '{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"fixture",' \
  '"update":{"sessionUpdate":"usage_update","size":12345}}}'
IFS= read -r request || exit 46
echo '{"jsonrpc":"2.0","id":3,"result":{}}'
"""
