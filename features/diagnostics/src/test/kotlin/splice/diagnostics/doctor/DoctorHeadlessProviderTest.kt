// gateway-no-heads: the doctor's auth section reads a provider no head runs, which the gateway serves on the door all the
// same: one with no key is the blocker it is for a head, naming the provider, and a splice.toml with no providers at all is
// told so.
package splice.diagnostics.doctor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.model.ModelEntry
import splice.core.terminal.TerminalOutput
import splice.core.topology.AuthConfig
import splice.core.topology.DaemonConfig
import splice.core.topology.Dialect
import splice.core.topology.ProviderConfig
import splice.core.topology.Topology
import splice.core.util.EnvReader
import splice.daemonclient.DaemonProbe
import splice.diagnostics.doctor.accounts.JdkAccountPoolRead

class DoctorHeadlessProviderTest {
    private fun authChecks(topology: Topology, env: Map<String, String> = emptyMap()) =
        DoctorAuth(TerminalOutput(::println)).authChecks(
            DoctorTopology.Parsed(topology),
            EnvReader { env[it] },
            DaemonSnapshot(4123, DaemonProbe.HealthProbe.Down),
            LoopbackDaemon(JdkAccountPoolRead()),
        )

    private val inkling = ProviderConfig(
        dialect = Dialect.OPENAI_CHAT,
        baseUrl = "http://127.0.0.1:8099/v1",
        auth = AuthConfig("api-key", env = "INKLING_KEY"),
        models = listOf(ModelEntry("m", contextWindow = 100_000)),
    )

    @Test
    fun `a provider with no head and no key is a missing credential, named, as a head would be`() {
        val topology = Topology(daemon = DaemonConfig(controlPort = 4123), providers = mapOf("inkling" to inkling))
        val checks = authChecks(topology)
        assertTrue(checks.any { it.status == CheckStatus.FAIL && it.name == "inkling" }, checks.toString())
    }

    @Test
    fun `a provider with no head and its key set is not a finding`() {
        val topology = Topology(daemon = DaemonConfig(controlPort = 4123), providers = mapOf("inkling" to inkling))
        val checks = authChecks(topology, mapOf("INKLING_KEY" to "k"))
        assertTrue(checks.none { it.status == CheckStatus.FAIL }, checks.toString())
    }

    @Test
    fun `no providers at all is said so`() {
        val checks = authChecks(Topology(daemon = DaemonConfig(controlPort = 4123)))
        assertEquals(listOf("no providers configured"), checks.map { it.detail })
    }
}
