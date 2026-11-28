package splice.app.mods

import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.mods.events.ModEventText
import splice.app.mods.events.ModSecretSources
import splice.core.turn.ObservedRequest
import splice.core.turn.ObservedResponse
import splice.core.turn.ObservedStreamFrame
import splice.core.turn.ObservedToolCall
import splice.core.turn.ObservedTurnEnd
import splice.core.turn.ObservedUsage
import splice.core.util.EnvReader
import splice.head.observe.ObservedTurnFacts
import splice.launch.LaunchModSettings
import splice.launch.ModsOff
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch

class ModTurnEventsTest {
    @TempDir lateinit var root: Path

    @Test
    fun `a streamed turn delivers every served field in order and only matching tools`() = testApplication {
        val rig = ModEventsTestRig(root)
        application { routing { rig.runner.mount.register(this) } }
        rig.runner.runner.start()
        try {
            rig.install(client, "recorder", """{ server: "notes", tool: "read" },""")
            val owner = rig.launch(settings = LaunchModSettings(labels = mapOf("ticket" to "42")))
            val turn = checkNotNull(rig.begin(ObservedTurnFacts("work", owner, "head", "asked", true, "reviewer", 1)))
            turn.requestSent(ObservedRequest(2, "upstream", 2))
            turn.responseReceived(ObservedResponse(201, 3, 3))
            turn.frame(ObservedStreamFrame(4, "message_start", 35, 4))
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
            assertTurnPayloads(got.drop(1))
        } finally {
            rig.runner.runner.stop()
        }
    }

    @Test
    fun `turn completion does not wait for the credential reader but the mod sees only scrubbed text`() =
        testApplication {
            val allowRead = CountDownLatch(1)
            val credential = "fixture-provider-secret"
            val envReader = EnvReader { name ->
                if (name != "FIXTURE_API_KEY") {
                    null
                } else {
                    allowRead.await()
                    credential
                }
            }
            val text = ModEventText(ModSecretSources(envReader, setOf("FIXTURE_API_KEY")))
            val rig = ModEventsTestRig(root, overrideText = text)
            application { routing { rig.runner.mount.register(this) } }
            rig.runner.runner.start()
            try {
                rig.install(client, "recorder")
                val owner = rig.launch(settings = LaunchModSettings(labels = mapOf("private" to credential)))
                val clientTurn = CompletableFuture.runAsync { rig.complete("work", owner) }
                try {
                    withTimeout(5_000) { clientTurn.await() }
                } finally {
                    allowRead.countDown()
                }
                val events = rig.through("recorder", "turn.complete")
                for (event in events) {
                    assertEquals("""{"private":"[redacted]"}""", event.payload["labels"].toString())
                }
            } finally {
                allowRead.countDown()
                rig.runner.runner.stop()
            }
        }

    private fun assertTurnPayloads(events: List<RecordedModEvent>) {
        val turn = events.first().payload.getValue("turn").jsonPrimitive
        val common = Json.parseToJsonElement("""{"session":"work","labels":{"ticket":"42"}}""").jsonObject +
            ("turn" to turn)
        val fields = listOf(
            """{"at":1,"head":"head","model":"asked","compact":true,"agent":"reviewer"}""",
            """{"at":2,"model":"upstream","round":2}""",
            """{"at":3,"model":"asked","status":201,"attempt":3}""",
            """{"at":4,"seq":4,"type":"message_start","bytes":35}""",
            """{"at":7,"caller":"model","id":"tool-1","server":"notes","tool":"read"}""",
            """{"at":8,"model":"asked","stopReason":"tool_use","outcome":"ok","durationMs":20,
                "usage":{"input":13,"output":2,"cacheRead":3,"cacheWrite":4}}""",
        )
        val expected = fields.map { JsonObject(common + Json.parseToJsonElement(it).jsonObject) }
        assertEquals(expected, events.map { it.payload })
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
