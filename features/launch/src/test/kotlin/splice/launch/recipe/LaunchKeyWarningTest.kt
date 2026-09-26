// NEW: V4-227 — a launch of an api-key head with no key names `splice key set`, which the head reads on
// its next request, and asks for no restart that fix does not need. The warning told the operator to
// export the variable and run `splice restart`: a restart that drops every head's turns in flight, for
// a key the daemon would have read without one had it been stored. A key kept in the daemon's own
// environment still needs the restart, so that path stays, second and with its reason.
package splice.launch.recipe

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.client.ClaudePolicy
import splice.core.auth.AuthDescription
import splice.core.auth.AuthProvider
import splice.launch.HeadTrees
import splice.launch.LaunchHead
import splice.launch.LaunchRecipe
import splice.launch.LaunchSpec
import splice.launch.runningHead
import java.nio.file.Path

private const val HEAD = "openrouter"
private const val ENV = "OPENROUTER_API_KEY"

class LaunchKeyWarningTest {

    private fun spec(configDir: Path) = LaunchSpec(
        trees = HeadTrees(configDir),
        pinnedModel = "m",
        availableModelIds = listOf("m"),
        modelLabels = mapOf("m" to "M"),
        contextWindow = 200_000,
        modelOptionsCache = buildJsonObject { },
        statuslineCommand = "\"/bin/curl\" -s :3096/statusline/$HEAD",
        loginCommand = "$HEAD login",
        signInLabel = "OpenRouter",
        policy = ClaudePolicy(share = emptySet(), isolate = emptySet()),
        port = 3102,
        inferenceToken = "test-token",
        apiTimeoutMs = 960_000,
        forwardClientAuth = false,
        headKey = HEAD,
    )

    private fun apiKeyHead(configDir: Path, present: Boolean, fields: Map<String, String>) = LaunchHead(
        head = runningHead(HEAD),
        auth = object : AuthProvider {
            override suspend fun credentials() = null
            override suspend fun describe() = AuthDescription(present, "api-key", fields)
        },
        spec = spec(configDir),
    )

    private val raw = LaunchRecipe(env = emptyMap(), unset = emptyList(), argv = listOf("claude"))

    private suspend fun warning(target: LaunchHead, configDir: Path): String =
        LaunchResponse().withAuthWarning(target, spec(configDir), raw).warning.orEmpty()

    /** RED before V4-227: "…is not set in the daemon's environment. Requests will fail until you export
     *  OPENROUTER_API_KEY and run: splice restart". */
    @Test
    fun `a missing key names splice key set first, with no restart for it`(@TempDir tmp: Path) = runTest {
        val text = warning(apiKeyHead(tmp, present = false, mapOf("env_var" to ENV)), tmp)

        assertTrue(text.contains("splice key set $ENV"), text)
        assertTrue(text.contains("with no restart"), text)
        assertTrue(text.indexOf("splice key set") < text.indexOf("splice restart"), "the restart-free fix leads: $text")
        assertTrue(text.contains("daemon's environment"), "the restart is only the environment path's: $text")
    }

    @Test
    fun `a file-configured head names its file and splice key set, and no restart at all`(@TempDir tmp: Path) =
        runTest {
            val fields = mapOf("env_var" to ENV, "key_file" to "/keys/openrouter.key")
            val text = warning(apiKeyHead(tmp, present = false, fields), tmp)

            assertTrue(text.contains("/keys/openrouter.key") && text.contains("splice key set $ENV"), text)
            assertTrue(!text.contains("restart:") && !text.contains("splice restart"), text)
        }

    @Test
    fun `a head with its key launches without a warning`(@TempDir tmp: Path) = runTest {
        val recipe = LaunchResponse().withAuthWarning(apiKeyHead(tmp, true, mapOf("env_var" to ENV)), spec(tmp), raw)
        assertEquals(null, recipe.warning)
    }
}
