// NEW: LAN share, round 17 of PR #419's review. The goal: a command this machine offers is served to the machines that
// joined it whenever its head runs, whenever that head came up: one that failed at the daemon's start and came up
// later, or went down and came back, is in the description, its picker and the health a joined machine reads, with no
// restart and no edit to `[share]`. A head not running is in the health as down and serves no picker, and one not
// offered is never shown. Heads come and go in any order, so the property runs over generated runs of heads coming up
// and going down under one open listener: "torad" sharing over its real TLS listener, read as "mini", which joined it,
// reads it.
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
import splice.core.topology.Dialect
import splice.core.topology.HeadConfig
import splice.core.topology.ProviderConfig
import splice.core.topology.ShareConfig
import splice.core.topology.Topology
import splice.core.util.EnvReader
import splice.share.MachinesJoined
import splice.share.ShareListener
import splice.share.ShareStart
import splice.share.SharedCatalogs
import splice.share.SharedQuotas
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

// why: the one model every head names.
private const val HEADS_MODEL = "mock-1"

// why: a window any model row carries; nothing here reads it.
private const val HEADS_WINDOW = 200_000L

// why: a fixed seed, so a failing run of heads coming and going is the same run next time.
private const val HEADS_SEED = 17

// why: steps in the run: enough that each head comes up and goes down several times, the first one at the start.
private const val HEADS_STEPS = 24

// why: the heads `[share]` offers; "three" runs here but is not offered.
private val OFFERED = listOf("one", "two")

class OfferedHeadsTest {

    @TempDir
    lateinit var tmp: Path

    private var listener: ShareListener? = null

    /** The heads running now: what the daemon's managed heads answer the listener. */
    private val up: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @AfterEach
    fun stop() {
        runBlocking { listener?.stop() }
    }

    private fun paths(letter: String): StatePaths {
        val home = tmp.resolve(letter)
        val env = mapOf(
            "SPLICE_STATE_DIR" to home.resolve("state").toString(),
            "SPLICE_CONFIG" to home.resolve("splice.toml").toString(),
            "HOME" to home.toString(),
        )
        return StatePaths(envReader = EnvReader { env[it] })
    }

    /** torad, sharing on [port] heads "one" and "two" of its three, none of which runs yet. */
    private fun torad(port: Int) {
        val mock = ProviderConfig(
            Dialect.ANTHROPIC_PASSTHROUGH,
            "http://127.0.0.1:1",
            AuthConfig(kind = "api-key", env = "MOCK_API_KEY"),
            models = listOf(ModelEntry(HEADS_MODEL, contextWindow = HEADS_WINDOW)),
        )
        fun head(port: Int) =
            HeadConfig(provider = "mock", port = port, discoveryPrefix = "claude-mock--", pinnedModel = HEADS_MODEL)
        val share = ShareConfig(enabled = true, port = port, heads = OFFERED)
        val topology = Topology(
            providers = mapOf("mock" to mock),
            heads = mapOf("one" to head(3901), "two" to head(3902), "three" to head(3903)),
            share = share,
        )
        val catalog = ModelCatalog(
            discoveryPrefix = "claude-mock--",
            models = listOf(ModelEntry(HEADS_MODEL, contextWindow = HEADS_WINDOW)),
            defaultContextWindow = HEADS_WINDOW,
            pinnedModel = HEADS_MODEL,
        )
        val paths = paths("t")
        val opened = ShareListener(
            ShareStart(topology, paths, MgmtKey(paths), ConfigService(paths)) { },
            SharedCatalogs { key -> catalog.takeIf { key in up } },
            SharedQuotas { null },
            paths,
            MachinesJoined { emptySet() },
            { },
        )
        listener = opened
        runBlocking { opened.follow(share) }
    }

    @Test
    fun `an offered head is served whenever it runs, whenever it came up, and one not offered never is`() {
        val port = TestPorts.reserve()
        torad(port)
        val http = RemoteHttp(RemoteTrust(null, "torad"))
        val info = checkNotNull(http.info("127.0.0.1", port).get())
        val joined = RemoteJoin("mini").join(RemoteFound("127.0.0.1", port, info)) as RemoteJoined.Joined
        val name = RemoteNames.of(info.server)
        val store = RemotesStore(paths("m").stateDir.resolve("share").resolve("remotes.json"))
        store.save(name, joined.token)
        val api = RemoteApi(name, RemoteTrust(joined.pin, name), store)
        val url = joined.http.base("127.0.0.1", port)

        val random = Random(HEADS_SEED)
        repeat(HEADS_STEPS) { step ->
            val head = (OFFERED + "three")[random.nextInt(OFFERED.size + 1)]
            if (!up.remove(head)) up += head
            val at = "step $step, running ${up.sorted()}"

            val served = OFFERED.filter { it in up }
            val described = checkNotNull(http.info("127.0.0.1", port).get()).heads.map { it.key }
            assertEquals(served, described, "$at: the description a joining machine reads")
            val health = api.health(url).heads.associate { it.key to it.running }
            assertEquals(OFFERED.associateWith { it in up }, health, "$at: the health a joined machine reads")
            (OFFERED + "three").forEach { key ->
                val picker = runCatching { api.roster("$url/share/v1/heads/$key") }.getOrNull()?.key
                assertEquals(key.takeIf { it in served }, picker, "$at: $key's picker")
            }
        }
    }
}
