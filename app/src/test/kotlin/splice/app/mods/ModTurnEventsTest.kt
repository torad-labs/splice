package splice.app.mods

import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.turn.ObservedRequest
import splice.core.turn.ObservedResponse
import splice.core.turn.ObservedStreamFrame
import splice.core.turn.ObservedToolCall
import splice.core.turn.ObservedTurnEnd
import splice.core.turn.ObservedUsage
import splice.launch.LaunchModSettings
import splice.launch.ModsOff
import java.nio.file.Path

class ModTurnEventsTest {
    @TempDir lateinit var root: Path

    @Test
    fun `a streamed turn delivers ordered labels and only matching tools`() = testApplication {
        val rig = ModEventsTestRig(root)
        application { routing { rig.runner.mount.register(this) } }
        rig.runner.runner.start()
        try {
            rig.install(client, "recorder", """{ server: "notes", tool: "read" },""")
            val owner = rig.launch(settings = LaunchModSettings(labels = mapOf("ticket" to "42")))
            val turn = checkNotNull(rig.begin("work", owner))
            turn.requestSent(ObservedRequest(1, "upstream", 2))
            turn.responseReceived(ObservedResponse(200, 1, 3))
            turn.frame(ObservedStreamFrame(1, "message_start", 35, 4))
            turn.toolCall(ObservedToolCall("ignored", "mcp__notes__write", 5))
            turn.toolCall(ObservedToolCall("ignored", "mcp__elsewhere__read", 6))
            turn.toolCall(ObservedToolCall("tool-1", "mcp__notes__read", 7))
            turn.completed(ObservedTurnEnd("ok", ObservedUsage(13, 2, 3, 4), 20, 8, stopReason = "tool_use"))
            val got = rig.through("recorder", "turn.complete")
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
                got.map { it.name },
            )
            val turnId = got[1].payload.getValue("turn")
            for (event in got) assertEquals("""{"ticket":"42"}""", event.payload["labels"].toString())
            for (event in got.drop(1)) assertEquals(turnId, event.payload["turn"])
            assertEquals(""""read"""", got[5].payload["tool"].toString())
            assertEquals("1", got[2].payload["round"].toString())
            assertFalse("bytes" in got[2].payload)
            assertFalse("body" in got[2].payload)
            assertFalse("body" in got[3].payload)
            assertFalse("event" in got[4].payload)
            assertFalse("arguments" in got[5].payload)
            assertEquals(""""tool_use"""", got[6].payload["stopReason"].toString())
        } finally {
            rig.runner.runner.stop()
        }
    }

    @Test
    fun `all off silences every mod and a name list silences only those mods`() = testApplication {
        val rig = ModEventsTestRig(root)
        application { routing { rig.runner.mount.register(this) } }
        rig.runner.runner.start()
        try {
            rig.install(client, "recorder")
            rig.install(client, "other")
            val all = rig.launch(settings = LaunchModSettings(modsOff = ModsOff(all = true)))
            val some = rig.launch(
                settings = LaunchModSettings(
                    labels = mapOf("private" to "launch"),
                    modsOff = ModsOff(names = setOf("recorder")),
                ),
            )
            rig.launches.sessionStarted(all, "all-off", "/work")
            rig.complete("all-off", null)
            rig.launches.sessionStarted(some, "some-off", "/work")
            rig.complete("some-off", null)
            rig.complete("shown", null)
            val one = rig.through("recorder", "turn.complete")
            val two = rig.through("other", "turn.complete", "shown")
            assertEquals(listOf("shown", "shown", "shown"), one.map { it.session })
            assertEquals(List(3) { "some-off" } + List(3) { "shown" }, two.map { it.session })
            assertEquals(
                listOf("""{"private":"launch"}""", "{}", "{}"),
                two.take(3).map { it.payload["labels"].toString() },
            )
        } finally {
            rig.runner.runner.stop()
        }
    }
}
