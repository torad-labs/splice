// Removing a command, THROUGH A REAL ControlServer on a real splice.toml: a live session on the command refuses it and is
// named, a removal drops the head and its project layers from the file with a backup, unlinks the wrapper only when no
// other head shares the command, records the command as retired, and leaves its history findable by its key.
package splice.app.control

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.configuration.topology.TopologyStale
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.retired.RetiredHeads
import splice.core.topology.TopologyParse
import splice.core.topology.TopologyWriter
import splice.sessions.registry.SessionAvailability
import splice.sessions.registry.SessionClient
import splice.sessions.registry.SessionListing
import splice.sessions.registry.SessionProcess
import splice.sessions.registry.SessionRecord
import splice.sessions.registry.SessionRoute
import splice.sessions.registry.SessionSource
import splice.sessions.registry.SessionStatus
import splice.topology.TopologyLoader
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path

private const val TIMEOUT_MS = 20_000L
private const val POLL_MS = 20L
private val TOML = """
    [providers.local]
    dialect = "openai-chat"
    base_url = "http://127.0.0.1:3105/v1"
    auth = { kind = "api-key", env = "LOCAL_API_KEY" }
    [[providers.local.models]]
    id = "m"
    label = "M"
    context_window = 200000

    [heads.alpha]
    provider = "local"
    port = 3102
    discovery_prefix = "claude-alpha--"
    pinned_model = "m"
    [heads.alpha.claude]
    command = "claude-alpha"

    [heads.beta]
    provider = "local"
    port = 3103
    discovery_prefix = "claude-beta--"
    pinned_model = "m"
    [heads.beta.claude]
    command = "claude-alpha"

    [heads.gamma]
    provider = "local"
    port = 3104
    discovery_prefix = "claude-gamma--"
    pinned_model = "m"
    [heads.gamma.claude]
    command = "claude-gamma"

    [projects."/work/app"]
    system_prompt = "Plan first."
    [projects."/work/app".heads.gamma]
    system_prompt = "Gamma only."
""".trimIndent() + "\n"

class HeadRemovalTest {
    @TempDir
    lateinit var tmp: Path

    private val file by lazy { tmp.resolve("splice.toml").also { Files.writeString(it, TOML) } }
    private val bin by lazy { Files.createDirectories(tmp.resolve("bin")) }
    private val paths by lazy { StatePaths(baseOverride = tmp.resolve("state")) }

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private class OneSession(private val record: SessionRecord) : SessionSource {
        override fun read(): List<SessionRecord> = listOf(record)

        override fun list(): SessionListing = SessionListing(read())
    }

    private fun live(head: String, id: String, availability: SessionAvailability): SessionSource = OneSession(
        SessionRecord(
            sessionId = id,
            name = "refactor",
            status = SessionStatus(),
            route = SessionRoute.Head(head),
            availability = availability,
            process = SessionProcess(
                pid = 7,
                cwd = null,
                startedAt = null,
                updatedAt = null,
                messagingSocketPath = null,
            ),
            client = SessionClient(kind = null, version = null),
        ),
    )

    private fun serve(
        sessions: SessionSource? = null,
        test: suspend (suspend (HttpMethod, String) -> HttpResponse) -> Unit,
    ) {
        val mgmt = MgmtKey(paths)
        val control = controlServerWith(
            heads = emptyMap(),
            config = ConfigService(paths),
            health = topologyHealthFor(emptyMap(), topologyStale = TopologyStale { true }),
            auth = ControlAuth(mgmtKey = mgmt, log = { }),
            runtime = ControlRuntime(sessions = sessions),
        )
        control.ports.topology = TopologyWriter(file, tmp.resolve("backups"), TopologyParse(TopologyLoader::parse))
        control.ports.wrapperBin = bin
        runBlocking { control.start() }
        val port = control.listeningPort
        val client = HttpClient(CIO) { expectSuccess = false }
        try {
            runBlocking {
                withTimeout(TIMEOUT_MS) {
                    while (runCatching { Socket("127.0.0.1", port).close() }.isFailure) delay(POLL_MS)
                    test { method, route ->
                        client.request("http://127.0.0.1:$port$route") {
                            this.method = method
                            header("Authorization", "Bearer ${mgmt.get()}")
                        }
                    }
                }
            }
        } finally {
            client.close()
            control.stop()
        }
    }

    private fun heads() = TopologyLoader.parse(Files.readString(file)).heads

