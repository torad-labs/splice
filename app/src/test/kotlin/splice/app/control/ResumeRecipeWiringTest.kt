// NEW: V4-320 — the resume recipe answers THROUGH A REAL ControlServer, over HTTP with the mgmt key:
// GET /api/sessions/{id}/resume?head=<key> is routed (a missing routing line answers 404 here, by
// name), guarded (no key is a 401), refuses a head whose command is not linked with the fix the add
// flow names, and answers a linked head's recipe with whether the original runs read from the server's
// own registry. ResumeRecipeRouteTest proves the recipe itself on the route class.
package splice.app.control

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.control.mount.RegistryLive
import splice.core.auth.AuthDescription
import splice.core.auth.AuthProvider
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.head.Head
import splice.core.head.HeadHealth
import splice.diagnostics.logs.HeadLogSource
import splice.head.compact.CompactView
import splice.head.compact.HeadCompactSource
import splice.launch.HeadTrees
import splice.launch.LaunchSpec
import splice.sessions.registry.SessionRegistry
import splice.sessions.registry.SessionRoute
import splice.usage.quota.HeadUsageSource
import splice.usage.quota.RateLimitView
import splice.usage.quota.UsageView
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path

private const val SESSION = "f7f7f7f7-0000-4000-8000-000000000007"
private const val OTHER = "f7f7f7f7-0000-4000-8000-000000000008"
private const val RESUME_AT = 1_789_725_600_000L

/** Past the registry's 30-minute stale window. */
private const val STALE_BY_MS = 31L * 60L * 1000L

/** A wrapper command no machine links: the recipe for it is refused, whoever runs the test. */
private const val UNLINKED = "splice-resume-wiring-unlinked"
private const val TIMEOUT_MS = 10_000L
private const val POLL_MS = 20L

class ResumeRecipeWiringTest {

    @TempDir
    lateinit var tmp: Path

    private fun json(body: String) = Json.parseToJsonElement(body).jsonObject

    @Test
    fun `the resume recipe is routed, guarded, and refuses an unlinked command by its fix - V4-320`() {
        serve { get, bare ->
            val reply = get("/api/sessions/$SESSION/resume?head=codex")
            assertEquals(409, reply.status.value, reply.bodyAsText())
            assertEquals(
                json("""{"error":"The $UNLINKED command is not linked; run splice install codex."}"""),
                json(reply.bodyAsText()),
            )
            assertEquals(401, bare("/api/sessions/$SESSION/resume?head=codex").status.value)
        }
    }

    /** A command named by an absolute path is linked when that path is a link: `Path.resolve` of an
     *  absolute path IS that path, so the recipe's default check reads it where `splice install` would
     *  have put a bare name, and the test needs no install dir of its own. */
    @Test
    fun `a linked head answers the recipe, and whether the original runs comes from the registry - V4-320`() {
        val command = Files.createSymbolicLink(tmp.resolve("claudex"), tmp.resolve("claudex-target")).toString()
        serve(command) { get, _ ->
            val reply = get("/api/sessions/$SESSION/resume?head=codex")
            assertEquals(200, reply.status.value, reply.bodyAsText())
            val transcript = tmp.resolve("codex-tree").resolve("projects").resolve("-work").resolve("$SESSION.jsonl")
            assertEquals(
                json(
                    """{"session_id":"$SESSION","head":"codex","argv":["$command","-r","$SESSION"],
                    |"from":"$transcript","to_tree":"${transcript.parent}","copies":false,
                    |"model":"gpt-6-sol","live":true}
                    """.trimMargin(),
                ),
                json(reply.bodyAsText()),
            )
        }
    }

    @Test
    fun `the original runs only when the registry holds it live, and no registry says nothing - V4-320`() {
        val sessions = registration()
        val registry = { alive: Boolean, at: Long ->
            SessionRegistry(sessions, routeOf = { SessionRoute.Unknown }, pidAlive = { alive }, clock = { at })
        }
        assertEquals(true, RegistryLive(registry(true, RESUME_AT)).live(SESSION))
        assertEquals(false, RegistryLive(registry(true, RESUME_AT + STALE_BY_MS)).live(SESSION), "stale")
        assertEquals(false, RegistryLive(registry(false, RESUME_AT)).live(SESSION), "gone")
        assertEquals(false, RegistryLive(registry(true, RESUME_AT)).live(OTHER), "not registered")
        assertNull(RegistryLive(null).live(SESSION))
    }

