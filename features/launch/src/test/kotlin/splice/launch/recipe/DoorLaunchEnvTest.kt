// NEW: #791 §3 and §5 (Nov 23, 2026) — what a launch of `claude` on the door hands Claude Code: what a head's launch
// sets (the client's timers and wall header, the windows, NO_PROXY), each non-secret value said again in the
// `--settings` overlay's `env` so a person's settings files cannot outrank it, every provider switch `0` and every
// other credential, tier and model option empty there, the key in the process environment alone, a caller's
// `--model` left to start the session, and a caller's own `statusLine` kept.
package splice.launch.recipe

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.client.ClaudeConfigMaterializer
import splice.client.desktop.DoorSession
import splice.client.wrap.ClaudeToRun
import splice.client.wrap.WrapStateRead
import splice.client.wrap.WrappedHead
import splice.core.util.EnvReader
import splice.launch.LaunchOutcome
import splice.launch.LaunchRecipe
import java.nio.file.Path

// why: a terminal key's shape, recognisable wherever it would leak
private const val KEY = "splice-terminal-secret-key-0123456789"

// why: a door's declared window, and a wall as a head's is (its cap plus the seven-day hold plus the grace)
private const val WINDOW = 400_000L
private const val WALL = 605_760_000L

private const val DOOR = "http://127.0.0.1:3096/door"
private const val STARTING = "claude-gpt-5.6-sol"

class DoorLaunchEnvTest(@param:TempDir private val tmp: Path) {
    private fun service(ambient: Map<String, String> = emptyMap()) = LaunchService(
        ClaudeConfigMaterializer(tmp),
        envReader = EnvReader { ambient[it] },
        wrap = WrappedHead(tmp),
        wrapState = WrapStateRead { ClaudeToRun.ThroughPath },
    )

    private fun spec(
        model: String? = STARTING,
        wall: Long? = WALL,
        door: String = DOOR,
        window: Long = WINDOW,
    ): DoorLaunchSpec {
        val session = DoorSession(door, KEY, window, model)
        val overlay = session.overlay(marked = false, markKey = "spliceLaunch")
        return DoorLaunchSpec(session.env(), session.unset(), overlay, window, wallMs = wall)
    }

    private fun launch(
        args: List<String> = emptyList(),
        ambient: Map<String, String> = emptyMap(),
        door: DoorLaunchSpec = spec(),
        noProxy: List<String> = emptyList(),
    ): LaunchRecipe {
        val caller = LaunchCaller(cwd = tmp.toString(), ambient = LaunchCallerAmbient(noProxy = noProxy))
        val outcome = service(ambient).onDoor.outcome(door, args, false, caller, emptyList())
        return (outcome as LaunchOutcome.Ready).recipe
    }

    /** The one `--settings` overlay the recipe hands Claude Code. */
    private fun overlayOf(recipe: LaunchRecipe): JsonObject {
        val at = recipe.argv.indexOf("--settings")
        assertTrue(at > 0, "the launch carries a --settings overlay: ${recipe.argv}")
        assertEquals(1, recipe.argv.count { it == "--settings" }, "one overlay")
        return Json.parseToJsonElement(recipe.argv[at + 1]).jsonObject
    }

    private fun ownerHeader(recipe: LaunchRecipe): String =
        "x-splice-launch-owner: ${recipe.env.getValue("SPLICE_FOREGROUND_OWNER")}"

    private fun overlayEnv(recipe: LaunchRecipe): Map<String, String> =
        overlayOf(recipe).getValue("env").jsonObject.mapValues { (_, value) -> value.jsonPrimitive.content }