    private suspend fun liveSession(reply: HttpResponse, field: String): String =
        json(reply.bodyAsText()).getValue("live_sessions").jsonArray.single().jsonObject
            .getValue(field).jsonPrimitive.content

    private fun link(command: String): Path = bin.resolve(command).also {
        Files.createSymbolicLink(it, tmp.resolve("splice-launch"))
    }

    @Test
    fun `a command a live session is on is refused and the sessions are named`() {
        val wrapper = link("claude-gamma")
        serve(live("gamma", "s-1", SessionAvailability.LIVE)) { call ->
            val read = call(HttpMethod.Get, "/api/heads/gamma/removal")
            assertEquals(200, read.status.value)
            assertEquals("s-1", liveSession(read, "id"))

            val refused = call(HttpMethod.Delete, "/api/heads/gamma")
            assertEquals(409, refused.status.value)
            assertEquals("refactor", liveSession(refused, "name"))
        }
        assertTrue(heads().containsKey("gamma"), "the head is still in the file")
        assertTrue(Files.isSymbolicLink(wrapper), "the wrapper stays")
        assertTrue(RetiredHeads(paths.stateDir).all().isEmpty(), "nothing is recorded as retired")
    }

    @Test
    fun `a removal drops the head and its project layer, unlinks its wrapper, and keeps its history findable`() {
        val wrapper = link("claude-gamma")
        Files.createDirectories(paths.stateDir)
        Files.writeString(paths.perfStatsFile("gamma"), "")
        serve(live("gamma", "s-1", SessionAvailability.GONE)) { call ->
            val before = call(HttpMethod.Get, "/api/perf/turns?head=gamma")
            assertEquals(400, before.status.value, "an unknown head is refused until it is retired")

            val removed = call(HttpMethod.Delete, "/api/heads/gamma")
            assertEquals(200, removed.status.value)
            val body = json(removed.bodyAsText())
            assertTrue(body.getValue("restart_required").jsonPrimitive.content.toBoolean())

            val after = call(HttpMethod.Get, "/api/perf/turns?head=gamma")
            assertEquals(200, after.status.value, "its requests are still read by its key")
            val byCommand = call(HttpMethod.Get, "/api/perf/turns?head=claude-gamma")
            assertEquals(200, byCommand.status.value, "and by the command a person typed")
            val listed = json(call(HttpMethod.Get, "/api/heads/retired").bodyAsText()).getValue("retired").jsonArray
            assertEquals(listOf("gamma"), listed.map { it.jsonObject.getValue("key").jsonPrimitive.content })
            assertEquals("claude-gamma", listed.single().jsonObject.getValue("command").jsonPrimitive.content)
        }
        val stored = TopologyLoader.parse(Files.readString(file))
        assertFalse(stored.heads.containsKey("gamma"))
        assertTrue(stored.heads.containsKey("alpha"), "the other heads stay")
        assertFalse(stored.projects.getValue("/work/app").heads.containsKey("gamma"), "its project layer goes with it")
        assertEquals(
            "Plan first.",
            stored.projects.getValue("/work/app").systemPrompt,
            "the project's own prompt stays",
        )
        assertFalse(Files.exists(wrapper, java.nio.file.LinkOption.NOFOLLOW_LINKS), "its wrapper is gone")
        assertEquals(listOf("gamma"), RetiredHeads(paths.stateDir).all().map { it.key })
        assertTrue(Files.list(tmp.resolve("backups")).use { it.count() } >= 1, "the topology writer took a backup")
        assertTrue(Files.exists(paths.perfStatsFile("gamma")), "the store stays in place")
    }

    @Test
    fun `a wrapper another head still answers to is kept`() {
        val wrapper = link("claude-alpha")
        serve { call ->
            assertEquals(200, call(HttpMethod.Delete, "/api/heads/alpha").status.value)
        }
        assertTrue(Files.isSymbolicLink(wrapper), "beta runs on the same command")
        assertFalse(TopologyLoader.parse(Files.readString(file)).heads.containsKey("alpha"))
    }

    @Test
    fun `a head the file does not hold is a 404 and nothing is recorded`() {
        serve { call ->
            assertEquals(404, call(HttpMethod.Delete, "/api/heads/nobody").status.value)
            assertEquals(404, call(HttpMethod.Get, "/api/heads/nobody/removal").status.value)
        }
        assertTrue(RetiredHeads(paths.stateDir).all().isEmpty())
    }
}
