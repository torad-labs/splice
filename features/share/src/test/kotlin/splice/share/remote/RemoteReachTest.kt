// NEW: LAN share, phase 2, round-1 review. The goal: a command imported from another machine reads "Not answering" on
// this one whenever that machine does not serve its head, as the console's heads route and doctor show it, never fine
// while every turn is refused there. Two machines in one JVM: "torad" shares over its real TLS listener, offering two
// heads of which one failed at its start, and "mini" joined it and relays a command to each. mini's check reads
// torad's real /share/v1/health.
package splice.share.remote

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.testing.TestPorts
import splice.core.topology.AuthConfig
import splice.core.topology.AuthKind
import splice.core.topology.Dialect
import splice.core.topology.HeadConfig
import splice.core.topology.ProviderConfig
import splice.core.topology.RemoteConfig
import splice.core.topology.ShareConfig
import splice.core.topology.Topology
import splice.core.util.EnvReader
import splice.share.SessionsSwitch
import splice.share.ShareListener
import splice.share.ShareStart
import splice.share.ShareState
import splice.share.SharedCatalogs
import splice.share.SharedQuotas
import java.nio.file.Path

// why: the one model both machines' heads name.
private const val MODEL = "mock-1"

// why: a window any model row carries; nothing here reads it.
private const val WINDOW = 200_000L

class RemoteReachTest {

    @TempDir
    lateinit var tmp: Path

    private var listener: ShareListener? = null

    @AfterEach
    fun stop() {
        runBlocking { listener?.stop() }
    }

    private fun head(provider: String, port: Int) =
        HeadConfig(provider = provider, port = port, discoveryPrefix = "claude-$provider--", pinnedModel = MODEL)

    private fun paths(letter: String): StatePaths {
        val home = tmp.resolve(letter)
        val env = mapOf(
            "SPLICE_STATE_DIR" to home.resolve("state").toString(),
            "SPLICE_CONFIG" to home.resolve("splice.toml").toString(),
            "HOME" to home.toString(),
        )
        return StatePaths(envReader = EnvReader { env[it] })
    }

    /** torad, sharing on [port]: heads "one" and "two" on its own key, both offered, and only "one" running, as when
     *  "two" failed at the daemon's start. */
    private fun torad(port: Int) {
        val mock = ProviderConfig(
            Dialect.ANTHROPIC_PASSTHROUGH,
            "http://127.0.0.1:1",
            AuthConfig(kind = "api-key", env = "MOCK_API_KEY"),
            models = listOf(ModelEntry(MODEL, contextWindow = WINDOW)),
        )
        val topology = Topology(
            providers = mapOf("mock" to mock),
            heads = mapOf("one" to head("mock", 3901), "two" to head("mock", 3902)),
        )
        val catalog = ModelCatalog(
            discoveryPrefix = "claude-mock--",
            models = listOf(ModelEntry(MODEL, contextWindow = WINDOW)),
            defaultContextWindow = WINDOW,
            pinnedModel = MODEL,
        )
        val paths = paths("t")
        val opened = ShareListener(
            ShareStart(topology, paths, MgmtKey(paths), ConfigService(paths)) { },
            SharedCatalogs { key -> catalog.takeIf { key == "one" } },
            SharedQuotas { null },
            paths,
            { },
        )
        listener = opened
        runBlocking { opened.follow(ShareConfig(enabled = true, port = port)) }
    }

    @Test
    fun `a command whose head the other machine does not serve reads not answering, and one it serves answering`() {
        val port = TestPorts.reserve()
        torad(port)
        val info = checkNotNull(RemoteHttp(RemoteTrust(null, "torad")).info("127.0.0.1", port).get())
        val joined = RemoteJoin("mini").join(RemoteFound("127.0.0.1", port, info)) as RemoteJoined.Joined
        val toradName = RemoteNames.of(info.server)
        val store = RemotesStore(paths("m").stateDir.resolve("share").resolve("remotes.json"))
        store.save(toradName, joined.token)
        val url = joined.http.base("127.0.0.1", port)
        fun relayed(key: String) = ProviderConfig(
            Dialect.ANTHROPIC_PASSTHROUGH,
            "$url/share/v1/heads/$key",
            AuthConfig(kind = AuthKind.SpliceRemote.wire, remote = toradName),
        )
        val mini = Topology(
            providers = mapOf("torad-one" to relayed("one"), "torad-two" to relayed("two")),
            heads = mapOf("torad-one" to head("torad-one", 3911), "torad-two" to head("torad-two", 3912)),
            remotes = mapOf(toradName to RemoteConfig(url, joined.pin)),
        )
        val links = RemoteLinks(mini, store, SessionsSwitch(ShareState(paths("m"))))

        links.check { }

        assertEquals(mapOf("torad-two" to toradName), links.notAnswering(), "the heads route and doctor read")
        assertEquals(setOf("two"), links.reach().getValue(toradName).down, "the Sharing page and /api/health read")
    }
}
