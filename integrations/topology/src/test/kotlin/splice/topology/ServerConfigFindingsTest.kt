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
    fun `a server on an API-key head boots, with its proxies, admins, keepalive, limits and TLS files`() {
        Files.writeString(directory.resolve("fullchain.pem"), "-----BEGIN CERTIFICATE-----\n")
        Files.writeString(directory.resolve("privkey.pem"), "-----BEGIN PRIVATE KEY-----\n")
        val lines = """
            tls_cert = "fullchain.pem"
            tls_key = "privkey.pem"
            trusted_proxies = ["10.0.0.0/8", "fd00::/8", "192.168.1.7"]
            admins = ["Ops@Team.example"]
            keepalive_seconds = 10
            [server.limits]
            inflight_per_person = 12
            rpm_per_person = 0
        """.trimIndent()
        val read = ConfigFindings.read(server(lines) + head(), directory, configDir = directory)
        val topology = assertInstanceOf(ConfigRead.Ready::class.java, read, read.toString()).topology
        assertEquals(listOf("claude-team"), topology.server.commands)
        assertEquals(12, topology.server.limits.inflightPerPerson)
        assertEquals(10, topology.server.keepaliveSeconds)
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
    fun `a provider with no head is a command served at the door, a personal plan one still refused`() {
        val headless = head().substringBefore("[heads.box]")
        val read = ConfigFindings.read(server(commands = "\"box\"") + headless, directory, configDir = directory)
        val ready = assertInstanceOf(ConfigRead.Ready::class.java, read, read.toString())
        assertEquals(listOf("box"), ready.topology.server.commands)
        val plan = head("openai-responses", """{ kind = "chatgpt-oauth" }""").substringBefore("[heads.box]")
        val finding = refused(server(commands = "\"box\"") + plan).single { it.path == "server.commands" }
        assertTrue("one person" in finding.message, finding.message)
    }

    @Test
    fun `a name that is a head's command and another provider's key is refused, never read as both`() {
        val twice = head() + """
            [providers.other]
            dialect = "openai-chat"
            base_url = "http://127.0.0.1:8081/v1"
            auth = $API_KEY
            [[providers.other.models]]
            id = "other-model"
            context_window = 131072

            [heads.shadow]
            provider = "box"
            port = 3191
            discovery_prefix = "claude-shadow--"
            pinned_model = "team-model"
            [heads.shadow.claude]
            command = "other"
        """.trimIndent() + "\n"
        val finding = refused(server(commands = "\"other\"") + twice).single { it.path == "server.commands" }
        assertTrue("which runs box" in finding.message && "another provider" in finding.message, finding.message)
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
    fun `an address, a TLS pair, a proxy, an admin, a keepalive and a cap that cannot be served are each refused`() {
        val lines = """
            tls_cert = "fullchain.pem"
            trusted_proxies = ["the-proxy"]
            admins = ["ops"]
            keepalive_seconds = -5
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
                "server.keepalive_seconds",
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

    @Test
    fun `external metering and telemetry settings decode without running their sources`() {
        val lines = """
            metrics_prefix = "team_"
            [server.telemetry]
            forward_to = "https://collector.example"
            [server.telemetry.identity_attributes]
            person = "team.actor"
            [server.cost_meters.gpu]
            hourly_usd = 4.5
            file = "not-yet-present"
            [server.cost_meters.worker]
            hourly_usd = 1.0
            argv = ["/not-yet-installed/probe"]
        """.trimIndent()
        val read = ConfigFindings.read(server(lines) + head(), directory, configDir = directory)
        val config = assertInstanceOf(ConfigRead.Ready::class.java, read, read.toString()).topology.server
        assertEquals("team_", config.metricsPrefix)
        assertEquals("team.actor", config.telemetry?.identityAttributes?.person)
        assertEquals("user.email", config.telemetry?.identityAttributes?.email)
        assertEquals(4.5, config.costMeters.getValue("gpu").hourlyUsd)
        assertEquals(listOf("/not-yet-installed/probe"), config.costMeters.getValue("worker").argv)
    }

    @Test
    fun `metering findings name invalid prefix, identity, rates, cadence and signal sources`() {
        val lines = """
            metrics_prefix = "1 invalid"
            [server.telemetry]
            forward_to = "https://collector.example"
            [server.telemetry.identity_attributes]
            person = "user.email"
            [server.cost_meters.gpu]
            hourly_usd = -1.0
            poll_seconds = 0
            [server.cost_meters.disk]
            hourly_usd = 0.5
            url = "file:///tmp/state"
            [server.cost_meters.worker]
            hourly_usd = 0.5
            argv = ["probe"]
        """.trimIndent()
        assertEquals(
            setOf(
                "server.metrics_prefix",
                "server.telemetry.identity_attributes",
                "server.cost_meters.gpu",
                "server.cost_meters.gpu.hourly_usd",
                "server.cost_meters.gpu.poll_seconds",
                "server.cost_meters.disk.url",
                "server.cost_meters.worker.argv",
            ),
            refused(server(lines) + head()).map { it.path }.toSet(),
        )
    }

    @Test
    fun `a whole-number meter rate boots as dollars, as a provider's does`() {
        val lines = """
            [server.cost_meters.gpu]
            hourly_usd = 3
            file = "not-yet-present"
        """.trimIndent()
        val read = ConfigFindings.read(server(lines) + head(), directory, configDir = directory)
        val config = assertInstanceOf(ConfigRead.Ready::class.java, read, read.toString()).topology.server
        assertEquals(3.0, config.costMeters.getValue("gpu").hourlyUsd)
    }

    @Test
    fun `a metrics prefix may not start with the double underscore Prometheus reserves`() {
        assertEquals(
            listOf("server.metrics_prefix"),
            refused(server("metrics_prefix = \"__team_\"") + head()).map { it.path },
        )
    }

    @Test
    fun `a cost meter may not take a provider's key, which the report's hourly rows are keyed by`() {
        val lines = """
            [server.cost_meters.box]
            hourly_usd = 1.0
            file = "not-yet-present"
        """.trimIndent()
        val findings = refused(server(lines) + head())
        assertEquals(listOf("server.cost_meters.box"), findings.map { it.path })
        assertTrue(findings.single().message.contains("provider"), findings.single().message)
    }

    @Test
    fun `a sign-in table boots with its issuer, client, domains and key days, and a loopback issuer may be http`() {
        val lines = """
            [server.signin]
            issuer = "http://127.0.0.1:9000"
            client_id = "splice-team"
            client_secret_env = "SPLICE_SIGNIN_SECRET"
            domains = ["@Team.example"]
            key_days = 7
        """.trimIndent()
        val read = ConfigFindings.read(server(lines) + head(), directory, configDir = directory)
        val signin = assertInstanceOf(ConfigRead.Ready::class.java, read, read.toString()).topology.server.signin
        assertEquals("splice-team", signin?.clientId)
        assertEquals(7, signin?.keyDays)
        assertTrue(signin?.admits("dana@team.example") == true, "the domain written with @ and capitals admits nobody")
        assertTrue(signin?.admits("dana@other.example") == false, "a person in another domain is admitted")
    }

    @Test
    fun `each wrong sign-in field is refused by its key, and an issuer off this machine must be https`() {
        val lines = """
            [server.signin]
            issuer = "http://login.team.example"
            client_id = " "
            client_secret_env = ""
            domains = ["team"]
            key_days = 0
            rate_per_minute = -1
        """.trimIndent()
        val found = refused(server(lines) + head())
        assertEquals(
            setOf(
                "server.signin.issuer",
                "server.signin.client_id",
                "server.signin.client_secret_env",
                "server.signin.domains",
                "server.signin.key_days",
                "server.signin.rate_per_minute",
            ),
            found.map { it.path }.toSet(),
        )
        val issuer = found.single { it.path == "server.signin.issuer" }.message
        assertTrue("https" in issuer, issuer)
    }

    @Test
    fun `forward_auth and client download_url accept valid entries and refuse malformed ones`() {
        val good = """
            [server.client]
            minimum = "0.10.0"
            download_url = "https://releases.team.example/splice"
            [server.forward_auth]
            allow = ["Ada@Team.example", "@ops.example"]
        """.trimIndent()
        val ready = ConfigFindings.read(server(good) + head(), directory, configDir = directory)
        val cfg = assertInstanceOf(ConfigRead.Ready::class.java, ready, ready.toString()).topology.server
        assertEquals("https://releases.team.example/splice", cfg.client.downloadUrl)
        assertTrue(cfg.forwardAuth?.admits("ada@team.example", isAdmin = false) == true)
        assertTrue(cfg.forwardAuth?.admits("bob@ops.example", isAdmin = false) == true)
        assertTrue(cfg.forwardAuth?.admits("stranger@other.example", isAdmin = false) == false)
        assertTrue(cfg.forwardAuth?.admits("stranger@other.example", isAdmin = true) == true)

        val bad = """
            [server.client]
            download_url = "ftp://bad.example"
            [server.forward_auth]
            allow = ["not-an-email"]
        """.trimIndent()
        val found = refused(server(bad) + head())
        assertEquals(setOf("server.client.download_url", "server.forward_auth.allow"), found.map { it.path }.toSet())
    }

    @Test
    fun `server backup accepts folder or signed S3 target and refuses malformed target or missing keys`() {
        val good = """
            [server.backup]
            to = "s3://team-backups/prod"
            endpoint = "https://r2.team.example"
            region = "auto"
            path_style = true
            access_key_env = "BACKUP_S3_KEY"
            secret_key_env = "BACKUP_S3_SECRET"
            every = "30m"
        """.trimIndent()
        val ready = ConfigFindings.read(server(good) + head(), directory, configDir = directory)
        val backup = assertInstanceOf(ConfigRead.Ready::class.java, ready, ready.toString()).topology.server.backup
        assertEquals("s3://team-backups/prod", backup.to)
        assertEquals("30m", backup.every)

        val bad = """
            [server.backup]
            to = "s3://team-backups/prod"
            every = "forever"
        """.trimIndent()
        val found = refused(server(bad) + head())
        assertEquals(
            setOf("server.backup.every", "server.backup.access_key_env", "server.backup.secret_key_env"),
            found.map { it.path }.toSet(),
        )
    }
}

private const val API_KEY = """{ kind = "api-key", env = "BOX_KEY" }"""
