// NEW (Nov 13, 2026): a session's fallback model as a person sets it, through the routes' own answers. A session gets a
// model its command has, keeps it across a daemon restart, and loses it when taken away; a model its command lacks and
// a session splice does not know are each refused in words, with nothing kept.
package splice.app.control.mount

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.control.ConsolePorts
import splice.app.control.HeadSources
import splice.app.control.ManagedHead
import splice.app.control.StatuslineContext
import splice.app.control.UsageWarning
import splice.app.control.UsageWarningSource
import splice.app.control.mount.fallback.SessionFallbackRoute
import splice.app.head.SESSION_FALLBACKS_FILE
import splice.app.head.SessionFallbackStore
import splice.core.auth.AuthDescription
import splice.core.auth.AuthProvider
import splice.core.head.Head
import splice.core.head.HeadHealth
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.diagnostics.logs.HeadLogSource
import splice.head.compact.CompactView
import splice.head.compact.HeadCompactSource
import splice.http.JsonReply
import splice.sessions.registry.SessionAvailability
import splice.sessions.registry.SessionClient
import splice.sessions.registry.SessionListing
import splice.sessions.registry.SessionProcess
import splice.sessions.registry.SessionRecord
import splice.sessions.registry.SessionRoute
import splice.sessions.registry.SessionSource
import splice.sessions.registry.SessionStatus
import splice.usage.quota.HeadUsageSource
import splice.usage.quota.RateLimitView
import splice.usage.quota.UsageView
import java.nio.file.Path

private const val SESSION = "0b9c-a-session"
private const val FABLE = "claude-fable-5-2"
private const val OPUS = "claude-opus-5-5"
private const val OTHER = "7f1e-another-session"

class SessionFallbackRouteTest {
    private val sessions = registry(record(SESSION, "builder"))

    @Test
    fun `a session given a model its command has keeps it across a restart until it is taken away`(@TempDir tmp: Path) {
        val set = route(tmp).set(SESSION, """{"model":"$OPUS"}""")
        assertEquals(HttpStatusCode.OK, set.status)
        assertTrue(said(set).contains(OPUS), said(set))

        // a new daemon reads the same file
        assertEquals(mapOf(SESSION to OPUS), listed(route(tmp).list()))

        val cleared = route(tmp).clear(SESSION)
        assertEquals(HttpStatusCode.OK, cleared.status)
        assertEquals(emptyMap<String, String>(), listed(route(tmp).list()))
    }

    @Test
    fun `a running session is named by its name, and its setting is kept under its id`(@TempDir tmp: Path) {
        assertEquals(HttpStatusCode.OK, route(tmp).set("builder", """{"model":"$OPUS"}""").status)
        assertEquals(mapOf(SESSION to OPUS), listed(route(tmp).list()))
        assertEquals(HttpStatusCode.OK, route(tmp).clear("builder").status)
        assertEquals(emptyMap<String, String>(), listed(route(tmp).list()))
    }

    @Test
    fun `a model the session's command does not have is refused, and nothing is kept`(@TempDir tmp: Path) {
        val reply = route(tmp).set(SESSION, """{"model":"gpt-6-luna"}""")
        assertEquals(HttpStatusCode.BadRequest, reply.status)
        assertTrue(error(reply).contains("gpt-6-luna"), error(reply))
        assertEquals(emptyMap<String, String>(), listed(route(tmp).list()))
    }

    @Test
    fun `a session splice does not know is refused, and nothing is kept`(@TempDir tmp: Path) {
        val reply = route(tmp).set("not-a-session", """{"model":"$OPUS"}""")
        assertEquals(HttpStatusCode.NotFound, reply.status)
        assertEquals(emptyMap<String, String>(), listed(route(tmp).list()))
    }

