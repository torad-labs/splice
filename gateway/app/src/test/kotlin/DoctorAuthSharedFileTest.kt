// NEW: 2026-09-05 — doctor warns when a head signs in with the vendor app's own credential file
// (AuthKind header): the config keeps working, so WARN with the exact fix, and nothing for a head
// on splice's own file or on the default.
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.app.cli.CheckStatus
import splice.app.cli.DaemonSnapshot
import splice.app.cli.DoctorAuth
import splice.app.cli.DoctorTopology
import splice.core.model.ModelEntry
import splice.core.topology.AuthConfig
import splice.core.topology.DaemonConfig
import splice.core.topology.Dialect
import splice.core.topology.HeadConfig
import splice.core.topology.ProviderConfig
import splice.core.topology.Topology
import splice.core.util.EnvReader

class DoctorAuthSharedFileTest {

    private fun topology(vararg providers: Pair<String, String?>): Topology = Topology(
        daemon = DaemonConfig(controlPort = 4123),
        providers = providers.associate { (key, file) ->
            key to ProviderConfig(
                dialect = Dialect.OPENAI_RESPONSES,
                baseUrl = "https://example.invalid",
                auth = AuthConfig("chatgpt-oauth", file = file),
                models = listOf(ModelEntry("m", contextWindow = 100_000)),
            )
        },
        heads = providers.associate { (key, _) ->
            "head-$key" to HeadConfig(key, 4101, "claude-$key--", "m")
        },
    )

    private fun authChecks(topology: Topology) =
        DoctorAuth().authChecks(DoctorTopology.Parsed(topology), EnvReader { null }, DaemonSnapshot(4123, null))

    @Test
    fun `a head on the vendor app's own file gets a WARN naming the fix`() {
        val checks = authChecks(topology("codex" to "~/.codex/auth.json"))
        val shared = checks.filter { it.detail.contains("Codex CLI / ChatGPT app") }
        assertEquals(1, shared.size, checks.toString())
        assertEquals(CheckStatus.WARN, shared.single().status)
        assertTrue(shared.single().detail.startsWith("head-codex signs in with"), shared.single().detail)
        assertEquals(
            "remove `file` from [providers.codex] auth in splice.toml, then: splice login head-codex",
            shared.single().fix,
        )
    }

    @Test
    fun `splice's own file, explicit or default, draws no warning`() {
        val checks = authChecks(topology("own" to "~/.config/splice/auth/codex.json", "default" to null))
        assertTrue(checks.none { it.detail.contains("own credential file") }, checks.toString())
    }
}
