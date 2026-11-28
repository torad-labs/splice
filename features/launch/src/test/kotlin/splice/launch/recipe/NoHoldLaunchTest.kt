package splice.launch.recipe

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.accept
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.client.ClaudeConfigMaterializer
import splice.client.ClaudePolicy
import splice.client.desktop.DoorSession
import splice.client.wrap.ClaudeToRun
import splice.client.wrap.WrapStateRead
import splice.client.wrap.WrappedHead
import splice.core.client.CLIENT_WALL_HEADER
import splice.core.client.HOLD_BUDGET_MS
import splice.core.client.NO_HOLD_HEADER
import splice.core.util.EnvReader
import splice.http.JsonBody
import splice.launch.GatewayBearer
import splice.launch.HeadTrees
import splice.launch.LaunchAudit
import splice.launch.LaunchGateway
import splice.launch.LaunchModels
import splice.launch.LaunchOutcome
import splice.launch.LaunchRecipe
import splice.launch.LaunchSignIn
import splice.launch.LaunchSpec
import splice.launch.launchHeadsOf
import java.nio.file.Path

private const val TURN_BUDGET_PLUS_GRACE_MS = 960_000L
private const val FULL_WALL_MS = HOLD_BUDGET_MS + TURN_BUDGET_PLUS_GRACE_MS
private const val WINDOW = 400_000L
private const val DOOR_URL = "http://127.0.0.1:3096/door"
private const val KEY = "splice-terminal-secret-key-0123456789"

class NoHoldLaunchTest(@param:TempDir private val tmp: Path) {
    private fun service() = LaunchService(
        ClaudeConfigMaterializer(tmp),
        envReader = EnvReader { null },
        wrap = WrappedHead(tmp),
        wrapState = WrapStateRead { ClaudeToRun.ThroughPath },
    )

    private fun doorSpec(): DoorLaunchSpec {
        val session = DoorSession(DOOR_URL, KEY, WINDOW, "claude-gpt-5.6-sol")
        val overlay = session.overlay(marked = false, markKey = "spliceLaunch")
        return DoorLaunchSpec(session.env(), session.unset(), overlay, WINDOW, wallMs = FULL_WALL_MS)
    }

    private fun headSpec(): LaunchSpec = LaunchSpec(
        trees = HeadTrees(tmp.resolve(".claude-no-hold")),
        models = LaunchModels(
            pinnedModel = "gpt-5.6-sol",
            availableModelIds = listOf("gpt-5.6-sol"),
            modelLabels = mapOf("gpt-5.6-sol" to "gpt-5.6-sol"),
            contextWindow = 272_000,
            modelOptionsCache = buildJsonObject { },
        ),
        signIn = LaunchSignIn(loginCommand = "claudex login", signInLabel = "Codex (ChatGPT)"),
        gateway = LaunchGateway(
            statuslineCommand = "synthetic-status",
            port = 3099,
            bearer = GatewayBearer.Planted("synthetic-token"),
            apiTimeoutMs = FULL_WALL_MS,
            forwardClientAuth = false,
        ),
        policy = ClaudePolicy(share = emptySet(), isolate = emptySet()),
    )

    private fun overlayEnv(recipe: LaunchRecipe): Map<String, String> {
        val at = recipe.argv.indexOf("--settings")
        assertTrue(at > 0, "expected --settings overlay: ${recipe.argv}")
        return Json.parseToJsonElement(recipe.argv[at + 1]).jsonObject
            .getValue("env").jsonObject
            .mapValues { (_, v) -> v.jsonPrimitive.content }
    }

