// NEW: LAN share, phase 2. What the Sharing page's tests share (SharingRoutesTest, SharingMachinesTest): a REAL
// ControlServer over HTTP with the mgmt key and the real share listener, splice.toml, the state folder and the
// certificate in a temp folder the routes reach through the daemon's environment (SharingParts.env), so nothing here
// reads or writes the machine's own. Split from SharingRoutesTest in round 14 of PR #419's review, past detekt's size.
package splice.app.control

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.io.TempDir
import splice.app.daemon.RemotesInFile
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.util.EnvReader
import splice.sessions.mirror.SessionMirrors
import splice.share.ShareListener
import splice.share.ShareStart
import splice.share.SharedCatalogs
import splice.share.SharedQuotas
import splice.share.remote.RemoteDiscovery
import splice.share.remote.RemoteFound
import splice.share.remote.RemoteHttp
import splice.share.remote.RemoteLinks
import splice.share.remote.RemoteTrust
import splice.topology.TopologyLoader
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path

internal const val SHARE_TIMEOUT_MS = 20_000L

// why: when the machines these tests book joined, as each card shows it.
internal const val JOINED_AT = "2026-10-23T12:00:00Z"
internal const val SHARE_POLL_MS = 20L

// why: longer than any test here runs, so no mirror one makes ends by its time while the page is read.
internal const val MIRRORS_LAST_MS = 3_600_000L

// Two heads on their own key, which this machine can offer, and one that forwards the caller's own Claude login,
// which it cannot.
internal val shareToml = """
    [providers.mock]
    dialect = "anthropic-passthrough"
    base_url = "http://127.0.0.1:1"
    auth = { kind = "api-key", env = "MOCK_API_KEY" }
    [[providers.mock.models]]
    id = "mock-1"
    context_window = 200000

    [providers.anthropic]
    dialect = "anthropic-passthrough"
    base_url = "https://api.anthropic.com"
    auth = { kind = "client" }
    [[providers.anthropic.models]]
    id = "claude-x"
    context_window = 200000

    [heads.one]
    provider = "mock"
    port = 3901
    discovery_prefix = "claude-one--"
    pinned_model = "mock-1"
    [heads.one.claude]
    command = "claude-one"

    [heads.two]
    provider = "mock"
    port = 3902
    discovery_prefix = "claude-two--"
    pinned_model = "mock-1"
    [heads.two.claude]
    command = "claude-two"

    [heads.mine]
    provider = "anthropic"
    port = 3903
    discovery_prefix = "claude-mine--"
    pinned_model = "claude-x"
    [heads.mine.claude]
    command = "claude-mine"
""".trimIndent() + "\n"

/** One request: method, path, body (null = none), and whether to send the mgmt key. */
internal fun interface ShareCall {
    suspend operator fun invoke(method: HttpMethod, path: String, body: String?, authorized: Boolean): HttpResponse
}

/** The real ControlServer the Sharing page's tests serve, with its share listener, over a splice.toml and a state
 *  folder in [tmp], and the page's own requests. */
internal abstract class SharingRoutesFixture {
    @TempDir
    lateinit var tmp: Path

    protected val file by lazy { tmp.resolve("splice.toml").also { Files.writeString(it, shareToml) } }

    protected val env by lazy {
        val vars = mapOf(
            "SPLICE_CONFIG" to file.toString(),
            "SPLICE_STATE_DIR" to tmp.resolve("state").toString(),
            "HOME" to tmp.toString(),
        )
        EnvReader { vars[it] }
    }

