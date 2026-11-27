package splice.app.mods

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.cli.mod.ModLines
import splice.app.mods.handover.MOD_HANDOVER_CAPACITY
import splice.core.turn.ObservedRequest
import splice.core.turn.ObservedResponse
import splice.core.turn.ObservedStreamFrame
import splice.core.turn.ObservedToolCall
import splice.core.turn.ObservedTurnEnd
import splice.core.turn.ObservedUsage
import java.nio.file.Path

class ModHandoverTest {
    @TempDir lateinit var root: Path

    @Test
    fun `a turn spanning handover reaches a mod once and in order`() = runBlocking {
        ModHandoverTestRig(root).use { rig ->
            rig.installRecorder()
            val turn = rig.startTurn("session")
            turn.requestSent(ObservedRequest(1, "model", 64, 2))
            rig.through("request.send")

            rig.retire()
            turn.responseReceived(ObservedResponse(200, 1, 3))
            turn.frame(ObservedStreamFrame(1, "message_start", 32, 4))
            turn.toolCall(ObservedToolCall("call", "Read", 5))
            turn.completed(ObservedTurnEnd("ok", ObservedUsage(0, 0, 0, 0), 1, 6))
            rig.finish()

            val events = rig.through("turn.complete").filterNot { it.name.startsWith("mod.") }
            assertEquals(
                listOf(
                    "session.start",
                    "turn.start",
                    "request.send",
                    "response.receive",
                    "stream.event",
                    "tool.call",
                    "turn.complete",
                ),
                events.map { it.name },
            )
            val id = events[1].payload.getValue("turn")
            assertTrue(events.drop(1).all { it.payload["turn"] == id })
        }
    }

    @Test
    fun `events dropped during handover are visible in the mods route`() = runBlocking {
        ModHandoverTestRig(root).use { rig ->
            rig.installRecorder()
            val turn = rig.startTurn("session")
            rig.through("turn.start")
            rig.retire(leave = false)
            repeat(MOD_HANDOVER_CAPACITY + 1) { turn.frame(ObservedStreamFrame(it.toLong(), "delta", 1, 2)) }
            rig.leave()
            rig.finish()

            HttpClient(CIO).use { client ->
                assertEquals(HttpStatusCode.Unauthorized, client.post(rig.handoverUrl).status)
                val response = client.get(rig.modsUrl) { bearerAuth(rig.key.get()) }
                assertEquals(HttpStatusCode.OK, response.status)
                val mods = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                assertEquals(1L, mods.getValue("handoverDropped").jsonPrimitive.long)
                assertTrue("Events dropped during daemon handover: 1." in ModLines().list(mods))
            }
        }
    }

    @Test
    fun `the old recorder process is gone when the old daemon stops`() = runBlocking {
        ModHandoverTestRig(root).use { rig ->
            rig.installRecorder()
            val old = rig.recorderProcess()
            assertTrue(old.isAlive)
            rig.retire()
            rig.finish()
            assertFalse(old.isAlive)
        }
    }

    @Test
    fun `the successor recorder starts only after the old process is gone`() = runBlocking {
        ModHandoverTestRig(root).use { rig ->
            rig.installRecorder(slowStop = true)
            val old = rig.recorderProcess()
            rig.retire()
            val successor = rig.successorRecorderStart()
            assertFalse(successor.oldProcessAlive)
            assertFalse(old.isAlive)
            assertTrue(successor.pid != old.pid())
            rig.finish()
        }
    }
}
