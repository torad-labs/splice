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

    // #791: the client minimum read from a launcher's own header or a check-in, and what an older one is answered with.
    @Test
    fun `a client table with its own header, status, words and check-in boots, and a wrong header or status is not`() {
        val good = """
            [server.client]
            minimum = "0.10.0"
            version_header = "x-team-launcher"
            outdated_status = 426
            outdated_message = "Run team-update: {version} is older than {minimum}."
            checkin = true
        """.trimIndent()
        val ready = ConfigFindings.read(server(good) + head(), directory, configDir = directory)
        val client = assertInstanceOf(ConfigRead.Ready::class.java, ready, ready.toString()).topology.server.client
        assertEquals("x-team-launcher", client.header)
        assertEquals(426, client.outdatedStatus)
        assertTrue(client.checkin)
        for (status in listOf(200, 400)) {
            val read = ConfigFindings.read(
                server("[server.client]\noutdated_status = $status") + head(),
                directory,
                configDir = directory,
            )
            assertInstanceOf(ConfigRead.Ready::class.java, read, "$status: $read")
        }

        val wrong = "[server.client]\nversion_header = \"x team: launcher\"\noutdated_status = 503"
        val bad = refused(server(wrong) + head())
        assertEquals(listOf("server.client.version_header", "server.client.outdated_status"), bad.map { it.path })
    }

    // #791: an alias is one plain path segment standing for a served command or the door.
    @Test
    fun `aliases to a served command or the door boot, and every alias that cannot be served is refused by name`() {
        val ready = ConfigFindings.read(
            server("[server.aliases]\n\"/v1\" = \"claude-team\"\n\"/openai\" = \"door\"") + head(),
            directory,
            configDir = directory,
        )
        val served = assertInstanceOf(ConfigRead.Ready::class.java, ready, ready.toString()).topology.server
        assertEquals(mapOf("v1" to "claude-team", "openai" to "door"), served.aliasSegments)

        val aliases = """
            [server.aliases]
            "v1" = "claude-team"
            "/a/b" = "claude-team"
            "/server" = "door"
            "/health" = "door"
            "/claude-team" = "door"
            "/door" = "claude-team"
            "/v2" = "claude-nobody"
        """.trimIndent()
        val found = refused(server(aliases) + head())
        assertEquals(7, found.size, found.toString())
        assertTrue(found.all { it.path == "server.aliases" }, found.toString())
        val named = listOf("'v1'", "'/a/b'", "'/server'", "'/health'", "'/claude-team'", "'/door'", "'claude-nobody'")
        for (alias in named) {
            assertTrue(found.any { alias in it.message }, "$alias: $found")
        }
    }

    // #791: the listener serves a head at its command and at its head key alike, and a provider with no head only at
    // the door, so boot judges an alias by the same names the listener answers to.
    @Test
    fun `an alias may not take a served head's key, stand for a headless provider, or be written twice`() {
        val shadow = refused(server("[server.aliases]\n\"/box\" = \"door\"") + head()).single()
        assertTrue("'/box'" in shadow.message && "already a path" in shadow.message, shadow.message)

        val byKey = server("[server.aliases]\n\"/v1\" = \"box\"") + head()
        assertInstanceOf(ConfigRead.Ready::class.java, ConfigFindings.read(byKey, directory, configDir = directory))

        val headless = head().substringBefore("[heads.box]")
        val provider = refused(server("[server.aliases]\n\"/v1\" = \"box\"", commands = "\"box\"") + headless).single()
        assertTrue("no head" in provider.message && "\"door\"" in provider.message, provider.message)
        val atDoor = server("[server.aliases]\n\"/v1\" = \"door\"", commands = "\"box\"") + headless
        assertInstanceOf(ConfigRead.Ready::class.java, ConfigFindings.read(atDoor, directory, configDir = directory))

        val twice = refused(server("[server.aliases]\n\"/v1\" = \"claude-team\"\n\" /v1\" = \"door\"") + head())
        assertEquals(1, twice.size, twice.toString())
        assertTrue("'/v1' is written more than once" in twice.single().message, twice.toString())
    }

    // #791: the listener answers `/server/...` and `/health` itself, so an alias standing for a head whose command or key
    // is so named would never reach that head.
    @Test
    fun `an alias may not stand for one of the listener's own routes, even where a served head is so named`() {
        for (name in listOf("server", "health")) {
            val named = head().replace("command = \"claude-team\"", "command = \"$name\"")
            val found = refused(server("[server.aliases]\n\"/v1\" = \"$name\"", commands = "\"$name\"") + named)
            val reserved = found.single { it.path == "server.aliases" }
            assertTrue("'/v1' stands for '$name'" in reserved.message, found.toString())
            assertTrue("listener's own routes" in reserved.message, reserved.message)
        }
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
