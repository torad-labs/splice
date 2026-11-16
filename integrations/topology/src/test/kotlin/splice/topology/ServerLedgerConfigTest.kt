// `[server.reference]`, `[server.teams]`, `[server.export]` and `[server.budgets]` as an operator writes them
// (docs/specs/server-mode.md, decision 5, F3 and F5): what each reads as once boot has read the file, and what boot
// refuses, by key path.
package splice.topology

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.topology.ServerConfig
import java.nio.file.Path

class ServerLedgerConfigTest {
    @TempDir lateinit var directory: Path

    private val head = """
        [providers.box]
        dialect = "openai-chat"
        base_url = "http://127.0.0.1:8080/v1"
        auth = { kind = "api-key", env = "BOX_KEY" }

        [[providers.box.models]]
        id = "team-model"
        context_window = 131072

        [heads.box]
        provider = "box"
        port = 3190
        discovery_prefix = "claude-box--"
        pinned_model = "team-model"
        [heads.box.claude]
        command = "claude-team"
    """.trimIndent()

    private fun toml(tables: String): String =
        "[server]\nlisten = \"0.0.0.0:3443\"\ncommands = [\"claude-team\"]\n\n${tables.trimIndent()}\n\n$head\n"

    private fun server(tables: String): ServerConfig {
        val read = ConfigFindings.read(toml(tables), directory, configDir = directory)
        return assertInstanceOf(ConfigRead.Ready::class.java, read, read.toString()).topology.server
    }

    private fun refused(tables: String): Set<String> {
        val read = ConfigFindings.read(toml(tables), directory, configDir = directory)
        return assertInstanceOf(ConfigRead.Refused::class.java, read).findings.map { it.path }.toSet()
    }

    @Test
    fun `a team comes from the email's domain, written quoted as a domain must be, in any case`() {
        val server = server(
            """
            [server.teams]
            by_domain = { "team.example" = "core", "Partner.Example" = "partners" }
            """,
        )
        assertEquals("core", server.teams.ofDomain("ada@team.example"))
        assertEquals("partners", server.teams.ofDomain("pat@partner.example"))
        assertNull(server.teams.ofDomain("eve@elsewhere.example"))
    }

    @Test
    fun `dollar amounts read the same written whole or with a decimal point`() {
        val whole = server(
            """
            [server.reference]
            input_usd_per_mtok = 3
            cached_usd_per_mtok = 0.3
            output_usd_per_mtok = 15

            [server.budgets]
            person_daily_usd = 50
            action = "warn"
            """,
        )
        val card = whole.reference
        assertEquals(listOf(3.0, 0.3, 15.0), listOf(card.inputUsdPerMtok, card.cachedUsdPerMtok, card.outputUsdPerMtok))
        assertEquals(50.0, whole.budgets.personDailyUsd)
        assertEquals("warn", whole.budgets.action)
        assertEquals(50.0, server("[server.budgets]\nperson_daily_usd = 50.0").budgets.personDailyUsd)
    }

    @Test
    fun `an export reads its target, its bucket's reach and how often it runs`() {
        val export = server(
            """
            [server.export]
            to = "s3://usage-bucket/gateway"
            endpoint = "https://account.r2.example"
            region = "auto"
            path_style = true
            access_key_env = "USAGE_S3_KEY"
            secret_key_env = "USAGE_S3_SECRET"
            every = "15m"
            """,
        ).export
        assertEquals("s3://usage-bucket/gateway", export.to)
        assertEquals(true, export.pathStyle)
        assertEquals(15 * 60_000L, export.everyMs)
    }

    @Test
    fun `boot refuses an export to nowhere, a bucket with no key variables, a zero budget and an unknown action`() {
        assertEquals(
            setOf("server.export.to", "server.export.every"),
            refused("[server.export]\nto = \"ftp://x\"\nevery = \"soon\""),
        )
        assertEquals(
            setOf("server.export.access_key_env", "server.export.secret_key_env"),
            refused("[server.export]\nto = \"s3://bucket/prefix\""),
        )
        assertEquals(
            setOf("server.budgets.person_daily_usd", "server.budgets.action"),
            refused("[server.budgets]\nperson_daily_usd = 0\naction = \"shout\""),
        )
        assertEquals(setOf("server.teams.by_domain"), refused("[server.teams]\nby_domain = { \"\" = \"core\" }"))
    }
}