    @Test
    fun `the client's timers come from the wall, in the environment and in the overlay`() {
        val recipe = launch()
        val overlay = overlayEnv(recipe)
        val timers = mapOf(
            "API_TIMEOUT_MS" to WALL.toString(),
            "API_FORCE_IDLE_TIMEOUT" to "0",
            "CLAUDE_CODE_RETRY_WATCHDOG" to "1",
            "CLAUDE_CODE_NONSTREAMING_TIMEOUT_RETRIES" to "2",
            "CLAUDE_STREAM_IDLE_TIMEOUT_MS" to WALL.toString(),
            "CLAUDE_BYTE_STREAM_IDLE_TIMEOUT_MS" to "300000",
        )
        for ((name, value) in timers) {
            assertEquals(value, recipe.env[name], "env $name")
            assertEquals(value, overlay[name], "overlay env $name")
        }
    }

    @Test
    fun `the recipe's custom headers are splice's own, never the daemon's environment, and never in the overlay`() {
        val daemonOwn = mapOf("ANTHROPIC_CUSTOM_HEADERS" to "x-corp-route: blue\nx-splice-client-wall-ms: 1")
        val recipe = launch(ambient = daemonOwn)
        assertEquals(
            listOf("x-splice-client-wall-ms: $WALL", ownerHeader(recipe)),
            recipe.env.getValue("ANTHROPIC_CUSTOM_HEADERS").lines(),
        )
        // the caller's headers can carry secrets: the launcher puts them before these in the shell (ShimHeadersTest)
        assertFalse("ANTHROPIC_CUSTOM_HEADERS" in overlayEnv(recipe))
        assertFalse("x-corp-route" in recipe.argv.joinToString(" "))
    }

    @Test
    fun `a caller's --settings headers join the launch's in the environment, never standing over the wall`() {
        val headers = "x-splice-wrapper: acme-ai/1.4.0\nx-team: blue\nx-splice-client-wall-ms: 1\n" +
            "X-Splice-Launch-Owner: outer"
        val inline = """{"env":{"ANTHROPIC_CUSTOM_HEADERS":${JsonPrimitive(headers)}}}"""
        val recipe = launch(listOf("--settings", inline))
        assertEquals(
            listOf(
                "x-splice-wrapper: acme-ai/1.4.0",
                "x-team: blue",
                "x-splice-client-wall-ms: $WALL",
                ownerHeader(recipe),
            ),
            recipe.env.getValue("ANTHROPIC_CUSTOM_HEADERS").lines(),
        )
        assertFalse("ANTHROPIC_CUSTOM_HEADERS" in overlayEnv(recipe), "the overlay never outranks the wall header")
    }

    @Test
    fun `with no wall to outlive the launch names its owner but sets no timer`() {
        val recipe = launch(door = spec(wall = null))
        assertNull(recipe.env["API_TIMEOUT_MS"])
        assertNull(overlayEnv(recipe)["API_TIMEOUT_MS"])
        assertEquals(ownerHeader(recipe), recipe.env["ANTHROPIC_CUSTOM_HEADERS"])
    }

    @Test
    fun `the door's window is the context and compaction window, set where a settings file cannot shrink it`() {
        val recipe = launch()
        val overlay = overlayEnv(recipe)
        for (env in listOf(recipe.env, overlay)) {
            assertEquals(WINDOW.toString(), env["CLAUDE_CODE_MAX_CONTEXT_TOKENS"])
            assertEquals(WINDOW.toString(), env["CLAUDE_CODE_AUTO_COMPACT_WINDOW"])
            assertEquals("85", env["CLAUDE_AUTOCOMPACT_PCT_OVERRIDE"])
        }
        assertFalse("CLAUDE_CODE_AUTO_COMPACT_WINDOW" in recipe.unset)
        assertFalse("CLAUDE_AUTOCOMPACT_PCT_OVERRIDE" in recipe.unset)
    }

    @Test
    fun `a small window compacts at a head's floor`() {
        val recipe = launch(door = spec(window = 32_000))
        assertEquals("32000", recipe.env["CLAUDE_CODE_MAX_CONTEXT_TOKENS"])
        assertEquals("60000", recipe.env["CLAUDE_CODE_AUTO_COMPACT_WINDOW"])
    }

