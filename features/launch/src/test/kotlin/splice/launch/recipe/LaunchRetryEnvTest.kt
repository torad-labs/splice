// NEW: the client-side retry limits, timeout and stream watchdog settings a launch plants, and the word that its
// address is Anthropic's own. Beside LaunchServiceTest's "is planted" tests, which sit at the
// size wall (detekt LargeClass 400), so this one is its own class, as ClientPickedLaunchTest is.
package splice.launch.recipe

import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.client.ClaudeConfigMaterializer
import splice.client.ClaudePolicy
import splice.launch.GatewayBearer
import splice.launch.HeadTrees
import splice.launch.LaunchGateway
import splice.launch.LaunchModels
import splice.launch.LaunchSignIn
import splice.launch.LaunchSpec
import splice.launch.launch
import java.nio.file.Path

class LaunchRetryEnvTest(@param:TempDir private val tmp: Path) {
    private val service = LaunchService(ClaudeConfigMaterializer(tmp))

    private fun spec(forwardClientAuth: Boolean) = LaunchSpec(
        trees = HeadTrees(tmp.resolve(".claude-retry")),
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
            apiTimeoutMs = 960_000,
            forwardClientAuth = forwardClientAuth,
        ),
        policy = ClaudePolicy(share = emptySet(), isolate = emptySet()),
    )

    // Nov 8-9: a subagent re-sent one request 228 times over 25 hours through splice, because each non-streaming
    // attempt hit the client's 360 s timeout and the client's limit for those retries is unlimited while this is
    // unset. 2 is the limit the client itself applies when its retry watchdog judges a failed stream outlasted the
    // timeout. Planted for EVERY head, the native one (forwardClientAuth) included, as CLAUDE_CODE_RETRY_WATCHDOG is.
    @Test
    fun `CLAUDE_CODE_NONSTREAMING_TIMEOUT_RETRIES is planted for a foreign and the native head`() {
        for (native in listOf(false, true)) {
            val env = service.launch(spec(native), emptyList(), dangerouslySkipPermissions = false).env
            assertEquals("2", env["CLAUDE_CODE_NONSTREAMING_TIMEOUT_RETRIES"], "forwardClientAuth=$native")
        }
    }

    // Nov 8-9: Claude Code cut every non-streaming request through splice at about 360 s, Bun's built-in fetch timeout
    // (measured: Bun 1.4.3 against a server that sends nothing, TimeoutError after 360363 ms with default options and
    // no timeout through 540 s with timeout: false), and re-sent it 228 times. The client passes timeout: false to
    // fetch only when its body-idle watchdog is on or this variable is falsy, so "0" leaves the head's own deadline to
    // answer a non-streaming request first. Planted for EVERY head, the native one (forwardClientAuth) included.
    @Test
    fun `API_FORCE_IDLE_TIMEOUT is planted as 0 for a foreign and the native head`() {
        for (native in listOf(false, true)) {
            val env = service.launch(spec(native), emptyList(), dangerouslySkipPermissions = false).env
            assertEquals("0", env["API_FORCE_IDLE_TIMEOUT"], "forwardClientAuth=$native")
        }
    }

    // Nov 6: a streaming turn held at its head for a usage limit showed "response stalled" and was re-sent, because the
    // client's stream watchdog gives up about 300 s plus this value after the last model event while only keepalives
    // arrive. The session's own client timeout is the wall a hold ends before, so the watchdog takes the same value.
    // Planted for EVERY head, the native one (forwardClientAuth) included.
    @Test
    fun `CLAUDE_STREAM_IDLE_TIMEOUT_MS is the session's client timeout for a foreign and the native head`() {
        for (native in listOf(false, true)) {
            val env = service.launch(spec(native), emptyList(), dangerouslySkipPermissions = false).env
            assertEquals(env["API_TIMEOUT_MS"], env["CLAUDE_STREAM_IDLE_TIMEOUT_MS"], "forwardClientAuth=$native")
        }
    }

    // A client timeout past a signed 32-bit millisecond count would fire the client's idle timer at once.
    @Test
    fun `CLAUDE_STREAM_IDLE_TIMEOUT_MS stays within the client's timer range`() {
        val base = spec(forwardClientAuth = false)
        val long = base.copy(gateway = base.gateway.copy(apiTimeoutMs = 40L * 24 * 60 * 60 * 1000))
        val env = service.launch(long, emptyList(), dangerouslySkipPermissions = false).env
        assertEquals(Int.MAX_VALUE.toString(), env["CLAUDE_STREAM_IDLE_TIMEOUT_MS"])
    }

    // The stream idle value above would also stretch the client's byte-level watchdog, which catches a path that sends
    // no byte at all, to 30 minutes. It keeps the 300 s a splice session had. Every head.
    @Test
    fun `CLAUDE_BYTE_STREAM_IDLE_TIMEOUT_MS keeps 300 s for a foreign and the native head`() {
        for (native in listOf(false, true)) {
            val env = service.launch(spec(native), emptyList(), dangerouslySkipPermissions = false).env
            assertEquals("300000", env["CLAUDE_BYTE_STREAM_IDLE_TIMEOUT_MS"], "forwardClientAuth=$native")
        }
    }

    // Nov 17, 2026: Claude Code takes any address but Anthropic's for a gateway's unless it is told otherwise, and then
    // sends its small requests on the session's main model. The usage check at startup came back a 429, shown as a
    // limit reached, in sessions started without the user settings that happened to carry the word. A head that
    // forwards the client's own login reaches Anthropic; a translated head does not, and clears the word a Claude
    // session passes down to it.
    @Test
    fun `a head that reaches Anthropic says its address is Anthropic's own, and no other head does`() {
        val word = "_CLAUDE_CODE_ASSUME_FIRST_PARTY_BASE_URL"
        val native = service.launch(spec(forwardClientAuth = true), emptyList(), dangerouslySkipPermissions = false)
        assertEquals("1", native.env[word])
        assertFalse(word in native.unset, "the launch must not clear what it plants: ${native.unset}")

        val foreign = service.launch(spec(forwardClientAuth = false), emptyList(), dangerouslySkipPermissions = false)
        assertNull(foreign.env[word], "a translated head is not Anthropic")
        assertTrue(word in foreign.unset, "one a Claude session passes down is cleared: ${foreign.unset}")
    }
}
