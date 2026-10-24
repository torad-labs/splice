// NEW: LAN share, round 11 of PR #419's review. The goal: a machine Marcos imports again at a new address, because its
// DHCP lease moved, serves every command already imported from it at that address, as the Sharing page and the health
// check already read it; a command relayed to another machine stays where it is. What `splice remote` writes on a
// re-join (RemoteTopology.joined), checked the way boot checks it.
package splice.share.remote

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.core.model.ModelEntry
import splice.core.topology.AuthConfig
import splice.core.topology.AuthKind
import splice.core.topology.Dialect
import splice.core.topology.HeadConfig
import splice.core.topology.ProviderConfig
import splice.core.topology.QuirksConfig
import splice.core.topology.RemoteConfig
import splice.core.topology.Topology

private const val PIN = "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
private const val OLD = "https://192.168.1.236:3097"
private const val NEW = "https://192.168.1.50:3097"
private const val BOX = "https://192.168.1.77:3097"

class RemoteTopologyTest {

    private fun relay(machine: String, url: String, head: String) = ProviderConfig(
        dialect = Dialect.ANTHROPIC_PASSTHROUGH,
        baseUrl = RemoteRelay.url(url, head),
        auth = AuthConfig(kind = AuthKind.SpliceRemote.wire, remote = machine),
        quirks = QuirksConfig(forwardClientIdentity = true),
        models = listOf(ModelEntry("gpt-x", "GPT X", contextWindow = 400_000)),
    )

    private fun head(provider: String, port: Int) =
        HeadConfig(provider = provider, port = port, discoveryPrefix = "claude-$provider--", pinnedModel = "gpt-x")

    /** This machine after importing two of torad-server's commands at its old address, and one of box's. */
    private val imported = Topology(
        remotes = mapOf("torad-server" to RemoteConfig(OLD, PIN), "box" to RemoteConfig(BOX, PIN)),
        providers = mapOf(
            "claudex-torad-server" to relay("torad-server", OLD, "claudex"),
            "claude-grok-torad-server" to relay("torad-server", OLD, "claude-grok"),
            "claudex-box" to relay("box", BOX, "claudex"),
        ),
        heads = mapOf(
            "claudex-torad-server" to head("claudex-torad-server", 3110),
            "claude-grok-torad-server" to head("claude-grok-torad-server", 3111),
            "claudex-box" to head("claudex-box", 3112),
        ),
    )

    @Test
    fun `joining a machine again at a new address moves every command relayed to it onto that address`() {
        val moved = RemoteTopology(imported).joined("torad-server", NEW, PIN)

        assertEquals(NEW, moved.remotes.getValue("torad-server").url)
        assertEquals("$NEW/share/v1/heads/claudex", moved.providers.getValue("claudex-torad-server").baseUrl)
        assertEquals("$NEW/share/v1/heads/claude-grok", moved.providers.getValue("claude-grok-torad-server").baseUrl)
        assertEquals("$BOX/share/v1/heads/claudex", moved.providers.getValue("claudex-box").baseUrl, "box's stays")
        val synced = RemoteTopology(moved).headFor("torad-server", "claudex")
        assertEquals("claudex-torad-server", synced, "a sync finds it")
    }
}