    protected fun serve(
        wired: Boolean = true,
        mirrors: SessionMirrors? = null,
        remotes: RemoteLinks? = null,
        environment: EnvReader = env,
        test: suspend (ShareCall) -> Unit,
    ) {
        val paths = StatePaths(envReader = env)
        val mgmt = MgmtKey(paths)
        val config = ConfigService(paths)
        val control = controlServerWith(
            heads = emptyMap(),
            config = config,
            health = topologyHealthFor(emptyMap()),
            auth = ControlAuth(mgmtKey = mgmt, log = { }),
        )
        val booted = TopologyLoader.parse(Files.readString(file))
        val listener = ShareListener(
            ShareStart(booted, paths, mgmt, config) { },
            SharedCatalogs { null },
            SharedQuotas { null },
            paths,
            RemotesInFile(file, booted.remotes.keys),
            { },
            mirrors,
        )
        if (wired) control.ports.sharing = SharingParts(listener, remotes = remotes, env = environment, booted = file)
        runBlocking { control.start() }
        val port = control.listeningPort
        val client = HttpClient(CIO) { expectSuccess = false }
        try {
            runBlocking {
                withTimeout(SHARE_TIMEOUT_MS) {
                    while (runCatching { Socket("127.0.0.1", port).close() }.isFailure) delay(SHARE_POLL_MS)
                    test { method, path, body, authorized ->
                        client.request("http://127.0.0.1:$port$path") {
                            this.method = method
                            if (authorized) header("Authorization", "Bearer ${mgmt.get()}")
                            body?.let { setBody(it) }
                        }
                    }
                }
            }
        } finally {
            client.close()
            runBlocking { listener.stop() }
            control.stop()
        }
    }

    protected fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    protected suspend fun read(call: ShareCall): JsonObject {
        val reply = call(HttpMethod.Get, "/api/share", null, true)
        assertEquals(200, reply.status.value, reply.bodyAsText())
        return json(reply.bodyAsText())
    }

    protected suspend fun put(call: ShareCall, body: String): JsonObject {
        val reply = call(HttpMethod.Put, "/api/share", body, true)
        assertEquals(200, reply.status.value, reply.bodyAsText())
        return json(reply.bodyAsText())
    }

    protected fun commands(view: JsonObject): Map<String, JsonObject> =
        view.getValue("sharing").jsonObject.getValue("commands").jsonArray
            .associate { it.jsonObject.getValue("key").jsonPrimitive.content to it.jsonObject }

    protected fun listening(reply: JsonObject): Boolean =
        reply.getValue("share").jsonObject.getValue("sharing").jsonObject.getValue("listening").jsonPrimitive.boolean

    protected fun answers(port: Int): Boolean = runCatching { Socket("127.0.0.1", port).close() }.isSuccess

    /** What a machine looking for sharing machines finds at [port], scanning as `splice remote` does until [wanted]
     *  says the answer is the one awaited. Of its sources only multicast DNS can name this machine: the subnet and the
     *  tailnet leave out the machine looking. */
    protected suspend fun looked(port: Int, wanted: (RemoteFound?) -> Boolean): RemoteFound? {
        val looking = RemoteDiscovery(RemoteHttp(RemoteTrust(null, "a sharing machine")))
        while (true) {
            val found = looking.scan(port).firstOrNull { it.port == port }
            if (wanted(found)) return found
            delay(SHARE_POLL_MS)
        }
    }

    /** The page's PUT of one Sessions switch at [path], from a card that showed the join of [joined], and whether it
     *  was done. */
    protected suspend fun switched(call: ShareCall, path: String, on: Boolean, joined: String = JOINED_AT): JsonObject {
        val reply = call(HttpMethod.Put, path, "{\"on\":$on,\"joined_at\":\"$joined\"}", true)
        assertEquals(200, reply.status.value, reply.bodyAsText())
        return json(reply.bodyAsText())
    }

    /** The Sessions switch of the one machine that joined this one, and of the one machine this one joined, as each
     *  card shows it; or, with [key] "crossing", whether sessions cross with each. */
    protected fun switches(view: JsonObject, key: String = "sessions"): List<Boolean> =
        listOf("joined", "remotes").map { rows ->
            view.getValue(rows).jsonArray.single().jsonObject.getValue(key).jsonPrimitive.boolean
        }
}
