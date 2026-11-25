package splice.core.topology.availability

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.core.topology.AuthConfig
import splice.core.topology.Dialect
import splice.core.topology.ProviderConfig
import splice.core.topology.ServerConfig
import splice.core.topology.ServerProbe
import splice.core.topology.Topology
import splice.core.topology.UpstreamConfig

/** #791: `[server.probe] single_url` names the providers on one base_url the probe also asks. An entry the probe could
 *  not ask is a boot finding beside every other, at its place in the list and never by the name it holds. */
class ProbeSingleUrlFindingTest {
    private val key = AuthConfig(kind = "api-key")
    private val providers = mapOf(
        "solo" to ProviderConfig(dialect = Dialect.OPENAI_CHAT, baseUrl = "http://10.0.0.9:8080/v1", auth = key),
        "pooled" to ProviderConfig(
            dialect = Dialect.OPENAI_CHAT,
            auth = key,
            upstreams = listOf(UpstreamConfig("gpu-1", "http://10.0.0.21:8080/v1")),
        ),
        "messages" to ProviderConfig(
            dialect = Dialect.ANTHROPIC_PASSTHROUGH,
            baseUrl = "https://api.example/v1",
            auth = key,
        ),
    )

    private fun findings(vararg names: String): List<Pair<String, String>> {
        val probe = ServerProbe(kind = "completion", model = "team-model", singleUrl = names.toList())
        val topology = Topology(providers = providers, server = ServerConfig(probe = probe))
        return ServerAvailabilityChecks(topology).findings().map { it.path to it.message }
    }

    @Test
    fun `an openai-chat provider on one base_url is one the probe can ask`() {
        assertEquals(emptyList<Pair<String, String>>(), findings("solo"))
    }

    @Test
    fun `an entry that is no provider, a pool, another dialect or a repeat refuses boot at its place`() {
        val found = findings("solo", "missing", "pooled", "messages", "solo")
        assertEquals(
            listOf(
                "server.probe.single_url" to "names a provider twice",
                "server.probe.single_url[1]" to "names a provider splice.toml does not declare",
                "server.probe.single_url[2]" to "names a provider that lists upstreams, which the probe asks already",
                "server.probe.single_url[3]" to "names a provider whose dialect is not \"openai-chat\"",
            ),
            found,
        )
    }
}
