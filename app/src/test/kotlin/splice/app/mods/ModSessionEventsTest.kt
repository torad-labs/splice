package splice.app.mods

import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.mods.run.ModCallRows
import splice.core.util.AsyncFileIo
import splice.launch.LaunchModSettings
import splice.sessions.registry.RouteOfPid
import splice.sessions.registry.SEEN_SESSIONS_DIR
import splice.sessions.registry.SessionRegistry
import splice.sessions.registry.SessionRoute
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.coroutineContext

class ModSessionEventsTest {
    @TempDir lateinit var root: Path

    @Test
    fun `a resumed session keeps one id across processes and ends each process separately`() = testApplication {
        val rig = ModEventsTestRig(root)
        application { routing { rig.runner.mount.register(this) } }
        val first = ProcessBuilder("/bin/sleep", "60").start()
        val second = ProcessBuilder("/bin/sleep", "60").start()
        val registry = SessionRegistry(root.resolve("registrations"), RouteOfPid { SessionRoute.Unknown })
        rig.runner.runner.start(ModCallRows(registry, rig.launches, rig.text, rig.sessions))
        rig.sessions.start(CoroutineScope(coroutineContext))
        try {
            rig.install(client, "recorder")
            val one = rig.launch(first.pid(), LaunchModSettings(labels = mapOf("worker" to "one")))
            val two = rig.launch(second.pid(), LaunchModSettings(labels = mapOf("worker" to "two")))
            rig.launches.sessionStarted(one, "shared", "/work")
            rig.complete("shared", one)
            rig.complete("shared", two)
            first.destroyForcibly().waitFor()
            val events = rig.through("recorder", "session.end")
            val starts = events.filter { it.name == "session.start" }
            assertEquals(listOf("shared", "shared"), starts.map { it.session })
            assertEquals(listOf("false", "true"), starts.map { it.payload["seenBefore"].toString() })
            assertEquals("""{"worker":"one"}""", events.last().payload["labels"].toString())
            val row = rig.rows("recorder").single()
            assertEquals("shared", row.getValue("id").jsonPrimitive.content)
            assertEquals("""{"worker":"two"}""", row["labels"].toString())
        } finally {
            rig.sessions.stop()
            rig.runner.runner.stop()
            first.destroyForcibly().waitFor()
            second.destroyForcibly().waitFor()
        }
    }

    @Test
    fun `seen before survives restart and honors the existing sessions history`() = testApplication {
        val previous = ModEventsTestRig(root)
        previous.complete("resumed", previous.launch())
        check(AsyncFileIo.drain())
        val seen = Files.createDirectories(previous.runner.paths.stateDir.resolve(SEEN_SESSIONS_DIR))
        Files.writeString(seen.resolve("existing.json"), """{"session_id":"existing","last_activity_ms":1}""")

        val rig = ModEventsTestRig(root)
        rig.launches.restore()
        rig.sessions.restore(rig.launches)
        application { routing { rig.runner.mount.register(this) } }
        rig.runner.runner.start()
        try {
            rig.install(client, "recorder")
            rig.complete("resumed", rig.launch())
            rig.complete("existing", rig.launch())
            val starts = rig.through("recorder", "turn.complete", "existing").filter { it.name == "session.start" }
            assertEquals(listOf("resumed", "existing"), starts.map { it.session })
            assertEquals(listOf("true", "true"), starts.map { it.payload["seenBefore"].toString() })
        } finally {
            rig.runner.runner.stop()
        }
    }

    @Test
    fun `installing a mod does not announce a session that already began`() = testApplication {
        val rig = ModEventsTestRig(root)
        application { routing { rig.runner.mount.register(this) } }
        rig.runner.runner.start()
        try {
            val owner = rig.launch()
            rig.complete("already", owner)
            rig.install(client, "recorder")
            rig.complete("already", owner)
            val names = rig.through("recorder", "turn.complete").map { it.name }
            assertEquals(listOf("turn.start", "turn.complete"), names)
        } finally {
            rig.runner.runner.stop()
        }
    }
}