    /** Claude Code's registry holding [SESSION], registered by pid 7 at [RESUME_AT]. */
    private fun registration(): Path {
        val sessions = Files.createDirectories(tmp.resolve("sessions"))
        Files.writeString(
            sessions.resolve("7.json"),
            """{"pid":7,"sessionId":"$SESSION","updatedAt":$RESUME_AT,"messagingSocketPath":"/run/7.sock"}""",
        )
        return sessions
    }

    /** A real control plane with one launchable head, run as [command], holding [SESSION] in its own
     *  tree, and the registry holding it live. */
    private fun serve(
        command: String = UNLINKED,
        test: suspend (get: suspend (String) -> HttpResponse, bare: suspend (String) -> HttpResponse) -> Unit,
    ) {
        val own = tmp.resolve("codex-tree")
        Files.createDirectories(own.resolve("projects").resolve("-work")).resolve("$SESSION.jsonl").let {
            Files.writeString(it, "{}\n")
        }
        val sessions = registration()
        val paths = StatePaths(baseOverride = tmp.resolve("state"))
        val mgmt = MgmtKey(paths)
        val control = ControlServer(
            port = 0,
            heads = mapOf("codex" to managedHead(own, command)),
            config = ConfigService(paths),
            mgmtKey = mgmt,
            dashboardHtml = { "<!doctype html>" },
            log = { },
            sessions = SessionRegistry(
                sessionsDir = sessions,
                routeOf = { SessionRoute.Unknown },
                pidAlive = { true },
                clock = { RESUME_AT },
            ),
        )
        runBlocking { control.start() }
        val port = control.listeningPort
        val client = HttpClient(CIO) { expectSuccess = false }
        try {
            runBlocking {
                withTimeout(TIMEOUT_MS) {
                    while (runCatching { Socket("127.0.0.1", port).close() }.isFailure) delay(POLL_MS)
                    val url = { path: String -> "http://127.0.0.1:$port$path" }
                    test(
                        { path -> client.get(url(path)) { header("Authorization", "Bearer ${mgmt.get()}") } },
                        { path -> client.get(url(path)) },
                    )
                }
            }
        } finally {
            client.close()
            control.stop()
        }
    }

    private fun managedHead(own: Path, command: String): ManagedHead = ManagedHead(
        head = object : Head {
            override val key: String = "codex"
            override val label: String = command
            override val port: Int = 0
            override suspend fun start() = Unit
            override suspend fun stop() = Unit
            override fun healthSnapshot(): HeadHealth = HeadHealth(true, true, port, "test")
        },
        auth = object : AuthProvider {
            override suspend fun credentials() = null
            override suspend fun describe() = AuthDescription(false, "test", emptyMap())
        },
        usage = HeadUsageSource { UsageView(0, 0, RateLimitView(null, null, null)) },
        compact = object : HeadCompactSource {
            override fun summary(tailN: Int): CompactView = CompactView(0, emptyMap(), emptyList())
        },
        logs = object : HeadLogSource {
            override fun tail(lines: Int): String = ""
            override fun path(): String = ""
        },
        warnPct = 80,
        warnTokens5h = 0,
        launchSpec = LaunchSpec(
            trees = HeadTrees(own),
            pinnedModel = "gpt-6-sol",
            availableModelIds = listOf("gpt-6-sol"),
            modelLabels = mapOf("gpt-6-sol" to "Sol"),
            contextWindow = 272_000,
            modelOptionsCache = buildJsonObject { },
            statuslineCommand = "",
            loginCommand = "",
            signInLabel = "",
            policy = splice.client.ClaudePolicy(share = emptySet(), isolate = emptySet()),
            port = 0,
            inferenceToken = "t",
            apiTimeoutMs = 1_000,
        ),
    )
}
