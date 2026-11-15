// `[server]` at boot (docs/specs/server-mode.md, decisions 1 to 3): what `check-config` refuses before one splice
// becomes a team's front door, each refusal naming its key and its reason, and the table it lets through.
package splice.topology

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.topology.TopologyFinding
import java.nio.file.Files
import java.nio.file.Path

class ServerConfigFindingsTest {
    @TempDir lateinit var directory: Path

    /** A head served as `claude-team`, on a provider signing with [auth]. */
    private fun head(dialect: String = "openai-chat", auth: String = API_KEY): String =
        """
        [providers.box]
        dialect = "$dialect"
        base_url = "http://127.0.0.1:8080/v1"
        auth = $auth

        [[providers.box.models]]
        id = "team-model"
        label = "team-model"
        context_window = 131072

        [heads.box]
        provider = "box"
        port = 3190
        discovery_prefix = "claude-box--"
        pinned_model = "team-model"
        [heads.box.claude]
        command = "claude-team"
        """.trimIndent() + "\n"

    private fun server(lines: String = "", commands: String = "\"claude-team\""): String =
        "[server]\nlisten = \"0.0.0.0:3443\"\ncommands = [$commands]\n$lines\n"

    /** The findings under `server`, as `check-config` lists them. */
    private fun refused(toml: String): List<TopologyFinding> {
        val read = ConfigFindings.read(toml, directory, configDir = directory)
        return assertInstanceOf(ConfigRead.Refused::class.java, read).findings.filter { it.path.startsWith("server") }
    }

    @Test
    fun `a server on an API-key head boots, with its proxies, admins, limits and TLS files`() {
        Files.writeString(directory.resolve("fullchain.pem"), "-----BEGIN CERTIFICATE-----\n")
        Files.writeString(directory.resolve("privkey.pem"), "-----BEGIN PRIVATE KEY-----\n")
        val lines = """
            tls_cert = "fullchain.pem"
            tls_key = "privkey.pem"
            trusted_proxies = ["10.0.0.0/8", "fd00::/8", "192.168.1.7"]
            admins = ["Ops@Team.example"]
            [server.limits]
            inflight_per_person = 12
            rpm_per_person = 0
        """.trimIndent()
        val read = ConfigFindings.read(server(lines) + head(), directory, configDir = directory)
        val topology = assertInstanceOf(ConfigRead.Ready::class.java, read, read.toString()).topology
        assertEquals(listOf("claude-team"), topology.server.commands)
        assertEquals(12, topology.server.limits.inflightPerPerson)
        assertTrue(topology.server.admin("ops@team.example"), "an admin written with capitals does not administer")
    }

    @Test
    fun `a file with no server table is not asked for one`() {
        val read = ConfigFindings.read(head(), directory, configDir = directory)
        assertInstanceOf(ConfigRead.Ready::class.java, read, read.toString())
    }

    @Test
    fun `a server beside an enabled share is refused, naming both, and beside a share that is off it boots`() {
        val both = refused("[share]\nenabled = true\n\n" + server() + head())
        assertEquals(listOf("server"), both.map { it.path })
        assertTrue(listOf("[server]", "[share]").all(both.single().message::contains), both.single().message)
        val shareOff = "[share]\nenabled = false\n\n" + server() + head()
        val off = ConfigFindings.read(shareOff, directory, configDir = directory)
        assertInstanceOf(ConfigRead.Ready::class.java, off, off.toString())
    }

    @Test
    fun `a command no head here runs is refused by name, with the commands there are`() {
        val unknown = refused(server(commands = "\"claude-nobody\"") + head()).single()
        assertEquals("server.commands", unknown.path)
        assertTrue(listOf("claude-nobody", "claude-team").all(unknown.message::contains), unknown.message)
        val none = refused(server(commands = "") + head()).single()
        assertEquals("server.commands", none.path)
    }

    @Test
    fun `a command signing with a ChatGPT or a Claude plan is refused, saying a plan is one person's`() {
        val plans = mapOf(
            "Codex (ChatGPT)" to head("openai-responses", """{ kind = "chatgpt-oauth" }"""),
            "Claude" to head("anthropic-passthrough", """{ kind = "client" }"""),
        )
        for ((plan, signedByPlan) in plans) {
            val finding = refused(server() + signedByPlan).single { it.path == "server.commands" }
            assertTrue(listOf("claude-team", plan, "one person").all(finding.message::contains), finding.message)
        }
    }

    @Test
    fun `an address, a TLS pair, a proxy, an admin and a cap that cannot be served are each refused at their key`() {
        val lines = """
            tls_cert = "fullchain.pem"
            trusted_proxies = ["the-proxy"]
            admins = ["ops"]
            [server.limits]
            rpm_per_person = -1
        """.trimIndent()
        val toml = server(lines).replace("0.0.0.0:3443", "3443") + head()
        assertEquals(
            setOf(
                "server.listen",
                "server.tls_key",
                "server.trusted_proxies",
                "server.admins",
                "server.limits.rpm_per_person",
            ),
            refused(toml).map { it.path }.toSet(),
        )
    }

    @Test
    fun `a TLS file that is not there is refused before the listener would fail on it`() {
        val lines = "tls_cert = \"fullchain.pem\"\ntls_key = \"privkey.pem\""
        assertEquals(
            setOf("server.tls_cert", "server.tls_key"),
            refused(server(lines) + head()).map { it.path }.toSet(),
        )
    }

    @Test
    fun `a TLS key in a form the listener cannot read is refused with the command that converts it`() {
        Files.writeString(directory.resolve("fullchain.pem"), "-----BEGIN CERTIFICATE-----\n")
        Files.writeString(directory.resolve("privkey.pem"), "-----BEGIN EC PRIVATE KEY-----\n")
        val lines = "tls_cert = \"fullchain.pem\"\ntls_key = \"privkey.pem\""
        val finding = refused(server(lines) + head()).single()
        assertEquals("server.tls_key", finding.path)
        assertTrue(listOf("PKCS#8", "openssl pkcs8 -topk8").all(finding.message::contains), finding.message)
    }
}

private const val API_KEY = """{ kind = "api-key", env = "BOX_KEY" }"""