    @Test
    fun `DoorRecipe with noHold disables retry watchdog, strips 7d hold wall, and plants header without duplication`() {
        val caller = LaunchCaller(cwd = tmp.toString(), ambient = LaunchCallerAmbient(noHold = true))
        val custom = JsonPrimitive("$NO_HOLD_HEADER: 0\nx-team: blue")
        val settingsWithHeader = """{"env":{"ANTHROPIC_CUSTOM_HEADERS":$custom}}"""
        val outcome = service().onDoor.outcome(
            doorSpec(),
            listOf("--settings", settingsWithHeader),
            dangerouslySkipPermissions = false,
            caller = caller,
            headDirs = emptyList(),
        )
        val recipe = (outcome as LaunchOutcome.Ready).recipe
        val overlay = overlayEnv(recipe)

        for (env in listOf(recipe.env, overlay)) {
            assertEquals("0", env["CLAUDE_CODE_RETRY_WATCHDOG"])
            assertEquals(TURN_BUDGET_PLUS_GRACE_MS.toString(), env["API_TIMEOUT_MS"])
            assertEquals(TURN_BUDGET_PLUS_GRACE_MS.toString(), env["CLAUDE_STREAM_IDLE_TIMEOUT_MS"])
        }
        val headers = recipe.env.getValue("ANTHROPIC_CUSTOM_HEADERS").lines()
        assertTrue("x-team: blue" in headers, "$headers")
        assertTrue("$CLIENT_WALL_HEADER: $TURN_BUDGET_PLUS_GRACE_MS" in headers, "$headers")
        assertEquals(
            1,
            headers.count { it.substringBefore(':').trim().lowercase() == NO_HOLD_HEADER },
            "no duplicate $NO_HOLD_HEADER: $headers",
        )
        assertTrue("$NO_HOLD_HEADER: 1" in headers, "$headers")
    }

    @Test
    fun `DoorRecipe preserves caller x-splice-no-hold when unset and clears it when explicitly false`() {
        val settingsWithHeader = """{"env":{"ANTHROPIC_CUSTOM_HEADERS":${JsonPrimitive("$NO_HOLD_HEADER: 1")}}}"""

        val unsetCaller = LaunchCaller(cwd = tmp.toString(), ambient = LaunchCallerAmbient(noHold = null))
        val unsetRecipe = (
            service().onDoor.outcome(
                doorSpec(),
                listOf("--settings", settingsWithHeader),
                dangerouslySkipPermissions = false,
                caller = unsetCaller,
                headDirs = emptyList(),
            ) as LaunchOutcome.Ready
            ).recipe
        assertEquals("1", unsetRecipe.env["CLAUDE_CODE_RETRY_WATCHDOG"])
        assertEquals(FULL_WALL_MS.toString(), unsetRecipe.env["API_TIMEOUT_MS"])
        assertTrue("$NO_HOLD_HEADER: 1" in unsetRecipe.env.getValue("ANTHROPIC_CUSTOM_HEADERS").lines())

        val offCaller = LaunchCaller(cwd = tmp.toString(), ambient = LaunchCallerAmbient(noHold = false))
        val offRecipe = (
            service().onDoor.outcome(
                doorSpec(),
                listOf("--settings", settingsWithHeader),
                dangerouslySkipPermissions = false,
                caller = offCaller,
                headDirs = emptyList(),
            ) as LaunchOutcome.Ready
            ).recipe
        assertEquals("1", offRecipe.env["CLAUDE_CODE_RETRY_WATCHDOG"])
        assertEquals(FULL_WALL_MS.toString(), offRecipe.env["API_TIMEOUT_MS"])
        val offHeaders = offRecipe.env.getValue("ANTHROPIC_CUSTOM_HEADERS").lines()
        assertTrue(
            offHeaders.none { it.substringBefore(':').trim().lowercase() == NO_HOLD_HEADER },
            "explicit noHold=false strips inherited --settings $NO_HOLD_HEADER: $offHeaders",
        )
    }

