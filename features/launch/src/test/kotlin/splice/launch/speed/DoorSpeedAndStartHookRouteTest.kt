package splice.launch.speed

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.client.DOOR_HOOK_KEY
import splice.core.client.ForegroundToolCall
import splice.core.client.ForegroundToolPhase
import splice.core.model.DiscoveredModel
import splice.core.model.HeadDiscoveredModels
import splice.core.model.ModelRuntimeMetadata
import splice.core.model.ServiceTier
import splice.core.speed.SessionSpeeds
import splice.core.speed.Speed
import splice.core.speed.SpeedCatalog
import splice.launch.launchHeadsOf
import splice.launch.recipe.DoorSpeedTarget
import splice.launch.recipe.LaunchSpeeds
import splice.launch.resume.ForegroundHookRoute
import splice.launch.resume.ResumeHookRoute
import splice.launch.resume.SessionStarts
import java.util.UUID

class DoorSpeedAndStartHookRouteTest {

    @Test
    fun `door speed hook switches fast and ultrafast against active ChatGPT provider`() {
        val sessions = SessionSpeeds()
        val rosters = object : HeadDiscoveredModels {
            override fun forHead(key: String): List<DiscoveredModel> = metadataForHead(key)
            override fun metadataForHead(key: String): List<DiscoveredModel> = if (key == "codex") {
                listOf(
                    DiscoveredModel(
                        id = "gpt-5.6-sol",
                        runtimeMetadata = ModelRuntimeMetadata(
                            serviceTiers = listOf(
                                ServiceTier(id = "priority", name = "Fast", description = "2x speed"),
                                ServiceTier(id = "ultrafast", name = "Ultrafast", description = "4x speed"),
                            ),
                        ),
                    ),
                )
            } else {
                emptyList()
            }
        }
        val speeds = LaunchSpeeds(
            sessions = sessions,
            catalog = SpeedCatalog(rosters),
            chatGpt = { null },
            door = { listOf(DoorSpeedTarget("codex", "gpt-5.6-sol")) },
        )
        val route = SpeedHookRoute(launchHeadsOf(), speeds, log = {})

        val fastReply = route.decide(DOOR_HOOK_KEY, """{"session_id":"door-sess-1","prompt":"/fast"}""")
        assertEquals("block", fastReply["decision"]?.jsonPrimitive?.content)
        assertEquals(Speed.FAST, sessions.speedOf("door-sess-1"))
        assertTrue("standard speed" in fastReply["reason"]?.jsonPrimitive?.content.orEmpty())

        val offReply = route.decide(DOOR_HOOK_KEY, """{"session_id":"door-sess-1","prompt":"/fast off"}""")
        assertEquals(Speed.STANDARD, sessions.speedOf("door-sess-1"))
        assertTrue("standard speed" in offReply["reason"]?.jsonPrimitive?.content.orEmpty())
    }

    @Test
    fun `door speed hook refuses cleanly when no ChatGPT provider is active`() {
        val rosters = object : HeadDiscoveredModels {
            override fun forHead(key: String): List<DiscoveredModel> = emptyList()
            override fun metadataForHead(key: String): List<DiscoveredModel> = emptyList()
        }
        val speeds = LaunchSpeeds(SessionSpeeds(), SpeedCatalog(rosters), chatGpt = { null }, door = { emptyList() })
        val route = SpeedHookRoute(launchHeadsOf(), speeds, log = {})

        val reply = route.decide(DOOR_HOOK_KEY, """{"session_id":"door-sess-2","prompt":"/fast"}""")
        assertEquals("block", reply["decision"]?.jsonPrimitive?.content)
        assertTrue("none is active on the door" in reply["reason"]?.jsonPrimitive?.content.orEmpty())
    }

    @Test
    fun `door resume and foreground routes notify SessionStarts and ForegroundTools without a head tree`() {
        val starts = mutableListOf<Triple<String, String, String>>()
        val calls = mutableListOf<ForegroundToolCall>()
        val owner = UUID.randomUUID().toString()
        val resumeRoute = ResumeHookRoute(
            heads = launchHeadsOf(),
            log = {},
            foreground = { calls += it },
            starts = SessionStarts { head, sessionId, cwd ->
                starts += Triple(head, sessionId, cwd)
                "bound $sessionId"
            },
        )
        val fgRoute = ForegroundHookRoute(launchHeadsOf(), activity = { calls += it })

        val startBody = """{"session_id":"door-sess-3","source":"startup","cwd":"/work/repo"}"""
        assertNull(resumeRoute.handle(DOOR_HOOK_KEY, startBody, owner))
        assertEquals(listOf(Triple(DOOR_HOOK_KEY, "door-sess-3", "/work/repo")), starts)

        fgRoute.handle(
            DOOR_HOOK_KEY,
            buildJsonObject {
                put("session_id", "door-sess-3")
                put("tool_use_id", "tool-1")
                put("phase", "start")
            },
            owner,
        )
        fgRoute.handle(
            DOOR_HOOK_KEY,
            buildJsonObject {
                put("session_id", "door-sess-3")
                put("tool_use_id", JsonNull)
                put("phase", "session_end")
            },
            owner,
        )
        assertEquals(
            listOf(ForegroundToolPhase.SESSION_START, ForegroundToolPhase.START, ForegroundToolPhase.SESSION_END),
            calls.map { it.phase },
        )
    }
}
