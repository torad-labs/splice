package splice.app.mods.sessions

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.control.ConsolePorts
import splice.client.transcript.TranscriptReader
import splice.core.util.AsyncFileIo
import splice.launch.LaunchModSettings
import splice.launch.LaunchNotice
import splice.sessions.http.SessionsRoutes
import splice.sessions.registry.HeadlessSessionSource
import splice.sessions.registry.SessionRegistry
import splice.sessions.registry.SessionRoute
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit

class HeadlessLaunchRetentionTest {
    @Test
    fun `a gone headless row leaves the listing when its launch expires`(@TempDir dir: Path) {
        var now = 1_789_312_411_660L
        val installed = object : InstalledModNames {
            override fun names(): Set<String> = emptySet()
        }
        val launches = ModLaunches(dir.resolve("mods"), {}, installed, clock = { now })
        val ports = ConsolePorts().apply { modLaunches = launches }
        val registry = SessionRegistry(dir.resolve("registry"), routeOf = { SessionRoute.Unknown })
        val routes = SessionsRoutes(HeadlessSessionSource(registry, HeadlessSessionRows(ports)), TranscriptReader())

        val launch = LaunchNotice(UUID.randomUUID().toString(), null, "/work", LaunchModSettings())
        val session = UUID.randomUUID().toString()
        launches.launched(launch)
        launches.forTurn(session, launch.owner)
        check(AsyncFileIo.drain())

        val row = rows(routes).single().jsonObject
        assertEquals(session, row.getValue("session_id").jsonPrimitive.content)
        assertEquals("gone", row.getValue("availability").jsonPrimitive.content)

        now += TimeUnit.DAYS.toMillis(1)
        launches.launched(launch.copy(owner = UUID.randomUUID().toString()))
        check(AsyncFileIo.drain())
        assertEquals(0, rows(routes).size)
    }

    private fun rows(routes: SessionsRoutes) =
        Json.parseToJsonElement(routes.sessionsJson()).jsonObject.getValue("sessions").jsonArray
}