    @Test
    fun `LaunchService with noHold disables retry watchdog, strips 7d hold wall, and plants header`() {
        val caller = LaunchCaller(cwd = tmp.toString(), ambient = LaunchCallerAmbient(noHold = true))
        val outcome = service().launchOutcome(
            headSpec(),
            emptyList(),
            dangerouslySkipPermissions = false,
            caller = caller,
        )
        val recipe = (outcome as LaunchOutcome.Ready).recipe
        assertEquals("0", recipe.env["CLAUDE_CODE_RETRY_WATCHDOG"])
        assertEquals(TURN_BUDGET_PLUS_GRACE_MS.toString(), recipe.env["API_TIMEOUT_MS"])
        assertEquals(TURN_BUDGET_PLUS_GRACE_MS.toString(), recipe.env["CLAUDE_STREAM_IDLE_TIMEOUT_MS"])
        val headers = recipe.env.getValue("ANTHROPIC_CUSTOM_HEADERS").lines()
        assertTrue("$CLIENT_WALL_HEADER: $TURN_BUDGET_PLUS_GRACE_MS" in headers, "$headers")
        assertTrue("$NO_HOLD_HEADER: 1" in headers, "$headers")
    }

    private suspend fun withLaunchServer(block: suspend (Int, HttpClient) -> Unit) {
        val launchService = service().apply {
            door = object : ClaudeDoor {
                override suspend fun launch() = doorSpec()
                override fun onDoor() = true
            }
        }
        val audit = object : LaunchAudit {
            override fun launched(key: String, argv: List<String>) = Unit
            override fun warned(message: String) = Unit
            override fun refused(key: String, reason: String) = Unit
        }
        val route = LaunchRoutes(launchHeadsOf(), launchService, audit, JsonBody())
        val server = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
            routing { post("/launch/{head}") { route.launch(call) } }
        }.start()
        try {
            val port = server.engine.resolvedConnectors().single().port
            HttpClient(CIO).use { client -> block(port, client) }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `LaunchRoutes parses noHold in form launch requests`() = runTest {
        withLaunchServer { port, client ->
            val formOn = client.submitForm(
                "http://127.0.0.1:$port/launch/claude",
                Parameters.build { append("noHold", "1") },
            ) {
                accept(ContentType.parse(LaunchRecipeLines.RECIPE_MEDIA_TYPE))
            }
            assertEquals(HttpStatusCode.OK, formOn.status)
            val formOnBody = formOn.bodyAsText()
            assertTrue("E CLAUDE_CODE_RETRY_WATCHDOG=0" in formOnBody, formOnBody)
            assertTrue("$NO_HOLD_HEADER: 1" in formOnBody, formOnBody)

            val formOff = client.submitForm(
                "http://127.0.0.1:$port/launch/claude",
                Parameters.build { append("noHold", "0") },
            ) {
                accept(ContentType.parse(LaunchRecipeLines.RECIPE_MEDIA_TYPE))
            }
            assertEquals(HttpStatusCode.OK, formOff.status)
            val formOffBody = formOff.bodyAsText()
            assertTrue("E CLAUDE_CODE_RETRY_WATCHDOG=1" in formOffBody, formOffBody)
            assertFalse("$NO_HOLD_HEADER: 1" in formOffBody, formOffBody)
        }
    }

    @Test
    fun `LaunchRoutes parses noHold in JSON launch requests`() = runTest {
        withLaunchServer { port, client ->
            val jsonOn = client.post("http://127.0.0.1:$port/launch/claude") {
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                setBody("""{"noHold":"true"}""")
            }
            assertEquals(HttpStatusCode.OK, jsonOn.status)
            val jsonOnEnv = Json.parseToJsonElement(jsonOn.bodyAsText()).jsonObject
                .getValue("env").jsonObject
            assertEquals("0", jsonOnEnv.getValue("CLAUDE_CODE_RETRY_WATCHDOG").jsonPrimitive.content)
            assertEquals(
                TURN_BUDGET_PLUS_GRACE_MS.toString(),
                jsonOnEnv.getValue("API_TIMEOUT_MS").jsonPrimitive.content,
            )
            assertTrue(
                "$NO_HOLD_HEADER: 1" in jsonOnEnv.getValue("ANTHROPIC_CUSTOM_HEADERS").jsonPrimitive.content,
            )
        }
    }
}