    @Test
    fun `the session starts on the door's model, in the environment and in the overlay`() {
        val recipe = launch()
        assertEquals(STARTING, recipe.env["ANTHROPIC_MODEL"])
        assertEquals(STARTING, overlayEnv(recipe)["ANTHROPIC_MODEL"])
    }

    @Test
    fun `a caller's --model starts the session, spaced or joined, and the launch names no model beside it`() {
        for (args in listOf(listOf("--model", "opus"), listOf("--model=opus"))) {
            val recipe = launch(args)
            assertNull(recipe.env["ANTHROPIC_MODEL"], "$args")
            assertTrue("ANTHROPIC_MODEL" in recipe.unset, "$args")
            assertEquals("", overlayEnv(recipe)["ANTHROPIC_MODEL"], "$args")
            assertEquals(args, recipe.argv.takeLast(args.size))
        }
    }

    @Test
    fun `a --model that is another option's value or a positional word does not stand for the caller's model`() {
        for (args in listOf(listOf("--system-prompt", "--model"), listOf("--", "--model", "opus"))) {
            val recipe = launch(args)
            assertEquals(STARTING, recipe.env["ANTHROPIC_MODEL"], "$args")
            assertEquals(STARTING, overlayEnv(recipe)["ANTHROPIC_MODEL"], "$args")
        }
    }

    @Test
    fun `with no model in the gateway the starting model is empty in the overlay and unset`() {
        val recipe = launch(door = spec(model = null))
        assertNull(recipe.env["ANTHROPIC_MODEL"])
        assertTrue("ANTHROPIC_MODEL" in recipe.unset)
        assertEquals("", overlayEnv(recipe)["ANTHROPIC_MODEL"])
    }

    @Test
    fun `every tier, the small model and the custom model option are empty in the overlay and unset`() {
        val recipe = launch()
        val overlay = overlayEnv(recipe)
        val tiers = listOf("OPUS", "SONNET", "HAIKU", "FABLE").flatMap { tier ->
            val model = "ANTHROPIC_DEFAULT_${tier}_MODEL"
            listOf(model, "${model}_NAME", "${model}_DESCRIPTION")
        }
        val custom = listOf(
            "ANTHROPIC_SMALL_FAST_MODEL",
            "ANTHROPIC_CUSTOM_MODEL_OPTION",
            "ANTHROPIC_CUSTOM_MODEL_OPTION_NAME",
            "ANTHROPIC_CUSTOM_MODEL_OPTION_DESCRIPTION",
        )
        for (name in tiers + custom) {
            assertEquals("", overlay[name], "overlay $name")
            assertTrue(name in recipe.unset, "unset $name")
            assertNull(recipe.env[name], "env $name")
        }
    }

    @Test
    fun `every provider switch is 0 and every other credential empty in the overlay, and unset`() {
        val recipe = launch()
        val overlay = overlayEnv(recipe)
        val switches = listOf("BEDROCK", "VERTEX", "FOUNDRY", "MANTLE").map { "CLAUDE_CODE_USE_$it" }
        for (name in switches) {
            assertEquals("0", overlay[name], name)
            assertTrue(name in recipe.unset, name)
        }
        val others = listOf(
            "ANTHROPIC_API_KEY",
            "CLAUDE_CODE_OAUTH_TOKEN",
            "CLAUDE_CODE_OAUTH_REFRESH_TOKEN",
            "_CLAUDE_CODE_ASSUME_FIRST_PARTY_BASE_URL",
            "AWS_BEARER_TOKEN_BEDROCK",
            "ANTHROPIC_BEDROCK_BASE_URL",
            "ANTHROPIC_BEDROCK_MANTLE_BASE_URL",
        )
        for (name in others) {
            assertEquals("", overlay[name], name)
            assertTrue(name in recipe.unset, name)
        }
    }