    @Test
    fun `clearing a name two running sessions share is refused, and both keep theirs`(@TempDir tmp: Path) {
        val twins = registry(record(SESSION, "twin"), record(OTHER, "twin"))
        assertEquals(HttpStatusCode.OK, route(tmp, twins).set(SESSION, """{"model":"$OPUS"}""").status)
        assertEquals(HttpStatusCode.OK, route(tmp, twins).set(OTHER, """{"model":"$OPUS"}""").status)

        val reply = route(tmp, twins).clear("twin")
        assertEquals(HttpStatusCode.Conflict, reply.status)
        assertTrue(error(reply).contains("its id"), error(reply))
        assertEquals(mapOf(SESSION to OPUS, OTHER to OPUS), listed(route(tmp, twins).list()))
    }

    @Test
    fun `a session no longer listed is cleared by its id, and an unknown id is refused`(@TempDir tmp: Path) {
        assertEquals(HttpStatusCode.OK, route(tmp).set(SESSION, """{"model":"$OPUS"}""").status)
        val forgotten = registry()

        assertEquals(HttpStatusCode.OK, route(tmp, forgotten).clear(SESSION).status)
        assertEquals(emptyMap<String, String>(), listed(route(tmp, forgotten).list()))
        assertEquals(HttpStatusCode.NotFound, route(tmp, forgotten).clear("not-a-session").status)
    }

    private fun route(tmp: Path, registry: SessionSource = sessions): SessionFallbackRoute {
        val store = SessionFallbackStore(tmp.resolve(SESSION_FALLBACKS_FILE))
        val ports = ConsolePorts().apply { sessionFallbacks = store }
        return SessionFallbackRoute(registry, mapOf("claude" to claudeHead()), ports)
    }

    private fun registry(vararg records: SessionRecord): SessionSource = object : SessionSource {
        override fun read(): List<SessionRecord> = records.toList()

        override fun list(): SessionListing = SessionListing(read())
    }

    private fun record(id: String, name: String): SessionRecord = SessionRecord(
        sessionId = id,
        name = name,
        status = SessionStatus(),
        route = SessionRoute.Head("claude"),
        availability = SessionAvailability.LIVE,
        process = SessionProcess(1L, "/", 0L, 0L, null),
        client = SessionClient("claude-code", "2.1.325"),
    )

    private fun body(reply: JsonReply): JsonObject = Json.parseToJsonElement(reply.body).jsonObject

    private fun said(reply: JsonReply): String = body(reply).getValue("said").jsonPrimitive.content

    private fun error(reply: JsonReply): String = body(reply).getValue("error").jsonPrimitive.content

    private fun listed(reply: JsonReply): Map<String, String> =
        body(reply).getValue("sessions").jsonObject.mapValues { it.value.jsonPrimitive.content }

    private fun claudeHead(): ManagedHead = ManagedHead(
        head = object : Head {
            override val key: String = "claude"
            override val label: String = "claude"
            override val port: Int = 0
            override suspend fun start() = Unit
            override suspend fun stop() = Unit
            override fun healthSnapshot(): HeadHealth = HeadHealth(true, true, port, "test")
        },
        auth = object : AuthProvider {
            override suspend fun credentials() = null
            override suspend fun describe() = AuthDescription(false, "client", emptyMap())
        },
        sources = HeadSources(
            usage = HeadUsageSource { UsageView(0, 0, RateLimitView(null, null, null)) },
            compact = object : HeadCompactSource {
                override fun summary(tailN: Int): CompactView = CompactView(0, emptyMap(), emptyList())
            },
            logs = object : HeadLogSource {
                override fun tail(lines: Int): String = ""
                override fun path(): String = ""
            },
        ),
        usageWarning = UsageWarningSource { UsageWarning(warnPct = 80, warnTokens5h = 0) },
        statusline = StatuslineContext(
            catalog = ModelCatalog(
                discoveryPrefix = "claude--",
                models = listOf(
                    ModelEntry(FABLE, "Fable", contextWindow = 1_000_000),
                    ModelEntry(OPUS, "Opus", contextWindow = 1_000_000),
                ),
                defaultContextWindow = 1_000_000,
            ),
        ),
    )
}
