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
import splice.launch.LaunchModSettings
import splice.launch.ModsOff
import java.nio.file.Path

class ModHandoverTest {
    @TempDir lateinit var root: Path

    @Test
    fun `a turn spanning handover reaches a mod once and in order`() = runBlocking {
        ModHandoverTestRig(root).use { rig ->
            rig.installRecorder()
            val turn = rig.startTurn("session")
            rig.holdRequest()
            turn.requestSent(ObservedRequest(1, "model", 2))
            rig.through("request.send")
            turn.responseReceived(ObservedResponse(200, 1, 3))

            rig.retire()
            rig.releaseRequest()
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
                val dropped = mods.getValue("handoverDropped").jsonPrimitive.long
                assertTrue(dropped >= 1)
                assertTrue("Events dropped during daemon handover: $dropped." in ModLines().list(mods))
            }
        }
    }

    @Test
    fun `a start missing from disk is not replayed and an early successor turn is delivered`() = runBlocking {
        ModHandoverTestRig(root).use { rig ->
            rig.installRecorder()
            val owner = rig.launchOwner()
            rig.startTurn("same", owner)
            rig.through("turn.start")
            rig.retire(leave = false)
            rig.forgetLedger("same", owner)
            rig.openSuccessor()
            rig.successorTurn("same", owner).completed(ObservedTurnEnd("ok", ObservedUsage(0, 0, 0, 0), 1, 2))
            rig.releaseRelay()
            rig.recoverSessions()
            rig.finish()
            val events = rig.through("turn.complete")
            assertEquals(listOf("session.start", "turn.start", "turn.start", "turn.complete"), events.map { it.name })
        }
    }

    @Test
    fun `a new session on the successor starts before its first turn is delivered`() = runBlocking {
        ModHandoverTestRig(root).use { rig ->
            rig.installRecorder()
            rig.retire(leave = false)
            rig.openSuccessor()
            rig.successorTurn("early").completed(ObservedTurnEnd("ok", ObservedUsage(0, 0, 0, 0), 1, 2))
            rig.releaseRelay()
            rig.recoverSessions()
            rig.finish()
            val events = rig.through("turn.complete", "early").filter { it.session == "early" }
            assertEquals(listOf("session.start", "turn.start", "turn.complete"), events.map { it.name })
        }
    }

    @Test
    fun `an early turn keeps its original launch opt out`() = runBlocking {
        ModHandoverTestRig(root).use { rig ->
            rig.installRecorder()
            val off = rig.launchOwner(LaunchModSettings(modsOff = ModsOff(names = setOf("recorder"))))
            rig.retire(leave = false)
            rig.openSuccessor()
            val end = ObservedTurnEnd("ok", ObservedUsage(0, 0, 0, 0), 1, 2)
            rig.successorTurn("quiet", off).completed(end)
            rig.successorTurn("visible").completed(end)
            rig.releaseRelay()
            rig.recoverSessions()
            rig.finish()
            assertTrue(rig.through("turn.complete", "visible").none { it.session == "quiet" })
        }
    }

    @Test
    fun `the old turn tail reaches a successor that opens its socket late`() = runBlocking {
        ModHandoverTestRig(root).use { rig ->
            rig.installRecorder()
            val turn = rig.startTurn("held")
            rig.through("turn.start")
            rig.retire(leave = false)
            turn.completed(ObservedTurnEnd("ok", ObservedUsage(0, 0, 0, 0), 1, 2))
            rig.releaseRelay()
            rig.awaitRelayWaiting()
            rig.openSuccessor()
            rig.recoverSessions()
            rig.finish()
            assertEquals(1, rig.through("turn.complete").count { it.name == "turn.complete" })
        }
    }

    @Test
    fun `an accepted event appears in handover drops when the replacement cannot load`() = runBlocking {
        ModHandoverTestRig(root).use { rig ->
            rig.installRecorder(linked = true)
            val turn = rig.startTurn("held")
            rig.through("turn.start")
            rig.retire(leave = false)
            rig.breakReplacement()
            turn.completed(ObservedTurnEnd("ok", ObservedUsage(0, 0, 0, 0), 1, 2))
            rig.leave()
            rig.finish()

            HttpClient(CIO).use { client ->
                val mods = rig.awaitMods(client, "failed")
                assertEquals(1L, mods.getValue("handoverDropped").jsonPrimitive.long)
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
    fun `the successor reaps the recorder left by a killed daemon`() = runBlocking {
        ModHandoverTestRig(root).use { rig ->
            rig.installRecorder(startRunner = false)
            val orphan = rig.killRetiringOwner()
            try {
                rig.recoverRecorder()
                val successor = rig.successorRecorderStart()
                assertFalse(successor.oldProcessAlive)
                assertFalse(orphan.isAlive)
            } finally {
                if (orphan.isAlive) orphan.destroyForcibly()
            }
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