    @Test
    fun `gateway mode is said in the overlay too, so a settings file cannot point the session elsewhere`() {
        val overlay = overlayEnv(launch())
        assertEquals(DOOR, overlay["ANTHROPIC_BASE_URL"])
        assertEquals("1", overlay["CLAUDE_CODE_ENABLE_GATEWAY_MODEL_DISCOVERY"])
        assertEquals("1", overlay["DISABLE_LOGIN_COMMAND"])
        assertEquals("1", overlay["DISABLE_LOGOUT_COMMAND"])
        assertEquals("0", overlay["CLAUDE_CODE_AUTO_MODE_SERVER"])
    }

    @Test
    fun `NO_PROXY keeps the caller's entries and the daemon's and adds loopback, in both spellings and both places`() {
        val caller = listOf("corp.example, 10.0.0.1", "internal.example,corp.example")
        val daemonOwn = mapOf("NO_PROXY" to "daemon.example")
        val recipe = launch(ambient = daemonOwn, noProxy = caller)
        val expected = "corp.example,10.0.0.1,internal.example,daemon.example,127.0.0.1,localhost"
        for (env in listOf(recipe.env, overlayEnv(recipe))) {
            assertEquals(expected, env["NO_PROXY"])
            assertEquals(expected, env["no_proxy"])
        }
    }

    @Test
    fun `NO_PROXY names another machine's door, which a proxy the shell names cannot reach`() {
        val recipe = launch(door = spec(door = "http://studio.lan:3097/door"))
        assertEquals("127.0.0.1,localhost,studio.lan", recipe.env["NO_PROXY"])
    }

    @Test
    fun `the key is in the process environment alone, never in the overlay`() {
        val inline = """{"env":{"ANTHROPIC_AUTH_TOKEN":"wrapper-token","OTEL_SERVICE_NAME":"acme"}}"""
        val recipe = launch(listOf("--settings", inline))
        assertEquals(KEY, recipe.env["ANTHROPIC_AUTH_TOKEN"])
        val overlay = overlayEnv(recipe)
        assertFalse("ANTHROPIC_AUTH_TOKEN" in overlay, "a caller's token never stands over the terminal's key")
        assertFalse(KEY in recipe.argv.joinToString(" "), "the key is never an argument")
        assertEquals("acme", overlay["OTEL_SERVICE_NAME"])
    }

    @Test
    fun `a caller's --settings env cannot move what the launch sets, and adds its own beside it`() {
        val inline = """{"env":{"ANTHROPIC_MODEL":"elsewhere","API_TIMEOUT_MS":"600000",""" +
            """"CLAUDE_CODE_USE_BEDROCK":"1","ANTHROPIC_DEFAULT_OPUS_MODEL":"other-opus","NO_PROXY":"",""" +
            """"OTEL_SERVICE_NAME":"acme"}}"""
        val overlay = overlayEnv(launch(listOf("--settings", inline)))
        assertEquals(STARTING, overlay["ANTHROPIC_MODEL"])
        assertEquals(WALL.toString(), overlay["API_TIMEOUT_MS"])
        assertEquals("0", overlay["CLAUDE_CODE_USE_BEDROCK"])
        assertEquals("", overlay["ANTHROPIC_DEFAULT_OPUS_MODEL"])
        assertEquals("127.0.0.1,localhost", overlay["NO_PROXY"])
        assertEquals("acme", overlay["OTEL_SERVICE_NAME"])
    }

    @Test
    fun `a caller's statusLine is kept when splice merges its overlay, inline or from a file`() {
        val status = """{"type":"command","command":"acme-ai statusline","padding":0}"""
        val file = tmp.resolve("wrapper.json")
        file.toFile().writeText("""{"statusLine":$status,"env":{"OTEL_SERVICE_NAME":"acme"}}""")
        for (args in listOf(listOf("--settings", """{"statusLine":$status}"""), listOf("--settings=$file"))) {
            val recipe = launch(args)
            val overlay = overlayOf(recipe)
            assertEquals(Json.parseToJsonElement(status), overlay["statusLine"], "$args")
            assertEquals(JsonPrimitive(WALL.toString()), overlay.getValue("env").jsonObject["API_TIMEOUT_MS"], "$args")
        }
    }
}
