package splice.app.mods

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.mods.handover.ModHandover
import java.nio.file.Path

class ModHandoverTest {
    @TempDir lateinit var root: Path

    @Test
    fun `a turn spanning handover reaches a mod once and in order`() = runBlocking {
        ModHandoverTestRig(root).use { rig ->
            rig.installRecorder()
            val turn = rig.startTurn("session")
            turn.requestSent()
            rig.through("request.send")

            rig.retire()
            turn.responseReceived()
            turn.streamEvent()
            turn.toolCall()
            turn.complete()
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
            assertEquals(1, events.drop(1).map { it.payload["turnId"] }.toSet().size)
            assertTrue(events.last().payload.containsKey("turnId"))
        }
    }

    @Test
    fun `events dropped during handover are visible in the mods route`() = runBlocking {
        ModHandoverTestRig(root).use { rig ->
            rig.installRecorder()
            val turn = rig.startTurn("session")
            rig.through("turn.start")
            rig.retire(leave = false)
            repeat(ModHandover.CAPACITY + 1) { turn.streamEvent() }
            rig.leave()
            rig.finish()

            HttpClient(CIO).use { client ->
                assertEquals(HttpStatusCode.Unauthorized, client.post(rig.handoverUrl).status)
                val mods = client.get(rig.modsUrl).bodyAsText()
                assertTrue("\"handoverDropped\":1" in mods, mods)
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
