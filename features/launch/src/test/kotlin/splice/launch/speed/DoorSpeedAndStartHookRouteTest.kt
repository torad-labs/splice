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
    fun `door speed hook switches fast and ultrafast on active ChatGPT model and never hijacks Claude fast`() {
        val sessions = SessionSpeeds()
        sessions.ran("door-codex", "gpt-5.6-sol")
        sessions.ran("door-claude", "claude-opus-4-6")
        val rosters = codexRosters()
        val speeds = LaunchSpeeds(
            sessions = sessions,
            catalog = SpeedCatalog(rosters),
            chatGpt = { null },
            door = { listOf(DoorSpeedTarget("codex", "gpt-5.6-sol")) },
        )
        val route = SpeedHookRoute(launchHeadsOf(), speeds, log = {})

        val fastReply = route.decide(DOOR_HOOK_KEY, """{"session_id":"door-codex","prompt":"/fast"}""")
        assertEquals("block", fastReply["decision"]?.jsonPrimitive?.content)
        assertEquals(Speed.FAST, sessions.speedOf("door-codex"))

        val offReply = route.decide(DOOR_HOOK_KEY, """{"session_id":"door-codex","prompt":"/fast off"}""")
        assertEquals(Speed.STANDARD, sessions.speedOf("door-codex"))
        assertTrue("standard speed" in offReply["reason"]?.jsonPrimitive?.content.orEmpty())

        // Non-ChatGPT (Claude) session: /fast must pass through untouched ({}), never hijacked by Codex.
        val claudeFast = route.decide(DOOR_HOOK_KEY, """{"session_id":"door-claude","prompt":"/fast"}""")
        assertTrue(claudeFast.isEmpty(), "Claude /fast must pass through untouched: $claudeFast")

        // Brand-new door session with no recorded model yet: /fast passes through untouched.
        val freshFast = route.decide(DOOR_HOOK_KEY, """{"session_id":"door-fresh","prompt":"/fast"}""")
        assertTrue(freshFast.isEmpty(), "Unrecorded door session /fast must pass through untouched: $freshFast")

        // /ultrafast on a Claude session refuses naming the Claude model.
        val claudeUltra = route.decide(DOOR_HOOK_KEY, """{"session_id":"door-claude","prompt":"/ultrafast"}""")
        assertEquals("block", claudeUltra["decision"]?.jsonPrimitive?.content)
        assertTrue("claude-opus-4-6" in claudeUltra["reason"]?.jsonPrimitive?.content.orEmpty())
    }

    @Test
    fun `door speed hook unmatches Codex after a session switches to Claude or a non-ChatGPT provider`() {
        val sessions = SessionSpeeds()
        val speeds = LaunchSpeeds(
            sessions = sessions,
            catalog = SpeedCatalog(codexRosters()),
            chatGpt = { null },
            door = { listOf(DoorSpeedTarget("codex", "gpt-5.6-sol")) },
        )
        val route = SpeedHookRoute(launchHeadsOf(), speeds, log = {})

        sessions.ran("door-switch", "gpt-5.6-sol", provider = "codex")
        val codexFast = route.decide(DOOR_HOOK_KEY, """{"session_id":"door-switch","prompt":"/fast"}""")
        assertEquals("block", codexFast["decision"]?.jsonPrimitive?.content)
        assertEquals(Speed.FAST, sessions.speedOf("door-switch"))

        // Switching the same session to Claude unmatches Codex so Claude Code handles /fast natively.
        sessions.ran("door-switch", "claude-opus-4-6", provider = "claude")
        val afterClaude = route.decide(DOOR_HOOK_KEY, """{"session_id":"door-switch","prompt":"/fast"}""")
        assertTrue(afterClaude.isEmpty(), "switching back to Claude must pass /fast through: $afterClaude")

        // Even if another non-ChatGPT provider runs the same model slug, lastProvider prevents matching Codex.
        sessions.ran("door-switch", "gpt-5.6-sol", provider = "openai")
        val afterOpenAi = route.decide(DOOR_HOOK_KEY, """{"session_id":"door-switch","prompt":"/fast"}""")
        assertTrue(afterOpenAi.isEmpty(), "non-ChatGPT provider must pass /fast through: $afterOpenAi")
    }

    @Test
    fun `door speed hook passes through fast and refuses ultrafast when no ChatGPT provider is active`() {
        val rosters = object : HeadDiscoveredModels {
            override fun forHead(key: String): List<DiscoveredModel> = emptyList()
            override fun metadataForHead(key: String): List<DiscoveredModel> = emptyList()
        }
        val speeds = LaunchSpeeds(SessionSpeeds(), SpeedCatalog(rosters), chatGpt = { null }, door = { emptyList() })
        val route = SpeedHookRoute(launchHeadsOf(), speeds, log = {})

        val fast = route.decide(DOOR_HOOK_KEY, """{"session_id":"door-sess-2","prompt":"/fast"}""")
        assertTrue(fast.isEmpty(), "fast must pass through when no ChatGPT provider is active: $fast")

        val ultra = route.decide(DOOR_HOOK_KEY, """{"session_id":"door-sess-2","prompt":"/ultrafast"}""")
        assertEquals("block", ultra["decision"]?.jsonPrimitive?.content)
        assertTrue("none is active on the door" in ultra["reason"]?.jsonPrimitive?.content.orEmpty())
    }

    @Test
    fun `door resume prefers payload provider before door fallback and notifies ForegroundTools`() {
        val starts = mutableListOf<Triple<String, String, String>>()
        val calls = mutableListOf<ForegroundToolCall>()
        val owner = UUID.randomUUID().toString()
        val resumeRoute = ResumeHookRoute(
            heads = launchHeadsOf(),
            log = {},
            foreground = { calls += it },
            starts = SessionStarts { head, sessionId, cwd ->
                starts += Triple(head, sessionId, cwd)
                if (head == DOOR_HOOK_KEY) "bound $sessionId" else null
            },
        )
        val fgRoute = ForegroundHookRoute(launchHeadsOf(), activity = { calls += it })

        val startBody = """{"session_id":"door-sess-3","source":"startup",""" +
            """"cwd":"/work/repo","provider":"antigravity"}"""
        assertNull(resumeRoute.handle(DOOR_HOOK_KEY, startBody, owner))
        assertEquals(
            listOf(
                Triple("antigravity", "door-sess-3", "/work/repo"),
                Triple(DOOR_HOOK_KEY, "door-sess-3", "/work/repo"),
            ),
            starts,
        )

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

    @Test
    fun `door foreground hook accepts session_start phase with null tool_use_id for resumed ACP sessions`() {
        val calls = mutableListOf<ForegroundToolCall>()
        val owner = UUID.randomUUID().toString()
        val fgRoute = ForegroundHookRoute(launchHeadsOf(), activity = { calls += it })

        fgRoute.handle(
            DOOR_HOOK_KEY,
            buildJsonObject {
                put("session_id", "door-resumed")
                put("tool_use_id", JsonNull)
                put("phase", "session_start")
            },
            owner,
        )
        assertEquals(listOf(ForegroundToolPhase.SESSION_START), calls.map { it.phase })
        assertNull(calls.single().toolUseId)
        assertEquals(owner, calls.single().owner)
    }

    private fun codexRosters(): HeadDiscoveredModels = object : HeadDiscoveredModels {
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
}
